package com.yanzhong.app.ui.mine

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.local.LocalDataWiper
import com.yanzhong.app.data.prefs.AppSettings
import com.yanzhong.app.data.prefs.PomodoroPlan
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.DeleteAccountReq
import com.yanzhong.app.data.remote.TokenStore
import com.yanzhong.app.data.remote.refreshIdentity
import com.yanzhong.app.data.stats.LearningProfile
import com.yanzhong.app.data.stats.buildLearningProfile
import com.yanzhong.app.util.TimeUtils
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账号登录态。
 *
 * Loading 专门用于首帧:DataStore 还没读出凭证时不能急着显示"未登录",
 * 否则已登录用户会先闪一下未登录文案,看起来像登录态丢了。
 */
sealed interface AccountState {
    data object Loading : AccountState
    /** 已登录;本地还没存用户名时 username 为空串 */
    data class LoggedIn(val username: String) : AccountState
    data object LoggedOut : AccountState
}

data class MineUiState(
    val settings: AppSettings = AppSettings(),
    val subjects: List<SubjectEntity> = emptyList(),
    val focusDays: Int = 0,
    val streak: Int = 0,
    val learningProfile: LearningProfile = LearningProfile(),
    val account: AccountState = AccountState.Loading,
    val identity: TokenStore.IdentityCache = TokenStore.IdentityCache()
)

class MineViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as YanZhongApp
    private val repo = container.repository
    private val settingsRepo = container.settingsRepo

    private val accountFlow: Flow<AccountState> = combine(
        TokenStore.observeLoggedIn(),
        TokenStore.observeUsername()
    ) { loggedIn, username ->
        if (loggedIn) AccountState.LoggedIn(username) else AccountState.LoggedOut
    }

    val uiState: StateFlow<MineUiState> = combine(
        settingsRepo.settings,
        repo.observeSubjects(),
        repo.observeSessions(),
        accountFlow,
        TokenStore.observeIdentity()
    ) { settings, subjects, sessions, account, identity ->
        val profile = buildLearningProfile(
            sessions = sessions,
            subjects = subjects,
            now = Instant.ofEpochMilli(TimeUtils.now()),
            zone = ZoneId.systemDefault()
        )
        MineUiState(
            settings = settings,
            subjects = subjects,
            focusDays = profile.activeDays,
            streak = profile.streakDays,
            learningProfile = profile,
            account = account,
            identity = identity
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MineUiState())

    init {
        // 登录后拉一次服务端身份(头像/昵称),写进缓存供头部卡与账号卡展示;失败静默回落本地缓存
        viewModelScope.launch {
            TokenStore.observeLoggedIn().distinctUntilChanged().collect { loggedIn ->
                if (loggedIn) refreshIdentity()
            }
        }
    }

    fun setTheme(mode: Int) {
        viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    }

    fun setVibration(on: Boolean) {
        viewModelScope.launch { settingsRepo.setVibration(on) }
    }

    fun setSound(on: Boolean) {
        viewModelScope.launch { settingsRepo.setSound(on) }
    }

    fun setSilent(on: Boolean) {
        viewModelScope.launch { settingsRepo.setSilent(on) }
    }

    fun setWeeklyGoal(hours: Int) {
        viewModelScope.launch { settingsRepo.setWeeklyGoal(hours) }
    }

    fun setDailyPomodoroGoal(count: Int) {
        viewModelScope.launch { settingsRepo.setDailyPomodoroGoal(count) }
    }

    fun setAutoChain(on: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoChain(on) }
    }

    fun setContinuousFocus(on: Boolean) {
        viewModelScope.launch { settingsRepo.setContinuousFocus(on) }
    }

    fun setCurrentPlan(name: String) {
        viewModelScope.launch { settingsRepo.setCurrentPlan(name) }
    }

    fun savePlans(plans: List<PomodoroPlan>) {
        viewModelScope.launch { settingsRepo.setPlans(plans) }
    }

    fun addSubject(name: String, colorArgb: Long) {
        viewModelScope.launch { repo.addSubject(name, colorArgb) }
    }

    /** 批量录入自定义科目(多行文本,一行一条):同名跳过,返回实际新增数经回调透出 */
    fun batchAddSubjects(text: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val added = repo.batchAddSubjects(text)
            onDone(if (added > 0) "已添加 $added 个科目" else "未识别到可添加的科目")
        }
    }

    fun deleteSubject(subject: SubjectEntity) {
        viewModelScope.launch { repo.deleteSubject(subject) }
    }

    // ---------------- 账号与安全 ----------------

    /**
     * 注销账号。
     *
     * 只有服务端确认删除了才清本地凭证——顺序反了就糟了:网络一抖,
     * 用户以为账号没了,其实服务端还好好留着;或者反过来,服务端删干净了,
     * 本地还存着一份 token,下次启动拿着废 token 反复撞墙。
     *
     * 服务端删档成功后必须把本机数据一并抹掉(凭证/设置/更新包/恢复前快照/Room 数据库),
     * 否则「注销」只注销了半份。数据库单例在清理时被关闭,当前进程不能再用,
     * 所以清理成功就重启进程回登录页;清理失败则至少清掉凭证,并提示用户卸载重装。
     */
    fun deleteAccount(username: String, password: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                ApiClient.api().deleteAccount(
                    DeleteAccountReq(confirm = username.trim(), password = password)
                )
            }
            result.fold(
                onSuccess = {
                    val app = getApplication<YanZhongApp>()
                    // 账号在服务端已不可恢复:清理不能因为页面退出/协程取消而只做一半
                    val wiped = runCatching {
                        withContext(NonCancellable) { LocalDataWiper.wipe(app) }
                    }
                    if (wiped.isSuccess) {
                        // 数据库单例已关闭,必须重启进程;用户会直接回到登录页
                        LocalDataWiper.restart(app)
                    } else {
                        TokenStore.clearAll()
                        onDone(true, "账号已注销，但本机数据清理失败，请卸载重装研钟")
                    }
                },
                onFailure = { e -> onDone(false, "注销失败:${e.readableMessage()}") }
            )
        }
    }

    /** 服务器返回的错误体对用户没什么意义,但去掉它就更没法排查;折中:取前 80 字 */
    private fun Throwable.readableMessage(): String {
        val http = this as? retrofit2.HttpException
        return when {
            this is java.net.SocketTimeoutException -> "连接超时"
            this is java.net.ConnectException -> "连不上服务器"
            this is java.net.UnknownHostException -> "找不到服务器"
            http != null -> {
                val body = http.response()?.errorBody()?.string().orEmpty().take(80)
                "HTTP ${http.code()} $body"
            }
            else -> message ?: "未知错误"
        }
    }
}
