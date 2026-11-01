package com.yanzhong.app.ui.home

import com.yanzhong.app.data.remote.CheckupResp

object CheckupFormat {
    fun headline(c: CheckupResp): String = when {
        c.overdueCount > 0 -> "有 ${c.overdueCount} 项任务没跟上"
        c.overloadedDays.isNotEmpty() -> "接下来 ${c.overloadedDays.size} 天排得有点满"
        else -> "计划节奏正常"
    }

    fun rate(c: CheckupResp): String =
        if (c.weekPlanTotal <= 0) "本周还没有计划任务"
        else "本周计划完成 ${c.weekPlanDone}/${c.weekPlanTotal}"

    fun trend(c: CheckupResp): String = when {
        c.lastWeekFocusMinutes > 0 && c.weekFocusMinutes == 0L ->
            "上周专注 ${c.lastWeekFocusMinutes} 分钟，本周等你开始"
        c.lastWeekFocusMinutes == 0L && c.weekFocusMinutes == 0L -> "本周已开始积累专注记录"
        c.weekActiveDays <= 0 -> "本周已专注 ${c.weekFocusMinutes} 分钟"
        else -> "本周 ${c.weekActiveDays} 天有专注，共 ${c.weekFocusMinutes} 分钟"
    }

    fun load(c: CheckupResp): String =
        if (c.next7dCapacityMinutes <= 0) "未来 7 天已排 ${c.next7dPlannedMinutes} 分钟 · 未确认空闲时段"
        else "未来 7 天已排 ${c.next7dPlannedMinutes} / ${c.next7dCapacityMinutes} 分钟"

    fun compare(c: CheckupResp): String {
        val last = c.lastWeekFocusMinutes
        if (last <= 0) {
            return if (c.weekFocusMinutes > 0) "上周没有记录，本周已专注 ${c.weekFocusMinutes} 分钟"
            else "本周还没有专注记录"
        }
        val diff = c.weekFocusMinutes - last
        if (diff == 0L) return "专注与上周持平"
        val pct = Math.round(Math.abs(diff) * 100.0 / last).toInt()
        return if (diff > 0) "专注比上周多 $pct%" else "专注比上周少 $pct%"
    }

    /** 面谈预填:第一人称、说清楚现状和诉求,用户点开就能直接发送 */
    fun adjustPrefill(c: CheckupResp): String = when {
        c.overdueCount > 0 ->
            "我最近有 ${c.overdueCount} 项计划没完成（共 ${c.behindMinutes} 分钟），帮我重新安排到接下来的空闲时间，别把每天排太满。"
        c.overloadedDays.isNotEmpty() ->
            "未来 7 天有 ${c.overloadedDays.size} 天超出可用时间，帮我减压并重新分配。"
        else -> "帮我看看未来一周的计划是否合理，需要的话微调。"
    }
}
