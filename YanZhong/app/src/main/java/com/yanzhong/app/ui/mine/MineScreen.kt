package com.yanzhong.app.ui.mine

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.prefs.PomodoroPlan
import com.yanzhong.app.data.prefs.ThemeMode
import com.yanzhong.app.data.stats.LearningProfile
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.CoralRed
import com.yanzhong.app.ui.theme.EnglishColor
import com.yanzhong.app.ui.theme.MathColor
import com.yanzhong.app.ui.theme.MintGreen
import com.yanzhong.app.ui.theme.PlanAccent
import com.yanzhong.app.ui.theme.PlanAccent2
import com.yanzhong.app.ui.theme.PlanInk
import com.yanzhong.app.ui.theme.PlanMuted
import com.yanzhong.app.ui.theme.PoliticsColor
import com.yanzhong.app.ui.theme.SkyBlue
import com.yanzhong.app.ui.theme.Subject408Color
import com.yanzhong.app.util.TimeUtils

/**
 * 我的页:对齐《468 天全程作战计划》的设计语言——靛蓝/紫渐变 hero + 玻璃拟态卡 +
 * 带序号的分区标题。这一页是整个「学习档案」的门面,不堆功能,只做分区清晰的收纳。
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

    val palette = minePalette()

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        PlanAccent.copy(alpha = 0.12f),
                        PlanAccent2.copy(alpha = 0.06f),
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
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
                top = 10.dp,
                bottom = padding.calculateBottomPadding() + 72.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "hero") { MineHero(state, palette) }

            item(key = "head-profile") {
                MineSectionHead("01", "学习档案", "AI 排计划要用的两份底稿", palette)
            }

            item(key = "account-card") {
                AccountCard(
                    account = state.account,
                    palette = palette,
                    onOpen = { navController.navigate(Routes.ACCOUNT) { launchSingleTop = true } }
                )
            }

            // 备考档案:AI 制定计划的唯一输入,放在账号卡下面,一眼可见入口
            item(key = "exam-profile") {
                MineCard(
                    title = "备考档案",
                    subtitle = "AI 按这份档案排计划",
                    icon = AppIcons.Target,
                    accent = SkyBlue,
                    palette = palette
                ) {
                    MineEntryRow(
                        text = "查看或调整备考条件(考试科目、目标院校、每日时间)",
                        palette = palette
                    ) {
                        navController.navigate(Routes.planSetup()) { launchSingleTop = true }
                    }
                }
            }

            item(key = "head-stats") {
                MineSectionHead("02", "数据总览", "最近的专注投入", palette)
            }

            item(key = "learning-profile") {
                LearningProfileCard(state.learningProfile, palette)
            }

            item(key = "head-prefs") {
                MineSectionHead("03", "偏好设置", "番茄钟与提醒", palette)
            }

            item(key = "theme") {
                MineCard(title = "主题", icon = AppIcons.Palette, accent = SkyBlue, palette = palette) {
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
                MineCard(
                    title = "番茄方案",
                    subtitle = "选一套,或改到顺手为止",
                    icon = AppIcons.Timer,
                    accent = CoralRed,
                    palette = palette
                ) {
                    state.settings.plans.forEachIndexed { index, plan ->
                        val selected = plan.name == state.settings.currentPlanName
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) PlanAccent.copy(alpha = 0.10f) else palette.tile,
                            border = if (selected) BorderStroke(1.dp, PlanAccent.copy(alpha = 0.45f))
                            else BorderStroke(1.dp, palette.tileBorder),
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
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) PlanAccent else palette.ink
                                    )
                                    Text(
                                        "专注 ${plan.focusMin} · 短休 ${plan.shortBreakMin} · " +
                                            "长休 ${plan.longBreakMin} · 每轮 ${plan.longBreakInterval}🍅",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.muted
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        AppIcons.Check,
                                        contentDescription = null,
                                        tint = PlanAccent,
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
                MineCard(title = "专注偏好", icon = AppIcons.Sliders, accent = MintGreen, palette = palette) {
                    // 学霸模式入口(参考番茄ToDo 学霸模式)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = PlanAccent.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, PlanAccent.copy(alpha = 0.35f)),
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
                                tint = PlanAccent,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("学霸模式", style = MaterialTheme.typography.bodyLarge, color = palette.ink)
                                Text(
                                    when {
                                        !state.settings.superModeOn -> "专注期间拦截非白名单应用 · 未开启"
                                        state.settings.superModeStrict -> "严格锁定 · 已开启"
                                        else -> "白名单放行 ${state.settings.whitelist.size} 个应用 · 已开启"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.muted
                                )
                            }
                            Icon(
                                AppIcons.ChevronRight,
                                contentDescription = null,
                                tint = palette.muted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SwitchRow("自动串联(休息结束自动开始下一个)", state.settings.autoChain, palette, AppIcons.Repeat) {
                        vm.setAutoChain(it)
                    }
                    Spacer(Modifier.height(4.dp))
                    SwitchRow("连续专注(跳过休息)", state.settings.continuousFocus, palette, AppIcons.InfinityLoop) {
                        vm.setContinuousFocus(it)
                    }
                    Spacer(Modifier.height(4.dp))
                    SwitchRow("静音模式", state.settings.silentMode, palette, AppIcons.VolumeX) {
                        vm.setSilent(it)
                    }
                    if (state.settings.silentMode) {
                        Text(
                            "已静音:阶段切换不震动、不出声,横幅与弹窗提醒不受影响",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 28.dp, bottom = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        "阶段切换震动", state.settings.vibrationOn, palette, AppIcons.Vibrate,
                        dim = state.settings.silentMode
                    ) {
                        vm.setVibration(it)
                    }
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        "阶段切换音效", state.settings.soundOn, palette, AppIcons.Music,
                        dim = state.settings.silentMode
                    ) {
                        vm.setSound(it)
                    }
                    Spacer(Modifier.height(10.dp))
                    MineGoalRow(
                        title = "每周目标净学习",
                        subtitle = "今日页与统计页周报的达标线",
                        value = "${state.settings.weeklyGoalHours} 小时",
                        palette = palette,
                        onClick = { showGoalEditor = true }
                    )
                    Spacer(Modifier.height(8.dp))
                    MineGoalRow(
                        title = "每日番茄目标",
                        subtitle = "今日页番茄数的达标线",
                        value = "${state.settings.dailyPomodoroGoal} 个",
                        palette = palette,
                        onClick = { showPomodoroGoalEditor = true }
                    )
                }
            }

            item(key = "head-subjects") {
                MineSectionHead("04", "科目管理", "番茄钟里的分类维度", palette)
            }

            item(key = "subjects") {
                MineCard(title = "科目", icon = AppIcons.GraduationCap, accent = MathColor, palette = palette) {
                    if (state.subjects.isEmpty()) {
                        Text(
                            "还没有科目,先添加几个再开始专注",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.muted
                        )
                    } else {
                        state.subjects.forEachIndexed { index, subject ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = palette.tile,
                                border = BorderStroke(1.dp, palette.tileBorder),
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
                                        color = palette.ink,
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

            item(key = "head-about") {
                MineSectionHead("05", "关于", null, palette)
            }

            item(key = "about") {
                MineCard(title = "研钟 YanZhong", icon = AppIcons.Info, accent = Subject408Color, palette = palette) {
                    // 版本号跟 BuildConfig 走(F5):写死会随发版漂移,关于页和实际包对不上
                    Text("版本 ${com.yanzhong.app.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyLarge, color = palette.ink)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "学什么用扇贝,怎么学、学得怎么样用研钟。\n本地数据 · 零广告 · 零社区",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.muted
                    )
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

// ---------- 设计基元:深浅两套玻璃拟态配色 ----------

/**
 * 我的页配色。玻璃拟态卡在浅色/深色下需要两套参数,统一从主题背景亮度判断,
 * 只在这一个地方做分支,下面所有组件都拿同一份 palette,避免颜色各自为政。
 */
