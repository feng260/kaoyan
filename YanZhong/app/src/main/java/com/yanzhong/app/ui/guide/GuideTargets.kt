package com.yanzhong.app.ui.guide

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

class GuideTargetRegistry {
    internal val bounds = mutableStateMapOf<String, Rect>()

    internal fun update(id: String, rect: Rect) {
        bounds[id] = rect
    }

    internal fun remove(id: String) {
        bounds.remove(id)
    }
}

val LocalGuideTargets = staticCompositionLocalOf<GuideTargetRegistry?> { null }

/**
 * 组合树根节点的窗口矩形。
 * Compose 1.7 的 [androidx.compose.ui.platform.WindowInfo] 还没有 containerSize,
 * 沿 parentLayoutCoordinates 上溯到根节点得到的矩形同样处在窗口坐标系里,
 * 可直接与 boundsInWindow() 比较。
 */
private fun LayoutCoordinates.rootWindowBounds(): Rect {
    var coords = this
    while (true) {
        coords = coords.parentLayoutCoordinates ?: break
    }
    return coords.boundsInWindow()
}

@Composable
fun GuideAnchor(
    id: String,
    modifier: Modifier = Modifier,
    expandTop: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val registry = LocalGuideTargets.current
    if (registry == null) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val extraTop = with(density) { expandTop.toPx() }
    Layout(
        content = content,
        modifier = modifier.onGloballyPositioned { coordinates ->
            val rect = coordinates.boundsInWindow()
            val target = if (extraTop > 0f) {
                Rect(rect.left, rect.top - extraTop, rect.right, rect.bottom)
            } else {
                rect
            }
            // 垂直滚动容器里的锚点滚出首屏后仍会组合;这类矩形登记进去只会被遮罩夹到屏幕边缘,
            // 所以只保留与窗口真正相交的锚点,让控制器按「锚点缺失」跳过对应步骤。
            val window = coordinates.rootWindowBounds()
            val windowKnown = window.width > 0f && window.height > 0f
            val offscreen = target.width <= 0f || target.height <= 0f ||
                (windowKnown && !target.overlaps(window))
            if (offscreen) {
                registry.remove(id)
            } else {
                registry.update(id, target)
            }
        }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
    DisposableEffect(registry, id) {
        onDispose { registry.remove(id) }
    }
}
