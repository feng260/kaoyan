package com.yanzhong.app.ui.plan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.remote.AdjustmentDto
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.data.remote.PlanItemDto
import com.yanzhong.app.data.remote.PlanItemStatusReq
import com.yanzhong.app.data.remote.PlanStageDto
import com.yanzhong.app.data.remote.SyncNotice
import com.yanzhong.app.data.remote.TokenStore
import com.yanzhong.app.util.TimeUtils
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlanUiState(
    val weekTasks: List<TaskEntity> = emptyList(),
    val openTasks: List<TaskEntity> = emptyList(),
    val subjects: List<SubjectEntity> = emptyList(),
    val today: Long = System.currentTimeMillis()
) {
    val weekStart: Long get() = TimeUtils.weekStartOf(today)
}

internal fun PlanDto.currentStage(today: LocalDate): PlanStageDto? =
    stages.sortedBy { it.sortOrder }.firstOrNull {
        it.startDate <= today.toString() && today.toString() <= it.endDate
    }

internal fun PlanDto.dailyItems(date: LocalDate): List<PlanItemDto> =
    items.filter { it.planDate == date.toString() }
        .sortedWith(compareBy<PlanItemDto> { it.sortOrder }.thenBy { it.id })

internal fun PlanDto.dailyProgress(date: LocalDate): Pair<Int, Int> =
    dailyItems(date).let { day -> day.count { it.status == "done" } to day.size }

internal fun PlanDto.upcomingItems(stage: PlanStageDto, today: LocalDate): List<PlanItemDto> =
    items.asSequence()
        .filter { it.stageId == stage.id && it.planDate >= today.toString() && it.status != "done" }
        .sortedWith(compareBy<PlanItemDto> { it.planDate }.thenBy { it.sortOrder })
        .take(8)
        .toList()

internal enum class PlanDisplayState { LOGIN_REQUIRED, LOADING, NO_PLAN, NETWORK_ERROR, READY }

internal fun selectPlanDisplayState(
    loggedIn: Boolean,
    plan: PlanDto?,
    networkError: Boolean,
    loading: Boolean
): PlanDisplayState = when {
    !loggedIn -> PlanDisplayState.LOGIN_REQUIRED
    plan != null -> PlanDisplayState.READY
    loading -> PlanDisplayState.LOADING
    networkError -> PlanDisplayState.NETWORK_ERROR
    else -> PlanDisplayState.NO_PLAN
}

class PlanViewModel(app: Application) : AndroidViewModel(app) {
    private val yanZhongApp = app as YanZhongApp
    private val repo = yanZhongApp.repository
    private val settingsRepo = yanZhongApp.settingsRepo
    private val requestLock = Mutex()
    private val _serverPlan = MutableStateFlow<PlanDto?>(null)
    val serverPlan: StateFlow<PlanDto?> = _serverPlan
    private val _planLoading = MutableStateFlow(false)
    val planLoading: StateFlow<Boolean> = _planLoading
    private val _planUnavailable = MutableStateFlow(false)
    val planUnavailable: StateFlow<Boolean> = _planUnavailable
    private val _loggedIn = MutableStateFlow(true)
    val loggedIn: StateFlow<Boolean> = _loggedIn
    private val _planError = MutableStateFlow<String?>(null)
    val planError: StateFlow<String?> = _planError
    private val _updatingItemId = MutableStateFlow<Long?>(null)
    val updatingItemId: StateFlow<Long?> = _updatingItemId
    private val _latestAdjustment = MutableStateFlow<AdjustmentDto?>(null)
    val latestAdjustment: StateFlow<AdjustmentDto?> = _latestAdjustment
    private val _undoing = MutableStateFlow(false)
    val undoing: StateFlow<Boolean> = _undoing

    init {
        refreshPlan()
        viewModelScope.launch {
            yanZhongApp.statusSync.notices.collect { notice ->
                if (notice is SyncNotice.PlanChanged) refreshPlan()
            }
        }
    }

