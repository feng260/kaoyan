package com.yanzhong.app.ui.plan

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.data.remote.AdjustmentDto
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.data.remote.PlanItemDto
import com.yanzhong.app.data.remote.PlanStageDto
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CompletionCircle
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.CoralRed
import com.yanzhong.app.ui.theme.EmptyState
import com.yanzhong.app.ui.theme.EnglishColor
import com.yanzhong.app.ui.theme.ListRow
import com.yanzhong.app.ui.theme.MathColor
import com.yanzhong.app.ui.theme.MintGreen
import com.yanzhong.app.ui.theme.PageHeader
import com.yanzhong.app.ui.theme.PillTone
import com.yanzhong.app.ui.theme.PlanAccent
import com.yanzhong.app.ui.theme.PlanAccent2
import com.yanzhong.app.ui.theme.PoliticsColor
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.ProgressLine
import com.yanzhong.app.ui.theme.RowDivider
import com.yanzhong.app.ui.theme.RowGroup
import com.yanzhong.app.ui.theme.SkeletonBlock
import com.yanzhong.app.ui.theme.StatusPill
import com.yanzhong.app.ui.theme.SubjectCsColor
import com.yanzhong.app.ui.theme.TextAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** 计划页:云端 AI 计划的主卡与状态机(登录 / 无计划 / 网络错误 / 加载) */
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

    val todayDate = remember(state.today) {
        Instant.ofEpochMilli(state.today).atZone(ZoneId.systemDefault()).toLocalDate()
    }

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                PageHeader(
                    title = "计划",
                    meta = "AI 按你的备考档案排的计划",
                    actions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { navController.navigate(Routes.PLAN_HISTORY) }) {
                                Icon(AppIcons.ClipboardList, contentDescription = "历史计划")
                            }
                            IconButton(onClick = vm::refreshPlan, enabled = loggedIn && !planLoading) {
                                Icon(AppIcons.RotateCcw, contentDescription = "刷新")
                            }
                        }
                    }
                )
            }

            item(key = "server-plan") {
                when (selectPlanDisplayState(loggedIn, serverPlan, planError != null, planLoading)) {
                    PlanDisplayState.READY -> Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        planError?.let { message ->
                            PlanErrorBanner(
                                message = message,
                                onRetry = vm::refreshPlan,
                                onDismiss = vm::clearPlanError
                            )
                        }
                        ServerPlanSummary(
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
                    }
                    PlanDisplayState.LOGIN_REQUIRED -> PlanActionCard(
                        icon = AppIcons.CloudUpload,
                        title = "登录后就有了云端计划",
                        desc = "计划存在账号里，换台设备登录还是同一份。",
                        primaryLabel = "前往登录",
                        onPrimary = { navController.navigate(Routes.ACCOUNT) }
                    )
                    PlanDisplayState.NO_PLAN -> PlanActionCard(
                        icon = AppIcons.Chat,
                        title = "还没有学习计划",
                        desc = "和 AI 聊 5 分钟，按目标院校与每天可用时间排一份全程计划。",
                        primaryLabel = "开始制定",
                        onPrimary = { navController.navigate(Routes.planInterview()) },
                        secondaryLabel = "看历史计划",
                        onSecondary = { navController.navigate(Routes.PLAN_HISTORY) }
                    )
                    PlanDisplayState.NETWORK_ERROR -> PlanActionCard(
                        icon = AppIcons.Info,
                        title = "未能读取云端计划",
                        desc = if (planError?.contains("timeout", ignoreCase = true) == true) {
                            "请求超时，稍后再试"
                        } else {
                            "网络或服务暂时不可用"
                        },
                        primaryLabel = "重试",
                        onPrimary = vm::refreshPlan
                    )
                    PlanDisplayState.LOADING -> PlanLoadingCard()
                }
            }
        }
    }
}

