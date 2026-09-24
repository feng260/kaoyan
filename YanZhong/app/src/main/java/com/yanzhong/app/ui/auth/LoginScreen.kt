package com.yanzhong.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.SuccessGreen

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
            .background(
                Brush.verticalGradient(
                    listOf(colors.primaryContainer.copy(alpha = 0.72f), colors.background, colors.background)
                )
            )
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp)) {
                BrandHeader(registerMode = state.registerMode)
                Spacer(Modifier.height(28.dp))

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = colors.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(20.dp)) {
                        AuthModeSwitch(
                            registerMode = state.registerMode,
                            onLogin = { vm.switchMode(false) },
                            onRegister = { vm.switchMode(true) }
                        )
                        Spacer(Modifier.height(24.dp))

                        Text(
                            if (state.registerMode) "创建你的账号" else "欢迎回来",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            if (state.registerMode) "注册后可在多台设备同步学习进度"
                            else "登录后继续今天的备考计划",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.height(22.dp))

                        AuthTextField(
                            value = state.username,
                            onValueChange = { value -> vm.update { it.copy(username = value) } },
                            label = "用户名",
                            placeholder = "3-32 位，中英文、数字或下划线",
                            icon = AppIcons.TabMine,
                            imeAction = ImeAction.Next
                        )
                        Spacer(Modifier.height(14.dp))

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

                        if (state.registerMode) {
                            Spacer(Modifier.height(14.dp))
                            AuthTextField(
                                value = state.confirm,
                                onValueChange = { value -> vm.update { it.copy(confirm = value) } },
                                label = "确认密码",
                                placeholder = "再次输入密码",
                                icon = AppIcons.ShieldCheck,
                                password = true,
                                imeAction = ImeAction.Next
                            )
                            Spacer(Modifier.height(14.dp))
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

                        if (state.message.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            StatusMessage(state.message, state.messageIsError)
                        }

                        Spacer(Modifier.height(22.dp))
                        Button(
                            onClick = vm::submit,
                            enabled = !state.busy,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            if (state.busy) {
                                CircularProgressIndicator(
                                    color = colors.onPrimary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(21.dp)
                                )
                            } else {
                                Text(
                                    if (state.registerMode) "创建账号" else "登录并继续学习",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    if (state.registerMode) "注册即表示你同意仅将账号用于学习数据同步"
                    else "没有账号？点击上方“注册”即可创建",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                )
                TextButton(
                    onClick = { showServerDialog = true },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Text("服务器地址（可选）")
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
    }
}

@Composable
private fun BrandHeader(registerMode: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.GraduationCap,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.size(14.dp))
        Column {
            Text("研钟", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                if (registerMode) "从这里开始你的备考节奏" else "让每一段专注都有迹可循",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AuthModeSwitch(registerMode: Boolean, onLogin: () -> Unit, onRegister: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ModeButton("登录", !registerMode, onLogin, Modifier.weight(1f))
            ModeButton("注册", registerMode, onRegister, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModeButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) colors.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp,
        modifier = modifier
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 10.dp),
            textAlign = TextAlign.Center
        )
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

@Composable
private fun StatusMessage(message: String, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    val contentColor = if (isError) colors.error else SuccessGreen
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isError) colors.errorContainer else SuccessGreen.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isError) AppIcons.Info else AppIcons.CheckCircle,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = contentColor)
        }
    }
}
