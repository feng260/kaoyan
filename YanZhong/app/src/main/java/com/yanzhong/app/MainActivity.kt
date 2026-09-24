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
                // 启动登录门:未登录→登录页,已登录→主界面,检查中→品牌过场
                val auth by app.authState.collectAsStateWithLifecycle()
                when (auth) {
                    null -> AuthChecking()
                    false -> LoginScreen()
                    else -> YanZhongAppRoot()
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
