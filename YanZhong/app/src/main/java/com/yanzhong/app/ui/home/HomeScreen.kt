package com.yanzhong.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.yanzhong.app.data.db.RepeatRule
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CoralRed
import com.yanzhong.app.ui.theme.CoralRedDeep
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.EnglishColor
import com.yanzhong.app.ui.theme.HeroCard
import com.yanzhong.app.ui.theme.isExpanded
import com.yanzhong.app.ui.theme.MathColor
import com.yanzhong.app.ui.theme.NeutralTagColor
import com.yanzhong.app.ui.theme.PageHeader
import com.yanzhong.app.ui.theme.PoliticsColor
import com.yanzhong.app.ui.theme.Subject408Color
import com.yanzhong.app.ui.theme.SubjectArtwork
import com.yanzhong.app.ui.theme.SuccessGreen
import com.yanzhong.app.util.PersonalPlan
import com.yanzhong.app.util.PhaseInfo
import com.yanzhong.app.util.Phases
import com.yanzhong.app.util.TimeUtils
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** 首页:倒计时置顶 → 今日进度 → 今日待办 → 番茄快捷栏(PRD 4.2 ①) */
@Composable
fun HomeScreen(
    padding: PaddingValues,
    navController: NavController
) {
    val vm: HomeViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val expanded = isExpanded()

    /** 完成后可撤销(一次性任务);误触右滑不再造成"看不见"的完成 */
    fun completeWithUndo(task: TaskEntity) {
        vm.completeTask(task)
        if (task.repeatRule == RepeatRule.NONE) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "已完成「${task.title}」",
                    actionLabel = "撤销",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) vm.uncompleteTask(task)
            }
        }
    }

    /** 顺延后可撤销:恢复原截止时间,postponeCount 同步回退 */
    fun postponeWithUndo(task: TaskEntity) {
        val oldDue = task.dueAt
        vm.postponeTask(task)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "已顺延「${task.title}」到明天",
                actionLabel = "撤销",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoPostpone(task, oldDue)
        }
    }

    var showNodeManager by remember { mutableStateOf(false) }
    var showTaskEditor by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showBatchImport by remember { mutableStateOf(false) }
    var showDailyReview by remember { mutableStateOf(false) }
    var showWeeklyReview by remember { mutableStateOf(false) }
    var showMonthlyReview by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }
    /** 左滑顺延确认弹窗的任务(从任务列表 item 提升,避免每行持有弹窗) */
    var postponeCandidate by remember { mutableStateOf<TaskEntity?>(null) }
    // 日键:模板/排程/周日判定跨天才会变化,避免每分钟 now 更新触发全页重组
    val epochDay = remember(state.now) {
        Instant.ofEpochMilli(state.now).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val todayTemplate = remember(epochDay) { PersonalPlan.todayTemplate(state.now) }
    /** 今日节奏输入:今日待办(含已完成)按科目标签 + 番茄数,供节奏卡动态排程 */
    val rhythmTasks = remember(state.today, state.subjects) {
        val tagOf = state.subjects.associate { it.id to PersonalPlan.tagOfSubject(it.name) }
        (state.today.open + state.today.done)
            .sortedWith(compareBy({ it.priority }, { it.dueAt ?: Long.MAX_VALUE }))
            .filter { it.pomodoroEstimate > 0 }
            .map {
                PersonalPlan.RhythmTask(
                    tag = tagOf[it.subjectId] ?: PersonalPlan.TAG_GEN,
                    title = it.title,
                    pomodoros = it.pomodoroEstimate,
                    taskId = it.id
                )
            }
    }
    val isSunday = remember(epochDay) { epochDay.dayOfWeek == java.time.DayOfWeek.SUNDAY }
    val isMonthEnd = remember(epochDay) { epochDay.dayOfMonth == epochDay.lengthOfMonth() }
    /** 今日节奏排程(逐番茄 + 智能休息):提到列表层计算,供节奏卡与待办时段同步复用 */
    val rhythmRows = remember(todayTemplate, epochDay, rhythmTasks, state.focusMinutes) {
        todayTemplate?.let { PersonalPlan.buildTodayRhythm(it, epochDay, rhythmTasks, state.focusMinutes) }
            ?: emptyList()
    }
    /** 待办任务 → 节奏计划时段(整段起止):任务卡不再用固定 dueAt,动态跟随排程 */
    val planSlots = remember(rhythmRows) {
        rhythmRows.filter { it.taskId != null }
            .groupBy { it.taskId!! }
            .mapValues { (_, segs) ->
                segs.first().time.substringBefore("–") + "–" + segs.last().time.substringAfterLast("–")
            }
    }

    LaunchedEffect(state.onboardingDone) {
        showOnboarding = !state.onboardingDone
    }

    Box(Modifier.fillMaxSize()) {
        // 顶部清新淡彩渐变,固定不随列表滚动(参考番茄ToDo 待办集页);颜色在组合期读取后缓存 Brush
        val colors = MaterialTheme.colorScheme
        val topBrush = remember(colors) {
            Brush.verticalGradient(
                listOf(
                    colors.primaryContainer.copy(alpha = 0.5f),
                    colors.background.copy(alpha = 0f)
                )
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(topBrush)
        )
        if (expanded) {
            // 平板双栏:左主列表(倒计时→阶段→进度→复盘→待办),右常驻今日节奏 + 番茄快捷栏,横屏同屏可见
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxSize()
                    .widthIn(max = 1320.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(
                    Modifier
                        .weight(0.56f)
                        .fillMaxHeight()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 8.dp,
                            top = 12.dp,
                            bottom = padding.calculateBottomPadding() + 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        item(key = "page-header") {
                            val dateLabel = remember(state.now) {
                                "${TimeUtils.formatMonthDay(state.now)} ${TimeUtils.weekdayName(state.now)}"
                            }
                            PageHeader(
                                title = "今日",
                                subtitle = dateLabel + (state.phase?.let { " · ${it.phase.name}" } ?: "")
                            )
                        }
                        item {
                            CountdownCard(
                                nodes = state.nodes,
                                initialIndex = state.pinnedIndex,
                                onNodeSelected = { vm.selectNode(it) },
                                onManageNodes = { showNodeManager = true }
                            )
                        }
                        state.phase?.let { info ->
                            item(key = "phase-strip") { PhaseStrip(info = info) }
                        }
                        item {
                            TodayProgress(
                                done = state.doneCount,
                                total = state.totalCount,
                                timerRunning = state.timerRunning,
                                weekMinutes = state.weekMinutes,
                                weekGoalMin = state.weekGoalMin,
                                todayPomodoros = state.todayPomodoros,
                                dailyPomodoroGoal = state.dailyPomodoroGoal
                            )
                        }
                        item(key = "review-card") {
                            ReviewCard(
                                yesterdayReview = state.yesterdayReview,
                                todayReview = state.todayReview,
                                weeklyReview = state.weeklyReview,
                                isSunday = isSunday,
                                weekDoneTasks = state.weekDoneTasks,
                                weekMinutes = state.weekMinutes,
                                weekGoalMin = state.weekGoalMin,
                                monthlyReview = state.monthlyReview,
                                isMonthEnd = isMonthEnd,
                                onOpenDaily = { showDailyReview = true },
                                onOpenWeekly = { showWeeklyReview = true },
                                onOpenMonthly = { showMonthlyReview = true }
                            )
                        }
                        taskListSection(
                            openTasks = state.today.open,
                            doneTasks = state.today.done,
                            subjects = state.subjects,
                            today = state.now,
                            planSlots = planSlots,
                            onComplete = { completeWithUndo(it) },
                            onPostpone = { postponeWithUndo(it) },
                            onPostponeRequest = { postponeCandidate = it },
                            onStartFocus = {
                                vm.startFocusFor(it)
                                navController.navigate(Routes.FOCUS) { launchSingleTop = true }
                            },
                            onTaskClick = {
                                editingTask = it
                                showTaskEditor = true
                            }
                        )
                    }
                }

                Column(
                    Modifier
                        .weight(0.44f)
                        .fillMaxHeight()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 20.dp,
                            top = 12.dp,
                            bottom = padding.calculateBottomPadding() + 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        todayTemplate?.let { tpl ->
                            item(key = "rhythm-strip") {
                                TodayRhythmCard(
                                    tpl = tpl,
                                    rows = rhythmRows,
                                    now = state.now,
                                    focusMinutes = state.focusMinutes
                                )
                            }
                        }
                        item(key = "quick-start") {
                            QuickStartBar(
                                recentTasks = state.today.open.take(2),
                                subjects = state.subjects,
                                onQuickStart = {
                                    vm.startFocusFor(it)
                                    navController.navigate(Routes.FOCUS) { launchSingleTop = true }
                                },
                                onNewPomodoro = {
                                    vm.quickFocus()
                                    navController.navigate(Routes.FOCUS) { launchSingleTop = true }
                                },
                                onAddTask = {
                                    editingTask = null
                                    showTaskEditor = true
                                },
                                onBatchImport = { showBatchImport = true }
                            )
                        }
                    }
                }
            }
        } else {
        // 手机/中等宽度:限宽 720dp 居中;顶部渐变保持整屏铺满
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 12.dp,
            bottom = padding.calculateBottomPadding() + 72.dp
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
            item(key = "page-header") {
                val dateLabel = remember(state.now) {
                    "${TimeUtils.formatMonthDay(state.now)} ${TimeUtils.weekdayName(state.now)}"
                }
                PageHeader(
                    title = "今日",
                    subtitle = dateLabel + (state.phase?.let { " · ${it.phase.name}" } ?: "")
                )
            }

            item {
                CountdownCard(
                    nodes = state.nodes,
                    initialIndex = state.pinnedIndex,
                    onNodeSelected = { vm.selectNode(it) },
                    onManageNodes = { showNodeManager = true }
                )
            }

            state.phase?.let { info ->
                item(key = "phase-strip") {
                    PhaseStrip(info = info)
                }
            }

            todayTemplate?.let { tpl ->
                item(key = "rhythm-strip") {
                    TodayRhythmCard(
                        tpl = tpl,
                        rows = rhythmRows,
                        now = state.now,
                        focusMinutes = state.focusMinutes
                    )
                }
            }

            item {
                TodayProgress(
                    done = state.doneCount,
                    total = state.totalCount,
                    timerRunning = state.timerRunning,
                    weekMinutes = state.weekMinutes,
                    weekGoalMin = state.weekGoalMin,
                    todayPomodoros = state.todayPomodoros,
                    dailyPomodoroGoal = state.dailyPomodoroGoal
                )
            }

            item(key = "review-card") {
                ReviewCard(
                    yesterdayReview = state.yesterdayReview,
                    todayReview = state.todayReview,
                    weeklyReview = state.weeklyReview,
                    isSunday = isSunday,
                    weekDoneTasks = state.weekDoneTasks,
                    weekMinutes = state.weekMinutes,
                    weekGoalMin = state.weekGoalMin,
                    monthlyReview = state.monthlyReview,
                    isMonthEnd = isMonthEnd,
                    onOpenDaily = { showDailyReview = true },
                    onOpenWeekly = { showWeeklyReview = true },
                    onOpenMonthly = { showMonthlyReview = true }
                )
            }

            // 待办逐项 item 化(key=id):增删位移动画 + 互不牵连重组;断点间距在 header 内
            taskListSection(
                openTasks = state.today.open,
                doneTasks = state.today.done,
                subjects = state.subjects,
                today = state.now,
                planSlots = planSlots,
                onComplete = { completeWithUndo(it) },
                onPostpone = { postponeWithUndo(it) },
                onPostponeRequest = { postponeCandidate = it },
                onStartFocus = {
                    vm.startFocusFor(it)
                    navController.navigate(Routes.FOCUS) { launchSingleTop = true }
                },
                onTaskClick = {
                    editingTask = it
                    showTaskEditor = true
                }
            )

            item(key = "quick-start") {
                QuickStartBar(
                    recentTasks = state.today.open.take(2),
                    subjects = state.subjects,
                    onQuickStart = {
                        vm.startFocusFor(it)
                        navController.navigate(Routes.FOCUS) { launchSingleTop = true }
                    },
                    onNewPomodoro = {
                        vm.quickFocus()
                        navController.navigate(Routes.FOCUS) { launchSingleTop = true }
                    },
                    onAddTask = {
                        editingTask = null
                        showTaskEditor = true
                    },
                    onBatchImport = { showBatchImport = true }
                )
            }
            }
        }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = padding.calculateBottomPadding() + 8.dp)
        )
    }

    /** 左滑顺延二次确认:防止误触导致任务"凭空消失"(参考番茄ToDo 防误触设计) */
    postponeCandidate?.let { candidate ->
        val isRepeat = candidate.repeatRule != 0
        AlertDialog(
            onDismissRequest = { postponeCandidate = null },
            title = { Text(if (isRepeat) "无法顺延" else "顺延到明天?") },
            text = {
                Text(
                    if (isRepeat) {
                        "「${candidate.title}」按重复规则自动出现,无法单独顺延。如需调整,请点击任务修改重复规则。"
                    } else {
                        "「${candidate.title}」将推迟到明天同一时刻,今日列表将不再显示。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        postponeCandidate = null
                        if (!isRepeat) postponeWithUndo(candidate)
                    }
                ) { Text(if (isRepeat) "知道了" else "顺延") }
            },
            dismissButton = {
                if (!isRepeat) {
                    TextButton(onClick = { postponeCandidate = null }) { Text("取消") }
                }
            }
        )
    }

    if (showNodeManager) {
        NodeManageSheet(
            nodes = state.nodes,
            onAdd = { vm.addNode(it) },
            onUpdate = { vm.updateNode(it) },
            onDelete = { vm.deleteNode(it) },
            onPin = { vm.toggleNodePin(it) },
            onBatchAdd = { text -> vm.batchAddNodes(text) },
            onDismiss = { showNodeManager = false }
        )
    }

    if (showTaskEditor) {
        TaskEditSheet(
            initial = editingTask,
            subjects = state.subjects,
            onSave = { vm.saveTask(it) },
            onDelete = { vm.deleteTask(it) },
            onDismiss = {
                showTaskEditor = false
                editingTask = null
            }
        )
    }

    if (showBatchImport) {
        BatchImportDialog(
            onImport = { text ->
                showBatchImport = false
                scope.launch {
                    val added = vm.batchInsertTasks(text)
                    snackbarHostState.showSnackbar(
                        if (added > 0) "已添加 $added 条今日任务" else "未识别到可导入的任务"
                    )
                }
            },
            onDismiss = { showBatchImport = false }
        )
    }

    if (showDailyReview) {
        DailyReviewSheet(
            initial = state.todayReview,
            onSave = { q1, q2, q3 -> vm.saveDailyReview(q1, q2, q3) },
            onDismiss = { showDailyReview = false }
        )
    }

    if (showWeeklyReview) {
        WeeklyReviewSheet(
            initial = state.weeklyReview,
            weekDoneTasks = state.weekDoneTasks,
            weekMinutes = state.weekMinutes,
            weekGoalMin = state.weekGoalMin,
            onSave = { weak, t1, t2, t3 -> vm.saveWeeklyReview(weak, t1, t2, t3) },
            onDismiss = { showWeeklyReview = false }
        )
    }

    if (showMonthlyReview) {
        MonthlyReviewSheet(
            initial = state.monthlyReview,
            monthDoneTasks = state.monthDoneTasks,
            monthMinutes = state.monthMinutes,
            onSave = { summary, t1, t2, t3 -> vm.saveMonthlyReview(summary, t1, t2, t3) },
            onDismiss = { showMonthlyReview = false }
        )
    }

    if (showOnboarding) {
        OnboardingCard(
            onManage = {
                showOnboarding = false
                showNodeManager = true
                vm.dismissOnboarding()
            },
            onDismiss = {
                showOnboarding = false
                vm.dismissOnboarding()
            }
        )
    }
}

