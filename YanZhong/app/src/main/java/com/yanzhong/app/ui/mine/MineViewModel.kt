package com.yanzhong.app.ui.mine

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.prefs.AppSettings
import com.yanzhong.app.data.prefs.PomodoroPlan
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.DeleteAccountReq
import com.yanzhong.app.data.remote.TokenStore
import com.yanzhong.app.data.repo.ExportPayload
import com.yanzhong.app.timer.streakDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class MineUiState(
    val settings: AppSettings = AppSettings(),
    val subjects: List<SubjectEntity> = emptyList(),
    val focusDays: Int = 0,
    val streak: Int = 0
)

class MineViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as YanZhongApp
    private val repo = container.repository
    private val settingsRepo = container.settingsRepo

    val uiState: StateFlow<MineUiState> = combine(
        settingsRepo.settings,
        repo.observeSubjects(),
        repo.observeActiveDays(0)
    ) { settings, subjects, activeDays ->
        MineUiState(
            settings = settings,
            subjects = subjects,
            focusDays = activeDays.size,
            streak = streakDays(activeDays)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MineUiState())

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

    /** 全量数据导出到 SAF uri:读库 + 序列化 + 写文件全在 IO 线程,不卡主线程 */
    fun exportToUri(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val payload = ExportPayload(
                        subjects = repo.allSubjects(),
                        nodes = repo.allNodes(),
                        tasks = repo.allTasks(),
                        sessions = repo.allSessions()
                    )
                    val json = Json { prettyPrint = true }.encodeToString(payload)
                    getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: throw IllegalStateException("无法打开导出文件")
                    "已导出全量数据 JSON"
                }
            }
            onDone(result.getOrElse { "导出失败:${it.message}" })
        }
    }

    /**
     * 批量导入计划包 / 备份包(SAF 多选,读文件 + 解析全在 IO 线程):
     * 逐个文件合并,同名科目合并、节点任务按名去重,重复导入幂等;单个文件失败不中断后续。
     */
    fun importFromUris(uris: List<Uri>, onDone: (String) -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    var subjects = 0
                    var nodes = 0
                    var tasks = 0
                    var skipped = 0
                    var failed = 0
                    uris.forEach { uri ->
                        runCatching {
                            val json = resolver.openInputStream(uri)
                                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                                ?: throw IllegalStateException("无法读取文件")
                            repo.importJson(json)
                        }.fold(
                            onSuccess = { r ->
                                subjects += r.subjectsAdded
                                nodes += r.nodesAdded
                                tasks += r.tasksAdded
                                skipped += r.skipped
                            },
                            onFailure = { failed++ }
                        )
                    }
                    "导入 ${uris.size - failed} 个文件:科目 +$subjects · 节点 +$nodes · 任务 +$tasks · 跳过重复 $skipped" +
                        if (failed > 0) " · $failed 个失败" else ""
                }
            }
            onDone(result.getOrElse { "导入失败:${it.message}" })
        }
    }

    // ---------------- 账号与安全 ----------------

    /**
     * 把服务端的账号全量数据导出到 SAF uri。
     *
     * 与 [exportToUri] 的区别:那个导的是**本机库**,这个导的是**服务器上属于你的那份**。
     * 两者内容高度重合,但不能互相替代——换机时人真正想要的是"服务器上那份备份",
     * 因为它包含别台设备同步上去、本机还没拉下来的东西。
     */
    fun exportAccountToUri(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val payload = ApiClient.api().exportAccount()
                    val text = Json { prettyPrint = true }.encodeToString(payload)
                    getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw IllegalStateException("无法打开导出文件")
                    "已导出账号数据,共 ${text.length} 字符"
                }
            }
            onDone(result.getOrElse { "导出失败:${it.readableMessage()}" })
        }
    }

    /**
     * 注销账号。
     *
     * 只有服务端确认删除了才清本地凭证——顺序反了就糟了:网络一抖,
     * 用户以为账号没了,其实服务端还好好留着;或者反过来,服务端删干净了,
     * 本地还存着一份 token,下次启动拿着废 token 反复撞墙。
     */
    fun deleteAccount(username: String, password: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                ApiClient.api().deleteAccount(
                    DeleteAccountReq(confirm = username.trim(), password = password)
                )
            }
            result.fold(
                onSuccess = { resp ->
                    TokenStore.clearAll()
                    onDone(true, "账号已注销,服务端清理了 ${resp.deleted} 条记录")
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
