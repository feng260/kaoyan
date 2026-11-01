package com.yanzhong.app.ui.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 被动下线的理由只说明一次:账号页写入,登录页消费 */
object LogoutNoticeBus {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun offer(message: String?) {
        if (!message.isNullOrBlank()) _message.value = message
    }

    fun consume(): String? = _message.value.also { _message.value = null }
}
