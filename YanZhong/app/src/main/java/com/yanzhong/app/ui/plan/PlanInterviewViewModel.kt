package com.yanzhong.app.ui.plan

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.remote.AdjustmentDto
import com.yanzhong.app.data.remote.AdjustReq
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.InterviewMessageDto
import com.yanzhong.app.data.remote.InterviewReq
import com.yanzhong.app.data.remote.PlanBriefDto
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.data.remote.PlanGenerationJobDto
import com.yanzhong.app.data.remote.PlanJobErrorDto
import com.yanzhong.app.data.remote.PlanJobProgressDto
import com.yanzhong.app.data.remote.SyncNotice
import com.yanzhong.app.data.remote.TokenStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val activeCompared: Boolean = false,
    /** 异步生成的进度(0–100);null 表示还没开始或已结束 */
    val progress: Int? = null,
    /** 当前生成阶段的说明(骨架/细化/校验/评审/文档) */
    val progressMessage: String? = null
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
    val mode: InterviewMode = InterviewMode.BUILD,
    /** 已生成但还没确认的草稿：退出后重进直接回到草稿预览，不用再点一次「生成」 */
    val plan: PlanDto? = null,
    /** 正在跑的异步生成任务:退出重进后接着看进度,而不是重新生成一份 */
    val jobId: String? = null
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
    /** 正在跟踪的异步生成任务:用于去重 WS 推送与轮询两条完成路径 */
    private var activeJobId: String? = null
    private var noticeJob: Job? = null

    fun beginSession(profileJustSaved: Boolean = false, forcedMode: InterviewMode? = null) {
        if (started && !profileJustSaved) return
        started = true
        activeJobId = null
        noticeJob?.cancel()
        noticeJob = null
        _ui.value = InterviewUiState()
        viewModelScope.launch {
            accountGuid = runCatching { TokenStore.currentAccountGuid() }.getOrNull()
            // 只有「问卷刚改过」才算从头来:入口要求重新生成不再清档,否则聊到一半退出重进会白白丢掉已聊的内容
            val regenerate = profileJustSaved
            if (regenerate) session.clear()
            val profile = runCatching { ApiClient.api().getProfile() }.getOrNull()?.profile
            if (profile?.isComplete != true) {
                _ui.update { it.copy(phase = InterviewPhase.NEED_PROFILE) }
                return@launch
            }
            // 归属校验放宽:只有两边都拿得到账号且对不上时才拒绝,避免读不到 guid 就静默把上一场对话丢掉
            val resumed = if (regenerate) null else session.load()
                ?.takeIf { it.messages.isNotEmpty()
                    && (it.accountGuid == null || accountGuid == null || it.accountGuid == accountGuid) }
            val activePlan = runCatching { ApiClient.api().getActivePlan().plan }.getOrNull()
            // 模式来源:入口显式指定 > 续上一次的模式 > 有生效计划就走行程小助手,否则从头制定。
            // 入口优先是这次的关键修正——「调整档案并重新生成」进来必须走制定,不能再被猜成行程小助手。
            val mode = forcedMode ?: when {
                regenerate -> InterviewMode.BUILD
                resumed != null -> resumed.mode
                activePlan != null -> InterviewMode.ADJUST
                else -> InterviewMode.BUILD
            }
            _ui.update { it.copy(mode = mode) }
            // 存档模式与本次模式不符时不续:上次是制定对话、这次从「行程小助手」入口进来,续上会驴唇不对马嘴
            val saved = resumed?.takeIf { it.mode == mode }
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
                val draft = saved.plan?.takeIf { it.status == "draft" }
                if (draft != null) {
                    // 上次已经生成过草稿:直接恢复到草稿预览，不用再点一次「生成」
                    _ui.value = InterviewUiState(phase = InterviewPhase.DRAFT, mode = mode,
                        messages = saved.messages, options = saved.options, done = saved.done,
                        brief = saved.brief, plan = draft)
                    // 与 generate() 对齐:草稿预览顶部要显示与生效计划的今日对比
                    runCatching { ApiClient.api().getActivePlan().plan }.onSuccess { active ->
                        _ui.update { it.copy(activePlan = active, activeCompared = true) }
                    }
                    return@launch
                }
                // 上次已发起异步生成但还没回来:接着看进度,而不是让考生再点一次「生成」
                val pendingJob = saved.jobId?.takeIf { it.isNotBlank() }
                if (pendingJob != null) {
                    _ui.value = InterviewUiState(phase = InterviewPhase.CHATTING, mode = mode,
                        messages = saved.messages, options = saved.options, done = saved.done,
                        brief = saved.brief, generating = true, progress = 0,
                        progressMessage = "正在恢复生成进度…")
                    activeJobId = pendingJob
                    watchGeneration(pendingJob)
                    pollGeneration(pendingJob)
                    return@launch
                }
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

    /**
     * 生成草稿:优先走异步任务(立即返回 jobId,WS 推进度、轮询兜底),
     * 避免一次 HTTP 等上两三分钟被网关掐断;旧服务端没有异步路由时回退到同步接口。
     */
    fun generate() {
        if (!_ui.value.canGenerate) return
        noticeJob?.cancel()
        viewModelScope.launch {
            _ui.update { it.copy(generating = true, error = null, activePlan = null,
                activeCompared = false, progress = 0, progressMessage = "正在创建生成任务…") }
            runCatching { ApiClient.api().startPlanGeneration() }.fold({ resp ->
                val jobId = resp.jobId.ifBlank { resp.job.id }
                if (jobId.isBlank()) {
                    failGeneration("草稿生成失败：服务端没有返回生成任务")
                    return@fold
                }
                activeJobId = jobId
                // 先落盘 jobId:请求途中退出页面,回来还能接着看进度
                persist()
                watchGeneration(jobId)
                applyJob(jobId, resp.job)
                pollGeneration(jobId)
            }, { e ->
                if (e is HttpException && (e.code() == 404 || e.code() == 405)) legacyGenerate()
                else failGeneration("草稿生成失败：${e.userMessage()}。可继续纠正后重试")
            })
        }
    }

    /** 旧服务端(无 /plans/generate/jobs):保持升级前的同步生成行为 */
    private suspend fun legacyGenerate() {
        runCatching { ApiClient.aiApi().generatePlan().plan?.takeIf { p -> p.status == "draft" }
            ?: error("服务端没有返回草稿") }.fold({ draft ->
            _ui.update { it.copy(phase = InterviewPhase.DRAFT, plan = draft, generating = false,
                progress = null, progressMessage = null) }
            // 草稿仍留在服务端；本地只记「面谈已完成」，退出后再进来还能一键重新拉草稿
            persist()
            loadActiveComparison()
        }, { e ->
            failGeneration("草稿生成失败：${e.userMessage()}。可继续纠正后重试")
        })
    }

    /** 异步任务的进度同时来自 WS 推送与轮询,这里统一落到 UI */
    private suspend fun applyJob(jobId: String, job: PlanGenerationJobDto) {
        if (activeJobId != jobId) return
        _ui.update {
            it.copy(progress = job.progress.percent,
                progressMessage = job.progress.message.takeIf { msg -> msg.isNotBlank() })
        }
        when (job.status) {
            "done" -> if (claimGeneration(jobId)) loadDraft(job.planId)
            "failed" -> if (claimGeneration(jobId)) {
                val detail = job.error?.message?.takeIf { it.isNotBlank() }
                failGeneration(detail?.let { "草稿生成失败：$it。可继续纠正后重试" } ?: "草稿生成失败，请重试")
            }
        }
    }

    /** 同一次生成只允许一条完成路径(WS 与轮询可能同时到达) */
    private fun claimGeneration(jobId: String): Boolean {
        if (activeJobId != jobId) return false
        activeJobId = null
        noticeJob?.cancel()
        noticeJob = null
        return true
    }

    private suspend fun loadDraft(planId: Long?) {
        val draft = runCatching {
            val id = planId ?: error("生成任务没有返回计划 id")
            ApiClient.api().getPlanById(id).plan?.takeIf { p -> p.status == "draft" }
                ?: error("服务端没有返回草稿")
        }.getOrElse { e ->
            failGeneration("草稿生成失败：${e.userMessage()}。可继续纠正后重试")
            return
        }
        _ui.update {
            it.copy(phase = InterviewPhase.DRAFT, plan = draft, generating = false,
                progress = null, progressMessage = null, error = null)
        }
        persist()
        loadActiveComparison()
    }

    private suspend fun loadActiveComparison() {
        // 对比只是辅助信息；读取失败不阻塞草稿预览或确认。
        runCatching { ApiClient.api().getActivePlan().plan }.onSuccess { active ->
            _ui.update { it.copy(activePlan = active, activeCompared = true) }
        }
    }

    private fun failGeneration(message: String) {
        activeJobId = null
        noticeJob?.cancel()
        noticeJob = null
        _ui.update {
            it.copy(generating = false, progress = null, progressMessage = null, error = message)
        }
        persist()
    }

    /** WS 推送优先:进度变化即时可见;连不上时下面的轮询仍然兜底 */
    private fun watchGeneration(jobId: String) {
        val container = getApplication<Application>() as? YanZhongApp ?: return
        noticeJob = viewModelScope.launch {
            container.statusSync.notices.collect { notice ->
                if (notice !is SyncNotice.PlanGeneration || notice.jobId != jobId) return@collect
                applyJob(jobId, PlanGenerationJobDto(
                    id = notice.jobId,
                    status = notice.status,
                    progress = PlanJobProgressDto(percent = notice.percent, message = notice.message),
                    planId = notice.planId,
                    error = notice.errorMessage?.let { PlanJobErrorDto(message = it) }
                ))
            }
        }
    }

    private suspend fun pollGeneration(jobId: String) {
        var misses = 0
        while (activeJobId == jobId) {
            delay(if (misses == 0) 1500L else 3000L)
            val result = runCatching { ApiClient.api().getPlanGenerationJob(jobId).job }
            val job = result.getOrNull()
            if (job == null) {
                val notFound = (result.exceptionOrNull() as? HttpException)?.code() == 404
                if (notFound || ++misses >= 8) {
                    if (claimGeneration(jobId)) failGeneration("生成任务已过期，请重新生成")
                    return
                }
                continue
            }
            misses = 0
            applyJob(jobId, job)
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
        // 带上最近几轮对话:上一轮刚问「是今天下午吗」,考生答「对」——
        // 没有历史的话服务端只能回「抱歉没接住上下文」再问一遍
        val history = _ui.value.messages
            .dropLast(1) // 最后一条就是本次要发的 message 本身
            .filter { it.content.isNotBlank() }
            .takeLast(8)
        runCatching { ApiClient.aiApi().adjustPlan(AdjustReq(message = message, history = history)) }.fold({ resp ->
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
            if (resp.needProfile) {
                // 服务端判定档案未就绪(F4):切回补档案阶段,渲染「填写备考档案」按钮。
                // 不再让用户对着一句纯文本打转——那是报告里点名的功能性死循环。
                _ui.update { it.copy(phase = InterviewPhase.NEED_PROFILE, sending = false) }
                return
            }
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
            options = s.options, done = s.done, brief = s.brief, mode = s.mode, plan = s.plan,
            jobId = activeJobId))
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
        // 服务端 message 已带具体被拦原因(如「计划包含未确认的考试科目:常识判断」),
        // 原样透出比一句笼统的「不合规」有用 —— 用户才知道回聊天里该补什么
        "PLAN_GENERATION_FAILED" -> or("这次生成的计划不合规,已拦下。可回聊天补充科目范围或剩余量后重试")
        "PLAN_INTERVIEW_FAILED" -> "AI 面谈这次没接上，稍后重试"
        "AI_NOT_CONFIGURED", "AI_VISION_NOT_CONFIGURED" -> or("服务端还没配置好 AI，请稍后再试")
        "ADJUSTMENT_STALE" -> or("计划在生成调整单之后又被改过，这张调整单失效了，重新说一遍即可")
        "ADJUSTMENT_NOT_FOUND" -> or("调整单不存在，可能已经确认或撤销过了")
        "PLAN_NOT_FOUND", "PLAN_ITEM_NOT_FOUND" -> or("相关计划已失效，请重新生成")
        else -> message.takeIf { it.isNotBlank() }
    }
}
