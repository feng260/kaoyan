package com.yanzhong.app.ui.supermode

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.prefs.AppSettings
import com.yanzhong.app.service.FocusService
import com.yanzhong.app.service.StrictGuardPolicy
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.ListRow
import com.yanzhong.app.ui.theme.PillTone
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.RowDivider
import com.yanzhong.app.ui.theme.RowGroup
import com.yanzhong.app.ui.theme.SecondaryButton
import com.yanzhong.app.ui.theme.SectionHeader
import com.yanzhong.app.ui.theme.StatusPill
import com.yanzhong.app.ui.theme.SubPageHeader
import com.yanzhong.app.ui.theme.TextAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SuperModeUiState(
    val settings: AppSettings = AppSettings(),
    val subjects: List<SubjectEntity> = emptyList(),
    /** 使用情况访问权限:标准模式识别白名单应用依赖 */
    val usageAccess: Boolean = false,
    /** 悬浮窗权限(「显示在其他应用上层」):全屏覆盖层拦截依赖 */
    val overlayAccess: Boolean = false
)

class SuperModeViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val container = app as YanZhongApp
    private val settingsRepo = container.settingsRepo

    private val usageAccess = MutableStateFlow(FocusService.hasUsageAccess(app))
    private val overlayAccess = MutableStateFlow(FocusService.hasOverlayPermission(app))

    val uiState: StateFlow<SuperModeUiState> = combine(
        settingsRepo.settings,
        container.repository.observeSubjects(),
        usageAccess,
        overlayAccess
    ) { settings, subjects, access, overlay ->
        SuperModeUiState(settings, subjects, access, overlay)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SuperModeUiState())

    /** 从系统授权页返回时刷新权限状态 */
    fun refreshUsageAccess() {
        usageAccess.update { FocusService.hasUsageAccess(getApplication()) }
        overlayAccess.update { FocusService.hasOverlayPermission(getApplication()) }
    }

    fun setOn(on: Boolean) = viewModelScope.launch { settingsRepo.setSuperMode(on) }

    fun setStrict(strict: Boolean) = viewModelScope.launch { settingsRepo.setSuperModeStrict(strict) }

    fun savePin(pin: String, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { settingsRepo.setEmergencyPin(pin) }
        onDone()
    }
}

/**
 * 学霸模式设置页(参考番茄ToDo):
 * 总开关 → 严格等级(标准/严格) → 使用情况权限引导 → 6 位紧急退出密码 → 白名单管理。
 */
@Composable
fun SuperModeScreen(padding: PaddingValues, navController: androidx.navigation.NavHostController) {
    val vm: SuperModeViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showPinEditor by remember { mutableStateOf(false) }
    var showStrictOverlayRequired by remember { mutableStateOf(false) }

    // 从系统授权页返回时刷新权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshUsageAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 平板:限宽 720dp 居中
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(bottom = padding.calculateBottomPadding() + 24.dp)
        ) {
            SubPageHeader(
                title = "学霸模式",
                meta = "专注期间拦截非白名单应用",
                onBack = { navController.popBackStack() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))
                StatusCard(
                    enabled = state.settings.superModeOn,
                    onChange = { vm.setOn(it) }
                )

                if (state.settings.superModeOn) {
                    StrictLevelSection(
                        strict = state.settings.superModeStrict,
                        onSelect = { strict ->
                            if (StrictGuardPolicy.needsOverlayPrompt(strict, state.overlayAccess)) {
                                showStrictOverlayRequired = true
                            } else {
                                vm.setStrict(strict)
                            }
                        }
                    )
                    OverlaySection(granted = state.overlayAccess)

                    if (!state.settings.superModeStrict) {
                        UsageAccessSection(granted = state.usageAccess)
                        WhitelistSection(
                            settings = state.settings,
                            subjects = state.subjects,
                            onOpenPicker = { subjectId -> navController.navigate(Routes.appPicker(subjectId)) }
                        )
                    } else {
                        EmergencyPinSection(
                            configured = state.settings.emergencyPinHash.isNotEmpty(),
                            onEdit = { showPinEditor = true }
                        )
                    }
                }
            }
        }
    }

    if (showPinEditor) {
        PinEditorDialog(
            onSave = { pin ->
                vm.savePin(pin) { showPinEditor = false }
            },
            onDismiss = { showPinEditor = false }
        )
    }

    if (showStrictOverlayRequired) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showStrictOverlayRequired = false },
            title = { Text("严格模式需要悬浮窗权限") },
            text = {
                Text(
                    "严格锁定依赖全屏覆盖层阻止按 Home 键回到桌面。\n" +
                        "没有「显示在其他应用上层」权限时，严格模式无法兑现“无法离开”的承诺。"
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
                                    android.net.Uri.parse("package:${context.packageName}")
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

/** 总开关状态卡:一个状态徽标 + 一句说明 + 唯一的行动按钮 */
@Composable
private fun StatusCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "启用学霸模式",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            StatusPill(
                text = if (enabled) "已开启" else "未开启",
                tone = if (enabled) PillTone.SOLID else PillTone.NEUTRAL
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "开启后,启动番茄钟专注计时时自动拦截非白名单应用,计时结束自动解除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        if (enabled) {
            SecondaryButton(text = "关闭学霸模式", onClick = { onChange(false) })
        } else {
            PrimaryButton(text = "开启学霸模式", onClick = { onChange(true) })
        }
    }
}

