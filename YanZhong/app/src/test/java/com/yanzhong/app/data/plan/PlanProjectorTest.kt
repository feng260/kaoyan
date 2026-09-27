package com.yanzhong.app.data.plan

import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.data.remote.PlanItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanProjectorTest {
    @Test
    fun resolvesPlanSubjectByExactNameThenTag() {
        val subjects = listOf(
            SubjectEntity(id = 1L, name = "数学", colorArgb = 0L, sort = 0),
            SubjectEntity(id = 2L, name = "计算机专业课", colorArgb = 0L, sort = 1),
        )

        assertEquals(1L, resolvePlanSubjectId("数学", subjects))
        assertEquals(2L, resolvePlanSubjectId("408", subjects))
        assertEquals(0L, resolvePlanSubjectId("政治", subjects))
    }

    @Test
    fun repeatedProjectionKeepsOneTaskPerPlanItem() {
        val plan = plan(itemId = 1001L, title = "数学 · 基础梳理")
        val first = projectPlanTasks(
            existing = emptyList(),
            plan = plan,
            accountGuid = "account-a",
            subjectIds = mapOf("数学" to 7L),
            now = 100L,
        )

        val second = projectPlanTasks(
            existing = first,
            plan = plan,
            accountGuid = "account-a",
            subjectIds = mapOf("数学" to 7L),
            now = 200L,
        )

        assertEquals(1, second.size)
        assertEquals(first.single().id, second.single().id)
        assertEquals(1001L, second.single().planItemId)
    }

    @Test
    fun projectionUpdatesPlanContentWithoutOverwritingManualTask() {
        val manual = TaskEntity(subjectId = 7L, title = "手工任务")
        val plan = plan(itemId = 1001L, title = "数学 · 强化训练")

        val result = projectPlanTasks(
            existing = listOf(manual),
            plan = plan,
            accountGuid = "account-a",
            subjectIds = mapOf("数学" to 7L),
            now = 100L,
        )

        assertEquals(2, result.size)
        assertEquals("手工任务", result.first().title)
        assertNull(result.first().planId)
        assertEquals("数学 · 强化训练", result.last().title)
    }

    @Test
    fun stalePlanTaskRemovedWhenItemDisappears() {
        val existing = listOf(
            planTask(id = 11L, planId = 42L, planItemId = 1001L),
            planTask(id = 12L, planId = 42L, planItemId = 1002L),
        )

        val stale = stalePlanTaskIds(
            existing = existing,
            accountGuid = "account-a",
            plan = plan(itemId = 1001L, title = "数学 · 基础梳理"),
        )

        assertEquals(listOf(12L), stale)
    }

    @Test
    fun otherAccountAndManualTasksAreNeverRemoved() {
        val manual = TaskEntity(id = 10L, subjectId = 7L, title = "手工任务")
        val existing = listOf(
            manual,
            planTask(id = 11L, planId = 42L, planItemId = 1001L),
            planTask(id = 13L, planId = 42L, planItemId = 1003L, accountGuid = "account-b"),
        )

        val stale = stalePlanTaskIds(
            existing = existing,
            accountGuid = "account-a",
            plan = plan(itemId = 1001L, title = "数学 · 基础梳理"),
        )

        assertEquals(emptyList<Long>(), stale)
    }

    @Test
    fun previousPlanVersionTasksAreRemoved() {
        val existing = listOf(
            planTask(id = 21L, planId = 41L, planItemId = 900L),
        )

        val stale = stalePlanTaskIds(
            existing = existing,
            accountGuid = "account-a",
            plan = plan(itemId = 1001L, title = "数学 · 基础梳理"),
        )

        assertEquals(listOf(21L), stale)
    }

    @Test
    fun projectRemovesStaleItemAndKeepsManualAndOtherAccountTasks() {
        val manual = TaskEntity(id = 10L, subjectId = 7L, title = "手工任务")
        val existing = listOf(
            manual,
            planTask(id = 11L, planId = 42L, planItemId = 1001L),
            planTask(id = 12L, planId = 42L, planItemId = 1002L),
            planTask(id = 13L, planId = 42L, planItemId = 1003L, accountGuid = "account-b"),
        )

        val result = projectPlanTasks(
            existing = existing,
            plan = plan(itemId = 1001L, title = "数学 · 基础梳理"),
            accountGuid = "account-a",
            subjectIds = mapOf("数学" to 7L),
            now = 100L,
        )

        assertEquals(listOf(10L, 11L, 13L), result.map { it.id })
        assertEquals("手工任务", result.first { it.id == 10L }.title)
    }

    private fun planTask(
        id: Long,
        planId: Long,
        planItemId: Long,
        accountGuid: String = "account-a",
    ) = TaskEntity(
        id = id,
        subjectId = 7L,
        title = "数学 · 计划任务",
        accountGuid = accountGuid,
        planId = planId,
        planItemId = planItemId,
    )

    private fun plan(itemId: Long, title: String) = PlanDto(
        id = 42L,
        title = "阶段计划",
        startDate = "2026-09-01",
        examDate = "2027-12-18",
        items = listOf(
            PlanItemDto(
                id = itemId,
                subject = "数学",
                title = title,
                planDate = "2026-09-26",
                minutes = 50,
            )
        )
    )
}
