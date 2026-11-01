package com.yanzhong.app.ui.cloud

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object CloudFormat {
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val monthDay = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)

    fun syncMoment(lastSyncAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (lastSyncAt <= 0L) return "尚未同步"
        val delta = (now - lastSyncAt).coerceAtLeast(0L)
        if (delta < 60_000L) return "刚刚"
        if (delta < 3_600_000L) return "${delta / 60_000L} 分钟前"
        if (delta < 86_400_000L) return "${delta / 3_600_000L} 小时前"
        val last = Instant.ofEpochMilli(lastSyncAt).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (last.toLocalDate() == today.minusDays(1)) "昨天 ${last.format(clock)}"
        else last.format(monthDay)
    }

    fun clockAt(epochMilli: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMilli).atZone(zone).format(clock)
}
