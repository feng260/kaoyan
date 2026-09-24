package com.yanzhong.app.ui.mine

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.prefs.AppSettings
import com.yanzhong.app.data.prefs.PomodoroPlan
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
}
