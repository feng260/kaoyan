package com.yanzhong.app.ui.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideSpecTest {

    @Test
    fun `今日有生效计划时按倒计时、进度、导航三步`() {
        val spec = homeGuide(hasActivePlan = true, tablet = false)
        assertEquals(GuidePage.HOME, spec.page)
        assertEquals(
            listOf(
                GuideAnchors.HOME_COUNTDOWN,
                GuideAnchors.HOME_PROGRESS,
                GuideAnchors.NAV_BAR
            ),
            spec.steps.map { it.target }
        )
    }

    @Test
    fun `今日无计划时第二步换成计划引导卡`() {
        val spec = homeGuide(hasActivePlan = false, tablet = false)
        assertEquals(
            listOf(
                GuideAnchors.HOME_COUNTDOWN,
                GuideAnchors.HOME_PLAN_GUIDE,
                GuideAnchors.NAV_BAR
            ),
            spec.steps.map { it.target }
        )
    }

    @Test
    fun `平板导航文案指向左侧导航栏`() {
        val phone = homeGuide(hasActivePlan = true, tablet = false).steps.last().body
        val tablet = homeGuide(hasActivePlan = true, tablet = true).steps.last().body
        assertTrue(phone.contains("在底部"))
        assertTrue(tablet.contains("在左侧"))
    }

    @Test
    fun `计划页有计划和没计划各自两步且锚点正确`() {
        assertEquals(
            listOf(GuideAnchors.PLAN_OVERVIEW, GuideAnchors.PLAN_PRIMARY_ACTION),
            planGuide(hasPlan = true).steps.map { it.target }
        )
        assertEquals(
            listOf(GuideAnchors.PLAN_ACTION_CARD, GuideAnchors.PLAN_HISTORY_ACTION),
            planGuide(hasPlan = false).steps.map { it.target }
        )
    }

    @Test
    fun `专注统计我的步骤数与锚点正确`() {
        assertEquals(
            listOf(GuideAnchors.FOCUS_START, GuideAnchors.FOCUS_TASK_ROW, GuideAnchors.FOCUS_MODE),
            focusGuide().steps.map { it.target }
        )
        assertEquals(
            listOf(GuideAnchors.STATS_RANGE, GuideAnchors.STATS_KPI),
            statsGuide().steps.map { it.target }
        )
        assertEquals(
            listOf(
                GuideAnchors.MINE_HEADER,
                GuideAnchors.MINE_ACCOUNT_GROUP,
                GuideAnchors.MINE_GUIDE_ROW
            ),
            mineGuide().steps.map { it.target }
        )
    }

    @Test
    fun `今日第一步文案点出节点入口`() {
        val body = homeGuide(hasActivePlan = true, tablet = false).steps.first().body
        assertTrue(body.contains("「添加节点」"))
        assertTrue(body.contains("「管理」"))
    }

    @Test
    fun `统计第一步文案锁定时间范围说明`() {
        assertEquals(
            "今日、本周、本月、累计，下面的数字跟着变。",
            statsGuide().steps.first().body
        )
    }

    @Test
    fun `所有锚点都来自约定集合且文案非空`() {
        val specs = listOf(
            homeGuide(true, false), homeGuide(false, true),
            planGuide(true), planGuide(false),
            focusGuide(), statsGuide(), mineGuide()
        )
        specs.flatMap { it.steps }.forEach {
            assertTrue("未知锚点 ${it.target}", it.target in GuideAnchors.ALL)
            assertTrue("步骤文案不能为空", it.title.isNotBlank() && it.body.isNotBlank())
        }
    }
}