/**
 * 计划页的主操作卡:登录 / 还没计划 / 读不到计划 三种情况共用。
 * 统一用无阴影 AppCard,把说明收短,把唯一该做的动作摆成实心主按钮。
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
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            PrimaryButton(primaryLabel, onPrimary, icon = AppIcons.Sparkles)
            if (secondaryLabel != null && onSecondary != null) {
                TextAction(secondaryLabel, onSecondary, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 加载卡:三条骨架直接示意"计划即将铺开",不转圈也不整卡脉冲 */
@Composable
private fun PlanLoadingCard() {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBlock(Modifier.fillMaxWidth(), height = 14.dp)
            SkeletonBlock(Modifier.fillMaxWidth(0.72f), height = 14.dp)
            SkeletonBlock(Modifier.fillMaxWidth(0.86f), height = 14.dp)
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
    onToggle: (PlanItemDto) -> Unit,
    onOpenSetup: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenDocument: () -> Unit,
    onOpenHistory: () -> Unit
) {
    var selectedDateText by rememberSaveable(plan.id) { mutableStateOf(today.toString()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val selectedDate = LocalDate.parse(selectedDateText)
    val stages = plan.stages.sortedBy { it.sortOrder }
    val current = plan.currentStage(today)
    val nextStage = stages.firstOrNull { it.startDate > today.toString() }
    val (dayInStage, stageTotalDays) = stageDayInfo(current, today)
    val stageLabel = current?.let { "第 $dayInStage / $stageTotalDays 天" } ?: "当前日期不在计划阶段内"
    val (done, total) = plan.dailyProgress(selectedDate)
    val dayItems = plan.dailyItems(selectedDate)
    val agenda = remember(dayItems) { buildDayAgenda(dayItems) }
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 概览卡:当前阶段与第几天、全程进度、下一阶段
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        plan.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${current?.name ?: "全程计划"} · $stageLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill("进行中", PillTone.ACCENT)
            }
            Spacer(Modifier.height(10.dp))
            ProgressLine(progress = plan.progress.doneItems.toFloat() / plan.progress.totalItems.coerceAtLeast(1))
            Spacer(Modifier.height(6.dp))
            Text(
                "全程 ${plan.progress.doneItems}/${plan.progress.totalItems} 项 · 考期 ${plan.examDate}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "下一阶段：${nextStage?.name ?: "已进入最后阶段"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 档案改过但计划还没重建:用醒目横幅说明"为什么内容和档案对不上",按钮直奔重新生成
        if (plan.stale) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        "备考档案改过了,这份计划还是按老档案排的",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "重新生成会出一份新计划,旧的会归档留住。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // 主操作:任何时候都能从这里去改档案 / 重新生成,这是这一页的中心入口
        PrimaryButton(
            text = if (plan.stale) "重新生成计划" else "调整档案并重新生成",
            onClick = onOpenSetup,
            enabled = !busy,
            icon = AppIcons.Sparkles
        )

        // 二级入口收进行卡:三个全宽大按钮改成三行入口,页面立刻轻下来
        RowGroup {
            ListRow(
                title = "调整近期计划",
                subtitle = "临时有事、生病、换课表，说一句就重排",
                icon = AppIcons.Chat,
                onClick = onOpenAssistant
            )
            RowDivider()
            if (plan.document != null) {
                ListRow(
                    title = "全程规划文档",
                    subtitle = "目标 · 阶段 · 作息 · 军规",
                    icon = AppIcons.Doc,
                    onClick = onOpenDocument
                )
                RowDivider()
            }
            ListRow(
                title = "历史计划",
                subtitle = "回看已归档的旧版本",
                icon = AppIcons.ClipboardList,
                onClick = onOpenHistory
            )
        }

        // 撤销最近一次已生效的行程调整(spec §2 可撤销):无新打卡时才可用,服务端会再校验一次
        if (latestAdjustment?.status == "applied") {
            RowGroup {
                ListRow(
                    title = "最近调整",
                    subtitle = latestAdjustment?.summary,
                    icon = AppIcons.RotateCcw,
                    onClick = if (!busy && !undoing) onUndo else null,
                    showChevron = false,
                    trailing = {
                        Text(
                            if (undoing) "撤销中…" else "撤销",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (busy || undoing) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                )
            }
        }

        // 当日卡:日期切换 + 当日进度 + 计划项勾选列表
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selectedDate == today) "今日计划" else "当日计划",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextAction(
                    "回到今天",
                    onClick = { selectedDateText = today.toString() },
                    enabled = selectedDate != today
                )
                TextAction("选日期", onClick = { showDatePicker = true })
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "$selectedDate ${weekdayLabel(selectedDate)} · 完成 $done / $total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            ProgressLine(progress = if (total == 0) 0f else done.toFloat() / total)
            if (dayItems.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                DayAgendaSummary(agenda)
            }
            Spacer(Modifier.height(4.dp))
            if (dayItems.isEmpty()) {
                EmptyState(icon = AppIcons.Calendar, title = "这一天没有安排", compact = true)
            } else {
                agenda.slots.forEachIndexed { slotIndex, slot ->
                    if (slotIndex > 0) Spacer(Modifier.height(6.dp))
                    AgendaSlotHeader(slot)
                    slot.items.forEachIndexed { index, entry ->
                        if (index > 0) RowDivider(startIndent = 48.dp)
                        AgendaTaskRow(entry = entry, busy = busy, onToggle = onToggle)
                    }
                }
            }
        }
    }
}

/** 全天时间跨度与总计划时长:先给一个整体轮廓,再由下面的时段逐段展开 */
@Composable
private fun DayAgendaSummary(agenda: DayAgenda) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "计划 ${formatMinutesCn(agenda.totalMinutes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        agenda.rangeLabel?.let { range ->
            Text(
                range,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 时段小标题:上午/下午/晚上 + 本段时间跨度、休息与任务量 */
@Composable
private fun AgendaSlotHeader(slot: AgendaSlot) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            slot.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            listOfNotNull(
                slot.rangeLabel,
                slot.breakMinutes?.takeIf { it >= 5 }?.let { "休息 ${formatMinutesCn(it)}" }
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${slot.items.size} 项 · ${formatMinutesCn(slot.minutes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 单条计划:精确起止时间用主色突出,科目与时长退到次级信息 */
@Composable
private fun AgendaTaskRow(
    entry: AgendaItem,
    busy: Boolean,
    onToggle: (PlanItemDto) -> Unit
) {
    val item = entry.item
    val done = item.status == "done"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) { onToggle(item) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompletionCircle(
            checked = done,
            onClick = { onToggle(item) },
            enabled = !busy
        )
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(planSubjectColor(item.subject))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.timeRange?.let { range ->
                    Text(
                        range,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    listOfNotNull(
                        item.subject,
                        item.minutes.takeIf { it > 0 }?.let { "$it 分钟" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 阶段第几天 / 总天数:日期解析失败时退化为 today,保证概览永远有数字 */
private fun stageDayInfo(stage: PlanStageDto?, today: LocalDate): Pair<Int, Int> {
    if (stage == null) return 0 to 0
    val start = runCatching { LocalDate.parse(stage.startDate) }.getOrDefault(today)
    val end = runCatching { LocalDate.parse(stage.endDate) }.getOrDefault(today)
    val total = (ChronoUnit.DAYS.between(start, end).toInt() + 1).coerceAtLeast(1)
    val day = (ChronoUnit.DAYS.between(start, today).toInt() + 1).coerceIn(1, total)
    return day to total
}

/** 科目名 → 稳定配色(哈希取色,同科目恒同色) */
private fun planSubjectColor(subject: String): Color {
    val palette = listOf(MathColor, SubjectCsColor, EnglishColor, PoliticsColor, MintGreen, CoralRed, PlanAccent, PlanAccent2)
    return palette[abs(subject.hashCode()) % palette.size]
}

@Composable
private fun PlanErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextAction(text = "重试", onClick = onRetry)
                Spacer(Modifier.width(8.dp))
                TextAction(text = "关闭", onClick = onDismiss)
            }
        }
    }
}
