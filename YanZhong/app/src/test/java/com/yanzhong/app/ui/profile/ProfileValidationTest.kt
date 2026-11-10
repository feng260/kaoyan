package com.yanzhong.app.ui.profile

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileValidationTest {
    @Test fun `昵称空通过超32码点拒绝`() {
        assertNull(validateNickname(null))
        assertNull(validateNickname("小明"))
        assertNotNull(validateNickname("字".repeat(33)))
    }
    @Test fun `签名超100码点拒绝`() {
        assertNull(validateSignature("一战成硕"))
        assertNotNull(validateSignature("字".repeat(101)))
    }
    @Test fun `性别枚举校验`() {
        assertNull(validateGender(null)); assertNull(validateGender("secret"))
        assertNotNull(validateGender("unknown"))
    }
    @Test fun `生日格式真实范围校验`() {
        assertNull(validateBirthday(null, "2026-11-09"))
        assertNull(validateBirthday("2003-05-01", "2026-11-09"))
        assertNotNull(validateBirthday("2026-02-30", "2026-11-09"))
        assertNotNull(validateBirthday("2027-01-01", "2026-11-09"))
        assertNotNull(validateBirthday("1899-12-31", "2026-11-09"))
    }
    @Test fun `预设与自定义互斥`() {
        assertNull(avatarConflict("fox", null))
        assertNull(avatarConflict(null, "base64"))
        assertNotNull(avatarConflict("fox", "base64"))
    }
}