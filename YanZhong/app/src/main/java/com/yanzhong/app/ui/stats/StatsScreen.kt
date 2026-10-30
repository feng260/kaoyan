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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.EmptyState
import com.yanzhong.app.ui.theme.InfoAction
import com.yanzhong.app.ui.theme.MetricTile
import com.yanzhong.app.ui.theme.MetricTone
import com.yanzhong.app.ui.theme.PageHeader
import com.yanzhong.app.ui.theme.SectionHeader
import com.yanzhong.app.ui.theme.SegmentedChips
import com.yanzhong.app.ui.theme.StatBanner
import com.yanzhong.app.util.TimeUtils

/** 统计口径:正文不展开,只在 InfoAction 弹窗里给出完整定义 */
private const val METHOD_NOTE =
    "任务完成率 = 已完成任务 / 计划任务；专注时长只统计单次满 1 分钟的会话；" +
        "长期图表窗口固定为近 14 天 / 近 30 天 / 近 15 周，不随上方筛选变化。"

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
            item(key = "header") {
                Column {
                    PageHeader(title = "统计", meta = "数据全部来自本机")
                    Spacer(Modifier.height(12.dp))
                    SegmentedChips(
                        options = StatsRange.entries.map { it.label },
                        selectedIndex = StatsRange.entries.indexOf(state.range),
                        onSelect = { vm.selectRange(StatsRange.entries[it]) }
                    )
                }
            }

            item(key = "kpi") { KpiBanner(state) }

            item(key = "subject") {
                AppCard {
                    Text(
                        "科目投入 · ${state.range.label}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.perSubject.isEmpty()) {
                        EmptyState(
                            icon = AppIcons.Timer,
                            title = "这个范围还没有专注记录",
                            hint = "完成一个番茄后开始统计",
                            compact = true
                        )
                    } else {
                        SubjectRingChart(perSubject = state.perSubject, subjects = state.subjects)
                    }
                }
            }

            item(key = "trend-heading") {
                Column {
                    SectionHeader(
                        title = "长期趋势",
                        meta = "近 14 天 / 近 30 天 / 近 15 周",
                        action = {
                            InfoAction(title = "统计口径", message = METHOD_NOTE)
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "以下窗口固定，不随上方筛选变化",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "trend") {
                ChartCard(title = "净专注趋势 · 近 14 天") {
                    TrendLineChart(trend = state.trend)
                }
            }

            item(key = "hourly") {
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

/** 渐变 KPI 横幅(参考番茄ToDo 统计页累计卡):四项一行,数字克制、单位/标签小字 */
@Composable
private fun KpiBanner(state: StatsUiState) {
    Column {
        val (focusNum, focusUnit) = splitValueUnit(TimeUtils.formatHours(state.focusMin))
        StatBanner(
            title = "${state.range.label}概览",
            action = {
                InfoAction(
                    title = "统计口径",
                    message = METHOD_NOTE,
                    tint = Color.White.copy(alpha = 0.9f)
                )
            }
        ) {
            Row(Modifier.fillMaxWidth()) {
                MetricTile(focusNum, "净专注", Modifier.weight(1f), unit = focusUnit, tone = MetricTone.ON_GRADIENT)
                MetricTile("${state.pomodoroCount}", "番茄", Modifier.weight(1f), unit = "个", tone = MetricTone.ON_GRADIENT)
                MetricTile(rateNumFirst(state.taskRateLabel), "任务完成率", Modifier.weight(1f), unit = rateUnitRest(state.taskRateLabel), tone = MetricTone.ON_GRADIENT)
                MetricTile("${state.streak}", "连续打卡", Modifier.weight(1f), unit = "天", tone = MetricTone.ON_GRADIENT)
            }
        }
        // 环比与专注天数:只在「本周 / 本月」下出现,替代原周报/月报卡
        if (state.range == StatsRange.WEEK) {
            Spacer(Modifier.height(8.dp))
            Text(
                "较上周 ${deltaLabel(state.focusMin - state.lastWeekMin)} · 专注 ${state.weekFocusDays} 天",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (state.range == StatsRange.MONTH) {
            Spacer(Modifier.height(8.dp))
            Text(
                "较上月 ${deltaLabel(state.focusMin - state.lastMonthMin)} · 专注 ${state.monthFocusDays} 天",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 环比标签:正数 ▲、负数 ▼、持平 */
private fun deltaLabel(delta: Int): String = when {
    delta > 0 -> "▲ ${TimeUtils.formatHours(delta)}"
    delta < 0 -> "▼ ${TimeUtils.formatHours(-delta)}"
    else -> "持平"
}

/** 「83%」→(「83」,「%」);无数字时原样返回,避免空数值列 */
private fun rateNumFirst(raw: String): String {
    val idx = raw.indexOfFirst { !it.isDigit() && it != '.' }
    return if (idx <= 0) raw else raw.substring(0, idx)
}

private fun rateUnitRest(raw: String): String {
    val idx = raw.indexOfFirst { !it.isDigit() && it != '.' }
    return if (idx <= 0) "" else raw.substring(idx).trim()
}

/** 「5.2h」→(「5.2」,「h」)、「12 个」→(「12」,「个」):数值/单位分层排版用 */
private fun splitValueUnit(raw: String): Pair<String, String> {
    val idx = raw.indexOfFirst { !it.isDigit() && it != '.' }
    if (idx <= 0) return raw to ""
    return raw.substring(0, idx) to raw.substring(idx).trim()
}

/** 图表容器卡:统一 AppCard + 卡片标题,内容间距由卡内 12dp 提供 */
@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    AppCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** 累计纪录卡:最长单次 / 最佳单日 / 日均 / 累计天数(参考番茄ToDo 累计统计) */
@Composable
private fun RecordsCard(records: FocusRecords) {
    AppCard {
        Text("累计纪录", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            MetricTile(TimeUtils.formatHours(records.longestSessionMin), "最长单次", Modifier.weight(1f), compact = true)
            MetricTile(TimeUtils.formatHours(records.bestDayMin), "最佳单日", Modifier.weight(1f), compact = true)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            MetricTile(TimeUtils.formatHours(records.avgPerDayMin), "日均", Modifier.weight(1f), compact = true)
            MetricTile("${records.activeDays}", "累计天数", Modifier.weight(1f), unit = "天", compact = true)
        }
    }
}
