package com.yanzhong.app.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val encodeJson = Json { encodeDefaults = true }

    @Test
    fun `旧服务端体检响应仍可解析`() {
        val resp = json.decodeFromString<CheckupResp>(
            """{"behindMinutes":30,"overdueCount":1,"suggestion":"x","serverTime":1}"""
        )
        assertEquals(30L, resp.behindMinutes)
        assertEquals(0L, resp.weekPlanTotal)
        assertTrue(resp.overloadedDays.isEmpty())
    }

    @Test
    fun `体检超载日解析日期与分钟`() {
        val resp = json.decodeFromString<CheckupResp>(
            """{"overloadedDays":[{"date":"2026-03-12","planned":300,"capacity":180}]}"""
        )
        assertEquals("2026-03-12", resp.overloadedDays[0].date)
        assertEquals(300L, resp.overloadedDays[0].planned)
        assertEquals(180L, resp.overloadedDays[0].capacity)
    }

    @Test
    fun `恢复响应保留被拒绝行明细`() {
        val resp = json.decodeFromString<RestoreResp>(
            """{"restored":true,"resources":{"tasks":{"applied":8,"skipped":1,"rejected":[{"index":0,"reasons":["bad"]}]}}}"""
        )
        assertEquals(8, resp.resources.getValue("tasks").applied)
        assertEquals(1, resp.resources.getValue("tasks").rejected.size)
    }

    @Test
    fun `备份设置缺省为空`() {
        val backup = json.decodeFromString<BackupDownload>("""{"version":2}""")
        assertTrue(backup.settings.isEmpty())
    }

    @Test
    fun `恢复载荷设置往返不丢`() {
        val upload = BackupUpload(
            subjects = emptyList(),
            countdownNodes = emptyList(),
            tasks = emptyList(),
            sessions = emptyList(),
            settings = mapOf(
                "theme" to kotlinx.serialization.json.JsonPrimitive("dark"),
                "goal" to kotlinx.serialization.json.JsonPrimitive(37)
            )
        )
        val encoded = encodeJson.encodeToString(BackupUpload.serializer(), upload)
        val decoded = json.decodeFromString<BackupDownload>(encoded)
        assertEquals(2, decoded.settings.size)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("dark"), decoded.settings.getValue("theme"))
    }
}
