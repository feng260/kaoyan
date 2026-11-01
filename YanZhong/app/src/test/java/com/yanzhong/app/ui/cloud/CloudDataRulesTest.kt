package com.yanzhong.app.ui.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDataRulesTest {
    @Test
    fun `结构完整时解析成功`() {
        val result = CloudDataRules.parseBackup(
            """{"version":2,"subjects":[],"tasks":[],"sessions":[]}"""
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `缺列表时给出可读原因`() {
        val result = CloudDataRules.parseBackup("""{"version":2,"subjects":[]}""")
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("tasks"))
    }

    @Test
    fun `损坏的 JSON 给出中文原因`() {
        val result = CloudDataRules.parseBackup("这不是 JSON {{{")
        assertTrue(result.isFailure)
        assertEquals("备份文件格式不正确", result.exceptionOrNull()?.message)
    }

    @Test
    fun `确认词必须完全一致`() {
        assertTrue(CloudDataRules.canConfirm("恢复"))
        assertTrue(CloudDataRules.canConfirm(" 恢复 "))
        assertFalse(CloudDataRules.canConfirm("恢复数据"))
        assertFalse(CloudDataRules.canConfirm(""))
    }

    @Test
    fun `超过上限拒绝`() {
        assertTrue(CloudDataRules.validateSize(CloudDataRules.MAX_BACKUP_BYTES) == null)
        assertNotNull(CloudDataRules.validateSize(CloudDataRules.MAX_BACKUP_BYTES + 1))
    }
}
