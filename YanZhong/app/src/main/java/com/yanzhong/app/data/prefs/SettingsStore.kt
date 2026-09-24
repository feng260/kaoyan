package com.yanzhong.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")
private val Context.timerStore: DataStore<Preferences> by preferencesDataStore("timer_state")

object ThemeMode {
    const val LIGHT = 0
    const val DARK = 1
    const val SYSTEM = 2
}

/** 一套番茄方案(PRD 3.2:可保存多套,如「标准 25」「深度 50」) */
@Serializable
data class PomodoroPlan(
    val name: String,
    val focusMin: Int,
    val shortBreakMin: Int,
    val longBreakMin: Int,
    val longBreakInterval: Int
) {
    companion object {
        val STANDARD = PomodoroPlan("标准 25", 25, 5, 30, 4)
        val DEEP = PomodoroPlan("深度 50", 50, 10, 30, 2)
        val DEFAULTS = listOf(STANDARD, DEEP)

        /** 参数合法范围(PRD 3.2):专注 5–180 / 短休 1–30 / 长休 5–60 */
        fun clamp(plan: PomodoroPlan) = plan.copy(
            focusMin = plan.focusMin.coerceIn(5, 180),
            shortBreakMin = plan.shortBreakMin.coerceIn(1, 30),
            longBreakMin = plan.longBreakMin.coerceIn(5, 60),
            longBreakInterval = plan.longBreakInterval.coerceIn(1, 12)
        )
    }
}

data class AppSettings(
    val themeMode: Int = ThemeMode.SYSTEM,
    val plans: List<PomodoroPlan> = PomodoroPlan.DEFAULTS,
    val currentPlanName: String = PomodoroPlan.STANDARD.name,
    val autoChain: Boolean = false,
    val continuousFocus: Boolean = false,
    val vibrationOn: Boolean = true,
    val soundOn: Boolean = true,
    /** 静音模式:总开关,压制阶段提醒的震动与音效(横幅/弹窗不受影响) */
    val silentMode: Boolean = false,
    val onboardingDone: Boolean = false,
    val weeklyGoalHours: Int = 37,
    /** 每日番茄目标数(参考番茄ToDo §8) */
    val dailyPomodoroGoal: Int = 8,
    val planPackVersion: Int = 0,
    /** 学霸模式:专注计时期间自动拦截非白名单应用(参考番茄ToDo 学霸模式) */
    val superModeOn: Boolean = false,
    /** 严格等级:标准=放行白名单应用;严格=完全锁定在研钟内 */
    val superModeStrict: Boolean = false,
    /** 紧急退出密码 SHA-256(严格模式逃生口,明文仅存在于用户记忆) */
    val emergencyPinHash: String = "",
    /** 全局白名单包名集合 */
    val whitelist: Set<String> = emptySet(),
    /** 科目定制白名单(场景化策略):subjectId → 包名集;未定制科目用全局白名单 */
    val subjectWhitelist: Map<Long, Set<String>> = emptyMap(),
    /** 紧急退出配额:已消耗的月份(yyyy-MM),跨月自动清零 */
    val emergencyExitMonth: String = "",
    /** 紧急退出配额:本月已用次数(每月上限 [EMERGENCY_EXIT_QUOTA]) */
    val emergencyExitsUsed: Int = 0
) {
    val currentPlan: PomodoroPlan
        get() = plans.firstOrNull { it.name == currentPlanName } ?: PomodoroPlan.STANDARD

    companion object {
        /** 紧急退出每月配额上限(防逃逸:逼用户等计时自然结束) */
        const val EMERGENCY_EXIT_QUOTA = 4
    }
}

/** 引擎状态持久化(ADR-001:进程被杀后恢复现场) */
@Serializable
data class PersistedTimer(
    val phase: String,
    val durationMs: Long,
    val remainingMs: Long,
    /** 计时模式:POMODORO / STOPWATCH / COUNTDOWN(旧数据缺省为番茄钟) */
    val mode: String = "POMODORO",
    val taskId: Long? = null,
    val taskTitle: String = "",
    val subjectId: Long? = null,
    val subjectColorArgb: Long = 0xFF5865F2,
    val startedAtWall: Long,
    val planName: String,
    val completedInCycle: Int = 0,
    val valid: Boolean = true,
    val pausedAccumMs: Long = 0,
    val savedAtElapsed: Long = 0,
    val savedAtWall: Long = 0,
    val pauseStartedWall: Long = 0,
    /** 任务番茄估计总数(开钟快照):>0 时休息结束自动续做,做满为止 */
    val taskPomodoroEstimate: Int = 0,
    /** 开钟时任务已完成番茄数 */
    val taskPomodoroDoneAtStart: Int = 0,
    /** 本次专注链路标识:多端跟随用,休息/续做保持不变,新开钟换新值 */
    val sessionGuid: String = ""
)

