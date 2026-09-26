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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
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
import com.yanzhong.app.ui.nav.YanZhongAppRoot
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.NightSkyBottom
import com.yanzhong.app.ui.theme.NightSkyTop
import com.yanzhong.app.ui.theme.YanZhongTheme
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        val app = application as YanZhongApp
        setContent {
            val themeMode by app.settingsRepo.settings
                .map { it.themeMode }
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            YanZhongTheme(themeMode = themeMode) {
                // 启动两道门:①登录 ②备考档案。两段都过完才进主界面
                val auth by app.authState.collectAsStateWithLifecycle()
                when (auth) {
                    null -> AuthChecking()
                    false -> LoginScreen()
                    else -> ProfileGate()
                }
            }
        }
    }

    /** 登录态检查过场:夜空底 + 品牌,通常一闪而过 */
    @androidx.compose.runtime.Composable
    private fun AuthChecking() {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(NightSkyTop, NightSkyBottom))),
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
        // remember 在这里正好当"本次进门只跑一次"用——ProfileGate 会随退出登录
        // 离开组合树,下次登录重新进入时这段代码自然重跑。
        androidx.compose.runtime.remember { vm.beginSession() }

        val state by vm.state.collectAsStateWithLifecycle()

        when (state.phase) {
            com.yanzhong.app.ui.onboarding.OnboardingPhase.LOADING -> AuthChecking()
            com.yanzhong.app.ui.onboarding.OnboardingPhase.EDITING,
            com.yanzhong.app.ui.onboarding.OnboardingPhase.SUBMITTING ->
                com.yanzhong.app.ui.onboarding.OnboardingScreen(vm)
            com.yanzhong.app.ui.onboarding.OnboardingPhase.FALLBACK ->
                if (state.awaitingChoice) com.yanzhong.app.ui.onboarding.OnboardingFallbackScreen(vm)
                else YanZhongAppRoot()
            com.yanzhong.app.ui.onboarding.OnboardingPhase.SUCCESS -> YanZhongAppRoot()
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

    /** 通知权限(Android 13+):仅影响提醒,拒绝不影响计时(PRD 3.13 边界) */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
