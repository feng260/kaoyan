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
}
