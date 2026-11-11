package com.yanzhong.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanzhong.app.data.prefs.ThemeMode
import com.yanzhong.app.service.FocusService
import com.yanzhong.app.ui.auth.LoginScreen
import com.yanzhong.app.ui.legal.ConsentGate
import com.yanzhong.app.ui.nav.YanZhongAppRoot
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.BrandGradient
import com.yanzhong.app.ui.theme.YanZhongTheme
import com.yanzhong.app.ui.update.UpdateHost
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as YanZhongApp
        setContent {
            // Flow 操作符必须待在 remember 里:否则每次重组都会新建一条 Flow,
            // collectAsStateWithLifecycle 会随之重新订阅(任意一次重组都把主题流重来一遍)。
            val themeModeFlow = remember {
                app.settingsRepo.settings.map { it.themeMode }
            }
            val themeMode by themeModeFlow
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            YanZhongTheme(themeMode = themeMode) {
                val auth by app.authState.collectAsStateWithLifecycle()
                // 三态(读取中/未同意/已同意)而不是 initialValue=false:
                // 后者会让老用户每次冷启动都先闪一帧同意门,再跳到登录/主界面。
                val consent by app.consentAccepted.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize()) {
                    // 启动三道门:①隐私同意 ②登录 ③备考档案。三段都过完才进主界面。
                    // 同意门排在最前:前两道之后的页面一挂载就会发请求(档案读取、同步、更新检查)。
                    when {
                        consent == false -> ConsentGate()
                        consent == null || auth == null -> AuthChecking()
                        auth == false -> LoginScreen()
                        else -> ProfileGate()
                    }
                    // 更新检查要盖在登录门之上(旧版本可能连登录都不可用,强更不能被卡在门外),
                    // 但它是网络请求,同意之前一次都不能发。
                    if (consent == true) UpdateHost()
                    // 通知权限同理:同意前不申请,同意且登录后解释一次用途再申请
                    if (consent == true && auth == true) NotificationPermissionPrompt()
                }
            }
        }
    }

    /** 登录态检查过场:品牌渐变底 + 白字,通常一闪而过 */
    @androidx.compose.runtime.Composable
    private fun AuthChecking() {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(BrandGradient)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    AppIcons.GraduationCap,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
            }
        }
    }

    /**
     * 第二道门:备考档案。
     *
     * 分流规则只有一条底线——**服务端出问题绝不挡住本机学习**。
     * 所以只有两种情况会让用户停下来填问卷或做选择:
     *   1. 档案确实不完整(服务端明确说缺);
     *   2. 档案存好了但计划没排出来,需要当场告诉他一句。
     * 单纯的网络不通走 FALLBACK 且 awaitingChoice=false,直接放行进主界面,
     * 重试入口留在「我的」页——高铁上打开 App 的人不该看见一个问卷。
     */
    @androidx.compose.runtime.Composable
    private fun ProfileGate() {
        val vm: com.yanzhong.app.ui.onboarding.OnboardingViewModel =
            androidx.lifecycle.viewmodel.compose.viewModel()

        // 先清零再读状态:顺序反了就会有一帧用上一位用户的结论放行。
        // 清零必须同步发生在组合期,不能挪进 LaunchedEffect —— 那样正好晚一帧,
        // 短暂闪出上一位用户的计划。所以仍用 remember;区别是块的返回值改成 vm.state
        // 而不是 Unit:既满足 lint 的「remember 不得返回 Unit」,又保持"本次进门只跑一次"
        // 的语义(ProfileGate 随退出登录离开组合树,下次登录重进时自然重跑)。
        val state by remember(vm) { vm.beginSession(); vm.state }.collectAsStateWithLifecycle()

        when (state.phase) {
            com.yanzhong.app.ui.onboarding.OnboardingPhase.LOADING -> AuthChecking()
            com.yanzhong.app.ui.onboarding.OnboardingPhase.EDITING,
            com.yanzhong.app.ui.onboarding.OnboardingPhase.SUBMITTING ->
                com.yanzhong.app.ui.onboarding.OnboardingScreen(vm)
            com.yanzhong.app.ui.onboarding.OnboardingPhase.FALLBACK ->
                if (state.awaitingChoice) com.yanzhong.app.ui.onboarding.OnboardingFallbackScreen(vm)
                else YanZhongAppRoot()
            // 刚存完档案但还没有计划:直接落到计划页,那里「和 AI 聊 5 分钟」的入口就在最上面
            com.yanzhong.app.ui.onboarding.OnboardingPhase.SUCCESS ->
                YanZhongAppRoot(
                    startDestination = if (state.profileSaved && state.plan == null) {
                        com.yanzhong.app.ui.nav.Routes.PLAN
                    } else {
                        com.yanzhong.app.ui.nav.Routes.HOME
                    }
                )
        }
    }

    /**
     * 学霸模式防逃逸(参考番茄ToDo):
     * 严格锁定期间任何离开研钟的行为(Home/划后台/其他应用),立即上报拦截引擎拉回;
     * 休息时段放行(可用任意应用),休息结束由引擎强制拉回。
     */
    override fun onStop() {
        super.onStop()
        val app = application as? YanZhongApp ?: return
        if (FocusService.guardActive && FocusService.guardStrictNow &&
            app.engine.state.value.isFocusing
        ) {
            FocusService.requestPullback(this)
        }
    }

    /**
     * 通知权限(Android 13+):仅影响提醒,拒绝不影响计时(PRD 3.13 边界)。
     *
     * 以前是在 onCreate 里直接弹系统授权框——用户还没看到同意门、不知道 App 拿数据做什么,
     * 就先被要一次通知权限,既不合规也容易被拒后永久关闭。现在改成同意并登录之后,
     * 先用一句话说明「为什么需要」再申请,并且每个分支都记下 notificationAsked,只问这一次。
     */
    @androidx.compose.runtime.Composable
    private fun NotificationPermissionPrompt() {
        val app = application as YanZhongApp
        // 默认 true:DataStore 尚未读出时按「已问过」处理,避免对话框闪现
        val asked by app.consentStore.notificationAsked
            .collectAsStateWithLifecycle(initialValue = true)
        var show by remember { mutableStateOf(false) }
        LaunchedEffect(asked) {
            if (asked) return@LaunchedEffect
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                app.appScope.launch { app.consentStore.markNotificationAsked() }
                return@LaunchedEffect
            }
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                app.appScope.launch { app.consentStore.markNotificationAsked() }
            } else {
                show = true
            }
        }
        if (show) {
            AlertDialog(
                onDismissRequest = {
                    show = false
                    app.appScope.launch { app.consentStore.markNotificationAsked() }
                },
                title = { Text("允许专注提醒吗？") },
                text = {
                    Text(
                        "允许通知后，番茄切换、休息结束和计划提醒才能及时送达；拒绝不影响计时与学习记录。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        show = false
                        app.appScope.launch { app.consentStore.markNotificationAsked() }
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text("允许") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        show = false
                        app.appScope.launch { app.consentStore.markNotificationAsked() }
                    }) { Text("暂不") }
                }
            )
        }
    }
}
