package com.yanzhong.app.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.ResetReq
import com.yanzhong.app.data.remote.TokenStore
import com.yanzhong.app.data.remote.apiErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PasswordResetUiState(
    val token: String = "",
    val newPassword: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    val error: String = "",
    val success: Boolean = false
)

class PasswordResetViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(PasswordResetUiState())
    val ui: StateFlow<PasswordResetUiState> = _ui

    /**
     * 同一个 token 再进入一次不重置状态:屏幕旋转会重跑 LaunchedEffect,
     * 那时用户刚敲的密码和「已重置」结果必须留着。
     */
    fun begin(token: String) = _ui.update { if (it.token == token) it else PasswordResetUiState(token = token) }

    /** 深链弹窗关闭后清场,下一次打开不带上一轮的密码/成功文案 */
    fun reset() = _ui.update { PasswordResetUiState() }

    fun updateNew(value: String) = _ui.update { it.copy(newPassword = value) }

    fun updateConfirm(value: String) = _ui.update { it.copy(confirm = value) }

    fun submit() {
        if (_ui.value.busy) return
        val state = _ui.value
        PasswordRules.validateReset(state.newPassword, state.confirm)?.let { error ->
            _ui.update { it.copy(error = error) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = "") }
            // 这两项必须在网络调用前取:成功后 TokenStore 立刻被清空,那时再读 guid 就晚了。
            val submittedToken = state.token
            val previousGuid = runCatching { TokenStore.currentAccountGuid() }.getOrNull()
            // runCatching 只包网络调用:服务端成功后的本地清理失败,不能反过来报「重置失败」。
            runCatching {
                ApiClient.api().resetPassword(ResetReq(submittedToken, state.newPassword))
            }.fold({
                // 走到这里 token 已被服务端消费、所有会话已吊销,本地清理一律尽力而为。
                if (previousGuid != null) {
                    runCatching {
                        getApplication<YanZhongApp>().repository.clearPlanProjections(previousGuid)
                    }
                }
                runCatching { TokenStore.clearAll() }
                // 请求期间可能来了新的深链,只有仍停在同一个 token 时才写回 UI;
                // 本地会话清理已在上面无条件完成。
                _ui.update {
                    if (it.token == submittedToken) it.copy(busy = false, success = true) else it
                }
            }, { e ->
                _ui.update {
                    if (it.token == submittedToken) {
                        it.copy(busy = false, error = e.apiErrorMessage())
                    } else {
                        it
                    }
                }
            })
        }
    }
}
