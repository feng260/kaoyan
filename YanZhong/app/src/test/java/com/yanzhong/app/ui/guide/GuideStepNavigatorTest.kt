package com.yanzhong.app.ui.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideStepNavigatorTest {

    private fun step(target: String) = GuideStep(target, "t-$target", "b-$target")

    @Test
    fun `缺失锚点的步骤被过滤`() {
        val steps = listOf(step("a"), step("b"), step("c"))
        assertEquals(listOf("a", "c"), availableSteps(steps, setOf("a", "c")).map { it.target })
    }

    @Test
    fun `没有锚点时没有可展示步骤`() {
        assertTrue(availableSteps(listOf(step("a")), emptySet()).isEmpty())
    }

    @Test
    fun `下一步跳到后面第一个有锚点的步骤`() {
        val steps = listOf(step("a"), step("b"), step("c"))
        assertEquals(2, nextAvailableIndex(steps, setOf("a", "c"), 0))
    }

    @Test
    fun `后面没有锚点步骤时返回空表示结束`() {
        val steps = listOf(step("a"), step("b"), step("c"))
        assertNull(nextAvailableIndex(steps, setOf("a"), 0))
    }
}
