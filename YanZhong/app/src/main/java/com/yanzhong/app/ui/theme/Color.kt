package com.yanzhong.app.ui.theme

import androidx.compose.ui.graphics.Color

// 科目色(PRD 4.1):贯穿任务标签、专注环、图表
val MathColor = Color(0xFF5865F2)
val SubjectCsColor = Color(0xFF18A999)
val EnglishColor = Color(0xFFFF8A5C)
val PoliticsColor = Color(0xFFE8618C)

// 预警色(PRD 6.4):30 天琥珀 / 10 天红
val WarnAmber = Color(0xFFB8860B)
val WarnAmberContainer = Color(0xFFFFF3D6)
val WarnRed = Color(0xFFD32F2F)
val WarnRedContainer = Color(0xFFFFDAD6)
val WarnAmberContent = Color(0xFF5C4400)
val WarnRedContent = Color(0xFF5A0F0F)

// 中性标签色:未匹配学科时的默认彩点/标签色
val NeutralTagColor = Color(0xFF64748B)

// 品牌色:低饱和靛蓝系
val BrandPrimary = Color(0xFF5865F2)
val BrandPrimaryDark = Color(0xFFA9B0FF)
val BrandContainer = Color(0xFFE2E4FF)
val BrandContainerDark = Color(0xFF40499A)

/** 品牌深靛 → 深紫:白字对比度 8.4:1 / 6.4:1,深浅模式共用 */
val BrandGradientStart = Color(0xFF4338CA)
val BrandGradientEnd = Color(0xFF6D28D9)
val BrandGradient = listOf(BrandGradientStart, BrandGradientEnd)

/** 珊瑚行动渐变:白字 ≥ 4.7:1;深色模式降一档但保持对比度 */
val CtaStart = Color(0xFFD9482E)
val CtaEnd = Color(0xFFB3321F)
val CtaStartDark = Color(0xFFB3321F)
val CtaEndDark = Color(0xFF8F2717)

// 深色模式底色:低饱和靛蓝,非纯灰(PRD 5.4)
val DarkBackground = Color(0xFF12142A)
val DarkSurface = Color(0xFF1A1D38)
val DarkSurfaceVariant = Color(0xFF232647)
val DarkOutline = Color(0xFF3B3F66)

val TomatoOrange = Color(0xFFFF7043)
val SuccessGreen = Color(0xFF2E9E5B)

// ---- 功能卡色板(参考番茄ToDo 一功能一色)----
// 薄荷绿:放松 / 小憩类功能
val MintGreen = Color(0xFF00B8A9)
val MintGreenDeep = Color(0xFF009688)
// 珊瑚红:立即行动 / 番茄专注类
val CoralRed = Color(0xFFFF7059)
val CoralRedDeep = Color(0xFFEF5B43)
// 天蓝:规划 / 定制类
val SkyBlue = Color(0xFF4FA8F5)
val SkyBlueDeep = Color(0xFF3784D9)
// 琥珀:设置 / 权限类
val AmberGold = Color(0xFFF5A83C)
val AmberGoldDeep = Color(0xFFE88E1B)

// ---- 参考计划(468 天全程作战计划)设计语言 ----
val PlanAccent = Color(0xFF4F46E5)   // --accent 靛蓝
val PlanAccent2 = Color(0xFF7C3AED)  // --accent2 紫
val PlanTeal = Color(0xFF0D9488)     // --c-en 青绿
val PlanInk = Color(0xFF1C2240)      // --ink
val PlanMuted = Color(0xFF5F6A8A)    // --muted
val PlanBg = Color(0xFFF2F4FB)       // --bg
