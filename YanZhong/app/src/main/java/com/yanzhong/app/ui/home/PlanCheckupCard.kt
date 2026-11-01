package com.yanzhong.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.remote.CheckupResp
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.SecondaryButton

/**
 * 首页计划体检卡:服务端主动体检的一句话结论 + 本周执行概况。
 * 「去调整计划」始终可用:没有问题也能让 AI 微调;关闭只影响今天(由 CheckupViewModel 记住日期)。
 */
@Composable
fun PlanCheckupCard(
    checkup: CheckupResp,
    onAdjust: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    "计划体检",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    CheckupFormat.headline(checkup),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            // 关闭按钮保持默认 48dp 触达区,只缩小图标;整卡点按不参与,避免误关
            IconButton(onClick = onDismiss) {
                Icon(
                    AppIcons.Close,
                    contentDescription = "今天不再显示",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(CheckupFormat.rate(checkup), style = MaterialTheme.typography.bodySmall)
        Text(
            CheckupFormat.trend(checkup),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            CheckupFormat.load(checkup),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (checkup.suggestion.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(checkup.suggestion, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            text = "去调整计划",
            onClick = onAdjust,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
