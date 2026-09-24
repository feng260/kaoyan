package com.yanzhong.app.ui.plan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.util.TimeUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PlanUiState(
    val weekTasks: List<TaskEntity> = emptyList(),
    val openTasks: List<TaskEntity> = emptyList(),
    val subjects: List<SubjectEntity> = emptyList(),
    val today: Long = System.currentTimeMillis()
) {
    val weekStart: Long get() = TimeUtils.weekStartOf(today)
}

/** 计划页:本周视图(模板按星期展开 + 里程碑概览)+ 全程规划 */
class PlanViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as YanZhongApp
    private val repo = container.repository

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
