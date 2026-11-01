package com.yanzhong.app.ui.home

import com.yanzhong.app.data.remote.CheckupOverloadDayDto
import com.yanzhong.app.data.remote.CheckupResp
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckupFormatTest {
    @Test
    fun `有逾期时标题说落后`() {
        assertEquals("有 2 项任务没跟上", CheckupFormat.headline(CheckupResp(overdueCount = 2)))
    }

    @Test
    fun `无逾期但超载时标题说过满`() {
        val c = CheckupResp(overloadedDays = listOf(CheckupOverloadDayDto("2026-03-12", 300, 180)))
        assertEquals("接下来 1 天排得有点满", CheckupFormat.headline(c))
    }

    @Test
    fun `健康时标题是节奏正常`() {
        assertEquals("计划节奏正常", CheckupFormat.headline(CheckupResp()))
    }

    @Test
    fun `没有计划任务时不显示完成率`() {
        assertEquals("本周还没有计划任务", CheckupFormat.rate(CheckupResp()))
    }

    @Test
    fun `上周没有专注时提示开始积累`() {
        assertEquals("本周已开始积累专注记录", CheckupFormat.trend(CheckupResp()))
    }

    @Test
    fun `未来负荷显示已排与容量`() {
        val c = CheckupResp(next7dPlannedMinutes = 600, next7dCapacityMinutes = 900)
        assertEquals("未来 7 天已排 600 / 900 分钟", CheckupFormat.load(c))
    }

    @Test
    fun `容量未知时标注未确认空闲时段`() {
        val c = CheckupResp(next7dPlannedMinutes = 600)
        assertEquals("未来 7 天已排 600 分钟 · 未确认空闲时段", CheckupFormat.load(c))
    }

    @Test
    fun `去调整预填按问题类型说人话`() {
        val overdue = CheckupResp(overdueCount = 2, behindMinutes = 240)
        assertEquals(
            "我最近有 2 项计划没完成（共 240 分钟），帮我重新安排到接下来的空闲时间，别把每天排太满。",
            CheckupFormat.adjustPrefill(overdue)
        )
        val overload = CheckupResp(
            overloadedDays = listOf(
                CheckupOverloadDayDto("2026-03-12", 300, 180),
                CheckupOverloadDayDto("2026-03-13", 260, 180)
            )
        )
        assertEquals("未来 7 天有 2 天超出可用时间，帮我减压并重新分配。", CheckupFormat.adjustPrefill(overload))
        assertEquals("帮我看看未来一周的计划是否合理，需要的话微调。", CheckupFormat.adjustPrefill(CheckupResp()))
    }

    @Test
    fun `上周对比文案`() {
        assertEquals("本周还没有专注记录", CheckupFormat.compare(CheckupResp()))
        assertEquals(
            "上周没有记录，本周已专注 75 分钟",
            CheckupFormat.compare(CheckupResp(weekFocusMinutes = 75))
        )
        assertEquals(
            "专注比上周多 88%",
            CheckupFormat.compare(CheckupResp(weekFocusMinutes = 75, lastWeekFocusMinutes = 40))
        )
        assertEquals(
            "专注比上周少 50%",
            CheckupFormat.compare(CheckupResp(weekFocusMinutes = 20, lastWeekFocusMinutes = 40))
        )
        assertEquals(
            "专注与上周持平",
            CheckupFormat.compare(CheckupResp(weekFocusMinutes = 40, lastWeekFocusMinutes = 40))
        )
    }
}
