package com.yanzhong.app.ui.focus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.prefs.AppSettings
import com.yanzhong.app.data.remote.PeerState
import com.yanzhong.app.timer.FollowSpec
import com.yanzhong.app.timer.Phase
import com.yanzhong.app.timer.TimerState
import com.yanzhong.app.util.TimeUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FocusUiState(
    val timer: TimerState = TimerState(),
    val todayFocusMin: Int = 0,
    val taskPomodoroToday: Int = 0,
    val subjects: List<SubjectEntity> = emptyList(),
    val todayTasks: List<TaskEntity> = emptyList(),
    val settings: AppSettings = AppSettings()
)

@OptIn(ExperimentalCoroutinesApi::class)
class FocusViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as YanZhongApp
    private val repo = container.repository
    private val engine = container.engine
    private val settingsRepo = container.settingsRepo
    private val db = container.database

    /** 同账号其他在线设备(多端跟随入口) */
    val peers = container.statusSync.peers

    /** 日边界流:跨午夜后重查「今日」口径(番茄数/今日专注分钟) */
    private val todayKey = repo.tickingNow()
        .map { TimeUtils.dayStartOf(it) }
        .distinctUntilChanged()

    /** 本任务今日已完成番茄数:仅以 taskId 为流键(引擎每秒发剩余时间,不重订阅 Room) */
    private val taskPomodoro = combine(
        engine.state.map { it.taskId }.distinctUntilChanged(),
        todayKey
    ) { taskId, _ -> taskId }
        .flatMapLatest { taskId ->
            if (taskId == null) flowOf(0)
            else db.sessionDao().observeCountForTaskSince(taskId, TimeUtils.dayStartOf())
        }

    /** 今日专注分钟:跨午夜自动切换统计窗口 */
    private val todayFocusMin = todayKey.flatMapLatest { dayStart ->
        repo.observeDurationMin(dayStart, Long.MAX_VALUE)
    }

    private data class CoreInfo(
        val timer: TimerState,
        val taskPomodoroToday: Int,
        val todayFocusMin: Int
    )

    private data class ListInfo(
        val subjects: List<SubjectEntity>,
        val todayTasks: List<TaskEntity>,
        val settings: AppSettings
    )

    private val coreInfo = combine(
        engine.state,
        taskPomodoro,
        todayFocusMin
    ) { timer, taskPomo, todayMin ->
        CoreInfo(timer, taskPomo, todayMin)
    }

    private val listInfo = combine(
        repo.observeSubjects(),
        repo.observeTodayView(),
        settingsRepo.settings
    ) { subjects, today, settings ->
        ListInfo(subjects, today.open, settings)
    }

    val uiState: StateFlow<FocusUiState> = combine(coreInfo, listInfo) { core, lists ->
        FocusUiState(
            timer = core.timer,
            todayFocusMin = core.todayFocusMin,
            taskPomodoroToday = core.taskPomodoroToday,
            subjects = lists.subjects,
            todayTasks = lists.todayTasks,
            settings = lists.settings
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FocusUiState())

    fun startForTask(task: TaskEntity) {
        val subject = uiState.value.subjects.firstOrNull { it.id == task.subjectId }
        viewModelScope.launch {
            engine.startFocus(
                taskId = task.id,
                taskTitle = task.title,
                subjectId = task.subjectId,
                subjectColorArgb = subject?.colorArgb ?: 0xFF5865F2,
                plan = settingsRepo.current().currentPlan,
                taskPomodoroEstimate = task.pomodoroEstimate,
                taskPomodoroDoneAtStart = task.completedPomodoros
            )
        }
    }

    fun quickFocus() {
        viewModelScope.launch {
            engine.startFocus(
                taskId = null,
                taskTitle = "",
                subjectId = null,
                subjectColorArgb = 0xFF5865F2,
                plan = settingsRepo.current().currentPlan
            )
        }
    }

    fun startForTaskStopwatch(task: TaskEntity) {
        val subject = uiState.value.subjects.firstOrNull { it.id == task.subjectId }
        engine.startStopwatch(
            taskId = task.id,
            taskTitle = task.title,
            subjectId = task.subjectId,
            subjectColorArgb = subject?.colorArgb ?: 0xFF5865F2
        )
    }

    fun quickStopwatch() {
        engine.startStopwatch(
            taskId = null,
            taskTitle = "",
            subjectId = null,
            subjectColorArgb = 0xFF5865F2
        )
    }

    fun startForTaskCountdown(task: TaskEntity, minutes: Int) {
        val subject = uiState.value.subjects.firstOrNull { it.id == task.subjectId }
        engine.startCountdown(
            minutes = minutes,
            taskId = task.id,
            taskTitle = task.title,
            subjectId = task.subjectId,
            subjectColorArgb = subject?.colorArgb ?: 0xFF5865F2
        )
    }

    fun quickCountdown(minutes: Int) {
        engine.startCountdown(
            minutes = minutes,
            taskId = null,
            taskTitle = "",
            subjectId = null,
            subjectColorArgb = 0xFF5865F2
        )
    }

    fun finishStopwatch() = engine.finishStopwatch()

    // ---------- 多端跟随 ----------

    /** 跟随他端(主端)的专注:镜像其阶段与倒计时,只展示不落库 */
    fun followPeer(peer: PeerState) {
        if (peer.sessionGuid.isEmpty()) return
        val phase = when (peer.phase) {
            "break" -> Phase.SHORT_BREAK
            "focus" -> Phase.FOCUSING
            else -> return
        }
        val endsAt = if (peer.paused) System.currentTimeMillis() + peer.remainMs else peer.endsAtLocalMs
        if (endsAt <= 0) return
        engine.enterFollow(
            FollowSpec(
                sessionGuid = peer.sessionGuid,
                peerDeviceId = peer.deviceId,
                peerName = peer.deviceName,
                taskTitle = peer.taskTitle.ifBlank { "自由专注" }
            ),
            phase,
            endsAt
        )
    }

    fun exitFollow() = engine.exitFollow(null)

    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun skipBreak() = engine.skipBreak()

    /** 休息时段直接结束专注:不落库、不消耗紧急退出月配额(休息本就不在番茄内) */
    fun endDuringBreak() = engine.abandon(null)

    /**
     * 紧急退出(防逃逸强化):唯一的专注中断出口,每月配额 4 次。
     * 非严格模式直接消耗配额退出;严格模式需 6 位密码验证,通过后消耗配额强制结束,
     * 落库为非正常退出(valid=false),在统计页中断分析中体现。
     */
    fun emergencyExit(pin: String?, reason: String? = null, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val settings = settingsRepo.current()
            val quotaLeft = AppSettings.EMERGENCY_EXIT_QUOTA -
                if (settings.emergencyExitMonth == settingsRepo.currentMonthKey())
                    settings.emergencyExitsUsed else 0
            if (quotaLeft <= 0) {
                android.widget.Toast.makeText(
                    getApplication(), "本月紧急退出次数已用完,请等待计时自然结束", android.widget.Toast.LENGTH_LONG
                ).show()
                onResult(false)
                return@launch
            }
            val ok = if (pin == null) true else settingsRepo.verifyEmergencyPin(settings, pin)
            if (ok) {
                val remaining = settingsRepo.consumeEmergencyExit()
                engine.abandon(reason ?: if (pin == null) "紧急退出" else "严格模式紧急退出")
                android.widget.Toast.makeText(
                    getApplication(),
                    "已紧急退出,本次专注记录为非正常退出 · 本月剩余 $remaining 次",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            onResult(ok)
        }
    }

    /** 专注中切换归属科目(快速开钟补选,PRD 3.4) */
    fun switchSubject(subject: SubjectEntity) {
        engine.switchSubject(subject.id, subject.colorArgb)
    }

    fun selectPlan(name: String) {
        viewModelScope.launch { settingsRepo.setCurrentPlan(name) }
    }

    fun toggleAutoChain(on: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoChain(on) }
    }

    fun toggleContinuous(on: Boolean) {
        viewModelScope.launch { settingsRepo.setContinuousFocus(on) }
    }
}
