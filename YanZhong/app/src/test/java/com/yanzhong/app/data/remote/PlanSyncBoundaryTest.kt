package com.yanzhong.app.data.remote

import com.yanzhong.app.data.db.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSyncBoundaryTest {
    @Test
    fun dirtyPushSelectionExcludesPlanProjections() {
        val tasks = listOf(
            task(id = 1, dirty = true, planId = null),
            task(id = 2, dirty = true, planId = 99L),
            task(id = 3, dirty = false, planId = null),
        )

        val pushable = tasks.filter { it.dirty && it.planId == null }

        assertEquals(listOf(1L), pushable.map { it.id })
    }

    @Test
    fun pullDeleteDoesNotApplyToPlanProjectionWithSameClientGuid() {
        val local = task(id = 7, dirty = false, planId = 99L, clientGuid = "same-guid")
        val remote = TaskSyncRow(
            clientGuid = "same-guid",
            title = "云端普通任务",
            priority = 1,
            pomodoroEstimate = 1,
            completedPomodoros = 0,
            repeatRule = 0,
            repeatDays = 0,
            status = 0,
            postponeCount = 0,
            createdAt = 1,
            updatedAt = 2,
            isDeleted = true,
        )

        val shouldDelete = remote.isDeleted && local.planId == null

        assertTrue(!shouldDelete)
        assertEquals(99L, local.planId)
    }

    private fun task(
        id: Long,
        dirty: Boolean,
        planId: Long?,
        clientGuid: String = "guid-$id",
    ) = TaskEntity(
        id = id,
        subjectId = 1,
        title = "任务$id",
        accountGuid = planId?.let { "account-a" },
        planId = planId,
        planItemId = planId?.plus(id),
        clientGuid = clientGuid,
        dirty = dirty,
    )
}
