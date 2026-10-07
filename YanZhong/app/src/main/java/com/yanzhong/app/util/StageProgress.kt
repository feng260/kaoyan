package com.yanzhong.app.util

import com.yanzhong.app.data.remote.PlanDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 从生效计划 stages 派生的阶段进度(取代旧的硬编码五阶段):
 * 计划是什么阶段结构,首页阶段条就展示什么 —— 考研/考公/法考/专升本各有各的阶段。
 */
data class StageInfo(
    /** 当前阶段名 */
    val stageName: String,
    /** 阶段条副标题,如「第 2/5 阶段」 */
    val slogan: String,
    /** 当前阶段第几天(从 1 起) */
    val dayIdx: Long,
    /** 当前阶段总天数 */
    val totalDays: Long,
    /** 距当前阶段结束还有几天(0 = 收官) */
    val daysToEnd: Long,
    /** 下一阶段名;当前已是末段时为 null */
    val nextStageName: String?,
    /** 当前阶段主线(L1 策略轮产出,如「行测五大模块分块入门,申论先立材料意识」);无则 null */
    val strategy: String?,
    /** 各阶段天数(顺序同计划),供比例条按占比渲染 */
    val stageDays: List<Long>,
    /** 当前阶段下标 */
    val currentIndex: Int
)

/**
 * 按 now 所在日期在计划的阶段区间中定位当前阶段:
 * - stages 为空或今天早于首段 → null(UI 隐藏阶段条);
 * - 今天晚于末段(考完了)或落在阶段空洞 → 钳制到 start ≤ 今天的最后一段。
 */
fun PlanDto.stageInfo(now: Long): StageInfo? {
    val sorted = stages.sortedBy { it.sortOrder }
    if (sorted.isEmpty()) return null
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val spans = sorted.map { stage ->
        val start = runCatching { LocalDate.parse(stage.startDate) }.getOrNull()
        val end = runCatching { LocalDate.parse(stage.endDate) }.getOrNull()
        if (start == null || end == null || end.isBefore(start)) null else start to end
    }
    if (spans.any { it == null }) return null
    val valid = spans.filterNotNull()

    var index = valid.indexOfFirst { (start, end) -> !today.isBefore(start) && !today.isAfter(end) }
    if (index < 0) {
        if (today.isBefore(valid.first().first)) return null
        index = valid.indexOfLast { (start, _) -> !today.isBefore(start) }
        if (index < 0) return null
    }
    val (start, end) = valid[index]
    val stageTotal = ChronoUnit.DAYS.between(start, end) + 1
    return StageInfo(
        stageName = sorted[index].name,
        slogan = "第 ${index + 1}/${valid.size} 阶段",
        dayIdx = (ChronoUnit.DAYS.between(start, today) + 1).coerceIn(1, stageTotal),
        totalDays = stageTotal,
        daysToEnd = ChronoUnit.DAYS.between(today, end).coerceAtLeast(0),
        nextStageName = sorted.getOrNull(index + 1)?.name,
        strategy = sorted[index].strategy,
        stageDays = valid.map { (s, e) -> ChronoUnit.DAYS.between(s, e) + 1 },
        currentIndex = index
    )
}
