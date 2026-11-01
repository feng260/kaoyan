package com.yanzhong.app.ui.auth

object PasswordRules {
    fun validateChange(old: String, new: String, confirm: String): String? = when {
        old.isEmpty() -> "请输入当前密码"
        new.length < 8 -> "新密码至少 8 位"
        new != confirm -> "两次输入的新密码不一致"
        new == old -> "新密码不能和当前密码相同"
        else -> null
    }

    fun validateReset(new: String, confirm: String): String? = when {
        new.length < 8 -> "新密码至少 8 位"
        new != confirm -> "两次输入的新密码不一致"
        else -> null
    }
}
