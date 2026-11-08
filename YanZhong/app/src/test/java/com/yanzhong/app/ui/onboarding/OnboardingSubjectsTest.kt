package com.yanzhong.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考研科目分组选择器的纯逻辑测试。
 *
 * 上一版问卷的科目题形同虚设:预设只有「政治/英语/数学/专业课」,
 * 第 3 步还从薄弱科目自动带出,用户一路「下一步」就把泛科目确认进了计划。
 * 这一版把科目拆成 政治/外语/数学联考/专业课 四组,组内互斥、必须显式作答,
 * 这里锁住三件事:派生结果不混入不考的科目、199 联动、泛称回填必须强制重选。
 */
class OnboardingSubjectsTest {

    // ---------------- 派生正式科目 ----------------

    @Test
    fun `完整选择按 政治 外语 数学 专业课 顺序派生`() {
        val selection = KaoyanSubjectSelection(
            politics = true,
            foreign = "英语二",
            math = "数学三",
            majors = setOf("金融综合")
        )
        assertEquals(listOf("政治", "英语二", "数学三", "金融综合"), deriveExamSubjects(selection).toList())
    }

    @Test
    fun `选不考数学时派生结果不含任何数学科目`() {
        val selection = KaoyanSubjectSelection(
            foreign = "英语一",
            math = KAOYAN_NO_MATH,
            majors = setOf("306西医综合")
        )
        assertEquals(listOf("政治", "英语一", "306西医综合"), deriveExamSubjects(selection).toList())
    }

    @Test
    fun `取消政治时派生结果不含政治`() {
        val selection = KaoyanSubjectSelection(
            politics = false,
            foreign = "英语二",
            math = KAOYAN_MANAGEMENT
        )
        assertEquals(listOf("英语二", "199管理类联考"), deriveExamSubjects(selection).toList())
    }

    @Test
    fun `什么都没选时派生为空集`() {
        assertEquals(emptySet<String>(), deriveExamSubjects(KaoyanSubjectSelection(politics = false)))
    }

    @Test
    fun `自命题专业课名字原样保留`() {
        val selection = KaoyanSubjectSelection(
            foreign = "英语一",
            math = KAOYAN_NO_MATH,
            majors = setOf("数据结构与操作系统")
        )
        assertTrue("数据结构与操作系统" in deriveExamSubjects(selection))
    }

    // ---------------- 199 联动 ----------------

    @Test
    fun `选199管理类联考自动取消政治`() {
        val selection = KaoyanSubjectSelection().withMath(KAOYAN_MANAGEMENT)
        assertFalse(selection.politics)
        assertEquals(KAOYAN_MANAGEMENT, selection.math)
    }

    @Test
    fun `从199切到其他数学选项时恢复政治`() {
        val selection = KaoyanSubjectSelection().withMath(KAOYAN_MANAGEMENT).withMath("数学一")
        assertTrue(selection.politics)
        assertEquals("数学一", selection.math)
    }

    @Test
    fun `手动取消政治后选普通数学选项不会擅自恢复`() {
        val selection = KaoyanSubjectSelection(politics = false).withMath("数学二")
        assertFalse(selection.politics)
    }

    // ---------------- 专业课增删与上限 ----------------

    @Test
    fun `toggleMajor 添加再点一次移除`() {
        val added = KaoyanSubjectSelection().toggleMajor("408计算机学科专业基础")
        assertTrue("408计算机学科专业基础" in added.majors)
        val removed = added.toggleMajor("408计算机学科专业基础")
        assertFalse("408计算机学科专业基础" in removed.majors)
    }

    @Test
    fun `派生总数到12门上限后不能再加专业课`() {
        // 政治 + 外语 + 数学 = 3 门,专业课已有 9 门,合计 12
        val full = KaoyanSubjectSelection(
            foreign = "英语一",
            math = "数学一",
            majors = (1..9).map { "专业课$it" }.toSet()
        )
        assertEquals(12, deriveExamSubjects(full).size)
        val blocked = full.toggleMajor("第13门")
        assertEquals(full, blocked)
    }

