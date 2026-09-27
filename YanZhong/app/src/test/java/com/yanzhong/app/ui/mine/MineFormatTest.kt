package com.yanzhong.app.ui.mine

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MineFormatTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val base = 1_800_000_000_000L // 固定基准时刻(2027-01-15 16:00 Asia/Shanghai)

    @Test fun syncTime_neverSynced() {
        assertEquals("尚未同步", formatSyncTime(0L, base, zone))
    }

    @Test fun syncTime_justNow() {
        assertEquals("刚刚", formatSyncTime(base - 30_000L, base, zone))
    }

    @Test fun syncTime_minutesAgo() {
        assertEquals("5 分钟前", formatSyncTime(base - 5 * 60_000L, base, zone))
    }

    @Test fun syncTime_sameDayUsesToday() {
        val sync = base - 3 * 3_600_000L
        val text = formatSyncTime(sync, base, zone)
        assertTrue("应以 '今天 ' 开头: $text", text.startsWith("今天 "))
    }

    @Test fun syncTime_crossDayUsesMonthDay() {
        val sync = base - 48 * 3_600_000L
        val text = formatSyncTime(sync, base, zone)
        assertTrue("无 '今天' 前缀: $text", !text.startsWith("今天 "))
        assertTrue("含 '月' 与 '日': $text", text.contains("月") && text.contains("日"))
    }

    @Test fun subjectSubtitle_empty() {
        assertEquals("尚未设置科目", formatSubjectSubtitle(emptyList()))
        assertEquals("尚未设置科目", formatSubjectSubtitle(listOf("  ", "")))
    }

    @Test fun subjectSubtitle_fourOrFewer() {
        assertEquals("数学", formatSubjectSubtitle(listOf("数学")))
        assertEquals("数学 + 英语 + 政治 + 专业课",
            formatSubjectSubtitle(listOf("数学", "英语", "政治", "专业课")))
    }

    @Test fun subjectSubtitle_moreThanFour() {
        assertEquals("数学 + 英语 + 政治 + 专业课 等 5 科",
            formatSubjectSubtitle(listOf("数学", "英语", "政治", "专业课", "第二外语")))
    }

    @Test fun syncError_mapping() {
        assertEquals("同步遇到问题", readableSyncError(null))
        assertEquals("同步遇到问题", readableSyncError(""))
        assertEquals("登录已过期，请重新登录", readableSyncError("HTTP 401 Unauthorized"))
        assertEquals("网络较慢，稍后会自动重试", readableSyncError("SocketTimeoutException: timeout"))
        assertEquals("网络不可用，稍后会自动重试",
            readableSyncError("java.net.UnknownHostException: Unable to resolve host \"api\""))
        assertEquals("网络不可用，稍后会自动重试",
            readableSyncError("Failed to connect to /10.0.2.2:8080"))
        assertEquals("服务器暂时不可用", readableSyncError("HTTP 503 Service Unavailable"))
    }
}
