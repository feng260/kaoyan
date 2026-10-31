package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.PlanItemDto
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanAgendaTest {
    private fun item(
        id: Long,
        title: String,
        minutes: Int = 50,
        slot: String? = null,
        startTime: String? = null,
        sortOrder: Int = id.toInt()
    ) = PlanItemDto(
        id = id,
        subject = "政治",
        title = title,
        planDate = "2026-10-31",
        minutes = minutes,
        sortOrder = sortOrder,
        slot = slot,
        startTime = startTime
    )

    @Test
    fun derivesExactStartEndFromStartTimeAndMinutes() {
        val agenda = buildDayAgenda(listOf(item(1, "毛中特", minutes = 50, slot = "morning", startTime = "08:30")))
        assertEquals("08:30–09:20", agenda.slots.single().items.single().timeRange)
        assertEquals("08:30–09:20", agenda.slots.single().rangeLabel)
        assertEquals("08:30–09:20", agenda.rangeLabel)
    }

    @Test
    fun groupsBySlotInCanonicalOrderAndSortsByStartTime() {
        val agenda = buildDayAgenda(
            listOf(
                item(1, "晚上复盘", slot = "evening", startTime = "19:00"),
                item(2, "下午阅读", slot = "afternoon", startTime = "14:00"),
                item(3, "上午第二项", slot = "morning", startTime = "09:30", sortOrder = 1),
                item(4, "上午第一项", slot = "morning", startTime = "08:30", sortOrder = 2)
            )
        )
        assertEquals(listOf("上午", "下午", "晚上"), agenda.slots.map { it.label })
        assertEquals(listOf("上午第一项", "上午第二项"), agenda.slots.first().items.map { it.item.title })
    }

    @Test
    fun computesSlotSpanAndBreakBetweenTasks() {
        val agenda = buildDayAgenda(
            listOf(
                item(1, "第一段", minutes = 50, slot = "morning", startTime = "08:30"),
                item(2, "第二段", minutes = 45, slot = "morning", startTime = "09:30")
            )
        )
        val morning = agenda.slots.single()
        assertEquals(95, morning.minutes)
        assertEquals("08:30–10:15", morning.rangeLabel)
        assertEquals(10, morning.breakMinutes)
        assertEquals(95, agenda.totalMinutes)
    }

    @Test
    fun infersSlotFromStartHourWhenServerSlotIsMissing() {
        val agenda = buildDayAgenda(
            listOf(
                item(1, "早读", startTime = "08:00"),
                item(2, "下午课", startTime = "14:00"),
                item(3, "晚自习", startTime = "19:00")
            )
        )
        assertEquals(listOf("上午", "下午", "晚上"), agenda.slots.map { it.label })
        assertEquals(3, agenda.scheduledSlotCount)
    }

    @Test
    fun keepsUntimedItemsInUnscheduledGroup() {
        val agenda = buildDayAgenda(
            listOf(
                item(1, "有时刻", minutes = 40, slot = "morning", startTime = "08:30"),
                item(2, "没时刻", minutes = 30)
            )
        )
        assertEquals(listOf("上午", "未排时段"), agenda.slots.map { it.label })
        assertNull(agenda.slots.last().rangeLabel)
        assertEquals(70, agenda.totalMinutes)
    }

    @Test
    fun slotWithoutStartTimeHasNoBreakEstimate() {
        val agenda = buildDayAgenda(listOf(item(1, "只知时段", minutes = 30, slot = "afternoon")))
        val slot = agenda.slots.single()
        assertNull(slot.rangeLabel)
        assertNull(slot.breakMinutes)
        assertEquals(30, slot.minutes)
        assertEquals(false, agenda.hasTimes)
    }

    @Test
    fun acceptsSingleDigitHourAndZeroDuration() {
        val agenda = buildDayAgenda(
            listOf(
                item(1, "宽松格式", minutes = 60, slot = "morning", startTime = "8:30"),
                item(2, "无时长", minutes = 0, slot = "morning", startTime = "10:00")
            )
        )
        val morning = agenda.slots.single()
        assertEquals("08:30–09:30", morning.items.first().timeRange)
        assertEquals("10:00", morning.items.last().timeRange)
    }

    @Test
    fun formatsDurationsInChinese() {
        assertEquals("0 分钟", formatMinutesCn(0))
        assertEquals("50 分钟", formatMinutesCn(50))
        assertEquals("1 小时", formatMinutesCn(60))
        assertEquals("4 小时 10 分钟", formatMinutesCn(250))
    }

    @Test
    fun formatsWeekdayForSelectedDate() {
        assertEquals("周六", weekdayLabel(LocalDate.parse("2026-10-31")))
        assertEquals("周日", weekdayLabel(LocalDate.parse("2026-11-01")))
    }
}
