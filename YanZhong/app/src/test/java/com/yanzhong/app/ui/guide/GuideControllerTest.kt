package com.yanzhong.app.ui.guide

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 控制器状态机:开始门槛、跳到有锚点的步骤、结束只写一次、可见期间不重复开始 */
class GuideControllerTest {

    private val spec = GuidePageSpec(
        page = GuidePage.MINE,
        steps = listOf(
            GuideStep(GuideAnchors.MINE_HEADER, "个人资料", "点头像和昵称可以改个人资料。"),
            GuideStep(GuideAnchors.MINE_ACCOUNT_GROUP, "账号与档案", "备考档案从这里改。")
        )
    )

    private fun rect(top: Float = 0f) = Rect(0f, top, 320f, top + 80f)

    @Test
    fun `没有登记任何锚点时不能开始`() {
        val finished = mutableListOf<GuidePage>()
        val controller = GuideController { finished += it }

        assertFalse(controller.start(spec))
        assertFalse(controller.visible)
        assertEquals(null, controller.activeSpec)
        assertTrue(finished.isEmpty())
    }

    @Test
    fun `登记了首步锚点时开始并停在该步`() {
        val controller = GuideController { }
        controller.targets.update(GuideAnchors.MINE_HEADER, rect())

        assertTrue(controller.start(spec))
        assertTrue(controller.visible)
        assertEquals(0, controller.stepIndex)
        assertEquals(spec, controller.activeSpec)
    }

    @Test
    fun `缺少首步锚点时开始并定位到后面第一个有锚点的步骤`() {
        val controller = GuideController { }
        controller.targets.update(GuideAnchors.MINE_ACCOUNT_GROUP, rect())

        assertTrue(controller.start(spec))
        assertTrue(controller.visible)
        assertEquals(1, controller.stepIndex)
    }

    @Test
    fun `最后一步 next 只结束一次并记入已关闭页`() {
        val finished = mutableListOf<GuidePage>()
        val controller = GuideController { finished += it }
        controller.targets.update(GuideAnchors.MINE_HEADER, rect())
        controller.targets.update(GuideAnchors.MINE_ACCOUNT_GROUP, rect(top = 200f))
        assertTrue(controller.start(spec))

        controller.next()
        assertEquals(1, controller.stepIndex)
        assertTrue(controller.visible)

        controller.next()
        assertEquals(listOf(GuidePage.MINE), finished)
        assertFalse(controller.visible)
        assertEquals(null, controller.activeSpec)
        assertTrue(GuidePage.MINE.key in controller.dismissedPages)

        // 再来一次 finish 不应重复回调
        controller.finish()
        assertEquals(listOf(GuidePage.MINE), finished)
    }

    @Test
    fun `已可见时再次开始返回 false`() {
        val controller = GuideController { }
        controller.targets.update(GuideAnchors.MINE_HEADER, rect())

        assertTrue(controller.start(spec))
        assertFalse(controller.start(spec))
        assertTrue(controller.visible)
        assertEquals(0, controller.stepIndex)
    }
}
