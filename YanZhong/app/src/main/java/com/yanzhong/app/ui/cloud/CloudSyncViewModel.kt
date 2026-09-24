package com.yanzhong.app.ui.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.prefs.AppSettings
import com.yanzhong.app.data.remote.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class CloudUiState(
    val serverUrl: String = "",
    val loggedIn: Boolean = false,
    val username: String = "",
    val devices: List<DeviceFullDto> = emptyList(),
    val busy: Boolean = false,
    val message: String = ""
)

/**
 * 云同步页 VM:
 * - 服务器配置 → 登录/注册 → 设备管理
 * - 全量备份/恢复(新设备迁移)
 * - 增量同步状态(DataSyncer 常驻,自动推拉,此处仅手动触发+展示)
 * - 专注状态实时同步(StatusSyncClient 常驻,此处仅展示他端在线)
 */
class CloudSyncViewModel(app: Application) : AndroidViewModel(app) {
    private val json = Json { ignoreUnknownKeys = true }
    private val repo = (app as YanZhongApp).repository
    private val settingsRepo = (app as YanZhongApp).settingsRepo
    private val syncer = (app as YanZhongApp).dataSyncer
    private val _ui = MutableStateFlow(CloudUiState())
    val ui: StateFlow<CloudUiState> = _ui

    /** 增量同步:自动推拉的状态(登录后常驻,本页手动触发一轮) */
    val syncState: StateFlow<SyncState> = syncer.state

    /** 专注状态 WS:连接态 + 他端在线快照(StatusSyncClient 已常驻) */
    val wsConnected: StateFlow<Boolean> = (app as YanZhongApp).statusSync.connected
    val peers: StateFlow<List<PeerState>> = (app as YanZhongApp).statusSync.peers

    init {
        viewModelScope.launch {
            val server = TokenStore.currentServer()
            val access = TokenStore.currentAccess()
            _ui.update { it.copy(serverUrl = server ?: DEFAULT_SERVER_URL, loggedIn = access != null) }
            if (access != null) refreshDevices()
        }
    }

    fun saveServer(url: String) {
        viewModelScope.launch {
            TokenStore.saveServer(url)
            _ui.update { it.copy(serverUrl = url, message = "服务器已保存") }
        }
    }

    fun register(username: String, password: String, email: String?) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "") }
            val result = runCatching {
                ApiClient.api().register(RegisterReq(username, password, email?.takeIf { it.isNotBlank() }))
            }
            result.fold({
                _ui.update { it.copy(busy = false, message = "注册成功,请登录") }
            }, { e ->
                _ui.update { it.copy(busy = false, message = "注册失败:${e.userMessage()}") }
            })
        }
    }

    fun login(username: String, password: String, rememberMe: Boolean) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "") }
            val result = runCatching {
                ApiClient.api().login(
                    LoginReq(
                        username = username,
                        password = password,
                        deviceGuid = TokenStore.ensureDeviceGuid(),
                        deviceName = android.os.Build.MODEL ?: "Android 设备",
                        rememberMe = rememberMe
                    )
                )
            }
            result.fold({ resp ->
                TokenStore.saveTokens(resp.tokens.access, resp.tokens.refresh)
                _ui.update { it.copy(busy = false, loggedIn = true, username = resp.user.username) }
                refreshDevices()
            }, { e ->
                _ui.update { it.copy(busy = false, message = "登录失败:${e.userMessage()}") }
            })
        }
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { ApiClient.api().logout() }
            TokenStore.clearAll()
            _ui.update { it.copy(loggedIn = false, username = "", devices = emptyList()) }
        }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            runCatching { ApiClient.api().devices() }.fold({
                _ui.update { s -> s.copy(devices = it.devices) }
            }, {
                _ui.update { s -> s.copy(message = "设备列表获取失败:${it.userMessage()}") }
            })
        }
    }

    fun revokeDevice(id: Long) {
        viewModelScope.launch {
            runCatching { ApiClient.api().revokeDevice(id) }
            refreshDevices()
        }
    }

    /** 手动触发一轮增量同步(自动调度常驻,这里供用户立即执行) */
    fun syncNow() {
        viewModelScope.launch {
            runCatching { syncer.syncOnce() }
        }
    }

    /** 上传本地全量数据到云端:本地实体 → 同步行(BackupUpload) → POST /backup/restore */
    fun backupToCloud() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "") }
            runCatching {
                val payload = withContext(Dispatchers.IO) { repo.buildBackupUpload() }
                ApiClient.api().restoreBackup(payload)
            }.fold({
                _ui.update { it.copy(busy = false, message = "已备份到云端") }
            }, { e ->
                _ui.update { it.copy(busy = false, message = "备份失败:${e.userMessage()}") }
            })
        }
    }

    /** 从云端下载全量备份并覆盖本地(清空本地后按 clientGuid 重映射重插) */
    fun restoreFromCloud() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "") }
            runCatching {
                val download = ApiClient.api().backup()
                withContext(Dispatchers.IO) { repo.restoreFromBackup(download) }
            }.fold({
                _ui.update { it.copy(busy = false, message = "已从云端恢复本机数据") }
            }, { e ->
                _ui.update { it.copy(busy = false, message = "恢复失败:${e.userMessage()}") }
            })
        }
    }

    private fun Throwable.userMessage(): String {
        val retrofitHttp = this as? retrofit2.HttpException
        return when {
            retrofitHttp != null -> {
                val body = retrofitHttp.response()?.errorBody()?.string().orEmpty().take(80)
                "HTTP ${retrofitHttp.code()} $body"
            }
            else -> message ?: "未知错误"
        }
    }
}
