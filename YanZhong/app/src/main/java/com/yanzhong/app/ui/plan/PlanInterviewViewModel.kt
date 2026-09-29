package com.yanzhong.app.ui.plan

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.data.remote.AdjustmentDto
import com.yanzhong.app.data.remote.AdjustReq
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/** 草稿只在本页面预览；确认成功前不向计划页发送刷新事件。 */
enum class InterviewPhase { LOADING, NEED_PROFILE, CHATTING, DRAFT, SUCCESS }

/** 对话模式:BUILD=制定/重新生成计划;ADJUST=已有生效计划,对话即行程小助手 */
enum class InterviewMode { BUILD, ADJUST }

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
    val mode: InterviewMode = InterviewMode.BUILD,
    val adjustment: AdjustmentDto? = null,
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
    val brief: PlanBriefDto? = null,
    val mode: InterviewMode = InterviewMode.BUILD
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
            // 模式分流:刚改完档案 → 重新生成(BUILD);有存档 → 续上次的模式;
            // 都没有 → 已有生效计划就是行程小助手,否则从头制定
            val activePlan = runCatching { ApiClient.api().getActivePlan().plan }.getOrNull()
            val mode = when {
                profileJustSaved -> InterviewMode.BUILD
                saved != null -> saved.mode
                else -> if (activePlan != null) InterviewMode.ADJUST else InterviewMode.BUILD
            }
            _ui.update { it.copy(mode = mode) }
            if (mode == InterviewMode.ADJUST) {
                if (saved != null) {
                    _ui.value = InterviewUiState(phase = InterviewPhase.CHATTING, mode = mode,
                        messages = saved.messages, activePlan = activePlan)
                    // 存档停在「刚发完、AI 还没回」:补发一次
                    if (saved.messages.last().role == "user") adjust(saved.messages.last().content)
                } else {
                    _ui.value = InterviewUiState(phase = InterviewPhase.CHATTING, mode = mode,
                        activePlan = activePlan,
                        messages = listOf(InterviewMessageDto("assistant",
                            "计划正在跑。临时有事、生病、换课表,直接跟我说一句,我帮你把近期计划调好——确认前不会改动现在的安排。")))
                }
                // 恢复还没确认的调整单:冷启动、切页回来不断片
                runCatching { ApiClient.api().getLatestAdjustment() }.getOrNull()?.adjustment
                    ?.takeIf { it.status == "draft" }
                    ?.let { latest -> _ui.update { it.copy(adjustment = latest) } }
                return@launch
            }
            if (saved != null) {
                _ui.value = InterviewUiState(phase = InterviewPhase.CHATTING, mode = mode,
                    messages = saved.messages, options = saved.options, done = saved.done, brief = saved.brief)
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
        viewModelScope.launch {
            if (_ui.value.mode == InterviewMode.ADJUST) adjust(content) else advance()
        }
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

    /** 调整模式的一轮:这句话交给 POST /plans/adjust,回来的是追问、闲聊或一张待确认调整单 */
    private suspend fun adjust(message: String) {
        _ui.update { it.copy(sending = true, error = null) }
        runCatching { ApiClient.aiApi().adjustPlan(AdjustReq(message = message)) }.fold({ resp ->
            _ui.update {
                it.copy(messages = it.messages + InterviewMessageDto("assistant", resp.reply.orEmpty()),
                    adjustment = resp.adjustment ?: it.adjustment, sending = false)
            }
            persist()
        }, { e ->
            _ui.update { it.copy(sending = false, error = "这次没算好(${e.userMessage()})，点重试继续或换个说法") }
            persist()
        })
    }

    /** 确认调整单:服务端就地改写计划项并广播 planChanged,计划页自动更新 */
    fun confirmAdjustment() {
        val adjustment = _ui.value.adjustment?.takeIf { it.status == "draft" } ?: return
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(confirming = true, error = null) }
            runCatching { ApiClient.api().confirmAdjustment(adjustment.id).plan }
                .fold({ plan ->
                    _ui.update {
                        it.copy(confirming = false, adjustment = null,
                            phase = InterviewPhase.SUCCESS, plan = plan)
                    }
                    persist()
                }, { e ->
                    _ui.update { it.copy(confirming = false, error = "确认失败(${e.userMessage()})，请重试或重新描述") }
                })
        }
    }

    /** 「重说一次」:只收起卡片;服务端的 draft 会被下一次 adjust 原地覆盖 */
    fun dismissAdjustmentCard() { _ui.update { it.copy(adjustment = null) } }

    fun retry() {
        if (_ui.value.busy) return
        when {
            _ui.value.phase == InterviewPhase.DRAFT -> confirm()
            _ui.value.canGenerate -> generate()
            _ui.value.phase == InterviewPhase.CHATTING -> viewModelScope.launch {
                if (_ui.value.mode == InterviewMode.ADJUST) {
                    val last = _ui.value.messages.lastOrNull { it.role == "user" }?.content
                    if (last != null) adjust(last) else advance()
                } else {
                    advance()
                }
            }
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
            options = s.options, done = s.done, brief = s.brief, mode = s.mode))
    }

    /** 把异常翻译成人话；不要把服务端原始 body / HTTP 400 直接甩给考生 */
    private fun Throwable.userMessage(): String = when (this) {
        is HttpException -> serverError()?.friendly() ?: when (val code = code()) {
            400, 422 -> "这次请求没被接受，多半是输入太长，精简一下再试"
            401, 403 -> "登录已过期，请重新登录"
            503 -> "服务端还没配置好 AI"
            in 500..599 -> "服务端暂时不可用($code)"
            else -> "请求失败($code)"
        }
        else -> message ?: "未知错误"
    }
}