    @Test
    fun `到上限后已有的专业课仍可移除`() {
        val full = KaoyanSubjectSelection(
            foreign = "英语一",
            math = "数学一",
            majors = (1..9).map { "专业课$it" }.toSet()
        )
        val removed = full.toggleMajor("专业课1")
        assertEquals(11, deriveExamSubjects(removed).size)
    }

    // ---------------- 存量档案回填 ----------------

    @Test
    fun `具体科目名能完整映射回分组`() {
        val selection = kaoyanSelectionFromNames(setOf("政治", "英语二", "数学三", "金融综合"))
        assertTrue(selection.politics)
        assertEquals("英语二", selection.foreign)
        assertEquals("数学三", selection.math)
        assertEquals(setOf("金融综合"), selection.majors)
    }

    @Test
    fun `泛称科目被丢弃 forcing 用户重新显式选择`() {
        // 旧档案的「英语/数学/专业课」正是计划出错的根源:
        // 回填时不能把它们当成已选答案,必须留空让校验拦住
        val selection = kaoyanSelectionFromNames(setOf("政治", "英语", "数学", "专业课"))
        assertTrue(selection.politics)
        assertNull(selection.foreign)
        assertNull(selection.math)
        assertEquals(emptySet<String>(), selection.majors)
    }

    @Test
    fun `小语种映射到外语组而不是专业课`() {
        val selection = kaoyanSelectionFromNames(setOf("政治", "日语", "数学一", "计算机综合"))
        assertEquals("日语", selection.foreign)
        assertEquals(setOf("计算机综合"), selection.majors)
    }

    @Test
    fun `396映射到数学联考组`() {
        val selection = kaoyanSelectionFromNames(setOf("政治", "英语二", "396经济类联考", "金融综合"))
        assertEquals("396经济类联考", selection.math)
        assertEquals(setOf("金融综合"), selection.majors)
    }

    @Test
    fun `档案里没有政治时回填不擅自选上`() {
        val selection = kaoyanSelectionFromNames(setOf("英语二", "199管理类联考"))
        assertFalse(selection.politics)
        assertEquals("199管理类联考", selection.math)
    }

    // ---------------- 科目步校验 ----------------

    @Test
    fun `考研没选外语过不了科目步`() {
        val selection = KaoyanSubjectSelection(math = "数学一", majors = setOf("408计算机学科专业基础"))
        assertNotNull(validateSubjectStep("考研", selection, deriveExamSubjects(selection)))
    }

    @Test
    fun `考研没选数学过不了科目步`() {
        val selection = KaoyanSubjectSelection(foreign = "英语一", majors = setOf("408计算机学科专业基础"))
        assertNotNull(validateSubjectStep("考研", selection, deriveExamSubjects(selection)))
    }

    @Test
    fun `考研没选专业课过不了科目步`() {
        val selection = KaoyanSubjectSelection(foreign = "英语一", math = "数学一")
        assertNotNull(validateSubjectStep("考研", selection, deriveExamSubjects(selection)))
    }

    @Test
    fun `199管理类联考不要求专业课`() {
        val selection = KaoyanSubjectSelection(politics = false, foreign = "英语二", math = KAOYAN_MANAGEMENT)
        assertNull(validateSubjectStep("考研", selection, deriveExamSubjects(selection)))
    }

    @Test
    fun `完整考研选择通过科目步`() {
        val selection = KaoyanSubjectSelection(foreign = "英语二", math = "数学三", majors = setOf("金融综合"))
        assertNull(validateSubjectStep("考研", selection, deriveExamSubjects(selection)))
    }

    @Test
    fun `非考研科目为空过不了科目步`() {
        assertNotNull(validateSubjectStep("考公", KaoyanSubjectSelection(), emptySet()))
    }

    @Test
    fun `非考研选了科目就通过科目步`() {
        assertNull(validateSubjectStep("考公", KaoyanSubjectSelection(), setOf("行测", "申论")))
    }
}