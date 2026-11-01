package com.yanzhong.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeUtilsTest {
    @Test
    fun planTimeLabelCombinesSlotAndTime() {
        assertEquals("上午 08:00", TimeUtils.planTimeLabel("morning", "08:00"))
        assertEquals("下午 14:30", TimeUtils.planTimeLabel("afternoon", "14:30"))
        assertEquals("晚上 19:00", TimeUtils.planTimeLabel("evening", "19:00"))
    }

    @Test
    fun planTimeLabelFallsBackWhenPartial() {
        assertEquals("08:00", TimeUtils.planTimeLabel(null, "08:00"))
        assertEquals("上午", TimeUtils.planTimeLabel("MORNING", null))
        assertNull(TimeUtils.planTimeLabel(null, null))
        assertNull(TimeUtils.planTimeLabel("unknown", "  "))
    }

    @Test
    fun `时区偏移按指定时区计算`() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val instant = java.time.Instant.parse("2026-11-01T00:00:00Z")
        org.junit.Assert.assertEquals(480, TimeUtils.tzOffsetMinutesAt(zone, instant))
    }

    @Test
    fun `文件名时间戳紧凑且含时分`() {
        val stamp = TimeUtils.fileStamp(0L)
        org.junit.Assert.assertEquals(13, stamp.length)
        org.junit.Assert.assertTrue(stamp.matches(Regex("""\d{8}-\d{4}""")))
    }
}
