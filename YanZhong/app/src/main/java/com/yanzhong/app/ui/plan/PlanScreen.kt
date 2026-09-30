package com.yanzhong.app.ui.plan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.data.db.RepeatRule
import com.yanzhong.app.data.remote.AdjustmentDto
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.isExpanded
import com.yanzhong.app.util.PersonalPlan
import com.yanzhong.app.util.PhaseInfo
import com.yanzhong.app.util.Phases
import com.yanzhong.app.util.TimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZoneId

private val TABS = listOf("本周", "日模板", "全程", "军规·资料")

/** 计划页:本周任务 / 日模板 / 全程规划 / 军规资料——468 天作战计划的 App 内全景 */
@Composable
fun PlanScreen(padding: PaddingValues, navController: NavHostController) {
    val vm: PlanViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val serverPlan by vm.serverPlan.collectAsStateWithLifecycle()
    val planLoading by vm.planLoading.collectAsStateWithLifecycle()
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val planError by vm.planError.collectAsStateWithLifecycle()
    val updatingItemId by vm.updatingItemId.collectAsStateWithLifecycle()
    val latestAdjustment by vm.latestAdjustment.collectAsStateWithLifecycle()
    val undoing by vm.undoing.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var referenceExpanded by rememberSaveable { mutableStateOf(false) }

    val weekStart = state.weekStart
    val days = (0..6L).map { weekStart + it * 24 * 3600 * 1000 }
    val phaseInfo = remember(state.today) { Phases.phaseInfo(state.today) }
    val todayTemplate = remember(state.today) { PersonalPlan.todayTemplate(state.today) }
    val todayDate = remember(state.today) {
        Instant.ofEpochMilli(state.today).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val weekLabel = remember(todayDate) { PersonalPlan.teachingWeekLabel(todayDate) }
    val expanded = isExpanded()

    // 从"制定/调整备考档案"页回来:计划可能刚被重新生成,静默刷一次
    LaunchedEffect(Unit) {
        val handle = navController.currentBackStackEntry?.savedStateHandle
        if (handle?.remove<Boolean>(Routes.EXTRA_PLAN_SETUP_DONE) == true) vm.refreshPlan()
    }

    // 平板:限宽 720dp 居中
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        modifier = Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
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
        item {
            com.yanzhong.app.ui.theme.PageHeader(
                title = "计划",
                subtitle = "AI 按你的备考档案排的计划"
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (referenceExpanded && weekLabel != null) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                weekLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }
                    IconButton(onClick = { navController.navigate(Routes.PLAN_HISTORY) }) {
                        Icon(AppIcons.ClipboardList, contentDescription = "历史计划")
                    }
                    IconButton(onClick = vm::refreshPlan, enabled = loggedIn && !planLoading) {
                        Icon(AppIcons.RotateCcw, contentDescription = "刷新")
                    }
                }
            }
        }

        item(key = "server-plan") {
            when (selectPlanDisplayState(loggedIn, serverPlan, planError != null, planLoading)) {
                PlanDisplayState.READY -> ServerPlanSummary(
                    plan = serverPlan!!,
                    today = todayDate,
                    updatingItemId = updatingItemId,
                    latestAdjustment = latestAdjustment,
                    undoing = undoing,
                    onUndo = vm::undoLatestAdjustment,
                    onToggle = vm::toggleItem,
                    onOpenSetup = { navController.navigate(Routes.planSetup()) },
                    onOpenAssistant = {
                        // 显式声明「调整模式」:面谈页只看有没有生效计划的话,这条入口也会被猜成制定
                        navController.navigate(Routes.planInterview("adjust"))
                    },
                    onOpenDocument = { navController.navigate(Routes.PLAN_DOCUMENT) },
                    onOpenHistory = { navController.navigate(Routes.PLAN_HISTORY) }
                )
                PlanDisplayState.LOGIN_REQUIRED -> PlanActionCard(
                    icon = AppIcons.CloudUpload,
                    title = "登录后就有了云端计划",
                    desc = "计划存在账号里,换台设备登录还是同一份,手机上勾掉的打卡会自动同步过去。",
                    primaryLabel = "前往登录",
                    onPrimary = { navController.navigate(Routes.ACCOUNT) }
                )
                PlanDisplayState.NO_PLAN -> PlanActionCard(
                    icon = AppIcons.Chat,
                    title = "还没有计划",
                    desc = "一份几百天的全程计划,靠两页问卷是排不出来的。先和 AI 聊几句——" +
                        "它问清你的目标院校、每天能学多久、哪科最弱,再照着一整份规划书的样子给你排。",
                    primaryLabel = "和 AI 聊 5 分钟,制定计划",
                    onPrimary = { navController.navigate(Routes.planInterview()) },
                    secondaryLabel = "看历史计划",
                    onSecondary = { navController.navigate(Routes.PLAN_HISTORY) }
                )
                PlanDisplayState.NETWORK_ERROR -> PlanActionCard(
                    icon = AppIcons.Info,
                    title = "没能读到云端计划",
                    desc = planError ?: "网络异常,稍后再试一次。你已经勾掉的打卡不会丢。",
                    primaryLabel = "重试",
                    onPrimary = vm::refreshPlan
                )
                PlanDisplayState.LOADING -> PlanLoadingCard()
            }
        }

        item(key = "tabs") {
            Surface(onClick = { referenceExpanded = !referenceExpanded }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("本地固定 468 天计划 · 仅供参考", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall)
                    Icon(if (referenceExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                        contentDescription = if (referenceExpanded) "收起参考" else "展开参考")
                }
            }
            if (referenceExpanded) {
                TabRow(selectedTabIndex = tab) {
                    TABS.forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index },
                            text = { Text(label, maxLines = 1) })
                    }
                }
            }
        }

        if (referenceExpanded) when (tab) {
            // ---------- 本周 ----------
            0 -> {
                phaseInfo?.let { info ->
                    item(key = "phase-timeline") { PhaseTimeline(info = info) }
                }
                // 周期模板按星期展开:每日任务全周常驻,每周任务落在自己的日子——导入的计划从此天天可见
                val templates = state.openTasks.filter { it.repeatRule != RepeatRule.NONE }
                val weekEnd = weekStart + 7L * 24 * 3600 * 1000 - 1
                days.forEach { dayStart ->
                    val dow = TimeUtils.dayOfWeekIndex(dayStart)
                    val expanded = templates.filter { template ->
                        template.repeatRule == RepeatRule.DAILY ||
                            (template.repeatRule == RepeatRule.WEEKLY &&
                                (template.repeatDays shr dow) and 1 == 1)
                    }
                    val dated = state.weekTasks.filter {
                        it.dueAt != null && TimeUtils.isSameDay(it.dueAt!!, dayStart)
                    }
                    val dayTasks = (dated + expanded)
                        .sortedWith(compareBy({ it.priority }, { it.dueAt ?: Long.MAX_VALUE }))
                    item(key = "day-$dayStart") {
                        DayCard(
                            dayStart = dayStart,
                            tasks = dayTasks,
                            subjects = state.subjects,
                            isToday = TimeUtils.isSameDay(state.today, dayStart)
                        )
                    }
                }
                // 近期里程碑:本周之后的里程碑任务按截止日排列,不再"导入后看不见"
                val milestones = state.openTasks
                    .filter { it.repeatRule == RepeatRule.NONE && it.dueAt != null && it.dueAt > weekEnd }
                    .sortedBy { it.dueAt }
                if (milestones.isNotEmpty()) {
                    item(key = "milestones") {
                        MilestoneCard(
                            tasks = milestones.take(8),
                            subjects = state.subjects,
                            more = milestones.size - 8
                        )
                    }
                }
            }

            // ---------- 日模板 ----------
            1 -> {
                todayTemplate?.let { tpl ->
                    item(key = "today-template") { TodayTemplateCard(tpl, todayDate) }
                }
                item(key = "template-groups") { TemplateGroupsCard() }
                item(key = "course-table") { CourseTableCard() }
                item(key = "weekly-rhythm") { WeeklyRhythmCard() }
                item(key = "review-questions") { ReviewQuestionsCard() }
            }

            // ---------- 全程 ----------
            2 -> {
                if (expanded) {
                    item(key = "plan-master-detail") {
                        PlanMasterDetail(today = state.today, todayDate = todayDate)
                    }
                } else {
                    item(key = "score-targets") { ScoreTargetsCard() }
                    item(key = "phases-overview") { PhasesCard(state.today) }
                    item(key = "campaign-calendar") { CampaignCalendarCard(todayDate) }
                    PersonalPlan.subjectPlans.forEach { plan ->
                        item(key = "subject-${plan.name}") { SubjectPlanCard(plan) }
                    }
                    item(key = "launch-weeks") { LaunchWeeksCard() }
                    item(key = "key-dates") { KeyDatesCard() }
                }
            }

            // ---------- 军规·资料 ----------
            else -> {
                item(key = "rules") { RulesCard() }
                item(key = "materials") { MaterialsCard() }
            }
        }
    }
    }
}

