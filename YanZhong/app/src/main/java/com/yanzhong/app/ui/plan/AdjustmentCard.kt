package com.yanzhong.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.remote.AdjustmentChangeDto
import com.yanzhong.app.data.remote.AdjustmentOptionDto
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.PillTone
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.SecondaryButton
import com.yanzhong.app.ui.theme.StatusPill
import com.yanzhong.app.ui.theme.TextAction

/** 单条变动的一行描述,如「挪动 · 数学 · 09-30 → 10-01」 */
internal fun adjustmentChangeLine(change: AdjustmentChangeDto): String {
    val subject = change.subject.ifBlank { "任务" }
    return when (change.kind) {
        "moved" -> {
            val from = change.from?.planDate.orEmpty().takeLast(5)
            val to = change.to?.planDate.orEmpty().takeLast(5)
            "挪动 · $subject · $from → $to"
        }
        "added" -> "新增 · $subject · ${change.to?.title.orEmpty()}"
        "removed" -> "删除 · $subject · ${change.from?.title.orEmpty()}"
        else -> "调整 · $subject · ${change.from?.title.orEmpty()}"
    }
}

/**
 * 行程调整的变动清单卡:确认前零副作用,确认后才生效;
 * 多方案时上方并列可选项,选定方案的影响与变动明细在下方同步刷新。
 * 「重说一次」只收起卡片(服务端 draft 会被下一次 adjust 原地覆盖)。
 */
@Composable
internal fun AdjustmentCard(
    summary: String,
    tier: String,
    changes: List<AdjustmentChangeDto>,
    confirming: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    options: List<AdjustmentOptionDto>? = null,
    selectedOption: String? = null,
    selecting: Boolean = false,
    applied: Boolean = false,
    onSelectOption: (String) -> Unit = {},
    onUndo: (() -> Unit)? = null,
    onPreview: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (applied) "调整已生效" else "调整清单",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            StatusPill(if (applied) "已生效" else "待确认", if (applied) PillTone.ACCENT else PillTone.NEUTRAL)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!options.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { index, option ->
                    AdjustmentOptionCard(
                        option = option,
                        index = index,
                        selected = option.id == selectedOption,
                        enabled = !confirming && !selecting,
                        onClick = { onSelectOption(option.id) }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        changes.take(8).forEach { change ->
            Text(adjustmentChangeLine(change), style = MaterialTheme.typography.bodySmall)
        }
        if (changes.size > 8) {
            Text(
                "…共 ${changes.size} 条变动，确认后可在计划页查看全部",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onPreview != null) {
            TextAction(
                text = "查看整页预览",
                onClick = onPreview,
                modifier = Modifier.align(Alignment.Start)
            )
        }
        Spacer(Modifier.height(4.dp))
        if (applied) {
            if (onUndo != null) {
                TextAction(text = "撤销这次调整", onClick = onUndo, danger = true)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(
                    text = "重说一次",
                    onClick = onDismiss,
                    enabled = !confirming && !selecting,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = if (options.isNullOrEmpty()) "确认调整" else "按这个方案调整",
                    onClick = onConfirm,
                    enabled = !confirming && !selecting && changes.isNotEmpty(),
                    loading = confirming,
                    modifier = Modifier.weight(1f)
                )
            }
            if (selecting) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "正在换方案…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
