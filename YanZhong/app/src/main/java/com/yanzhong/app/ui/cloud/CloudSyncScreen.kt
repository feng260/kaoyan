package com.yanzhong.app.ui.cloud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.ui.theme.SectionCard
import com.yanzhong.app.ui.theme.SubPageHeader
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.util.TimeUtils

/**
 * 云同步页:服务器配置 → 登录/注册 → 设备管理 + 全量备份 + 状态 WS 演示。
 * v1 手动触发;登录后其他设备(平板)恢复即可完成数据迁移。
 */
@Composable
fun CloudSyncScreen(padding: PaddingValues, navController: NavHostController) {
    val vm: CloudSyncViewModel = viewModel()
    val state by vm.ui.collectAsState()
    val syncState by vm.syncState.collectAsState()
    val wsConnected by vm.wsConnected.collectAsState()
    val peers by vm.peers.collectAsState()
    var serverInput by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(bottom = padding.calculateBottomPadding() + 24.dp)
        ) {
            SubPageHeader(
                title = "云同步",
                subtitle = "多设备数据备份与专注状态实时同步",
                onBack = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Column(Modifier.padding(horizontal = 20.dp)) {

                // 服务器
                SectionCard(title = "服务器", subtitle = "已内置默认服务器，正常使用无需修改；仅连接本地或调试服务器时才需要填写") {
                    OutlinedTextField(
                        value = serverInput,
                        onValueChange = { serverInput = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text(com.yanzhong.app.data.remote.DEFAULT_SERVER_URL) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "当前生效：${state.serverUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { vm.saveServer(serverInput) }, shape = RoundedCornerShape(999.dp)) {
                            Text("保存服务器地址")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { vm.resetServer() }) { Text("恢复默认") }
                    }
                }
                Spacer(Modifier.height(16.dp))

                if (!state.loggedIn) {
                    // 登录 / 注册
                    SectionCard(title = "账号") {
                        OutlinedTextField(
                            value = username, onValueChange = { username = it },
                            label = { Text("用户名") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            label = { Text("密码(至少 8 位)") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = email, onValueChange = { email = it },
                            label = { Text("邮箱(找回密码用,可选)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
                            Text("记住我(30 天免登录)", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { vm.login(username, password, rememberMe) },
                                enabled = !state.busy && username.isNotBlank() && password.length >= 8,
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier.weight(1f)
                            ) { Text("登录") }
                            OutlinedButton(
                                onClick = { vm.register(username, password, email) },
                                enabled = !state.busy && username.length >= 3 && password.length >= 8,
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier.weight(1f)
                            ) { Text("注册") }
                        }
                    }
                } else {
                    // 已登录:设备管理 + 增量同步 + 备份 + 状态 WS
                    SectionCard(title = "已登录 · ${state.username}", subtitle = "数据仅自己可见,多设备状态实时同步") {
                        state.devices.forEach { device ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "${device.platform} · ${TimeUtils.formatYmdHm(device.lastActiveAt)}" +
                                                if (device.online) " · 在线" else "",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(
                                        onClick = { vm.revokeDevice(device.id) },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) { Text("登出") }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        TextButton(onClick = { vm.refreshDevices() }) { Text("刷新设备列表") }
                    }
                    Spacer(Modifier.height(16.dp))

                    SectionCard(
                        title = "增量数据同步",
                        subtitle = "登录后自动运行:本机改动上云,他端改动落本机(15 分钟一轮,改动即时触发)"
                    ) {
                        val syncLine = when {
                            syncState.running -> "同步中…"
                            syncState.lastError != null -> "上次失败:${syncState.lastError}"
                            syncState.lastSyncAt > 0 -> "上次同步 ${TimeUtils.formatYmdHm(syncState.lastSyncAt)} · ↑${syncState.pushed} ↓${syncState.pulled}"
                            else -> "等待首次同步"
                        }
                        Text(
                            syncLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (syncState.lastError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { vm.syncNow() },
                            enabled = !syncState.running,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (syncState.running) "同步中…" else "立即同步") }
                    }
                    Spacer(Modifier.height(16.dp))

                    SectionCard(title = "数据备份", subtitle = "整包上传/覆盖恢复,新设备迁移或手动兜底用") {
                        Button(
                            onClick = { vm.backupToCloud() },
                            enabled = !state.busy,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (state.busy) "备份中…" else "备份当前设备数据到云端") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showRestoreConfirm = true },
                            enabled = !state.busy,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("从云端恢复到本机") }
                    }
                    Spacer(Modifier.height(16.dp))

                    SectionCard(title = "专注状态实时同步", subtitle = "同账号设备在线互见,他端专注可跟随") {
                        Text(
                            if (wsConnected) "已连接" else "未连接",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (wsConnected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (peers.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            peers.forEach { peer ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                        Text(peer.deviceName, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            if (peer.isFocusing) {
                                                val phaseText = if (peer.phase == "focus") "专注中" else "休息中"
                                                "${phaseText}${if (peer.paused) " · 暂停" else ""} · ${peer.taskTitle.ifBlank { "未选任务" }}"
                                            } else {
                                                "空闲"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "其他设备上线后会显示在这里",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        onClick = { vm.logout() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("退出登录") }
                }

                if (state.message.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (showRestoreConfirm) {
            AlertDialog(
                onDismissRequest = { showRestoreConfirm = false },
                title = { Text("从云端恢复？") },
                text = { Text("将用云端数据覆盖本机当前全部数据（科目/倒计时/任务/番茄记录/复盘），本机未备份数据会被清除。") },
                confirmButton = {
                    TextButton(onClick = {
                        showRestoreConfirm = false
                        vm.restoreFromCloud()
                    }) { Text("覆盖恢复") }
                },
                dismissButton = {
                    TextButton(onClick = { showRestoreConfirm = false }) { Text("取消") }
                }
            )
        }
    }
}
