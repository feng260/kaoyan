package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.PlanItemDto
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val planTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val looseTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

/** 当日单项:把 startTime + minutes 展开成精确起止时刻 */
internal data class AgendaItem(
    val item: PlanItemDto,
    val start: LocalTime?,
    val end: LocalTime?
) {
    val timeRange: String? get() = formatRange(start, end)
}

/** 当日一个时段(上午/下午/晚上)及其时间跨度、任务总时长与段内空档 */
internal data class AgendaSlot(
    val label: String,
    val items: List<AgendaItem>,
    val start: LocalTime?,
    val end: LocalTime?,
    val minutes: Int,
    /** 跨度减去任务总时长;有任务缺时间时无法计算,为 null */
    val breakMinutes: Int?
) {
    val rangeLabel: String? get() = formatRange(start, end)
}

/** 当日完整日程概览 */
internal data class DayAgenda(
    val slots: List<AgendaSlot>,
    val totalMinutes: Int,
    val start: LocalTime?,
    val end: LocalTime?
) {
    val hasTimes: Boolean get() = start != null
    val rangeLabel: String? get() = formatRange(start, end)
    val scheduledSlotCount: Int get() = slots.count { it.start != null }
}

private enum class AgendaPeriod(val label: String) {
    MORNING("上午"),
    AFTERNOON("下午"),
    EVENING("晚上"),
    UNSCHEDULED("未排时段")
}

internal fun buildDayAgenda(items: List<PlanItemDto>): DayAgenda {
    val grouped = AgendaPeriod.values().associateWith { mutableListOf<AgendaItem>() }
    items.forEach { item ->
        val start = parsePlanStart(item.startTime)
        val end = if (item.minutes > 0) start?.plusMinutes(item.minutes.toLong()) else null
        grouped.getValue(periodOf(item, start)).add(AgendaItem(item, start, end))
    }
    val slots = AgendaPeriod.values().mapNotNull { period ->
        val entries = grouped.getValue(period)
        if (entries.isEmpty()) return@mapNotNull null
        val sorted = entries.sortedWith(
            compareBy<AgendaItem> { it.start == null }
                .thenBy(nullsLast()) { it.start }
                .thenBy { it.item.sortOrder }
                .thenBy { it.item.id }
        )
        val minutes = sorted.sumOf { it.item.minutes.coerceAtLeast(0) }
        val start = sorted.mapNotNull { it.start }.minOrNull()
        val end = sorted.mapNotNull { it.end }.maxOrNull()
        val breakMinutes = slotBreakMinutes(sorted, start, end, minutes)
        AgendaSlot(period.label, sorted, start, end ?: start, minutes, breakMinutes)
    }
    val start = slots.mapNotNull { it.start }.minOrNull()
    val end = slots.mapNotNull { it.end }.maxOrNull()
    return DayAgenda(
        slots = slots,
        totalMinutes = items.sumOf { it.minutes.coerceAtLeast(0) },
        start = start,
        end = end
    )
}

/** 只有全部任务都有起止时刻时,空档才是可信数字 */
private fun slotBreakMinutes(
    items: List<AgendaItem>,
    start: LocalTime?,
    end: LocalTime?,
    minutes: Int
): Int? {
    if (start == null || end == null) return null
    if (items.any { it.start == null || it.end == null }) return null
    val span = end.toSecondOfDay() - start.toSecondOfDay()
    if (span < 0) return null
    return (span / 60 - minutes).coerceAtLeast(0)
}

internal fun parsePlanStart(value: String?): LocalTime? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching { LocalTime.parse(text) }.getOrNull()
        ?: runCatching { LocalTime.parse(text, looseTimeFormatter) }.getOrNull()
}

private fun periodOf(item: PlanItemDto, start: LocalTime?): AgendaPeriod {
    when (item.slot?.trim()?.lowercase()) {
        "morning" -> return AgendaPeriod.MORNING
        "afternoon" -> return AgendaPeriod.AFTERNOON
        "evening" -> return AgendaPeriod.EVENING
    }
    return when {
        start == null -> AgendaPeriod.UNSCHEDULED
        start.hour < 12 -> AgendaPeriod.MORNING
        start.hour < 18 -> AgendaPeriod.AFTERNOON
        else -> AgendaPeriod.EVENING
    }
}

private fun formatRange(start: LocalTime?, end: LocalTime?): String? = when {
    start == null -> null
    end == null || end == start -> start.format(planTimeFormatter)
    else -> "${start.format(planTimeFormatter)}–${end.format(planTimeFormatter)}"
}

internal fun formatMinutesCn(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val hours = safe / 60
    val rest = safe % 60
    return when {
        hours == 0 -> "$rest 分钟"
        rest == 0 -> "$hours 小时"
        else -> "$hours 小时 $rest 分钟"
    }
}

private val weekdayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

internal fun weekdayLabel(date: LocalDate): String = weekdayNames[date.dayOfWeek.value - 1]
