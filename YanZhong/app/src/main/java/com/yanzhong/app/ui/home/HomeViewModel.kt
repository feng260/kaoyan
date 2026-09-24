package com.yanzhong.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.db.CountdownNodeEntity
import com.yanzhong.app.data.db.DailyReviewEntity
import com.yanzhong.app.data.db.MonthlyReviewEntity
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.db.WeeklyReviewEntity
import com.yanzhong.app.data.repo.TodayView
import com.yanzhong.app.util.PhaseInfo
import com.yanzhong.app.util.Phases
import com.yanzhong.app.util.TimeUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HomeUiState(
    val nodes: List<CountdownNodeEntity> = emptyList(),
    val pinnedIndex: Int = 0,
    val now: Long = System.currentTimeMillis(),
    val today: TodayView = TodayView(),
    val subjects: List<SubjectEntity> = emptyList(),
    val timerRunning: Boolean = false,
    val onboardingDone: Boolean = true,
    val phase: PhaseInfo? = null,
    val weekMinutes: Int = 0,
    val weekGoalMin: Int = 37 * 60,
    val todayPomodoros: Int = 0,
    val dailyPomodoroGoal: Int = 8,
    val focusMinutes: Int = 25,
    val todayReview: DailyReviewEntity? = null,
    val yesterdayReview: DailyReviewEntity? = null,
    val weeklyReview: WeeklyReviewEntity? = null,
    val weekDoneTasks: Int = 0,
    val monthlyReview: MonthlyReviewEntity? = null,
    val monthMinutes: Int = 0,
    val monthDoneTasks: Int = 0
) {
    val pinned: CountdownNodeEntity? get() = nodes.getOrNull(pinnedIndex) ?: nodes.firstOrNull()
    val doneCount: Int get() = today.done.size
    val totalCount: Int get() = today.open.size + today.done.size
    val weekGoalProgress: Float
        get() = if (weekGoalMin <= 0) 0f else (weekMinutes.toFloat() / weekGoalMin).coerceAtMost(1f)
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as YanZhongApp
    private val repo = container.repository
    private val engine = container.engine
    private val settingsRepo = container.settingsRepo

    /** 首页展示的节点下标(左右滑切换,默认置顶节点) */
    private val nodeIndex = MutableStateFlow(0)

    /** 分钟级时钟:仅驱动日期/阶段/今日列表等低频字段;秒级倒计时由 CountdownCard 内部自刷,避免整页每秒重组 */
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }

    private data class NodeSlice(
        val nodes: List<CountdownNodeEntity>,
        val index: Int,
        val now: Long
    )

    private val onboarding = settingsRepo.settings
        .map {
            SettingsSlice(
                onboardingDone = it.onboardingDone,
                weeklyGoalMin = it.weeklyGoalHours * 60,
                dailyPomodoroGoal = it.dailyPomodoroGoal,
                focusMinutes = it.currentPlan.focusMin
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsSlice())

    private data class SettingsSlice(
        val onboardingDone: Boolean = true,
        val weeklyGoalMin: Int = 37 * 60,
        val dailyPomodoroGoal: Int = 8,
        val focusMinutes: Int = 25
    )

    private data class FocusStats(val weekMinutes: Int, val todayPomodoros: Int, val monthMinutes: Int = 0)

    private data class ReviewSlice(
        val today: DailyReviewEntity? = null,
        val yesterday: DailyReviewEntity? = null,
        val weekly: WeeklyReviewEntity? = null,
        val weekDone: Int = 0,
        val monthly: MonthlyReviewEntity? = null,
        val monthDone: Int = 0
    )

    /** 今日 epochDay:跨天时自动触发复盘相关流重订阅 */
    private val dayKey = ticker
        .map { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay().toInt() }
        .distinctUntilChanged()

    /** 本周周一 epochDay:与统计页同一 SQL 口径统计周净学习 */
    private val weekKey = dayKey
        .map { LocalDate.ofEpochDay(it.toLong()).with(DayOfWeek.MONDAY).toEpochDay().toInt() }
        .distinctUntilChanged()

    /** 本月 1 号 epochDay:驱动月复盘订阅与月度自动盘点 */
    private val monthKey = dayKey
        .map { LocalDate.ofEpochDay(it.toLong()).withDayOfMonth(1).toEpochDay().toInt() }
        .distinctUntilChanged()

    private data class MonthSlice(val monthly: MonthlyReviewEntity? = null, val monthDone: Int = 0)

    /** 月度复盘 + 本月完成任务数:预合并成单流,避免 combine 六参超载(combine 仅支持最多 5 流) */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val monthSlice = combine(
        monthKey.flatMapLatest { month -> repo.observeMonthlyReview(LocalDate.ofEpochDay(month.toLong())) },
        monthKey.flatMapLatest { month ->
            val monthStart = LocalDate.ofEpochDay(month.toLong())
            val zone = ZoneId.systemDefault()
            repo.observeDoneCountBetween(
                monthStart.atStartOfDay(zone).toInstant().toEpochMilli(),
                monthStart.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            )
        }
    ) { monthly, monthDone -> MonthSlice(monthly, monthDone) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val reviewSlice = combine(
        dayKey.flatMapLatest { day -> repo.observeDailyReview(LocalDate.ofEpochDay(day.toLong())) },
        dayKey.flatMapLatest { day -> repo.observeDailyReview(LocalDate.ofEpochDay((day - 1).toLong())) },
        dayKey.flatMapLatest { day -> repo.observeWeeklyReview(LocalDate.ofEpochDay(day.toLong()).with(DayOfWeek.MONDAY)) },
        dayKey.flatMapLatest { day ->
            val monday = LocalDate.ofEpochDay(day.toLong()).with(DayOfWeek.MONDAY)
            val zone = ZoneId.systemDefault()
            repo.observeDoneCountBetween(
                monday.atStartOfDay(zone).toInstant().toEpochMilli(),
                monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            )
        },
        monthSlice
    ) { today, yesterday, weekly, weekDone, month ->
        ReviewSlice(today, yesterday, weekly, weekDone, month.monthly, month.monthDone)
    }

    private val nodeSlice = combine(
        repo.observeNodes(),
        nodeIndex,
        ticker
    ) { nodes, index, now ->
        val pinnedIdx = nodes.indexOfFirst { it.pinned }.let { if (it < 0) 0 else it }
        NodeSlice(nodes, if (index in nodes.indices) index else pinnedIdx, now)
    }

    /** 周净学习分钟 + 今日番茄数 + 月度净学习:SQL 聚合 + 跨天自动重订阅(口径与统计页一致:startedAt BETWEEN) */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val focusStats = combine(
        weekKey.flatMapLatest { weekStartDay ->
            val from = LocalDate.ofEpochDay(weekStartDay.toLong()).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            repo.observeDurationMin(from, from + 7L * 24 * 3600 * 1000 - 1)
        },
        dayKey.flatMapLatest { day ->
            val from = LocalDate.ofEpochDay(day.toLong()).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            repo.observeCount(from, from + 24L * 3600 * 1000 - 1)
        },
        monthKey.flatMapLatest { month ->
            val monthStart = LocalDate.ofEpochDay(month.toLong())
            val from = monthStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            repo.observeDurationMin(
                from,
                monthStart.plusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
            )
        }
    ) { weekMinutes, todayPomodoros, monthMinutes ->
        FocusStats(weekMinutes, todayPomodoros, monthMinutes)
    }

    /** 引擎状态只取 isRunning(低频),避免 remainingMs 的秒级更新每秒触发 uiState 重算 */
    private val timerRunning = engine.state
        .map { it.isRunning }
        .distinctUntilChanged()

    val uiState: StateFlow<HomeUiState> = combine(
        nodeSlice,
        repo.observeTodayView(),
        repo.observeSubjects(),
        timerRunning,
        combine(onboarding, focusStats, reviewSlice) { s, f, r -> Triple(s, f, r) }
    ) { slice, today, subjects, timerRunning, (settings, stats, reviews) ->
        HomeUiState(
            nodes = slice.nodes,
            pinnedIndex = slice.index,
            now = slice.now,
            today = today,
            subjects = subjects,
            timerRunning = timerRunning,
            onboardingDone = settings.onboardingDone,
            phase = Phases.phaseInfo(slice.now),
            weekMinutes = stats.weekMinutes,
            weekGoalMin = settings.weeklyGoalMin,
            todayPomodoros = stats.todayPomodoros,
            dailyPomodoroGoal = settings.dailyPomodoroGoal,
            focusMinutes = settings.focusMinutes,
            todayReview = reviews.today,
            yesterdayReview = reviews.yesterday,
            weeklyReview = reviews.weekly,
            weekDoneTasks = reviews.weekDone,
            monthlyReview = reviews.monthly,
            monthMinutes = stats.monthMinutes,
            monthDoneTasks = reviews.monthDone
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun selectNode(index: Int) {
        if (index >= 0) nodeIndex.value = index
    }

    fun startFocusFor(task: TaskEntity) {
        val state = uiState.value
        val subject = state.subjects.firstOrNull { it.id == task.subjectId }
        viewModelScope.launch {
            engine.startFocus(
                taskId = task.id,
                taskTitle = task.title,
                subjectId = task.subjectId,
                subjectColorArgb = subject?.colorArgb ?: 0xFF5865F2,
                plan = settingsRepo.current().currentPlan,
                taskPomodoroEstimate = task.pomodoroEstimate,
                taskPomodoroDoneAtStart = task.completedPomodoros
            )
        }
    }

    /** 快速开钟:不选任务直接开,防止漏记(PRD 3.4) */
    fun quickFocus() {
        viewModelScope.launch {
            engine.startFocus(
                taskId = null,
                taskTitle = "",
                subjectId = null,
                subjectColorArgb = 0xFF5865F2,
                plan = settingsRepo.current().currentPlan
            )
        }
    }

    fun completeTask(task: TaskEntity) {
        viewModelScope.launch { repo.completeTask(task) }
    }

    fun uncompleteTask(task: TaskEntity) {
        viewModelScope.launch { repo.uncompleteTask(task) }
    }

    fun postponeTask(task: TaskEntity) {
        viewModelScope.launch { repo.postponeTask(task) }
    }

    fun undoPostpone(task: TaskEntity, oldDue: Long?) {
        viewModelScope.launch { repo.undoPostpone(task, oldDue) }
    }

    fun saveTask(task: TaskEntity) {
        viewModelScope.launch {
            if (task.id == 0L) repo.insertTask(task) else repo.updateTask(task)
        }
    }

    fun deleteTask(task: TaskEntity) {
        viewModelScope.launch { repo.deleteTask(task) }
    }

    /** 批量录入今日待办:多行文本一行一条,按关键词自动归科目;返回实际新增条数(供 UI 提示) */
    suspend fun batchInsertTasks(text: String): Int = repo.batchInsertTasks(text)

    fun addNode(node: CountdownNodeEntity) {
        viewModelScope.launch { repo.addNode(node) }
    }

    /** 批量录入倒计时节点(多行文本,一行一条):返回实际新增数 */
    suspend fun batchAddNodes(text: String): Int = repo.batchAddNodes(text)

    fun updateNode(node: CountdownNodeEntity) {
        viewModelScope.launch { repo.updateNode(node) }
    }

    fun deleteNode(node: CountdownNodeEntity) {
        viewModelScope.launch { repo.deleteNode(node) }
    }

    fun pinNode(id: Long) {
        viewModelScope.launch { repo.pinNode(id) }
    }

    fun toggleNodePin(node: CountdownNodeEntity) {
        viewModelScope.launch { repo.toggleNodePin(node) }
    }

    fun dismissOnboarding() {
        viewModelScope.launch { settingsRepo.setOnboardingDone() }
    }

    fun saveDailyReview(q1: String, q2: String, q3: String) {
        viewModelScope.launch { repo.saveDailyReview(q1, q2, q3) }
    }

    fun saveWeeklyReview(weakPoints: String, top1: String, top2: String, top3: String) {
        viewModelScope.launch { repo.saveWeeklyReview(weakPoints, top1, top2, top3) }
    }

    fun saveMonthlyReview(summary: String, top1: String, top2: String, top3: String) {
        viewModelScope.launch { repo.saveMonthlyReview(summary, top1, top2, top3) }
    }
}
