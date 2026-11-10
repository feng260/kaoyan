package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.UsageSummaryDto
import java.util.Locale
import java.util.TimeZone

/** AI 用量展示:千分位、不缩写;没有数据时返回 null,让界面整行隐藏 */
object AiUsageFormat {
    fun tokens(value: Long): String = String.format(Locale.US, "%,d", value)

    fun statusLine(turn: UsageSummaryDto?, today: UsageSummaryDto?): String? {
        val parts = buildList {
            turn?.let { add("本轮消耗 ${tokens(it.totalTokens)} tokens") }
            today?.let { add("今日 ${tokens(it.totalTokens)}") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun draftLine(job: UsageSummaryDto?): String? =
        job?.takeIf { it.totalTokens > 0 }?.let { "本次生成共消耗 ${tokens(it.totalTokens)} tokens" }

    /** 服务端功能标签 → 用户能看懂的模块名 */
    fun featureLabel(feature: String): String = when (feature) {
        "interview" -> "备考面谈"
        "adjust_intent" -> "行程理解"
        "adjust_plan" -> "行程重排"
        "plan_generate" -> "计划骨架"
        "plan_refine" -> "阶段细化"
        "plan_critic" -> "计划评审"
        "plan_document" -> "长文档生成"
        "timetable_ocr" -> "课表识别"
        else -> feature
    }
}

/** 设备当前时区偏移(分钟,东八区=480):今日/本月边界按设备时间算 */
internal fun deviceUtcOffsetMinutes(now: Long = System.currentTimeMillis()): Int =
    TimeZone.getDefault().getOffset(now) / 60_000
