package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.data.remote.PlanItemDto
import com.yanzhong.app.data.remote.PlanStageDto
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanSelectionTest {
    private val first = PlanStageDto(1, "基础", "2026-09-01", "2026-09-30", 0)
    private val second = PlanStageDto(2, "强化", "2026-10-01", "2026-10-31", 1)
    private fun plan(items: List<PlanItemDto> = emptyList()) = PlanDto(
        id = 1, title = "备考计划", startDate = first.startDate, examDate = "2026-11-01",
        stages = listOf(second, first), items = items
    )

    @Test fun currentStageIncludesBothBoundaryDates() {
        assertEquals(first, plan().currentStage(LocalDate.parse("2026-09-30")))
        assertEquals(second, plan().currentStage(LocalDate.parse("2026-10-01")))
        assertNull(plan().currentStage(LocalDate.parse("2026-11-01")))
    }

    @Test fun dailyItemsIncludeDoneItemsInSortOrder() {
        val items = listOf(
            PlanItemDto(1, 1, "数学", "第二项", "2026-09-27", status = "done", sortOrder = 2),
            PlanItemDto(2, 1, "英语", "第一项", "2026-09-27", sortOrder = 1),
            PlanItemDto(3, 1, "政治", "其他日期", "2026-09-28")
        )
        assertEquals(listOf("第一项", "第二项"),
            plan(items).dailyItems(LocalDate.parse("2026-09-27")).map { it.title })
    }

    @Test fun upcomingItemsExcludePastCompletedAndOtherStages() {
        val items = listOf(
            PlanItemDto(1, 2, "数学", "明天", "2026-10-02", sortOrder = 2),
            PlanItemDto(2, 2, "英语", "今天", "2026-10-01"),
            PlanItemDto(3, 2, "英语", "已完成", "2026-10-01", status = "done"),
            PlanItemDto(4, 2, "数学", "昨天", "2026-09-30"),
            PlanItemDto(5, 1, "政治", "其他阶段", "2026-10-02")
        )
        assertEquals(listOf("今天", "明天"),
            plan(items).upcomingItems(second, LocalDate.parse("2026-10-01")).map { it.title })
    }

    @Test fun planDisplayStateKeepsLoadedPlanDuringNetworkFailure() {
        val loaded = plan()
        assertEquals(PlanDisplayState.READY, selectPlanDisplayState(true, loaded, true, false))
        assertEquals(PlanDisplayState.NETWORK_ERROR, selectPlanDisplayState(true, null, true, false))
        assertEquals(PlanDisplayState.NO_PLAN, selectPlanDisplayState(true, null, false, false))
        assertEquals(PlanDisplayState.LOGIN_REQUIRED, selectPlanDisplayState(false, null, false, false))
        assertEquals(PlanDisplayState.LOADING, selectPlanDisplayState(true, null, false, true))
    }

    @Test fun dailyProgressIncludesCompletedAndPendingItems() {
        val items = listOf(
            PlanItemDto(1, 1, "数学", "完成", "2026-09-27", status = "done"),
            PlanItemDto(2, 1, "英语", "待做", "2026-09-27"),
            PlanItemDto(3, 1, "政治", "明天", "2026-09-28", status = "done")
        )
        assertEquals(1 to 2, plan(items).dailyProgress(LocalDate.parse("2026-09-27")))
        assertEquals(0 to 0, plan(items).dailyProgress(LocalDate.parse("2026-09-29")))
    }
}
