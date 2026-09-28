package com.yanzhong.app.ui.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.remote.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudUiState(
    val serverUrl: String = "",
    val loggedIn: Boolean = false,
    val username: String = "",
    val devices: List<DeviceFullDto> = emptyList(),
    val busy: Boolean = false,
    val message: String = ""
)

/**
 * 账号页 VM:登录/注册、设备管理、退出。
 *
 * 同步是后台常驻行为(DataSyncer / StatusSyncClient),不需要用户理解或手动触发,
 * 因此这里不暴露任何"立即同步""备份恢复""专注接力"之类的入口。
 */
class CloudSyncViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as YanZhongApp).repository
    private val _ui = MutableStateFlow(CloudUiState())
    val ui: StateFlow<CloudUiState> = _ui

    init {
        viewModelScope.launch {
            val server = effectiveServerUrl()
            val access = TokenStore.currentAccess()
            _ui.update { it.copy(serverUrl = server, loggedIn = access != null) }
            if (access != null) {
                _ui.update { it.copy(username = TokenStore.currentUsername().orEmpty()) }
                refreshDevices()
            }
        }
    }

    fun register(username: String, password: String, email: String?, consentChecked: Boolean) {
        viewModelScope.launch {
            if (!consentChecked) {
                _ui.update { it.copy(message = "注册前先勾一下协议那一行") }
                return@launch
            }
            _ui.update { it.copy(busy = true, message = "") }
            val result = runCatching {
                ApiClient.api().register(
                    RegisterReq(
                        username = username,
                        password = password,
                        email = email?.takeIf { it.isNotBlank() },
                        // 服务端硬校验:少一个就是 400 LEGAL_CONSENT_REQUIRED
                        termsAccepted = true,
                        privacyAccepted = true
                    )
                )
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
                val previousAccount = TokenStore.currentAccountGuid()
                if (previousAccount != null && previousAccount != resp.user.guid) {
                    repo.clearPlanProjections(previousAccount)
                }
                TokenStore.saveAccountGuid(resp.user.guid)
                TokenStore.saveTokens(resp.tokens.access, resp.tokens.refresh)
                // 持久化用户名:"我的"页头部与同步卡需在重启后仍能显示账号
                TokenStore.saveUsername(resp.user.username)
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
            TokenStore.currentAccountGuid()?.let { repo.clearPlanProjections(it) }
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
