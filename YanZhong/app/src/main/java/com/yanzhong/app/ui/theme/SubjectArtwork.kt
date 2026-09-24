package com.yanzhong.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 科目小插画(参考番茄ToDo 待办集插图):每个科目一个专属清新小场景,
 * 纯代码绘制,不加图片资源(drawable 只保留必要文件)。
 * 白色主体 + 科目色点缀,深浅色模式自适应。
 */

private enum class ArtScene { MATH, ENGLISH, CS, POLITICS, DEFAULT }

private fun artSceneOf(name: String): ArtScene = when {
    name.contains("数学") -> ArtScene.MATH
    name.contains("英语") -> ArtScene.ENGLISH
    name.contains("政治") -> ArtScene.POLITICS
    name.contains("408") || name.contains("数据结构") || name.contains("组成") ||
        name.contains("操作") || name.contains("网络") || name.contains("计算机") ||
        name.contains("编译") || name.contains("数据库") || name.contains("人工智能") -> ArtScene.CS
    else -> ArtScene.DEFAULT
}

@Composable
fun SubjectArtwork(
    subjectName: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val white = Color.White
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.08f))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            when (artSceneOf(subjectName)) {
                ArtScene.MATH -> drawMath(color, white)
                ArtScene.ENGLISH -> drawEnglish(color, white)
                ArtScene.CS -> drawCs(color, white)
                ArtScene.POLITICS -> drawPolitics(color, white)
                ArtScene.DEFAULT -> drawCoffee(color, white)
            }
        }
    }
}

/** 数学:夜空月星 + 正弦曲线 */
private fun DrawScope.drawMath(color: Color, white: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    // 月亮
    drawCircle(white.copy(alpha = 0.9f), radius = h * 0.17f, center = Offset(w * 0.74f, h * 0.28f))
    // 星星
    drawCircle(white.copy(alpha = 0.65f), radius = 1.6.dp.toPx(), center = Offset(w * 0.18f, h * 0.22f))
    drawCircle(color.copy(alpha = 0.55f), radius = 1.2.dp.toPx(), center = Offset(w * 0.46f, h * 0.46f))
    drawCircle(color.copy(alpha = 0.4f), radius = 1.2.dp.toPx(), center = Offset(w * 0.86f, h * 0.6f))
    // 正弦曲线
    val wave = Path().apply {
        moveTo(0f, h * 0.74f)
        cubicTo(w * 0.18f, h * 0.56f, w * 0.32f, h * 0.56f, w * 0.5f, h * 0.74f)
        cubicTo(w * 0.68f, h * 0.92f, w * 0.82f, h * 0.92f, w, h * 0.74f)
    }
    drawPath(wave, white.copy(alpha = 0.85f), style = stroke)
}

/** 英语:翻开的书 + 太阳云朵 */
private fun DrawScope.drawEnglish(color: Color, white: Color) {
    val w = size.width
    val h = size.height
    // 太阳
    drawCircle(color.copy(alpha = 0.45f), radius = h * 0.13f, center = Offset(w * 0.8f, h * 0.22f))
    // 云朵:两圆 + 底条
    drawCircle(white.copy(alpha = 0.75f), radius = h * 0.10f, center = Offset(w * 0.2f, h * 0.18f))
    drawCircle(white.copy(alpha = 0.6f), radius = h * 0.08f, center = Offset(w * 0.32f, h * 0.2f))
    // 翻开的书:左右两页
    val left = Path().apply {
        moveTo(w * 0.48f, h * 0.42f)
        lineTo(w * 0.1f, h * 0.34f)
        lineTo(w * 0.1f, h * 0.78f)
        lineTo(w * 0.48f, h * 0.88f)
        close()
    }
    val right = Path().apply {
        moveTo(w * 0.52f, h * 0.42f)
        lineTo(w * 0.9f, h * 0.34f)
        lineTo(w * 0.9f, h * 0.78f)
        lineTo(w * 0.52f, h * 0.88f)
        close()
    }
    drawPath(left, white.copy(alpha = 0.85f))
    drawPath(right, white.copy(alpha = 0.85f))
    // 书脊 + 页线
    drawLine(white.copy(alpha = 0.95f), Offset(w * 0.5f, h * 0.40f), Offset(w * 0.5f, h * 0.90f), strokeWidth = 2.dp.toPx())
    drawLine(color.copy(alpha = 0.5f), Offset(w * 0.18f, h * 0.44f), Offset(w * 0.4f, h * 0.48f), strokeWidth = 1.4.dp.toPx())
    drawLine(color.copy(alpha = 0.5f), Offset(w * 0.6f, h * 0.48f), Offset(w * 0.82f, h * 0.44f), strokeWidth = 1.4.dp.toPx())
}

