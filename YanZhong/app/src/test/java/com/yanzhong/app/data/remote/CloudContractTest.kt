package com.yanzhong.app.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `占用带单双周标记时能反序列化`() {
        val dto = json.decodeFromString<BriefCommitmentDto>(
            """{"weekday":3,"start":"08:00","end":"09:40","label":"人工智能","weekParity":"odd"}"""
        )
        assertEquals("odd", dto.weekParity)
    }

    @Test
    fun `老服务端不带 weekParity 时回落到 null`() {
        val dto = json.decodeFromString<BriefCommitmentDto>(
            """{"weekday":3,"start":"08:00","end":"09:40","label":"人工智能"}"""
        )
        assertEquals(null, dto.weekParity)
    }

    @Test
    fun `档案请求体带上校历第 1 周与占用的单双周`() {
        val req = ProfileReq(
            targetType = "考研", examDate = "2026-12-19", studyWindows = listOf("上午"),
            foundation = "一般", weakSubjects = listOf("数学"),
            fixedCommitments = listOf(
                BriefCommitmentDto(weekday = 3, start = "08:00", end = "09:40", label = "人工智能", weekParity = "even")
            ),
            termWeekOneStart = "2026-09-07",
            commitmentsConfirmed = true
        )
        val encoded = encodeJson.encodeToString(ProfileReq.serializer(), req)
        assertTrue(encoded, encoded.contains("\"termWeekOneStart\":\"2026-09-07\""))
        assertTrue(encoded, encoded.contains("\"weekParity\":\"even\""))
    }

    @Test
    fun `老档案响应缺 termWeekOneStart 时回落到空串`() {
        val dto = json.decodeFromString<PlanBriefDto>(
            """{"summary":"","goals":[],"constraints":[],"focus":[],"materials":[],"notes":[],"examSubjects":[],"availability":[],"fixedCommitments":[],"availabilityConfirmed":true,"commitmentsConfirmed":true}"""
        )
        assertEquals("", dto.termWeekOneStart)
    }

    @Test
    fun `没标单双周的占用不把 weekParity 发成 null`() {
        // 服务端 zod 的 .optional() 拒收 null:一旦发成 "weekParity":null,整个 PUT /profile 会 400
        val req = ProfileReq(
            targetType = "考研", examDate = "2026-12-19", studyWindows = listOf("上午"),
            foundation = "一般", weakSubjects = listOf("数学"),
            fixedCommitments = listOf(
                BriefCommitmentDto(weekday = 3, start = "08:00", end = "09:40", label = "高等数学")
            ),
            commitmentsConfirmed = true
        )
        val encoded = encodeJson.encodeToString(ProfileReq.serializer(), req)
        assertFalse(encoded, encoded.contains("weekParity"))
    }
}
