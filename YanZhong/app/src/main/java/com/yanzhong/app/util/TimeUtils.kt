package com.yanzhong.app.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeUtils {
    private val zone: ZoneId = ZoneId.systemDefault()

    fun now(): Long = System.currentTimeMillis()

    /**
     * 毫秒时间戳 → 本地日期。
     *
     * 必须走 `Instant.atZone(zone).toLocalDate()`,**不能用 `LocalDate.ofInstant(instant, zone)`**:
     * 后者是 Java 9 才加入的方法,Android 上直到 API 33 才提供。本项目 minSdk = 29,
     * 在 Android 12/12L(API 31/32)上调用它会抛
     * `NoSuchMethodError: No static method ofInstant(Instant, ZoneId)` —— 且因为它出现在
     * 设置流的映射里,进程每次启动都会直接崩溃(实测 Black Shark / Android 12)。
     * 新模拟器是 API 34 所以掩盖了这个问题。
     * `atZone().toLocalDate()` 是 Java 8 API,API 26 起原生可用,语义与 ofInstant 完全一致。
     */
    private fun dateOf(epochMilli: Long): LocalDate =
        Instant.ofEpochMilli(epochMilli).atZone(zone).toLocalDate()

    fun dayStartOf(epochMilli: Long = now()): Long =
        dateOf(epochMilli).atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayEndOf(epochMilli: Long = now()): Long =
        dateOf(epochMilli).atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()

    /** 本周周一 0 点(周为考研周视图粒度) */
    fun weekStartOf(epochMilli: Long = now()): Long {
        val date = dateOf(epochMilli)
        return date.with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun weekEndOf(epochMilli: Long = now()): Long = weekStartOf(epochMilli) + 7L * 24 * 3600 * 1000 - 1

    fun monthStartOf(epochMilli: Long = now()): Long {
        val date = dateOf(epochMilli)
        return date.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun monthEndOf(epochMilli: Long = now()): Long {
        val date = dateOf(epochMilli)
        return date.withDayOfMonth(date.lengthOfMonth()).atTime(LocalTime.MAX)
            .atZone(zone).toInstant().toEpochMilli()
    }

    fun daysAgoStart(days: Int, from: Long = now()): Long = dayStartOf(from) - days * 24L * 3600 * 1000

    /** 距目标还剩几天(向上取整的自然日差) */
    fun daysBetween(from: Long, to: Long): Long {
        val fromDate = dateOf(from)
        val toDate = dateOf(to)
        return toDate.toEpochDay() - fromDate.toEpochDay()
    }

    fun isSameDay(a: Long, b: Long): Boolean = dateOf(a) == dateOf(b)

    /** 倒计时四段:天 / 时 / 分 / 秒 */
    data class Countdown(val days: Long, val hours: Long, val minutes: Long, val seconds: Long)

    fun countdownTo(target: Long, from: Long = now()): Countdown {
        val diff = (target - from).coerceAtLeast(0)
        val totalSec = diff / 1000
        return Countdown(
            days = totalSec / 86400,
            hours = totalSec % 86400 / 3600,
            minutes = totalSec % 3600 / 60,
            seconds = totalSec % 60
        )
    }

    /** mm:ss 计时显示 */
    fun mmss(millis: Long): String {
        val totalSec = (millis / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    private val mdFormat = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val ymdFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINESE)
    private val ymdHmFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINESE)
    private val hmFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINESE)
    private val monthKeyFormat = DateTimeFormatter.ofPattern("yyyy-MM", Locale.CHINA)

    /** 月份键(yyyy-MM):紧急退出配额的计费周期 */
    fun monthKeyOf(epochMilli: Long = now()): String =
        dateOf(epochMilli).format(monthKeyFormat)

    fun formatMonthDay(epochMilli: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), zone).format(mdFormat)

    fun formatYmd(epochMilli: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), zone).format(ymdFormat)

    fun formatYmdHm(epochMilli: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), zone).format(ymdHmFormat)

    fun formatHm(epochMilli: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), zone).format(hmFormat)

    private val weekNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 截止时间的人性化显示:今天/明天/周X/日期;过期加「已过期」标记由调用方处理 */
    fun dueLabel(dueAt: Long): String {
        val dueDate = dateOf(dueAt)
        val today = LocalDate.now(zone)
        return when (dueDate) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            today.minusDays(1) -> "昨天"
            else -> {
                val dowIdx = dueDate.dayOfWeek.value - 1
                if (dueDate.isAfter(today) && dueDate.toEpochDay() - today.toEpochDay() < 7) {
                    weekNames[dowIdx]
                } else {
                    dueDate.format(mdFormat)
                }
            }
        }
    }

    fun weekdayName(epochMilli: Long): String {
        val date = dateOf(epochMilli)
        return weekNames[date.dayOfWeek.value - 1]
    }

    /** 周一=0 … 周日=6,与任务 repeatDays 位定义(bit0=周一)一致 */
    fun dayOfWeekIndex(epochMilli: Long): Int = dateOf(epochMilli).dayOfWeek.value - 1

    /** 长时长格式化:5h 40m / 42m / 3h */
    fun formatHours(minutes: Int): String {
        if (minutes <= 0) return "0m"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}
