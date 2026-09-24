package com.yanzhong.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.EmptyState
import com.yanzhong.app.data.db.DayDuration
import com.yanzhong.app.data.db.HourDuration
import com.yanzhong.app.data.db.SubjectDuration
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.util.TimeUtils
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

/** 科目投入占比环形图(PRD 3.9 / 4.4):Compose Canvas 自绘,无第三方依赖 */
@Composable
fun SubjectRingChart(
    perSubject: List<SubjectDuration>,
    subjects: List<SubjectEntity>,
    modifier: Modifier = Modifier
) {
    val entries = perSubject.mapNotNull { sd ->
        val subject = subjects.firstOrNull { it.id == sd.subjectId }
        subject?.let { Triple(it.name, sd.totalMin, Color(it.colorArgb)) }
    }
    val total = entries.sumOf { it.second }
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
            Canvas(Modifier.size(120.dp)) {
                val stroke = 16.dp.toPx()
                if (total <= 0 || entries.isEmpty()) {
                    drawCircle(
                        color = emptyColor,
                        radius = (size.minDimension - stroke) / 2,
                        style = Stroke(stroke)
                    )
                } else {
                    // 外圈 subtle 轨道
                    drawCircle(
                        color = emptyColor.copy(alpha = 0.5f),
                        radius = size.minDimension / 2,
                        style = Stroke(stroke)
                    )
                    var startAngle = -90f
                    entries.forEach { (_, minutes, color) ->
                        val sweep = minutes.toFloat() / total * 360f
                        drawArc(
                            color = color,
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = false,
                            style = Stroke(stroke, cap = StrokeCap.Butt)
                        )
                        startAngle += sweep
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    TimeUtils.formatHours(total),
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    "净专注",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(20.dp))
        Column {
            if (entries.isEmpty()) {
                Text(
                    "暂无数据,先完成一个番茄吧",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                entries.forEach { (name, minutes, color) ->
                    val pct = if (total == 0) 0 else minutes * 100 / total
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$name  $pct% · ${TimeUtils.formatHours(minutes)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** 近 14 天净专注趋势折线图:Y 轴刻度 + 隔天日期 + 渐变面积 */
@Composable
fun TrendLineChart(
    trend: List<DayDuration>,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 9.sp)

    Column(modifier.fillMaxWidth()) {
        if (trend.isEmpty()) {
            EmptyState(
                icon = AppIcons.TrendingUp,
                title = "暂无专注数据",
                hint = "完成第一个番茄后,这里会出现近 14 天趋势"
            )
            return@Column
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(168.dp)
        ) {
            val maxMin = trend.maxOf { it.totalMin }.coerceAtLeast(30)
            val axisLeft = 34.dp.toPx()
            val rightPad = 6.dp.toPx()
            val axisBottom = 18.dp.toPx()
            val topPad = 6.dp.toPx()
            val chartWidth = size.width - axisLeft - rightPad
            val chartHeight = size.height - topPad - axisBottom
            val stepX = if (trend.size > 1) chartWidth / (trend.size - 1) else chartWidth

            // 网格线(3条) + Y 轴刻度值
            for (i in 0..3) {
                val y = topPad + chartHeight * i / 3f
                if (i > 0) {
                    drawLine(
                        color = gridColor,
                        start = Offset(axisLeft, y),
                        end = Offset(axisLeft + chartWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                val label = textMeasurer.measure(
                    fmtAxis((maxMin * (3 - i) / 3f).toInt()),
                    axisStyle
                )
                drawText(
                    label,
                    color = axisColor,
                    topLeft = Offset(
                        axisLeft - label.size.width - 4.dp.toPx(),
                        y - label.size.height / 2f
                    )
                )
            }

            // 折线数据点
            val points = trend.mapIndexed { i, day ->
                Offset(
                    x = axisLeft + i * stepX,
                    y = topPad + chartHeight - (day.totalMin.toFloat() / maxMin) * chartHeight
                )
            }

            // 折线下渐变面积
            val areaPath = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                lineTo(points.last().x, topPad + chartHeight)
                lineTo(points.first().x, topPad + chartHeight)
                close()
            }
            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.18f),
                        lineColor.copy(alpha = 0.02f)
                    ),
                    startY = topPad,
                    endY = topPad + chartHeight
                )
            )

            // 折线
            for (i in 0 until points.size - 1) {
                drawLine(
                    color = lineColor,
                    start = points[i],
                    end = points[i + 1],
                    strokeWidth = 2.dp.toPx()
                )
            }
            points.forEach { p ->
                drawCircle(
                    color = lineColor,
                    radius = 3.dp.toPx(),
                    center = p
                )
            }

            // X 轴隔天日期(首/中/尾,最多 4 个)
            val labelCount = if (trend.size < 2) 1 else 4
            for (k in 0 until labelCount) {
                val idx = (k * (trend.size - 1) / 3f).roundToInt()
                val x = axisLeft + idx * stepX
                val label = textMeasurer.measure(trend[idx].day.substring(5), axisStyle)
                val tx = if (k == labelCount - 1) x - label.size.width
                else x - label.size.width / 2f
                drawText(
                    label,
                    color = axisColor,
                    topLeft = Offset(tx, size.height - 12.dp.toPx())
                )
            }
        }
    }
}

private fun fmtAxis(min: Int): String = when {
    min == 0 -> "0"
    min >= 60 -> {
        val h = min / 60.0
        if (h % 1.0 == 0.0) "${h.toInt()}h" else String.format("%.1f", h) + "h"
    }
    else -> "${min}m"
}

/** 24 小时时段专注分布柱状图 + 基线 + 小时标签 + 黄金时段结论(PRD 3.9) */
@Composable
fun HourlyBarChart(
    hourly: List<HourDuration>,
    modifier: Modifier = Modifier
) {
    val barColor = MaterialTheme.colorScheme.primary
    val highlightColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 9.sp)

    val sorted = hourly.sortedByDescending { it.totalMin }
    val topHours = sorted.take(3).map { it.hour }.toSet()

    Column(modifier.fillMaxWidth()) {
        if (hourly.isEmpty()) {
            EmptyState(
                icon = AppIcons.Timer,
                title = "暂无时段数据",
                hint = "完成后可以看到你的黄金专注时段"
            )
            return@Column
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(136.dp)
        ) {
            val maxMin = sorted.first().totalMin.coerceAtLeast(1)
            val axisBottom = 16.dp.toPx()
            val chartHeight = size.height - axisBottom - 2.dp.toPx()
            val barSpace = 2.dp.toPx()
            val barWidth = (size.width - barSpace * 23) / 24f

            repeat(24) { hour ->
                val minutes = hourly.firstOrNull { it.hour == hour }?.totalMin ?: 0
                val barHeight = (minutes.toFloat() / maxMin) * chartHeight
                drawRoundRect(
                    color = if (hour in topHours && minutes > 0) highlightColor
                    else barColor.copy(alpha = if (minutes > 0) 0.85f else 0.15f),
                    topLeft = Offset(
                        hour * (barWidth + barSpace),
                        chartHeight - barHeight
                    ),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }

            // 基线
            drawLine(
                color = axisColor.copy(alpha = 0.35f),
                start = Offset(0f, chartHeight + 1.dp.toPx()),
                end = Offset(size.width, chartHeight + 1.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )

            // 0/6/12/18/24 小时标签
            listOf(0, 6, 12, 18, 24).forEach { hour ->
                val label = textMeasurer.measure("$hour", axisStyle)
                val x = if (hour == 24) size.width - label.size.width
                else hour * (barWidth + barSpace) + barWidth / 2f - label.size.width / 2f
                drawText(
                    label,
                    color = axisColor,
                    topLeft = Offset(x, size.height - 12.dp.toPx())
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (topHours.isNotEmpty()) {
            val tops = topHours.sorted()
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    "黄金时段 " + tops.joinToString(" / ") {
                        String.format("%02d:00", it)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        } else {
            Text(
                "完成几个番茄后,这里会显示你的学习高峰时段",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val HEATMAP_WEEKS = 15

/** 打卡热力图(GitHub 风格):近 15 周每日净专注强度,列=周(旧→新),行=周一~周日,未来日期留空 */
@Composable
fun HeatmapChart(days: List<DayDuration>, modifier: Modifier = Modifier) {
    if (days.isEmpty()) {
        EmptyState(
            icon = AppIcons.Flame,
            title = "暂无打卡数据",
            hint = "完成番茄后,这里会出现每日专注热力"
        )
        return
    }
    val byDay = remember(days) { days.associate { it.day to it.totalMin } }
    val today = remember { LocalDate.now() }
    val startMonday = remember { today.with(DayOfWeek.MONDAY).minusWeeks(HEATMAP_WEEKS - 1L) }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 9.sp)

    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val leftW = 16.dp
            val gap = 3.dp
            val labelH = 14.dp
            val cell = ((maxWidth - leftW - gap * (HEATMAP_WEEKS - 1)) / HEATMAP_WEEKS)
                .coerceAtMost(16.dp)
            val gridH = cell * 7 + gap * 6
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(labelH + gridH + 2.dp)
            ) {
                val leftPx = leftW.toPx()
                val gapPx = gap.toPx()
                val topPx = labelH.toPx()
                val cellPx = cell.toPx()
                val step = cellPx + gapPx

                // 顶部月份标签:月份变化的首列标注
                var lastMonth = -1
                for (w in 0 until HEATMAP_WEEKS) {
                    val monday = startMonday.plusWeeks(w.toLong())
                    if (monday.monthValue != lastMonth) {
                        lastMonth = monday.monthValue
                        val label = textMeasurer.measure("${monday.monthValue}月", axisStyle)
                        drawText(label, color = axisColor, topLeft = Offset(leftPx + w * step, 0f))
                    }
                }

                // 7×15 单元格:按当日净专注分钟分档着色
                for (w in 0 until HEATMAP_WEEKS) {
                    for (d in 0 until 7) {
                        val date = startMonday.plusWeeks(w.toLong()).plusDays(d.toLong())
                        if (date.isAfter(today)) continue
                        val minutes = byDay[date.toString()] ?: 0
                        val color = when {
                            minutes <= 0 -> track.copy(alpha = 0.35f)
                            minutes < 30 -> primary.copy(alpha = 0.25f)
                            minutes < 90 -> primary.copy(alpha = 0.5f)
                            minutes < 180 -> primary.copy(alpha = 0.75f)
                            else -> primary
                        }
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(leftPx + w * step, topPx + d * step),
                            size = Size(cellPx, cellPx),
                            cornerRadius = CornerRadius(2.dp.toPx())
                        )
                    }
                }

                // 左侧周几标签:一/三/五
                listOf(0 to "一", 2 to "三", 4 to "五").forEach { (row, text) ->
                    val label = textMeasurer.measure(text, axisStyle)
                    drawText(
                        label,
                        color = axisColor,
                        topLeft = Offset(0f, topPx + row * step + (cellPx - label.size.height) / 2f)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 图例:少 → 多
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("少", style = MaterialTheme.typography.labelSmall, color = axisColor)
            listOf(0.35f, 0.25f, 0.5f, 0.75f, 1f).forEachIndexed { i, alpha ->
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (i == 0) track.copy(alpha = alpha)
                            else primary.copy(alpha = alpha)
                        )
                )
            }
            Text("多", style = MaterialTheme.typography.labelSmall, color = axisColor)
        }
    }
}

/** 周一~周日净专注分布:找出一周里最能学的那天(黄金学习日) */
@Composable
fun WeekdayBarChart(stats: List<WeekdayStat>, modifier: Modifier = Modifier) {
    val names = listOf("一", "二", "三", "四", "五", "六", "日")
    val maxMin = stats.maxOfOrNull { it.totalMin } ?: 0
    if (maxMin <= 0) {
        EmptyState(
            icon = AppIcons.Activity,
            title = "暂无周几数据",
            hint = "积累一段时间后,可以看到一周里哪天最能学"
        )
        return
    }
    val barColor = MaterialTheme.colorScheme.primary
    val highlight = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val best = stats.maxByOrNull { it.totalMin }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            stats.forEach { st ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    val isBest = st.weekday == best?.weekday
                    Text(
                        if (st.totalMin > 0) TimeUtils.formatHours(st.totalMin) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isBest) highlight else axisColor
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .height((96.dp * st.totalMin / maxMin).coerceAtLeast(4.dp))
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isBest) highlight else barColor.copy(alpha = 0.7f)
                            )
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        names[st.weekday - 1],
                        style = MaterialTheme.typography.labelMedium,
                        color = axisColor
                    )
                }
            }
        }
        if (best != null && best.totalMin > 0) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    "黄金学习日 · 周${names[best.weekday - 1]} 累计 ${TimeUtils.formatHours(best.totalMin)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}
