package com.yanzhong.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.yanzhong.app.MainActivity
import com.yanzhong.app.R
import com.yanzhong.app.timer.Phase
import com.yanzhong.app.util.TimeUtils

/**
 * 番茄钟前台保活服务(PRD 3.2 / 5.3):
 * 常驻通知展示剩余时间,保证后台计时不被系统回收;
 * Android 14+ 声明 specialUse 前台服务类型(见 Manifest)。
 *
 * 兼学霸模式拦截引擎(参考番茄ToDo):
 * - 标准模式:轮询识别前台应用,白名单内放行,否则锁屏拦截;
 * - 严格模式:任何离开研钟的行为都被立即拉回,仅计时结束或 6 位紧急退出密码可解除;
 * - 拦截优先走全屏覆盖层(TYPE_APPLICATION_OVERLAY):非白名单应用直接被覆盖层锁定触摸,
 *   消掉按 Home 后的最大逃逸口;未授予「显示在其他应用上层」时回退为轮询拉回方案;
 * - 前台服务身份使本应用具备后台启动 Activity 豁免,覆盖层按钮拉回在 Android 10+ 生效;
 * - 覆盖层局限:拦不住下拉状态栏(需配合无障碍才完整)。
 */
class FocusService : Service() {

    private var guardOn = false
    private var guardStrict = false
    private var allowPkgs: Set<String> = emptySet()
    private var guardThread: HandlerThread? = null
    private var guardHandler: Handler? = null
    private var usageStats: UsageStatsManager? = null
    private var lastPullAt = 0L
    private var lastToastAt = 0L

