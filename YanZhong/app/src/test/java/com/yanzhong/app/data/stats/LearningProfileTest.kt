package com.yanzhong.app.data.stats

import com.yanzhong.app.data.db.PomodoroSessionEntity
import com.yanzhong.app.data.db.SubjectEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearningProfileTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 9, 26)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val subjects = listOf(
        SubjectEntity(id = 1, name = "数学二", colorArgb = 0xFF123456, sort = 0),
        SubjectEntity(id = 2, name = "英语二", colorArgb = 0xFF654321, sort = 1),
        SubjectEntity(id = 3, name = "政治", colorArgb = 0xFFABCDEF, sort = 2)
    )

    @Test
    fun emptyDataReturnsZeroesAndSevenDays() {
        val profile = buildLearningProfile(emptyList(), subjects, now, zone)

        assertEquals(0, profile.totalFocusMin)
        assertEquals(0, profile.activeDays)
        assertEquals(0, profile.streakDays)
        assertEquals(emptyList<SubjectFocus>(), profile.perSubject)
        assertNull(profile.peakHour)
        assertEquals(7, profile.recentDaily.size)
        assertEquals(List(7) { 0 }, profile.recentDaily.map { it.totalFocusMin })
    }

    @Test
    fun invalidSessionsAreExcludedFromAllMetrics() {
        val sessions = listOf(
            session(today, 9, 25, 1, true),
            session(today.minusDays(1), 10, 35, 2, true),
            session(today, 9, 99, 1, false)
        )

        val profile = buildLearningProfile(sessions, subjects, now, zone)

        assertEquals(60, profile.totalFocusMin)
        assertEquals(2, profile.activeDays)
        assertEquals(2, profile.streakDays)
        assertEquals(10, profile.peakHour)
    }

    @Test
    fun subjectsAndPeakHourUseTotalsAndStableOrdering() {
        val sessions = listOf(
            session(today, 9, 20, 1, true),
            session(today, 9, 20, 2, true),
            session(today, 10, 40, 2, true),
            session(today, 11, 60, null, true),
            session(today, 12, 60, 3, true)
        )

        val profile = buildLearningProfile(sessions, subjects, now, zone)

        assertEquals(listOf("政治", "未分类", "英语二", "数学二"), profile.perSubject.map { it.name })
        assertEquals(listOf(60, 60, 60, 20), profile.perSubject.map { it.totalFocusMin })
        assertEquals(11, profile.peakHour)
    }

    @Test
    fun recentDailyIsSevenDaysAscendingWithMissingDaysZero() {
        val sessions = listOf(
            session(today.minusDays(6), 8, 15, 1, true),
            session(today.minusDays(2), 8, 30, 1, true),
            session(today, 8, 45, 1, true)
        )

        val profile = buildLearningProfile(sessions, subjects, now, zone)

        assertEquals((0L..6L).map { today.minusDays(6 - it) }, profile.recentDaily.map { it.date })
        assertEquals(listOf(15, 0, 0, 0, 30, 0, 45), profile.recentDaily.map { it.totalFocusMin })
    }

    private fun session(
        date: LocalDate,
        hour: Int,
        duration: Int,
        subjectId: Long?,
        valid: Boolean
    ) = PomodoroSessionEntity(
        taskId = null,
        subjectId = subjectId,
        startedAt = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
        endedAt = date.atTime(hour, 0).atZone(zone).toInstant().plusSeconds(duration * 60L).toEpochMilli(),
        durationMin = duration,
        valid = valid
    )
}
