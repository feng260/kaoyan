package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.AdjustmentImpactDto
import com.yanzhong.app.data.remote.AdjustmentOptionDto
import com.yanzhong.app.data.remote.PlanItemDto
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanEditTest {
    private val day = LocalDate.parse("2026-11-05")
    private fun item(id: Long, date: String = "2026-11-05", minutes: Int = 60,
        title: String = "任务", subject: String = "数学", locked: Boolean = false) =
        PlanItemDto(id = id, stageId = 1, subject = subject, title = title,
            planDate = date, minutes = minutes, locked = locked)

    @Test fun optimisticPatchKeepsUntouchedFields() {
        val original = item(1, locked = false)
        val edited = optimisticItemPatch(original, PlanItemEdit(minutes = 90, planDate = "2026-11-06"))
        assertEquals(90, edited.minutes)
        assertEquals("2026-11-06", edited.planDate)
        assertEquals(original.title, edited.title)
        assertEquals(original.subject, edited.subject)
        assertEquals(original.locked, edited.locked)
    }

    @Test fun optimisticPatchCanLockWithoutChangingContent() {
        val edited = optimisticItemPatch(item(1), PlanItemEdit(locked = true))
        assertTrue(edited.locked)
        assertEquals("任务", edited.title)
    }

    @Test fun dayRemainingMinutesOnlyCountsThatDay() {
        val items = listOf(item(1, minutes = 60), item(2, minutes = 45), item(3, date = "2026-11-06", minutes = 90))
        assertEquals(135, dayRemainingMinutes(items, 240, day))
        assertEquals(0, dayRemainingMinutes(items, 60, day))
    }

    @Test fun dayRemainingMinutesIsZeroWhenCapacityUnknown() {
        assertEquals(0, dayRemainingMinutes(listOf(item(1, minutes = 30)), 0, day))
        assertEquals(0, dayRemainingMinutes(emptyList(), -1, day))
    }

    @Test fun lockedItemFlagDefaultsFalseForOldServer() {
        assertFalse(item(9).locked)
    }

    @Test fun optionHeadlineCarriesPlanLetterAndLabel() {
        val option = AdjustmentOptionDto(id = "compress", label = "压缩任务时长")
        assertEquals("方案 A · 压缩任务时长", optionHeadline(option, 0))
        assertTrue(optionHeadline(option, 1).contains("方案"))
        assertTrue(optionHeadline(option, 1).contains("压缩任务时长"))
        assertTrue(optionHeadline(AdjustmentOptionDto()).contains("调整方案"))
    }

    @Test fun impactSummaryCountsMovesDropsAndRisk() {
        val summary = impactSummary(
            AdjustmentOptionDto(
                id = "drop",
                impact = AdjustmentImpactDto(
                    affectedDays = 3,
                    movedCount = 2,
                    removedCount = 1,
                    milestoneRiskCount = 1,
                )
            )
        )
        assertTrue(summary.contains("移动 2 项"))
        assertTrue(summary.contains("删除 1 项"))
        assertTrue(summary.contains("里程碑风险 1 项"))
    }

    @Test fun impactSummaryIsBlankWhenNothingChanges() {
        assertEquals("", impactSummary(AdjustmentOptionDto(id = "manual")))
    }
}
