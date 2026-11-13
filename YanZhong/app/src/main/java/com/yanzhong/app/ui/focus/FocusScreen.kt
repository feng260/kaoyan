package com.yanzhong.app.ui.focus

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.prefs.AppSettings
import com.yanzhong.app.data.remote.PeerState
import com.yanzhong.app.service.FocusService
import com.yanzhong.app.service.StrictGuardPolicy
import com.yanzhong.app.timer.Phase
import com.yanzhong.app.timer.TimerMode
import com.yanzhong.app.ui.guide.GuideAnchor
import com.yanzhong.app.ui.guide.GuideAnchors
import com.yanzhong.app.ui.guide.ProvideGuideSpec
import com.yanzhong.app.ui.guide.focusGuide
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.AppSheet
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.CountdownNumberStyle
import com.yanzhong.app.ui.theme.EmptyState
import com.yanzhong.app.ui.theme.FocusTimerStyle
import com.yanzhong.app.ui.theme.ListRow
import com.yanzhong.app.ui.theme.PageHeader
import com.yanzhong.app.ui.theme.PillTone
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.RowDivider
import com.yanzhong.app.ui.theme.RowGroup
import com.yanzhong.app.ui.theme.SectionHeader
import com.yanzhong.app.ui.theme.SegmentedChips
import com.yanzhong.app.ui.theme.StatusPill
import com.yanzhong.app.ui.theme.SuccessGreen
import com.yanzhong.app.ui.theme.SwitchRow
import com.yanzhong.app.ui.theme.TextAction
import com.yanzhong.app.ui.theme.ToggleChip
import com.yanzhong.app.util.TimeUtils
import kotlinx.coroutines.delay

