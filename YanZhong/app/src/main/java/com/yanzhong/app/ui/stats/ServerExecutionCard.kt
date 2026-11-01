package com.yanzhong.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.ui.home.CheckupFormat
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.MetricTile
import com.yanzhong.app.ui.theme.ProgressLine
import com.yanzhong.app.ui.theme.SkeletonBlock
import com.yanzhong.app.ui.theme.TextAction
import com.yanzhong.app.util.TimeUtils

/** 统计页「本周执行」卡:数据来自服务端 /plans/checkup,与本机图表互不影响 */
@Composable
fun ServerExecutionCard(
    state: ServerExecutionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("本周执行", style = MaterialTheme.typography.titleMedium)
                Text(
                    "来自服务端 · 云端计划与专注记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.loggedOut -> Text(
                "登录后可查看云端执行情况",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.error.isNotBlank() -> {
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextAction(text = "重试", onClick = onRetry)
            }
            state.checkup == null -> SkeletonBlock(height = 48.dp)
            else -> {
                val checkup = state.checkup
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(
                        value = if (checkup.weekPlanTotal > 0) {
                            "${checkup.weekPlanDone}/${checkup.weekPlanTotal}"
                        } else "—",
                        label = "本周计划",
                        modifier = Modifier.weight(1f),
                        compact = true
                    )
                    MetricTile(
                        value = "${checkup.weekActiveDays} 天",
                        label = "本周专注",
                        modifier = Modifier.weight(1f),
                        compact = true
                    )
                    MetricTile(
                        value = TimeUtils.formatHours(checkup.weekFocusMinutes.toInt()),
                        label = "专注时长",
                        modifier = Modifier.weight(1f),
                        compact = true
                    )
                }
                Spacer(Modifier.height(10.dp))
                ProgressLine(
                    progress = checkup.weekPlanDone.toFloat() / checkup.weekPlanTotal.coerceAtLeast(1),
                    height = 4.dp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (checkup.weekPlanTotal > 0) {
                        "计划完成率 ${checkup.weekPlanDone * 100 / checkup.weekPlanTotal}%"
                    } else "本周还没有计划任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    CheckupFormat.compare(checkup),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    CheckupFormat.load(checkup),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (checkup.overloadedDays.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "负荷偏高：${checkup.overloadedDays.joinToString("、") { it.date.takeLast(5) }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
