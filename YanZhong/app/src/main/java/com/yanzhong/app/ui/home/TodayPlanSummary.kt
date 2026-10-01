package com.yanzhong.app.ui.home

import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.db.TaskStatus
import java.time.Instant
import java.time.ZoneId

/** 首页当天服务端计划任务摘要。 */
data class TodayPlanSummary(
    val isServerPlan: Boolean = false,
    val tasks: List<TaskEntity> = emptyList(),
    val totalMinutes: Int = 0,
    val completedMinutes: Int = 0,
    val remainingMinutes: Int = 0,
) {
    val completedCount: Int
        get() = tasks.count { it.status == TaskStatus.DONE }
}

fun summarizeTodayPlanTasks(
    tasks: List<TaskEntity>,
    now: Long,
    focusMinutes: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): TodayPlanSummary {
    val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val todayPlanTasks = tasks
        .filter { task ->
            task.planId != null &&
                task.dueAt != null &&
                Instant.ofEpochMilli(task.dueAt).atZone(zone).toLocalDate() == date
        }
        .sortedWith(compareBy<TaskEntity> { it.dueAt }.thenBy { it.id })

    val total = todayPlanTasks.sumOf { it.pomodoroEstimate.coerceAtLeast(0) * focusMinutes.coerceAtLeast(0) }
    val completed = todayPlanTasks
        .filter { it.status == TaskStatus.DONE }
        .sumOf { it.pomodoroEstimate.coerceAtLeast(0) * focusMinutes.coerceAtLeast(0) }

    return TodayPlanSummary(
        isServerPlan = todayPlanTasks.isNotEmpty(),
        tasks = todayPlanTasks,
        totalMinutes = total,
        completedMinutes = completed,
        remainingMinutes = (total - completed).coerceAtLeast(0),
    )
}
