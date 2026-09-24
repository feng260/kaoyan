package com.yanzhong.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.EmptyState
import com.yanzhong.app.ui.theme.StatBanner
import com.yanzhong.app.ui.theme.StatBannerCell
import com.yanzhong.app.ui.theme.SuccessGreen
import com.yanzhong.app.util.TimeUtils

/** 统计页:四档时间切换 + KPI + 科目环形 + 趋势 + 时段分布 + 周几分布 + 打卡热力图 + 累计纪录(PRD 4.2 ④) */
@Composable
fun StatsScreen(padding: PaddingValues) {
    val vm: StatsViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()

    // 平板:限宽 720dp 居中
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        modifier = Modifier
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 12.dp,
            bottom = padding.calculateBottomPadding() + 72.dp
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column {
                Text("统计", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "数据全部来自本地,逐条可对账",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatsRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.range == range,
                        onClick = { vm.selectRange(range) },
                        label = { Text(range.label) }
                    )
                }
            }
        }

        item { KpiBanner(state) }

        item { KpiNotes(state) }

        if (state.range == StatsRange.WEEK) {
            item(key = "week-report") {
                WeekReportCard(
                    focusMin = state.focusMin,
                    goalMin = state.weekGoalMin,
                    lastWeekMin = state.lastWeekMin,
                    focusDays = state.weekFocusDays,
                    streak = state.streak
                )
            }
        }

        if (state.range == StatsRange.MONTH) {
            item(key = "month-report") {
                MonthReportCard(
                    focusMin = state.focusMin,
                    lastMonthMin = state.lastMonthMin,
                    focusDays = state.monthFocusDays,
                    pomodoroCount = state.pomodoroCount,
                    streak = state.streak
                )
            }
        }

        item {
            ChartCard(title = "科目投入占比 · ${state.range.label}") {
                SubjectRingChart(
                    perSubject = state.perSubject,
                    subjects = state.subjects
                )
            }
        }

        item {
            ChartCard(title = "近 14 天净专注趋势") {
                TrendLineChart(trend = state.trend)
            }
        }

        item {
            ChartCard(title = "24 小时时段专注分布 · 近 30 天") {
                HourlyBarChart(hourly = state.hourly)
            }
        }

        item(key = "weekday-chart") {
            ChartCard(title = "一周节奏 · 周几分布") {
                WeekdayBarChart(stats = state.weekday)
            }
        }

        item(key = "heatmap") {
            ChartCard(title = "打卡热力图 · 近 15 周") {
                HeatmapChart(days = state.heatmap)
            }
        }

        item(key = "records") {
            RecordsCard(state.records)
        }
        }
    }
}

