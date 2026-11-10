package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.UsageSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiUsageFormatTest {
    @Test
    fun `token 数字用千分位不缩写`() {
        assertEquals("0", AiUsageFormat.tokens(0))
        assertEquals("1,234", AiUsageFormat.tokens(1234))
        assertEquals("12,345,678", AiUsageFormat.tokens(12_345_678))
    }

    @Test
    fun `本轮与今日拼成一行`() {
        val line = AiUsageFormat.statusLine(
            UsageSummaryDto(totalTokens = 1234),
            UsageSummaryDto(totalTokens = 12345)
        )
        assertEquals("本轮消耗 1,234 tokens · 今日 12,345", line)
    }

    @Test
    fun `没有数据时整行隐藏`() {
        assertNull(AiUsageFormat.statusLine(null, null))
    }

    @Test
    fun `只有今日数据时不显示本轮`() {
        assertEquals("今日 12,345", AiUsageFormat.statusLine(null, UsageSummaryDto(totalTokens = 12345)))
    }

    @Test
    fun `草稿消耗显示本次总计`() {
        assertEquals("本次生成共消耗 12,345 tokens", AiUsageFormat.draftLine(UsageSummaryDto(totalTokens = 12345)))
        assertNull(AiUsageFormat.draftLine(null))
        assertNull(AiUsageFormat.draftLine(UsageSummaryDto(totalTokens = 0)))
    }

    @Test
    fun `功能标签翻成用户能看懂的模块名`() {
        assertEquals("备考面谈", AiUsageFormat.featureLabel("interview"))
        assertEquals("行程重排", AiUsageFormat.featureLabel("adjust_plan"))
        assertEquals("课表识别", AiUsageFormat.featureLabel("timetable_ocr"))
        assertEquals("custom_feature", AiUsageFormat.featureLabel("custom_feature"))
    }
}