private data class MinePalette(
    val dark: Boolean,
    val glass: Color,
    val border: Color,
    val ink: Color,
    val muted: Color,
    val tile: Color,
    val tileBorder: Color
)

@Composable
private fun minePalette(): MinePalette {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        MinePalette(
            dark = true,
            glass = Color.White.copy(alpha = 0.06f),
            border = Color.White.copy(alpha = 0.10f),
            ink = Color(0xFFE6E7F0),
            muted = Color(0xFFA8ABC8),
            tile = Color.White.copy(alpha = 0.05f),
            tileBorder = Color.White.copy(alpha = 0.08f)
        )
    } else {
        MinePalette(
            dark = false,
            glass = Color.White.copy(alpha = 0.74f),
            border = Color.White,
            ink = PlanInk,
            muted = PlanMuted,
            tile = Color.White.copy(alpha = 0.62f),
            tileBorder = Color(0x141C2240)
        )
    }
}

/** 玻璃拟态卡:圆角 20dp + 半透明底 + 1dp 亮边,对标参考文档的 .card */
@Composable
private fun MineCard(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = PlanAccent,
    palette: MinePalette,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = palette.glass,
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = if (palette.dark) 0.dp else 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.muted)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** 分区标题:mono 序号 + 大号标题 + 一行说明,对标参考文档的 .sec-head */
@Composable
private fun MineSectionHead(no: String, title: String, lead: String?, palette: MinePalette) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            no,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = PlanAccent
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = palette.ink
            )
            if (!lead.isNullOrBlank()) {
                Text(lead, style = MaterialTheme.typography.bodySmall, color = palette.muted)
            }
        }
    }
}

