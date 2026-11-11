package com.yanzhong.app.ui.auth

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.DEFAULT_SERVER_URL
import com.yanzhong.app.data.remote.EmailCodeReq
import com.yanzhong.app.data.remote.ForgotReq
import com.yanzhong.app.data.remote.LoginReq
import com.yanzhong.app.data.remote.RegisterReq
import com.yanzhong.app.data.remote.ResetReq
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
    // 注册验证码与邀请码
    val emailCode: String = "",
    val inviteCode: String = "",
    val codeBusy: Boolean = false,
    val codeCountdown: Int = 0,
    // 邮箱验证码找回
    val forgotMode: Boolean = false,
    val forgotAccount: String = "",
    val forgotBusy: Boolean = false,
    val forgotMessage: String = "",
    val resetCode: String = "",
    val resetPassword: String = "",
    val resetConfirm: String = "",
    val resetBusy: Boolean = false,
    val resetCountdown: Int = 0,
    val resetSuccess: Boolean = false
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
                // 用户名不一定是邮箱,预填只会误导;让用户明确填写注册邮箱
                forgotAccount = "",
                forgotBusy = false,
                forgotMessage = "",
                resetCode = "",
                resetPassword = "",
                resetConfirm = "",
                resetBusy = false,
                resetSuccess = false
            )
        }
    }

    fun closeForgot() = _ui.update { it.copy(forgotMode = false, forgotMessage = "") }

    fun updateForgotAccount(value: String) = _ui.update { it.copy(forgotAccount = value) }

    /** 注册第一步:请求邮箱验证码;服务端按邮箱做 60s 冷却,客户端倒计时只是提前反馈 */
    fun requestRegisterCode() {
        val s = _ui.value
        val email = s.email.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _ui.update { it.copy(message = "请先填写正确的邮箱", messageIsError = true) }
            return
        }
        if (s.codeBusy || s.codeCountdown > 0) return
        viewModelScope.launch {
            _ui.update { it.copy(codeBusy = true, message = "") }
            runCatching {
                ApiClient.api().requestEmailCode(
                    EmailCodeReq(
                        email = email,
                        purpose = "register",
                        inviteCode = s.inviteCode.trim().takeIf { it.isNotEmpty() }
                    )
                )
            }.fold({
                _ui.update {
                    it.copy(codeBusy = false, message = "验证码已发送，请查收邮箱（含垃圾箱）", messageIsError = false)
                }
                startCountdown(register = true)
            }, { e ->
                _ui.update { it.copy(codeBusy = false, message = "发送失败:${e.apiErrorMessage()}", messageIsError = true) }
            })
        }
    }

    private fun startCountdown(register: Boolean) {
        viewModelScope.launch {
            for (remaining in 60 downTo 1) {
                _ui.update {
                    if (register) it.copy(codeCountdown = remaining)
                    else it.copy(resetCountdown = remaining)
                }
                kotlinx.coroutines.delay(1_000)
            }
            _ui.update {
                if (register) it.copy(codeCountdown = 0) else it.copy(resetCountdown = 0)
            }
        }
    }

    /** 找回第一步:请求重置验证码;统一文案不暴露邮箱是否注册 */
    fun requestResetCode() {
        val email = _ui.value.forgotAccount.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _ui.update { it.copy(forgotMessage = "请输入注册邮箱") }
            return
        }
        if (_ui.value.forgotBusy || _ui.value.resetCountdown > 0) return
        viewModelScope.launch {
            _ui.update { it.copy(forgotBusy = true, forgotMessage = "") }
            runCatching { ApiClient.api().forgotPassword(ForgotReq(email)) }.fold({
                _ui.update {
                    it.copy(
                        forgotBusy = false,
                        forgotMessage = "如果该邮箱已注册，验证码已发送，10 分钟内有效。"
                    )
                }
                startCountdown(register = false)
            }, { e ->
                _ui.update { it.copy(forgotBusy = false, forgotMessage = "发送失败:${e.apiErrorMessage()}") }
            })
        }
    }

    /** 找回第二步:邮箱 + 验证码 + 新密码,成功后所有会话失效 */
    fun submitReset() {
        val s = _ui.value
        if (s.resetBusy) return
        val email = s.forgotAccount.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _ui.update { it.copy(forgotMessage = "请输入注册邮箱") }
            return
        }
        if (!s.resetCode.trim().matches(Regex("^\\d{6}$"))) {
            _ui.update { it.copy(forgotMessage = "请输入 6 位验证码") }
            return
        }
        if (s.resetPassword.length < 8) {
            _ui.update { it.copy(forgotMessage = "新密码至少 8 位") }
            return
        }
        if (s.resetPassword != s.resetConfirm) {
            _ui.update { it.copy(forgotMessage = "两次输入的密码不一致") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(resetBusy = true, forgotMessage = "") }
            runCatching {
                ApiClient.api().resetPassword(ResetReq(email, s.resetCode.trim(), s.resetPassword))
            }.fold({
                runCatching { TokenStore.clearAll() }
                _ui.update {
                    it.copy(
                        resetBusy = false,
                        resetSuccess = true,
                        forgotMessage = "密码已重置，请用新密码登录",
                        password = ""
                    )
                }
            }, { e ->
                _ui.update { it.copy(resetBusy = false, forgotMessage = "重置失败:${e.apiErrorMessage()}") }
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
            if (s.email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(s.email.trim()).matches()) {
                _ui.update { it.copy(message = "请输入正确的邮箱", messageIsError = true) }
                return
            }
            if (!s.emailCode.trim().matches(Regex("^\\d{6}$"))) {
                _ui.update { it.copy(message = "请输入 6 位邮箱验证码", messageIsError = true) }
                return
            }
            if (s.inviteCode.isBlank()) {
                _ui.update { it.copy(message = "请输入邀请码", messageIsError = true) }
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
                            email = s.email.trim(),
                            code = s.emailCode.trim(),
                            inviteCode = s.inviteCode.trim(),
                            // 服务端对这两个字段是硬校验:缺了直接 400 LEGAL_CONSENT_REQUIRED;
                            // 协议留档由首启同意门(ConsentGate)负责,注册只负责带上"已同意"这个事实
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
                            emailCode = "",
                            inviteCode = "",
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
