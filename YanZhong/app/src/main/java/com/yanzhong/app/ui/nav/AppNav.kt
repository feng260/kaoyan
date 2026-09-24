package com.yanzhong.app.ui.nav

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.timer.Phase
import com.yanzhong.app.ui.focus.FocusScreen
import com.yanzhong.app.ui.home.HomeScreen
import com.yanzhong.app.ui.mine.MineScreen
import com.yanzhong.app.ui.plan.PlanScreen
import com.yanzhong.app.ui.stats.StatsScreen
import com.yanzhong.app.ui.theme.AppIcons
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

object Routes {
    const val HOME = "home"
    const val PLAN = "plan"
    const val FOCUS = "focus"
    const val STATS = "stats"
    const val MINE = "mine"
    const val SUPERMODE = "supermode"
    const val APP_PICKER = "apppicker/{subjectId}"
    const val CLOUD = "cloud"
    const val WEBDAV = "webdav"

    /** 应用白名单选择页:subjectId=-1 为全局,其余为科目定制 */
    fun appPicker(subjectId: Long) = "apppicker/$subjectId"
}

private data class TabSpec(
    val route: String,
    val label: String,
    val icon: ImageVector
)

@Composable
fun YanZhongAppRoot() {
    val navController = rememberNavController()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as YanZhongApp
    // 只订阅低频的 isRunning:remainingMs 每秒变化,若在根部收集整包状态会引发整树每秒重组
    val timerRunning by remember {
        app.engine.state.map { it.isRunning }.distinctUntilChanged()
    }.collectAsState(initial = false)
    val tablet = com.yanzhong.app.ui.theme.isTablet()
    // 深层页(学霸模式/白名单选择)隐藏导航,防止误触 tab 丢弃未保存勾选
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val deepPage = currentRoute == Routes.SUPERMODE || currentRoute == Routes.CLOUD ||
        currentRoute == Routes.WEBDAV || currentRoute?.startsWith("apppicker") == true
    val showNav = !timerRunning && !deepPage

    // 应用内更新:启动时检查一次(需已配置服务器),有新版本弹窗 → 下载 → 调起安装
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var updateInfo by remember {
        mutableStateOf<com.yanzhong.app.data.remote.LatestVersionDto?>(null)
    }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val result = com.yanzhong.app.data.remote.AppUpdater.checkForUpdate()
        if (result.updateAvailable) updateInfo = result.latest
    }
    updateInfo?.let { info ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!info.forced && !downloading) updateInfo = null },
            title = { Text("发现新版本 ${info.versionName}") },
            text = {
                androidx.compose.foundation.layout.Column {
                    if (info.notes.isNotEmpty()) {
                        info.notes.forEach { note ->
                            Text("· $note", style = MaterialTheme.typography.bodyMedium)
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                    }
                    if (downloading) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                        )
                        Text(
                            "下载中 $downloadProgress%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
                    confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = !downloading,
                    onClick = {
                        downloading = true
                        scope.launch {
                            val result = com.yanzhong.app.data.remote.AppUpdater
                                .downloadAndInstall(context, info) { p -> downloadProgress = p }
                            downloading = false
                            result.onFailure { e ->
                                android.widget.Toast.makeText(
                                    context, "更新失败:${e.message}", android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) { Text(if (downloading) "下载中…" else "立即更新") }
            },
            dismissButton = {
                if (!info.forced && !downloading) {
                    androidx.compose.material3.TextButton(onClick = { updateInfo = null }) {
                        Text("稍后再说")
                    }
                }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
        // 平板:左侧导航栏(Material 适配规范);手机:底部导航栏
        if (tablet && showNav) {
            YanZhongNavRail(navController)
        }
        Scaffold(
            bottomBar = {
                if (!tablet && showNav) {
                    YanZhongBottomBar(navController)
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.HOME,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Routes.HOME) { HomeScreen(padding, navController) }
                    composable(Routes.PLAN) { PlanScreen(padding) }
                    composable(Routes.FOCUS) { FocusScreen(padding) }
                    composable(Routes.STATS) { StatsScreen(padding) }
                    composable(Routes.MINE) { MineScreen(padding, navController) }
                    composable(Routes.SUPERMODE) {
                        com.yanzhong.app.ui.supermode.SuperModeScreen(padding, navController)
                    }
                    composable(Routes.CLOUD) {
                        com.yanzhong.app.ui.cloud.CloudSyncScreen(padding, navController)
                    }
                    composable(Routes.WEBDAV) {
                        com.yanzhong.app.ui.webdav.WebDavScreen(padding, navController)
                    }
                    composable(
                        Routes.APP_PICKER,
                        arguments = listOf(
                            androidx.navigation.navArgument("subjectId") {
                                type = androidx.navigation.NavType.LongType
                            }
                        )
                    ) { entry ->
                        com.yanzhong.app.ui.supermode.AppPickerScreen(
                            padding,
                            navController,
                            entry.arguments?.getLong("subjectId") ?: -1L
                        )
                    }
                }
            }
        }
    }

        // 专注运行中(导航栏已隐藏):右下角悬浮迷你倒计时,点击回到专注屏
        if (timerRunning && currentRoute != Routes.FOCUS) {
            RunningTimerPill(
                navController,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
            )
        }
    }
}

/** 专注运行中的悬浮迷你倒计时:导航栏隐藏期间的全局返回专注入口;内部订阅引擎状态,秒级重组仅限本组件 */
@Composable
private fun RunningTimerPill(navController: NavHostController, modifier: Modifier = Modifier) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as YanZhongApp
    val timer by app.engine.state.collectAsState()
    if (!timer.isRunning) return
    val prefix = when (timer.phase) {
        Phase.PAUSED -> "已暂停 "
        Phase.SHORT_BREAK, Phase.LONG_BREAK -> "休息 "
        else -> ""
    }
    Surface(
        onClick = { navController.navigate(Routes.FOCUS) { launchSingleTop = true } },
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(
                AppIcons.TabFocus,
                contentDescription = "返回专注",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                prefix + formatPillTime(timer.remainingMs),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** 毫秒 → "mm:ss"(超 1 小时为 "h:mm:ss") */
private fun formatPillTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 平板左侧导航栏:顶部专注 FAB + 四个页签(专注入口由 FAB 承担;专注运行时整栏隐藏) */
@Composable
private fun YanZhongNavRail(navController: NavHostController) {
    val tabs = listOf(
        TabSpec(Routes.HOME, "今日", AppIcons.TabToday),
        TabSpec(Routes.PLAN, "计划", AppIcons.TabPlan),
        TabSpec(Routes.STATS, "统计", AppIcons.TabStats),
        TabSpec(Routes.MINE, "我的", AppIcons.TabMine)
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            FloatingActionButton(
                    onClick = {
                        navController.navigate(Routes.FOCUS) {
                            launchSingleTop = true
                            popUpTo(Routes.HOME) { saveState = true }
                            restoreState = true
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                Icon(
                    AppIcons.TabFocus,
                    contentDescription = "专注"
                )
            }
        }
    ) {
        tabs.forEach { tab ->
            NavigationRailItem(
                selected = currentRoute == tab.route,
                onClick = {
                    navController.navigate(tab.route) {
                        launchSingleTop = true
                        popUpTo(Routes.HOME) { saveState = true }
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label
                    )
                },
                label = { Text(tab.label) }
            )
        }
    }
}

@Composable
private fun YanZhongBottomBar(navController: NavHostController) {
    val tabs = listOf(
        TabSpec(Routes.HOME, "今日", AppIcons.TabToday),
        TabSpec(Routes.PLAN, "计划", AppIcons.TabPlan),
        TabSpec(Routes.FOCUS, "专注", AppIcons.TabFocus),
        TabSpec(Routes.STATS, "统计", AppIcons.TabStats),
        TabSpec(Routes.MINE, "我的", AppIcons.TabMine)
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Box {
        NavigationBar(
            tonalElevation = 2.dp
        ) {
            tabs.forEach { tab ->
                if (tab.route == Routes.FOCUS) {
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { navController.navigate(tab.route) { launchSingleTop = true } },
                        icon = { Box(Modifier.size(24.dp)) },
                        label = { Text(tab.label) },
                        enabled = false // 由中央凸起按钮接管点击
                    )
                } else {
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                launchSingleTop = true
                                popUpTo(Routes.HOME) { saveState = true }
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }

        // 中央凸起专注按钮:全局常驻入口(PRD 4.2);专注中显示迷你倒计时(PRD 4.3)
        FloatingActionButton(
            onClick = {
                navController.navigate(Routes.FOCUS) {
                    launchSingleTop = true
                    popUpTo(Routes.HOME) { saveState = true }
                    restoreState = true
                }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-8).dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                AppIcons.TabFocus,
                contentDescription = "专注"
            )
        }
    }
}
