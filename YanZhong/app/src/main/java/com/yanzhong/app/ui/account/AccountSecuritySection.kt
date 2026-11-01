package com.yanzhong.app.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yanzhong.app.ui.auth.PasswordRules
import com.yanzhong.app.ui.theme.ListRow
import com.yanzhong.app.ui.theme.RowDivider
import com.yanzhong.app.ui.theme.RowGroup
import com.yanzhong.app.ui.theme.SectionHeader

@Composable
fun AccountSecuritySection(
    changing: Boolean,
    error: String,
    onClearError: () -> Unit,
    onChangePassword: (String, String, String) -> Unit,
    onRevokeAll: () -> Unit
) {
    var showChange by remember { mutableStateOf(false) }
    var showRevokeAll by remember { mutableStateOf(false) }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    SectionHeader(title = "账号安全", meta = "密码与登录设备")
    RowGroup {
        ListRow(
            title = "修改密码",
            subtitle = "修改后其他设备需要重新登录",
            onClick = {
                oldPassword = ""
                newPassword = ""
                confirmPassword = ""
                onClearError()
                showChange = true
            }
        )
        RowDivider()
        ListRow(
            title = "登出全部设备",
            subtitle = "本机也会退出，需要重新登录",
            onClick = { showRevokeAll = true }
        )
    }

    if (showChange) {
        AlertDialog(
            onDismissRequest = {
                showChange = false
                onClearError()
            },
            title = { Text("修改密码") },
            text = {
                Column {
                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it },
                        label = { Text("当前密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("新密码（至少 8 位）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("再输一次新密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onChangePassword(oldPassword, newPassword, confirmPassword) },
                    enabled = !changing &&
                        PasswordRules.validateChange(oldPassword, newPassword, confirmPassword) == null
                ) {
                    Text(if (changing) "提交中…" else "修改密码")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showChange = false
                        onClearError()
                    }
                ) { Text("取消") }
            }
        )
    }

    if (showRevokeAll) {
        AlertDialog(
            onDismissRequest = { showRevokeAll = false },
            title = { Text("登出全部设备？") },
            text = {
                Text("所有设备都会立即停止同步，包括这台设备；需要重新登录才能恢复。学习数据仍保存在服务端。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevokeAll = false
                        onRevokeAll()
                    }
                ) { Text("全部登出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeAll = false }) { Text("取消") }
            }
        )
    }
}
