package com.yanzhong.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「铺不铺网格」这两个判断的纯函数版接线测试。
 *
 * 上一版回归就长在这段接线上(loadInternal 无条件铺网格,把用户第 2 题的选择盖掉了),
 * 8 条针对 seedAvailability 本身的单测一条没抓到,只有模拟器抓到。抽出来之后,
 * 同类问题会在 CI 就红 —— 这里的第 1 条用例就是当时能抓到 bug 的那一条。
 */
class OnboardingAvailabilityWiringTest {

    @Test
    fun `全新账号从第 1 题走起时不在加载阶段铺网格`() {
        // 这条就是当时能抓到已上线回归的那一条:
        // 全新账号 p == null、landsOnLastStep 必然是 false,网格得留空,
        // 等用户走到第 2 题勾完时段、由 next() 再铺 —— 提前铺只会拿默认时段盖住他的选择
        val grid = resolveLoadedAvailability(
            landsOnLastStep = false,
            profileWindows = null,
            profileGrid = null,
            fallbackStudyWindows = setOf("上午", "下午", "晚上"),
            currentGrid = emptyMap()
        )
        assertEquals(emptyMap<Int, List<TimeWindow>>(), grid)
    }

    @Test
    fun `manage 落到最后一题且档案没存空闲时按档案时段铺满`() {
        val grid = resolveLoadedAvailability(
            landsOnLastStep = true,
            profileWindows = setOf("晚上"),
            profileGrid = null,
            fallbackStudyWindows = setOf("上午"),
            currentGrid = emptyMap()
        )
        // 铺的应该是档案里的「晚上」,而不是当前状态里的「上午」
        assertEquals(7, grid.size)
        assertEquals(listOf(TimeWindow("19:00", "22:00")), grid[1])
        assertEquals(listOf(TimeWindow("19:00", "22:00")), grid[7])
    }

    @Test
    fun `manage 落到最后一题但档案里已有真实空闲时原样保留`() {
        // 老账号已确认过的真实空闲必须原样保留,不能被预设重新铺一遍;
        // 特别是第 2~7 天不能被"补齐",用户可能有意只填了周一
        val fromProfile = mapOf(1 to listOf(TimeWindow("07:00", "08:00")))
        val grid = resolveLoadedAvailability(
            landsOnLastStep = true,
            profileWindows = setOf("晚上"),
            profileGrid = fromProfile,
            fallbackStudyWindows = setOf("上午"),
            currentGrid = emptyMap()
        )
        assertEquals(fromProfile, grid)
    }

    @Test
    fun `档案里没有时段时回落到当前状态的时段`() {
        // 档案是半份(studyWindows 空集)时应该走 s.studyWindows 兜底;
        // 这里兜底只有「早晨」,不该混进别的时段
        val grid = resolveLoadedAvailability(
            landsOnLastStep = true,
            profileWindows = emptySet(),
            profileGrid = null,
            fallbackStudyWindows = setOf("早晨"),
            currentGrid = emptyMap()
        )
        assertEquals(7, grid.size)
        assertEquals(listOf(TimeWindow("06:30", "08:00")), grid[1])
        assertEquals(listOf(TimeWindow("06:30", "08:00")), grid[7])
    }

    @Test
    fun `半份档案从第 1 题走起时只回填不铺预设`() {
        // 老账号档案半份(只有 availability、没有 isComplete),从第 1 题走起:
        // 档案里存过的空闲要回填给用户看,但别把默认时段铺到没填的那 6 天上 ——
        // 用户还没走到第 2 题,时段是他的下一题的选择,不能被这一屏提前定死
        val fromProfile = mapOf(6 to listOf(TimeWindow("09:00", "17:00")))
        val grid = resolveLoadedAvailability(
            landsOnLastStep = false,
            profileWindows = setOf("晚上"),
            profileGrid = fromProfile,
            fallbackStudyWindows = setOf("上午", "下午", "晚上"),
            currentGrid = emptyMap()
        )
        assertEquals(fromProfile, grid)
    }

    @Test
    fun `半份档案从第 1 题走起且档案没存空闲时网格保持为空`() {
        val grid = resolveLoadedAvailability(
            landsOnLastStep = false,
            profileWindows = setOf("晚上"),
            profileGrid = null,
            fallbackStudyWindows = setOf("上午"),
            currentGrid = emptyMap()
        )
        assertEquals(emptyMap<Int, List<TimeWindow>>(), grid)
    }

    @Test
    fun `下一步走进最后一题时按第 2 题勾的时段铺网格`() {
        // 顺序跟着 STUDY_WINDOWS 走(上午 → 晚上),而不是 Set 的迭代序;
        // 勾「晚上、上午」和勾「上午、晚上」应该铺出一样的结果
        val grid = nextStepAvailability(true, setOf("上午", "晚上"), emptyMap())
        assertEquals(7, grid.size)
        val expected = listOf(TimeWindow("08:30", "11:30"), TimeWindow("19:00", "22:00"))
        for (day in 1..7) {
            assertEquals(expected, grid[day])
        }
    }

    @Test
    fun `下一步还没走到最后一题时不动网格`() {
        // 第 1 → 第 2 题这一步不该动网格;网格是第 3 题的东西,提前铺会盖住用户的选择
        val grid = nextStepAvailability(false, setOf("晚上"), emptyMap())
        assertEquals(emptyMap<Int, List<TimeWindow>>(), grid)
    }

    @Test
    fun `下一步走进最后一题但网格已填时不覆盖`() {
        // 用户在第 3 题删掉的某一天,回第 2 题再进来也不该被重新铺回
        val filled = mapOf(1 to listOf(TimeWindow("19:00", "22:00")))
        val grid = nextStepAvailability(true, setOf("上午"), filled)
        assertEquals(filled, grid)
    }
}
