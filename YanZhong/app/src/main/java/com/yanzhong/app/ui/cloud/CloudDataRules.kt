package com.yanzhong.app.ui.cloud

import com.yanzhong.app.data.remote.BackupDownload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object CloudDataRules {
    /** 服务端 body 上限 8MB,留出余量给 JSON 包装 */
    const val MAX_BACKUP_BYTES = 7_500_000
    const val RESTORE_CONFIRM_WORD = "恢复"

    /** 解析失败时给用户看的原因,不暴露 kotlinx.serialization 的内部报错 */
    const val INVALID_BACKUP_MESSAGE = "备份文件格式不正确"

    private val json = Json { ignoreUnknownKeys = true }

    fun validateSize(bytes: Int): String? =
        if (bytes > MAX_BACKUP_BYTES) "备份文件超过 7.5MB，请换一个小一点的文件" else null

    fun parseBackup(text: String): Result<BackupDownload> = runCatching {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException(INVALID_BACKUP_MESSAGE)
        }
        for (key in listOf("subjects", "tasks", "sessions")) {
            val element = root[key] ?: throw IllegalArgumentException("备份包缺少 $key 列表")
            runCatching { element.jsonArray }.getOrElse {
                throw IllegalArgumentException("备份包里的 $key 不是列表")
            }
        }
        try {
            json.decodeFromString<BackupDownload>(text)
        } catch (e: Exception) {
            throw IllegalArgumentException(INVALID_BACKUP_MESSAGE)
        }
    }

    fun summary(backup: BackupDownload): String {
        val reviews = backup.dailyReviews.size + backup.weeklyReviews.size + backup.monthlyReviews.size
        return "科目 ${backup.subjects.size} · 任务 ${backup.tasks.size} · " +
            "专注 ${backup.sessions.size} 条 · 复盘 $reviews 条"
    }

    fun canConfirm(input: String): Boolean = input.trim() == RESTORE_CONFIRM_WORD
}
