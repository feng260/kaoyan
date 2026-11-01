package com.yanzhong.app.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordRulesTest {
    @Test
    fun `改密拒绝过短与不一致`() {
        assertEquals("新密码至少 8 位", PasswordRules.validateChange("oldpass1", "short", "short"))
        assertEquals("两次输入的新密码不一致", PasswordRules.validateChange("oldpass1", "newpass12", "newpass13"))
        assertEquals("新密码不能和当前密码相同", PasswordRules.validateChange("samepass1", "samepass1", "samepass1"))
        assertNull(PasswordRules.validateChange("oldpass1", "newpass12", "newpass12"))
    }

    @Test
    fun `重置只校验新密码`() {
        assertEquals("新密码至少 8 位", PasswordRules.validateReset("short", "short"))
        assertNull(PasswordRules.validateReset("newpass12", "newpass12"))
    }
}
