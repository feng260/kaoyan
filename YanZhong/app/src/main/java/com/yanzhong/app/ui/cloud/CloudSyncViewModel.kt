package com.yanzhong.app.ui.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.remote.*
import com.yanzhong.app.ui.auth.LogoutNoticeBus
import com.yanzhong.app.ui.auth.PasswordRules
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
    val message: String = "",
    val syncing: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastSyncError: String? = null,
    val changingPassword: Boolean = false,
    val securityError: String = ""
)

/**
 * 账号页 VM:登录/注册、同步状态与手动同步、设备管理、修改密码、退出。
 *
 * 同步本身是后台常驻行为(DataSyncer / StatusSyncClient);手动同步只是让用户
 * 主动催一轮并看到结果,失败仍由后台自动重试。
 */
class CloudSyncViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as YanZhongApp).repository
    private val _ui = MutableStateFlow(CloudUiState())
    val ui: StateFlow<CloudUiState> = _ui

    init {
        viewModelScope.launch {
            (getApplication<Application>() as YanZhongApp).dataSyncer.state.collect { s ->
                _ui.update { it.copy(syncing = s.running, lastSyncError = s.lastError) }
            }
        }
        viewModelScope.launch {
            TokenStore.observeLastSync().collect { at ->
                _ui.update { it.copy(lastSyncAt = at) }
            }
        }
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
                _ui.update {
                    it.copy(
                        busy = false,
                        message = "登录失败:${e.apiErrorMessage(unauthorized = "用户名或密码错误")}"
                    )
                }
            })
        }
    }

    /** 密码变更/全设备登出后本机也必须重新登录:清 token 与计划投影,立即停同步 */
    private suspend fun clearLocalSession(message: String) {
        val app = getApplication<Application>() as YanZhongApp
        TokenStore.currentAccountGuid()?.let { repo.clearPlanProjections(it) }
        // 账号页马上会被销毁,消息由登录页接手显示,不然用户只看到莫名其妙被踢回登录页
        LogoutNoticeBus.offer(message)
        TokenStore.clearAll()
        app.dataSyncer.stop()
        _ui.update {
            CloudUiState(serverUrl = it.serverUrl, message = message)
        }
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { ApiClient.api().logout() }
            clearLocalSession("")
        }
    }

    /** 消费一次性提示,让同样的操作重复发生时仍能再次提醒 */
    fun clearMessage() {
        _ui.update { it.copy(message = "") }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            runCatching { ApiClient.api().devices() }.fold({
                _ui.update { s -> s.copy(devices = it.devices) }
            }, {
                _ui.update { s -> s.copy(message = "设备列表获取失败:${it.apiErrorMessage()}") }
            })
        }
    }

    fun revokeDevice(id: Long) {
        viewModelScope.launch {
            runCatching { ApiClient.api().revokeDevice(id) }.fold({
                refreshDevices()
                _ui.update { s -> s.copy(message = "已登出该设备") }
            }, {
                _ui.update { s -> s.copy(message = "登出失败:${it.apiErrorMessage()}") }
            })
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            val app = getApplication<Application>() as YanZhongApp
            app.dataSyncer.syncOnce()
            val error = app.dataSyncer.state.value.lastError
            _ui.update {
                it.copy(message = if (error == null) "已同步" else "同步失败，稍后会自动重试")
            }
        }
    }

    fun changePassword(oldPassword: String, newPassword: String, confirm: String) {
        val error = PasswordRules.validateChange(oldPassword, newPassword, confirm)
        if (error != null) {
            _ui.update { it.copy(securityError = error) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(changingPassword = true, securityError = "") }
            runCatching {
                ApiClient.api().changePassword(ChangePasswordReq(oldPassword, newPassword))
            }.fold({
                clearLocalSession("密码已修改，请用新密码重新登录")
            }, { e ->
                _ui.update {
                    it.copy(
                        changingPassword = false,
                        securityError = e.apiErrorMessage(unauthorized = "当前密码不正确")
                    )
                }
            })
        }
    }

    fun revokeAllDevices() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            runCatching { ApiClient.api().revokeAllDevices() }.fold({
                clearLocalSession("已登出全部设备，请重新登录")
            }, { e ->
                _ui.update { it.copy(busy = false, message = "登出失败:${e.apiErrorMessage()}") }
            })
        }
    }

    fun clearSecurityError() {
        _ui.update { it.copy(securityError = "") }
    }
}
