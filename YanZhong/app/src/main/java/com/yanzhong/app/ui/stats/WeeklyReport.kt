package com.yanzhong.app.ui.stats

import com.yanzhong.app.data.db.PomodoroSessionEntity
import java.time.LocalDate
import java.time.ZoneId

data class WeeklyReport(
    val weekStart: LocalDate,
    val dailyMinutes: List<Int>,
    val totalMinutes: Int,
    val activeDays: Int,
    val subjectMinutes: Map<Long, Int>,
    val peakHour: Int?,
    val goalMinutes: Int,
    val goalConfigured: Boolean,
) {
    val isEmpty: Boolean
        get() = totalMinutes == 0

    val goalProgress: Float
        get() = if (!goalConfigured) 0f else (totalMinutes.toFloat() / goalMinutes).coerceAtMost(1f)
}

fun buildWeeklyReport(
    sessions: List<PomodoroSessionEntity>,
    weekStart: LocalDate,
    goalMinutes: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): WeeklyReport {
    val weekEnd = weekStart.plusDays(7)
    val valid = sessions.filter { session ->
        session.valid &&
            !java.time.Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate().isBefore(weekStart) &&
            java.time.Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate().isBefore(weekEnd)
    }
    val daily = (0L..6L).map { offset ->
        valid.filter { session ->
            java.time.Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate() == weekStart.plusDays(offset)
        }.sumOf { it.durationMin }
    }
    val subjects = valid
        .filter { it.subjectId != null }
        .groupingBy { it.subjectId!! }
        .fold(0) { total, session -> total + session.durationMin }
    val hours = valid.groupingBy {
        java.time.Instant.ofEpochMilli(it.startedAt).atZone(zone).hour
    }.fold(0) { total, session -> total + session.durationMin }

    return WeeklyReport(
        weekStart = weekStart,
        dailyMinutes = daily,
        totalMinutes = valid.sumOf { it.durationMin },
        activeDays = daily.count { it > 0 },
        subjectMinutes = subjects,
        peakHour = hours.maxByOrNull { it.value }?.key,
        goalMinutes = goalMinutes.coerceAtLeast(0),
        goalConfigured = goalMinutes > 0,
    )
}
