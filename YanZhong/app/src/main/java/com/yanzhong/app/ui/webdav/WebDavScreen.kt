package com.yanzhong.app.ui.webdav

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.SectionCard
import com.yanzhong.app.ui.theme.SubPageHeader

/** WebDAV 云同步页:配置 → 测试连接 → 上传/下载全量备份 JSON(下载恢复为覆盖操作,弹确认)。 */
@Composable
fun WebDavScreen(padding: PaddingValues, navController: NavHostController) {
    val vm: WebDavViewModel = viewModel()
    val state by vm.ui.collectAsState()
    var url by remember(state.url) { mutableStateOf(state.url) }
    var username by remember(state.username) { mutableStateOf(state.username) }
    var password by remember(state.password) { mutableStateOf(state.password) }
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
                title = "WebDAV 云同步",
                subtitle = "把备份 JSON 存到你自己的 WebDAV 网盘(坚果云 / Nextcloud 等)",
                onBack = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Column(Modifier.padding(horizontal = 20.dp)) {
                SectionCard(title = "服务器配置", subtitle = "地址请填到可写入的目录结尾") {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        label = { Text("WebDAV 地址") },
                        placeholder = { Text("https://dav.jianguoyun.com/dav/备份/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username, onValueChange = { username = it },
                        label = { Text("用户名") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("密码 / 应用密码") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.saveConfig(url, username, password) },
                        enabled = url.isNotBlank(),
                        shape = RoundedCornerShape(999.dp)
                    ) { Text("保存配置") }
                }
                Spacer(Modifier.height(16.dp))

                SectionCard(title = "备份操作") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { vm.testConnection(url, username, password) },
                            enabled = !state.busy && url.isNotBlank(),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("测试连接") }
                        Button(
                            onClick = { vm.uploadBackup(url, username, password) },
                            enabled = !state.busy && url.isNotBlank(),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("上传备份") }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showRestoreConfirm = true },
                        enabled = !state.busy && url.isNotBlank(),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从 WebDAV 恢复(覆盖本机)") }

                    if (state.message.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                Text(
                    "上传与恢复使用同一份全量 JSON(科目 / 节点 / 任务 / 专注记录 / 日周月复盘)。\n" +
                        "恢复会清空本机数据后覆盖,操作前请确认已做好备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("恢复将覆盖本机数据") },
            text = { Text("从 WebDAV 下载备份并清空本地后恢复,当前本机数据将被替换。是否继续?") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    vm.restoreFromWebDav(url, username, password)
                }) { Text("覆盖并恢复", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("取消") } }
        )
    }
}