/**
 * 计划页的主操作卡:登录 / 还没计划 / 读不到计划 三种情况共用。
 *
 * 之前这些状态只有一行小字加一个 TextButton,藏在标题下面,用户找不到"去哪制定计划";
 * 换成整卡 + 实心主按钮,把唯一该做的动作摆到最显眼的位置。
 */
@Composable
private fun PlanActionCard(
    icon: ImageVector,
    title: String,
    desc: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surface,
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(30.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Button(
                onClick = onPrimary,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(primaryLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}

/**
 * 加载卡:用骨架屏脉冲代替转圈。
 * 这里等的是"一整份全程规划文档",骨架条能比 spinner 更直观地说明内容即将铺开,
 * 也避免转圈在低端机上持续重绘动画带来的额外开销(脉冲只有一个 alpha 在变)。
 */
@Composable
private fun PlanLoadingCard() {
    val transition = rememberInfiniteTransition(label = "plan-loading")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "plan-loading-pulse"
    )
    val bar = MaterialTheme.colorScheme.surfaceVariant
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "正在获取云端计划…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            listOf(1f, 0.72f, 0.86f).forEach { fraction ->
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(bar.copy(alpha = pulse))
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerPlanSummary(
    plan: PlanDto,
    today: LocalDate,
    updatingItemId: Long?,
    latestAdjustment: AdjustmentDto?,
    undoing: Boolean,
    onUndo: () -> Unit,
    onToggle: (com.yanzhong.app.data.remote.PlanItemDto) -> Unit,
    onOpenSetup: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenDocument: () -> Unit,
    onOpenHistory: () -> Unit
) {
    var selectedDateText by rememberSaveable(plan.id) { mutableStateOf(today.toString()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val selectedDate = LocalDate.parse(selectedDateText)
    val current = plan.currentStage(today)
    val (done, total) = plan.dailyProgress(selectedDate)
    val busy = updatingItemId != null
    if (showDatePicker) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        selectedDateText = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            }, dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }) { DatePicker(state = picker) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(plan.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        // 档案改过但计划还没重建:用醒目横幅说明"为什么内容和档案对不上",按钮直奔重新生成
        if (plan.stale) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "备考档案改过了,这份计划还是按老档案排的",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "重新生成会出一份新计划,旧的会归档留住。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // 主操作:任何时候都能从这里去改档案 / 重新生成,这是这一页的中心入口
        Button(
            onClick = onOpenSetup,
            enabled = !busy,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(AppIcons.Sparkles, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (plan.stale) "重新生成计划" else "调整档案并重新生成",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        // 行程小助手:同一份生效计划上的「临时有事」入口。和上面「改档案重建」是两件事,
        // 分开两个按钮,考生一眼就知道点哪个会进哪个模式,不用再靠猜
        OutlinedButton(
            onClick = onOpenAssistant,
            enabled = !busy,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(AppIcons.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "有事？让 AI 调整近期计划",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        // AI 排出来的那份 8 章规划书:目标、阶段、各科、作息、启动周、资料、军规、时间线
        if (plan.document != null) {
            OutlinedButton(
                onClick = onOpenDocument,
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(AppIcons.Doc, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "查看全程规划文档",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 撤销最近一次已生效的行程调整(spec §2 可撤销):无新打卡时才可用,服务端会再校验一次
        if (latestAdjustment?.status == "applied") {
            TextButton(onClick = onUndo, enabled = !busy && !undoing, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.RotateCcw, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (undoing) "正在撤销…"
                    else "撤销上次调整${latestAdjustment?.summary?.let { " · $it" }.orEmpty()}")
            }
        }

        Text("考期 ${plan.examDate} · 总进度 ${plan.progress.doneItems}/${plan.progress.totalItems}",
            style = MaterialTheme.typography.bodyMedium)
        Text("当前阶段：${current?.name ?: "当前日期不在计划阶段内"}",
            style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${if (selectedDate == today) "今日" else "日期"} · $selectedDate",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { selectedDateText = today.toString() }, enabled = selectedDate != today) {
                Text("回到今天")
            }
            TextButton(onClick = { showDatePicker = true }) { Text("选日期") }
        }
        Text("当日完成 $done / $total", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total },
            modifier = Modifier.fillMaxWidth())
        if (total == 0) Text("这一天暂无计划项", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        plan.dailyItems(selectedDate).forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = item.status == "done", onCheckedChange = { onToggle(item) },
                    enabled = !busy)
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyMedium)
                    Text("${item.subject} · ${item.minutes} 分钟", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        TextButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
            Icon(AppIcons.ClipboardList, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("历史计划")
        }
    }
}

/** 平板「全程」双栏:左导航大纲(目标/阶段/作战日历/四科/启动周/关键日期),右详情主从浏览 */
@Composable
private fun PlanMasterDetail(today: Long, todayDate: LocalDate) {
    val planNames = PersonalPlan.subjectPlans.map { it.name }
    val outline = buildList {
        add("目标分数")
        add("五阶段总览")
        add("五阶段作战日历")
        addAll(planNames)
        add("启动四周")
        add("关键日期")
    }
    var selected by rememberSaveable { mutableIntStateOf(2) }
    val subjectStart = 3
    val launchIdx = subjectStart + planNames.size
    val keyIdx = launchIdx + 1

    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
    ) {
        // 左:大纲导航(固定宽,选中高亮)
        Surface(
            modifier = Modifier.width(200.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ) {
            Column(Modifier.padding(8.dp)) {
                outline.forEachIndexed { idx, label ->
                    val sel = idx == selected
                    Surface(
                        onClick = { selected = idx },
                        shape = RoundedCornerShape(10.dp),
                        color = if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (sel) FontWeight.SemiBold else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
        // 右:详情
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp)
        ) {
            when (selected) {
                0 -> ScoreTargetsCard()
                1 -> PhasesCard(today)
                2 -> CampaignCalendarCard(todayDate)
                in subjectStart until launchIdx -> SubjectPlanCard(
                    PersonalPlan.subjectPlans[selected - subjectStart]
                )
                launchIdx -> LaunchWeeksCard()
                keyIdx -> KeyDatesCard()
            }
        }
    }
}

/** 五阶段时间轴:段宽按天数比例,当前阶段高亮;附周目标/里程碑/科目占比 */
@Composable
private fun PhaseTimeline(info: PhaseInfo) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Phases.all.forEach { phase ->
                    Text(
                        phase.shortName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (phase == info.phase) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(phase.daysCount().toFloat())
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (info.phase.weeklyHours.isNotEmpty()) {
                Text(
                    "本阶段目标 ${info.phase.weeklyHours} h/周 · ${info.phase.alloc}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (info.phase.milestone.isNotEmpty()) {
                Text(
                    "里程碑:${info.phase.milestone}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 单日卡:手风琴式——今天默认展开,其余各天折叠为任务数摘要,点击展开明细 */
@Composable
private fun DayCard(
    dayStart: Long,
    tasks: List<TaskEntity>,
    subjects: List<SubjectEntity>,
    isToday: Boolean
) {
    var expanded by remember(isToday) { mutableStateOf(isToday) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.surface,
        tonalElevation = if (isToday) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = tasks.isNotEmpty()) { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${TimeUtils.weekdayName(dayStart)} · ${TimeUtils.formatMonthDay(dayStart)}" +
                        if (isToday) " · 今天" else "",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val totalPomos = tasks.sumOf { it.pomodoroEstimate }
                    if (tasks.isNotEmpty()) {
                        Text(
                            "${tasks.size} 项 · ${totalPomos}🍅",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    if (tasks.isEmpty()) {
                        Text(
                            "暂无任务",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        tasks.forEach { task ->
                            val subject = subjects.firstOrNull { it.id == task.subjectId }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 3.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            subject?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary
                                        )
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    task.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (task.dueAt != null) {
                                    Text(
                                        TimeUtils.formatHm(task.dueAt!!),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    "${task.pomodoroEstimate}🍅",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 近期里程碑:导入的全程里程碑任务,按截止日排序,日期与星期一目了然 */
@Composable
private fun MilestoneCard(tasks: List<TaskEntity>, subjects: List<SubjectEntity>, more: Int) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("近期里程碑 · 468 天全程", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            tasks.forEach { task ->
                val subject = subjects.firstOrNull { it.id == task.subjectId }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 3.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                subject?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        task.title.removePrefix("里程碑:"),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${TimeUtils.formatYmd(task.dueAt!!)} ${TimeUtils.weekdayName(task.dueAt!!)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (more > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "…另有 $more 项里程碑排在其后,临近截止时自动进入对应周",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
