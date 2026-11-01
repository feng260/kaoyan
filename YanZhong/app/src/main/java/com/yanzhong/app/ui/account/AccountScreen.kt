package com.yanzhong.app.ui.account

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.data.remote.DeviceFullDto
import com.yanzhong.app.ui.cloud.CloudSyncViewModel
import com.yanzhong.app.ui.legal.ConsentRow
import com.yanzhong.app.ui.legal.PolicyLinksRow
import com.yanzhong.app.ui.mine.MineViewModel
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
import com.yanzhong.app.util.TimeUtils

/**
 * 账号子页:一个账号多设备登录,共用同一份服务端计划。
 *
 * 只做三件事 —— 登录注册、查看/登出其他设备、退出与注销。
 * 服务器地址、增量同步、备份恢复、专注接力等实现细节一律不在此出现:
 * 同步是后台常驻行为,用户不需要理解,也不该被要求操作。
 */
@Composable
fun AccountScreen(padding: PaddingValues, navController: NavHostController) {
    val vm: CloudSyncViewModel = viewModel()
    val mineVm: MineViewModel = viewModel()
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(false) }
    var consentChecked by remember { mutableStateOf(false) }

    // 退出与登出都不可撤销,先确认再执行
    var logoutConfirm by remember { mutableStateOf(false) }
    var revokeCandidate by remember { mutableStateOf<DeviceFullDto?>(null) }

    // 注销账号:两步确认(第一步说明后果,第二步用户名 + 密码)
    var deleteStep by remember { mutableIntStateOf(0) }
    var deleteConfirmName by remember { mutableStateOf("") }
    var deletePassword by remember { mutableStateOf("") }

    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) {
            Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
            vm.clearMessage()
        }
    }

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
                title = "账号",
                meta = "多设备共用同一份计划与进度",
                onBack = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))
                if (!state.loggedIn) {
                    SectionHeader(title = "登录 / 注册")
                    Spacer(Modifier.height(8.dp))
                    AppCard {
                        Text(
                            "登录后计划与每日进度存入服务端,换设备接着学",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
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
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Password
                            ),
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
                        ConsentRow(
                            checked = consentChecked,
                            onCheckedChange = { consentChecked = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PrimaryButton(
                                text = "登录",
                                onClick = { vm.login(username, password, rememberMe) },
                                enabled = !state.busy && username.isNotBlank() && password.length >= 8,
                                modifier = Modifier.weight(1f)
                            )
                            SecondaryButton(
                                text = "注册",
                                onClick = { vm.register(username, password, email, consentChecked) },
                                enabled = !state.busy && username.length >= 3 && password.length >= 8,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        PolicyLinksRow(prefix = "", modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    AppIcons.GraduationCap,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    state.username.ifBlank { "已登录" },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "数据仅自己可见 · 计划与进度已存服务端",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusPill(text = "已登录", tone = PillTone.SOLID)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "同账号的设备共用同一份计划与每日进度。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(20.dp))

                    SectionHeader(title = "登录的设备", meta = "可登出不再使用的设备")
                    Spacer(Modifier.height(8.dp))
                    if (state.devices.isNotEmpty()) {
                        RowGroup {
                            state.devices.forEachIndexed { index, device ->
                                if (index > 0) RowDivider()
                                ListRow(
                                    title = device.name,
                                    subtitle = "${device.platform} · " +
                                        TimeUtils.formatYmdHm(device.lastActiveAt) +
                                        (if (device.online) " · 在线" else ""),
                                    trailing = {
                                        TextAction(
                                            text = "登出",
                                            onClick = { revokeCandidate = device },
                                            danger = true
                                        )
                                    }
                                )
                            }
                        }
                    }
                    TextAction(
                        text = "刷新设备列表",
                        onClick = { vm.refreshDevices() },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(12.dp))

                    SecondaryButton(
                        text = "退出登录",
                        onClick = { logoutConfirm = true },
                        danger = true
                    )
                    Spacer(Modifier.height(4.dp))
                    TextAction(
                        text = "注销账号",
                        onClick = {
                            deleteConfirmName = ""
                            deletePassword = ""
                            deleteStep = 1
                        },
                        danger = true,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                Spacer(Modifier.height(20.dp))
                SectionHeader(title = "隐私与协议")
                Spacer(Modifier.height(8.dp))
                AppCard {
                    PolicyLinksRow(prefix = "我们对数据的承诺写在", modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (logoutConfirm) {
        AlertDialog(
            onDismissRequest = { logoutConfirm = false },
            title = { Text("要退出登录吗?") },
            text = {
                Text(
                    "退出后这台设备停止同步,本机学习记录不会删除;" +
                        "计划与进度仍保存在服务端,重新登录即可继续。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        logoutConfirm = false
                        vm.logout()
                    }
                ) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { logoutConfirm = false }) { Text("取消") } }
        )
    }

    revokeCandidate?.let { device ->
        AlertDialog(
            onDismissRequest = { revokeCandidate = null },
            title = { Text("登出这台设备?") },
            text = {
                Text(
                    "「${device.name}」会立即停止同步,需要重新登录才能恢复;" +
                        "计划与进度仍保存在服务端。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        revokeCandidate = null
                        vm.revokeDevice(device.id)
                    }
                ) { Text("登出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { revokeCandidate = null }) { Text("取消") } }
        )
    }

    /** 注销第一步:把后果讲清楚,别用红字吓人,也别轻描淡写 */
    if (deleteStep == 1) {
        AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            title = { Text("要注销账号吗?") },
            text = {
                Text(
                    "服务器上的档案、计划、进度记录会一起删掉,删完就没有了。\n" +
                        "本机上的学习记录都留着,不影响你继续用。"
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteStep = 2 }) {
                    Text("继续", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteStep = 0 }) { Text("算了") } }
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
                        mineVm.deleteAccount(name, pwd) { ok, message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            if (!ok) deleteStep = 2
                        }
                    },
                    enabled = deleteConfirmName.isNotBlank() && deletePassword.isNotEmpty()
                ) { Text("注销账号", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteStep = 0 }) { Text("取消") } }
        )
    }
}
