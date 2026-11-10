package com.yanzhong.app.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底部导航策略回归测试。
 *
 * 「退出番茄钟后卡在专注页,点今日也仍弹回专注页」的根因在导航选项的组合:
 * navigation-runtime 的非 inclusive `popUpTo(HOME) { saveState = true }` 会把刚弹出的
 * 栈(此时是专注页)登记在 HOME 名下,而同一次 navigate 又带 `restoreState = true`,
 * 于是刚保存的专注页被立即恢复回来。restoreState 只能用于非 popUpTo 锚点的目标。
 */
class TabNavPlanTest {

    @Test
    fun `点今日不恢复状态以免弹回刚离开的专注页`() {
        val plan = tabNavPlan(Routes.HOME)
        assertEquals(Routes.HOME, plan.popUpToRoute)
        assertTrue("回到首页仍应保存其它页签的状态", plan.saveState)
        assertFalse("首页是 popUpTo 锚点,恢复状态会把刚弹掉的栈又装回来", plan.restoreState)
    }

    @Test
    fun `切到其它页签保持保存与恢复`() {
        listOf(Routes.PLAN, Routes.STATS, Routes.MINE, Routes.FOCUS).forEach { route ->
            val plan = tabNavPlan(route)
            assertEquals(Routes.HOME, plan.popUpToRoute)
            assertTrue("$route 需要保存页签状态", plan.saveState)
            assertTrue("$route 需要恢复页签状态", plan.restoreState)
        }
    }
}
