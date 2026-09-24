package com.yanzhong.app.ui.supermode

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.SectionCard
import com.yanzhong.app.ui.theme.SubPageHeader
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
            onBack = { navController.popBackStack() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                "开启后,启动番茄钟专注计时时自动拦截非白名单应用,计时结束自动解除。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            // 总开关
            SwitchCard(
                title = "启用学霸模式",
                subtitle = "专注期间从根源避免刷社交、看视频等分心行为",
                checked = state.settings.superModeOn,
                onChange = { vm.setOn(it) }
            )

            if (state.settings.superModeOn) {
                Spacer(Modifier.height(16.dp))

                // 严格等级
                SectionCard(title = "严格等级", icon = AppIcons.Sliders) {
                    StrictLevelCard(
                        title = "标准模式",
                        desc = "允许正常使用白名单内的应用(词典、网课、刷题等),屏蔽其余所有应用",
                        icon = AppIcons.ShieldCheck,
                        selected = !state.settings.superModeStrict,
                        onClick = { vm.setStrict(false) }
                    )
                    Spacer(Modifier.height(10.dp))
                    StrictLevelCard(
                        title = "严格模式",
                        desc = "完全锁定在研钟专注界面,无法退出、无法暂停、无法使用任何其他应用",
                        icon = AppIcons.Lock,
                        selected = state.settings.superModeStrict,
                        onClick = { vm.setStrict(true) }
                    )
                }

                Spacer(Modifier.height(16.dp))

                // 悬浮窗权限:全屏覆盖层拦截(防逃逸增强)
                SectionCard(title = "悬浮窗拦截", icon = AppIcons.Smartphone) {
                    val context = LocalContext.current
                    Text(
                        if (state.overlayAccess)
                            "「显示在其他应用上层」已授予。专注期间对非白名单应用渲染全屏锁定层,直接拦截触摸,消除按 Home 键后的最大逃逸口。"
                        else
                            "开启「显示在其他应用上层」后,专注期间对非白名单应用渲染全屏锁定层直接拦截触摸;" +
                                "未授权时回退为 1 秒轮询拉回方案(体验较差)。注意:覆盖层无法拦截下拉状态栏。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!state.overlayAccess) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }
                            },
                            shape = RoundedCornerShape(999.dp)
                        ) { Text("去开启悬浮窗权限") }
                    }
                }

                if (!state.settings.superModeStrict) {
                    Spacer(Modifier.height(16.dp))

                    // 权限引导:标准模式识别前台应用
                    SectionCard(title = "拦截权限", icon = AppIcons.ShieldCheck) {
                        val context = LocalContext.current
                        Text(
                            if (state.usageAccess) "「使用情况访问」已授予,可识别白名单应用并拦截其余应用。"
                            else "标准模式需要「使用情况访问」权限识别前台应用,未授权时无法执行白名单拦截。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!state.usageAccess) {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                    }
                                },
                                shape = RoundedCornerShape(999.dp)
                            ) { Text("去系统设置授权") }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // 白名单管理
                    SectionCard(title = "应用白名单", icon = AppIcons.AppWindow) {
                        EntryRow(
                            title = "全局白名单",
                            value = "已选 ${state.settings.whitelist.size} 个应用",
                            onClick = {
                                navController.navigate(Routes.appPicker(-1L))
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "场景化策略:可为不同科目定制专属白名单,启动该科目任务时生效;未定制科目使用全局白名单。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        state.subjects.forEach { subject ->
                            val custom = state.settings.subjectWhitelist[subject.id]
                            EntryRow(
                                title = subject.name,
                                value = custom?.let { "定制 ${it.size} 个应用" } ?: "跟随全局",
                                dotColor = Color(subject.colorArgb),
                                onClick = {
                                    navController.navigate(Routes.appPicker(subject.id))
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                } else {
                    Spacer(Modifier.height(16.dp))

                    // 紧急退出密码(严格模式逃生口)
                    SectionCard(title = "紧急退出密码", icon = AppIcons.Key) {
                        Text(
                            "遇到突发情况时,输入 6 位密码可强制退出严格锁定;" +
                                "此次退出会记录为非正常退出,体现在专注数据报告中。忘记密码只能等待计时结束。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { showPinEditor = true },
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                if (state.settings.emergencyPinHash.isEmpty()) "设置 6 位紧急退出密码"
                                else "修改紧急退出密码"
                            )
                        }
                        if (state.settings.emergencyPinHash.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "未设置密码时严格模式无法紧急退出",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
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
    }
}

@Composable
private fun SwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (checked) 0.5f else 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                AppIcons.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
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
private fun StrictLevelCard(
    title: String,
    desc: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    title: String,
    value: String,
    dotColor: Color? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dotColor != null) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
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