/** 408:芯片 + 信号波 */
private fun DrawScope.drawCs(color: Color, white: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
    // 芯片主体
    val chip = Size(w * 0.44f, w * 0.44f)
    drawRoundRect(
        color = white.copy(alpha = 0.88f),
        topLeft = Offset(w * 0.28f, h * 0.34f),
        size = chip,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
    )
    // 芯片内芯
    drawRoundRect(
        color = color.copy(alpha = 0.85f),
        topLeft = Offset(w * 0.43f, h * 0.49f),
        size = Size(w * 0.14f, w * 0.14f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
    )
    // 引脚:左右各两条
    listOf(0.42f, 0.62f).forEach { fy ->
        drawLine(white.copy(alpha = 0.75f), Offset(w * 0.10f, h * fy), Offset(w * 0.28f, h * fy), strokeWidth = 2.dp.toPx())
        drawLine(white.copy(alpha = 0.75f), Offset(w * 0.72f, h * fy), Offset(w * 0.90f, h * fy), strokeWidth = 2.dp.toPx())
    }
    // 右上信号波
    drawArc(
        color = color.copy(alpha = 0.55f),
        startAngle = -70f, sweepAngle = 70f, useCenter = false,
        topLeft = Offset(w * 0.52f, h * 0.02f),
        size = Size(w * 0.34f, w * 0.34f),
        style = stroke
    )
}

/** 政治:飘扬旗帜 + 太阳 */
private fun DrawScope.drawPolitics(color: Color, white: Color) {
    val w = size.width
    val h = size.height
    // 太阳
    drawCircle(color.copy(alpha = 0.4f), radius = h * 0.12f, center = Offset(w * 0.8f, h * 0.2f))
    // 旗杆
    drawLine(
        white.copy(alpha = 0.9f),
        Offset(w * 0.28f, h * 0.16f),
        Offset(w * 0.28f, h * 0.9f),
        strokeWidth = 2.2.dp.toPx(),
        cap = StrokeCap.Round
    )
    // 旗面:微微飘动
    val flag = Path().apply {
        moveTo(w * 0.28f, h * 0.18f)
        cubicTo(w * 0.5f, h * 0.10f, w * 0.62f, h * 0.30f, w * 0.86f, h * 0.22f)
        lineTo(w * 0.86f, h * 0.52f)
        cubicTo(w * 0.62f, h * 0.60f, w * 0.5f, h * 0.40f, w * 0.28f, h * 0.48f)
        close()
    }
    drawPath(flag, white.copy(alpha = 0.85f))
    // 旗面条纹点缀
    drawLine(
        color.copy(alpha = 0.65f),
        Offset(w * 0.38f, h * 0.30f),
        Offset(w * 0.62f, h * 0.28f),
        strokeWidth = 1.6.dp.toPx()
    )
    // 旗杆底座
    drawLine(white.copy(alpha = 0.7f), Offset(w * 0.18f, h * 0.9f), Offset(w * 0.42f, h * 0.9f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
}

/** 默认:咖啡杯 + 蒸汽(日常清新) */
private fun DrawScope.drawCoffee(color: Color, white: Color) {
    val w = size.width
    val h = size.height
    // 蒸汽:两条小波浪
    val steam1 = Path().apply {
        moveTo(w * 0.36f, h * 0.10f)
        cubicTo(w * 0.30f, h * 0.18f, w * 0.42f, h * 0.24f, w * 0.36f, h * 0.32f)
    }
    val steam2 = Path().apply {
        moveTo(w * 0.60f, h * 0.10f)
        cubicTo(w * 0.54f, h * 0.18f, w * 0.66f, h * 0.24f, w * 0.60f, h * 0.32f)
    }
    drawPath(steam1, color.copy(alpha = 0.55f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    drawPath(steam2, color.copy(alpha = 0.55f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    // 杯身
    drawRoundRect(
        color = white.copy(alpha = 0.88f),
        topLeft = Offset(w * 0.20f, h * 0.38f),
        size = Size(w * 0.52f, h * 0.42f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx())
    )
    // 杯把
    drawArc(
        color = white.copy(alpha = 0.85f),
        startAngle = -55f, sweepAngle = 110f, useCenter = false,
        topLeft = Offset(w * 0.68f, h * 0.44f),
        size = Size(w * 0.22f, h * 0.26f),
        style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
    )
    // 杯口咖啡线
    drawLine(color.copy(alpha = 0.5f), Offset(w * 0.24f, h * 0.46f), Offset(w * 0.68f, h * 0.46f), strokeWidth = 1.6.dp.toPx())
    // 杯碟
    drawLine(white.copy(alpha = 0.7f), Offset(w * 0.14f, h * 0.84f), Offset(w * 0.78f, h * 0.84f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
}
