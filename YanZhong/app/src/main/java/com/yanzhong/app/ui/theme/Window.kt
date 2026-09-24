package com.yanzhong.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** 平板/宽屏断点(Material 窗口尺寸类):≥600dp */
@Composable
fun isTablet(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** 扩展宽度(≥840dp):平板双栏布局启用点——中等宽度仍单栏限宽,足够宽才分两栏 */
@Composable
fun isExpanded(): Boolean = LocalConfiguration.current.screenWidthDp >= 840

/** 宽屏下页面内容限宽居中,避免整屏拉伸(平板左侧为导航栏) */
val CONTENT_MAX_WIDTH = 720.dp
