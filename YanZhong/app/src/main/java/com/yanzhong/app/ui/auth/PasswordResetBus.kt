package com.yanzhong.app.ui.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 深链 token 只分发一次:MainActivity 写入,全局弹窗消费 */
object PasswordResetBus {
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    fun offer(token: String?) {
        if (!token.isNullOrBlank()) _token.value = token
    }

    fun consume() {
        _token.value = null
    }
}
