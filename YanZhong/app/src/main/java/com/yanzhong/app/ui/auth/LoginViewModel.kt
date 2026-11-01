package com.yanzhong.app.ui.auth

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.DEFAULT_SERVER_URL
import com.yanzhong.app.data.remote.ForgotReq
import com.yanzhong.app.data.remote.LoginReq
import com.yanzhong.app.data.remote.RegisterReq
import com.yanzhong.app.data.remote.TokenStore
import com.yanzhong.app.data.remote.apiErrorMessage
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
    val serverUrl: String = DEFAULT_SERVER_URL,
    /**
     * 注册时必须勾选,登录不需要。默认不勾——同意这种事不能被默认选中,
     * 那是替用户做决定,也是最容易被投诉的写法。
     */
    val consentChecked: Boolean = false,
    val forgotMode: Boolean = false,
    val forgotAccount: String = "",
    val forgotBusy: Boolean = false,
    val forgotMessage: String = "",
    val emailResetAvailable: Boolean? = null
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
        // 能力探测:决定「忘记密码」发送后给用户哪种解释文案
        viewModelScope.launch {
            val available = runCatching { ApiClient.api().health() }.getOrNull()
                ?.features?.emailReset
            if (available != null) _ui.update { it.copy(emailResetAvailable = available) }
        }
        // 退出/被踢回登录页的这一跳只发生一次:顺手清掉上次提交留在输入框里的密码。
        // 用登录态而不是 LoginScreen 的 LaunchedEffect——后者每次 Activity 重建都会跑,
        // 用户转屏就会把正在输入的密码抹掉。
        viewModelScope.launch {
            (getApplication<Application>() as YanZhongApp).authState.collect { signedIn ->
                if (signedIn == false) {
                    val notice = LogoutNoticeBus.consume()
                    _ui.update { it.copy(password = "", message = notice.orEmpty(), messageIsError = false) }
                }
            }
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

    fun openForgot() {
        _ui.update {
            it.copy(
                forgotMode = true,
                forgotAccount = it.username,
                forgotBusy = false,
                forgotMessage = ""
            )
        }
    }

    fun closeForgot() = _ui.update { it.copy(forgotMode = false, forgotMessage = "") }

    fun updateForgotAccount(value: String) = _ui.update { it.copy(forgotAccount = value) }

    fun submitForgot() {
        if (_ui.value.forgotBusy) return
        val account = _ui.value.forgotAccount.trim()
        if (account.isEmpty()) {
            _ui.update { it.copy(forgotMessage = "请输入用户名或邮箱") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(forgotBusy = true, forgotMessage = "") }
            runCatching { ApiClient.api().forgotPassword(ForgotReq(account)) }.fold({
                // 服务端有意不透露账号是否存在;这里也只给统一结果,
                // 文案按「服务器是否配置邮件」分三档,能力未知时不替服务器下结论。
                val message = when (_ui.value.emailResetAvailable) {
                    true -> "如果这个账号绑定了邮箱，重置链接已经发出，30 分钟内有效。"
                    false -> "已提交。当前服务器未配置邮件发送，请联系管理员获取重置链接；链接 30 分钟内有效。"
                    null -> "已提交。若账号绑定了邮箱，重置链接会发出，30 分钟内有效；未收到请联系管理员。"
                }
                _ui.update { it.copy(forgotBusy = false, forgotMessage = message) }
            }, { e ->
                _ui.update { it.copy(forgotBusy = false, forgotMessage = "提交失败:${e.apiErrorMessage()}") }
            })
        }
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
            if (!s.consentChecked) {
                _ui.update { it.copy(message = "先勾一下上面那行,我们才好把数据存到服务器", messageIsError = true) }
                return
            }
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "") }
            if (s.registerMode) {
                runCatching {
                    ApiClient.api().register(
                        RegisterReq(
                            username = username,
                            password = s.password,
                            email = s.email.trim().takeIf { it.isNotEmpty() },
                            // 服务端对这两个字段是硬校验:缺了直接 400 LEGAL_CONSENT_REQUIRED
                            termsAccepted = true,
                            privacyAccepted = true
                        )
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
                    _ui.update { it.copy(busy = false, message = "注册失败:${e.apiErrorMessage()}", messageIsError = true) }
                })
            } else {
                runCatching {
                    val deviceGuid = TokenStore.ensureDeviceGuid()
                    ApiClient.api().login(
                        LoginReq(username, s.password, deviceGuid, Build.MODEL ?: "Android 设备", rememberMe = true)
                    )
                }.fold({ resp ->
                    val previousAccount = TokenStore.currentAccountGuid()
                    if (previousAccount != null && previousAccount != resp.user.guid) {
                        (getApplication<com.yanzhong.app.YanZhongApp>()).repository.clearPlanProjections(previousAccount)
                    }
                    TokenStore.saveAccountGuid(resp.user.guid)
                    TokenStore.saveTokens(resp.tokens.access, resp.tokens.refresh)
                    // 用户名也落盘:否则「我的」页账号卡只能显示通用的"已登录"，
                    // 而通过账号页登录过的设备却能显示真实用户名，同一个 App 两副面孔。
                    TokenStore.saveUsername(resp.user.username)
                    _ui.update { it.copy(busy = false) }
                }, { e ->
                    _ui.update {
                        it.copy(
                            busy = false,
                            message = "登录失败:${e.apiErrorMessage(unauthorized = "用户名或密码错误")}",
                            messageIsError = true
                        )
                    }
                })
            }
        }
    }
}
