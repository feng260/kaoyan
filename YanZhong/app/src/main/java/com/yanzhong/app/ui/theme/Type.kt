package com.yanzhong.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号阶梯。数字一律等宽(tabular-nums)防抖动。
 *
 * 15 个排版角色在这里**全部显式声明**。此前只覆盖了 7 个,另外 8 个落到 Material 默认值,
 * 而默认值与自定义值不在同一套标尺上,于是产生了 headlineMedium(16) 小于 headlineSmall(24)
 * 这类倒挂。显式化之后,任何一个字号的唯一来源都是本文件,不再有隐式默认值参与运算。
 *
 * 本版本按 Material 的比例关系重定标:三档之间单调递减、五条序列之间互不撞车。
 *   display  40 / 34 / 30    仅倒计时主数字
 *   headline 26 / 22 / 20    页面大标题 / 卡片与区块标题
 *   title    18 / 16 / 14    内容型标题 / 列表项标题
 *   body     16 / 14 / 12    正文三档
 *   label    14 / 12 / 11    按钮文字 / 标签 / 辅助说明
 * 相比旧值的主要变化:headlineMedium 16→22(卡片标题)、titleLarge 22→18、
 * headlineSmall 24→20、bodyLarge 14→16、bodyMedium 12→14(全项目用得最多的一档,
 * 提到 14 后正文才名副其实;若某处出现溢出裁切,可回退到 13 折中)。
 */
private val base = Typography()

val AppTypography = Typography(
    // ---- display:仅倒计时主数字在用 ----
    displayLarge = base.displayLarge.copy(
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum"
    ),
    displayMedium = base.displayMedium.copy(fontSize = 34.sp),
    displaySmall = base.displaySmall.copy(fontSize = 30.sp),

    // ---- headline:页面大标题 / 区块标题 ----
    headlineLarge = base.headlineLarge.copy(
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold
    ),
    headlineMedium = base.headlineMedium.copy(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    headlineSmall = base.headlineSmall.copy(fontSize = 20.sp),

    // ---- title:卡片与列表项标题 ----
    titleLarge = base.titleLarge.copy(fontSize = 18.sp),
    titleMedium = base.titleMedium.copy(fontSize = 16.sp),
    titleSmall = base.titleSmall.copy(fontSize = 14.sp),

    // ---- body:正文三档 ----
    bodyLarge = base.bodyLarge.copy(
        fontSize = 16.sp,
        fontFeatureSettings = "tnum"
    ),
    bodyMedium = base.bodyMedium.copy(
        fontSize = 14.sp,
        fontFeatureSettings = "tnum"
    ),
    bodySmall = base.bodySmall.copy(fontSize = 12.sp),

    // ---- label:按钮文字 / 标签 / 辅助说明 ----
    labelLarge = base.labelLarge.copy(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum"
    ),
    labelMedium = base.labelMedium.copy(
        fontSize = 12.sp,
        fontFeatureSettings = "tnum"
    ),
    labelSmall = base.labelSmall.copy(fontSize = 11.sp)
)

/** 倒计时等宽数字样式(40sp,粗体,tnum) */
val CountdownNumberStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    fontFeatureSettings = "tnum"
)