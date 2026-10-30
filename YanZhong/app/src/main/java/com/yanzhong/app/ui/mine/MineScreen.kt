package com.yanzhong.app.ui.mine

import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.BuildConfig
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.prefs.PomodoroPlan
import com.yanzhong.app.data.prefs.ThemeMode
import com.yanzhong.app.data.stats.LearningProfile
import com.yanzhong.app.ui.legal.PolicyLinksRow
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.BrandGradient
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.EmptyState
import com.yanzhong.app.ui.theme.EnglishColor
import com.yanzhong.app.ui.theme.GradientCard
import com.yanzhong.app.ui.theme.GradientTag
import com.yanzhong.app.ui.theme.ListRow
import com.yanzhong.app.ui.theme.MathColor
import com.yanzhong.app.ui.theme.MetricTone
import com.yanzhong.app.ui.theme.MetricTile
import com.yanzhong.app.ui.theme.PoliticsColor
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.RowDivider
import com.yanzhong.app.ui.theme.RowGroup
import com.yanzhong.app.ui.theme.SecondaryButton
import com.yanzhong.app.ui.theme.SectionHeader
import com.yanzhong.app.ui.theme.SegmentedChips
import com.yanzhong.app.ui.theme.SubjectCsColor
import com.yanzhong.app.ui.theme.SwitchRow
import com.yanzhong.app.ui.theme.TextAction
import com.yanzhong.app.util.TimeUtils

