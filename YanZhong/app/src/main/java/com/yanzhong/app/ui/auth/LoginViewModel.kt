package com.yanzhong.app.ui.auth

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.DEFAULT_SERVER_URL
import com.yanzhong.app.data.remote.LoginReq
import com.yanzhong.app.data.remote.RegisterReq
import com.yanzhong.app.data.remote.TokenStore
import com.yanzhong.app.data.remote.effectiveServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val confirm: String = "",
    val email: String = "",
    val registerMode: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val messageIsError: Boolean = false,
    val serverUrl: String = DEFAULT_SERVER_URL
)

/** 登录和注册使用 ApiClient 的内置地址或设备上已保存的地址。 */
class LoginViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(LoginUiState())
    val ui: StateFlow<LoginUiState> = _ui

    init {
        viewModelScope.launch {
            val server = effectiveServerUrl()
            _ui.update { it.copy(serverUrl = server) }
        }
    }

    fun update(transform: (LoginUiState) -> LoginUiState) = _ui.update(transform)

    fun switchMode(register: Boolean) = _ui.update {
        if (it.registerMode == register) it else it.copy(
            registerMode = register,
            password = "",
            confirm = "",
            message = ""
        )
    }

    fun saveServer(rawUrl: String) {
        val url = rawUrl.trim().trimEnd('/')
        if (!url.matches(Regex("^https?://[^\\s/]+(?::\\d+)?(?:/.*)?$"))) {
            _ui.update { it.copy(message = "服务器地址应以 http:// 或 https:// 开头", messageIsError = true) }
            return
        }
        viewModelScope.launch {
            TokenStore.saveServer(url)
            _ui.update { it.copy(serverUrl = url, message = "服务器地址已保存", messageIsError = false) }
        }
    }

    /** 丢弃设备上保存的自定义地址,回到内置默认服务器 */
    fun resetServer() {
        viewModelScope.launch {
            TokenStore.clearServer()
            _ui.update {
                it.copy(
                    serverUrl = DEFAULT_SERVER_URL,
                    message = "已恢复默认服务器地址",
                    messageIsError = false
                )
            }
        }
    }

    fun submit() {
        val s = _ui.value
        if (s.busy) return
        val username = s.username.trim()
        if (username.isEmpty() || s.password.isEmpty()) {
            _ui.update { it.copy(message = "请输入用户名和密码", messageIsError = true) }
            return
        }
        if (s.registerMode) {
            if (username.length !in 3..32 || !username.matches(Regex("^[a-zA-Z0-9_\\u4e00-\\u9fa5]+$"))) {
                _ui.update { it.copy(message = "用户名为 3-32 位中英文、数字或下划线", messageIsError = true) }
                return
            }
            if (s.password.length < 8) {
                _ui.update { it.copy(message = "密码至少 8 位", messageIsError = true) }
                return
            }
            if (s.password != s.confirm) {
                _ui.update { it.copy(message = "两次输入的密码不一致", messageIsError = true) }
                return
            }
            if (s.email.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(s.email.trim()).matches()) {
                _ui.update { it.copy(message = "请输入正确的邮箱地址", messageIsError = true) }
                return
            }
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "") }
            if (s.registerMode) {
                runCatching {
                    ApiClient.api().register(
                        RegisterReq(username, s.password, s.email.trim().takeIf { it.isNotEmpty() })
                    )
                }.fold({
                    _ui.update {
                        it.copy(
                            busy = false,
                            registerMode = false,
                            password = "",
                            confirm = "",
                            message = "注册成功，请登录",
                            messageIsError = false
                        )
                    }
                }, { e ->
                    _ui.update { it.copy(busy = false, message = "注册失败:${e.userMessage()}", messageIsError = true) }
                })
            } else {
                runCatching {
                    val deviceGuid = TokenStore.ensureDeviceGuid()
                    ApiClient.api().login(
                        LoginReq(username, s.password, deviceGuid, Build.MODEL ?: "Android 设备", rememberMe = true)
                    )
                }.fold({ resp ->
                    TokenStore.saveTokens(resp.tokens.access, resp.tokens.refresh)
                    _ui.update { it.copy(busy = false) }
                }, { e ->
                    _ui.update { it.copy(busy = false, message = "登录失败:${e.userMessage()}", messageIsError = true) }
                })
            }
        }
    }

    private fun Throwable.userMessage(): String {
        val http = this as? retrofit2.HttpException
        return when {
            this is java.net.SocketTimeoutException -> "连接超时，请检查服务器地址和网络"
            this is java.net.ConnectException -> "无法连接服务器，请确认服务已启动"
            this is java.net.UnknownHostException -> "找不到服务器，请检查地址"
            http != null -> {
                val body = http.response()?.errorBody()?.string().orEmpty().take(80)
                "HTTP ${http.code()} $body".ifBlank { "HTTP ${http.code()}" }
            }
            else -> message ?: "未知错误"
        }
    }
}
