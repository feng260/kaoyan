package com.yanzhong.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.BrandGradient
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.SegmentedChips
import com.yanzhong.app.ui.theme.successContentColor

/** 未登录时的账号入口。登录和注册共用同一页面，避免用户找不到注册入口。 */
@Composable
fun LoginScreen() {
    val vm: LoginViewModel = viewModel()
    val state by vm.ui.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var showServerDialog by remember { mutableStateOf(false) }
    var serverInput by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(BrandGradient))
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp)) {
                BrandHeader(registerMode = state.registerMode)
                Spacer(Modifier.height(24.dp))

                AppCard(contentPadding = PaddingValues(20.dp)) {
                    SegmentedChips(
                        options = listOf("登录", "注册"),
                        selectedIndex = if (state.registerMode) 1 else 0,
                        onSelect = { vm.switchMode(it == 1) }
                    )
                    Spacer(Modifier.height(20.dp))

                    Text(
                        if (state.registerMode) "创建你的账号" else "欢迎回来",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.registerMode) "注册后可在多台设备同步学习进度"
                        else "登录后继续今天的备考计划",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(18.dp))

                    AuthTextField(
                        value = state.username,
                        onValueChange = { value -> vm.update { it.copy(username = value) } },
                        label = "用户名",
                        placeholder = "3-32 位，中英文、数字或下划线",
                        icon = AppIcons.TabMine,
                        imeAction = ImeAction.Next
                    )
                    Spacer(Modifier.height(12.dp))

                    var passwordVisible by remember { mutableStateOf(false) }
                    AuthTextField(
                        value = state.password,
                        onValueChange = { value -> vm.update { it.copy(password = value) } },
                        label = "密码",
                        placeholder = if (state.registerMode) "至少 8 位" else "请输入密码",
                        icon = AppIcons.Key,
                        password = !passwordVisible,
                        trailing = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) AppIcons.EyeOff else AppIcons.Eye,
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        imeAction = if (state.registerMode) ImeAction.Next else ImeAction.Done,
                        onDone = { if (!state.registerMode) vm.submit() }
                    )

                    // 找回入口贴在密码框正下方、表单之内;注册模式没有可找回的密码,故只出现在登录侧
                    if (!state.registerMode) {
                        TextButton(
                            onClick = { vm.openForgot() },
                            modifier = Modifier.align(Alignment.End)
                        ) { Text("忘记密码？") }
                    }

                    if (state.registerMode) {
                        Spacer(Modifier.height(12.dp))
                        AuthTextField(
                            value = state.confirm,
                            onValueChange = { value -> vm.update { it.copy(confirm = value) } },
                            label = "确认密码",
                            placeholder = "再次输入密码",
                            icon = AppIcons.ShieldCheck,
                            password = true,
                            imeAction = ImeAction.Next
                        )
                        Spacer(Modifier.height(12.dp))
                        AuthTextField(
                            value = state.email,
                            onValueChange = { value -> vm.update { it.copy(email = value) } },
                            label = "邮箱",
                            placeholder = "用于接收验证码与找回密码",
                            icon = AppIcons.TabMine,
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                AuthTextField(
                                    value = state.emailCode,
                                    onValueChange = { value -> vm.update { it.copy(emailCode = value) } },
                                    label = "邮箱验证码",
                                    placeholder = "6 位数字",
                                    icon = AppIcons.ShieldCheck,
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Next
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = vm::requestRegisterCode,
                                enabled = !state.codeBusy && state.codeCountdown == 0
                            ) {
                                Text(
                                    when {
                                        state.codeBusy -> "发送中…"
                                        state.codeCountdown > 0 -> "${state.codeCountdown}s"
                                        else -> "获取验证码"
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        AuthTextField(
                            value = state.inviteCode,
                            onValueChange = { value -> vm.update { it.copy(inviteCode = value) } },
                            label = "邀请码",
                            placeholder = "向管理员获取",
                            icon = AppIcons.Key,
                            imeAction = ImeAction.Done,
                            onDone = vm::submit
                        )
                    }

                    if (state.message.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        StatusMessage(state.message, state.messageIsError)
                    }

                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(
                        text = if (state.registerMode) "创建账号" else "登录并继续学习",
                        onClick = vm::submit,
                        enabled = !state.busy,
                        loading = state.busy
                    )

                    if (!state.registerMode) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "没有账号？点击上方“注册”即可创建",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    com.yanzhong.app.ui.legal.PolicyLinksRow(
                        prefix = "政策原文在",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 服务器地址是调试入口(F7):普通考生误改后无法登录且难以自行恢复,
                // 只在 Debug 构建暴露;线上排障可走长按标题唤出。
                if (com.yanzhong.app.BuildConfig.DEBUG) {
                    TextButton(
                        onClick = { showServerDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text("服务器地址（可选）")
                    }
                }
            }
        }

        if (showServerDialog) {
            AlertDialog(
                onDismissRequest = { showServerDialog = false },
                title = { Text("服务器地址") },
                text = {
                    Column {
                        Text(
                            "已内置默认服务器，正常使用无需填写。仅当需要连接本地或调试服务器时才在此修改。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "当前生效：${state.serverUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = serverInput,
                            onValueChange = { serverInput = it },
                            label = { Text("服务器地址") },
                            singleLine = true,
                            placeholder = { Text("http://192.168.x.x:3000") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                vm.resetServer()
                                showServerDialog = false
                            },
                            modifier = Modifier.align(Alignment.Start)
                        ) { Text("恢复默认") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.saveServer(serverInput)
                        showServerDialog = false
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { showServerDialog = false }) { Text("取消") }
                }
            )
        }

        if (state.forgotMode) {
            AlertDialog(
                onDismissRequest = { if (!state.forgotBusy && !state.resetBusy) vm.closeForgot() },
                title = { Text(if (state.resetSuccess) "密码已重置" else "找回密码") },
                text = {
                    if (state.resetSuccess) {
                        Text("请用新密码重新登录。")
                    } else {
                        Column {
                            Text(
                                "填写注册邮箱获取验证码，10 分钟内有效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = state.forgotAccount,
                                onValueChange = vm::updateForgotAccount,
                                label = { Text("注册邮箱") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = state.resetCode,
                                    onValueChange = { value -> vm.update { it.copy(resetCode = value) } },
                                    label = { Text("验证码") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    onClick = vm::requestResetCode,
                                    enabled = !state.forgotBusy && state.resetCountdown == 0
                                ) {
                                    Text(
                                        when {
                                            state.forgotBusy -> "发送中…"
                                            state.resetCountdown > 0 -> "${state.resetCountdown}s"
                                            else -> "获取验证码"
                                        }
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = state.resetPassword,
                                onValueChange = { value -> vm.update { it.copy(resetPassword = value) } },
                                label = { Text("新密码（至少 8 位）") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = state.resetConfirm,
                                onValueChange = { value -> vm.update { it.copy(resetConfirm = value) } },
                                label = { Text("再输一次新密码") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (state.forgotMessage.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(state.forgotMessage, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { if (state.resetSuccess) vm.closeForgot() else vm.submitReset() },
                        enabled = !state.resetBusy
                    ) {
                        Text(
                            when {
                                state.resetSuccess -> "知道了"
                                state.resetBusy -> "提交中…"
                                else -> "重置密码"
                            }
                        )
                    }
                },
                dismissButton = if (!state.resetSuccess) {
                    {
                        TextButton(
                            onClick = { vm.closeForgot() },
                            enabled = !state.forgotBusy && !state.resetBusy
                        ) { Text("取消") }
                    }
                } else null
            )
        }
    }
}

/** 品牌头:白色 Logo、名称与一句副标题,压在全屏渐变上 */
@Composable
private fun BrandHeader(registerMode: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.GraduationCap,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.size(14.dp))
        Column {
            Text(
                "日拱",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Text(
                if (registerMode) "从这里开始你的备考节奏" else "让每一段专注都有迹可循",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
    password: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction,
    onDone: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant) },
        trailingIcon = trailing,
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outline.copy(alpha = 0.7f)
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 校验/服务器提示:输入组件下方一行 12sp 文案 */
@Composable
private fun StatusMessage(message: String, isError: Boolean) {
    val contentColor = if (isError) MaterialTheme.colorScheme.error else successContentColor
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (isError) AppIcons.Info else AppIcons.CheckCircle,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor
        )
    }
}