/**
 * 我的页:渐变头部卡(身份 / 科目 / 四项指标)+ 分组设置列表。
 * 行与卡片全部来自共享组件,页面不再自带配色、私有卡片与分隔线。
 */
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

    val accountTitle = when (val account = state.account) {
        is AccountState.LoggedIn -> account.username.ifBlank { "已登录" }
        AccountState.LoggedOut -> "未登录"
        AccountState.Loading -> "账号"
    }
    val accountSubtitle = when (state.account) {
        is AccountState.LoggedIn -> "计划与进度已存服务端 · 多设备共用"
        AccountState.LoggedOut -> "登录后数据自动保存到服务端"
        AccountState.Loading -> "正在读取登录状态…"
    }
    val planSummary = state.settings.plans
        .find { it.name == state.settings.currentPlanName }
        ?.let { "专注${it.focusMin} · 短休${it.shortBreakMin} · 长休${it.longBreakMin}" }
        ?: "选一套，或改到顺手为止"
    val superModeSubtitle = when {
        !state.settings.superModeOn -> "专注期间拦截非白名单应用 · 未开启"
        state.settings.superModeStrict -> "严格锁定 · 已开启"
        else -> "白名单放行 ${state.settings.whitelist.size} 个应用 · 已开启"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
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
            item(key = "header") {
                HeaderCard(
                    state,
                    onOpenAccount = {
                        navController.navigate(Routes.ACCOUNT) { launchSingleTop = true }
                    }
                )
            }

            item(key = "account") {
                Column {
                    SectionHeader("账号与档案")
                    Spacer(Modifier.height(8.dp))
                    RowGroup {
                        ListRow(
                            title = accountTitle,
                            subtitle = accountSubtitle,
                            icon = AppIcons.CloudUpload,
                            onClick = {
                                navController.navigate(Routes.ACCOUNT) { launchSingleTop = true }
                            }
                        )
                        RowDivider()
                        ListRow(
                            title = "备考档案",
                            subtitle = "考试科目、目标院校、课表",
                            icon = AppIcons.Target,
                            onClick = {
                                navController.navigate(Routes.planSetup()) { launchSingleTop = true }
                            }
                        )
                    }
                }
            }

            item(key = "learning-profile") {
                AppCard { LearningProfileCard(state.learningProfile) }
            }

            item(key = "prefs") {
                Column {
                    SectionHeader("偏好设置")
                    Spacer(Modifier.height(8.dp))
                    RowGroup {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.Palette,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("外观", style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.height(8.dp))
                            SegmentedChips(
                                options = listOf("浅色", "深色", "跟随系统"),
                                selectedIndex = listOf(
                                    ThemeMode.LIGHT,
                                    ThemeMode.DARK,
                                    ThemeMode.SYSTEM
                                ).indexOf(state.settings.themeMode),
                                onSelect = {
                                    vm.setTheme(
                                        listOf(
                                            ThemeMode.LIGHT,
                                            ThemeMode.DARK,
                                            ThemeMode.SYSTEM
                                        )[it]
                                    )
                                }
                            )
                        }
                        RowDivider()
                        ListRow(
                            title = "番茄方案",
                            subtitle = planSummary,
                            icon = AppIcons.Timer,
                            onClick = { showPlanEditor = true },
                            trailing = {
                                Text(
                                    state.settings.currentPlanName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                        RowDivider()
                        ListRow(
                            title = "学霸模式",
                            subtitle = superModeSubtitle,
                            icon = AppIcons.Shield,
                            onClick = { navController.navigate(Routes.SUPERMODE) }
                        )
                        RowDivider()
                        SwitchRow(
                            "自动串联",
                            state.settings.autoChain,
                            vm::setAutoChain,
                            subtitle = "休息结束自动开始下一个"
                        )
                        RowDivider()
                        SwitchRow(
                            "连续专注",
                            state.settings.continuousFocus,
                            vm::setContinuousFocus,
                            subtitle = "跳过休息，一路专注下去"
                        )
                        RowDivider()
                        SwitchRow(
                            "静音模式",
                            state.settings.silentMode,
                            vm::setSilent,
                            subtitle = "阶段切换不震动、不出声"
                        )
                        RowDivider()
                        SwitchRow(
                            "阶段切换震动",
                            state.settings.vibrationOn,
                            vm::setVibration,
                            dimmed = state.settings.silentMode
                        )
                        RowDivider()
                        SwitchRow(
                            "阶段切换音效",
                            state.settings.soundOn,
                            vm::setSound,
                            dimmed = state.settings.silentMode
                        )
                        RowDivider()
                        ListRow(
                            title = "每周目标净学习",
                            subtitle = "今日页与统计页的达标线",
                            icon = AppIcons.Target,
                            onClick = { showGoalEditor = true },
                            trailing = {
                                Text(
                                    "${state.settings.weeklyGoalHours} 小时",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                        RowDivider()
                        ListRow(
                            title = "每日番茄目标",
                            subtitle = "今日页番茄数的达标线",
                            icon = AppIcons.Target,
                            onClick = { showPomodoroGoalEditor = true },
                            trailing = {
                                Text(
                                    "${state.settings.dailyPomodoroGoal} 个",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
            }

            item(key = "subjects") {
                Column {
                    SectionHeader("科目管理")
                    Spacer(Modifier.height(8.dp))
                    RowGroup {
                        if (state.subjects.isEmpty()) {
                            EmptyState(
                                icon = AppIcons.SubjectDefault,
                                title = "还没有科目",
                                hint = "先添加几个再开始专注",
                                compact = true
                            )
                        } else {
                            state.subjects.forEachIndexed { index, subject ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(subject.colorArgb))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        subject.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { deleteSubjectCandidate = subject }) {
                                        Icon(
                                            AppIcons.Delete,
                                            contentDescription = "删除科目 ${subject.name}",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                if (index < state.subjects.size - 1) RowDivider()
                            }
                        }
                        RowDivider()
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextAction(
                                "添加科目",
                                onClick = { showSubjectEditor = true },
                                icon = AppIcons.Add
                            )
                            TextAction("批量添加", onClick = { showSubjectBatch = true })
                        }
                    }
                }
            }

            item(key = "about") {
                Column {
                    SectionHeader("关于")
                    Spacer(Modifier.height(8.dp))
                    RowGroup {
                        ListRow(
                            title = "研钟 YanZhong",
                            subtitle = "v${BuildConfig.VERSION_NAME} · 本地数据 · 零广告",
                            icon = AppIcons.Info
                        )
                        RowDivider()
                        PolicyLinksRow(prefix = "隐私与协议：")
                    }
                }
            }
        }
    }

    /** 删除科目二次确认:外键关联的任务与专注记录将一并删除 */
    deleteSubjectCandidate?.let { subject ->
        AlertDialog(
            onDismissRequest = { deleteSubjectCandidate = null },
            title = {
                Text(
                    "删除科目「${subject.name}」?",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text("该科目及其下所有任务、专注记录将被永久删除,统计中对应数据也会消失。此操作无法恢复。")
            },
            confirmButton = {
                DialogActions(
                    confirmText = "删除",
                    onConfirm = {
                        vm.deleteSubject(subject)
                        deleteSubjectCandidate = null
                    },
                    onDismiss = { deleteSubjectCandidate = null }
                )
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

// ---------- 头部卡 ----------

/** 渐变头部卡:身份 + 科目标签 + 四项指标,整卡点击进入账号页 */
@Composable
private fun HeaderCard(state: MineUiState, onOpenAccount: () -> Unit) {
    val profile = state.learningProfile
    val username = when (val account = state.account) {
        is AccountState.LoggedIn -> account.username.ifBlank { "已登录" }
        AccountState.LoggedOut -> "本机学习档案"
        // 首帧未定:留空比闪一下"未登录"更稳
        AccountState.Loading -> ""
    }
    val statusLine = when (state.account) {
        is AccountState.LoggedIn -> "已登录 · 云端同步"
        AccountState.LoggedOut -> "数据保存在本机"
        AccountState.Loading -> " "
    }

    GradientCard(onClick = onOpenAccount) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    AppIcons.GraduationCap,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (username.isNotBlank()) {
                    Text(
                        username,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1
                    )
                }
                Text(
                    statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        if (state.subjects.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.subjects.take(4).forEach { subject ->
                    GradientTag(subject.name)
                }
                if (state.subjects.size > 4) {
                    GradientTag("等 ${state.subjects.size} 科")
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile(
                TimeUtils.formatHours(profile.totalFocusMin),
                "累计专注",
                Modifier.weight(1f),
                compact = true,
                tone = MetricTone.ON_GRADIENT
            )
            MetricTile(
                "${profile.activeDays}",
                "活跃天数",
                Modifier.weight(1f),
                unit = "天",
                compact = true,
                tone = MetricTone.ON_GRADIENT
            )
            MetricTile(
                "${profile.streakDays}",
                "连续打卡",
                Modifier.weight(1f),
                unit = "天",
                compact = true,
                tone = MetricTone.ON_GRADIENT
            )
            MetricTile(
                profile.peakHour?.let { "%02d:00".format(it) } ?: "--",
                "峰值时段",
                Modifier.weight(1f),
                compact = true,
                tone = MetricTone.ON_GRADIENT
            )
        }

        // 全 0 空态引导:四格都是 0 时看起来像"什么都没发生"
        if (profile.totalFocusMin == 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "完成第一个番茄后，这里会亮起来",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---------- 投入与节奏 ----------

/** 科目投入 Top 3 + 最近 7 天柱状图;容器由调用方的 AppCard 提供 */
@Composable
private fun LearningProfileCard(profile: LearningProfile) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.TrendingUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("投入与节奏", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))

        if (profile.perSubject.isEmpty()) {
            Text(
                "暂无科目投入",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val top = profile.perSubject.take(3)
            top.forEachIndexed { index, subject ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(subject.colorArgb))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        subject.name,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        TimeUtils.formatHours(subject.totalFocusMin),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (index < top.size - 1) RowDivider(startIndent = 28.dp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("最近 7 天", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        val maxMin = profile.recentDaily.maxOfOrNull { it.totalFocusMin }?.coerceAtLeast(1) ?: 1
        if (profile.recentDaily.all { it.totalFocusMin <= 0 }) {
            // 全 0 时空柱状图是一大片无意义的留白,一句空态顶掉整块图区
            Text(
                "最近 7 天还没有专注记录,去「今日」开始第一个番茄吧",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                textAlign = TextAlign.Center
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                profile.recentDaily.forEach { day ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.height(64.dp), contentAlignment = Alignment.BottomCenter) {
                            val barHeight = if (day.totalFocusMin <= 0) 4.dp
                            else (6f + 46f * day.totalFocusMin / maxMin).dp
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(barHeight)
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(
                                        if (day.totalFocusMin <= 0) {
                                            Modifier.background(MaterialTheme.colorScheme.outlineVariant)
                                        } else {
                                            Modifier.background(Brush.verticalGradient(BrandGradient))
                                        }
                                    )
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${day.date.monthValue}/${day.date.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ---------- 弹窗 ----------

/** 弹窗底部按钮:取消在左、主操作在右,各占一半宽度,冲齐弹窗内容 */
@Composable
private fun DialogActions(
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SecondaryButton(
            text = "取消",
            onClick = onDismiss,
            modifier = Modifier.weight(1f)
        )
        PrimaryButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 科目色圆点:48dp 触达区 + 32dp 视觉圆 */
@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
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
        title = {
            Text(
                "每日番茄目标(个)",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { if (count > 1) count-- }) { Text("− 1") }
                Text("$count", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { if (count < 30) count++ }) { Text("+ 1") }
            }
        },
        confirmButton = {
            DialogActions(
                confirmText = "保存",
                onConfirm = { onConfirm(count) },
                onDismiss = onDismiss
            )
        }
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
        title = {
            Text(
                "每周目标净学习(小时)",
                style = MaterialTheme.typography.titleMedium
            )
        },
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
            DialogActions(
                confirmText = "保存",
                onConfirm = { parsed?.let { onConfirm(it.coerceIn(10, 80)) } },
                onDismiss = onDismiss,
                confirmEnabled = parsed != null
            )
        }
    )
}

@Composable
private fun SubjectEditorDialog(
    onConfirm: (String, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var colorIdx by remember { mutableStateOf(0) }
    val palette = listOf(
        MathColor, SubjectCsColor, EnglishColor, PoliticsColor,
        Color(0xFF7E57C2), Color(0xFF26A69A), Color(0xFFEF5350), Color(0xFF5C6BC0)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加科目", style = MaterialTheme.typography.titleMedium) },
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
                        ColorSwatch(color, selected = colorIdx == i) { colorIdx = i }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.drop(4).forEachIndexed { i, color ->
                        ColorSwatch(color, selected = colorIdx == i + 4) { colorIdx = i + 4 }
                    }
                }
            }
        },
        confirmButton = {
            DialogActions(
                confirmText = "添加",
                onConfirm = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim(), palette[colorIdx].value.toLong() and 0xFFFFFFFFL)
                    }
                },
                onDismiss = onDismiss,
                confirmEnabled = name.isNotBlank()
            )
        }
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
        title = { Text("批量添加科目", style = MaterialTheme.typography.titleMedium) },
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
            DialogActions(
                confirmText = "添加",
                onConfirm = { onImport(text) },
                onDismiss = onDismiss,
                confirmEnabled = text.isNotBlank()
            )
        }
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
        title = { Text("编辑番茄方案", style = MaterialTheme.typography.titleMedium) },
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
        confirmButton = {
            DialogActions(
                confirmText = "保存",
                onConfirm = { onSave(edited) },
                onDismiss = onDismiss
            )
        }
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
