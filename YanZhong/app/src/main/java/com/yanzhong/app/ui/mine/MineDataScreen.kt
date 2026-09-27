package com.yanzhong.app.ui.mine

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.data.remote.DEFAULT_SERVER_URL
import com.yanzhong.app.ui.cloud.CloudSyncViewModel
import com.yanzhong.app.ui.legal.PolicyLinksRow
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.MintGreen
import com.yanzhong.app.ui.theme.SectionCard
import com.yanzhong.app.ui.theme.SubPageHeader

/**
 * 数据与账号(次级页):把技术性入口从"我的"主页收拢到这里。
 * 普通用户只需认识"云端同步"与"手动备份"两组;服务器地址属于高级项,默认折叠。
 */
@Composable
fun MineDataScreen(padding: PaddingValues, navController: NavHostController) {
    val vm: MineViewModel = viewModel()
    val cloudVm: CloudSyncViewModel = viewModel()
    val cloudUi by cloudVm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 注销账号:两步确认(第一步说明后果,第二步用户名 + 密码)
    var deleteStep by remember { mutableIntStateOf(0) }
    var deleteConfirmName by remember { mutableStateOf("") }
    var deletePassword by remember { mutableStateOf("") }

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var serverExpanded by remember { mutableStateOf(false) }
    var serverInput by remember(cloudUi.serverUrl) { mutableStateOf(cloudUi.serverUrl) }

    // 导出全量 JSON:系统文件选择器 SAF,不申请存储权限;文件 IO 全在 VM 的 IO 线程
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.exportToUri(uri) { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // 账号数据导出:导的是服务器上那份,和上面「本机库」不是一回事
    val exportAccountLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.exportAccountToUri(uri) { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // 计划包 / 恢复包批量导入:SAF 多选文件,只读不写权限
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        vm.importFromUris(uris) { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // 备份/恢复结果通过 VM 的 message 变化提示
    LaunchedEffect(cloudUi.message) {
        if (cloudUi.message.isNotBlank()) {
            Toast.makeText(context, cloudUi.message, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        SubPageHeader(
            title = "数据与账号",
            subtitle = "同步、备份与账号操作都在这里",
            onBack = { navController.popBackStack() }
        )

        Spacer(Modifier.height(8.dp))

        SectionCard(
            title = "云端同步",
            subtitle = "登录后自动同步,换设备或重装可恢复",
            icon = AppIcons.CloudUpload,
            accent = MintGreen
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        navController.navigate(Routes.CLOUD) { launchSingleTop = true }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("打开云同步", modifier = Modifier.weight(1f))
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { cloudVm.syncNow() }, enabled = !cloudUi.busy) {
                Text("立即同步")
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(
            title = "手动备份",
            subtitle = "整包上传/覆盖恢复,新设备迁移或手动兜底用",
            icon = AppIcons.CloudDownload,
            accent = MintGreen
        ) {
            Button(
                onClick = { cloudVm.backupToCloud() },
                enabled = !cloudUi.busy,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (cloudUi.busy) "备份中…" else "备份当前设备数据到云端") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showRestoreConfirm = true },
                enabled = !cloudUi.busy,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("从云端恢复到本机") }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(
            title = "文件导入导出",
            subtitle = "全量数据导出为 JSON,可导入换机恢复或批量导入计划包",
            icon = AppIcons.DatabaseBackup,
            accent = MintGreen
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { exportLauncher.launch("yanzhong-backup.json") },
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        AppIcons.Upload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("导出全量 JSON")
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        AppIcons.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("批量导入 JSON")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(
            title = "其他云盘",
            subtitle = "自建网盘同步,数据放你自己的空间",
            icon = AppIcons.CloudDownload,
            accent = MintGreen
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        navController.navigate(Routes.WEBDAV) { launchSingleTop = true }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("WebDAV 网盘同步", modifier = Modifier.weight(1f))
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(
            title = "账号",
            subtitle = "你在服务器上存了什么、怎么拿走、怎么删干净",
            icon = AppIcons.Shield,
            accent = MintGreen
        ) {
            PolicyLinksRow(
                prefix = "我们对数据的承诺写在",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "导出账号数据",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "把服务器上属于你的那份存档取回来。和上面「导出全量 JSON」不同:" +
                    "那份是本机库,这份含别的设备同步上去、本机还没拉下来的记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { exportAccountLauncher.launch("yanzhong-account.json") },
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    AppIcons.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("导出服务器存档")
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "注销账号",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "服务器会删掉你的档案、计划与同步记录,删完就找不回来了。" +
                    "本机上的学习数据不受影响,想留个底可以先导出。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    deleteConfirmName = ""
                    deletePassword = ""
                    deleteStep = 1
                },
                shape = RoundedCornerShape(999.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    AppIcons.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(6.dp))
                Text("注销账号", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(
            title = "服务器地址",
            subtitle = "仅在连接本地或调试服务器时需要修改",
            icon = AppIcons.Info,
            accent = MintGreen
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { serverExpanded = !serverExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (serverExpanded) "收起高级设置" else "已使用内置服务器",
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (serverExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (serverExpanded) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverInput,
                    onValueChange = { serverInput = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text(DEFAULT_SERVER_URL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前生效：${cloudUi.serverUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { cloudVm.saveServer(serverInput) },
                        shape = RoundedCornerShape(999.dp)
                    ) { Text("保存") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { cloudVm.resetServer() }) { Text("恢复默认") }
                }
            }
        }

        Spacer(Modifier.height(padding.calculateBottomPadding() + 32.dp))
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("从云端恢复？") },
            text = { Text("将用云端数据覆盖本机全部学习记录，且不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    cloudVm.restoreFromCloud()
                }) { Text("覆盖恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("取消") }
            }
        )
    }

    /** 注销第一步:把后果讲清楚,别用红字吓人,也别轻描淡写 */
    if (deleteStep == 1) {
        AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            title = { Text("要注销账号吗?") },
            text = {
                Text(
                    "服务器上的档案、计划、同步记录会一起删掉,删完就没有了。\n" +
                        "本机上的科目、任务和专注记录都留着,不影响你继续用。"
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteStep = 2 }) {
                    Text("继续", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteStep = 0 }) { Text("算了") }
            }
        )
    }

    /** 注销第二步:用户名 + 密码都对了服务端才认,这也是唯一的身份核验 */
    if (deleteStep == 2) {
        AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            title = { Text("最后确认一次") },
            text = {
                Column {
                    Text(
                        "输入用户名和密码,确认是你本人操作。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = deleteConfirmName,
                        onValueChange = { deleteConfirmName = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { deletePassword = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = deleteConfirmName
                        val pwd = deletePassword
                        deleteStep = 0
                        vm.deleteAccount(name, pwd) { ok, message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            // 成功时 VM 已清本地凭证,authState 翻 false 会自动退回登录页,
                            // 这里不用再手动导航;失败就留在原地,账号还是他的
                            if (!ok) deleteStep = 2
                        }
                    },
                    enabled = deleteConfirmName.isNotBlank() && deletePassword.isNotEmpty()
                ) { Text("注销账号", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteStep = 0 }) { Text("取消") }
            }
        )
    }
}
