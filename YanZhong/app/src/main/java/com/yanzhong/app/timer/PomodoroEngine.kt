package com.yanzhong.app.timer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.room.withTransaction
import com.yanzhong.app.data.db.PomodoroSessionEntity
import com.yanzhong.app.data.db.YanZhongDatabase
import com.yanzhong.app.data.prefs.AppSettings
import com.yanzhong.app.data.prefs.PersistedTimer
import com.yanzhong.app.data.prefs.PomodoroPlan
import com.yanzhong.app.data.prefs.SettingsRepository
import com.yanzhong.app.service.FocusService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 番茄钟状态机引擎(PRD 6.1 / ADR-001)。
 *
 * 计时不用累加,而是「记录起点 + 每秒渲染差值」:
 * 息屏、切后台、分屏均不影响精度;基于 elapsedRealtime 单调时钟,改系统时间无效。
 */
enum class Phase { IDLE, FOCUSING, PAUSED, SHORT_BREAK, LONG_BREAK }

/** 计时模式(参考番茄ToDo):番茄钟循环 / 正计时累计 / 单次倒计时 */
enum class TimerMode { POMODORO, STOPWATCH, COUNTDOWN }

/** 跟随对象:镜像他端(主端)的专注;本端只展示,不落库、不触发学霸模式守护 */
data class FollowSpec(
    val sessionGuid: String,
    val peerDeviceId: Long,
    val peerName: String,
    val taskTitle: String,
    val subjectColorArgb: Long = 0xFF5865F2
)

data class TimerState(
    val phase: Phase = Phase.IDLE,
    val mode: TimerMode = TimerMode.POMODORO,
    val durationMs: Long = 0,
    val remainingMs: Long = 0,
    val taskId: Long? = null,
    val taskTitle: String = "",
    val subjectId: Long? = null,
    val subjectColorArgb: Long = 0xFF5865F2,
    val planName: String = "",
    val completedInCycle: Int = 0,
    val pausedAccumMs: Long = 0,
    val focusStartWallMs: Long = 0,
    /** 任务番茄估计总数(开钟快照):>0 时休息结束自动续做,做满为止 */
    val taskPomodoroEstimate: Int = 0,
    /** 开钟时任务已完成番茄数:任务总进度 = 该值 + completedInCycle */
    val taskPomodoroDoneAtStart: Int = 0,
    /** 本次专注链路标识:休息/自动续做保持不变,跟随端据此识别同一场专注 */
    val sessionGuid: String = "",
    /** 非空 = 正在跟随他端专注(镜像态):暂停/放弃等控制权在主端 */
    val follow: FollowSpec? = null
) {
    val isRunning: Boolean get() = phase != Phase.IDLE
    val isFocusing: Boolean get() = phase == Phase.FOCUSING || phase == Phase.PAUSED
}

