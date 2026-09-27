package com.yanzhong.app.ui.home

import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.db.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayPlanSummaryTest {
    @Test
    fun serverPlanSummaryCountsOnlyTodayPlanTasks() {
        val today = 1_700_000_000_000L
        val tasks = listOf(
            task(id = 1, dueAt = today, planId = 10, pomodoros = 2),
            task(id = 2, dueAt = today, planId = 10, pomodoros = 1, status = TaskStatus.DONE),
            task(id = 3, dueAt = today, planId = null, pomodoros = 4),
            task(id = 4, dueAt = today - 86_400_000L, planId = 10, pomodoros = 3),
        )

        val summary = summarizeTodayPlanTasks(tasks, today, focusMinutes = 25)

        assertTrue(summary.isServerPlan)
        assertEquals(listOf(1L, 2L), summary.tasks.map { it.id })
        assertEquals(75, summary.totalMinutes)
        assertEquals(25, summary.completedMinutes)
        assertEquals(50, summary.remainingMinutes)
    }

    @Test
    fun noTodayServerPlanUsesOfflineFallback() {
        val summary = summarizeTodayPlanTasks(
            tasks = listOf(task(id = 1, dueAt = 1_700_000_000_000L, planId = null)),
            now = 1_700_000_000_000L,
            focusMinutes = 25,
        )

        assertFalse(summary.isServerPlan)
        assertTrue(summary.tasks.isEmpty())
        assertEquals(0, summary.totalMinutes)
        assertEquals(0, summary.remainingMinutes)
    }

    private fun task(
        id: Long,
        dueAt: Long,
        planId: Long?,
        pomodoros: Int = 1,
        status: Int = TaskStatus.TODO,
    ) = TaskEntity(
        id = id,
        subjectId = 1,
        title = "任务$id",
        pomodoroEstimate = pomodoros,
        status = status,
        dueAt = dueAt,
        accountGuid = planId?.let { "account-a" },
        planId = planId,
        planItemId = planId?.plus(id),
    )
}
