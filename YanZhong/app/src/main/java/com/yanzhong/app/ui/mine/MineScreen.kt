package com.yanzhong.app.ui.mine

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.prefs.PomodoroPlan
import com.yanzhong.app.data.stats.LearningProfile
import com.yanzhong.app.util.TimeUtils
import com.yanzhong.app.data.prefs.ThemeMode
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CoralRed
import com.yanzhong.app.ui.theme.CoralRedDeep
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.EnglishColor
import com.yanzhong.app.ui.theme.MintGreen
import com.yanzhong.app.ui.theme.MintGreenDeep
import com.yanzhong.app.ui.theme.MathColor
import com.yanzhong.app.ui.theme.NightMountainFar
import com.yanzhong.app.ui.theme.NightMountainNear
import com.yanzhong.app.ui.theme.NightSkyBottom
import com.yanzhong.app.ui.theme.NightSkyMid
import com.yanzhong.app.ui.theme.NightSkyTop
import com.yanzhong.app.ui.theme.PillTag
import com.yanzhong.app.ui.theme.PoliticsColor
import com.yanzhong.app.ui.theme.SkyBlue
import com.yanzhong.app.ui.theme.SkyBlueDeep
import com.yanzhong.app.ui.theme.SectionCard
import com.yanzhong.app.ui.theme.Subject408Color

