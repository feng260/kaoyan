package com.yanzhong.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * 窗口尺寸断点(Material 窗口尺寸类语义):COMPACT < 600dp ≤ MEDIUM < 840dp ≤ EXPANDED。
 *
 * 为什么用手写阈值而不是 androidx 的 WindowSizeClass:
 * 曾尝试接入 `androidx.compose.material3.adaptive` + `material3-window-size-class`,
 * 但与本项目 Compose BOM(2024.09.03 / compose 1.7.3)配套的 window-size-class 1.3.0
 * 已把尺寸类常量收成内部实现,公开 API 只剩 `calculateFromSize` 与尺寸类「集合」,
 * 拿不到可直接比较的 compact/medium/expanded,要用起来只能做集合成员判断;
 * 而更新的版本需要整体升级 Compose BOM,爆炸半径远大于收益。
 * 这两行读的 screenWidthDp 与官方断点数值完全一致,信息等价,故保持现状。
 */

/** 平板/宽屏(≥600dp):底部导航换左侧 NavigationRail */
@Composable
fun isTablet(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** 扩展宽度(≥840dp):足以支撑双栏,中等宽度仍走单栏限宽 */
@Composable
fun isExpanded(): Boolean = LocalConfiguration.current.screenWidthDp >= 840

/** 宽屏下页面内容限宽居中,避免整屏拉伸(平板左侧为导航栏) */
val CONTENT_MAX_WIDTH = 720.dp