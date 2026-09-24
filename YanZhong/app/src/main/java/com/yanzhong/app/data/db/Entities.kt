package com.yanzhong.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 倒计时节点类型:初试/单科/复试/报名确认/模考/自定义 */
object NodeType {
    const val EXAM = 0
    const val SUBJECT_EXAM = 1
    const val RETEST = 2
    const val ENROLL = 3
    const val MOCK = 4
    const val CUSTOM = 5

    fun label(type: Int): String = when (type) {
        EXAM -> "初试"
        SUBJECT_EXAM -> "单科考试"
        RETEST -> "复试"
        ENROLL -> "报名确认"
        MOCK -> "模考"
        else -> "自定义"
    }
}

/** 任务状态:待办/完成/顺延/放弃 */
object TaskStatus {
    const val TODO = 0
    const val DONE = 1
    const val POSTPONED = 2
    const val DROPPED = 3
}

/** 重复规则 */
object RepeatRule {
    const val NONE = 0
    const val DAILY = 1
    const val WEEKLY = 2
}

@Serializable
@Entity(tableName = "subject")
data class SubjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Long,
    val sort: Int,
    val archived: Boolean = false,
    /** 云同步:行稳定标识(36 位 UUID),跨设备幂等 upsert 的命中键 */
    @ColumnInfo(defaultValue = "") val clientGuid: String = "",
    /** 云同步:最后修改毫秒时间戳,LWW 冲突裁决 */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /** 云同步:本地有未推送改动(绕开时钟漂移的推送标记) */
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true
)

@Serializable
@Entity(
    tableName = "countdown_node",
    indices = [Index("pinned")]
)
data class CountdownNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: Int = NodeType.EXAM,
    val targetAt: Long,
    val pinned: Boolean = false,
    val sort: Int = 0,
    @ColumnInfo(defaultValue = "") val clientGuid: String = "",
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true
)

@Serializable
@Entity(
    tableName = "task",
    indices = [Index("subjectId"), Index("dueAt"), Index("status")]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val title: String,
    val priority: Int = 1, // 0高 1中 2低
    val pomodoroEstimate: Int = 1,
    /** 已完成番茄数:每完成一个关联番茄自动 +1(参考番茄ToDo 任务预估) */
    val completedPomodoros: Int = 0,
    val dueAt: Long? = null,
    val repeatRule: Int = RepeatRule.NONE,
    val repeatDays: Int = 0, // bit0=周一 … bit6=周日,仅 WEEKLY 生效
    val status: Int = TaskStatus.TODO,
    val postponeCount: Int = 0,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val note: String = "",
    /** 重复模板完成时生成的当日 DONE 实例指向其模板 id,用于「今日已完成」过滤与取消勾选回滚 */
    val repeatParentId: Long? = null,
    @ColumnInfo(defaultValue = "") val clientGuid: String = "",
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true
)

/** 统计的唯一事实来源:一次番茄 = 一行记录 */
@Serializable
@Entity(
    tableName = "pomodoro_session",
    indices = [Index("subjectId"), Index("startedAt"), Index("taskId")]
)
data class PomodoroSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long?,
    val subjectId: Long?,
    val startedAt: Long,
    val endedAt: Long,
    val durationMin: Int,
    val valid: Boolean = true,
    val planName: String = "",
    /** 放弃原因(被打断/临时有事/不想做了等),仅放弃的专注记录,valid=false(参考番茄ToDo) */
    val abandonReason: String? = null,
    @ColumnInfo(defaultValue = "") val clientGuid: String = "",
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true
)

/** 每日复盘三问(作战计划:每个日模板 21:30 雷打不动):一天一行,epochDay 为键 */
@Serializable
@Entity(tableName = "daily_review")
data class DailyReviewEntity(
    @PrimaryKey val epochDay: Int,
    /** Q1 今天完成了什么? */
    val q1Done: String = "",
    /** Q2 哪个知识点最模糊? */
    val q2Weak: String = "",
    /** Q3 明天最重要的 1 件事?(次日首页展示,睡前定好早起不犹豫) */
    val q3Tomorrow: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 云同步:本地有未推送改动(复盘表以 epochDay 为自然键,无需 clientGuid) */
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true
)

/** 周复盘(作战计划:周日晚 30min 雷打不动):一周一行,周一 epochDay 为键 */
@Serializable
@Entity(tableName = "weekly_review")
data class WeeklyReviewEntity(
    @PrimaryKey val weekStartEpochDay: Int,
    /** 本周薄弱点 */
    val weakPoints: String = "",
    /** 下周 3 件要事 */
    val nextWeekTop1: String = "",
    val nextWeekTop2: String = "",
    val nextWeekTop3: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true
)

/** 月复盘(作战计划:月底一次):一月一行,月初(1 号)epochDay 为键 */
@Serializable
@Entity(tableName = "monthly_review")
data class MonthlyReviewEntity(
    @PrimaryKey val monthStartEpochDay: Int,
    /** 本月总结/复盘 */
    val summary: String = "",
    /** 下月 3 件要事 */
    val nextMonthTop1: String = "",
    val nextMonthTop2: String = "",
    val nextMonthTop3: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true
)

/**
 * 云同步墓碑:本地硬删的行在此留痕,推送时作为 isDeleted=true 行上云,
 * 其他设备拉取后删除对应行,防止「删端复现」。
 */
@Entity(tableName = "sync_tombstone")
data class SyncTombstoneEntity(
    @PrimaryKey val clientGuid: String,
    /** subjects / countdownNodes / tasks / sessions */
    val resource: String,
    val deletedAt: Long
)