/** 专注页:全屏番茄钟,环形进度,操作仅三键(PRD 4.2 ②) */
@Composable
fun FocusScreen(padding: PaddingValues) {
    val vm: FocusViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val timer = state.timer
    // 只在空闲态引导;计时中或休息中不打断
    // loaded 保证等引擎首个真实状态(冷启动恢复中的计时)到达后再判定就绪
    ProvideGuideSpec(focusGuide(), ready = timer.phase == Phase.IDLE && state.loaded)
    val view = LocalView.current

    val backContext = androidx.compose.ui.platform.LocalContext.current
    BackHandler(enabled = timer.phase != Phase.IDLE) {
        // 专注期间不允许通过系统返回误触退出;给出提示避免用户以为按键失灵
        android.widget.Toast.makeText(
            backContext, "专注进行中,计时结束后可返回", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    DisposableEffect(timer.phase != Phase.IDLE) {
        val window = (view.context as? Activity)?.window
        if (timer.phase != Phase.IDLE) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 运行态顶部淡淡一层阶段色渐变:专注=科目色,休息=绿,暂停=灰
    val isBreak = timer.phase == Phase.SHORT_BREAK || timer.phase == Phase.LONG_BREAK
    val phaseColor = when {
        timer.phase == Phase.IDLE -> MaterialTheme.colorScheme.background
        timer.phase == Phase.PAUSED -> MaterialTheme.colorScheme.surfaceVariant
        isBreak -> SuccessGreen
        else -> Color(timer.subjectColorArgb)
    }
    val animatedPhaseColor by animateColorAsState(
        targetValue = phaseColor,
        animationSpec = tween(600),
        label = "focusPhaseBg"
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        animatedPhaseColor.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding()
            .padding(bottom = padding.calculateBottomPadding()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (timer.phase == Phase.IDLE) {
            val peers by vm.peers.collectAsStateWithLifecycle()
            IdleContent(vm, state, peers.filter { it.isFocusing && !it.following })
        } else {
            RunningContent(vm, state)
        }
    }
}

// ---------------- 空闲态:页头 → 圆环 → 主按钮 → 选任务 → 模式与方案 ----------------

@Composable
private fun IdleContent(vm: FocusViewModel, state: FocusUiState, activePeers: List<PeerState>) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var mode by remember { mutableStateOf(TimerMode.POMODORO) }
    var countdownMin by remember { mutableStateOf(25) }
    var showTaskSheet by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var showStrictOverlayRequired by remember { mutableStateOf(false) }

    val plan = state.settings.currentPlan
    val hasTasks = state.todayTasks.isNotEmpty()
    val quickMinutes = listOf(5, 15, 25, 45)
    val ringTime = when (mode) {
        TimerMode.POMODORO -> TimeUtils.mmss(plan.focusMin * 60_000L)
        TimerMode.STOPWATCH -> TimeUtils.mmss(0L)
        TimerMode.COUNTDOWN -> TimeUtils.mmss(countdownMin * 60_000L)
    }
    val modeLabel = when (mode) {
        TimerMode.POMODORO -> "番茄钟"
        TimerMode.STOPWATCH -> "正计时"
        TimerMode.COUNTDOWN -> "倒计时"
    }
    val startLabel = when (mode) {
        TimerMode.POMODORO -> "开始专注 · ${plan.focusMin} 分钟"
        TimerMode.STOPWATCH -> "开始正计时"
        TimerMode.COUNTDOWN -> "开始倒计时 · $countdownMin 分钟"
    }
    val ringTrack = MaterialTheme.colorScheme.surfaceVariant

    // 模式 → 开始的唯一映射:主按钮(task = null)与任务行(带任务)共用,防止两处 when 各自漂移
    val startByMode: (TaskEntity?) -> Unit = start@{ task ->
        val strictRequested = state.settings.superModeOn &&
            state.settings.superModeStrict &&
            state.settings.emergencyPinHash.isNotEmpty()
        if (StrictGuardPolicy.needsOverlayPrompt(
                strict = strictRequested,
                overlayGranted = FocusService.hasOverlayPermission(context)
            )
        ) {
            showStrictOverlayRequired = true
            return@start
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        when (mode) {
            TimerMode.POMODORO ->
                if (task == null) vm.quickFocus() else vm.startForTask(task)
            TimerMode.STOPWATCH ->
                if (task == null) vm.quickStopwatch() else vm.startForTaskStopwatch(task)
            TimerMode.COUNTDOWN ->
                if (task == null) vm.quickCountdown(countdownMin)
                else vm.startForTaskCountdown(task, countdownMin)
        }
    }

    BoxWithConstraints(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(12.dp))
            PageHeader(title = "专注", meta = "选一个任务，或直接开始")

            // 专注接力:同账号主机设备正在专注,可一键跟随同步状态与倒计时
            if (activePeers.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                activePeers.forEach { peer ->
                    PeerFocusCard(peer = peer, onFollow = { vm.followPeer(peer) })
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(24.dp))

            // 中央圆环:模式 → 时长 → 当前任务
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    drawCircle(
                        color = ringTrack,
                        radius = size.minDimension / 2f - stroke / 2f,
                        style = Stroke(stroke)
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 200.dp)
                ) {
                    Text(
                        modeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(ringTime, style = CountdownNumberStyle)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "自由专注",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            GuideAnchor(GuideAnchors.FOCUS_START) {
                PrimaryButton(
                    text = startLabel,
                    onClick = { startByMode(null) },
                    modifier = Modifier.widthIn(max = 320.dp)
                )
            }

            Spacer(Modifier.height(20.dp))
            GuideAnchor(GuideAnchors.FOCUS_TASK_ROW) {
                RowGroup {
                    ListRow(
                        title = "选择任务",
                        icon = AppIcons.ClipboardList,
                        subtitle = if (hasTasks) "从今日待办中选一个" else "今日没有待办",
                        onClick = if (hasTasks) ({ showTaskSheet = true }) else null,
                        showChevron = hasTasks,
                        enabled = hasTasks
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            GuideAnchor(GuideAnchors.FOCUS_MODE) {
                SegmentedChips(
                    options = listOf("番茄钟", "正计时", "倒计时"),
                    selectedIndex = mode.ordinal,
                    onSelect = { index ->
                        mode = when (index) {
                            0 -> TimerMode.POMODORO
                            1 -> TimerMode.STOPWATCH
                            else -> TimerMode.COUNTDOWN
                        }
                    }
                )
            }

            when (mode) {
                TimerMode.POMODORO -> {
                    Spacer(Modifier.height(20.dp))
                    SectionHeader(title = "番茄方案")
                    Spacer(Modifier.height(8.dp))
                    RowGroup {
                        state.settings.plans.forEachIndexed { index, planOption ->
                            if (index > 0) RowDivider(startIndent = 16.dp)
                            val selected = planOption.name == state.settings.currentPlanName
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else Color.Transparent
                                    )
                            ) {
                                ListRow(
                                    title = planOption.name,
                                    subtitle = "专注 ${planOption.focusMin} 分钟 · 短休 ${planOption.shortBreakMin} 分钟",
                                    onClick = { vm.selectPlan(planOption.name) },
                                    showChevron = false,
                                    trailing = {
                                        if (selected) {
                                            Icon(
                                                AppIcons.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                TimerMode.COUNTDOWN -> {
                    Spacer(Modifier.height(20.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "倒计时时长",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "$countdownMin 分钟",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = countdownMin.toFloat(),
                        onValueChange = { countdownMin = it.toInt().coerceIn(5, 180) },
                        valueRange = 5f..180f,
                        steps = 34,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SegmentedChips(
                        options = quickMinutes.map { "$it 分" },
                        selectedIndex = quickMinutes.indexOf(countdownMin),
                        onSelect = { countdownMin = quickMinutes[it] }
                    )
                }

                TimerMode.STOPWATCH -> Unit
            }

            // 更多设置:默认收起,降低首屏密度;与模式无关的开关集中在此
            Spacer(Modifier.height(24.dp))
            RowGroup {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { settingsExpanded = !settingsExpanded }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "更多设置",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (settingsExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                        contentDescription = if (settingsExpanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (settingsExpanded) {
                    RowDivider(startIndent = 16.dp)
                    SwitchRow(
                        title = "自动串联",
                        checked = state.settings.autoChain,
                        onCheckedChange = vm::toggleAutoChain,
                        subtitle = "休息结束自动开始下一个"
                    )
                    RowDivider(startIndent = 16.dp)
                    SwitchRow(
                        title = "连续专注",
                        checked = state.settings.continuousFocus,
                        onCheckedChange = vm::toggleContinuous,
                        subtitle = "跳过休息，一路专注下去"
                    )
                }
            }
            // 底部导航的中央 FAB 会向上探出,预留它的高度避免最后一行被压住
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showTaskSheet) {
        AppSheet(title = "选择今日任务", onDismiss = { showTaskSheet = false }) {
            if (!hasTasks) {
                EmptyState(icon = AppIcons.ClipboardList, title = "今日没有待办", compact = true)
            } else {
                RowGroup {
                    state.todayTasks.forEachIndexed { index, task ->
                        if (index > 0) RowDivider()
                        val subject = state.subjects.firstOrNull { it.id == task.subjectId }
                        val pomodoro = if (task.pomodoroEstimate > 0) {
                            "番茄 ${task.completedPomodoros}/${task.pomodoroEstimate}"
                        } else {
                            "番茄 ${task.completedPomodoros}"
                        }
                        ListRow(
                            title = task.title,
                            subtitle = "${subject?.name ?: "未分类"} · $pomodoro",
                            onClick = {
                                showTaskSheet = false
                                startByMode(task)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showStrictOverlayRequired) {
        AlertDialog(
            onDismissRequest = { showStrictOverlayRequired = false },
            title = { Text("严格模式需要悬浮窗权限") },
            text = {
                Text(
                    "没有「显示在其他应用上层」权限时，按 Home 键仍可离开日拱，严格锁定不会生效。\n" +
                        "去系统设置开启权限后，再回来开始专注。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStrictOverlayRequired = false
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                ) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { showStrictOverlayRequired = false }) { Text("取消") }
            }
        )
    }
}

/** 接力邀请卡片:主机设备名 + 任务 + 实时倒计时 + 跟随入口(专注接力) */
@Composable
private fun PeerFocusCard(peer: PeerState, onFollow: () -> Unit) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(peer.endsAtLocalMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val remain = if (peer.paused) peer.remainMs
    else (peer.endsAtLocalMs - nowMs).coerceAtLeast(0)
    val isBreak = peer.phase == "break"
    val accent = if (isBreak) SuccessGreen else MaterialTheme.colorScheme.primary
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.Smartphone,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${peer.deviceName} · ${if (isBreak) "休息中" else "专注中"}" +
                        if (peer.paused) "(已暂停)" else "",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    peer.taskTitle.ifBlank { "自由专注" } + " · " + TimeUtils.mmss(remain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextAction(text = "跟随", onClick = onFollow)
        }
    }
}

// ---------------- 运行态:环形进度 + 阶段胶囊 + 主控制 ----------------

@Composable
private fun RunningContent(vm: FocusViewModel, state: FocusUiState) {
    val haptic = LocalHapticFeedback.current
    val timer = state.timer
    var showAbandonConfirm by remember { mutableStateOf(false) }
    var showEmergencyExit by remember { mutableStateOf(false) }
    // 放弃确认弹窗期间暂停计时:否则犹豫的这几分钟仍在倒数,倒计时甚至可能先跑完
    var pausedByAbandonDialog by remember { mutableStateOf(false) }
    /** 开/关普通放弃确认:打开时若在计时则暂停,取消时原样恢复 */
    fun setAbandonConfirm(open: Boolean) {
        if (open) {
            pausedByAbandonDialog = timer.phase == Phase.FOCUSING
            if (pausedByAbandonDialog) vm.pause()
        } else if (pausedByAbandonDialog) {
            pausedByAbandonDialog = false
            vm.resume()
        }
        showAbandonConfirm = open
    }
    val isBreak = timer.phase == Phase.SHORT_BREAK || timer.phase == Phase.LONG_BREAK
    val superOn = state.settings.superModeOn
    // 紧急退出月度配额(跨月自动清零):严格/普通模式共用的唯一专注中断出口
    val monthKey = remember { TimeUtils.monthKeyOf() }
    val quotaUsed = if (state.settings.emergencyExitMonth == monthKey)
        state.settings.emergencyExitsUsed else 0
    val quotaLeft = (AppSettings.EMERGENCY_EXIT_QUOTA - quotaUsed).coerceAtLeast(0)
    /** 严格锁定:专注期间无法暂停/放弃/离开,仅计时结束或 6 位紧急退出密码可解除(参考番茄ToDo) */
    val strictFocus = superOn && state.settings.superModeStrict && timer.isFocusing
    val guardAllowCount = timer.subjectId
        ?.let { state.settings.subjectWhitelist[it]?.size }
        ?: state.settings.whitelist.size
    val ringColor = when {
        timer.phase == Phase.PAUSED -> MaterialTheme.colorScheme.outline
        isBreak -> SuccessGreen
        else -> Color(timer.subjectColorArgb)
    }
    // 任务番茄未做完 → 休息结束自动续做(长任务跨多个番茄连续推进)
    val taskEstimate = timer.taskPomodoroEstimate
    val taskDoneTotal = timer.taskPomodoroDoneAtStart + timer.completedInCycle
    val willContinue = taskEstimate > 0 && taskDoneTotal < taskEstimate
    // 正计时:进度环按分钟循环填充(秒针式);其余:剩余时间占比
    val rawProgress = if (timer.mode == TimerMode.STOPWATCH) {
        (timer.remainingMs % 60_000).toFloat() / 60_000
    } else if (timer.durationMs == 0L) 0f
    else timer.remainingMs.toFloat() / timer.durationMs
    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(500),
        label = "ringProgress"
    )
    val phaseLabel = when (timer.phase) {
        Phase.FOCUSING -> "专注"
        Phase.PAUSED -> "暂停"
        Phase.SHORT_BREAK -> "短休"
        Phase.LONG_BREAK -> "长休"
        Phase.IDLE -> ""
    }
    val phaseTone = if (timer.phase == Phase.PAUSED) PillTone.NEUTRAL else PillTone.ACCENT
    val ringCaption = when {
        timer.phase == Phase.PAUSED -> "累计上限 5 分钟"
        isBreak && willContinue -> "结束后自动继续"
        timer.isFocusing && timer.mode == TimerMode.POMODORO && taskEstimate > 0 ->
            "番茄 $taskDoneTotal/$taskEstimate"
        timer.isFocusing && timer.mode == TimerMode.POMODORO ->
            "本任务第 ${state.taskPomodoroToday + 1} 个番茄"
        else -> null
    }

    Column(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxSize()
            // 可滚动:横屏/矮屏下 280dp 圆环 + 文案不裁切(与空闲态一致)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        // 学霸模式提示胶囊:严格锁定与原白名单状态文案保持不变
        if (superOn) {
            StatusPill(
                text = if (state.settings.superModeStrict) "学霸模式 · 严格锁定中"
                else "学霸模式 · 白名单内 $guardAllowCount 个应用可用",
                tone = if (state.settings.superModeStrict) PillTone.WARNING else PillTone.ACCENT
            )
            Spacer(Modifier.height(16.dp))
        }

        // 环形进度 + 阶段胶囊 + 中央时间 + 任务名
        Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 14.dp.toPx()
                val inset = stroke / 2 + 2.dp.toPx()
                val arcSize = size.width - inset * 2
                // 进度弧外圈辉光:更宽的低透明度弧,营造发光质感
                val glowPad = 3.dp.toPx()
                drawArc(
                    color = ringColor.copy(alpha = 0.18f),
                    startAngle = -90f,
                    sweepAngle = -360f * animatedProgress,
                    useCenter = false,
                    topLeft = Offset(inset - glowPad, inset - glowPad),
                    size = androidx.compose.ui.geometry.Size(
                        arcSize + glowPad * 2,
                        arcSize + glowPad * 2
                    ),
                    style = Stroke(stroke + glowPad * 2, cap = StrokeCap.Round)
                )
                drawCircle(
                    color = ringColor.copy(alpha = 0.12f),
                    radius = arcSize / 2,
                    center = Offset(size.width / 2, size.height / 2),
                    style = Stroke(stroke)
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = -360f * animatedProgress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 220.dp)
            ) {
                StatusPill(text = phaseLabel, tone = phaseTone)
                Spacer(Modifier.height(12.dp))
                Text(text = TimeUtils.mmss(timer.remainingMs), style = FocusTimerStyle)
                Spacer(Modifier.height(6.dp))
                Text(
                    // 休息中任务名没有意义:按 spec 显示“休息一下”
                    text = if (isBreak) "休息一下" else timer.taskTitle.ifBlank { "自由专注" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                if (ringCaption != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        ringCaption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // 操作键:严格锁定期间无任何计时操作(参考番茄ToDo 严格模式);
        // 其余:暂停/继续 · 正计时「结束并记录」 · 休息=跳过
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                strictFocus -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            AppIcons.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "严格锁定中 · 计时结束自动解锁",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                timer.phase == Phase.FOCUSING -> {
                    PrimaryButton(
                        text = "暂停",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.pause()
                        },
                        modifier = Modifier.width(200.dp),
                        icon = AppIcons.Pause
                    )
                    if (timer.mode == TimerMode.STOPWATCH) {
                        Spacer(Modifier.height(4.dp))
                        TextAction(
                            text = "结束并记录",
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.finishStopwatch()
                            },
                            icon = AppIcons.Stop
                        )
                    }
                }

                timer.phase == Phase.PAUSED -> {
                    PrimaryButton(
                        text = "继续",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.resume()
                        },
                        modifier = Modifier.width(200.dp),
                        icon = AppIcons.Play
                    )
                }

                isBreak -> {
                    // 休息态主按钮保持 SuccessGreen(与圆环同色);内容色取方案深靛
                    // (与 onPrimaryContainer 同值 #10144A),在绿色底上约 5.0:1,深浅模式一致
                    MaterialTheme(
                        colorScheme = MaterialTheme.colorScheme.copy(
                            primary = SuccessGreen,
                            onPrimary = Color(0xFF10144A)
                        )
                    ) {
                        PrimaryButton(
                            text = "跳过休息",
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.skipBreak()
                            },
                            modifier = Modifier.width(200.dp),
                            icon = AppIcons.SkipForward
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // 结束专注不属于绿色休息操作:留在常规主题内,避免绿字落在浅色背景上
                    TextAction(
                        text = "结束专注 · 休息中,不消耗次数",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.endDuringBreak()
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            // 休息入口已并入控制区
            isBreak -> Unit
            strictFocus && quotaLeft <= 0 -> {
                // 严格锁定 + 配额耗尽:防逃逸,计时结束前无法退出
                Text(
                    "本月紧急退出次数已用完 · 计时结束前无法退出",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> {
                TextAction(
                    text = if (strictFocus) "紧急退出 · 本月剩 $quotaLeft 次" else "结束专注",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // 严格锁定走密码 + 配额;普通模式随时可放弃,不计次
                        if (strictFocus) showEmergencyExit = true else setAbandonConfirm(true)
                    },
                    danger = true
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "今日已专注 ${TimeUtils.formatHours(state.todayFocusMin)}" +
                if (state.timer.completedInCycle > 0) " · 本轮第 ${state.timer.completedInCycle + 1} 个番茄"
                else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        // 快速开钟的科目补选(PRD 3.4)
        if (timer.isFocusing && timer.taskId == null) {
            Text(
                "这次专注归属哪个科目?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 多科目横向滚动,不再挤压截断
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                state.subjects.forEach { subject ->
                    ToggleChip(
                        text = subject.name,
                        selected = timer.subjectId == subject.id,
                        onClick = { vm.switchSubject(subject) }
                    )
                }
            }
        }
    }

    if (showAbandonConfirm) {
        // 普通放弃确认(非严格模式):原因记录用于后续专注力分析,可跳过;不消耗紧急退出配额
        var reason by remember { mutableStateOf<String?>(null) }
        val reasons = listOf("被打断", "临时有事", "状态不好", "不想学了")
        AlertDialog(
            onDismissRequest = { setAbandonConfirm(false) },
            title = { Text("结束本次专注？") },
            text = {
                Column {
                    Text("已专注的时长会记为中断,不计入统计。")
                    Spacer(Modifier.height(12.dp))
                    reasons.forEach { r ->
                        ToggleChip(
                            text = r,
                            selected = reason == r,
                            onClick = { reason = if (reason == r) null else r },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 放弃生效:不必复活计时(abandon 会直接停表)
                        pausedByAbandonDialog = false
                        showAbandonConfirm = false
                        vm.abandonFocus(reason)
                    }
                ) {
                    Text("确认退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { setAbandonConfirm(false) }) {
                    Text("继续专注")
                }
            }
        )
    }

    if (showEmergencyExit) {
        EmergencyExitDialog(
            quotaLeft = quotaLeft,
            onExit = { pin, onError ->
                vm.emergencyExit(pin) { ok -> if (!ok) onError() }
            },
            onDismiss = { showEmergencyExit = false }
        )
    }
}

/**
 * 严格模式紧急退出(参考番茄ToDo):
 * 输入 6 位紧急退出密码强制结束专注,消耗 1 次月度配额,
 * 本次记录为非正常退出并体现在专注数据报告中。
 */
@Composable
private fun EmergencyExitDialog(
    quotaLeft: Int,
    onExit: (String, () -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("紧急退出严格锁定?") },
        text = {
            Column {
                Text(
                    "输入 6 位紧急退出密码强制结束本次专注,消耗 1 次月度配额(本月紧急退出剩余 $quotaLeft 次)。" +
                        "系统会记录此次非正常退出,并体现在专注数据报告中。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        error = false
                        pin = it.filter { c -> c.isDigit() }.take(6)
                    },
                    label = { Text("6 位数字密码") },
                    isError = error,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (error) {
                    Text(
                        "密码错误,请重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "忘记密码?只能等待计时自然结束(防逃逸设计)。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.length == 6,
                onClick = { onExit(pin) { error = true; pin = "" } }
            ) {
                Text("强制退出", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("继续专注") }
        }
    )
}
