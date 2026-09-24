package com.yanzhong.app.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.SubjectDuration
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.db.DayDuration
import com.yanzhong.app.data.db.HourDuration
import com.yanzhong.app.timer.streakDays
import com.yanzhong.app.util.TimeUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId

enum class StatsRange(val label: String) {
    TODAY("今日"), WEEK("本周"), MONTH("本月"), ALL("累计")
}

/** 累计专注纪录:最长单次 / 最佳单日 / 日均 / 累计天数(全量 valid 会话统计) */
data class FocusRecords(
    val longestSessionMin: Int = 0,
    val bestDayMin: Int = 0,
    val bestDayLabel: String = "",
    val avgPerDayMin: Int = 0,
    val activeDays: Int = 0,
    val totalSessions: Int = 0
)

/** 周几净专注(1=周一 … 7=周日) */
data class WeekdayStat(val weekday: Int, val totalMin: Int)

data class StatsUiState(
    val range: StatsRange = StatsRange.TODAY,
    val focusMin: Int = 0,
    val pomodoroCount: Int = 0,
    val perSubject: List<SubjectDuration> = emptyList(),
    val subjects: List<SubjectEntity> = emptyList(),
    val trend: List<DayDuration> = emptyList(),          // 近 14 天
    val hourly: List<HourDuration> = emptyList(),       // 近 30 天
    val streak: Int = 0,
    val tasksDone: Int = 0,
    val tasksOpen: Int = 0,
    val taskRateLabel: String = "--",
    val weekGoalMin: Int = 37 * 60,
    val lastWeekMin: Int = 0,
    val weekFocusDays: Int = 0,
    val lastMonthMin: Int = 0,
    val monthFocusDays: Int = 0,
    val abandoned: Int = 0,
    val abandonReasons: List<Pair<String, Int>> = emptyList(),
    val heatmap: List<DayDuration> = emptyList(),   // 近 15 周(105 天)打卡热力
    val records: FocusRecords = FocusRecords(),
    val weekday: List<WeekdayStat> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as YanZhongApp
    private val repo = container.repository

    private val _range = MutableStateFlow(StatsRange.TODAY)
    val range = _range.asStateFlow()

    private fun windowOf(range: StatsRange): Pair<Long, Long> = when (range) {
        StatsRange.TODAY -> TimeUtils.dayStartOf() to TimeUtils.dayEndOf()
        StatsRange.WEEK -> TimeUtils.weekStartOf() to TimeUtils.weekEndOf()
        StatsRange.MONTH -> TimeUtils.monthStartOf() to TimeUtils.monthEndOf()
        StatsRange.ALL -> 0L to Long.MAX_VALUE
    }

    private data class RangeData(
        val focusMin: Int,
        val pomodoroCount: Int,
        val perSubject: List<SubjectDuration>,
        val tasksDone: List<TaskEntity>,
        val tasksOpen: List<TaskEntity>
    )

    private val rangeData = _range.flatMapLatest { range ->
        val (from, to) = windowOf(range)
        val doneFlow = container.database.taskDao()
            .observeDoneBetween(from, to)
        val openFlow = when (range) {
            StatsRange.TODAY -> container.database.taskDao().observeTodayOneShot(to)
            else -> container.database.taskDao().observeDueBetween(from, to)
        }
        combine(
            repo.observeDurationMin(from, to),
            repo.observeCount(from, to),
            repo.observePerSubject(from, to),
            doneFlow,
            openFlow
        ) { duration, count, perSubject, done, open ->
            RangeData(duration, count, perSubject, done, open)
        }
    }

    private data class StatsContext(
        val subjects: List<SubjectEntity>,
        val trend: List<DayDuration>,
        val hourly: List<HourDuration>,
        val heatmap: List<DayDuration>
    )

    private val fourFlows = combine(
        repo.observeSubjects(),
        repo.observeDailyTrend(TimeUtils.daysAgoStart(13), Long.MAX_VALUE),
        repo.observeHourly(TimeUtils.daysAgoStart(29), Long.MAX_VALUE),
        repo.observeDailyTrend(TimeUtils.daysAgoStart(104), Long.MAX_VALUE)
    ) { subjects, trend, hourly, heatmap ->
        StatsContext(subjects, trend, hourly, heatmap)
    }

    private val streakFlow = repo.observeActiveDays(0)

    /** 周报/月报数据:目标分钟、上周/上月分钟(环比)、本周/本月专注天数 */
    private data class WeekReport(
        val goalMin: Int = 37 * 60,
        val lastWeekMin: Int = 0,
        val focusDays: Int = 0,
        val lastMonthMin: Int = 0,
        val monthFocusDays: Int = 0,
        val abandoned: Int = 0,
        val abandonReasons: List<Pair<String, Int>> = emptyList(),
        val records: FocusRecords = FocusRecords(),
        val weekday: List<WeekdayStat> = emptyList()
    )

    private val weekReport = combine(
        repo.observeSessions(),
        container.settingsRepo.settings,
        flow {
            while (true) {
                emit(TimeUtils.now())
                delay(3600_000L)
            }
        }
    ) { sessions, settings, now ->
        // 口径与首页/统计页统一:startedAt BETWEEN 本周;中断数仅统计本周(而非全部历史)
        val weekStart = TimeUtils.weekStartOf(now)
        val weekEnd = weekStart + 7L * 24 * 3600 * 1000 - 1
        val lastWeekStart = weekStart - 7L * 24 * 3600 * 1000
        val thisWeek = sessions.filter { it.valid && it.startedAt in weekStart..weekEnd }
        val broken = sessions.filter { !it.valid && it.startedAt in weekStart..weekEnd }
        val monthStart = TimeUtils.monthStartOf(now)
        val monthEnd = TimeUtils.monthEndOf(now)
        val lastMonthStart = TimeUtils.monthStartOf(monthStart - 1)
        val lastMonthEnd = TimeUtils.monthEndOf(monthStart - 1)
        // 累计纪录 + 周几分布(全量 valid 会话,不随 range 切换)
        val zone = ZoneId.systemDefault()
        val valid = sessions.filter { it.valid }
        val daySums = valid.groupBy { TimeUtils.dayStartOf(it.startedAt) }
            .mapValues { (_, list) -> list.sumOf { it.durationMin } }
        val bestDay = daySums.maxByOrNull { it.value }
        WeekReport(
            goalMin = settings.weeklyGoalHours * 60,
            lastWeekMin = sessions
                .filter { it.valid && it.startedAt in lastWeekStart until weekStart }
                .sumOf { it.durationMin },
            focusDays = thisWeek.map { TimeUtils.dayStartOf(it.startedAt) }.distinct().count(),
            lastMonthMin = sessions
                .filter { it.valid && it.startedAt in lastMonthStart..lastMonthEnd }
                .sumOf { it.durationMin },
            monthFocusDays = sessions
                .filter { it.valid && it.startedAt in monthStart..monthEnd }
                .map { TimeUtils.dayStartOf(it.startedAt) }.distinct().count(),
            abandoned = broken.size,
            abandonReasons = broken
                .groupingBy { it.abandonReason ?: "未填写" }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
                .take(3),
            records = FocusRecords(
                longestSessionMin = valid.maxOfOrNull { it.durationMin } ?: 0,
                bestDayMin = bestDay?.value ?: 0,
                bestDayLabel = bestDay?.key?.let { TimeUtils.formatMonthDay(it) } ?: "",
                avgPerDayMin = if (daySums.isEmpty()) 0 else valid.sumOf { it.durationMin } / daySums.size,
                activeDays = daySums.size,
                totalSessions = valid.size
            ),
            weekday = (1..7).map { wd ->
                WeekdayStat(
                    wd,
                    valid.filter {
                        Instant.ofEpochMilli(it.startedAt).atZone(zone).dayOfWeek.value == wd
                    }.sumOf { it.durationMin }
                )
            }
        )
    }

    val uiState: StateFlow<StatsUiState> = combine(
        rangeData,
        fourFlows,
        streakFlow,
        weekReport
    ) { data, ctx, activeDays, report ->
        StatsUiState(
            range = _range.value,
            focusMin = data.focusMin,
            pomodoroCount = data.pomodoroCount,
            perSubject = data.perSubject,
            subjects = ctx.subjects,
            trend = ctx.trend,
            hourly = ctx.hourly,
            streak = streakDays(activeDays),
            tasksDone = data.tasksDone.size,
            tasksOpen = data.tasksOpen.size,
            taskRateLabel = rateLabel(data.tasksDone.size, data.tasksOpen.size),
            weekGoalMin = report.goalMin,
            lastWeekMin = report.lastWeekMin,
            weekFocusDays = report.focusDays,
            lastMonthMin = report.lastMonthMin,
            monthFocusDays = report.monthFocusDays,
            abandoned = report.abandoned,
            abandonReasons = report.abandonReasons,
            heatmap = ctx.heatmap,
            records = report.records,
            weekday = report.weekday
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    private fun rateLabel(done: Int, open: Int): String {
        val total = done + open
        if (total == 0) return "--"
        return "${done * 100 / total}%"
    }

    fun selectRange(range: StatsRange) {
        _range.value = range
    }
}
