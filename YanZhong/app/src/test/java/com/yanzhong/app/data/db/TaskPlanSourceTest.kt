package com.yanzhong.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskPlanSourceTest {
    @Test
    fun manualTaskHasNoPlanSource() {
        val task = TaskEntity(subjectId = 1, title = "手工任务")

        assertNull(task.accountGuid)
        assertNull(task.planId)
        assertNull(task.planItemId)
    }

    @Test
    fun planTaskCarriesAccountPlanAndItemSource() {
        val task = TaskEntity(
            subjectId = 1,
            title = "数学 · 基础梳理",
            accountGuid = "account-a",
            planId = 42L,
            planItemId = 1001L,
        )

        assertEquals("account-a", task.accountGuid)
        assertEquals(42L, task.planId)
        assertEquals(1001L, task.planItemId)
    }
}
