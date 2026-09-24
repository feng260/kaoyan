package com.yanzhong.app.ui.supermode

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.SubPageHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一个可加入白名单的安装应用 */
data class InstalledApp(
    val pkg: String,
    val label: String,
    val icon: Drawable?
)

data class AppPickerUiState(
    val loading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val selected: Set<String> = emptySet(),
    /** 进入页面时的初始勾选:与 selected 比对判断是否有未保存修改 */
    val initialSelected: Set<String> = emptySet()
) {
    val isDirty: Boolean get() = selected != initialSelected
}

class AppPickerViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsRepo = (app as YanZhongApp).settingsRepo
    private val _ui = MutableStateFlow(AppPickerUiState())
    val ui: StateFlow<AppPickerUiState> = _ui.asStateFlow()

    private var boundSubjectId = Long.MIN_VALUE
    private var query = ""

    /** subjectId = -1 表示全局白名单;否则为科目定制(场景化策略) */
    fun bind(subjectId: Long) {
        if (boundSubjectId == subjectId) return
        boundSubjectId = subjectId
        viewModelScope.launch {
            val apps = loadInstalledApps()
            val settings = settingsRepo.current()
            val selected = if (subjectId == -1L) settings.whitelist
            else settings.subjectWhitelist[subjectId] ?: emptySet()
            _ui.update {
                it.copy(loading = false, apps = apps, selected = selected, initialSelected = selected)
            }
        }
    }

    fun setQuery(q: String) {
        query = q
    }

    fun filtered(apps: List<InstalledApp>): List<InstalledApp> {
        if (query.isBlank()) return apps
        val q = query.trim().lowercase()
        return apps.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
    }

    fun toggle(pkg: String) = _ui.update { s ->
        s.copy(selected = if (pkg in s.selected) s.selected - pkg else s.selected + pkg)
    }

    fun selectAll(pkgs: Collection<String>) = _ui.update { it.copy(selected = pkgs.toSet()) }

    fun clearAll() = _ui.update { it.copy(selected = emptySet()) }

    fun save(onDone: () -> Unit) {
        val subjectId = boundSubjectId
        val pkgs = _ui.value.selected
        viewModelScope.launch {
            if (subjectId == -1L) settingsRepo.setWhitelist(pkgs)
            else settingsRepo.setSubjectWhitelist(subjectId, pkgs)
            onDone()
        }
    }

    private suspend fun loadInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        runCatching {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcherIntent, 0)
                .asSequence()
                .mapNotNull { info ->
                    val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                    val pkg = info.activityInfo.packageName
                    if (pkg == context.packageName) return@mapNotNull null
                    InstalledApp(
                        pkg = pkg,
                        label = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(pkg),
                        icon = runCatching { appInfo.loadIcon(pm) }.getOrNull()
                    )
                }
                .distinctBy { it.pkg }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                .toList()
        }.getOrDefault(emptyList())
    }
}

/**
 * 应用白名单选择器(参考番茄ToDo 学霸模式白名单):
 * 列出全部可启动应用,支持搜索与批量勾选;subjectId=-1 编辑全局白名单,否则编辑科目定制。
 */
@Composable
fun AppPickerScreen(
    padding: PaddingValues,
    navController: NavHostController,
    subjectId: Long
) {
    val vm: AppPickerViewModel = viewModel(key = "app_picker")
    val state by vm.ui.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    /** 有未保存勾选时返回/清空需确认,防止误触丢失 */
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(subjectId) { vm.bind(subjectId) }

    // 系统返回同样保护未保存修改
    BackHandler(enabled = state.isDirty) { showDiscardConfirm = true }

    // 平板:限宽 720dp 居中
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(bottom = padding.calculateBottomPadding())
    ) {
        SubPageHeader(
            title = "应用白名单",
            subtitle = if (subjectId == -1L) "专注期间可正常使用的应用"
            else "该科目专注时使用的应用(场景化策略)",
            onBack = {
                if (state.isDirty) showDiscardConfirm = true else navController.popBackStack()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            trailing = {
                TextButton(
                    enabled = !state.loading,
                    onClick = { vm.save { navController.popBackStack() } }
                ) { Text("保存") }
            }
        )

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                vm.setQuery(it)
            },
            label = { Text("搜索应用") },
            leadingIcon = {
                Icon(
                    AppIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "已选 ${state.selected.size} 个应用",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.selectAll(vm.filtered(state.apps).map { it.pkg }) }) {
                Text("全选")
            }
            TextButton(
                onClick = {
                    // 已有勾选的清空属于大改动,确认后再执行
                    if (state.selected.isEmpty()) vm.clearAll() else showClearConfirm = true
                }
            ) { Text("清空") }
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(vm.filtered(state.apps), key = { it.pkg }) { app ->
                    AppRow(
                        app = app,
                        checked = app.pkg in state.selected,
                        onToggle = { vm.toggle(app.pkg) }
                    )
                }
            }
        }
    }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("放弃未保存的修改?") },
            text = { Text("你对白名单的勾选修改尚未保存,离开后将丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    navController.popBackStack()
                }) { Text("放弃修改", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("继续编辑") }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空已选应用?") },
            text = { Text("将取消全部 ${state.selected.size} 个应用的勾选,仍需点「保存」才会生效。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    showClearConfirm = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bitmap = remember(app.pkg) {
                runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    app.pkg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}