/** 严格等级:两档二选一,当前档位用勾选图标标出 */
@Composable
private fun StrictLevelSection(strict: Boolean, onSelect: (Boolean) -> Unit) {
    Spacer(Modifier.height(20.dp))
    SectionHeader(title = "严格等级")
    Spacer(Modifier.height(8.dp))
    RowGroup {
        ListRow(
            title = "标准模式",
            subtitle = "允许正常使用白名单内的应用(词典、网课、刷题等),屏蔽其余所有应用",
            onClick = { onSelect(false) },
            showChevron = false,
            trailing = { SelectedMark(selected = !strict) }
        )
        RowDivider()
        ListRow(
            title = "严格模式",
            subtitle = "完全锁定在日拱专注界面,无法退出、无法暂停、无法使用任何其他应用",
            onClick = { onSelect(true) },
            showChevron = false,
            trailing = { SelectedMark(selected = strict) }
        )
    }
}

@Composable
private fun SelectedMark(selected: Boolean) {
    if (selected) {
        Icon(
            AppIcons.Check,
            contentDescription = "使用中",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 悬浮窗拦截:状态一句话,详情按需展开,未授权时给唯一行动按钮 */
@Composable
private fun OverlaySection(granted: Boolean) {
    val context = LocalContext.current
    var detailOpen by remember { mutableStateOf(false) }

    Spacer(Modifier.height(20.dp))
    SectionHeader(
        title = "悬浮窗拦截",
        action = {
            TextAction(
                text = if (detailOpen) "收起详情" else "查看详情",
                onClick = { detailOpen = !detailOpen }
            )
        }
    )
    Spacer(Modifier.height(8.dp))
    AppCard {
        Text(
            if (granted) "已授权 · 专注期间非白名单应用被全屏锁定层拦截。"
            else "未授权 · 严格模式无法锁定 Home 键，请开启后再选严格模式。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (detailOpen) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (granted)
                    "「显示在其他应用上层」生效中:专注期间对非白名单应用渲染全屏锁定层,直接拦截触摸,消除按 Home 键后的最大逃逸口。"
                else
                    "开启「显示在其他应用上层」后,专注期间对非白名单应用渲染全屏锁定层直接拦截触摸;" +
                        "未授权时,严格模式入口和开钟按钮都会先要求授权,不会假装已经锁定。注意:覆盖层无法拦截下拉状态栏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!granted) {
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = "去开启悬浮窗权限",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }
            )
        }
    }
}

/** 使用情况访问权限:标准模式识别前台应用的前提 */
@Composable
private fun UsageAccessSection(granted: Boolean) {
    val context = LocalContext.current

    Spacer(Modifier.height(20.dp))
    SectionHeader(title = "拦截权限")
    Spacer(Modifier.height(8.dp))
    AppCard {
        Text(
            if (granted) "已授权 · 可识别白名单应用并拦截其余应用。"
            else "未授权 · 标准模式无法识别前台应用，白名单拦截不生效。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!granted) {
            Spacer(Modifier.height(6.dp))
            Text(
                "标准模式需要「使用情况访问」权限识别前台应用,未授权时无法执行白名单拦截。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = "去系统设置授权",
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }
            )
        }
    }
}

/** 应用白名单:全局 + 按科目定制,每行进入同一个选择器 */
@Composable
private fun WhitelistSection(
    settings: AppSettings,
    subjects: List<SubjectEntity>,
    onOpenPicker: (Long) -> Unit
) {
    Spacer(Modifier.height(20.dp))
    SectionHeader(title = "应用白名单")
    Spacer(Modifier.height(8.dp))
    RowGroup {
        ListRow(
            title = "全局白名单",
            onClick = { onOpenPicker(-1L) },
            trailing = {
                Text(
                    "已选 ${settings.whitelist.size} 个应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
        RowDivider()
        subjects.forEachIndexed { index, subject ->
            if (index > 0) RowDivider()
            val custom = settings.subjectWhitelist[subject.id]
            ListRow(
                title = subject.name,
                onClick = { onOpenPicker(subject.id) },
                trailing = {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(subject.colorArgb))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        custom?.let { "定制 ${it.size} 个应用" } ?: "跟随全局",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
        RowDivider()
        Text(
            "场景化策略:可为不同科目定制专属白名单,启动该科目任务时生效;未定制科目使用全局白名单。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

/** 紧急退出密码:严格模式下唯一的逃生口 */
@Composable
private fun EmergencyPinSection(configured: Boolean, onEdit: () -> Unit) {
    Spacer(Modifier.height(20.dp))
    SectionHeader(title = "紧急退出密码")
    Spacer(Modifier.height(8.dp))
    AppCard {
        Text(
            "遇到突发情况时,输入 6 位密码可强制退出严格锁定;" +
                "此次退出会记录为非正常退出,体现在专注数据报告中。忘记密码只能等待计时结束。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = if (configured) "修改紧急退出密码" else "设置 6 位紧急退出密码",
            onClick = onEdit
        )
        if (!configured) {
            Spacer(Modifier.height(8.dp))
            Text(
                "未设置密码时严格模式无法紧急退出",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 紧急退出密码编辑:两次输入一致且为 6 位数字才能保存 */
@Composable
private fun PinEditorDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val bothValid = first.length == 6 && first == second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置紧急退出密码") },
        text = {
            Column {
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("输入 6 位数字") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("再次输入确认") },
                    singleLine = true,
                    isError = second.isNotEmpty() && first != second,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (second.isNotEmpty() && first != second) {
                    Text(
                        "两次输入不一致",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = bothValid,
                onClick = { onSave(first) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