/** 渐变 KPI 横幅(参考番茄ToDo 统计页顶部累计卡):白字大数字 */
@Composable
private fun KpiBanner(state: StatsUiState) {
    StatBanner(title = "${state.range.label}概览") {
        Row(Modifier.fillMaxWidth()) {
            val (focusNum, focusUnit) = splitValueUnit(TimeUtils.formatHours(state.focusMin))
            StatBannerCell(focusNum, focusUnit, "净专注", Modifier.weight(1f))
            StatBannerCell("${state.pomodoroCount}", "个", "番茄", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            val (rateNum, rateUnit) = splitValueUnit(state.taskRateLabel)
            StatBannerCell(rateNum, rateUnit, "任务完成率", Modifier.weight(1f))
            StatBannerCell("${state.streak}", "天", "连续打卡", Modifier.weight(1f))
        }
    }
}

/** KPI 口径说明 + 中断分析(横幅下方的普通卡) */
@Composable
private fun KpiNotes(state: StatsUiState) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                "任务完成率 = ${state.tasksDone} 已完成 / ${state.tasksDone + state.tasksOpen} 计划",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 中断分析:放弃原因 Top3(参考番茄ToDo 专注力分析)
            if (state.abandoned > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "累计中断 ${state.abandoned} 次 · " +
                        state.abandonReasons.joinToString(" / ") { "${it.first}×${it.second}" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 「5.2h」→(「5.2」,「h」)、「12 个」→(「12」,「个」):数值/单位分层排版用 */
private fun splitValueUnit(raw: String): Pair<String, String> {
    val idx = raw.indexOfFirst { !it.isDigit() && it != '.' }
    if (idx <= 0) return raw to ""
    return raw.substring(0, idx) to raw.substring(idx).trim()
}

/** 周报卡(作战计划周复盘制度):目标达成 + 环比上周 + 专注天数 */
@Composable
private fun WeekReportCard(
    focusMin: Int,
    goalMin: Int,
    lastWeekMin: Int,
    focusDays: Int,
    streak: Int
) {
    val reached = focusMin >= goalMin
    val goalProgress = if (goalMin <= 0) 0f else (focusMin.toFloat() / goalMin).coerceAtMost(1f)
    val diff = focusMin - lastWeekMin
    val diffLabel = when {
        lastWeekMin == 0 && focusMin > 0 -> "本周首周"
        diff >= 0 -> "较上周 ▲ ${TimeUtils.formatHours(diff)}"
        else -> "较上周 ▼ ${TimeUtils.formatHours(-diff)}"
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("周目标达成", style = MaterialTheme.typography.headlineMedium)
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (reached) SuccessGreen.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        if (reached) "已达成" else "${(goalProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (reached) SuccessGreen
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { goalProgress },
                modifier = Modifier.fillMaxWidth(),
                color = if (reached) com.yanzhong.app.ui.theme.SuccessGreen
                else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    diffLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (diff >= 0) com.yanzhong.app.ui.theme.SuccessGreen
                    else MaterialTheme.colorScheme.error
                )
                Text(
                    "本周专注 $focusDays 天 · 连续打卡 $streak 天",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "净学习 ${TimeUtils.formatHours(focusMin)} / 目标 ${goalMin / 60}h",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 月报卡(作战计划周复盘制度的月度版):本月净值 + 环比上月 + 专注天数 + 番茄数 */
@Composable
private fun MonthReportCard(
    focusMin: Int,
    lastMonthMin: Int,
    focusDays: Int,
    pomodoroCount: Int,
    streak: Int
) {
    val diff = focusMin - lastMonthMin
    val diffLabel = when {
        lastMonthMin == 0 && focusMin > 0 -> "本月首月"
        diff >= 0 -> "较上月 ▲ ${TimeUtils.formatHours(diff)}"
        else -> "较上月 ▼ ${TimeUtils.formatHours(-diff)}"
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("本月复盘", style = MaterialTheme.typography.headlineMedium)
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        "月报",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "净学习 ${TimeUtils.formatHours(focusMin)} · ${pomodoroCount} 个番茄",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    diffLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (diff >= 0) com.yanzhong.app.ui.theme.SuccessGreen
                    else MaterialTheme.colorScheme.error
                )
                Text(
                    "本月专注 $focusDays 天 · 连续打卡 $streak 天",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** 累计纪录卡:最长单次 / 最佳单日 / 日均 / 累计天数(参考番茄ToDo 累计统计) */
@Composable
private fun RecordsCard(records: FocusRecords) {
    ChartCard(title = "专注纪录 · 累计") {
        if (records.activeDays == 0) {
            EmptyState(
                icon = AppIcons.Trophy,
                title = "还没有专注纪录",
                hint = "完成第一个番茄后开始积累"
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecordCell(
                        value = TimeUtils.formatHours(records.longestSessionMin),
                        label = "最长单次专注",
                        modifier = Modifier.weight(1f)
                    )
                    RecordCell(
                        value = records.bestDayLabel.ifEmpty { "--" },
                        label = "最佳单日 · ${TimeUtils.formatHours(records.bestDayMin)}",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecordCell(
                        value = TimeUtils.formatHours(records.avgPerDayMin),
                        label = "日均净学习 · 按专注天",
                        modifier = Modifier.weight(1f)
                    )
                    RecordCell(
                        value = "${records.activeDays} 天",
                        label = "累计专注 · ${records.totalSessions} 个番茄",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordCell(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
