package com.yanzhong.app.ui.focus

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yanzhong.app.data.prefs.AppSettings
import com.yanzhong.app.data.prefs.PomodoroPlan
import com.yanzhong.app.data.remote.PeerState
import com.yanzhong.app.timer.Phase
import com.yanzhong.app.timer.TimerMode
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.SuccessGreen
import com.yanzhong.app.util.TimeUtils
import kotlinx.coroutines.delay

/** 专注页:全屏番茄钟,环形进度,操作仅三键(PRD 4.2 ②) */
@Composable
fun FocusScreen(padding: PaddingValues) {
    val vm: FocusViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val timer = state.timer
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

// ---------------- 空闲态:选方案 → 选任务 / 自由专注 ----------------

@Composable
private fun IdleContent(vm: FocusViewModel, state: FocusUiState, activePeers: List<PeerState>) {
    var mode by remember { mutableStateOf(TimerMode.POMODORO) }
    var countdownMin by remember { mutableStateOf(25) }
    Column(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("开始专注", style = MaterialTheme.typography.headlineLarge)
        Text(
            "选一个任务,或者直接开始",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        // 多端跟随:同账号其他设备正在专注,可一键跟随同步状态与倒计时
        if (activePeers.isNotEmpty()) {
            activePeers.forEach { peer -> PeerFocusCard(peer = peer, onFollow = { vm.followPeer(peer) }) }
            Spacer(Modifier.height(16.dp))
        }

        // 三种计时模式(参考番茄ToDo):番茄钟 / 正计时 / 倒计时
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("番茄钟", mode == TimerMode.POMODORO) { mode = TimerMode.POMODORO }
            ModeChip("正计时", mode == TimerMode.STOPWATCH) { mode = TimerMode.STOPWATCH }
            ModeChip("倒计时", mode == TimerMode.COUNTDOWN) { mode = TimerMode.COUNTDOWN }
        }
        Spacer(Modifier.height(16.dp))

        if (mode == TimerMode.POMODORO) {
            Text(
                "番茄方案",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.settings.plans.forEach { plan ->
                    PlanCard(
                        plan = plan,
                        selected = plan.name == state.settings.currentPlanName,
                        onClick = { vm.selectPlan(plan.name) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            SettingSwitchRow(
                title = "自动串联",
                subtitle = "休息结束自动开始下一个番茄",
                checked = state.settings.autoChain,
                onChange = { vm.toggleAutoChain(it) }
            )
            Spacer(Modifier.height(8.dp))
            SettingSwitchRow(
                title = "连续专注",
                subtitle = "跳过休息,继续专注",
                checked = state.settings.continuousFocus,
                onChange = { vm.toggleContinuous(it) }
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { vm.quickFocus() },
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("自由专注 · ${state.settings.currentPlan.focusMin} 分钟", fontSize = 16.sp)
            }
            Spacer(Modifier.height(20.dp))
        } else if (mode == TimerMode.STOPWATCH) {
            Text(
                "从 00:00 向上累计,无上限,随时结束并记录总专注时长",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.quickStopwatch() },
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("自由正计时 · 无上限", fontSize = 16.sp)
            }
            Spacer(Modifier.height(20.dp))
        } else {
            Text(
                "单次倒计时,归零即完成,不进入休息循环",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 15, 25, 45).forEach { min ->
                    FilterChip(
                        selected = countdownMin == min,
                        onClick = { countdownMin = min },
                        label = { Text("${min}分") }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$countdownMin",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "分钟",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
            }
            Slider(
                value = countdownMin.toFloat(),
                onValueChange = { countdownMin = it.toInt().coerceIn(5, 180) },
                valueRange = 5f..180f,
                steps = 34,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.quickCountdown(countdownMin) },
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("开始倒计时 · $countdownMin 分钟", fontSize = 16.sp)
            }
            Spacer(Modifier.height(20.dp))
        }

        if (state.todayTasks.isNotEmpty()) {
            Text(
                "从今日待办开始",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(8.dp))
            state.todayTasks.forEach { task ->
                val subject = state.subjects.firstOrNull { it.id == task.subjectId }
                TaskPickRow(
                    title = task.title,
                    subjectName = subject?.name ?: "未分类",
                    subjectColor = subject?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary,
                    onClick = {
                        when (mode) {
                            TimerMode.POMODORO -> vm.startForTask(task)
                            TimerMode.STOPWATCH -> vm.startForTaskStopwatch(task)
                            TimerMode.COUNTDOWN -> vm.startForTaskCountdown(task, countdownMin)
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 方案卡片:选中 primaryContainer 弱底色强调块 + 对勾,未选中弱化;时长胶囊「专注 + 短休」 */
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/** 他端专注卡片(多端跟随入口):设备名 + 任务 + 实时倒计时 + 跟随按钮 */
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
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isBreak) SuccessGreen.copy(alpha = 0.10f)
        else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        border = BorderStroke(
            1.dp,
            if (isBreak) SuccessGreen.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(
                AppIcons.Smartphone,
                contentDescription = null,
                tint = if (isBreak) SuccessGreen else MaterialTheme.colorScheme.primary,
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
            Button(
                onClick = onFollow,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
            ) {
                Text("跟随", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: PomodoroPlan,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)) else null,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plan.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                if (selected) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        AppIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
            ) {
                Text(
                    "${plan.focusMin} + ${plan.shortBreakMin} 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/** 开关行:弱底色圆角容器 + 副说明 */
@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun TaskPickRow(
    title: String,
    subjectName: String,
    subjectColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(subjectColor)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subjectName,
                    style = MaterialTheme.typography.labelMedium,
                    color = subjectColor
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    AppIcons.Play,
                    contentDescription = "开始",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ---------------- 运行态:环形进度 + 三键 ----------------

@Composable
private fun RunningContent(vm: FocusViewModel, state: FocusUiState) {
    val timer = state.timer
    var showAbandonConfirm by remember { mutableStateOf(false) }
    var showEmergencyExit by remember { mutableStateOf(false) }
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

    // 完成一个番茄的即时反馈:番茄数增加时弹出“+1 🍅”(参考番茄ToDo 成就反馈)
    var showPomodoroBurst by remember { mutableStateOf(false) }
    LaunchedEffect(timer.completedInCycle) {
        if (timer.completedInCycle > 0) {
            showPomodoroBurst = true
            delay(1600)
            showPomodoroBurst = false
        }
    }
    val burstAlpha by animateFloatAsState(
        targetValue = if (showPomodoroBurst) 1f else 0f,
        animationSpec = tween(500),
        label = "burstAlpha"
    )

    Column(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxSize()
            // 可滚动:横屏/矮屏下 260dp 圆环 + 文案不裁切(与空闲态一致)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(28.dp))

        // 任务名 + 科目色环
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(ringColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = timer.taskTitle.ifBlank { "自由专注" },
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )
        }
        // 任务番茄未做完 → 休息结束自动续做(长任务跨多个番茄连续推进)
        val taskEstimate = timer.taskPomodoroEstimate
        val taskDoneTotal = timer.taskPomodoroDoneAtStart + timer.completedInCycle
        val willContinue = taskEstimate > 0 && taskDoneTotal < taskEstimate
        Text(
            text = when (timer.phase) {
                Phase.FOCUSING -> when (timer.mode) {
                    TimerMode.STOPWATCH -> "正计时中 · 无上限累计"
                    TimerMode.COUNTDOWN -> "倒计时中 · ${timer.planName}"
                    else -> "专注中 · ${timer.planName}"
                }
                Phase.PAUSED -> "已暂停(累计上限 5 分钟)"
                Phase.SHORT_BREAK -> "短休息" + if (willContinue) " · 结束后自动继续" else ""
                Phase.LONG_BREAK -> "长休息" + if (willContinue) " · 结束后自动继续" else ""
                Phase.IDLE -> ""
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (superOn) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (state.settings.superModeStrict) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    val pillColor = if (state.settings.superModeStrict) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                    Icon(
                        imageVector = if (state.settings.superModeStrict) AppIcons.Lock else AppIcons.LockOpen,
                        contentDescription = null,
                        tint = pillColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (state.settings.superModeStrict) "学霸模式 · 严格锁定中"
                        else "学霸模式 · 白名单内 $guardAllowCount 个应用可用",
                        style = MaterialTheme.typography.labelMedium,
                        color = pillColor
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))

        // 环形进度 + 中央时间
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(260.dp)) {
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = TimeUtils.mmss(timer.remainingMs),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 56.sp,
                        fontFeatureSettings = "tnum"
                    )
                )
                if (timer.isFocusing && timer.mode == TimerMode.POMODORO) {
                    Text(
                        if (taskEstimate > 0)
                            "任务番茄 ${taskDoneTotal + 1}/$taskEstimate · 今日 ${state.taskPomodoroToday} 🍅"
                        else "本任务第 ${state.taskPomodoroToday + 1} 个番茄",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // “+1 🍅”上浮淡出动画
            Text(
                "+1 🍅",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = burstAlpha
                        translationY = (1f - burstAlpha) * 60f
                    }
            )
        }

        Spacer(Modifier.height(30.dp))

        // 操作键:严格锁定期间无任何计时操作(参考番茄ToDo 严格模式);
        // 其余:暂停/继续 · 正计时「结束并记录」 · 休息=跳过
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                    ActionButton("暂停", AppIcons.Pause, onClick = { vm.pause() })
                    if (timer.mode == TimerMode.STOPWATCH) {
                        ActionButton("结束并记录", AppIcons.Stop, compact = true, onClick = { vm.finishStopwatch() })
                    }
                }
                timer.phase == Phase.PAUSED -> {
                    ActionButton("继续", AppIcons.Play, onClick = { vm.resume() })
                }
                isBreak -> {
                    ActionButton("跳过休息", AppIcons.SkipForward, onClick = { vm.skipBreak() })
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        if (isBreak) {
            // 休息时段:可直接结束专注,不消耗紧急退出月配额(休息本就不在番茄内)
            TextButton(onClick = { vm.endDuringBreak() }) {
                Text(
                    "结束专注 · 休息中,不消耗次数",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (quotaLeft <= 0) {
            // 紧急退出:唯一专注中断出口,每月配额 4 次(防逃逸:逼用户等计时自然结束)
            Text(
                "本月紧急退出次数已用完 · 计时结束前无法退出",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            TextButton(onClick = {
                if (strictFocus) showEmergencyExit = true else showAbandonConfirm = true
            }) {
                Text(
                    "紧急退出 · 本月剩 $quotaLeft 次",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "今日已专注 ${TimeUtils.formatHours(state.todayFocusMin)}" +
                if (state.timer.completedInCycle > 0) " · 本轮第 ${state.timer.completedInCycle + 1} 🍅"
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
                    FilterChip(
                        selected = timer.subjectId == subject.id,
                        onClick = { vm.switchSubject(subject) },
                        label = { Text(subject.name) }
                    )
                }
            }
        }
    }

    if (showAbandonConfirm) {
        // 紧急退出确认(非严格模式):原因记录用于后续专注力分析,可跳过
        var reason by remember { mutableStateOf<String?>(null) }
        val reasons = listOf("被打断", "临时有事", "状态不好", "不想学了")
        AlertDialog(
            onDismissRequest = { showAbandonConfirm = false },
            title = { Text("紧急退出本次专注?") },
            text = {
                Column {
                    Text(
                        "紧急退出每月只有 ${AppSettings.EMERGENCY_EXIT_QUOTA} 次机会,本次将消耗 1 次(本月剩 $quotaLeft 次)。" +
                            "已专注的时长会记为中断,不计入统计。"
                    )
                    Spacer(Modifier.height(12.dp))
                    reasons.forEach { r ->
                        FilterChip(
                            selected = reason == r,
                            onClick = { reason = if (reason == r) null else r },
                            label = { Text(r) },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAbandonConfirm = false
                        vm.emergencyExit(null, reason) { }
                    }
                ) {
                    Text("确认退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAbandonConfirm = false }) {
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
                    "输入 6 位紧急退出密码强制结束本次专注,消耗 1 次月度配额(本月剩 $quotaLeft 次)。" +
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

@Composable
private fun ActionButton(text: String, icon: ImageVector?, onClick: () -> Unit, compact: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .height(52.dp)
            .widthIn(min = if (compact) 120.dp else 168.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors()
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 16.sp)
    }
}
