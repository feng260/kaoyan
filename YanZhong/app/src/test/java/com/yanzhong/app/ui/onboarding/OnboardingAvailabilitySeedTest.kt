package com.yanzhong.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingAvailabilitySeedTest {

    @Test
    fun `只勾晚上时七天各铺一段晚上钟点`() {
        val grid = seedAvailability(setOf("晚上"), emptyMap())
        assertEquals((1..7).toList(), grid.keys.sorted())
        for (day in 1..7) {
            assertEquals(listOf(TimeWindow("19:00", "22:00")), grid[day])
        }
    }

    @Test
    fun `窗口顺序跟着时段预设走而不是 Set 迭代序`() {
        // 故意用乱序 Set 传入:结果必须是 上午 → 下午 → 晚上
        val grid = seedAvailability(linkedSetOf("晚上", "上午", "下午"), emptyMap())
        assertEquals(
            listOf(
                TimeWindow("08:30", "11:30"),
                TimeWindow("14:00", "17:30"),
                TimeWindow("19:00", "22:00")
            ),
            grid[3]
        )
    }

    @Test
    fun `全部时段都勾上时早晨到深夜五段齐全`() {
        val grid = seedAvailability(setOf("早晨", "上午", "下午", "晚上", "深夜"), emptyMap())
        assertEquals(
            listOf(
                TimeWindow("06:30", "08:00"),
                TimeWindow("08:30", "11:30"),
                TimeWindow("14:00", "17:30"),
                TimeWindow("19:00", "22:00"),
                TimeWindow("22:30", "23:59")
            ),
            grid[7]
        )
    }

    @Test
    fun `用户已经填过的网格一根手指都不动`() {
        val filled = mapOf(2 to listOf(TimeWindow("09:00", "10:00")))
        val grid = seedAvailability(setOf("上午", "下午", "晚上"), filled)
        assertEquals(filled, grid)
    }

    @Test
    fun `时段交集为空时回落到默认三段`() {
        // 第 2 步的闸门保证不会为空,这里是防御:别铺出一张空网格把人又挡回去
        val grid = seedAvailability(setOf("午休"), emptyMap())
        assertEquals(
            listOf(
                TimeWindow("08:30", "11:30"),
                TimeWindow("14:00", "17:30"),
                TimeWindow("19:00", "22:00")
            ),
            grid[1]
        )
        assertEquals(7, grid.size)
    }

    @Test
    fun `铺出来的每段窗口都符合校验器的格式与先后要求`() {
        // addAvailability 用 normalizeClock 校验 HH:mm,且要求 from < to;
        // 铺出来的窗口必须天然满足,否则用户点下一步照样被拦
        val grid = seedAvailability(setOf("早晨", "上午", "下午", "晚上", "深夜"), emptyMap())
        val clock = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        grid.values.flatten().forEach { window ->
            assertTrue(window.start, clock.matches(window.start))
            assertTrue(window.end, clock.matches(window.end))
            assertTrue("${window.start} 应早于 ${window.end}", window.start < window.end)
        }
    }
}
