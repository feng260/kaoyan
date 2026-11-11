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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.yanzhong.app.data.db.RepeatRule
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.plan.isServerPlanItem
import com.yanzhong.app.ui.guide.GuideAnchor
import com.yanzhong.app.ui.guide.GuideAnchors
import com.yanzhong.app.ui.guide.ProvideGuideSpec
import com.yanzhong.app.ui.guide.homeGuide
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.actionCardGradient
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.EnglishColor
import com.yanzhong.app.ui.theme.HeroCard
import com.yanzhong.app.ui.theme.isExpanded
import com.yanzhong.app.ui.theme.isTablet
import com.yanzhong.app.ui.theme.MathColor
import com.yanzhong.app.ui.theme.NeutralTagColor
import com.yanzhong.app.ui.theme.PageHeader
import com.yanzhong.app.ui.theme.PillTone
import com.yanzhong.app.ui.theme.PoliticsColor
import com.yanzhong.app.ui.theme.ProgressLine
import com.yanzhong.app.ui.theme.StatusPill
import com.yanzhong.app.ui.theme.SubjectCsColor
import com.yanzhong.app.ui.theme.successContentColor
import com.yanzhong.app.util.RhythmEngine
import com.yanzhong.app.util.StageInfo
import com.yanzhong.app.util.TimeUtils
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** 首页:倒计时置顶 → 阶段 → 计划体检 → 今日进度 → 今日待办 → 今日节奏 → 复盘(PRD 4.2 ①) */
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
    // 今日页首次引导:加载出首帧真实数据后再允许出现,避免有/无计划两种文案闪变
    ProvideGuideSpec(
        spec = homeGuide(hasActivePlan = state.hasActivePlan, tablet = isTablet()),
        ready = state.loaded
    )
    // 计划体检:有生效计划才拉;未登录时 refresh() 内部直接返回,不展示
    val checkupVm: CheckupViewModel = viewModel()
    val checkupState by checkupVm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(state.hasActivePlan) {
        if (state.hasActivePlan) checkupVm.refresh()
    }

    /** 完成后可撤销(一次性任务);误触右滑不再造成"看不见"的完成 */
    fun completeWithUndo(task: TaskEntity) {
        scope.launch {
            if (!vm.completeTask(task)) {
                snackbarHostState.showSnackbar("更新失败，请重试；完成状态未改变")
                return@launch
            }
            if (task.repeatRule == RepeatRule.NONE) {
                val result = snackbarHostState.showSnackbar(
                    message = "已完成「${task.title}」",
                    actionLabel = "撤销",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed && !vm.uncompleteTask(task)) {
                    snackbarHostState.showSnackbar("撤销失败，请重试")
                }
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
    /** 左滑顺延确认弹窗的任务(从任务列表 item 提升,避免每行持有弹窗) */
    var postponeCandidate by remember { mutableStateOf<TaskEntity?>(null) }
    // 日键:模板/排程/周日判定跨天才会变化,避免每分钟 now 更新触发全页重组
    val epochDay = remember(state.now) {
        Instant.ofEpochMilli(state.now).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val todayTemplate = remember(epochDay) { RhythmEngine.defaultTemplate(epochDay.dayOfWeek) }
    /** 今日节奏输入:今日待办(含已完成)按科目标签 + 番茄数,供节奏卡动态排程 */
    val rhythmTasks = remember(state.today, state.subjects) {
        val tagOf = state.subjects.associate { it.id to RhythmEngine.tagOfSubject(it.name) }
        (state.today.open + state.today.done)
            .sortedWith(compareBy({ it.priority }, { it.dueAt ?: Long.MAX_VALUE }))
            .filter { it.pomodoroEstimate > 0 }
            .map {
                RhythmEngine.RhythmTask(
                    tag = tagOf[it.subjectId] ?: RhythmEngine.TAG_GEN,
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
        RhythmEngine.buildTodayRhythm(todayTemplate, epochDay, rhythmTasks, state.focusMinutes)
    }
    /**
     * 待办任务 → 展示时段。
     * 服务端计划项自带编译好的开始时刻(随真实课表空档排定),优先用它;
     * 手工/旧计划任务再退回本地节奏排程(整段起止),保证原有体验不退化。
     */
    val planSlots = remember(rhythmRows, state.today) {
        val rhythm = rhythmRows.filter { it.taskId != null }
            .groupBy { it.taskId!! }
            .mapValues { (_, segs) ->
                segs.first().time.substringBefore("–") + "–" + segs.last().time.substringAfterLast("–")
            }
        val server = (state.today.open + state.today.done).mapNotNull { task ->
            TimeUtils.planTimeLabel(task.planSlot, task.planStartTime)?.let { task.id to it }
        }.toMap()
        rhythm + server
    }
    /** 今日已完成任务 id:节奏卡概览行按任务真实完成态统计,与展开后的任务段一一对应 */
    val doneTaskIds = remember(state.today.done) { state.today.done.map { it.id }.toSet() }

    Box(Modifier.fillMaxSize()) {
        if (expanded) {
            // 平板双栏:左主列表(倒计时→阶段→体检→进度→待办→复盘),右常驻今日节奏,横屏同屏可见
            val showRhythmColumn = state.hasActivePlan
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxSize()
                    .widthIn(max = 1320.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(
                    Modifier
                        .weight(if (showRhythmColumn) 0.56f else 1f)
                        .fillMaxHeight()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = if (showRhythmColumn) 8.dp else 20.dp,
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
                                meta = dateLabel + (state.phase?.let { " · ${it.stageName}" } ?: ""),
                                actions = {
                                    IconButton(
                                        onClick = {
                                            editingTask = null
                                            showTaskEditor = true
                                        }
                                    ) {
                                        Icon(
                                            AppIcons.Add,
                                            contentDescription = "添加任务",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
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
                        if (!state.hasActivePlan) {
                            item(key = "plan-empty") {
                                PlanGuideCard(
                                    onOpenPlan = {
                                        navController.navigate(Routes.planInterview()) { launchSingleTop = true }
                                    }
                                )
                            }
                        }
                        if (state.hasActivePlan) {
                            state.phase?.let { info ->
                                item(key = "phase-strip") { PhaseStrip(info = info) }
                            }
                            val checkup = checkupState.checkup
                            if (checkup != null && !checkupState.dismissed) {
                                item(key = "plan-checkup") {
                                    PlanCheckupCard(
                                        checkup = checkup,
                                        onAdjust = {
                                            navController.navigate(
                                                Routes.planInterview(
                                                    mode = "adjust",
                                                    prefill = CheckupFormat.adjustPrefill(checkup)
                                                )
                                            ) { launchSingleTop = true }
                                        },
                                        onDismiss = { checkupVm.dismissForToday() }
                                    )
                                }
                            }
                            item(key = "today-progress") {
                                TodayProgress(
                                    done = state.doneCount,
                                    total = state.totalCount,
                                    timerRunning = state.timerRunning,
                                    weekMinutes = state.weekMinutes,
                                    weekGoalMin = derivedWeekGoalMin(state),
                                    todayPomodoros = state.todayPomodoros,
                                    dailyPomodoroGoal = derivedDailyGoal(state)
                                )
                            }
                        }
                        taskListSection(
                            openTasks = state.today.open,
                            doneTasks = state.today.done,
                            subjects = state.subjects,
                            today = state.now,
                            planSlots = planSlots,
                            onComplete = { completeWithUndo(it) },
                            onPostpone = { postponeWithUndo(it) },
                            onPostponeRequest = { if (!it.isServerPlanItem()) postponeCandidate = it },
                            onStartFocus = {
                                vm.startFocusFor(it)
                                navController.navigate(Routes.FOCUS) { launchSingleTop = true }
                            },
                            onTaskClick = {
                                if (it.isServerPlanItem()) {
                                    navController.navigate(Routes.PLAN) { launchSingleTop = true }
                                } else {
                                    editingTask = it
                                    showTaskEditor = true
                                }
                            },
                            onAddTask = {
                                editingTask = null
                                showTaskEditor = true
                            },
                            onBatchImport = { showBatchImport = true }
                        )

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
                    }
                }

                if (showRhythmColumn) {
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
                            item(key = "rhythm-strip") {
                                TodayRhythmCard(
                                    tpl = todayTemplate,
                                    rows = rhythmRows,
                                    now = state.now,
                                    focusMinutes = state.focusMinutes,
                                    doneTaskIds = doneTaskIds
                                )
                            }
                        }
                    }
                }
            }
        } else {
        // 手机/中等宽度:限宽 720dp 居中
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
                    meta = dateLabel + (state.phase?.let { " · ${it.stageName}" } ?: ""),
                    actions = {
                        IconButton(
                            onClick = {
                                editingTask = null
                                showTaskEditor = true
                            }
                        ) {
                            Icon(
                                AppIcons.Add,
                                contentDescription = "添加任务",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
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

            if (!state.hasActivePlan) {
                item(key = "plan-empty") {
                    PlanGuideCard(
                        onOpenPlan = {
                            navController.navigate(Routes.planInterview()) { launchSingleTop = true }
                        }
                    )
                }
            }

            if (state.hasActivePlan) {
                state.phase?.let { info ->
                    item(key = "phase-strip") {
                        PhaseStrip(info = info)
                    }
                }
            }

            if (state.hasActivePlan) {
                val checkup = checkupState.checkup
                if (checkup != null && !checkupState.dismissed) {
                    item(key = "plan-checkup") {
                        PlanCheckupCard(
                            checkup = checkup,
                            onAdjust = {
                                navController.navigate(
                                    Routes.planInterview(
                                        mode = "adjust",
                                        prefill = CheckupFormat.adjustPrefill(checkup)
                                    )
                                ) { launchSingleTop = true }
                            },
                            onDismiss = { checkupVm.dismissForToday() }
                        )
                    }
                }
            }

            if (state.hasActivePlan) {
                item(key = "today-progress") {
                    TodayProgress(
                        done = state.doneCount,
                        total = state.totalCount,
                        timerRunning = state.timerRunning,
                        weekMinutes = state.weekMinutes,
                        weekGoalMin = derivedWeekGoalMin(state),
                        todayPomodoros = state.todayPomodoros,
                        dailyPomodoroGoal = derivedDailyGoal(state)
                    )
                }
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
                onPostponeRequest = { if (!it.isServerPlanItem()) postponeCandidate = it },
                onStartFocus = {
                    vm.startFocusFor(it)
                    navController.navigate(Routes.FOCUS) { launchSingleTop = true }
                },
                onTaskClick = {
                    if (it.isServerPlanItem()) {
                        navController.navigate(Routes.PLAN) { launchSingleTop = true }
                    } else {
                        editingTask = it
                        showTaskEditor = true
                    }
                },
                onAddTask = {
                    editingTask = null
                    showTaskEditor = true
                },
                onBatchImport = { showBatchImport = true }
            )

            if (state.hasActivePlan) {
                item(key = "rhythm-strip") {
                    TodayRhythmCard(
                        tpl = todayTemplate,
                        rows = rhythmRows,
                        now = state.now,
                        focusMinutes = state.focusMinutes,
                        doneTaskIds = doneTaskIds
                    )
                }
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
        val isPlanItem = candidate.isServerPlanItem()
        AlertDialog(
            onDismissRequest = { postponeCandidate = null },
            title = { Text(if (isRepeat || isPlanItem) "无法顺延" else "顺延到明天?") },
            text = {
                Text(
                    when {
                        isPlanItem -> "「${candidate.title}」属于云端计划，不能在首页单独顺延。"
                        isRepeat -> "「${candidate.title}」按重复规则自动出现,无法单独顺延。如需调整,请点击任务修改重复规则。"
                        else -> "「${candidate.title}」将推迟到明天同一时刻,今日列表将不再显示。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        postponeCandidate = null
                        if (!isRepeat && !isPlanItem) postponeWithUndo(candidate)
                    }
                ) { Text(if (isRepeat || isPlanItem) "知道了" else "顺延") }
            },
            dismissButton = {
                if (!isRepeat && !isPlanItem) {
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
            serverHint = checkupState.checkup?.let { c ->
                if (c.weekPlanTotal > 0) {
                    "本周计划完成 ${c.weekPlanDone}/${c.weekPlanTotal} · 专注 ${c.weekFocusMinutes} 分钟"
                } else {
                    "本周还没有云端计划任务"
                }
            },
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
}

/** 未生成 AI 计划时的引导空态:不展示阶段进度/今日节奏/进度卡,只引导去和 AI 制定计划 */
@Composable
private fun PlanGuideCard(onOpenPlan: () -> Unit) {
    GuideAnchor(GuideAnchors.HOME_PLAN_GUIDE) {
        HeroCard(
            title = "还没有学习计划",
            desc = "和 AI 聊 5 分钟，按目标院校与每天可用时间排一份全程计划。",
            icon = AppIcons.Sparkles,
            colors = actionCardGradient(),
            onClick = onOpenPlan,
            actionLabel = "开始制定"
        )
    }
}

/** 阶段进度条:当前阶段名 + 第几天 + 各阶段占比轨道(结构来自生效 AI 计划的 stages) */
@Composable
private fun PhaseStrip(info: StageInfo) {
    AppCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                info.stageName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "第 ${info.dayIdx} / ${info.totalDays} 天",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            info.stageDays.forEachIndexed { index, days ->
                val isCurrent = index == info.currentIndex
                val isPast = index < info.currentIndex
                Box(
                    Modifier
                        .weight(days.toFloat())
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
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
        Text(
            when {
                info.daysToEnd <= 0 -> "阶段收官在即"
                info.nextStageName != null -> "${info.daysToEnd} 天后进入${info.nextStageName}"
                else -> "距考试 ${info.daysToEnd} 天"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 今日节奏:待办合并为整段任务时段(去掉逐番茄与小憩/长休明细),当前时段以「现在」高亮 */
@Composable
private fun TodayRhythmCard(
    tpl: RhythmEngine.DayTemplate,
    rows: List<RhythmEngine.RhythmRow>,
    now: Long,
    focusMinutes: Int = 25,
    doneTaskIds: Set<Long> = emptySet()
) {
    var expanded by remember { mutableStateOf(false) }
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
                RhythmEngine.RhythmRow(
                    time = first.time.substringBefore("–") + "–" + last.time.substringAfterLast("–"),
                    label = first.label.substringBefore(" · "),
                    tag = first.tag,
                    minutes = group.sumOf { it.minutes },
                    isTask = true,
                    taskId = first.taskId
                )
            }
    }
    val activeIdx = remember(slots, localNow) { RhythmEngine.activeRhythmIndex(slots, localNow) }
    val totalMinutes = slots.sumOf { it.minutes }
    val totalPomodoros = if (focusMinutes > 0) totalMinutes / focusMinutes else 0
    // 概览行进度按任务真实完成态统计,分母与展开后的任务段一致
    val doneSegments = remember(slots, doneTaskIds) {
        slots.count { it.taskId != null && it.taskId in doneTaskIds }
    }

    AppCard(onClick = { expanded = !expanded }) {
        // 收起态:计划名 + 一句话概览 + 展开箭头;点击整卡展开/收起各时段明细
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("今日节奏", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (slots.isEmpty()) "${tpl.name} · 净学习 ${tpl.hours}"
                    else "${tpl.name} · 净学习 ${formatStudyMinutes(totalMinutes)} · ${totalPomodoros} 个番茄 · ${doneSegments}/${slots.size} 段",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
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
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(
                            row.time,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.widthIn(min = 64.dp)
                        )
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(rhythmTagColor(row.tag))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            row.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (active) {
                            Spacer(Modifier.width(8.dp))
                            StatusPill("现在", PillTone.ACCENT)
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

/** 今日番茄目标:今天有计划任务就按计划的预计番茄合计,否则回退设置里的手动目标 */
private fun derivedDailyGoal(state: HomeUiState): Int =
    state.todayPlan.totalPomodoros.takeIf { it > 0 } ?: state.dailyPomodoroGoal

/** 周净学习目标:本周有计划任务就按计划合计 × focusMinutes,否则回退设置里的手动目标 */
private fun derivedWeekGoalMin(state: HomeUiState): Int =
    (state.weekPlanPomodoros * state.focusMinutes).takeIf { it > 0 } ?: state.weekGoalMin

/** 标签 → 学科色(与计划页保持一致) */
private fun rhythmTagColor(tag: String): Color = when (tag) {
    RhythmEngine.TAG_MATH -> MathColor
    RhythmEngine.TAG_CS -> SubjectCsColor
    RhythmEngine.TAG_EN -> EnglishColor
    RhythmEngine.TAG_POL -> PoliticsColor
    else -> NeutralTagColor
}

/** 今日概览:任务 / 番茄 / 本周净学习三行紧凑进度(周净学习 37–40h 是作战计划的核心硬指标) */
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
    val taskProgress = if (total == 0) 0f else done.toFloat() / total
    val pomodoroProgress = if (dailyPomodoroGoal <= 0) 0f else todayPomodoros.toFloat() / dailyPomodoroGoal
    val weekProgress = if (weekGoalMin <= 0) 0f else weekMinutes.toFloat() / weekGoalMin
    val weekReached = weekMinutes >= weekGoalMin

    GuideAnchor(GuideAnchors.HOME_PROGRESS) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今日概览",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (timerRunning) {
                    Icon(
                        AppIcons.Flame,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "专注中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            ProgressRow(label = "今日任务", value = "$done/$total", progress = taskProgress)
            ProgressRow(
                label = "今日番茄",
                value = "$todayPomodoros/$dailyPomodoroGoal",
                progress = pomodoroProgress,
                icon = AppIcons.Timer
            )
            ProgressRow(
                label = "本周净学习",
                value = "${formatStudyMinutes(weekMinutes)} / ${formatStudyMinutes(weekGoalMin)}",
                progress = weekProgress,
                color = if (weekReached) successContentColor else MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 概览单行:标签 + 数值(右对齐)+ 进度线 */
@Composable
private fun ProgressRow(
    label: String,
    value: String,
    progress: Float,
    color: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
        Spacer(Modifier.height(6.dp))
        ProgressLine(progress = progress, color = color)
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
