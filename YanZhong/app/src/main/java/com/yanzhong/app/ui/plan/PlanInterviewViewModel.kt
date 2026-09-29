package com.yanzhong.app.ui.plan

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.InterviewMessageDto
import com.yanzhong.app.data.remote.InterviewReq
import com.yanzhong.app.data.remote.PlanBriefDto
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.data.remote.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

/**
 * 面谈会话的本地存档：退出页面（ViewModel 随之销毁）或进程被杀后还能接着聊，
 * 不用把同一批问题重答一遍。接口本身是无状态的——整段历史由客户端全量携带，
 * 所以本地留一份即可原样续上。
 */
@Serializable
private data class InterviewSessionSnapshot(
    /** 归属账号：换号登录后不认领别人的存档 */
    val accountGuid: String? = null,
    val messages: List<InterviewMessageDto> = emptyList(),
    val options: List<String> = emptyList(),
    val done: Boolean = false,
    val brief: PlanBriefDto? = null
)

private class InterviewSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("plan_interview", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** 存档损坏/结构升级导致解析失败时当作没有存档，绝不能因此让面谈页崩掉 */
    fun load(): InterviewSessionSnapshot? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching { json.decodeFromString(InterviewSessionSnapshot.serializer(), raw) }.getOrNull()
    }

    fun save(snapshot: InterviewSessionSnapshot) {
        val raw = runCatching { json.encodeToString(InterviewSessionSnapshot.serializer(), snapshot) }.getOrNull() ?: return
        prefs.edit().putString(KEY_SESSION, raw).apply()
    }

    fun clear() { prefs.edit().remove(KEY_SESSION).apply() }

    private companion object { const val KEY_SESSION = "session" }
}

class PlanInterviewViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(InterviewUiState())
    val state: StateFlow<InterviewUiState> = _ui
    private var started = false
    private val session = InterviewSessionStore(app)
    private var accountGuid: String? = null

    fun beginSession(profileJustSaved: Boolean = false) {
        if (started && !profileJustSaved) return
        started = true
        _ui.value = InterviewUiState()
        viewModelScope.launch {
            accountGuid = runCatching { TokenStore.currentAccountGuid() }.getOrNull()
            // 问卷刚改过：正式科目/空档这些事实已并进简报，旧对话的前提变了，作废重问
            if (profileJustSaved) session.clear()
            val profile = runCatching { ApiClient.api().getProfile() }.getOrNull()?.profile
            if (profile?.isComplete != true) {
                _ui.update { it.copy(phase = InterviewPhase.NEED_PROFILE) }
                return@launch
            }
            val saved = if (profileJustSaved) null else session.load()
                ?.takeIf { it.messages.isNotEmpty() && it.accountGuid == accountGuid }
            if (saved != null) {
                _ui.value = InterviewUiState(phase = InterviewPhase.CHATTING, messages = saved.messages,
                    options = saved.options, done = saved.done, brief = saved.brief)
                // 存档停在"考生刚发完、AI 还没回"：补问一次，否则续上也没得可点
                if (saved.messages.last().role == "user") advance()
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
                options = emptyList(), done = false, error = null)
        }
        // 先落盘再发请求：请求途中被杀，考生刚打的内容也不会白费
        persist()
        viewModelScope.launch { advance() }
    }

    fun generate() {
        if (!_ui.value.canGenerate) return
        viewModelScope.launch {
            _ui.update { it.copy(generating = true, error = null, activePlan = null, activeCompared = false) }
            runCatching { ApiClient.aiApi().generatePlan().plan?.takeIf { p -> p.status == "draft" }
                ?: error("服务端没有返回草稿") }.fold({ draft ->
                _ui.update { it.copy(phase = InterviewPhase.DRAFT, plan = draft, generating = false) }
                // 草稿仍留在服务端；本地只记「面谈已完成」，退出后再进来还能一键重新拉草稿
                persist()
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
                // 计划已生效，面谈存档用完即弃：下次进来该从新计划出发，而不是看到一份过期对话
                persist()
            }, { e ->
                _ui.update { it.copy(confirming = false, error = "确认失败(${e.userMessage()})，草稿仍可查看，请重试") }
            })
        }
    }

    fun backToChat() {
        if (_ui.value.phase != InterviewPhase.DRAFT || _ui.value.busy) return
        // 能进 DRAFT 说明面谈已经问完，回来纠正时保留「已完成」：
        // 否则既没有可点的选项、也没有「生成计划草稿」按钮，只能干瞪眼。
        _ui.update { it.copy(phase = InterviewPhase.CHATTING, plan = null, activePlan = null,
            activeCompared = false, done = true, options = emptyList(), error = null) }
        persist()
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
                    // 中途也保留 brief：顶部副标题要显示「已确认了什么」
                    done = resp.done, brief = resp.brief ?: it.brief, sending = false)
            }
            persist()
        }, { e ->
            _ui.update { it.copy(sending = false, error = "AI 这次没回上话(${e.userMessage()})，点重试继续") }
            // 失败也要落盘：下次进来是接着这轮重试，而不是从头再答一遍
            persist()
        })
    }

    /**
     * 落盘当前会话；计划一旦生效就清空存档——那份对话已经用完了，
     * 下次进来该从新计划出发，而不是把旧面谈再推给考生。
     */
    private fun persist() {
        val s = _ui.value
        if (s.phase == InterviewPhase.SUCCESS) { session.clear(); return }
        if (s.messages.isEmpty()) return
        session.save(InterviewSessionSnapshot(accountGuid = accountGuid, messages = s.messages,
            options = s.options, done = s.done, brief = s.brief))
    }

    /** 把异常翻译成人话；不要把服务端原始 body / HTTP 400 直接甩给考生 */
    private fun Throwable.userMessage(): String = when (this) {
        is retrofit2.HttpException -> when (val code = code()) {
            400, 422 -> "这次请求没被接受，多半是输入太长，精简一下再试"
            401, 403 -> "登录已过期，请重新登录"
            503 -> "服务端还没配置好 AI"
            in 500..599 -> "服务端暂时不可用($code)"
            else -> "请求失败($code)"
        }
        else -> message ?: "未知错误"
    }
}
