package com.yanzhong.app.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiUsageContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `旧服务端面谈响应没有 usage 仍可解析`() {
        val resp = json.decodeFromString<InterviewResp>(
            """{"reply":"你好","options":[],"done":false,"serverTime":1}"""
        )
        assertNull(resp.usage)
    }

    @Test
    fun `新服务端面谈 usage 解析`() {
        val resp = json.decodeFromString<InterviewResp>(
            """{"reply":"好","usage":{"promptTokens":1234,"completionTokens":567,"totalTokens":1801,"calls":2,"estimatedCalls":0}}"""
        )
        assertEquals(1801L, resp.usage?.totalTokens)
        assertEquals(2, resp.usage?.calls)
    }

    @Test
    fun `生成任务带 usage 解析`() {
        val job = json.decodeFromString<PlanGenerationJobDto>(
            """{"id":"j1","status":"done","usage":{"promptTokens":10,"completionTokens":5,"totalTokens":15,"calls":1,"estimatedCalls":0}}"""
        )
        assertEquals(15L, job.usage?.totalTokens)
    }

    @Test
    fun `用量响应缺省字段为零`() {
        val resp = json.decodeFromString<AiUsageResp>("""{}""")
        assertEquals(0L, resp.today.totalTokens)
        assertEquals(0, resp.month.calls)
        assertEquals(0, resp.byFeature.size)
    }

    @Test
    fun `用量响应分项解析`() {
        val resp = json.decodeFromString<AiUsageResp>(
            """{"today":{"totalTokens":3},"month":{"totalTokens":30},"byFeature":[{"feature":"interview","totalTokens":20,"calls":2}]}"""
        )
        assertEquals(3L, resp.today.totalTokens)
        assertEquals(30L, resp.month.totalTokens)
        assertEquals("interview", resp.byFeature[0].feature)
        assertEquals(20L, resp.byFeature[0].totalTokens)
    }
}
