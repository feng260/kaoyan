package com.yanzhong.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
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
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.SecondaryButton
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
 * 行程调整的变动清单卡:确认前零副作用,「确认调整」才生效;
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
    onPreview: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier) {
        Text("调整清单", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(
                text = "重说一次",
                onClick = onDismiss,
                enabled = !confirming,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = "确认调整",
                onClick = onConfirm,
                enabled = !confirming && changes.isNotEmpty(),
                loading = confirming,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
