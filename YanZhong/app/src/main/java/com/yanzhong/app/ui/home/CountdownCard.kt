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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.db.CountdownNodeEntity
import com.yanzhong.app.data.db.NodeType
import com.yanzhong.app.ui.guide.GuideAnchor
import com.yanzhong.app.ui.guide.GuideAnchors
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.BrandGradient
import com.yanzhong.app.ui.theme.CountdownAlertLevel
import com.yanzhong.app.ui.theme.CountdownNumberStyle
import com.yanzhong.app.ui.theme.EmptyState
import com.yanzhong.app.ui.theme.GradientCard
import com.yanzhong.app.ui.theme.TextAction
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
        GuideAnchor(GuideAnchors.HOME_COUNTDOWN) {
            AppCard {
                EmptyState(
                    icon = AppIcons.Timer,
                    title = "还没有倒计时节点",
                    hint = "添加一个节点，让每一天都有锚点",
                    compact = true,
                    action = { TextAction("添加节点", onClick = onManageNodes) }
                )
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

    GuideAnchor(GuideAnchors.HOME_COUNTDOWN) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalPager(state = pagerState) { page ->
                val node = nodes.getOrNull(page) ?: return@HorizontalPager
                CountdownFace(
                    node = node,
                    now = now,
                    nodeCount = nodes.size,
                    onManageNodes = onManageNodes
                )
            }
            // 分页圆点:≤6 个画点,更多时退化为「3/8」计数。
            // 圆点在卡外(页面底色)上,普通档用 primary、预警档用语义预警色,白点会与浅色背景融为一体。
            val current = nodes.getOrNull(pagerState.currentPage)
            val currentLevel = current?.let { alertLevelOf(TimeUtils.daysBetween(now, it.targetAt)) }
                ?: CountdownAlertLevel.NORMAL
            val dotColor = when (currentLevel) {
                CountdownAlertLevel.NORMAL -> MaterialTheme.colorScheme.primary
                CountdownAlertLevel.AMBER -> WarnAmber
                else -> WarnRed
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (nodes.size <= 6) {
                    repeat(nodes.size) { index ->
                        Box(
                            Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (index == pagerState.currentPage) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .background(dotColor.copy(alpha = if (index == pagerState.currentPage) 0.95f else 0.45f))
                        )
                    }
                } else {
                    Text(
                        "${pagerState.currentPage + 1}/${nodes.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = dotColor.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CountdownFace(
    node: CountdownNodeEntity,
    now: Long,
    nodeCount: Int,
    onManageNodes: () -> Unit
) {
    val c = TimeUtils.countdownTo(node.targetAt, now)
    val daysLeft = TimeUtils.daysBetween(now, node.targetAt)
    val level = alertLevelOf(daysLeft)

    val containerColor = if (level == CountdownAlertLevel.AMBER) WarnAmberContainer else WarnRedContainer
    val contentColor = when (level) {
        CountdownAlertLevel.NORMAL -> Color.White
        CountdownAlertLevel.AMBER -> WarnAmberContent
        else -> WarnRedContent
    }
    // 天数主数字:普通档纯白,预警档用语义预警色
    val digitColor = when (level) {
        CountdownAlertLevel.NORMAL -> Color.White
        CountdownAlertLevel.AMBER -> WarnAmber
        else -> WarnRed
    }
    val muted = contentColor.copy(alpha = 0.7f)

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

    val face: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onManageNodes) {
                Text("管理 · $nodeCount 个", style = MaterialTheme.typography.labelMedium, color = muted)
            }
        }
        Text(
            text = "${NodeType.label(node.type)} · ${TimeUtils.formatYmdHm(node.targetAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = muted
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .scale(breath),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TimeUnitCell(c.days, "天", digitColor, muted, Modifier.weight(1.1f))
            TimeUnitCell(c.hours, "时", contentColor, muted, Modifier.weight(1f))
            TimeUnitCell(c.minutes, "分", contentColor, muted, Modifier.weight(1f))
            TimeUnitCell(c.seconds, "秒", contentColor, muted, Modifier.weight(1f))
        }
    }

    if (level == CountdownAlertLevel.NORMAL) {
        GradientCard(colors = BrandGradient, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            face()
        }
    } else {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = containerColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                face()
            }
        }
    }
}

@Composable
private fun TimeUnitCell(
    value: Long,
    unit: String,
    valueColor: Color,
    unitColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = CountdownNumberStyle,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.labelSmall,
            color = unitColor
        )
    }
}
