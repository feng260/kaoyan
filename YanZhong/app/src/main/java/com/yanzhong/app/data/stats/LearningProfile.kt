package com.yanzhong.app.data.stats

import com.yanzhong.app.data.db.PomodoroSessionEntity
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.timer.streakDays
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class LearningProfile(
    val totalFocusMin: Int = 0,
    val activeDays: Int = 0,
    val streakDays: Int = 0,
    val perSubject: List<SubjectFocus> = emptyList(),
    val peakHour: Int? = null,
    val recentDaily: List<DailyFocus> = emptyList()
)

data class SubjectFocus(
    val subjectId: Long,
    val name: String,
    val colorArgb: Long,
    val totalFocusMin: Int
)

data class DailyFocus(
    val date: LocalDate,
    val totalFocusMin: Int
)

private const val UNCATEGORIZED_ID = Long.MIN_VALUE
private const val UNCATEGORIZED_COLOR = 0xFF9E9E9E

fun buildLearningProfile(
    sessions: List<PomodoroSessionEntity>,
    subjects: List<SubjectEntity>,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault()
): LearningProfile {
    val valid = sessions.filter { it.valid }
    val dates = valid.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
    val today = now.atZone(zone).toLocalDate()
    val subjectMap = subjects.associateBy { it.id }
    val subjectSums = valid.groupingBy { it.subjectId ?: UNCATEGORIZED_ID }
        .fold(0) { total, session -> total + session.durationMin }
    val perSubject = subjectSums.map { (id, total) ->
        val subject = subjectMap[id]
        SubjectFocus(
            subjectId = id,
            name = subject?.name ?: "未分类",
            colorArgb = subject?.colorArgb ?: UNCATEGORIZED_COLOR,
            totalFocusMin = total
        )
    }.sortedWith(compareByDescending<SubjectFocus> { it.totalFocusMin }.thenBy { it.name })
    val hourlySums = valid.groupingBy {
        Instant.ofEpochMilli(it.startedAt).atZone(zone).hour
    }.fold(0) { total, session -> total + session.durationMin }
    val dailySums = valid.groupingBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        .fold(0) { total, session -> total + session.durationMin }
    val recentDaily = (6L downTo 0L).map { today.minusDays(it) }
        .map { date -> DailyFocus(date, dailySums[date] ?: 0) }

    return LearningProfile(
        totalFocusMin = valid.sumOf { it.durationMin },
        activeDays = dates.toSet().size,
        streakDays = streakDays(dates.map { it.toString() }, today),
        perSubject = perSubject,
        peakHour = hourlySums.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key,
        recentDaily = recentDaily
    )
}
