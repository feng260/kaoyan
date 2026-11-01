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
                            label = "邮箱（选填）",
                            placeholder = "用于找回密码",
                            icon = AppIcons.TabMine,
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done,
                            onDone = vm::submit
                        )
                    }

                    if (state.registerMode) {
                        Spacer(Modifier.height(12.dp))
                        // 协议放在注册这一侧:登录的人早就同意了,不必每次再问一遍
                        com.yanzhong.app.ui.legal.ConsentRow(
                            checked = state.consentChecked,
                            onCheckedChange = { checked ->
                                vm.update { it.copy(consentChecked = checked, message = "", messageIsError = false) }
                            },
                            modifier = Modifier.fillMaxWidth()
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
            // 能力探测中/探测失败时既不能承诺发信,也不能断言服务器没配置邮件,只能用中性说法
            val intro = when (state.emailResetAvailable) {
                true -> "填写注册时绑定的邮箱，我们会发一封重置链接，30 分钟内有效。"
                false -> "填写账号提交后，需要联系管理员获取重置链接；链接 30 分钟内有效。"
                null -> "填写用户名或邮箱提交，我们会按服务器配置发送重置链接。"
            }
            AlertDialog(
                onDismissRequest = { if (!state.forgotBusy) vm.closeForgot() },
                title = {
                    Text(
                        if (state.emailResetAvailable == false) "找回密码（邮件未开通）" else "找回密码"
                    )
                },
                text = {
                    Column {
                        Text(
                            intro,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.forgotAccount,
                            onValueChange = vm::updateForgotAccount,
                            label = { Text("用户名或邮箱") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (state.forgotMessage.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(state.forgotMessage, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { vm.submitForgot() },
                        enabled = !state.forgotBusy && state.forgotAccount.isNotBlank()
                    ) { Text(if (state.forgotBusy) "提交中…" else "提交") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { vm.closeForgot() },
                        // 提交在途时关窗会丢掉结果提示,这里不允许关
                        enabled = !state.forgotBusy
                    ) { Text("关闭") }
                }
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
                "研钟",
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
