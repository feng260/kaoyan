package com.yanzhong.app.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanzhong.app.data.db.CountdownNodeEntity
import com.yanzhong.app.data.db.NodeType
import com.yanzhong.app.ui.theme.CountdownAlertLevel
import com.yanzhong.app.ui.theme.WarnAmber
import com.yanzhong.app.ui.theme.WarnAmberContainer
import com.yanzhong.app.ui.theme.WarnAmberContent
import com.yanzhong.app.ui.theme.WarnRed
import com.yanzhong.app.ui.theme.WarnRedContainer
import com.yanzhong.app.ui.theme.WarnRedContent
import com.yanzhong.app.ui.theme.alertLevelOf
import com.yanzhong.app.util.TimeUtils
import kotlinx.coroutines.delay

/** 首页置顶倒计时区:天/时/分/秒逐秒刷新、左右滑切节点、预警配色(PRD 3.1 / 6.4) */
@Composable
fun CountdownCard(
    nodes: List<CountdownNodeEntity>,
    initialIndex: Int,
    onNodeSelected: (Int) -> Unit,
    onManageNodes: () -> Unit
) {
    // 秒级时钟只在本卡片内部刷新,避免 now 变化波及整个首页重组
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            now = System.currentTimeMillis()
        }
    }

    if (nodes.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("还没有倒计时节点", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "添加一个节点,让每一天都有锚点",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onManageNodes) { Text("添加节点") }
            }
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, nodes.lastIndex),
        pageCount = { nodes.size }
    )
    LaunchedEffect(initialIndex, nodes.size) {
        if (!pagerState.isScrollInProgress) {
            val target = initialIndex.coerceIn(0, nodes.lastIndex)
            if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        onNodeSelected(pagerState.currentPage)
    }

    HorizontalPager(state = pagerState) { page ->
        val node = nodes.getOrNull(page) ?: return@HorizontalPager
        CountdownFace(node = node, now = now, onManageNodes = onManageNodes)
    }
}

@Composable
private fun CountdownFace(node: CountdownNodeEntity, now: Long, onManageNodes: () -> Unit) {
    val c = TimeUtils.countdownTo(node.targetAt, now)
    val daysLeft = TimeUtils.daysBetween(now, node.targetAt)
    val level = alertLevelOf(daysLeft)

    val alertColor = when (level) {
        CountdownAlertLevel.NORMAL -> MaterialTheme.colorScheme.primary
        CountdownAlertLevel.AMBER -> WarnAmber
        else -> WarnRed
    }
    val containerColor = when (level) {
        CountdownAlertLevel.NORMAL -> MaterialTheme.colorScheme.primaryContainer
        CountdownAlertLevel.AMBER -> WarnAmberContainer
        else -> WarnRedContainer
    }
    val contentColor = when (level) {
        CountdownAlertLevel.NORMAL -> MaterialTheme.colorScheme.onPrimaryContainer
        CountdownAlertLevel.AMBER -> WarnAmberContent
        else -> WarnRedContent
    }

    // ≤3 天:数字呼吸动效(PRD 6.4)
    val breath = if (level == CountdownAlertLevel.CRITICAL) {
        val transition = rememberInfiniteTransition(label = "breath")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathScale"
        ).value
    } else 1f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(listOf(containerColor, containerColor.copy(alpha = 0.82f)))
            )
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.headlineMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onManageNodes) {
                Text("管理节点 · ${nodesCountLabel(node)}", color = contentColor.copy(alpha = 0.7f))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${NodeType.label(node.type)} · ${TimeUtils.formatYmdHm(node.targetAt)}",
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .scale(breath),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TimeUnitCell(c.days, "天", alertColor, contentColor, Modifier.weight(1.1f))
            TimeUnitCell(c.hours, "时", alertColor, contentColor, Modifier.weight(1f))
            TimeUnitCell(c.minutes, "分", alertColor, contentColor, Modifier.weight(1f))
            TimeUnitCell(c.seconds, "秒", alertColor, contentColor, Modifier.weight(1f))
        }
    }
}

private fun nodesCountLabel(node: CountdownNodeEntity) = if (node.pinned) "已置顶" else "普通"

@Composable
private fun TimeUnitCell(
    value: Long,
    unit: String,
    alertColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            color = if (unit == "天") alertColor else contentColor,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 40.sp,
                fontFeatureSettings = "tnum"
            )
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor.copy(alpha = 0.6f)
        )
    }
}
