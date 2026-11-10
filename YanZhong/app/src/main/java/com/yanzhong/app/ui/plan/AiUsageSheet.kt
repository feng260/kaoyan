package com.yanzhong.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.remote.AiUsageFeatureDto
import com.yanzhong.app.data.remote.UsageSummaryDto
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppSheet

/** AI 用量详情:今日/本月汇总 + 本月按功能分项 */
@Composable
fun AiUsageSheet(
    today: UsageSummaryDto?,
    month: UsageSummaryDto?,
    byFeature: List<AiUsageFeatureDto>,
    onDismiss: () -> Unit,
) {
    AppSheet(title = "AI 用量", onDismiss = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UsageCard("今日", today, Modifier.weight(1f))
            UsageCard("本月", month, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        if (byFeature.isEmpty()) {
            Text("本月还没有 AI 调用记录", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("本月按功能", style = MaterialTheme.typography.labelLarge)
            byFeature.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(AiUsageFormat.featureLabel(item.feature),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Text("${AiUsageFormat.tokens(item.totalTokens)} tokens · ${item.calls} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("只统计 token 数量,不记录对话内容", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UsageCard(title: String, summary: UsageSummaryDto?, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${AiUsageFormat.tokens(summary?.totalTokens ?: 0)} tokens",
            style = MaterialTheme.typography.titleMedium)
        Text(
            "${summary?.calls ?: 0} 次调用" +
                if ((summary?.estimatedCalls ?: 0) > 0) " · 估算 ${summary!!.estimatedCalls} 次" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
