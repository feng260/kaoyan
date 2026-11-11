package com.yanzhong.app.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.TextAction
import kotlin.math.roundToInt

@Composable
internal fun GuideOverlay(
    spec: GuidePageSpec,
    stepIndex: Int,
    targets: GuideTargetRegistry,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    val step = spec.steps.getOrNull(stepIndex) ?: return
    val target = targets.bounds[step.target] ?: return
    val density = LocalDensity.current
    val safeTop = WindowInsets.safeDrawing.getTop(density)
    val safeBottom = WindowInsets.safeDrawing.getBottom(density)
    val margin = with(density) { 20.dp.toPx() }
    val gap = with(density) { 12.dp.toPx() }
    val inflate = with(density) { 8.dp.toPx() }
    val primary = MaterialTheme.colorScheme.primary

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    val cutLeftTarget = (target.left - inflate).coerceAtLeast(0f)
    val cutTopTarget = (target.top - inflate).coerceAtLeast(0f)
    val cutRightTarget = (target.right + inflate).coerceAtMost(containerSize.width.toFloat())
    val cutBottomTarget = (target.bottom + inflate).coerceAtMost(containerSize.height.toFloat())
    val cutLeft by animateFloatAsState(cutLeftTarget, tween(220), label = "guideCutLeft")
    val cutTop by animateFloatAsState(cutTopTarget, tween(220), label = "guideCutTop")
    val cutRight by animateFloatAsState(cutRightTarget, tween(220), label = "guideCutRight")
    val cutBottom by animateFloatAsState(cutBottomTarget, tween(220), label = "guideCutBottom")

    val dim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { dim.animateTo(1f, tween(180)) }

    BackHandler(onBack = onSkip)

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                containerSize = it.size
                containerOrigin = it.boundsInWindow().topLeft
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val dimColor = Color.Black.copy(alpha = 0.72f * dim.value)
            val left = cutLeft.coerceIn(0f, size.width)
            val top = cutTop.coerceIn(0f, size.height)
            val right = cutRight.coerceIn(0f, size.width)
            val bottom = cutBottom.coerceIn(0f, size.height)
            drawRect(dimColor, Offset.Zero, Size(size.width, top))
            drawRect(dimColor, Offset(0f, bottom), Size(size.width, size.height - bottom))
            drawRect(
                dimColor,
                Offset(0f, top),
                Size(left, (bottom - top).coerceAtLeast(0f))
            )
            drawRect(
                dimColor,
                Offset(right, top),
                Size((size.width - right).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f))
            )
            drawRoundRect(
                color = primary.copy(alpha = dim.value),
                topLeft = Offset(left, top),
                size = Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        val local = Rect(
            target.left - containerOrigin.x,
            target.top - containerOrigin.y,
            target.right - containerOrigin.x,
            target.bottom - containerOrigin.y
        )
        val screenW = containerSize.width
        val screenH = containerSize.height
        val cardW = cardSize.width
        val cardH = cardSize.height
        val position = if (cardW > 0 && cardH > 0 && screenW > 0 && screenH > 0) {
            val fullHeightTarget = local.height >= screenH * 0.8f
            if (fullHeightTarget) {
                val placeRight = screenW - local.right >= local.left
                val rawX = if (placeRight) local.right + gap else local.left - gap - cardW
                val rawY = local.center.y - cardH / 2f
                IntOffset(
                    rawX.coerceIn(margin, (screenW - margin - cardW).coerceAtLeast(margin)).roundToInt(),
                    rawY.coerceIn(
                        safeTop + margin,
                        (screenH - safeBottom - cardH - margin).coerceAtLeast(safeTop + margin)
                    ).roundToInt()
                )
            } else {
                val spaceBelow = screenH - safeBottom - local.bottom
                val spaceAbove = local.top - safeTop
                val placeBelow = spaceBelow >= cardH + gap || spaceBelow >= spaceAbove
                val rawX = local.center.x - cardW / 2f
                val rawY = if (placeBelow) local.bottom + gap else local.top - gap - cardH
                IntOffset(
                    rawX.coerceIn(margin, (screenW - margin - cardW).coerceAtLeast(margin)).roundToInt(),
                    rawY.coerceIn(
                        safeTop + margin,
                        (screenH - safeBottom - cardH - margin).coerceAtLeast(safeTop + margin)
                    ).roundToInt()
                )
            }
        } else {
            IntOffset.Zero
        }

        GuideCard(
            spec = spec,
            stepIndex = stepIndex,
            isLast = nextAvailableIndex(spec.steps, targets.bounds.keys, stepIndex) == null,
            onNext = onNext,
            onSkip = onSkip,
            modifier = Modifier
                .offset { position }
                .onSizeChanged { cardSize = it }
                .alpha(if (cardSize == IntSize.Zero) 0f else 1f)
        )
    }
}

@Composable
private fun GuideCard(
    spec: GuidePageSpec,
    stepIndex: Int,
    isLast: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.widthIn(max = 320.dp)
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Crossfade(targetState = stepIndex, animationSpec = tween(140), label = "guideStep") { index ->
                val step = spec.steps.getOrNull(index) ?: return@Crossfade
                Column {
                    Text(step.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        step.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "第 ${index + 1} / ${spec.steps.size} 步",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("跳过", onClick = onSkip)
                Spacer(Modifier.weight(1f))
                PrimaryButton(
                    text = if (isLast) "知道了" else "下一步",
                    onClick = onNext,
                    modifier = Modifier.widthIn(max = 160.dp)
                )
            }
        }
    }
}
