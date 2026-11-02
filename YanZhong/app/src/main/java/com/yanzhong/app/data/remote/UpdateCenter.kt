package com.yanzhong.app.data.remote

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class UpdateCheckResult { UPDATE_AVAILABLE, UP_TO_DATE, SKIPPED, THROTTLED, FAILED }

/**
 * 全局更新检查:登录页 / 前台恢复 / 手动检查共用同一份结果。
 * 自动检查 6 小时节流;手动检查忽略节流,也忽略「跳过此版本」。
 */
object UpdateCenter {
    private const val PREFS = "app_update"
    private const val KEY_SKIP = "skipped_version"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val mutex = Mutex()
    private val _available = MutableStateFlow<LatestVersionDto?>(null)
    val available: StateFlow<LatestVersionDto?> = _available.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun check(manual: Boolean, context: Context): UpdateCheckResult = mutex.withLock {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (!manual && now - p.getLong(KEY_LAST_CHECK, 0L) < AUTO_INTERVAL_MS) {
            return@withLock UpdateCheckResult.THROTTLED
        }
        _checking.value = true
        try {
            val resp = AppUpdater.checkForUpdate() ?: return@withLock UpdateCheckResult.FAILED
            p.edit().putLong(KEY_LAST_CHECK, now).apply()
            val latest = resp.latest
            if (!resp.updateAvailable || latest == null) {
                _available.value = null
                return@withLock UpdateCheckResult.UP_TO_DATE
            }
            val skipped = p.getInt(KEY_SKIP, -1)
            if (!UpdatePolicy.shouldPrompt(latest.versionCode, latest.forced, skipped, manual)) {
                return@withLock UpdateCheckResult.SKIPPED
            }
            _available.value = latest
            UpdateCheckResult.UPDATE_AVAILABLE
        } finally {
            _checking.value = false
        }
    }

    fun skip(context: Context, versionCode: Int) {
        prefs(context).edit().putInt(KEY_SKIP, versionCode).apply()
        if (_available.value?.versionCode == versionCode) _available.value = null
    }

    fun dismiss() {
        _available.value = null
    }
}
