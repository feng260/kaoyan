package com.yanzhong.app.ui.onboarding

import com.yanzhong.app.data.remote.TARGET_TYPES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 非考研类型的科目候选与引导文案。
 *
 * 研钟面向所有备考者,不只考研。考研用分组选择器,其余类型走「预设 chips + 自定义」,
 * 但「其他」(事业编/考证/雅思/期末等)没有统一科目结构,套考研味的「政治/英语/数学/专业课」
 * 预设会误导用户确认进不考的科目 —— 与上一版考研计划出错同源。这里锁住:
 * 「其他」与未知类型不注入任何泛称预设、只保留用户自建科目;分型引导文案贴合各自考试。
 */
class OnboardingSubjectOptionsTest {

    // ---------------- 科目候选 ----------------

    @Test
    fun `其他类型不注入任何预设`() {
        assertEquals(emptyList<String>(), subjectOptionsFor("其他", emptyList()))
    }

    @Test
    fun `其他类型只保留用户自建科目`() {
        assertEquals(listOf("雅思", "写作"), subjectOptionsFor("其他", listOf("雅思", "写作")))
    }

    @Test
    fun `未知类型也不注入考研泛称预设`() {
        assertEquals(emptyList<String>(), subjectOptionsFor("未知考试", emptyList()))
    }

    @Test
    fun `法考保留专属科目且不混入数学`() {
        val options = subjectOptionsFor("法考", emptyList())
        assertTrue("法考应有民法", "民法" in options)
        assertTrue("法考应有刑法", "刑法" in options)
        assertFalse("法考不该看到数学", "数学" in options)
    }

    @Test
    fun `考公预设为行测申论`() {
        assertEquals(listOf("行测", "申论"), subjectOptionsFor("考公", emptyList()))
    }

    @Test
    fun `本地科目去重后追加在预设之后`() {
        assertEquals(
            listOf("行测", "申论", "公共基础知识"),
            subjectOptionsFor("考公", listOf("行测", "公共基础知识"))
        )
    }

    // ---------------- 分型引导文案 ----------------

    @Test
    fun `每种备考类型都有非空引导文案`() {
        TARGET_TYPES.forEach { type ->
            assertTrue("$type 的引导文案不该为空", subjectStepSubtitle(type).isNotBlank())
        }
    }

    @Test
    fun `其他类型引导自定义而非预设`() {
        val subtitle = subjectStepSubtitle("其他")
        assertTrue("其他应引导用户自己写", subtitle.contains("写"))
        assertFalse("其他不该再提政治预设", subtitle.contains("政治"))
    }

    @Test
    fun `考公引导提到行测申论`() {
        val subtitle = subjectStepSubtitle("考公")
        assertTrue(subtitle.contains("行测"))
        assertTrue(subtitle.contains("申论"))
    }
}