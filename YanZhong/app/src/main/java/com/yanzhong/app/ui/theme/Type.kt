package com.yanzhong.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

val AppTypography = Typography(
    // ---- display：仅倒计时主数字 ----
    displayLarge = base.displayLarge.copy(
        fontSize = 40.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum"
    ),
    displayMedium = base.displayMedium.copy(fontSize = 34.sp),
    displaySmall = base.displaySmall.copy(fontSize = 30.sp),

    // ---- headline：页面标题 / KPI ----
    headlineLarge = base.headlineLarge.copy(
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold
    ),
    headlineMedium = base.headlineMedium.copy(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum"
    ),
    headlineSmall = base.headlineSmall.copy(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold
    ),

    // ---- title：卡片 / 列表 ----
    titleLarge = base.titleLarge.copy(
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = base.titleMedium.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleSmall = base.titleSmall.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium
    ),

    // ---- body：正文 ----
    bodyLarge = base.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = "tnum"
    ),
    bodyMedium = base.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = "tnum"
    ),
    bodySmall = base.bodySmall.copy(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = "tnum"
    ),

    // ---- label：按钮 / 标签 ----
    labelLarge = base.labelLarge.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum"
    ),
    labelMedium = base.labelMedium.copy(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    ),
    labelSmall = base.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum"
    )
)

/** 紧凑指标（我的页头部、纪录卡）：20/26，仍走 tnum */
val MetricCompactStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    fontFeatureSettings = "tnum"
)

/** 倒计时与专注主数字：40/44 Bold tnum */
val CountdownNumberStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    lineHeight = 44.sp,
    fontFeatureSettings = "tnum"
)

/** 专注运行态主数字：48/52 Bold tnum（全 App 唯一的大于 40sp 的数字） */
val FocusTimerStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 48.sp,
    lineHeight = 52.sp,
    fontFeatureSettings = "tnum"
)
