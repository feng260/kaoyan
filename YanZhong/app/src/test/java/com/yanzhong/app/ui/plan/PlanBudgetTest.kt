package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.AdjustmentImpactDto
import com.yanzhong.app.data.remote.AdjustmentOptionDto
import com.yanzhong.app.data.remote.BriefSubjectDto
import com.yanzhong.app.data.remote.PlanBriefDto
import com.yanzhong.app.data.remote.PlanWarningDto
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanBudgetTest {
    @Test fun deltaMinutesUseHumanTextAndSign() {
        assertEquals("持平", formatDeltaMinutes(0))
        assertEquals("多 30 分钟", formatDeltaMinutes(30))
        assertEquals("多 1 小时 30 分钟", formatDeltaMinutes(90))
        assertEquals("少 45 分钟", formatDeltaMinutes(-45))
        assertEquals("少 2 小时", formatDeltaMinutes(-120))
    }

    @Test fun optionImpactListsOnlyMeaningfulMetricsInStableOrder() {
        val option = AdjustmentOptionDto(
            id = "compress",
            label = "压缩",
            impact = AdjustmentImpactDto(
                affectedDays = 3,
                movedCount = 2,
                removedCount = 1,
                totalMinutesBefore = 600,
                totalMinutesAfter = 480,
                maxDayLoad = 180,
                maxDayCapacity = 240,
                milestoneRiskCount = 1,
                lockedKeptCount = 2,
            )
        )
        assertEquals(
            listOf("涉及 3 天", "移动 2 项", "删除 1 项", "总量减少 2 小时",
                "最大日负荷 180/240 分钟", "里程碑风险 1 项"),
            optionImpactLines(option)
        )
    }

    @Test fun optionImpactIsEmptyWhenNothingChanges() {
        assertTrue(optionImpactLines(AdjustmentOptionDto(id = "manual")).isEmpty())
    }

    @Test fun optionImpactShowsShortfallAndAddition() {
        val lines = optionImpactLines(
            AdjustmentOptionDto(
                id = "spill",
                impact = AdjustmentImpactDto(
                    addedCount = 1,
                    totalMinutesBefore = 60,
                    totalMinutesAfter = 120,
                    shortfallMinutes = 45,
                )
            )
        )
        assertTrue(lines.contains("新增 1 项"))
        assertTrue(lines.contains("总量增加 1 小时"))
        assertTrue(lines.contains("仍缺口 45 分钟"))
        assertFalse(lines.any { it.startsWith("移动") })
    }

    @Test fun etaGrowsWithHorizonAndStaysWithinEightToThirty() {
        assertEquals(8..13, etaRange(0))
        assertEquals(8..13, etaRange(100))
        assertEquals(8..15, etaRange(250))
        assertEquals(13..23, etaRange(450))
        assertEquals(25..30, etaRange(10_000))
    }

    @Test fun elapsedTimeIsMinutesOrHours() {
        assertEquals("0:05", formatElapsed(5))
        assertEquals("1:05", formatElapsed(65))
        assertEquals("59:59", formatElapsed(3599))
        assertEquals("1:00:00", formatElapsed(3600))
        assertEquals("0:00", formatElapsed(-3))
    }

    @Test fun horizonDaysComeFromExamDate() {
        val today = LocalDate.parse("2026-11-03")
        assertEquals(48, horizonDaysFrom("2026-12-21", today))
        assertNull(horizonDaysFrom("2026-11-03", today))
        assertNull(horizonDaysFrom("not-a-date", today))
        assertNull(horizonDaysFrom("", today))
    }

    @Test fun briefPatchOnlyCarriesTouchedFields() {
        val brief = PlanBriefDto(examSubjects = listOf(
            BriefSubjectDto(name = "数学", remainingMinutes = 600, milestoneDate = "2026-11-30",
                estimated = false),
            BriefSubjectDto(name = "英语", remainingMinutes = 300, milestoneDate = "2026-12-10")
        ))
        val patches = briefPatchFromEdits(brief, mapOf(
            "数学" to BriefSubjectEdit(remainingMinutes = 900, milestoneDate = "2026-12-05",
                estimated = true),
            "英语" to BriefSubjectEdit(remainingMinutes = 300, milestoneDate = "2026-12-10",
                estimated = false)
        ))
        assertEquals(1, patches.size)
        assertEquals("数学", patches.single().name)
        assertEquals(900, patches.single().remainingMinutes)
        assertEquals("2026-12-05", patches.single().milestoneDate)
        assertEquals(true, patches.single().estimated)
    }

    @Test fun subjectDeltaToneOnlyFlagsDifferencesOverFivePercent() {
        assertEquals(BudgetDeltaTone.BALANCED, subjectDeltaTone(50, 1000))
        assertEquals(BudgetDeltaTone.OVER, subjectDeltaTone(60, 1000))
        assertEquals(BudgetDeltaTone.BALANCED, subjectDeltaTone(-50, 1000))
        assertEquals(BudgetDeltaTone.UNDER, subjectDeltaTone(-60, 1000))
        assertEquals(BudgetDeltaTone.OVER, subjectDeltaTone(1, 0))
        assertEquals(BudgetDeltaTone.BALANCED, subjectDeltaTone(0, 0))
    }

    @Test fun budgetWarningTextKeepsServerMessageAndFallsBackToCode() {
        val withMessage = PlanWarningDto(code = "MILESTONE_RISK",
            message = "数学 里程碑前还差 120 分钟", subject = "数学")
        assertEquals("数学 里程碑前还差 120 分钟", budgetWarningText(withMessage))
        val withDate = PlanWarningDto(code = "DAY_OVERLOADED", message = "", date = "2026-11-05")
        assertEquals("2026-11-05 · 有日期的安排超出当天容量", budgetWarningText(withDate))
        assertEquals("English 里程碑前可能完不成",
            budgetWarningText(PlanWarningDto(code = "MILESTONE_RISK", subject = "English")))
    }
}