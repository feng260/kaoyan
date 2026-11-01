package com.yanzhong.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.ui.cloud.CloudFormat
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.PillTone
import com.yanzhong.app.ui.theme.SecondaryButton
import com.yanzhong.app.ui.theme.StatusPill
import kotlinx.coroutines.delay

/** 同步状态四态:正常 / 同步中 / 失败 / 尚未同步;数字时间每 30 秒刷新一次,不拖整页重组 */
@Composable
fun SyncStatusCard(
    lastSyncAt: Long,
    syncing: Boolean,
    lastError: String?,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val error = lastError
    val statusText = when {
        syncing -> "正在同步…"
        error != null -> "同步失败 · 上次成功 ${
            if (lastSyncAt > 0) CloudFormat.clockAt(lastSyncAt) else "尚未同步"
        }"
        lastSyncAt <= 0 -> "尚未同步 · 连上服务器后会自动同步"
        else -> "已同步 · ${CloudFormat.syncMoment(lastSyncAt, now)}"
    }
    val statusColor = if (error != null) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("同步状态", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                if (syncing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }
                } else {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                }
            }
            StatusPill(
                text = when {
                    syncing -> "同步中"
                    error != null -> "待重试"
                    lastSyncAt <= 0 -> "待同步"
                    else -> "正常"
                },
                tone = when {
                    syncing -> PillTone.NEUTRAL
                    error != null -> PillTone.WARNING
                    lastSyncAt <= 0 -> PillTone.NEUTRAL
                    else -> PillTone.SOLID
                }
            )
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            text = if (error != null) "重试同步" else "立即同步",
            onClick = onSyncNow,
            enabled = !syncing,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