/** 我的页:沉浸式头部 + 快捷入口 + 设置列表(PRD 4.2 ⑤,视觉参考番茄ToDo 我的页) */
@Composable
fun MineScreen(padding: PaddingValues, navController: NavHostController) {
    val vm: MineViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showSubjectEditor by remember { mutableStateOf(false) }
    var showSubjectBatch by remember { mutableStateOf(false) }
    var showPlanEditor by remember { mutableStateOf(false) }
    var showGoalEditor by remember { mutableStateOf(false) }
    var showPomodoroGoalEditor by remember { mutableStateOf(false) }
    /** 删除科目二次确认(连带删除科目下任务与记录,防误触) */
    var deleteSubjectCandidate by remember { mutableStateOf<SubjectEntity?>(null) }

    // 夜空为深色:进入本页时状态栏切白色图标,离开恢复
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val original = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (original != null) controller.isAppearanceLightStatusBars = original }
    }

    // 注意:根必须用 Column —— Box 中 fillMaxSize 的面板会盖住整个头部
    // 平板:限宽 720dp 居中;background 填充面板上移 28dp 留出的底部缝隙
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
    Column(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxWidth()
    ) {
        // 沉浸式头部:Canvas 绘制夜空 + 月亮 + 山脉(不加图片资源,drawable 只留必要文件)
        Box(Modifier.fillMaxWidth()) {
            NightSkyCanvas(Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 头像:白描边圆底 + 学士帽
                        Box(
                            Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.18f))
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                AppIcons.GraduationCap,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                when {
                                    !state.loggedIn -> "本机学习档案"
                                    state.username.isNotBlank() -> state.username
                                    else -> "已登录"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                formatSubjectSubtitle(state.subjects.map { it.name }),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillTag(text = "共专注 ${state.learningProfile.activeDays} 天", icon = AppIcons.Flame)
                        PillTag(text = "连续打卡 ${state.learningProfile.streakDays} 天", icon = AppIcons.TrendingUp)
                    }
                }
            // 头部底部多留 28dp 夜空,供面板圆角压边
            Spacer(Modifier.height(28.dp))
            }
        }

        // 白色大圆角面板:上移 28dp 与夜空衔接(参考图3 顶部弧形过渡)
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxSize()
                .offset(y = (-28).dp)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = padding.calculateBottomPadding() + 72.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "sync-card") {
                    SyncStatusCard(
                        loggedIn = state.loggedIn,
                        username = state.username,
                        lastSyncAt = state.lastSyncAt,
                        sync = state.sync,
                        onLogin = { navController.navigate(Routes.CLOUD) { launchSingleTop = true } },
                        onSyncNow = { vm.syncNow() }
                    )
                }

                // 快捷功能区(参考图3 三个图标入口)
                item(key = "quick-entries") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        QuickEntry(
                            icon = AppIcons.Hourglass,
                            label = "未来倒计时",
                            colors = listOf(SkyBlue, SkyBlueDeep)
                        ) {
                            navController.navigate(Routes.HOME) {
                                launchSingleTop = true
                                popUpTo(Routes.HOME) { saveState = true }
                            }
                        }
                        QuickEntry(
                            icon = AppIcons.TrendingUp,
                            label = "专注历史",
                            colors = listOf(MintGreen, MintGreenDeep)
                        ) {
                            navController.navigate(Routes.STATS) {
                                launchSingleTop = true
                                popUpTo(Routes.HOME) { saveState = true }
                            }
                        }
                        QuickEntry(
                            icon = AppIcons.Shield,
                            label = "学霸模式",
                            colors = listOf(CoralRed, CoralRedDeep)
                        ) {
                            navController.navigate(Routes.SUPERMODE) { launchSingleTop = true }
                        }
                    }
                }

                item(key = "learning-profile") {
                    LearningProfileCard(state.learningProfile)
                }

                item(key = "preferences-heading") {
                    SectionHeading("偏好设置")
                }

                item(key = "theme") {
                    SectionCard(title = "主题", icon = AppIcons.Palette, accent = SkyBlue) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                ThemeMode.LIGHT to "浅色",
                                ThemeMode.DARK to "深色",
                                ThemeMode.SYSTEM to "跟随系统"
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = state.settings.themeMode == mode,
                                    onClick = { vm.setTheme(mode) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }

                item(key = "plans") {
                    SectionCard(title = "番茄方案", icon = AppIcons.Timer, accent = CoralRed) {
                        state.settings.plans.forEachIndexed { index, plan ->
                            val selected = plan.name == state.settings.currentPlanName
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.setCurrentPlan(plan.name) }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            plan.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            "专注 ${plan.focusMin} · 短休 ${plan.shortBreakMin} · " +
                                                "长休 ${plan.longBreakMin} · 每轮 ${plan.longBreakInterval}🍅",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (selected) {
                                        Icon(
                                            AppIcons.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            if (index < state.settings.plans.size - 1) Spacer(Modifier.height(8.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { showPlanEditor = true }) { Text("编辑方案参数") }
                    }
                }

                item(key = "focus-pref") {
                    SectionCard(title = "专注偏好", icon = AppIcons.Sliders, accent = MintGreen) {
                        // 学霸模式入口(参考番茄ToDo 学霸模式)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navController.navigate(Routes.SUPERMODE) }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    AppIcons.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("学霸模式", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        when {
                                            !state.settings.superModeOn -> "专注期间拦截非白名单应用 · 未开启"
                                            state.settings.superModeStrict -> "严格锁定 · 已开启"
                                            else -> "白名单放行 ${state.settings.whitelist.size} 个应用 · 已开启"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    AppIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        SwitchRow("自动串联(休息结束自动开始下一个)", state.settings.autoChain, AppIcons.Repeat) {
                            vm.setAutoChain(it)
                        }
                        Spacer(Modifier.height(4.dp))
                        SwitchRow("连续专注(跳过休息)", state.settings.continuousFocus, AppIcons.InfinityLoop) {
                            vm.setContinuousFocus(it)
                        }
                        Spacer(Modifier.height(4.dp))
                        SwitchRow("静音模式", state.settings.silentMode, AppIcons.VolumeX) {
                            vm.setSilent(it)
                        }
                        if (state.settings.silentMode) {
                            Text(
                                "已静音:阶段切换不震动、不出声,横幅与弹窗提醒不受影响",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 28.dp, bottom = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        SwitchRow(
                            "阶段切换震动", state.settings.vibrationOn, AppIcons.Vibrate,
                            dim = state.settings.silentMode
                        ) {
                            vm.setVibration(it)
                        }
                        Spacer(Modifier.height(4.dp))
                        SwitchRow(
                            "阶段切换音效", state.settings.soundOn, AppIcons.Music,
                            dim = state.settings.silentMode
                        ) {
                            vm.setSound(it)
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    AppIcons.Target,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("每周目标净学习", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "今日页与统计页周报的达标线",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { showGoalEditor = true }) {
                                    Text("${state.settings.weeklyGoalHours} 小时")
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    AppIcons.Target,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("每日番茄目标", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "今日页番茄数的达标线",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { showPomodoroGoalEditor = true }) {
                                    Text("${state.settings.dailyPomodoroGoal} 个")
                                }
                            }
                        }
                    }
                }

                item(key = "subjects") {
                    SectionCard(title = "科目管理", icon = AppIcons.GraduationCap, accent = MathColor) {
                        state.subjects.forEachIndexed { index, subject ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(Color(subject.colorArgb))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        subject.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { deleteSubjectCandidate = subject }) {
                                        Icon(
                                            AppIcons.Delete,
                                            contentDescription = "删除科目 ${subject.name}",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            if (index < state.subjects.size - 1) Spacer(Modifier.height(8.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = { showSubjectEditor = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("＋ 添加科目") }
                            TextButton(
                                onClick = { showSubjectBatch = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("批量添加") }
                        }
                    }
                }

                item(key = "data-entry") {
                    SectionCard(
                        title = "数据与账号",
                        subtitle = "云端同步、备份与恢复、导入导出、账号注销",
                        icon = AppIcons.DatabaseBackup,
                        accent = SkyBlue
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navController.navigate(Routes.DATA) { launchSingleTop = true }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("打开数据与账号", modifier = Modifier.weight(1f))
                            Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                item(key = "about") {
                    SectionCard(title = "关于", icon = AppIcons.Info, accent = Subject408Color) {
                        Text("研钟 YanZhong 1.0.0-m1", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "学什么用扇贝,怎么学、学得怎么样用研钟。\n本地数据 · 零广告 · 零社区",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    /** 删除科目二次确认:外键关联的任务与专注记录将一并删除 */
    deleteSubjectCandidate?.let { subject ->
        AlertDialog(
            onDismissRequest = { deleteSubjectCandidate = null },
            title = { Text("删除科目「${subject.name}」?") },
            text = {
                Text("该科目及其下所有任务、专注记录将被永久删除,统计中对应数据也会消失。此操作无法恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteSubject(subject)
                        deleteSubjectCandidate = null
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSubjectCandidate = null }) { Text("取消") }
            }
        )
    }

    if (showSubjectEditor) {
        SubjectEditorDialog(
            onConfirm = { name, color ->
                vm.addSubject(name, color)
                showSubjectEditor = false
            },
            onDismiss = { showSubjectEditor = false }
        )
    }

    if (showSubjectBatch) {
        BatchSubjectDialog(
            onImport = { text ->
                showSubjectBatch = false
                vm.batchAddSubjects(text) { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showSubjectBatch = false }
        )
    }

    if (showPlanEditor) {
        PlanEditorDialog(
            plans = state.settings.plans,
            onSave = {
                vm.savePlans(it)
                showPlanEditor = false
            },
            onDismiss = { showPlanEditor = false }
        )
    }

    if (showGoalEditor) {
        GoalEditorDialog(
            currentHours = state.settings.weeklyGoalHours,
            onConfirm = {
                vm.setWeeklyGoal(it)
                showGoalEditor = false
            },
            onDismiss = { showGoalEditor = false }
        )
    }

    if (showPomodoroGoalEditor) {
        PomodoroGoalEditorDialog(
            currentCount = state.settings.dailyPomodoroGoal,
            onConfirm = {
                vm.setDailyPomodoroGoal(it)
                showPomodoroGoalEditor = false
            },
            onDismiss = { showPomodoroGoalEditor = false }
        )
    }
    }
}

/**
 * 同步状态卡:一张卡承载全部同步感知,用户不必进入任何页面就知道数据是否安全。
 * 未登录 → 说明数据只在本机 + 登录入口;已登录 → 三态(运行中/成功/失败)。
 * 服务器地址、同步频率等实现细节不在本卡出现。
 */
@Composable
private fun SyncStatusCard(
    loggedIn: Boolean,
    username: String,
    lastSyncAt: Long,
    sync: com.yanzhong.app.data.remote.SyncState,
    onLogin: () -> Unit,
    onSyncNow: () -> Unit
) {
    val statusText = when {
        sync.running -> "正在同步…"
        sync.lastError != null -> "同步遇到问题"
        else -> "已同步 · ${formatSyncTime(lastSyncAt, TimeUtils.now())}"
    }
    SectionCard(
        title = if (loggedIn) "自动同步已开启" else "数据只保存在本机",
        subtitle = if (loggedIn) (username.ifBlank { "已登录" }) else null,
        icon = AppIcons.CloudUpload,
        accent = MintGreen
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (loggedIn) {
                    TextButton(
                        onClick = onSyncNow,
                        enabled = !sync.running
                    ) { Text(if (sync.lastError != null) "重试" else "立即同步") }
                }
            }
            if (sync.lastError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    readableSyncError(sync.lastError),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoralRedDeep
                )
            }
            if (!loggedIn) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "登录后自动同步，换设备或重装可恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onLogin,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("登录并开启同步") }
                Spacer(Modifier.height(6.dp))
                Text(
                    "不登录也可以正常使用，数据不会丢失。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun LearningProfileCard(profile: LearningProfile) {
    SectionCard(title = "学习档案", icon = AppIcons.TrendingUp, accent = SkyBlue) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileMetric("累计专注", TimeUtils.formatHours(profile.totalFocusMin), Modifier.weight(1f))
            ProfileMetric("活跃天数", "${profile.activeDays} 天", Modifier.weight(1f))
            ProfileMetric("连续打卡", "${profile.streakDays} 天", Modifier.weight(1f))
            ProfileMetric(
                "峰值时段",
                profile.peakHour?.let { "${it}:00" } ?: "暂无",
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("科目投入", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (profile.perSubject.isEmpty()) {
            Text(
                "暂无科目投入",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            profile.perSubject.take(3).forEach { subject ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape).background(Color(subject.colorArgb))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(subject.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        TimeUtils.formatHours(subject.totalFocusMin),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("最近 7 天", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            profile.recentDaily.forEach { day ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${day.date.monthValue}/${day.date.dayOfMonth}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${day.totalFocusMin}m",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 夜空插画:渐变星空 + 月亮 + 两层山脉剪影(参考图3 我的页背景,纯代码绘制) */
@Composable
private fun NightSkyCanvas(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(
            brush = Brush.verticalGradient(
                listOf(NightSkyTop, NightSkyMid, NightSkyBottom)
            ),
            size = size
        )
        // 星星:固定坐标散布,大小/透明度交替
        val stars = listOf(
            0.10f to 0.16f, 0.24f to 0.09f, 0.36f to 0.22f, 0.52f to 0.12f,
            0.66f to 0.20f, 0.78f to 0.07f, 0.90f to 0.16f, 0.62f to 0.32f,
            0.16f to 0.34f, 0.44f to 0.05f
        )
        stars.forEachIndexed { i, (sx, sy) ->
            drawCircle(
                color = Color.White.copy(alpha = if (i % 3 == 0) 0.9f else 0.5f),
                radius = if (i % 2 == 0) 2.4f else 1.7f,
                center = Offset(w * sx, h * sy)
            )
        }
        // 月亮 + 光晕
        val moon = Offset(w * 0.80f, h * 0.20f)
        drawCircle(Color.White.copy(alpha = 0.10f), radius = 64f, center = moon)
        drawCircle(Color.White.copy(alpha = 0.20f), radius = 44f, center = moon)
        drawCircle(Color(0xFFFDF3DC), radius = 27f, center = moon)
        // 远山
        val far = Path().apply {
            moveTo(0f, h)
            lineTo(0f, h * 0.68f)
            lineTo(w * 0.16f, h * 0.46f)
            lineTo(w * 0.34f, h * 0.72f)
            lineTo(w * 0.52f, h * 0.52f)
            lineTo(w * 0.74f, h * 0.76f)
            lineTo(w * 0.90f, h * 0.60f)
            lineTo(w, h * 0.74f)
            lineTo(w, h)
            close()
        }
        drawPath(far, NightMountainFar)
        // 近山
        val near = Path().apply {
            moveTo(0f, h)
            lineTo(0f, h * 0.84f)
            lineTo(w * 0.22f, h * 0.66f)
            lineTo(w * 0.48f, h * 0.86f)
            lineTo(w * 0.70f, h * 0.72f)
            lineTo(w, h * 0.90f)
            lineTo(w, h)
            close()
        }
        drawPath(near, NightMountainNear)
    }
}

/** 快捷入口:渐变圆底图标 + 文字(参考图3 头像下方三入口) */
@Composable
private fun QuickEntry(
    icon: ImageVector,
    label: String,
    colors: List<Color>,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** 每日番茄目标数编辑(参考番茄ToDo,允许 1–30) */
@Composable
private fun PomodoroGoalEditorDialog(
    currentCount: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var count by remember { mutableIntStateOf(currentCount) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("每日番茄目标(个)") },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { if (count > 1) count-- }) { Text("− 1") }
                Text("$count 🍅", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { if (count < 30) count++ }) { Text("+ 1") }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(count) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 每周目标小时数编辑(作战计划基准 37–40h,允许 10–80) */
@Composable
private fun GoalEditorDialog(
    currentHours: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentHours.toString()) }
    val parsed = text.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("每周目标净学习(小时)") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(2) },
                    singleLine = true,
                    label = { Text("10 – 80 小时") }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "作战计划基准:黄金周 37–40h,保底周 25h+",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onConfirm(it.coerceIn(10, 80)) } },
                enabled = parsed != null
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 分组卡:彩色圆底图标 + 标题(参考图3 列表项图标色彩编码) */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    icon: ImageVector? = null,
    /** 置灰提示被上级开关覆盖(如静音模式压制),仍可预改设置 */
    dim: Boolean = false,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = if (dim) 0.45f else 1f }
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SubjectEditorDialog(
    onConfirm: (String, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var colorIdx by remember { mutableStateOf(0) }
    val palette = listOf(
        MathColor, Subject408Color, EnglishColor, PoliticsColor,
        Color(0xFF7E57C2), Color(0xFF26A69A), Color(0xFFEF5350), Color(0xFF5C6BC0)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加科目") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("科目名称(如:复试机试)") },
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Text("科目色", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.take(4).forEachIndexed { i, color ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { colorIdx = i }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (colorIdx == i) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.drop(4).forEachIndexed { i, color ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { colorIdx = i + 4 }
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (colorIdx == i + 4) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim(), palette[colorIdx].value.toLong() and 0xFFFFFFFFL)
                    }
                },
                enabled = name.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 批量添加科目弹窗:多行文本一行一条,同名自动跳过,颜色自动分配 */
@Composable
private fun BatchSubjectDialog(
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量添加科目") },
        text = {
            Column {
                Text(
                    "一行一个科目名称,已存在的同名科目自动跳过,颜色自动分配。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如:\n复试机试\n408 强化\n政治冲刺") },
                    minLines = 4,
                    maxLines = 8
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = text.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PlanEditorDialog(
    plans: List<PomodoroPlan>,
    onSave: (List<PomodoroPlan>) -> Unit,
    onDismiss: () -> Unit
) {
    var edited by remember(plans) {
        mutableStateOf(plans.map { PomodoroPlan.clamp(it) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑番茄方案") },
        text = {
            Column {
                edited.forEachIndexed { idx, plan ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        OutlinedTextField(
                            value = plan.name,
                            onValueChange = {
                                edited = edited.toMutableList().apply { set(idx, plan.copy(name = it)) }
                            },
                            label = { Text("名称") },
                            singleLine = true
                        )
                        StepperRow(
                            label = "专注 ${plan.focusMin} 分钟",
                            onMinus = {
                                if (plan.focusMin > 5) edited = edited.toMutableList()
                                    .apply { set(idx, plan.copy(focusMin = plan.focusMin - 5)) }
                            },
                            onPlus = {
                                if (plan.focusMin < 180) edited = edited.toMutableList()
                                    .apply { set(idx, plan.copy(focusMin = plan.focusMin + 5)) }
                            }
                        )
                        StepperRow(
                            label = "短休 ${plan.shortBreakMin} 分钟",
                            onMinus = {
                                if (plan.shortBreakMin > 1) edited = edited.toMutableList()
                                    .apply { set(idx, plan.copy(shortBreakMin = plan.shortBreakMin - 1)) }
                            },
                            onPlus = {
                                if (plan.shortBreakMin < 30) edited = edited.toMutableList()
                                    .apply { set(idx, plan.copy(shortBreakMin = plan.shortBreakMin + 1)) }
                            }
                        )
                        StepperRow(
                            label = "长休 ${plan.longBreakMin} 分钟",
                            onMinus = {
                                if (plan.longBreakMin > 5) edited = edited.toMutableList()
                                    .apply { set(idx, plan.copy(longBreakMin = plan.longBreakMin - 5)) }
                            },
                            onPlus = {
                                if (plan.longBreakMin < 60) edited = edited.toMutableList()
                                    .apply { set(idx, plan.copy(longBreakMin = plan.longBreakMin + 5)) }
                            }
                        )
                        StepperRow(
                            label = "每 ${plan.longBreakInterval} 个番茄一次长休",
                            onMinus = {
                                if (plan.longBreakInterval > 1) edited = edited.toMutableList()
                                    .apply { set(idx, plan.copy(longBreakInterval = plan.longBreakInterval - 1)) }
                            },
                            onPlus = {
                                if (plan.longBreakInterval < 12) edited = edited.toMutableList()
                                    .apply { set(idx, plan.copy(longBreakInterval = plan.longBreakInterval + 1)) }
                            }
                        )
                        if (edited.size > 1) {
                            TextButton(onClick = {
                                edited = edited.filterIndexed { i, _ -> i != idx }
                            }) { Text("删除此方案") }
                        }
                    }
                }
                TextButton(onClick = {
                    edited = edited + PomodoroPlan(
                        name = "方案 ${edited.size + 1}",
                        focusMin = 25, shortBreakMin = 5, longBreakMin = 30, longBreakInterval = 4
                    )
                }) { Text("＋ 新增方案") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(edited) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun StepperRow(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(onClick = onMinus) { Text("−") }
            TextButton(onClick = onPlus) { Text("＋") }
        }
    }
}
