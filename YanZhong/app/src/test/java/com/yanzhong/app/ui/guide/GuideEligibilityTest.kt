package com.yanzhong.app.ui.guide

import com.yanzhong.app.ui.nav.Routes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideEligibilityTest {

    @Test
    fun `主 tab 未看过且就绪时允许展示`() {
        assertTrue(
            shouldStartGuide(
                route = Routes.HOME,
                deepPage = false,
                ready = true,
                declaredPage = GuidePage.HOME,
                seenPages = emptySet(),
                overlayVisible = false
            )
        )
    }

    @Test
    fun `深层页不允许展示`() {
        assertFalse(
            shouldStartGuide(
                route = Routes.HOME,
                deepPage = true,
                ready = true,
                declaredPage = GuidePage.HOME,
                seenPages = emptySet(),
                overlayVisible = false
            )
        )
    }

    @Test
    fun `已看过的页面不再展示`() {
        assertFalse(
            shouldStartGuide(
                route = Routes.HOME,
                deepPage = false,
                ready = true,
                declaredPage = GuidePage.HOME,
                seenPages = setOf("home"),
                overlayVisible = false
            )
        )
    }

    @Test
    fun `专注计时中页面未就绪不展示`() {
        assertFalse(
            shouldStartGuide(
                route = Routes.FOCUS,
                deepPage = false,
                ready = false,
                declaredPage = GuidePage.FOCUS,
                seenPages = emptySet(),
                overlayVisible = false
            )
        )
    }

    @Test
    fun `引导已可见时不重复开始`() {
        assertFalse(
            shouldStartGuide(
                route = Routes.PLAN,
                deepPage = false,
                ready = true,
                declaredPage = GuidePage.PLAN,
                seenPages = emptySet(),
                overlayVisible = true
            )
        )
    }

    @Test
    fun `声明页与当前路由不一致不展示`() {
        assertFalse(
            shouldStartGuide(
                route = Routes.PLAN,
                deepPage = false,
                ready = true,
                declaredPage = GuidePage.HOME,
                seenPages = emptySet(),
                overlayVisible = false
            )
        )
    }

    @Test
    fun `没有声明页时不展示`() {
        assertFalse(
            shouldStartGuide(
                route = "plan_interview?mode=&prefill=",
                deepPage = false,
                ready = true,
                declaredPage = null,
                seenPages = emptySet(),
                overlayVisible = false
            )
        )
    }
}
