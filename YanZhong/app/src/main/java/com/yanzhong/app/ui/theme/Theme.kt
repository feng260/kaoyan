package com.yanzhong.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
    secondary = Subject408Color,
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
    errorContainer = WarnRedContainer
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
    errorContainer = androidx.compose.ui.graphics.Color(0xFF93000A)
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
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

/** 倒计时预警档位(PRD 6.4):>30 正常 / ≤30 琥珀 / ≤10 红 / ≤3 红+呼吸 */
enum class CountdownAlertLevel { NORMAL, AMBER, RED, CRITICAL }

fun alertLevelOf(daysLeft: Long): CountdownAlertLevel = when {
    daysLeft <= 3 -> CountdownAlertLevel.CRITICAL
    daysLeft <= 10 -> CountdownAlertLevel.RED
    daysLeft <= 30 -> CountdownAlertLevel.AMBER
    else -> CountdownAlertLevel.NORMAL
}