class PomodoroEngine(
    private val app: Application,
    private val db: YanZhongDatabase,
    private val settings: SettingsRepository
) {
    companion object {
        const val PAUSE_LIMIT_MS: Long = 5 * 60 * 1000 // 暂停累计上限 5 min(PRD 3.2)
        private const val TICK_MS = 1000L

        /** 全局专注阶段快照(每秒同步):FocusService 守护线程读取,休息期解除拦截 */
        @Volatile
        var isFocusingNow: Boolean = false
            internal set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state.asStateFlow()

    /** 设置快照缓存:引擎阶段切换时同步读取,避免在计时循环中挂起 */
    @Volatile
    private var settingsCache: AppSettings = AppSettings()

    fun primeSettings(s: AppSettings) { settingsCache = s }

    /** 本段运行的 elapsedRealtime 起点;elapsedBefore 为此前已流逝毫秒 */
    private var segmentStartElapsed = 0L
    private var elapsedBeforeSegment = 0L
    private var pauseStartElapsed = 0L
    private var lastNotifiedMinute = -1L
    private var tickJob: Job? = null

    // ---------- 多端跟随(镜像态) ----------

    /** 跟随中的主端终点(墙钟);0 = 主端暂停中,倒计时冻结 */
    @Volatile
    private var followEndsAtWallMs = 0L
    @Volatile
    private var lastPeerUpdateMs = 0L
    private var followTickJob: Job? = null

    /** 进入跟随:镜像主端(他端)的专注。本端只展示——不落库、不起前台服务、不触发守护 */
    fun enterFollow(spec: FollowSpec, phase: Phase, endsAtWallMs: Long) {
        if (_state.value.isRunning) return
        followEndsAtWallMs = endsAtWallMs
        lastPeerUpdateMs = System.currentTimeMillis()
        _state.value = TimerState(
            phase = phase,
            mode = TimerMode.POMODORO,
            durationMs = 0,
            remainingMs = (endsAtWallMs - System.currentTimeMillis()).coerceAtLeast(0),
            taskTitle = spec.taskTitle,
            subjectColorArgb = spec.subjectColorArgb,
            planName = "跟随 · ${spec.peerName}",
            focusStartWallMs = System.currentTimeMillis(),
            sessionGuid = spec.sessionGuid,
            follow = spec
        )
        startFollowTick()
        feedback(settingsCache, "已跟随「${spec.peerName}」的专注")
    }

    /** 主端推送更新(同 sessionGuid):校正阶段与剩余;paused 冻结倒计时,其余按墙钟终点倒数 */
    fun updateFollow(phaseStr: String, remainMs: Long, paused: Boolean, taskTitle: String?) {
        val s = _state.value
        if (s.follow == null) return
        lastPeerUpdateMs = System.currentTimeMillis()
        followEndsAtWallMs = if (paused) 0L else System.currentTimeMillis() + remainMs
        val newPhase = when {
            paused -> Phase.PAUSED
            phaseStr == "focus" -> Phase.FOCUSING
            phaseStr == "break" -> if (s.phase == Phase.LONG_BREAK) Phase.LONG_BREAK else Phase.SHORT_BREAK
            else -> return
        }
        _state.value = s.copy(
            phase = newPhase,
            remainingMs = remainMs.coerceAtLeast(0),
            taskTitle = taskTitle?.takeIf { it.isNotBlank() } ?: s.taskTitle
        )
    }

    /** 结束跟随(主动停止/主端结束/主端开新钟)。reason 非空时给出提示 */
    fun exitFollow(reason: String? = null) {
        val s = _state.value
        if (s.follow == null) return
        followTickJob?.cancel()
        followTickJob = null
        _state.value = TimerState()
        if (reason != null) feedback(settingsCache, reason)
    }

    private fun startFollowTick() {
        followTickJob?.cancel()
        followTickJob = scope.launch {
            while (_state.value.follow != null) {
                delay(TICK_MS)
                val s = _state.value
                if (s.follow == null) break
                val remain =
                    if (followEndsAtWallMs > 0) (followEndsAtWallMs - System.currentTimeMillis()).coerceAtLeast(0)
                    else s.remainingMs
                _state.value = s.copy(remainingMs = remain)
                // 主端静默超时(断网/离线):归零后 30s 仍无更新则自动退出跟随
                if (remain <= 0 && System.currentTimeMillis() - lastPeerUpdateMs > 30_000) {
                    exitFollow("跟随的设备已离线,已停止跟随")
                }
            }
        }
    }

    /** App 启动时恢复被杀进程的现场(ADR-001) */
    fun restoreIfNeeded() {
        scope.launch {
            val persisted = settings.loadTimer() ?: return@launch
            val phase = runCatching { Phase.valueOf(persisted.phase) }.getOrNull() ?: return@launch
            if (phase == Phase.IDLE) return@launch
            val mode = runCatching { TimerMode.valueOf(persisted.mode) }.getOrNull()
                ?: TimerMode.POMODORO
            val remaining = persisted.remainingMs
            val nowElapsed = SystemClock.elapsedRealtime()
            val offlineElapsed = if (phase != Phase.PAUSED) {
                if (persisted.savedAtElapsed > 0L && nowElapsed >= persisted.savedAtElapsed) {
                    nowElapsed - persisted.savedAtElapsed
                } else {
                    (System.currentTimeMillis() - persisted.savedAtWall).coerceAtLeast(0L)
                }
            } else 0L
            // 正计时:离线时长继续累计;其余:从剩余中扣除
            val restoredRemaining = if (mode == TimerMode.STOPWATCH) remaining + offlineElapsed
            else (remaining - offlineElapsed).coerceAtLeast(0L)
            if (mode != TimerMode.STOPWATCH && restoredRemaining <= 0) {
                // 被杀前已归零:专注直接落库,休息直接结束
                if (phase == Phase.FOCUSING) {
                    writeSession(persisted, persisted.durationMs)
                }
                settings.clearTimer()
                return@launch
            }
            _state.value = TimerState(
                phase = phase,
                mode = mode,
                durationMs = persisted.durationMs,
                remainingMs = restoredRemaining,
                taskId = persisted.taskId,
                taskTitle = persisted.taskTitle,
                subjectId = persisted.subjectId,
                subjectColorArgb = persisted.subjectColorArgb,
                planName = persisted.planName,
                completedInCycle = persisted.completedInCycle,
                pausedAccumMs = persisted.pausedAccumMs,
                focusStartWallMs = persisted.startedAtWall,
                taskPomodoroEstimate = persisted.taskPomodoroEstimate,
                taskPomodoroDoneAtStart = persisted.taskPomodoroDoneAtStart,
                sessionGuid = persisted.sessionGuid
            )
            segmentStartElapsed = SystemClock.elapsedRealtime()
            // 正计时:elapsedBefore 为已累计专注时长;倒计时/番茄钟:durationMs - 剩余
            elapsedBeforeSegment = if (mode == TimerMode.STOPWATCH) restoredRemaining
            else persisted.durationMs - restoredRemaining
            if (phase == Phase.FOCUSING) {
                startService()
                armGuard(persisted.subjectId, readSettingsForGuard())
            } else if (phase == Phase.PAUSED) {
                // 恢复为暂停态:保持暂停,不自动继续
                val pausedOffline = if (persisted.pauseStartedWall > 0L) {
                    (System.currentTimeMillis() - persisted.pauseStartedWall).coerceAtLeast(0L)
                } else 0L
                pauseStartElapsed = nowElapsed - pausedOffline
                startService()
                armGuard(persisted.subjectId, readSettingsForGuard())
            }
            startTickLoop()
            notifyPhase(phase, restoredRemaining)
        }
    }

    /** 开始专注。taskId 为空 = 快速开钟(结束时补选科目,PRD 3.4)。 */
    fun startFocus(
        taskId: Long?,
        taskTitle: String,
        subjectId: Long?,
        subjectColorArgb: Long,
        plan: PomodoroPlan,
        taskPomodoroEstimate: Int = 0,
        taskPomodoroDoneAtStart: Int = 0
    ) {
        if (_state.value.isRunning) return
        val focusMs = plan.focusMin * 60_000L
        segmentStartElapsed = SystemClock.elapsedRealtime()
        elapsedBeforeSegment = 0
        _state.value = TimerState(
            phase = Phase.FOCUSING,
            mode = TimerMode.POMODORO,
            durationMs = focusMs,
            remainingMs = focusMs,
            taskId = taskId,
            taskTitle = taskTitle,
            subjectId = subjectId,
            subjectColorArgb = subjectColorArgb,
            planName = plan.name,
            focusStartWallMs = System.currentTimeMillis(),
            taskPomodoroEstimate = taskPomodoroEstimate,
            taskPomodoroDoneAtStart = taskPomodoroDoneAtStart,
            sessionGuid = UUID.randomUUID().toString()
        )
        persist()
        startService()
        armGuard(subjectId, settingsCache)
        startTickLoop()
        notifyPhase(Phase.FOCUSING, focusMs)
    }

    /** 正计时模式:从 0 向上累计,无上限,手动结束并记录(remainingMs 表示已专注时长) */
    fun startStopwatch(
        taskId: Long?,
        taskTitle: String,
        subjectId: Long?,
        subjectColorArgb: Long
    ) {
        if (_state.value.isRunning) return
        segmentStartElapsed = SystemClock.elapsedRealtime()
        elapsedBeforeSegment = 0
        _state.value = TimerState(
            phase = Phase.FOCUSING,
            mode = TimerMode.STOPWATCH,
            durationMs = 0,
            remainingMs = 0,
            taskId = taskId,
            taskTitle = taskTitle,
            subjectId = subjectId,
            subjectColorArgb = subjectColorArgb,
            planName = "正计时",
            focusStartWallMs = System.currentTimeMillis(),
            sessionGuid = UUID.randomUUID().toString()
        )
        persist()
        startService()
        armGuard(subjectId, settingsCache)
        startTickLoop()
        notifyPhase(Phase.FOCUSING, 0)
    }

    /** 倒计时模式:单次自定义时长(5-180 分钟),结束即完成,不关联休息 */
    fun startCountdown(
        minutes: Int,
        taskId: Long?,
        taskTitle: String,
        subjectId: Long?,
        subjectColorArgb: Long
    ) {
        if (_state.value.isRunning) return
        val ms = minutes.coerceIn(1, 180) * 60_000L
        segmentStartElapsed = SystemClock.elapsedRealtime()
        elapsedBeforeSegment = 0
        _state.value = TimerState(
            phase = Phase.FOCUSING,
            mode = TimerMode.COUNTDOWN,
            durationMs = ms,
            remainingMs = ms,
            taskId = taskId,
            taskTitle = taskTitle,
            subjectId = subjectId,
            subjectColorArgb = subjectColorArgb,
            planName = "倒计时 $minutes 分钟",
            focusStartWallMs = System.currentTimeMillis(),
            sessionGuid = UUID.randomUUID().toString()
        )
        persist()
        startService()
        armGuard(subjectId, settingsCache)
        startTickLoop()
        notifyPhase(Phase.FOCUSING, ms)
    }

    /** 正计时手动结束:记录本次总专注时长(不足 1 分钟不落库) */
    fun finishStopwatch() {
        val s = _state.value
        if (s.follow != null || s.phase == Phase.IDLE || s.mode != TimerMode.STOPWATCH) return
        val actualMs = computeRemaining(s) // 净专注时长,不含暂停
        if (actualMs >= 60_000L) {
            scope.launch { writeSession(toPersisted(s), actualMs) }
        }
        feedback(settingsCache, "专注已记录")
        stopTimer()
    }

    fun pause() {
        val s = _state.value
        if (s.follow != null || s.phase != Phase.FOCUSING) return
        val nowElapsed = SystemClock.elapsedRealtime()
        // 先冻结剩余,再累加已流逝,避免重复扣减;正计时冻结的是已累计时长
        val frozen =
            if (s.mode == TimerMode.STOPWATCH) elapsedBeforeSegment + (nowElapsed - segmentStartElapsed)
            else (s.durationMs - (elapsedBeforeSegment + (nowElapsed - segmentStartElapsed)))
                .coerceAtLeast(0)
        elapsedBeforeSegment += nowElapsed - segmentStartElapsed
        pauseStartElapsed = nowElapsed
        _state.value = s.copy(phase = Phase.PAUSED, remainingMs = frozen)
        persist()
        notifyPhase(Phase.PAUSED, frozen)
    }

    fun resume() {
        val s = _state.value
        if (s.follow != null || s.phase != Phase.PAUSED) return
        val nowElapsed = SystemClock.elapsedRealtime()
        _state.value = s.copy(
            phase = Phase.FOCUSING,
            pausedAccumMs = s.pausedAccumMs + (nowElapsed - pauseStartElapsed)
        )
        segmentStartElapsed = nowElapsed
        persist()
        notifyPhase(Phase.FOCUSING, computeRemaining(_state.value))
    }

    /** 放弃当前阶段:专注记为中断(valid=false + 可选原因),休息直接结束 */
    fun abandon(reason: String? = null) {
        val s = _state.value
        if (s.follow != null || s.phase == Phase.IDLE) return
        if (s.isFocusing) {
            val actualMs = if (s.mode == TimerMode.STOPWATCH) s.remainingMs
            else s.durationMs - computeRemaining(s)
            if (actualMs >= 60_000L) {
                val snap = toPersisted(s)
                scope.launch { writeSession(snap, actualMs, abandonReason = reason ?: "未填写原因") }
            }
        }
        stopTimer()
    }

    /** 专注中切换归属科目(快速开钟补选,PRD 3.4) */
    fun switchSubject(subjectId: Long?, subjectColorArgb: Long) {
        val s = _state.value
        if (s.follow != null || !s.isFocusing) return
        _state.value = s.copy(subjectId = subjectId, subjectColorArgb = subjectColorArgb)
        persist()
        // 学霸模式在跑时,场景化白名单策略随科目切换
        if (FocusService.guardActive) armGuard(subjectId, settingsCache)
    }

    /** 跳过休息 */
    fun skipBreak() {
        val s = _state.value
        if (s.follow != null || (s.phase != Phase.SHORT_BREAK && s.phase != Phase.LONG_BREAK)) return
        stopTimer()
    }

    private fun stopTimer() {
        isFocusingNow = false
        tickJob?.cancel()
        tickJob = null
        followTickJob?.cancel()
        followTickJob = null
        scope.launch { settings.clearTimer() }
        _state.value = TimerState()
        stopService()
        FocusService.cancelOngoing(app)
    }

    private fun startTickLoop() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                val s = _state.value
                // 专注阶段快照:休息期守护解除的依据(FocusService 每秒读取);跟随态不参与守护
                isFocusingNow = s.isFocusing && s.follow == null
                when (s.phase) {
                    Phase.FOCUSING, Phase.SHORT_BREAK, Phase.LONG_BREAK -> {
                        val remaining = computeRemaining(s)
                        _state.value = s.copy(remainingMs = remaining.coerceAtLeast(0))
                        val minute = remaining / 60_000
                        if (minute != lastNotifiedMinute) {
                            lastNotifiedMinute = minute
                            FocusService.updateNotification(
                                app, s.phase, remaining.coerceAtLeast(0), s.taskTitle,
                                countUp = s.mode == TimerMode.STOPWATCH
                            )
                        }
                        // 正计时无归零终点,只有倒计时/番茄钟会触发结束
                        if (remaining <= 0 && s.mode != TimerMode.STOPWATCH) {
                            if (s.phase == Phase.FOCUSING) onFocusFinished() else onBreakFinished()
                        }
                    }
                    Phase.PAUSED -> {
                        val pausedNow = SystemClock.elapsedRealtime() - pauseStartElapsed
                        if (s.pausedAccumMs + pausedNow >= PAUSE_LIMIT_MS) {
                            resume() // 暂停上限自动恢复,防止无限暂停
                        }
                    }
                    Phase.IDLE -> Unit
                }
            }
        }
    }

    /** 专注归零:倒计时即完成;番茄钟进入循环(Session 落库 → 休息或连续专注) */
    private fun onFocusFinished() {
        val s = _state.value
        val snap = settingsCache
        val plan = snap.currentPlan

        if (s.mode == TimerMode.COUNTDOWN) {
            // 倒计时结束:落库即完成,无休息、不串联(参考番茄ToDo 倒计时模式)
            scope.launch { writeSession(toPersisted(s), s.durationMs) }
            feedback(snap, "倒计时结束,已记录")
            stopTimer()
            return
        }

        scope.launch { writeSession(toPersisted(s), s.durationMs) }

        val completed = s.completedInCycle + 1
        val nextIsLong = completed % plan.longBreakInterval == 0

        if (snap.continuousFocus) {
            // 连续专注模式:跳过休息直接开始下一个番茄(PRD 3.2)
            enterFocus(s, plan, completed)
            feedback(snap, "开始下一个番茄")
        } else {
            val breakMs = (if (nextIsLong) plan.longBreakMin else plan.shortBreakMin) * 60_000L
            segmentStartElapsed = SystemClock.elapsedRealtime()
            elapsedBeforeSegment = 0
            _state.value = s.copy(
                phase = if (nextIsLong) Phase.LONG_BREAK else Phase.SHORT_BREAK,
                durationMs = breakMs,
                remainingMs = breakMs,
                completedInCycle = completed
            )
            persist()
            feedback(snap, if (nextIsLong) "长休息时间到" else "短休息时间到")
            notifyPhase(_state.value.phase, breakMs)
        }
    }

    /**
     * 休息归零:
     * - 任务番茄还没做完(估计 > 已完成)→ 自动续做下一个番茄,长任务(如 3h)跨多个番茄连续推进;
     * - 自由专注/番茄已做满 → 开了「自动串联」继续,否则回到 Idle。
     */
    private fun onBreakFinished() {
        val s = _state.value
        val snap = settingsCache
        // 学霸模式:休息结束强制拉回研钟(继续下一番茄 / 查看完成结果)
        FocusService.pullToFront(app)
        val estimate = s.taskPomodoroEstimate
        val doneTotal = s.taskPomodoroDoneAtStart + s.completedInCycle
        if (estimate > 0 && doneTotal < estimate) {
            enterFocus(s, snap.currentPlan, s.completedInCycle)
            feedback(snap, "休息结束,继续「${s.taskTitle}」· 剩 ${estimate - doneTotal} 个番茄")
        } else if (snap.autoChain) {
            enterFocus(s, snap.currentPlan, s.completedInCycle)
            feedback(snap, "休息结束,开始下一个番茄")
        } else {
            feedback(snap, if (estimate > 0) "本任务 $estimate 个番茄已完成" else "休息结束")
            stopTimer()
        }
    }

    private fun enterFocus(prev: TimerState, plan: PomodoroPlan, completedInCycle: Int) {
        val focusMs = plan.focusMin * 60_000L
        segmentStartElapsed = SystemClock.elapsedRealtime()
        elapsedBeforeSegment = 0
        _state.value = prev.copy(
            phase = Phase.FOCUSING,
            durationMs = focusMs,
            remainingMs = focusMs,
            completedInCycle = completedInCycle,
            focusStartWallMs = System.currentTimeMillis()
        )
        persist()
        // 自动续做(连续专注/休息结束续做)也要保活:恢复现场可能落在休息段,服务未启动;
        // 学霸模式同理,否则自动续做的专注静默失去拦截
        startService()
        armGuard(prev.subjectId, settingsCache)
        notifyPhase(Phase.FOCUSING, focusMs)
    }

    private fun computeRemaining(s: TimerState): Long {
        if (s.phase == Phase.PAUSED) return s.remainingMs
        val nowElapsed = SystemClock.elapsedRealtime()
        val elapsed = elapsedBeforeSegment + (nowElapsed - segmentStartElapsed)
        // 正计时:返回已专注时长(向上累计);其余:返回剩余时间
        return if (s.mode == TimerMode.STOPWATCH) elapsed else s.durationMs - elapsed
    }

    private suspend fun writeSession(persisted: PersistedTimer, actualMs: Long, abandonReason: String? = null) {
        if (!persisted.valid) return
        val endedAt = persisted.startedAtWall + actualMs
        val now = System.currentTimeMillis()
        // insert session + 计任务进度放同一事务,进程中途被杀不会只落库不计进度
        db.withTransaction {
            db.sessionDao().insert(
                PomodoroSessionEntity(
                    taskId = persisted.taskId,
                    subjectId = persisted.subjectId,
                    startedAt = persisted.startedAtWall,
                    endedAt = endedAt,
                    durationMin = (actualMs / 60_000L).toInt().coerceAtLeast(1),
                    valid = abandonReason == null,
                    planName = persisted.planName,
                    abandonReason = abandonReason,
                    clientGuid = UUID.randomUUID().toString(),
                    updatedAt = now,
                    dirty = true
                )
            )
            // 完整完成的番茄才计入任务进度(参考番茄ToDo:中途放弃不计数)
            if (abandonReason == null) {
                persisted.taskId?.let { db.taskDao().incrementPomodoro(it, now) }
            }
        }
        // 落库后刷新桌面小组件,今日番茄数即时更新
        com.yanzhong.app.widget.TodayWidgetProvider.requestUpdate(app)
        // 引擎直写路径打脏标,通知自动增量同步(拉取应用不发此信号,无回声)
        onSessionWritten?.invoke()
    }

    /** 会话落库回调:App 装配时接到 repository.notifyDataChanged() */
    var onSessionWritten: (() -> Unit)? = null

    private fun toPersisted(s: TimerState) = PersistedTimer(
        phase = s.phase.name,
        mode = s.mode.name,
        durationMs = s.durationMs,
        remainingMs = s.remainingMs,
        taskId = s.taskId,
        taskTitle = s.taskTitle,
        subjectId = s.subjectId,
        subjectColorArgb = s.subjectColorArgb,
        startedAtWall = s.focusStartWallMs,
        planName = s.planName,
        completedInCycle = s.completedInCycle,
        valid = true,
        pausedAccumMs = s.pausedAccumMs,
        savedAtElapsed = SystemClock.elapsedRealtime(),
        savedAtWall = System.currentTimeMillis(),
        pauseStartedWall = if (s.phase == Phase.PAUSED) System.currentTimeMillis() else 0L,
        taskPomodoroEstimate = s.taskPomodoroEstimate,
        taskPomodoroDoneAtStart = s.taskPomodoroDoneAtStart,
        sessionGuid = s.sessionGuid
    )

    private fun persist() {
        // 跟随态是镜像,不持久化:进程重启后由 presence 快照重新提供跟随入口
        if (_state.value.follow != null) return
        val snapshot = toPersisted(_state.value)
        scope.launch { settings.saveTimer(snapshot) }
    }

    private fun feedback(snap: AppSettings, text: String) {
        // 静音模式:总开关,压制震动与音效;横幅/弹窗等视觉提醒不受影响
        val quiet = snap.silentMode
        if (snap.vibrationOn && !quiet) vibrate()
        FocusService.postEvent(app, text, snap.soundOn && !quiet)
    }

    private fun vibrate() {
        runCatching {
            val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun startService() {
        app.startForegroundService(Intent(app, FocusService::class.java))
    }

    /**
     * 学霸模式激活(参考番茄ToDo):
     * 开钟即拦截、计时结束随服务销毁自动解除;科目有定制白名单则用定制集(场景化策略)。
     */
    private fun armGuard(subjectId: Long?, snap: AppSettings) {
        if (!snap.superModeOn) return
        val allow = subjectId?.let { snap.subjectWhitelist[it] } ?: snap.whitelist
        // 严格模式依赖紧急退出密码兜底;未设密码时降级为标准模式,避免无逃生口被锁死
        val strict = snap.superModeStrict && snap.emergencyPinHash.isNotEmpty()
        FocusService.startGuard(app, strict, allow)
    }

    /** 恢复现场时 settingsCache 可能尚未 prime,直接读持久化设置 */
    private suspend fun readSettingsForGuard(): AppSettings = settings.current()

    private fun stopService() {
        app.stopService(Intent(app, FocusService::class.java))
    }

    private fun notifyPhase(phase: Phase, remainingMs: Long) {
        lastNotifiedMinute = -1L
        FocusService.updateNotification(app, phase, remainingMs, _state.value.taskTitle)
    }
}

/** 连续打卡天数:从今天(或昨天)往前数有有效番茄的日子 */
fun streakDays(activeDays: List<String>): Int {
    if (activeDays.isEmpty()) return 0
    val set = activeDays.toSet()
    val today = LocalDate.now(ZoneId.systemDefault())
    var day = when {
        set.contains(today.toString()) -> today
        set.contains(today.minusDays(1).toString()) -> today.minusDays(1)
        else -> return 0
    }
    var streak = 0
    while (set.contains(day.toString())) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}
