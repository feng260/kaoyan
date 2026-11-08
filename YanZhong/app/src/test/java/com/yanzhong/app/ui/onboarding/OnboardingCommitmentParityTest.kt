package com.yanzhong.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingCommitmentParityTest {

    @Test
    fun `单双周标记只认 odd 与 even`() {
        assertEquals("odd", normalizeWeekParity("odd"))
        assertEquals("even", normalizeWeekParity("even"))
    }

    @Test
    fun `服务端识别出的中文或大写标记一律退化成每周都占`() {
        // OCR 提示词要求输出 odd/even,但模型偶尔会写「单周」;
        // 非法值退化成 null(每周都占)比整条占用消失安全
        assertEquals(null, normalizeWeekParity("单周"))
        assertEquals(null, normalizeWeekParity("ODD"))
        assertEquals(null, normalizeWeekParity(""))
        assertEquals(null, normalizeWeekParity(null))
    }

    @Test
    fun `界面上显示人话而不是 odd_even`() {
        assertEquals("单周", parityLabel("odd"))
        assertEquals("双周", parityLabel("even"))
        assertEquals("每周", parityLabel(null))
        assertEquals("每周", parityLabel("单周"))
    }

    @Test
    fun `校历第 1 周只接受完整年月日`() {
        assertEquals("2026-09-07", normalizeTermAnchor("2026-09-07"))
        assertEquals("2026-09-07", normalizeTermAnchor("  2026-09-07  "))
        assertEquals("", normalizeTermAnchor("2026-9-7"))
        assertEquals("", normalizeTermAnchor("下周一"))
        assertEquals("", normalizeTermAnchor(""))
        assertEquals("", normalizeTermAnchor("2026-02-30"))
    }
}
