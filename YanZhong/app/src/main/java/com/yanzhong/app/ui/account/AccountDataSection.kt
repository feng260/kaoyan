package com.yanzhong.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import com.yanzhong.app.ui.cloud.CloudDataRules
import com.yanzhong.app.ui.cloud.CloudDataUiState
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.ListRow
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.RowDivider
import com.yanzhong.app.ui.theme.RowGroup
import com.yanzhong.app.ui.theme.SecondaryButton
import com.yanzhong.app.ui.theme.SectionHeader

/**
 * 账号页「云端数据」分组:导出 / 下载备份 / 下载快照 / 从备份恢复。
 *
 * 恢复走五步:选文件 → 预览摘要 → 输入确认词 → 执行(快照/停同步/覆盖/续同步) → 结果提示。
 * 这一步会覆盖用户数据,所以文案必须把「覆盖什么、不覆盖什么、能不能撤销」说清楚。
 */
@Composable
fun AccountDataSection(
    state: CloudDataUiState,
    onExportAccount: () -> Unit,
    onDownloadBackup: () -> Unit,
    onPickRestore: () -> Unit,
    onDownloadSnapshot: () -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }
    var confirmText by remember { mutableStateOf("") }

    SectionHeader(title = "云端数据", meta = "导出 / 下载 / 恢复")
    RowGroup {
        ListRow(
            title = "导出账号数据",
            subtitle = "账号、档案、计划与学习记录，JSON 文件",
            onClick = onExportAccount,
            enabled = !state.busy
        )
        RowDivider()
        ListRow(
            title = "下载云端备份",
            subtitle = "只含学习数据与设置，不含档案和 AI 计划",
            onClick = onDownloadBackup,
            enabled = !state.busy
        )
        RowDivider()
        ListRow(
            title = "从备份恢复",
            subtitle = "覆盖本机与服务端的学习数据和设置",
            onClick = onPickRestore,
            enabled = !state.busy
        )
        if (state.snapshotAvailable) {
            RowDivider()
            ListRow(
                title = "下载恢复前快照",
                subtitle = "上次恢复之前自动保存的一份备份",
                onClick = onDownloadSnapshot,
                enabled = !state.busy
            )
        }
    }

    if (state.busy) {
        Spacer(Modifier.height(10.dp))
        Text(
            "正在处理…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    state.pending?.let { pending ->
        Spacer(Modifier.height(10.dp))
        AppCard {
            Text("准备恢复 ${state.pendingName}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                CloudDataRules.summary(pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "将覆盖服务端与本机的学习数据和设置；备考档案与 AI 计划不会变。恢复前会自动保存一份快照。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    text = "取消",
                    onClick = {
                        showConfirm = false
                        confirmText = ""
                        onCancelRestore()
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = "继续恢复",
                    onClick = { showConfirm = true },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showConfirm && state.pending != null) {
        AlertDialog(
            onDismissRequest = {
                showConfirm = false
                confirmText = ""
            },
            title = { Text("最后确认一次") },
            text = {
                Column {
                    Text(
                        "服务端与本机现有的学习数据、设置会被这份备份覆盖，无法撤销。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "输入「${CloudDataRules.RESTORE_CONFIRM_WORD}」继续。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        label = { Text("确认词") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (CloudDataRules.canConfirm(confirmText)) {
                            showConfirm = false
                            confirmText = ""
                            onConfirmRestore()
                        }
                    },
                    enabled = CloudDataRules.canConfirm(confirmText) && !state.busy,
                    // 用 buttonColors 而不是在 Text 上硬写红字,禁用态才看得出变灰
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        confirmText = ""
                    }
                ) { Text("取消") }
            }
        )
    }
}