class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val THEME = intPreferencesKey("theme_mode")
        val PLANS = stringPreferencesKey("pomodoro_plans")
        val CURRENT_PLAN = stringPreferencesKey("current_plan")
        val AUTO_CHAIN = booleanPreferencesKey("auto_chain")
        val CONTINUOUS_FOCUS = booleanPreferencesKey("continuous_focus")
        val VIBRATION = booleanPreferencesKey("vibration_on")
        val SOUND = booleanPreferencesKey("sound_on")
        val SILENT = booleanPreferencesKey("silent_mode")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val WEEKLY_GOAL = intPreferencesKey("weekly_goal_hours")
        val DAILY_POMODORO = intPreferencesKey("daily_pomodoro_goal")
        val PLAN_PACK = intPreferencesKey("plan_pack_version")
        val SUPER_ON = booleanPreferencesKey("super_mode_on")
        val SUPER_STRICT = booleanPreferencesKey("super_mode_strict")
        val EMERGENCY_PIN = stringPreferencesKey("emergency_pin_hash")
        val WHITELIST = stringPreferencesKey("guard_whitelist")
        val SUBJECT_WHITELIST = stringPreferencesKey("guard_subject_whitelist")
        val EMERGENCY_MONTH = stringPreferencesKey("emergency_exit_month")
        val EMERGENCY_USED = intPreferencesKey("emergency_exit_used")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data
        .catch { e ->
            // DataStore 文件损坏等 IO 异常:回退默认设置,避免整个设置流崩溃
            if (e is java.io.IOException) emit(emptyPreferences()) else throw e
        }
        .map { p ->
        AppSettings(
            themeMode = p[Keys.THEME] ?: ThemeMode.SYSTEM,
            plans = p[Keys.PLANS]?.let { runCatching { json.decodeFromString<List<PomodoroPlan>>(it) }.getOrNull() }
                ?: PomodoroPlan.DEFAULTS,
            currentPlanName = p[Keys.CURRENT_PLAN] ?: PomodoroPlan.STANDARD.name,
            autoChain = p[Keys.AUTO_CHAIN] ?: false,
            continuousFocus = p[Keys.CONTINUOUS_FOCUS] ?: false,
            vibrationOn = p[Keys.VIBRATION] ?: true,
            soundOn = p[Keys.SOUND] ?: true,
            silentMode = p[Keys.SILENT] ?: false,
            onboardingDone = p[Keys.ONBOARDING] ?: false,
            weeklyGoalHours = (p[Keys.WEEKLY_GOAL] ?: 37).coerceIn(10, 80),
            dailyPomodoroGoal = (p[Keys.DAILY_POMODORO] ?: 8).coerceIn(1, 30),
            planPackVersion = p[Keys.PLAN_PACK] ?: 0,
            superModeOn = p[Keys.SUPER_ON] ?: false,
            superModeStrict = p[Keys.SUPER_STRICT] ?: false,
            emergencyPinHash = p[Keys.EMERGENCY_PIN] ?: "",
            whitelist = p[Keys.WHITELIST]
                ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
                ?.toSet() ?: emptySet(),
            subjectWhitelist = p[Keys.SUBJECT_WHITELIST]
                ?.let {
                    runCatching { json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }
                ?.mapNotNull { (k, v) -> k.toLongOrNull()?.let { id -> id to v.toSet() } }
                ?.toMap() ?: emptyMap(),
            // 配额跨月自动清零:仅展示层归一,写回发生在 consumeEmergencyExit()
            emergencyExitMonth = p[Keys.EMERGENCY_MONTH] ?: "",
            emergencyExitsUsed = if (p[Keys.EMERGENCY_MONTH] == currentMonthKey())
                (p[Keys.EMERGENCY_USED] ?: 0) else 0
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: Int) = context.settingsStore.edit { it[Keys.THEME] = mode }

    suspend fun setPlans(plans: List<PomodoroPlan>) {
        context.settingsStore.edit {
            it[Keys.PLANS] = json.encodeToString(plans)
            it[Keys.CURRENT_PLAN] = plans.firstOrNull()?.name ?: PomodoroPlan.STANDARD.name
        }
    }

    suspend fun setCurrentPlan(name: String) {
        context.settingsStore.edit { it[Keys.CURRENT_PLAN] = name }
    }

    suspend fun setAutoChain(on: Boolean) = context.settingsStore.edit { it[Keys.AUTO_CHAIN] = on }

    suspend fun setContinuousFocus(on: Boolean) =
        context.settingsStore.edit { it[Keys.CONTINUOUS_FOCUS] = on }

    suspend fun setVibration(on: Boolean) = context.settingsStore.edit { it[Keys.VIBRATION] = on }

    suspend fun setSound(on: Boolean) = context.settingsStore.edit { it[Keys.SOUND] = on }

    suspend fun setSilent(on: Boolean) = context.settingsStore.edit { it[Keys.SILENT] = on }

    suspend fun setOnboardingDone() = context.settingsStore.edit { it[Keys.ONBOARDING] = true }

    suspend fun setPlanPackVersion(version: Int) =
        context.settingsStore.edit { it[Keys.PLAN_PACK] = version }

    suspend fun setWeeklyGoal(hours: Int) = context.settingsStore.edit {
        it[Keys.WEEKLY_GOAL] = hours.coerceIn(10, 80)
    }

    suspend fun setDailyPomodoroGoal(count: Int) = context.settingsStore.edit {
        it[Keys.DAILY_POMODORO] = count.coerceIn(1, 30)
    }

    // ---- 学霸模式(参考番茄ToDo) ----

    suspend fun setSuperMode(on: Boolean) =
        context.settingsStore.edit { it[Keys.SUPER_ON] = on }

    suspend fun setSuperModeStrict(strict: Boolean) =
        context.settingsStore.edit { it[Keys.SUPER_STRICT] = strict }

    /** 设置 6 位紧急退出密码,仅存哈希不存明文 */
    suspend fun setEmergencyPin(pin: String) {
        require(pin.length == 6 && pin.all { it.isDigit() }) { "紧急退出密码必须为 6 位数字" }
        context.settingsStore.edit { it[Keys.EMERGENCY_PIN] = sha256Hex(pin) }
    }

    suspend fun clearEmergencyPin() =
        context.settingsStore.edit { it[Keys.EMERGENCY_PIN] = "" }

    suspend fun setWhitelist(pkgs: Set<String>) {
        context.settingsStore.edit { it[Keys.WHITELIST] = json.encodeToString(pkgs.toList()) }
    }

    /** 科目定制白名单;空集表示删除定制、回落全局 */
    suspend fun setSubjectWhitelist(subjectId: Long, pkgs: Set<String>) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.SUBJECT_WHITELIST]
                ?.let { runCatching { json.decodeFromString<Map<String, List<String>>>(it) }.getOrNull() }
                ?.toMutableMap() ?: mutableMapOf()
            if (pkgs.isEmpty()) current.remove(subjectId.toString())
            else current[subjectId.toString()] = pkgs.toList()
            prefs[Keys.SUBJECT_WHITELIST] = json.encodeToString(current)
        }
    }

    fun verifyEmergencyPin(settings: AppSettings, pin: String): Boolean =
        settings.emergencyPinHash.isNotEmpty() && sha256Hex(pin) == settings.emergencyPinHash

    /** 当前月份键(yyyy-MM) */
    fun currentMonthKey(): String = com.yanzhong.app.util.TimeUtils.monthKeyOf()

    /**
     * 消耗一次紧急退出配额:跨月自动重置后 +1;
     * 超出 [AppSettings.EMERGENCY_EXIT_QUOTA] 时不消耗并返回剩余 0。
     */
    suspend fun consumeEmergencyExit(): Int {
        var remaining = 0
        context.settingsStore.edit { prefs ->
            val month = currentMonthKey()
            val used = if (prefs[Keys.EMERGENCY_MONTH] == month)
                (prefs[Keys.EMERGENCY_USED] ?: 0) else 0
            val newUsed = (used + 1).coerceAtMost(AppSettings.EMERGENCY_EXIT_QUOTA)
            prefs[Keys.EMERGENCY_MONTH] = month
            prefs[Keys.EMERGENCY_USED] = newUsed
            remaining = AppSettings.EMERGENCY_EXIT_QUOTA - newUsed
        }
        return remaining
    }

    companion object {
        fun sha256Hex(s: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // ---- 引擎状态持久化 ----

    suspend fun saveTimer(state: PersistedTimer) {
        context.timerStore.edit { prefs ->
            prefs[stringPreferencesKey("state_json")] = json.encodeToString(state)
        }
    }

    suspend fun loadTimer(): PersistedTimer? {
        val raw = context.timerStore.data.first()[stringPreferencesKey("state_json")] ?: return null
        return runCatching { json.decodeFromString<PersistedTimer>(raw) }.getOrNull()
    }

    suspend fun clearTimer() {
        context.timerStore.edit { it.clear() }
    }

    // ---- 云端设置同步映射 ----

    /**
     * 可同步偏好 → 云端 KV。只收设备无关项;
     * PIN 哈希/紧急退出配额(设备级防逃逸)/引导与计划包版本(本地状态)不进云。
     */
    suspend fun toSyncMap(): Map<String, JsonElement> = syncMapOf(current())

    /** 纯映射:设置流 collector 直接调用(无挂起) */
    fun syncMapOf(s: AppSettings): Map<String, JsonElement> = mapOf(
        "themeMode" to kotlinx.serialization.json.JsonPrimitive(s.themeMode),
        "currentPlanName" to kotlinx.serialization.json.JsonPrimitive(s.currentPlanName),
        "plans" to json.encodeToJsonElement(ListSerializer(PomodoroPlan.serializer()), s.plans),
        "autoChain" to kotlinx.serialization.json.JsonPrimitive(s.autoChain),
        "continuousFocus" to kotlinx.serialization.json.JsonPrimitive(s.continuousFocus),
        "vibrationOn" to kotlinx.serialization.json.JsonPrimitive(s.vibrationOn),
        "soundOn" to kotlinx.serialization.json.JsonPrimitive(s.soundOn),
        "silentMode" to kotlinx.serialization.json.JsonPrimitive(s.silentMode),
        "weeklyGoalHours" to kotlinx.serialization.json.JsonPrimitive(s.weeklyGoalHours),
        "dailyPomodoroGoal" to kotlinx.serialization.json.JsonPrimitive(s.dailyPomodoroGoal),
        "superModeOn" to kotlinx.serialization.json.JsonPrimitive(s.superModeOn),
        "superModeStrict" to kotlinx.serialization.json.JsonPrimitive(s.superModeStrict),
        "whitelist" to json.encodeToJsonElement(
            ListSerializer(String.serializer()), s.whitelist.toList()
        )
    )

    /** 云端 KV → 本地:仅应用存在且解析成功的键,单次事务写回 */
    suspend fun applySyncMap(map: Map<String, kotlinx.serialization.json.JsonElement>) {
        context.settingsStore.edit { p ->
            map["themeMode"]?.jsonPrimitive?.intOrNull?.let { p[Keys.THEME] = it }
            map["currentPlanName"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }?.let { p[Keys.CURRENT_PLAN] = it }
            map["plans"]?.let { el ->
                runCatching {
                    json.decodeFromJsonElement(ListSerializer(PomodoroPlan.serializer()), el)
                }.getOrNull()?.let { plans -> p[Keys.PLANS] = json.encodeToString(plans) }
            }
            map["autoChain"]?.jsonPrimitive?.booleanOrNull?.let { p[Keys.AUTO_CHAIN] = it }
            map["continuousFocus"]?.jsonPrimitive?.booleanOrNull?.let { p[Keys.CONTINUOUS_FOCUS] = it }
            map["vibrationOn"]?.jsonPrimitive?.booleanOrNull?.let { p[Keys.VIBRATION] = it }
            map["soundOn"]?.jsonPrimitive?.booleanOrNull?.let { p[Keys.SOUND] = it }
            map["silentMode"]?.jsonPrimitive?.booleanOrNull?.let { p[Keys.SILENT] = it }
            map["weeklyGoalHours"]?.jsonPrimitive?.intOrNull
                ?.let { p[Keys.WEEKLY_GOAL] = it.coerceIn(10, 80) }
            map["dailyPomodoroGoal"]?.jsonPrimitive?.intOrNull
                ?.let { p[Keys.DAILY_POMODORO] = it.coerceIn(1, 30) }
            map["superModeOn"]?.jsonPrimitive?.booleanOrNull?.let { p[Keys.SUPER_ON] = it }
            map["superModeStrict"]?.jsonPrimitive?.booleanOrNull?.let { p[Keys.SUPER_STRICT] = it }
            map["whitelist"]?.let { el ->
                runCatching {
                    json.decodeFromJsonElement(ListSerializer(String.serializer()), el)
                }.getOrNull()?.let { list -> p[Keys.WHITELIST] = json.encodeToString(list) }
            }
        }
    }
}
