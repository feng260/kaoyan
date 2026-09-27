package com.yanzhong.app.ui.mine

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 同步时间文案:尚未同步 / 刚刚 / N 分钟前 / 今天 HH:mm / M月d日 HH:mm */
fun formatSyncTime(lastSyncAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (lastSyncAt <= 0L) return "尚未同步"
    val diff = now - lastSyncAt
    if (diff in 0 until 60_000L) return "刚刚"
    if (diff in 0 until 3_600_000L) return "${diff / 60_000L} 分钟前"
    val syncTime = Instant.ofEpochMilli(lastSyncAt).atZone(zone)
    val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = syncTime.format(DateTimeFormatter.ofPattern("HH:mm"))
    return if (syncTime.toLocalDate() == nowDate) "今天 $clock"
    else "${syncTime.monthValue}月${syncTime.dayOfMonth}日 $clock"
}

/** 头部副标题:最多 4 个科目名以 " + " 连接,超出追加 "等 N 科";无科目给占位文案 */
fun formatSubjectSubtitle(names: List<String>): String {
    val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return "尚未设置科目"
    val head = cleaned.take(4).joinToString(" + ")
    return if (cleaned.size > 4) "$head 等 ${cleaned.size} 科" else head
}

/** 同步失败的可理解措辞:不向普通用户暴露 HTTP 状态码与异常类名 */
fun readableSyncError(raw: String?): String {
    val msg = raw.orEmpty()
    if (msg.isBlank()) return "同步遇到问题"
    val lower = msg.lowercase()
    return when {
        "401" in msg || "unauthorized" in lower || "登录" in msg -> "登录已过期，请重新登录"
        "timeout" in lower || "超时" in msg -> "网络较慢，稍后会自动重试"
        "unknownhost" in lower || "unable to resolve" in lower || "failed to connect" in lower ||
            "connect" in lower -> "网络不可用，稍后会自动重试"
        "500" in msg || "502" in msg || "503" in msg || "server" in lower -> "服务器暂时不可用"
        else -> "同步遇到问题"
    }
}