    /** 悬浮窗覆盖层状态:视图挂载于主线程,期望态由守护线程写入 */
    @Volatile private var overlayView: View? = null
    @Volatile private var overlayWanted = false
    @Volatile private var overlayFailed = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent == null) {
            // 服务被系统重建(START_STICKY,如后台被划掉):学霸模式在跑则重新激活拦截
            // 引擎现场由 YanZhongApp 启动时 restoreIfNeeded 恢复,这里只需恢复守护规则
            (applicationContext as? com.yanzhong.app.YanZhongApp)?.let { app ->
                if (app.engine.state.value.isRunning) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val snap = app.settingsRepo.current()
                        if (snap.superModeOn) {
                            val subjectId = app.engine.state.value.subjectId
                            val allow = subjectId?.let { snap.subjectWhitelist[it] } ?: snap.whitelist
                            startGuard(applicationContext, snap.superModeStrict, allow)
                        }
                    }
                }
            }
            return START_STICKY
        }
        when (intent.action) {
            ACTION_GUARD_ON -> {
                guardStrict = intent.getBooleanExtra(EXTRA_STRICT, false)
                allowPkgs = intent.getStringArrayListExtra(EXTRA_ALLOW)?.toSet() ?: emptySet()
                guardOn = true
                guardActive = true
                guardStrictNow = guardStrict
                ensureGuardLoop()
                if (guardStrict) {
                    toast(
                        if (hasOverlayPermission(this)) "学霸模式已激活 · 严格锁定,覆盖层拦截已开启"
                        else "学霸模式已激活 · 严格锁定,计时结束前无法离开"
                    )
                } else if (hasUsageAccess(this)) {
                    toast(
                        if (hasOverlayPermission(this)) "学霸模式已激活 · 白名单 ${allowPkgs.size} 个应用可用,覆盖层拦截已开启"
                        else "学霸模式已激活 · 白名单 ${allowPkgs.size} 个应用可用(建议开启悬浮窗权限增强拦截)"
                    )
                } else {
                    toast("学霸模式已激活,但未授予「使用情况访问」,无法识别白名单应用")
                }
            }
            ACTION_PULLBACK -> if (guardOn) {
                // 严格模式 onStop 即时上报:覆盖层兜底 + 立即拉回研钟
                showOverlay(guardStrict)
                pullBack(
                    if (guardStrict) "严格模式锁定中 · 计时结束前无法离开研钟"
                    else "应用已禁用 · 专注期间仅可使用白名单应用"
                )
            }
            ACTION_GUARD_OFF -> clearGuard()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        clearGuard()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification(this, Phase.FOCUSING, 0, "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(ONGOING_ID, notification)
        }
    }

    // ---- 学霸模式拦截 ----

    private fun ensureGuardLoop() {
        if (guardHandler != null) return
        val thread = HandlerThread("super_mode_guard").apply { start() }
        guardThread = thread
        guardHandler = Handler(thread.looper)
        guardHandler?.post(guardTick)
    }

    private fun clearGuard() {
        guardOn = false
        guardActive = false
        hideOverlay()
        guardHandler?.removeCallbacks(guardTick)
        guardHandler = null
        guardThread?.quitSafely()
        guardThread = null
    }

    /**
     * 每秒轮询前台应用:严格=一律锁定;标准=非白名单锁定(无使用情况权限则返回 null 不拦截)。
     * 休息时段(PomodoroEngine.isFocusingNow=false)完全解除拦截:
     * 可使用任意应用、可切后台,专注恢复后继续守护——休息结束后由引擎拉回研钟。
     */
    private val guardTick: Runnable = object : Runnable {
        override fun run() {
            if (!guardOn) return
            if (!com.yanzhong.app.timer.PomodoroEngine.isFocusingNow) {
                hideOverlay() // 休息中:撤掉覆盖层,放行一切应用
            } else {
                val fg = foregroundPkg()
                if (fg != null) {
                    if (fg == packageName) {
                        // 回到研钟自身:撤掉覆盖层
                        hideOverlay()
                    } else {
                        val blocked = if (guardStrict) true else fg !in allowPkgs
                        if (blocked) {
                            if (!showOverlay(guardStrict)) {
                                // 无悬浮窗权限:回退拉回方案
                                pullBack(
                                    if (guardStrict) "严格模式锁定中 · 计时结束前无法离开研钟"
                                    else "应用已禁用 · 专注期间仅可使用白名单应用"
                                )
                            }
                        } else {
                            // 白名单应用:放开覆盖层
                            hideOverlay()
                        }
                    }
                }
            }
            guardHandler?.postDelayed(this, GUARD_POLL_MS)
        }
    }

    /** 近 5s 内最后一次前台切换的包名;未授权使用情况访问时返回 null */
    private fun foregroundPkg(): String? {
        if (!hasUsageAccess(this)) return null
        val manager = usageStats
            ?: getSystemService(UsageStatsManager::class.java)?.also { usageStats = it }
            ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { manager.queryEvents(now - 5_000, now) }.getOrNull()
            ?: return null
        var pkg: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                pkg = event.packageName
            }
        }
        return pkg
    }

    // ---- 悬浮窗覆盖层(防逃逸增强) ----

    /**
     * 请求展示全屏锁定覆盖层:返回 false 表示无悬浮窗权限或挂载失败,调用方回退拉回方案。
     * 期望态立即写入,视图构建/挂载切换到主线程执行。
     */
    private fun showOverlay(strict: Boolean): Boolean {
        if (overlayFailed) return false
        if (!Settings.canDrawOverlays(this)) return false
        overlayWanted = true
        if (overlayView != null) return true
        mainHandler.post { addOverlayInternal(strict) }
        return true
    }

    /** 撤掉覆盖层:白名单应用/回到研钟时调用 */
    private fun hideOverlay() {
        overlayWanted = false
        if (overlayView == null) return
        mainHandler.post { removeOverlayInternal() }
    }

    private fun addOverlayInternal(strict: Boolean) {
        if (!overlayWanted || overlayView != null) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val view = buildOverlayView(strict)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        val added = runCatching { wm.addView(view, params) }.isSuccess
        if (added) {
            overlayView = view
        } else {
            // 挂载失败(厂商悬浮窗管控等):标记后守护循环回退拉回方案
            overlayFailed = true
            overlayWanted = false
        }
    }

    private fun removeOverlayInternal() {
        val view = overlayView ?: return
        val wm = getSystemService(WindowManager::class.java) ?: return
        runCatching { wm.removeView(view) }
        overlayView = null
    }

    /** 覆盖层视图:深夜色全屏,拦截一切触摸;提供返回研钟与白名单快捷入口 */
    private fun buildOverlayView(strict: Boolean): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        fun roundedBg(color: Int, radiusDp: Int): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radiusDp).toFloat()
            }

        val root = FrameLayout(this).apply { setBackgroundColor(OVERLAY_BG) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(56), dp(28), dp(48))
        }
        val lpColumn = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )

        fun addTitle(text: String) {
            column.addView(TextView(this).apply {
                this.text = text
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
        }

        fun addSub(text: String) {
            column.addView(TextView(this).apply {
                this.text = text
                setTextColor(OVERLAY_TEXT_DIM)
                textSize = 14f
                gravity = Gravity.CENTER
                setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }

        fun addSpace(h: Int) {
            column.addView(Space(this), LinearLayout.LayoutParams(1, dp(h)))
        }

        fun addPrimaryButton(label: String, onClick: () -> Unit) {
            column.addView(Button(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                isAllCaps = false
                background = roundedBg(OVERLAY_ACCENT, 24)
                minHeight = dp(48)
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) })
        }

        fun launchApp(pkg: String) {
            runCatching {
                packageManager.getLaunchIntentForPackage(pkg)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    applicationContext.startActivity(it)
                }
            }
        }

        fun backToYanZhong() {
            runCatching {
                startActivity(
                    Intent(applicationContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        if (strict) {
            addTitle("严格模式锁定中")
            addSub("计时结束前无法离开研钟\n如遇突发情况,回到研钟后可在专注页使用紧急退出")
            addSpace(24)
            addPrimaryButton("返回研钟专注", ::backToYanZhong)
        } else {
            addTitle("应用已禁用")
            addSub("专注期间仅可使用白名单应用")
            addSpace(20)

            // 白名单快捷入口:锁定状态下仍可直达可用应用(最多展示 6 个)
            val launchable = allowPkgs
                .mapNotNull { pkg ->
                    runCatching {
                        if (packageManager.getLaunchIntentForPackage(pkg) != null) {
                            pkg to (packageManager.getApplicationLabel(
                                packageManager.getApplicationInfo(pkg, 0)
                            ).toString())
                        } else null
                    }.getOrNull()
                }
                .sortedBy { it.second }
            if (launchable.isNotEmpty()) {
                val scroll = ScrollView(this).apply {
                    isVerticalScrollBarEnabled = false
                    val inner = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 0, 0, dp(4))
                    }
                    val shown = launchable.take(6)
                    shown.forEach { (pkg, label) ->
                        inner.addView(TextView(context).apply {
                            text = "返回 $label"
                            setTextColor(0xFFFFFFFF.toInt())
                            textSize = 14f
                            gravity = Gravity.CENTER
                            background = roundedBg(OVERLAY_CHIP, 18)
                            minHeight = dp(42)
                            setOnClickListener { launchApp(pkg) }
                        }, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(8) })
                    }
                    if (launchable.size > shown.size) {
                        inner.addView(TextView(context).apply {
                            text = "…以及另外 ${launchable.size - shown.size} 个白名单应用"
                            setTextColor(OVERLAY_TEXT_DIM)
                            textSize = 12f
                            gravity = Gravity.CENTER
                        }, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(6) })
                    }
                    addView(inner)
                }
                column.addView(
                    scroll,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            addPrimaryButton("返回研钟专注", ::backToYanZhong)
        }

        root.addView(column, lpColumn)
        return root
    }

    /** 拉回研钟:前台服务具备后台启动 Activity 豁免;限频防止风暴 */
    private fun pullBack(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPullAt < PULL_MIN_INTERVAL_MS) return
        lastPullAt = now
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            )
        }
        if (now - lastToastAt < TOAST_MIN_INTERVAL_MS) return
        lastToastAt = now
        mainHandler.post { Toast.makeText(applicationContext, reason, Toast.LENGTH_SHORT).show() }
    }

    private fun toast(text: String) {
        mainHandler.post { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val CHANNEL_ONGOING = "focus_ongoing"
        private const val CHANNEL_EVENT = "focus_event"
        private const val ONGOING_ID = 1001
        private const val EVENT_ID = 1002

        private const val ACTION_GUARD_ON = "com.yanzhong.app.GUARD_ON"
        private const val ACTION_GUARD_OFF = "com.yanzhong.app.GUARD_OFF"
        private const val ACTION_PULLBACK = "com.yanzhong.app.GUARD_PULLBACK"
        private const val EXTRA_STRICT = "strict"
        private const val EXTRA_ALLOW = "allow"
        private const val GUARD_POLL_MS = 1000L
        private const val PULL_MIN_INTERVAL_MS = 900L
        private const val TOAST_MIN_INTERVAL_MS = 5000L

        // 覆盖层配色(深夜色系,与 DarkBackground/MintGreenDeep 一脉)
        private const val OVERLAY_BG = 0xF612142A.toInt()
        private const val OVERLAY_TEXT_DIM = 0xB3FFFFFF.toInt()
        private const val OVERLAY_ACCENT = 0xFF009688.toInt()
        private const val OVERLAY_CHIP = 0x26FFFFFF.toInt()

        /** 拦截状态快照:供 Activity 生命周期回调即时查询(严格模式 onStop 拉回) */
        @Volatile
        var guardActive: Boolean = false
            private set

        @Volatile
        var guardStrictNow: Boolean = false
            private set

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // 通道设置创建后不可变:静音版通道(无声无震)需删旧重建,一次性迁移
            val prefs = context.getSharedPreferences("focus_service", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("event_channel_v2", false)) {
                nm.deleteNotificationChannel(CHANNEL_EVENT)
                prefs.edit().putBoolean("event_channel_v2", true).apply()
            }
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING,
                    "专注计时",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "番茄钟进行中的常驻通知"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_EVENT,
                    "阶段提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "专注与休息开始/结束的强提醒(横幅弹出)"
                    // 声音/震动全由应用按设置项(含静音模式)控制:通道自身不出声不震动,
                    // 否则系统默认提示音会绕过 soundOn/silentMode
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        /** 使用情况访问权限状态(标准模式白名单识别依赖) */
        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }

        /** 悬浮窗权限状态(「显示在其他应用上层」,覆盖层拦截依赖) */
        fun hasOverlayPermission(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        /** 引擎开钟时调用:激活拦截规则(标准=白名单放行集;严格=完全锁定) */
        fun startGuard(context: Context, strict: Boolean, allow: Set<String>) {
            runCatching {
                context.startForegroundService(
                    Intent(context, FocusService::class.java).apply {
                        action = ACTION_GUARD_ON
                        putExtra(EXTRA_STRICT, strict)
                        putStringArrayListExtra(EXTRA_ALLOW, ArrayList(allow))
                    }
                )
            }
        }

        /** Activity onStop 时上报:严格模式下任何离开立即拉回(不等轮询) */
        fun requestPullback(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, FocusService::class.java).apply { action = ACTION_PULLBACK }
                )
            }
        }

        /**
         * 强制拉回研钟前台(学霸模式专属):休息结束时由引擎调用,
         * 无 Toast、无限频——必须立即回来说明下一番茄开始/计时结束。
         */
        fun pullToFront(context: Context) {
            if (!guardActive) return
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                )
            }
        }

        private fun contentIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun phaseLabel(phase: Phase): String = when (phase) {
            Phase.FOCUSING -> "专注中"
            Phase.PAUSED -> "已暂停"
            Phase.SHORT_BREAK -> "短休息"
            Phase.LONG_BREAK -> "长休息"
            Phase.IDLE -> "研钟"
        }

        private fun buildNotification(
            context: Context,
            phase: Phase,
            remainingMs: Long,
            taskTitle: String,
            countUp: Boolean = false
        ): Notification {
            val title = if (taskTitle.isBlank()) "${phaseLabel(phase)} · 研钟"
            else "${phaseLabel(phase)} · $taskTitle"
            val timeLabel = (if (countUp) "已专注 " else "剩余 ") + TimeUtils.mmss(remainingMs)
            val text = if (remainingMs > 0) timeLabel else "研钟 · 考研学习效率工具"
            return NotificationCompat.Builder(context, CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_pomodoro)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(contentIntent(context))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
        }

        /** 引擎每分钟 / 每次阶段切换时更新常驻通知内容 */
        fun updateNotification(
            context: Context,
            phase: Phase,
            remainingMs: Long,
            taskTitle: String,
            countUp: Boolean = false
        ) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (phase == Phase.IDLE) return
            ensureChannels(context)
            nm.notify(ONGOING_ID, buildNotification(context, phase, remainingMs, taskTitle, countUp))
        }

        /**
         * 阶段切换事件通知(番茄结束 / 休息结束):闹钟式强提醒。
         * - 息屏/锁屏:系统直接全屏弹窗(full-screen intent);
         * - 亮屏但在其他应用:heads-up 横幅从顶部滑出 + 震动 + 可选提示音;
         * - Android 14+ 需用户授予「全屏通知」特殊权限,未授予时降级为横幅,依然醒目。
         */
        fun postEvent(context: Context, text: String, playSound: Boolean) {
            ensureChannels(context)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val fullScreen = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_EVENT)
                .setSmallIcon(R.drawable.ic_stat_pomodoro)
                .setContentTitle("研钟")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context))
                .setFullScreenIntent(fullScreen, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .build()
            nm.notify(EVENT_ID, notification)
            if (playSound) {
                runCatching {
                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                        .startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                }
            }
        }

        fun cancelOngoing(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(ONGOING_ID)
        }
    }
}
