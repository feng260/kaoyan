package com.yanzhong.app.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.CheckupResp
import com.yanzhong.app.data.remote.TokenStore
import com.yanzhong.app.data.remote.apiErrorMessage
import com.yanzhong.app.util.TimeUtils
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CheckupUiState(
    val loading: Boolean = true,
    val checkup: CheckupResp? = null,
    val error: String = "",
    val dismissed: Boolean = false
)

class CheckupViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("plan_checkup", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(CheckupUiState())
    val ui: StateFlow<CheckupUiState> = _ui
    private var loadedForDay: String? = null

    /** 首页拉体检时不触发服务端提醒(notify=0),提醒只走后台 15 分钟轮询,避免打开首页就弹通知 */
    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            if (TokenStore.currentAccess() == null) {
                _ui.update { it.copy(loading = false, checkup = null, error = "") }
                return@launch
            }
            val today = LocalDate.now().toString()
            if (!force && loadedForDay == today && _ui.value.checkup != null) return@launch
            _ui.update { it.copy(loading = true, error = "") }
            runCatching {
                ApiClient.api().planCheckup(
                    tzOffset = TimeUtils.currentTzOffsetMinutes(),
                    notify = 0
                )
            }.fold({ checkup ->
                loadedForDay = today
                _ui.update {
                    it.copy(
                        loading = false,
                        checkup = checkup,
                        error = "",
                        dismissed = prefs.getString(KEY_DISMISSED_DAY, null) == today
                    )
                }
            }, { e ->
                _ui.update { it.copy(loading = false, error = e.apiErrorMessage()) }
            })
        }
    }

    fun dismissForToday() {
        val today = LocalDate.now().toString()
        prefs.edit().putString(KEY_DISMISSED_DAY, today).apply()
        _ui.update { it.copy(dismissed = true) }
    }

    companion object {
        private const val KEY_DISMISSED_DAY = "dismissed_day"
    }
}