/** 服务端错误信封 { error, message }：error 是稳定业务码，message 是给考生看的中文说明 */
private data class ServerError(val code: String, val message: String)

/** 解析错误体；任何一步失败都当作「没有服务端说明」，退回按 HTTP 状态码兜底 */
private fun HttpException.serverError(): ServerError? {
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: return null
    val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val code = obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    return ServerError(code, obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty())
}

/**
 * 业务码 → 考生能照着做的提示。服务端 message 往往已含关键信息
 * （如「考前可用 1200 分钟,无法完成:数学二 强化 缺 900 分钟」），能直接用就用原文；
 * 只有纯技术性说明才换成人话，拿不准的码原样透出，也好过一律降级成「请求失败」。
 */
private fun ServerError.friendly(): String? {
    fun or(fallback: String) = message.ifBlank { fallback }
    return when (code) {
        // 「考前可用 N 分钟,无法完成:科目 里程碑 缺 N 分钟」——考生要知道差多少才好调整
        "PLAN_CAPACITY_INSUFFICIENT" -> or("考前可用时间不足以覆盖已确认的剩余任务")
        // 「生成计划前请确认:xxx」
        "PLANNING_FACTS_INCOMPLETE" -> or("还有关键信息没确认，先在对话里补上")
        "PROFILE_INCOMPLETE" -> or("请先完成备考问卷，再生成计划")
        "PLAN_PROFILE_CHANGED", "PLAN_PROGRESS_CHANGED" -> or("备考档案或进度已变化，请重新生成草稿")
        // reason 偏技术（如「阶段之间有空隙或重叠:基础阶段」），换成能照着做的说法
        "PLAN_GENERATION_FAILED" -> "这次生成的计划不合规，已拦下。可回聊天补充科目范围或剩余量后重试"
        "PLAN_INTERVIEW_FAILED" -> "AI 面谈这次没接上，稍后重试"
        "AI_NOT_CONFIGURED", "AI_VISION_NOT_CONFIGURED" -> or("服务端还没配置好 AI，请稍后再试")
        "ADJUSTMENT_STALE" -> or("计划在生成调整单之后又被改过，这张调整单失效了，重新说一遍即可")
        "ADJUSTMENT_NOT_FOUND" -> or("调整单不存在，可能已经确认或撤销过了")
        "PLAN_NOT_FOUND", "PLAN_ITEM_NOT_FOUND" -> or("相关计划已失效，请重新生成")
        else -> message.takeIf { it.isNotBlank() }
    }
}
