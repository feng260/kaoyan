package com.yanzhong.app.service

import com.yanzhong.app.data.remote.UpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test
    fun `大版本、强更或手动检查都必须提示`() {
        assertTrue(UpdatePolicy.shouldPrompt(latestCode = 9, forced = true, skippedCode = 9, manual = false))
        assertTrue(UpdatePolicy.shouldPrompt(latestCode = 9, forced = false, skippedCode = 9, manual = true))
        assertTrue(UpdatePolicy.shouldPrompt(latestCode = 10, forced = false, skippedCode = 9, manual = false))
    }

    @Test
    fun `跳过同一非强更版本后不再自动提示`() {
        assertFalse(UpdatePolicy.shouldPrompt(latestCode = 9, forced = false, skippedCode = 9, manual = false))
    }

    @Test
    fun `安装包大小按可读单位展示`() {
        assertEquals("512 B", UpdatePolicy.formatFileSize(512))
        assertEquals("2 KB", UpdatePolicy.formatFileSize(2048))
        assertEquals("5.0 MB", UpdatePolicy.formatFileSize(5L * 1024 * 1024))
    }
}
