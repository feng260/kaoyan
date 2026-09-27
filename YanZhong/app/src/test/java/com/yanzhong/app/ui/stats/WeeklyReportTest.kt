package com.yanzhong.app.ui.stats

import com.yanzhong.app.data.db.PomodoroSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WeeklyReportTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val monday = LocalDate.of(2026, 9, 21)

    @Test
    fun aggregatesMondayToSundayAndFillsMissingDays() {
        val sessions = listOf(
            session(monday, 30, subjectId = 1, hour = 9),
            session(monday.plusDays(2), 45, subjectId = 2, hour = 15),
            session(monday.plusDays(6), 25, subjectId = 1, hour = 15),
            session(monday.plusDays(1), 60, subjectId = 1, hour = 9, valid = false),
        )

        val report = buildWeeklyReport(sessions, monday, goalMinutes = 120, zone = zone)

        assertEquals(listOf(30, 0, 45, 0, 0, 0, 25), report.dailyMinutes)
        assertEquals(100, report.totalMinutes)
        assertEquals(3, report.activeDays)
        assertEquals(mapOf(1L to 55, 2L to 45), report.subjectMinutes)
        assertEquals(15, report.peakHour)
        assertTrue(report.goalConfigured)
        assertFalse(report.isEmpty)
    }

    @Test
    fun emptyWeekReportsNoDataAndUnconfiguredGoal() {
        val report = buildWeeklyReport(emptyList(), monday, goalMinutes = 0, zone = zone)

        assertEquals(List(7) { 0 }, report.dailyMinutes)
        assertEquals(0, report.totalMinutes)
        assertEquals(0, report.activeDays)
        assertTrue(report.subjectMinutes.isEmpty())
        assertEquals(null, report.peakHour)
        assertFalse(report.goalConfigured)
        assertTrue(report.isEmpty)
    }

    private fun session(
        date: LocalDate,
        duration: Int,
        subjectId: Long,
        hour: Int,
        valid: Boolean = true,
    ) = PomodoroSessionEntity(
        taskId = null,
        subjectId = subjectId,
        startedAt = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
        endedAt = date.atTime(hour, 0).plusMinutes(duration.toLong()).atZone(zone).toInstant().toEpochMilli(),
        durationMin = duration,
        valid = valid,
    )
}