/** 阶段进度条:当前阶段名 + 第几天 + 五段比例轨道 + 阶段内细进度(与计划页同款视觉语言) */
@Composable
private fun PhaseStrip(info: PhaseInfo) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(info.phase.name, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        info.phase.slogan,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "第 ${info.dayIdx} / ${info.totalDays} 天",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Phases.all.forEach { phase ->
                    val isCurrent = phase == info.phase
                    val isPast = phase.start.isBefore(info.phase.start)
                    Box(
                        Modifier
                            .weight(phase.daysCount().toFloat())
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when {
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    isPast -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((info.dayIdx.toFloat() / info.totalDays).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    if (info.phase.weeklyHours.isNotEmpty()) append("目标 ${info.phase.weeklyHours} h/周 · ")
                    append(
                        when {
                            info.daysToEnd <= 0 -> "阶段收官在即"
                            info.next != null -> "${info.daysToEnd} 天后进入${info.next.name}"
                            else -> "距初试 ${info.daysToEnd} 天"
                        }
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 今日节奏:待办合并为整段任务时段(去掉逐番茄与小憩/长休明细),当前时段以「现在」高亮 */
@Composable
private fun TodayRhythmCard(
    tpl: PersonalPlan.DayTemplate,
    rows: List<PersonalPlan.RhythmRow>,
    now: Long,
    focusMinutes: Int = 25
) {
    val localNow = remember(now) {
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime()
    }
    // 同一任务的多个番茄合并为一段:首段起点 + 末段终点,标题去掉「· i/N」后缀
    val slots = remember(rows) {
        rows.filter { it.isTask && it.taskId != null }
            .groupBy { it.taskId }
            .values
            .map { group ->
                val first = group.first()
                val last = group.last()
                PersonalPlan.RhythmRow(
                    time = first.time.substringBefore("–") + "–" + last.time.substringAfterLast("–"),
                    label = first.label.substringBefore(" · "),
                    tag = first.tag,
                    minutes = group.sumOf { it.minutes },
                    isTask = true,
                    taskId = first.taskId
                )
            }
    }
    val activeIdx = remember(slots, localNow) { PersonalPlan.activeRhythmIndex(slots, localNow) }
    val totalMinutes = slots.sumOf { it.minutes }
    val totalPomodoros = if (focusMinutes > 0) totalMinutes / focusMinutes else 0

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("今日节奏 · ${tpl.name}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (slots.isEmpty()) "净学习 ${tpl.hours}"
                    else "净学习 ${formatStudyMinutes(totalMinutes)} · ${totalPomodoros}🍅",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(6.dp))
            if (slots.isEmpty()) {
                Text(
                    "今日暂无排程任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                slots.forEachIndexed { idx, row ->
                    val active = idx == activeIdx
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 6.dp, vertical = if (active) 5.dp else 2.dp)
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(rhythmTagColor(row.tag))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            row.time,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (active) FontWeight.SemiBold else null,
                            modifier = Modifier.width(92.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            row.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.SemiBold else null,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (active) {
                            // 「现在」标记:当前时段锚点
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    "现在",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 分钟数 → "7h55m" 样式(整点为 "7h",不足 1 小时为 "50m") */
private fun formatStudyMinutes(min: Int): String {
    val h = min / 60
    val m = min % 60
    return when {
        h > 0 && m > 0 -> "${h}h${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/** 标签 → 学科色(与计划页保持一致) */
private fun rhythmTagColor(tag: String): Color = when (tag) {
    PersonalPlan.TAG_MATH -> MathColor
    PersonalPlan.TAG_CS -> Subject408Color
    PersonalPlan.TAG_EN -> EnglishColor
    PersonalPlan.TAG_POL -> PoliticsColor
    else -> NeutralTagColor
}

/** 今日完成进度条 + 本周净学习目标(周净学习 37–40h 是作战计划的核心硬指标) */
@Composable
private fun TodayProgress(
    done: Int,
    total: Int,
    timerRunning: Boolean,
    weekMinutes: Int,
    weekGoalMin: Int,
    todayPomodoros: Int = 0,
    dailyPomodoroGoal: Int = 8
) {
    val progress = if (total == 0) 0f else done.toFloat() / total
    val weekProgress = if (weekGoalMin <= 0) 0f else (weekMinutes.toFloat() / weekGoalMin).coerceAtMost(1f)
    val weekReached = weekMinutes >= weekGoalMin
    val goalReached = todayPomodoros >= dailyPomodoroGoal
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "今日进度 · $done / $total",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (timerRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            AppIcons.Flame,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            " 专注进行中",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "今日番茄 $todayPomodoros 🍅",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (goalReached) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "目标达成",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Text(
                        "目标 $dailyPomodoroGoal 🍅 · 还差 ${dailyPomodoroGoal - todayPomodoros} 个",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "本周净学习 ${"%.1f".format(weekMinutes / 60f)}h",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (weekReached) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = SuccessGreen.copy(alpha = 0.18f)
                    ) {
                        Text(
                            "目标达成",
                            style = MaterialTheme.typography.labelSmall,
                            color = SuccessGreen,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Text(
                        "目标 ${weekGoalMin / 60}h · 还差 ${
                            "%.1f".format((weekGoalMin - weekMinutes) / 60f)
                        }h",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(weekProgress)
                        .fillMaxHeight()
                        .background(if (weekReached) SuccessGreen else MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

/** 番茄快捷栏:彩色功能卡一键开钟 + 最近任务 + 新番茄(PRD 4.2 / 4.3,视觉参考番茄ToDo 锁机页功能卡) */
@Composable
private fun QuickStartBar(
    recentTasks: List<TaskEntity>,
    subjects: List<SubjectEntity>,
    onQuickStart: (TaskEntity) -> Unit,
    onNewPomodoro: () -> Unit,
    onAddTask: () -> Unit,
    onBatchImport: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        HeroCard(
            title = "新番茄 · 直接专注",
            desc = "一键开钟,立刻进入专注状态",
            icon = AppIcons.Timer,
            colors = listOf(CoralRed, CoralRedDeep),
            onClick = onNewPomodoro
        )
        if (recentTasks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    recentTasks.forEach { task ->
                        val subject = subjects.firstOrNull { it.id == task.subjectId }
                        val subjectColor =
                            subject?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary
                        Surface(
                            onClick = { onQuickStart(task) },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SubjectArtwork(
                                    subjectName = subject?.name ?: "未分类",
                                    color = subjectColor,
                                    modifier = Modifier.size(38.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    task.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "开始",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = subjectColor
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBatchImport, modifier = Modifier.weight(1f)) {
                Icon(
                    AppIcons.ClipboardList,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("批量导入")
            }
            TextButton(onClick = onAddTask, modifier = Modifier.weight(1f)) {
                Text("＋ 添加今日任务")
            }
        }
    }
}

/** 批量录入弹窗:多行文本一行一条,按关键词自动归科目,提交后回调实际文本 */
@Composable
private fun BatchImportDialog(
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量录入今日待办") },
        text = {
            Column {
                Text(
                    "一行一条,按关键词自动归入数学 / 408 / 英语 / 政治科目;无关键词归入默认科目,截止时间统一设为今天。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如:\n高数 极限计算 30 题\n英语单词 List6\n408 数据结构 链表") },
                    minLines = 6,
                    maxLines = 10
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = text.isNotBlank()
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 首次启动引导:只讲一件事——添加倒计时节点(PRD 1.4 ③) */
@Composable
private fun OnboardingCard(onManage: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("欢迎来到研钟") },
        text = {
            Text("30 秒开始:为你的考研路添加第一个倒计时节点——\n\n已为你预置「2028 考研初试 2027-12-18」,你可以直接使用,或添加更多节点(模考、报名、复试)。")
        },
        confirmButton = { TextButton(onClick = onManage) { Text("管理节点") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("开始使用") } }
    )
}