    fun refreshPlan() {
        viewModelScope.launch {
            requestLock.withLock {
                _planLoading.value = true
                _planError.value = null
                try {
                    val account = TokenStore.currentAccountGuid()
                    val authenticated = TokenStore.currentAccess() != null && !account.isNullOrBlank()
                    _loggedIn.value = authenticated
                    if (!authenticated) {
                        _serverPlan.value = null
                        _planUnavailable.value = false
                        _latestAdjustment.value = null
                        return@withLock
                    }
                    val plan = repo.fetchActivePlan()
                    if (plan == null) repo.clearPlanProjections(account!!)
                    else repo.applyPlanProjection(plan, account!!, settingsRepo.current().currentPlan.focusMin)
                    _serverPlan.value = plan
                    _planUnavailable.value = plan == null
                    _latestAdjustment.value = runCatching { ApiClient.api().getLatestAdjustment().adjustment }.getOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _planError.value = "网络异常，无法获取云端计划，请重试"
                } finally {
                    _planLoading.value = false
                }
            }
        }
    }

    fun toggleItem(item: PlanItemDto) {
        if (_planLoading.value || _updatingItemId.value != null || _serverPlan.value?.items?.none { it.id == item.id } != false) return
        _updatingItemId.value = item.id
        viewModelScope.launch {
            try {
                requestLock.withLock {
                    val account = TokenStore.currentAccountGuid()
                    if (TokenStore.currentAccess() == null || account.isNullOrBlank()) {
                        _loggedIn.value = false
                        _serverPlan.value = null
                        return@withLock
                    }
                    val current = _serverPlan.value?.items?.firstOrNull { it.id == item.id } ?: return@withLock
                    val status = if (current.status == "done") "pending" else "done"
                    // slim:服务端只回被改的那一项与进度(~1KB),本地合并进缓存的 plan 得到完整新计划
                    val slim = ApiClient.aiApi().patchPlanItemSlim(item.id, slim = true, body = PlanItemStatusReq(status))
                    val basePlan = _serverPlan.value ?: throw IllegalStateException("本地计划缓存丢失")
                    val plan = basePlan.copy(
                        items = basePlan.items.map { if (it.id == slim.item.id) slim.item else it },
                        progress = slim.progress,
                    )
                    repo.applyPlanProjection(plan, account, settingsRepo.current().currentPlan.focusMin)
                    _serverPlan.value = plan
                    _planError.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _planError.value = "更新失败，请重试；完成状态未改变"
            } finally {
                _updatingItemId.value = null
            }
        }
    }

    /** 撤销最近一次已生效的调整:服务端把 beforeJson 写回,planChanged 广播后各端自动更新 */
    fun undoLatestAdjustment() {
        val latest = _latestAdjustment.value?.takeIf { it.status == "applied" } ?: return
        if (_undoing.value) return
        viewModelScope.launch {
            _undoing.value = true
            try {
                requestLock.withLock {
                    val account = TokenStore.currentAccountGuid()
                    if (TokenStore.currentAccess() == null || account.isNullOrBlank()) return@withLock
                    val plan = ApiClient.api().undoAdjustment(latest.id).plan
                        ?: throw IllegalStateException("服务端未返回计划")
                    repo.applyPlanProjection(plan, account, settingsRepo.current().currentPlan.focusMin)
                    _serverPlan.value = plan
                    _planError.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _planError.value = "撤销失败，请重试；计划未改变"
            } finally {
                _undoing.value = false
                _latestAdjustment.value = runCatching { ApiClient.api().getLatestAdjustment().adjustment }.getOrNull()
            }
        }
    }

    val uiState: StateFlow<PlanUiState> = combine(
        repo.observeWeekView(),
        repo.observeOpenTasks(),
        repo.observeSubjects()
    ) { weekTasks, openTasks, subjects ->
        PlanUiState(
            weekTasks = weekTasks,
            openTasks = openTasks,
            subjects = subjects,
            today = TimeUtils.now()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlanUiState())
}
