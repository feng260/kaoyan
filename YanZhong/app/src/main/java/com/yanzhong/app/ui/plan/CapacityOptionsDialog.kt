package com.yanzhong.app.ui.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.ui.theme.SecondaryButton

/**
 * 容量不足时的三选一:调整作息 / 降低目标 / 生成尽力版。
 * 把「差多少」摆在前面,再让考生选怎么补,避免只给一句「时间不够」。
 */
@Composable
internal fun CapacityOptionsDialog(
    issue: PlanCapacityIssue,
    onChoose: (CapacityChoice) -> Unit,
    onDismiss: () -> Unit
) {
    var bestEffortConfirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bestEffortConfirm) "生成尽力版？" else "时间不够，怎么排？") },
        text = {
            if (bestEffortConfirm) {
                Column {
                    Text(
                        "尽力版不会硬塞任务：排不下的科目会在草案预算表里标出缺口，" +
                            "计划仍然可以确认，之后随时用对话调整。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Column {
                    Text(
                        "考前可用 ${formatMinutesCn(issue.availableMinutes)}，" +
                            "已确认的任务需要 ${formatMinutesCn(issue.requiredMinutes)}。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (issue.deficits.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        issue.deficits.forEach { deficit ->
                            val milestone = if (deficit.milestone.isNotBlank()) "（${deficit.milestone}）" else ""
                            Text(
                                "· ${deficit.subject.ifBlank { "任务" }}$milestone 缺 ${formatMinutesCn(deficit.missingMinutes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton(
                        text = "调整作息，多挤出时间",
                        onClick = { onChoose(CapacityChoice.ADJUST_SCHEDULE) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(
                        text = "降低目标或剩余量",
                        onClick = { onChoose(CapacityChoice.LOWER_TARGET) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(
                        text = "按现有时间生成尽力版",
                        onClick = { bestEffortConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (bestEffortConfirm) {
                TextButton(onClick = { onChoose(CapacityChoice.BEST_EFFORT) }) {
                    Text("仍然生成")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("先不生成") }
            }
        },
        dismissButton = if (bestEffortConfirm) {
            { TextButton(onClick = { bestEffortConfirm = false }) { Text("返回") } }
        } else null
    )
}
