package com.yanzhong.app.ui.plan

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yanzhong.app.data.db.RepeatRule
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
import java.time.ZoneId

private val TABS = listOf("本周", "日模板", "全程", "军规·资料")

/** 计划页:本周任务 / 日模板 / 全程规划 / 军规资料——468 天作战计划的 App 内全景 */
@Composable
fun PlanScreen(padding: PaddingValues) {
    val vm: PlanViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val weekStart = state.weekStart
    val days = (0..6L).map { weekStart + it * 24 * 3600 * 1000 }
    val phaseInfo = remember(state.today) { Phases.phaseInfo(state.today) }
    val todayTemplate = remember(state.today) { PersonalPlan.todayTemplate(state.today) }
    val todayDate = remember(state.today) {
        Instant.ofEpochMilli(state.today).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val weekLabel = remember(todayDate) { PersonalPlan.teachingWeekLabel(todayDate) }
    val expanded = isExpanded()

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
                subtitle = "468 天作战计划 · 408 / 数学二 / 英语二 / 政治"
            ) {
                if (weekLabel != null) {
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
            }
        }

        item(key = "tabs") {
            TabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { index, label ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(label, maxLines = 1) }
                    )
                }
            }
        }

        when (tab) {
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
