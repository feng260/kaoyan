package com.yanzhong.app.ui.cloud

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudFormatTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()

    @Test
    fun `同步时间按距离分档`() {
        assertEquals("尚未同步", CloudFormat.syncMoment(0, now, zone))
        assertEquals("刚刚", CloudFormat.syncMoment(now - 30_000, now, zone))
        assertEquals("5 分钟前", CloudFormat.syncMoment(now - 5 * 60_000, now, zone))
        assertEquals("2 小时前", CloudFormat.syncMoment(now - 2 * 3_600_000, now, zone))
    }

    @Test
    fun `昨天显示昨天加时刻`() {
        val last = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()
        assertEquals("昨天 20:00", CloudFormat.syncMoment(last, now, zone))
    }

    @Test
    fun `更早显示月日时刻`() {
        val last = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        assertEquals("10-30 20:00", CloudFormat.syncMoment(last, now, zone))
    }
}
