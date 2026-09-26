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
}
