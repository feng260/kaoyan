package com.yanzhong.app.data.remote

import kotlinx.serialization.Serializable

/**
 * 云同步行格式:与 server/src/modules/sync/resources.ts 的 zod schema 严格对齐。
 * 本地 Room 实体用 Long id + subjectId/taskId 引用,云端用 clientGuid + *ClientGuid 引用,
 * 由 StudyRepository 的 buildBackupUpload / restoreFromBackup 负责双向映射。
 */

@Serializable
data class SubjectSyncRow(
    val clientGuid: String,
    val name: String,
    val colorArgb: Long,
    val sort: Int,
    val archived: Boolean = false,
    val isDeleted: Boolean = false,
    val updatedAt: Long
)

@Serializable
data class CountdownNodeSyncRow(
    val clientGuid: String,
    val name: String,
    val type: Int,
    val targetAt: Long,
    val pinned: Boolean = false,
    val sort: Int = 0,
    val isDeleted: Boolean = false,
    val updatedAt: Long
)

@Serializable
data class TaskSyncRow(
    val clientGuid: String,
    val subjectClientGuid: String? = null,
    val title: String,
    val priority: Int,
    val pomodoroEstimate: Int,
    val completedPomodoros: Int,
    val dueAt: Long? = null,
    val repeatRule: Int,
    val repeatDays: Int,
    val status: Int,
    val postponeCount: Int,
    val completedAt: Long? = null,
    val createdAt: Long,
    val note: String = "",
    val isDeleted: Boolean = false,
    val updatedAt: Long
)

@Serializable
data class SessionSyncRow(
    val clientGuid: String,
    val taskClientGuid: String? = null,
    val subjectClientGuid: String? = null,
    val startedAt: Long,
    val endedAt: Long,
    val durationMin: Int,
    val valid: Boolean,
    val planName: String = "",
    val abandonReason: String? = null,
    val isDeleted: Boolean = false,
    val updatedAt: Long
)

@Serializable
data class DailyReviewSyncRow(
    val epochDay: Int,
    val q1Done: String = "",
    val q2Weak: String = "",
    val q3Tomorrow: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long
)

@Serializable
data class WeeklyReviewSyncRow(
    val weekStartEpochDay: Int,
    val weakPoints: String = "",
    val nextWeekTop1: String = "",
    val nextWeekTop2: String = "",
    val nextWeekTop3: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long
)

@Serializable
data class MonthlyReviewSyncRow(
    val monthStartEpochDay: Int,
    val summary: String = "",
    val nextMonthTop1: String = "",
    val nextMonthTop2: String = "",
    val nextMonthTop3: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long
)

/** 上传给 POST /backup/restore 的负载 */
@Serializable
data class BackupUpload(
    val subjects: List<SubjectSyncRow>,
    val countdownNodes: List<CountdownNodeSyncRow>,
    val tasks: List<TaskSyncRow>,
    val sessions: List<SessionSyncRow>,
    val dailyReviews: List<DailyReviewSyncRow> = emptyList(),
    val weeklyReviews: List<WeeklyReviewSyncRow> = emptyList(),
    val monthlyReviews: List<MonthlyReviewSyncRow> = emptyList()
)

/** GET /backup 返回的负载(客户端恢复用) */
@Serializable
data class BackupDownload(
    val version: Int = 2,
    val exportedAt: Long = 0,
    val subjects: List<SubjectSyncRow> = emptyList(),
    val countdownNodes: List<CountdownNodeSyncRow> = emptyList(),
    val tasks: List<TaskSyncRow> = emptyList(),
    val sessions: List<SessionSyncRow> = emptyList(),
    val dailyReviews: List<DailyReviewSyncRow> = emptyList(),
    val weeklyReviews: List<WeeklyReviewSyncRow> = emptyList(),
    val monthlyReviews: List<MonthlyReviewSyncRow> = emptyList()
)

/** GET /sync?since= 返回的增量变更包(行内含服务端额外字段,ignoreUnknownKeys 兼容) */
@Serializable
data class SyncChanges(
    val subjects: List<SubjectSyncRow> = emptyList(),
    val countdownNodes: List<CountdownNodeSyncRow> = emptyList(),
    val tasks: List<TaskSyncRow> = emptyList(),
    val sessions: List<SessionSyncRow> = emptyList(),
    val dailyReviews: List<DailyReviewSyncRow> = emptyList(),
    val weeklyReviews: List<WeeklyReviewSyncRow> = emptyList(),
    val monthlyReviews: List<MonthlyReviewSyncRow> = emptyList()
) {
    val totalRows: Int get() = subjects.size + countdownNodes.size + tasks.size +
        sessions.size + dailyReviews.size + weeklyReviews.size + monthlyReviews.size
}

@Serializable
data class PullResp(val changes: SyncChanges = SyncChanges(), val serverTime: Long = 0)