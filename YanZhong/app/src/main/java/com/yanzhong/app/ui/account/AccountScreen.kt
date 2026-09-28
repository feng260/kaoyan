package com.yanzhong.app.ui.account

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.ui.cloud.CloudSyncViewModel
import com.yanzhong.app.ui.legal.ConsentRow
import com.yanzhong.app.ui.legal.PolicyLinksRow
import com.yanzhong.app.ui.mine.MineViewModel
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.MintGreen
import com.yanzhong.app.ui.theme.SectionCard
import com.yanzhong.app.ui.theme.SkyBlue
import com.yanzhong.app.ui.theme.SubPageHeader
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

    // 注销账号:两步确认(第一步说明后果,第二步用户名 + 密码)
    var deleteStep by remember { mutableIntStateOf(0) }
    var deleteConfirmName by remember { mutableStateOf("") }
    var deletePassword by remember { mutableStateOf("") }

    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) {
            Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
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
                subtitle = "多设备共用同一份计划与进度",
                onBack = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Column(Modifier.padding(horizontal = 20.dp)) {
                if (!state.loggedIn) {
                    SectionCard(
                        title = "登录 / 注册",
                        subtitle = "登录后计划与每日进度存入服务端,换设备接着学",
                        icon = AppIcons.CloudUpload,
                        accent = MintGreen
                    ) {
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
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { vm.login(username, password, rememberMe) },
                                enabled = !state.busy && username.isNotBlank() && password.length >= 8,
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier.weight(1f)
                            ) { Text("登录") }
                            OutlinedButton(
                                onClick = { vm.register(username, password, email, consentChecked) },
                                enabled = !state.busy && username.length >= 3 && password.length >= 8,
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier.weight(1f)
                            ) { Text("注册") }
                        }
                        PolicyLinksRow(prefix = "", modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    SectionCard(
                        title = state.username.ifBlank { "已登录" },
                        subtitle = "数据仅自己可见 · 计划与进度已存服务端",
                        icon = AppIcons.CloudUpload,
                        accent = MintGreen
                    ) {
                        Text(
                            "同账号的设备共用同一份计划与每日进度。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    SectionCard(title = "登录的设备", subtitle = "可登出不再使用的设备", icon = AppIcons.Shield) {
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

                    SectionCard(title = "注销账号", icon = AppIcons.Delete, accent = SkyBlue) {
                        Text(
                            "服务器会删掉你的档案、计划与进度记录,删完就找不回来了。",
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
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("注销账号", color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(18.dp))
                        TextButton(
                            onClick = { vm.logout() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("退出登录") }
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionCard(title = "隐私与协议", icon = AppIcons.Info, accent = SkyBlue) {
                    PolicyLinksRow(prefix = "我们对数据的承诺写在", modifier = Modifier.fillMaxWidth())
                }
            }
        }
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
