package com.yanzhong.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// PRD 4.1 字号阶梯:倒计时主数字 40 / 页面大标题 22 / 卡片标题 16 / 正文 14 / 辅助 12
// 数字一律等宽(tabular-nums)防抖动

val TabularNums = TextStyle(fontFeatureSettings = "tnum")

private val base = Typography()

val AppTypography = Typography(
    displayLarge = base.displayLarge.copy(
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum"
    ),
    headlineLarge = base.headlineLarge.copy(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    headlineMedium = base.headlineMedium.copy(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = base.bodyLarge.copy(
        fontSize = 14.sp,
        fontFeatureSettings = "tnum"
    ),
    bodyMedium = base.bodyMedium.copy(
        fontSize = 12.sp,
        fontFeatureSettings = "tnum"
    ),
    labelLarge = base.labelLarge.copy(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum"
    ),
    labelMedium = base.labelMedium.copy(
        fontSize = 12.sp,
        fontFeatureSettings = "tnum"
    )
)

/** 倒计时等宽数字样式(40sp,粗体,tnum) */
val CountdownNumberStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    fontFeatureSettings = "tnum"
)
