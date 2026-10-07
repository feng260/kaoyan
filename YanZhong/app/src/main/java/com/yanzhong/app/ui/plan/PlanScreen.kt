package com.yanzhong.app.ui.plan

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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.data.remote.AdjustmentDto
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.CoralRed
import com.yanzhong.app.ui.theme.EnglishColor
import com.yanzhong.app.ui.theme.MathColor
import com.yanzhong.app.ui.theme.MintGreen
import com.yanzhong.app.ui.theme.PlanAccent
import com.yanzhong.app.ui.theme.PlanAccent2
import com.yanzhong.app.ui.theme.PoliticsColor
import com.yanzhong.app.ui.theme.SubjectCsColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZoneId
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
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            com.yanzhong.app.ui.theme.PageHeader(
                title = "计划",
                subtitle = "AI 按你的备考档案排的计划"
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 概览卡:计划名 + 关键元信息,一行一项对齐阅读
        PlanCard {
            Column(Modifier.padding(16.dp)) {
                Text(plan.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                PlanMetaRow("考期", plan.examDate)
                PlanMetaRow("总进度", "${plan.progress.doneItems}/${plan.progress.totalItems} 项已完成")
                PlanMetaRow("当前阶段", current?.name ?: "当前日期不在计划阶段内")
            }
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
                        style = MaterialTheme.typography.bodySmall,
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
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Icon(AppIcons.Sparkles, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (plan.stale) "重新生成计划" else "调整档案并重新生成",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        // 二级入口收进行卡(Mine 页同款):三个全宽大按钮改成三行入口,页面立刻轻下来
        PlanCard {
            PlanEntryRow(
                icon = AppIcons.Chat,
                title = "AI 调整近期计划",
                subtitle = "临时有事、生病、换课表,说一句就重排",
                onClick = onOpenAssistant
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.padding(start = 46.dp))
            if (plan.document != null) {
                PlanEntryRow(
                    icon = AppIcons.Doc,
                    title = "全程规划文档",
                    subtitle = "目标 · 阶段 · 作息 · 军规,8 章作战计划书",
                    onClick = onOpenDocument
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.padding(start = 46.dp))
            }
            PlanEntryRow(
                icon = AppIcons.ClipboardList,
                title = "历史计划",
                subtitle = "回看已归档的旧版本",
                onClick = onOpenHistory
            )
        }

        // 撤销最近一次已生效的行程调整(spec §2 可撤销):无新打卡时才可用,服务端会再校验一次
        if (latestAdjustment?.status == "applied") {
            TextButton(onClick = onUndo, enabled = !busy && !undoing, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.RotateCcw, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (undoing) "正在撤销…"
                    else "撤销上次调整${latestAdjustment?.summary?.let { " · $it" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        // 当日卡:日期切换 + 当日进度 + 计划项勾选列表
        PlanCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (selectedDate == today) "今日 · $selectedDate" else "当日 · $selectedDate",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { selectedDateText = today.toString() }, enabled = selectedDate != today) {
                        Text("回到今天", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { showDatePicker = true }) {
                        Text("选日期", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "当日完成 $done / $total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (total == 0) {
                    Text("这一天暂无计划项", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                plan.dailyItems(selectedDate).forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.status == "done", onCheckedChange = { onToggle(item) },
                            enabled = !busy)
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(planSubjectColor(item.subject))
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium)
                            Text("${item.subject} · ${item.minutes} 分钟", style = MaterialTheme.typography.labelSmall,
                                color = planSubjectColor(item.subject))
                        }
                    }
                    if (index < plan.dailyItems(selectedDate).size - 1) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 52.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---------- 计划页卡片基元(与我的页分组卡同语言) ----------

/** 计划页分组卡:浅色 surface + 1dp tonal,不再让内容裸排在页面背景上 */
@Composable
private fun PlanCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

/** 元信息行:固定宽度标签 + 值,概览卡里对齐阅读 */
@Composable
private fun PlanMetaRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 入口行:图标 chip + 标题/说明 + 右箭头(与我的页 NavRow 同构) */
@Composable
private fun PlanEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** 科目名 → 稳定配色(哈希取色,同科目恒同色) */
private fun planSubjectColor(subject: String): Color {
    val palette = listOf(MathColor, SubjectCsColor, EnglishColor, PoliticsColor, MintGreen, CoralRed, PlanAccent, PlanAccent2)
    return palette[abs(subject.hashCode()) % palette.size]
}