/** 卡片内的可点行:标题行 + 右箭头,用于「备考档案」这类跳转入口 */
@Composable
private fun MineEntryRow(text: String, palette: MinePalette, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .background(palette.tile)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = palette.ink)
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = palette.muted, modifier = Modifier.size(20.dp))
    }
}

/** 卡片内的数值行:左标题/说明,右侧可点数值按钮 */
@Composable
private fun MineGoalRow(
    title: String,
    subtitle: String,
    value: String,
    palette: MinePalette,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = palette.tile,
        border = BorderStroke(1.dp, palette.tileBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                AppIcons.Target,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = palette.ink)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.muted)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClick) { Text(value) }
        }
    }
}

// ---------- Hero ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MineHero(state: MineUiState, palette: MinePalette) {
    val profile = state.learningProfile
    val username = when (val account = state.account) {
        is AccountState.LoggedIn -> account.username.ifBlank { "已登录" }
        AccountState.LoggedOut -> "本机学习档案"
        // 首帧未定:留空比闪一下"未登录"更稳
        AccountState.Loading -> ""
    }
    val titleBrush = if (palette.dark) {
        Brush.linearGradient(listOf(Color(0xFF818CF8), Color(0xFFC4B5FD)))
    } else {
        Brush.linearGradient(listOf(PlanAccent, PlanAccent2))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 徽标胶囊:左上角小圆点 + 文案,和规划文档 hero 是同一套
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = PlanAccent.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, PlanAccent.copy(alpha = 0.22f))
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(PlanAccent, PlanAccent2)))
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "研钟 · 学习档案",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PlanAccent
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // 头像:靛蓝→紫渐变圆 + 白学士帽
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(PlanAccent, PlanAccent2))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.GraduationCap,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        if (username.isNotBlank()) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(brush = titleBrush)) { append(username) }
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = palette.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
        }

        if (state.subjects.isEmpty()) {
            Text(
                "尚未设置科目 · 点下方「科目管理」补齐",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                state.subjects.forEach { subject ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color(subject.colorArgb)
                    ) {
                        Text(
                            subject.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MineStatCell("累计专注", TimeUtils.formatHours(profile.totalFocusMin), palette, Modifier.weight(1f))
                MineStatCell("活跃天数", "${profile.activeDays} 天", palette, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MineStatCell("连续打卡", "${profile.streakDays} 天", palette, Modifier.weight(1f))
                MineStatCell(
                    "峰值时段",
                    profile.peakHour?.let { "${it}:00" } ?: "暂无",
                    palette,
                    Modifier.weight(1f)
                )
            }
            // 全 0 空态引导(A6):四张卡都是 0 时看起来像"什么都没发生"
            if (profile.totalFocusMin == 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "完成第一个番茄后，这里会亮起来",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** 玻璃数据格:大字数值 + 小字标签,对标参考文档 hero 里的 .stats 单元 */
@Composable
private fun MineStatCell(label: String, value: String, palette: MinePalette, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = palette.glass,
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = if (palette.dark) 0.dp else 1.dp,
        modifier = modifier
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = palette.ink,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---------- 账号卡 ----------

/**
 * 账号卡:一个入口进去管理账号,不在主页堆登录表单与同步按钮。
 * 登录态未定(首帧)时既不显示"已登录"也不显示"未登录",避免误报。
 */
@Composable
private fun AccountCard(
    account: AccountState,
    palette: MinePalette,
    onOpen: () -> Unit
) {
    val title = when (account) {
        is AccountState.LoggedIn -> account.username.ifBlank { "已登录" }
        AccountState.LoggedOut -> "未登录"
        AccountState.Loading -> "账号"
    }
    val subtitle = when (account) {
        is AccountState.LoggedIn -> "计划与每日进度已存服务端 · 多设备共用"
        AccountState.LoggedOut -> "数据只保存在本机"
        AccountState.Loading -> "正在读取登录状态…"
    }
    MineCard(
        title = title,
        subtitle = subtitle,
        icon = AppIcons.CloudUpload,
        accent = MintGreen,
        palette = palette
    ) {
        MineEntryRow(
            text = when (account) {
                is AccountState.LoggedIn -> "管理账号与登录设备"
                AccountState.LoggedOut -> "登录后计划与进度自动保存到服务端"
                AccountState.Loading -> " "
            },
            palette = palette,
            onClick = onOpen
        )
    }
}

// ---------- 数据总览 ----------

@Composable
private fun LearningProfileCard(profile: LearningProfile, palette: MinePalette) {
    MineCard(title = "学习数据", icon = AppIcons.TrendingUp, accent = SkyBlue, palette = palette) {
        Text(
            "科目投入",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.ink
        )
        Spacer(Modifier.height(8.dp))
        if (profile.perSubject.isEmpty()) {
            Text("暂无科目投入", style = MaterialTheme.typography.bodySmall, color = palette.muted)
        } else {
            profile.perSubject.take(3).forEach { subject ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(subject.colorArgb)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        subject.name,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.ink
                    )
                    Text(
                        TimeUtils.formatHours(subject.totalFocusMin),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.ink
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "最近 7 天",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.ink
        )
        Spacer(Modifier.height(10.dp))
        val maxMin = profile.recentDaily.maxOfOrNull { it.totalFocusMin }?.coerceAtLeast(1) ?: 1
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
                                .background(
                                    if (day.totalFocusMin <= 0) SolidColor(palette.tileBorder)
                                    else Brush.verticalGradient(listOf(PlanAccent, PlanAccent2))
                                )
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${day.date.monthValue}/${day.date.dayOfMonth}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted
                    )
                }
            }
        }
    }
}

// ---------- 开关行 ----------

/** 设置开关行:可选前置图标,置灰表示被上级开关覆盖(如静音模式压制),仍可预改设置 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    palette: MinePalette,
    icon: ImageVector? = null,
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
                .then(if (dim) Modifier.alpha(0.45f) else Modifier)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = palette.muted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge, color = palette.ink)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------- 弹窗 ----------

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
