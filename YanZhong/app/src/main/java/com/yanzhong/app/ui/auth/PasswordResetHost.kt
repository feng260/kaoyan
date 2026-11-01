package com.yanzhong.app.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** 全局重置密码弹窗:不受登录门限制,未登录收到深链也能用 */
@Composable
fun PasswordResetHost() {
    val token by PasswordResetBus.token.collectAsStateWithLifecycle()
    // ViewModel 必须先于早退获取,否则弹窗树反复进出会拿到新的实例
    val vm: PasswordResetViewModel = viewModel()
    val state by vm.ui.collectAsStateWithLifecycle()
    val current = token
    // 进入和清场都交给同一个 effect:没有 token 就 reset,有就 begin(同 token 不重置输入)
    LaunchedEffect(current) {
        if (current == null) vm.reset() else vm.begin(current)
    }
    if (current == null) return
    // 等 begin(current) 生效后再渲染:上一个 token 的成功文案/密码框不会闪到新链接上
    if (state.token != current) return

    AlertDialog(
        onDismissRequest = { if (!state.busy) PasswordResetBus.consume() },
        title = { Text(if (state.success) "密码已重置" else "设置新密码") },
        text = {
            if (state.success) {
                Text("请用新密码重新登录。")
            } else {
                Column {
                    OutlinedTextField(
                        value = state.newPassword,
                        onValueChange = vm::updateNew,
                        label = { Text("新密码（至少 8 位）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.confirm,
                        onValueChange = vm::updateConfirm,
                        label = { Text("再输一次") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.error.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (state.success) PasswordResetBus.consume() else vm.submit()
                },
                enabled = !state.busy
            ) {
                Text(
                    when {
                        state.success -> "知道了"
                        state.busy -> "提交中…"
                        else -> "确认重置"
                    }
                )
            }
        },
        dismissButton = if (!state.success) {
            {
                TextButton(
                    onClick = { PasswordResetBus.consume() },
                    // 请求在途时不允许关窗:否则请求回来后悄悄清了本地会话,用户看不到任何结果
                    enabled = !state.busy
                ) { Text("取消") }
            }
        } else null
    )
}
