package com.yanzhong.app.ui.plan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.data.remote.PlanItemDto
import com.yanzhong.app.data.remote.PlanStageDto
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

internal fun PlanDto.upcomingItems(stage: PlanStageDto, today: LocalDate): List<PlanItemDto> =
    items.asSequence()
        .filter { it.stageId == stage.id && it.planDate >= today.toString() && it.status != "done" }
        .sortedWith(compareBy<PlanItemDto> { it.planDate }.thenBy { it.sortOrder })
        .take(8)
        .toList()

/** 本地周任务独立保留；服务端计划仅为计划页的附加数据源。 */
class PlanViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as YanZhongApp).repository
    private val _serverPlan = MutableStateFlow<PlanDto?>(null)
    val serverPlan: StateFlow<PlanDto?> = _serverPlan
    private val _planLoading = MutableStateFlow(false)
    val planLoading: StateFlow<Boolean> = _planLoading
    private val _planUnavailable = MutableStateFlow(false)
    val planUnavailable: StateFlow<Boolean> = _planUnavailable

    init { refreshPlan() }

    fun refreshPlan() {
        if (_planLoading.value) return
        viewModelScope.launch {
            _planLoading.value = true
            _planUnavailable.value = false
            try {
                _serverPlan.value = if (TokenStore.currentAccess() == null) null
                else ApiClient.api().getActivePlan().plan
                _planUnavailable.value = _serverPlan.value == null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _serverPlan.value = null
                _planUnavailable.value = true
            } finally {
                _planLoading.value = false
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
