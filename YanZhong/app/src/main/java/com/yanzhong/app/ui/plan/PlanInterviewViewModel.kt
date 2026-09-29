package com.yanzhong.app.ui.plan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.InterviewMessageDto
import com.yanzhong.app.data.remote.InterviewReq
import com.yanzhong.app.data.remote.PlanBriefDto
import com.yanzhong.app.data.remote.PlanDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 草稿只在本页面预览；确认成功前不向计划页发送刷新事件。 */
enum class InterviewPhase { LOADING, NEED_PROFILE, CHATTING, DRAFT, SUCCESS }

data class InterviewUiState(
    val phase: InterviewPhase = InterviewPhase.LOADING,
    val messages: List<InterviewMessageDto> = emptyList(),
    val options: List<String> = emptyList(),
    val sending: Boolean = false,
    val generating: Boolean = false,
    val confirming: Boolean = false,
    val done: Boolean = false,
    val brief: PlanBriefDto? = null,
    val error: String? = null,
    val plan: PlanDto? = null,
    val activePlan: PlanDto? = null,
    val activeCompared: Boolean = false
) {
    val busy: Boolean get() = sending || generating || confirming
    val canGenerate: Boolean get() = phase == InterviewPhase.CHATTING && done && !busy
    val canConfirm: Boolean get() = phase == InterviewPhase.DRAFT && plan?.status == "draft" && !busy
}

class PlanInterviewViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(InterviewUiState())
    val state: StateFlow<InterviewUiState> = _ui
    private var started = false

    fun beginSession(profileJustSaved: Boolean = false) {
        if (started && !profileJustSaved) return
        started = true
        _ui.value = InterviewUiState()
        viewModelScope.launch {
            val profile = runCatching { ApiClient.api().getProfile() }.getOrNull()?.profile
            if (profile?.isComplete != true) {
                _ui.update { it.copy(phase = InterviewPhase.NEED_PROFILE) }
                return@launch
            }
            _ui.update { it.copy(phase = InterviewPhase.CHATTING) }
            advance()
        }
    }

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _ui.value.busy || _ui.value.phase != InterviewPhase.CHATTING) return
        _ui.update {
            it.copy(messages = it.messages + InterviewMessageDto("user", content),
                options = emptyList(), done = false, brief = null, error = null)
        }
        viewModelScope.launch { advance() }
    }

    fun generate() {
        if (!_ui.value.canGenerate) return
        viewModelScope.launch {
            _ui.update { it.copy(generating = true, error = null, activePlan = null, activeCompared = false) }
            runCatching { ApiClient.aiApi().generatePlan().plan?.takeIf { p -> p.status == "draft" }
                ?: error("服务端没有返回草稿") }.fold({ draft ->
                _ui.update { it.copy(phase = InterviewPhase.DRAFT, plan = draft, generating = false) }
                // 对比只是辅助信息；读取失败不阻塞草稿预览或确认。
                runCatching { ApiClient.api().getActivePlan().plan }.onSuccess { active ->
                    _ui.update { it.copy(activePlan = active, activeCompared = true) }
                }
            }, { e ->
                _ui.update { it.copy(generating = false, error = "草稿生成失败：${e.userMessage()}。可继续纠正后重试") }
            })
        }
    }

    fun confirm() {
        val draft = _ui.value.plan?.takeIf { _ui.value.canConfirm } ?: return
        viewModelScope.launch {
            _ui.update { it.copy(confirming = true, error = null) }
            runCatching { ApiClient.api().confirmPlan(draft.id).plan?.takeIf { it.status == "active" }
                ?: error("服务端没有返回生效计划") }.fold({ active ->
                _ui.update { it.copy(phase = InterviewPhase.SUCCESS, plan = active, confirming = false) }
            }, { e ->
                _ui.update { it.copy(confirming = false, error = "确认失败(${e.userMessage()})，草稿仍可查看，请重试") }
            })
        }
    }

    fun backToChat() {
        if (_ui.value.phase != InterviewPhase.DRAFT || _ui.value.busy) return
        _ui.update { it.copy(phase = InterviewPhase.CHATTING, plan = null, activePlan = null,
            activeCompared = false, done = false, brief = null, options = emptyList(), error = null) }
    }

    fun retry() {
        if (_ui.value.busy) return
        when {
            _ui.value.phase == InterviewPhase.DRAFT -> confirm()
            _ui.value.canGenerate -> generate()
            _ui.value.phase == InterviewPhase.CHATTING -> viewModelScope.launch { advance() }
        }
    }

    fun dismissError() { _ui.update { it.copy(error = null) } }

    private suspend fun advance() {
        _ui.update { it.copy(sending = true, error = null) }
        val snapshot = _ui.value.messages
        runCatching { ApiClient.aiApi().interview(InterviewReq(messages = snapshot)) }.fold({ resp ->
            _ui.update {
                it.copy(messages = snapshot + InterviewMessageDto("assistant", resp.reply),
                    options = if (resp.done) emptyList() else resp.options,
                    done = resp.done, brief = if (resp.done) resp.brief else null, sending = false)
            }
        }, { e ->
            _ui.update { it.copy(sending = false, error = "AI 这次没回上话(${e.userMessage()})，点重试继续") }
        })
    }

    private fun Throwable.userMessage(): String =
        (this as? retrofit2.HttpException)?.let { http ->
            val body = http.response()?.errorBody()?.string().orEmpty()
            val detail = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(body)
                    .let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("message")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
            }.getOrNull().orEmpty().take(180)
            "HTTP ${http.code()}${if (detail.isBlank()) "" else "：$detail"}"
        } ?: message ?: "未知错误"
}
