package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.DocBlockDto
import com.yanzhong.app.data.remote.DocChapterDto
import com.yanzhong.app.data.remote.PlanDocumentDto
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.data.remote.PlanItemDto
import com.yanzhong.app.data.remote.PlanStageDto
import com.yanzhong.app.data.remote.PlanBriefDto
import kotlinx.serialization.json.Json
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanDraftTest {
    private val today = LocalDate.parse("2026-09-29")
    private fun item(id: Long, title: String, date: String = today.toString()) =
        PlanItemDto(id, 1, "数学", title, date, minutes = 45)
    private fun plan(status: String, items: List<PlanItemDto> = emptyList(), title: String = "计划") =
        PlanDto(1, title, status = status, startDate = "2026-09-29", examDate = "2026-12-20",
            stages = listOf(PlanStageDto(1, "基础", "2026-09-29", "2026-10-29")), items = items)

    @Test fun onlyCompletedInterviewCanGenerate() {
        assertFalse(InterviewUiState(phase = InterviewPhase.CHATTING).canGenerate)
        assertTrue(InterviewUiState(phase = InterviewPhase.CHATTING, done = true).canGenerate)
        assertFalse(InterviewUiState(phase = InterviewPhase.CHATTING, done = true, sending = true).canGenerate)
        assertFalse(InterviewUiState(phase = InterviewPhase.DRAFT, done = true, plan = plan("draft")).canGenerate)
    }

    @Test fun onlyDraftCanBeConfirmed() {
        assertTrue(InterviewUiState(phase = InterviewPhase.DRAFT, plan = plan("draft")).canConfirm)
        assertFalse(InterviewUiState(phase = InterviewPhase.DRAFT, plan = plan("active")).canConfirm)
        assertFalse(InterviewUiState(phase = InterviewPhase.DRAFT, plan = plan("draft"), confirming = true).canConfirm)
    }

    @Test fun todayDiffUsesTaskContentNotIdsAndExcludesOtherDates() {
        val old = plan("active", listOf(item(1, "保留"), item(2, "移除")))
        val draft = plan("draft", listOf(item(19, "保留"), item(20, "新增"), item(21, "明日", "2026-09-30")))
        val diff = compareTodayItems(draft, old, today)
        assertEquals(listOf("移除"), diff.removed.map { it.title })
        assertEquals(listOf("新增"), diff.added.map { it.title })
    }

    @Test fun completedTodayTasksAreRetainedNotReportedAsRemoved() {
        val completed = item(1, "已完成").copy(status = "done")
        val pending = item(2, "待替换")
        val draft = plan("draft", listOf(item(3, "新增")))
        val diff = compareTodayItems(draft, plan("active", listOf(completed, pending)), today)
        assertEquals(listOf("待替换"), diff.removed.map { it.title })
        assertEquals(listOf("新增"), diff.added.map { it.title })
        val html = renderDraftHtml(draft, diff, today)
        assertTrue(html.contains("已完成任务保留"))
    }

    @Test fun briefReadsConfirmedSubjectsAvailabilityAndCommitments() {
        val brief = Json.decodeFromString<PlanBriefDto>("""{
            "examSubjects":[{"name":"英语一","progress":"真题","scope":"阅读","remainingMinutes":120,"milestone":"一轮"}],
            "availability":[{"weekday":1,"windows":[{"start":"19:00","end":"21:00"}]}],
            "fixedCommitments":[{"weekday":1,"start":"19:00","end":"20:00","label":"工作"}],
            "availabilityConfirmed":true,"commitmentsConfirmed":true
        }""")
        assertEquals("英语一", brief.examSubjects.single().name)
        assertEquals("19:00", brief.availability.single().windows.single().start)
        assertEquals("工作", brief.fixedCommitments.single().label)
        assertTrue(brief.availabilityConfirmed)
        assertTrue(brief.commitmentsConfirmed)
    }

    @Test fun unavailableActivePlanDoesNotClaimZeroRemovedTasks() {
        val draft = plan("draft", listOf(item(4, "新增")))
        val html = renderDraftHtml(draft, compareTodayItems(draft, null, today), today, activeCompared = false)
        assertTrue(html.contains("当前计划读取失败"))
        assertFalse(html.contains("将移除 0 项"))
    }

    @Test fun previewEscapesAllDtoContentAndRendersStructuredSections() {
        val unsafe = "<img src=x onerror=alert(1)> & \"test\" '"
        val draft = plan("draft", listOf(item(1, unsafe)), unsafe).copy(
            document = PlanDocumentDto(title = unsafe, chapters = listOf(
                DocChapterDto(no = "01", title = unsafe, blocks = listOf(DocBlockDto(text = unsafe)))
            ))
        )
        val html = renderDraftHtml(draft, compareTodayItems(draft, null, today), today)
        assertFalse(html.contains(unsafe))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertTrue(html.contains("&amp; &quot;test&quot; &#39;"))
        assertTrue(html.contains("基础"))
        assertTrue(html.contains("今日任务变化"))
    }
}
