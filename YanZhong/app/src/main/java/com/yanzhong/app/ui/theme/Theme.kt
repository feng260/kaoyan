package com.yanzhong.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.yanzhong.app.data.prefs.ThemeMode

/** 圆角体系(参考番茄ToDo):卡片大圆角 20dp → 控件 14dp → 标签 8dp */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val LightScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = BrandContainer,
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF10144A),
    secondary = SubjectCsColor,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFCFF2EA),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF003731),
    tertiary = TomatoOrange,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDBD1),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF3E0A02),
    background = androidx.compose.ui.graphics.Color(0xFFF7F7FB),
    onBackground = androidx.compose.ui.graphics.Color(0xFF1A1B26),
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = androidx.compose.ui.graphics.Color(0xFF1A1B26),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFEDEEF4),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF565A72),
    outline = androidx.compose.ui.graphics.Color(0xFFC6C8D4),
    error = WarnRed,
    errorContainer = WarnRedContainer,
    // ---- 以下 8 个槽位此前未覆盖,会漏出 Material 默认灰,与自定义靛蓝体系不一致 ----
    onError = androidx.compose.ui.graphics.Color.White,
    onErrorContainer = WarnRedContent,
    // 承载全部 divider 与描边,取比 outline 更浅一档
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFE2E4EC),
    // Snackbar 等反色表面的底色/字色
    inverseSurface = androidx.compose.ui.graphics.Color(0xFF1A1B26),
    inverseOnSurface = androidx.compose.ui.graphics.Color(0xFFF2F2F7),
    inversePrimary = BrandPrimaryDark,
    // 影响 Card/TextField 的 elevation 叠加色,取主色以保持品牌一致
    surfaceTint = BrandPrimary,
    scrim = Color(0x52000000)
)

/** 深色:低饱和靛蓝底,非纯灰(PRD 5.4) */
private val DarkScheme = darkColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF10144A),
    primaryContainer = BrandContainerDark,
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFE2E4FF),
    secondary = androidx.compose.ui.graphics.Color(0xFF6ED3BE),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF003731),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF005046),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFCFF2EA),
    tertiary = androidx.compose.ui.graphics.Color(0xFFFFB4A3),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF3E0A02),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF6E3126),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDBD1),
    background = DarkBackground,
    onBackground = androidx.compose.ui.graphics.Color(0xFFE6E7F0),
    surface = DarkSurface,
    onSurface = androidx.compose.ui.graphics.Color(0xFFE6E7F0),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFA8ABC8),
    outline = DarkOutline,
    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB),
    errorContainer = androidx.compose.ui.graphics.Color(0xFF93000A),
    // ---- 补齐 8 个槽位,与 LightScheme 一一对应 ----
    onError = androidx.compose.ui.graphics.Color(0xFF690005),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFDAD6),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF2A2E4F),
    inverseSurface = androidx.compose.ui.graphics.Color(0xFFE6E7F0),
    inverseOnSurface = androidx.compose.ui.graphics.Color(0xFF2A2C3C),
    inversePrimary = BrandPrimary,
    surfaceTint = BrandPrimaryDark,
    scrim = Color(0x8A000000)
)

@Composable
fun YanZhongTheme(
    themeMode: Int = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    // 系统栏图标跟随「App 内实际主题」,而不是系统深浅。
    // enableEdgeToEdge() 只在启动时按系统 uiMode 定一次色;用户在「我的」里切浅色后,
    // 透明状态栏上的图标仍是白色,浅色背景上几乎看不见。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 立即行动类功能卡渐变(珊瑚行动):深色模式自动降一档但仍保持白字对比度。
 * 判定用当前主题背景亮度——跟随系统与手动选择深色两种来源都能覆盖。
 */
@Composable
fun actionCardGradient(): List<Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) listOf(CtaStartDark, CtaEndDark) else listOf(CtaStart, CtaEnd)
}

/** 内嵌槽统一底色:图表底、次级块、输入框底色都从这里取 */
@Composable
fun insetSurfaceColor(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

/** 成功态文字色:浅色主题用深绿、深色主题用提亮绿,12% 绿底上都 ≥4.5:1 */
val successContentColor: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) SuccessContentGreenDark else SuccessContentGreen

/** 倒计时预警档位(PRD 6.4):>30 正常 / ≤30 琥珀 / ≤10 红 / ≤3 红+呼吸 */
enum class CountdownAlertLevel { NORMAL, AMBER, RED, CRITICAL }

fun alertLevelOf(daysLeft: Long): CountdownAlertLevel = when {
    daysLeft <= 3 -> CountdownAlertLevel.CRITICAL
    daysLeft <= 10 -> CountdownAlertLevel.RED
    daysLeft <= 30 -> CountdownAlertLevel.AMBER
    else -> CountdownAlertLevel.NORMAL
}
