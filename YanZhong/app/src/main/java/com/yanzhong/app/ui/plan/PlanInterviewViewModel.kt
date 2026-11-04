package com.yanzhong.app.ui.plan

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.remote.AdjustmentDto
import com.yanzhong.app.data.remote.AdjustReq
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.BriefSubjectPatch
import com.yanzhong.app.data.remote.ClarifyOptionDto
import com.yanzhong.app.data.remote.GeneratePlanReq
import com.yanzhong.app.data.remote.InterviewMessageDto
import com.yanzhong.app.data.remote.InterviewReq
import com.yanzhong.app.data.remote.PatchBriefReq
import com.yanzhong.app.data.remote.PlanBriefDto
import com.yanzhong.app.data.remote.PlanBriefResp
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.data.remote.PlanGenerationJobDto
import com.yanzhong.app.data.remote.PlanJobErrorDto
import com.yanzhong.app.data.remote.PlanJobProgressDto
import com.yanzhong.app.data.remote.PlanOverviewDto
import com.yanzhong.app.data.remote.SelectOptionReq
import com.yanzhong.app.data.remote.SyncNotice
import com.yanzhong.app.data.remote.TokenStore
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** 草稿只在本页面预览；确认成功前不向计划页发送刷新事件。 */
enum class InterviewPhase { LOADING, NEED_PROFILE, CHATTING, DRAFT, SUCCESS }

/** 对话模式:BUILD=制定/重新生成计划;ADJUST=已有生效计划,对话即行程小助手 */
enum class InterviewMode { BUILD, ADJUST }

/** 容量不足时的三个出路:改作息 / 降目标 / 先出尽力版 */
enum class CapacityChoice { ADJUST_SCHEDULE, LOWER_TARGET, BEST_EFFORT }

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
    val progressMessage: String? = null,
    /** 生成前口径:GET /plans/brief 的完整响应 */
    val briefResp: PlanBriefResp? = null,
    /** 是否正在读取/保存口径 */
    val briefLoading: Boolean = false,
    /** 口径确认面板是否可见 */
    val showBriefSheet: Boolean = false,
    /** 面板关闭后是否要继续生成(点击主按钮时置 true) */
    val pendingGenerate: Boolean = false,
    /** 容量缺口的结构化数据;非空时展示 CapacityOptionsDialog */
    val capacityIssue: PlanCapacityIssue? = null,
    val showCapacityDialog: Boolean = false,
    /** 生成耗时的展示区间(分钟) */
    val etaMinutes: IntRange? = null,
    val generationStartedAt: Long? = null,
    val elapsedSeconds: Int = 0,
    /** 追问时的可点选答案 */
    val clarifyOptions: List<ClarifyOptionDto> = emptyList(),
    /** 容量选择「调整作息」时请求页面跳转到档案页 */
    val openSetupRequested: Boolean = false,
    /** 锁定任务冲突的服务端说明;非空时弹窗提示先解锁 */
    val lockedConflict: String? = null,
    /** 正在切换多方案 */
    val selectingOption: Boolean = false,
    /** 草稿预算 overview:读取失败不阻塞草稿预览 */
    val draftOverview: PlanOverviewDto? = null
) {
    val busy: Boolean get() = sending || generating || confirming || briefLoading || selectingOption
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
    val jobId: String? = null,
    /** 生成开始时刻与 ETA 区间:恢复进度后继续显示真实耗时 */
    val generationStartedAt: Long? = null,
    val etaFrom: Int = 0,
    val etaTo: Int = 0
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

    /** 上次用户确认过的口径哈希:与 GET /plans/brief 返回值不同才重新弹确认面板 */
    fun confirmedBriefHash(): String? = prefs.getString(KEY_BRIEF_HASH, null)

    fun saveConfirmedBriefHash(hash: String) {
        prefs.edit().putString(KEY_BRIEF_HASH, hash).apply()
    }

    private companion object {
        const val KEY_SESSION = "session"
        const val KEY_BRIEF_HASH = "confirmed_brief_hash"
    }
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
    private var elapsedJob: Job? = null
    private var prefillSent = false

    fun beginSession(
        profileJustSaved: Boolean = false,
        forcedMode: InterviewMode? = null,
        /** 从体检卡/教练卡带过来的调整描述:进入调整模式后自动发送一次 */
        prefill: String = ""
    ) {
        if (started && !profileJustSaved) return
        started = true
        prefillSent = false
        activeJobId = null
        noticeJob?.cancel()
        noticeJob = null
        stopElapsedTicker()
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
            // 服务端已有未确认草稿(典型:生成完成时 App 被杀/服务重启,任务进度丢失):
            // 直接进草稿预览,让考生确认或继续纠正,而不是把已生成的计划晾在库里重新面谈一遍。
            val recoveredDraft = if (mode == InterviewMode.BUILD && !regenerate
                && saved?.plan?.status != "draft" && saved?.jobId.isNullOrBlank()) {
                runCatching { ApiClient.api().getLatestDraft().plan }
                    .getOrNull()?.takeIf { it.status == "draft" }
            } else {
                null
            }
            if (recoveredDraft != null) {
                _ui.value = InterviewUiState(phase = InterviewPhase.DRAFT, mode = mode,
                    messages = saved?.messages ?: emptyList(),
                    options = saved?.options ?: emptyList(),
                    done = saved?.done ?: true, brief = saved?.brief, plan = recoveredDraft)
                runCatching { ApiClient.api().getActivePlan().plan }.onSuccess { active ->
                    _ui.update { it.copy(activePlan = active, activeCompared = true) }
                }
                loadDraftOverview(recoveredDraft.id)
                return@launch
            }
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
                maybeSendPrefill(prefill)
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
                    loadDraftOverview(draft.id)
                    return@launch
                }
                // 上次已发起异步生成但还没回来:接着看进度,而不是让考生再点一次「生成」
                val pendingJob = saved.jobId?.takeIf { it.isNotBlank() }
                if (pendingJob != null) {
                    val restoredEta = if (saved.etaFrom in 8..30 && saved.etaTo in saved.etaFrom..30) {
                        saved.etaFrom..saved.etaTo
                    } else {
                        runCatching { ApiClient.api().getPlanBrief() }.getOrNull()
                            ?.let { horizonDaysFrom(it.profile.examDate)?.let(::etaRange) }
                    }
                    _ui.value = InterviewUiState(phase = InterviewPhase.CHATTING, mode = mode,
                        messages = saved.messages, options = saved.options, done = saved.done,
                        brief = saved.brief, generating = true, progress = 0,
                        progressMessage = "正在恢复生成进度…",
                        etaMinutes = restoredEta,
                        generationStartedAt = saved.generationStartedAt ?: System.currentTimeMillis())
                    startElapsedTicker()
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
                options = emptyList(), clarifyOptions = emptyList(), done = false, error = null)
        }
        // 先落盘再发请求：请求途中被杀，考生刚打的内容也不会白费
        persist()
        viewModelScope.launch {
            if (_ui.value.mode == InterviewMode.ADJUST) adjust(content) else advance()
        }
    }

    /**
     * 生成草稿前先确认口径:GET /plans/brief,与本机记录的上次确认哈希比对。
     * 哈希不同(或首次生成)就弹 PlanBriefSheet,用户确认后再带着 hash 发起生成,
     * 服务端据此证明「生成用的口径就是用户刚看过的这一份」。
     */
    fun requestGeneration() {
        if (!_ui.value.canGenerate) return
        noticeJob?.cancel()
        viewModelScope.launch {
            _ui.update { it.copy(briefLoading = true, error = null) }
            val resp = runCatching { ApiClient.api().getPlanBrief() }.getOrNull()
            _ui.update { it.copy(briefLoading = false) }
            if (resp == null || resp.briefHash.isBlank()) {
                // 旧服务端没有 /plans/brief:退回直接生成,不让新流程卡住老环境
                startGeneration(null, false)
                return@launch
            }
            if (session.confirmedBriefHash() != resp.briefHash) {
                _ui.update {
                    it.copy(briefResp = resp, showBriefSheet = true,
                        pendingGenerate = true, error = null)
                }
            } else {
                startGeneration(resp.briefHash, false)
            }
        }
    }

    /** 口径确认面板:保存改过的字段(没有改动就不发请求),再用最新哈希生成 */
    fun confirmBrief(patches: List<BriefSubjectPatch>) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(briefLoading = true, error = null) }
            val current = _ui.value.briefResp
            val saved = if (patches.isEmpty()) current else runCatching {
                ApiClient.api().patchPlanBrief(PatchBriefReq(examSubjects = patches))
            }.getOrElse { e ->
                _ui.update {
                    it.copy(briefLoading = false,
                        error = "口径保存失败：${e.userMessage()}")
                }
                return@launch
            }
            val hash = saved?.briefHash?.takeIf { it.isNotBlank() }
                ?: current?.briefHash?.takeIf { it.isNotBlank() }
            if (hash != null) session.saveConfirmedBriefHash(hash)
            _ui.update {
                it.copy(briefResp = saved ?: it.briefResp, briefLoading = false,
                    showBriefSheet = false, pendingGenerate = false)
            }
            startGeneration(hash, false)
        }
    }

    fun dismissBriefSheet() {
        if (_ui.value.briefLoading) return
        _ui.update { it.copy(showBriefSheet = false, pendingGenerate = false) }
    }

    /** 容量缺口三选一:调整作息 / 降低目标 / 生成尽力版 */
    fun chooseCapacity(choice: CapacityChoice) {
        val hash = _ui.value.briefResp?.briefHash
        when (choice) {
            CapacityChoice.ADJUST_SCHEDULE -> _ui.update {
                it.copy(showCapacityDialog = false, capacityIssue = null, openSetupRequested = true)
            }
            CapacityChoice.LOWER_TARGET -> _ui.update {
                it.copy(showCapacityDialog = false, showBriefSheet = true, pendingGenerate = true)
            }
            CapacityChoice.BEST_EFFORT -> {
                if (hash.isNullOrBlank()) {
                    // 没有口径哈希就不能走尽力版:先让用户确认口径,服务端也只接受「hash + allowShortfall」组合
                    _ui.update {
                        it.copy(showCapacityDialog = false, showBriefSheet = true, pendingGenerate = true)
                    }
                    return
                }
                viewModelScope.launch { startGeneration(hash, true) }
            }
        }
    }

    fun dismissCapacityDialog() {
        _ui.update { it.copy(showCapacityDialog = false, capacityIssue = null) }
    }

    fun consumeOpenSetup() {
        _ui.update { it.copy(openSetupRequested = false) }
    }

    fun dismissLockedConflict() {
        _ui.update { it.copy(lockedConflict = null) }
    }

    /** 从体检卡/教练卡带进来的话术只自动发一次：续档重进不应重复发 */
    private fun maybeSendPrefill(prefill: String) {
        val text = prefill.trim()
        if (text.isEmpty() || prefillSent) return
        // 存档停在「刚发完、还没回」时已经补发过一次,别让带进来的话术再叠一轮
        if (_ui.value.messages.lastOrNull()?.role == "user") return
        prefillSent = true
        viewModelScope.launch {
            delay(300)
            if (_ui.value.phase == InterviewPhase.CHATTING && !_ui.value.busy) send(text)
        }
    }

    private fun startElapsedTicker() {
        stopElapsedTicker()
        elapsedJob = viewModelScope.launch {
            while (true) {
                val startedAt = _ui.value.generationStartedAt ?: return@launch
                val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                _ui.update { it.copy(elapsedSeconds = seconds.coerceAtLeast(0)) }
                delay(1000)
            }
        }
    }

    private fun stopElapsedTicker() {
        elapsedJob?.cancel()
        elapsedJob = null
    }

    /** 生成没有真正开始(口径冲突/容量不足):清掉进度与计时,回到可再次发起的状态 */
    private fun resetGenerationProgress() {
        stopElapsedTicker()
        _ui.update {
            it.copy(generating = false, progress = null, progressMessage = null,
                etaMinutes = null, generationStartedAt = null, elapsedSeconds = 0)
        }
    }

    /**
     * 发起异步生成:立即返回 jobId,WS 推进度、轮询兜底;旧服务端没有异步路由时回退同步接口。
     * [briefHash] 为空表示老环境/老档案,服务端不做口径校验。
     */
    private suspend fun startGeneration(briefHash: String?, allowShortfall: Boolean) {
        _ui.update {
            it.copy(generating = true, error = null, activePlan = null, activeCompared = false,
                draftOverview = null, progress = 0, progressMessage = "正在创建生成任务…",
                showCapacityDialog = false, capacityIssue = null, elapsedSeconds = 0)
        }
        val body = GeneratePlanReq(
            briefHash = briefHash,
            allowShortfall = allowShortfall.takeIf { it }
        )
        runCatching { ApiClient.api().startPlanGeneration(body) }.fold({ resp ->
            val jobId = resp.jobId.ifBlank { resp.job.id }
            if (jobId.isBlank()) {
                failGeneration("草稿生成失败：服务端没有返回生成任务")
                return@fold
            }
            activeJobId = jobId
            _ui.update {
                it.copy(
                    etaMinutes = horizonDaysFrom(it.briefResp?.profile?.examDate)?.let(::etaRange),
                    generationStartedAt = System.currentTimeMillis()
                )
            }
            startElapsedTicker()
            // 先落盘 jobId:请求途中退出页面,回来还能接着看进度
            persist()
            watchGeneration(jobId)
            applyJob(jobId, resp.job)
            pollGeneration(jobId)
        }, { e ->
            val error = e.planServerError()
            when {
                e is HttpException && (e.code() == 404 || e.code() == 405) ->
                    legacyGenerate(briefHash, allowShortfall)
                error?.code == "PLAN_BRIEF_CHANGED" -> {
                    // 口径在确认之后又被改过:重新拉一份让用户再看一眼,而不是把 409 直接甩出去
                    resetGenerationProgress()
                    reloadBriefForConfirm()
                }
                error?.code == "PLAN_CAPACITY_INSUFFICIENT" -> {
                    resetGenerationProgress()
                    _ui.update {
                        it.copy(
                            capacityIssue = error.capacityIssue()
                                ?: PlanCapacityIssue(availableMinutes = 0, requiredMinutes = 0),
                            showCapacityDialog = true
                        )
                    }
                }
                else -> failGeneration("草稿生成失败：${e.userMessage()}。可继续纠正后重试")
            }
        })
    }

    /** 旧服务端(无 /plans/generate/jobs):保持升级前的同步生成行为 */
    private suspend fun legacyGenerate(briefHash: String?, allowShortfall: Boolean) {
        val body = GeneratePlanReq(
            briefHash = briefHash,
            allowShortfall = allowShortfall.takeIf { it }
        )
        runCatching { ApiClient.aiApi().generatePlan(body).plan?.takeIf { p -> p.status == "draft" }
            ?: error("服务端没有返回草稿") }.fold({ draft ->
            stopElapsedTicker()
            _ui.update { it.copy(phase = InterviewPhase.DRAFT, plan = draft, generating = false,
                progress = null, progressMessage = null, etaMinutes = null,
                generationStartedAt = null, elapsedSeconds = 0, error = null) }
            // 草稿仍留在服务端；本地只记「面谈已完成」，退出后再进来还能一键重新拉草稿
            persist()
            loadActiveComparison()
            loadDraftOverview(draft.id)
        }, { e ->
            failGeneration("草稿生成失败：${e.userMessage()}。可继续纠正后重试")
        })
    }

    /** 重新拉口径并打开确认面板(409 PLAN_BRIEF_CHANGED 后) */
    private suspend fun reloadBriefForConfirm() {
        runCatching { ApiClient.api().getPlanBrief() }.getOrNull()?.let { resp ->
            _ui.update {
                it.copy(briefResp = resp, showBriefSheet = true, pendingGenerate = true, error = null)
            }
        }
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
        applyDraft(draft)
    }

    private suspend fun applyDraft(draft: PlanDto) {
        stopElapsedTicker()
        _ui.update {
            it.copy(phase = InterviewPhase.DRAFT, plan = draft, generating = false,
                progress = null, progressMessage = null, etaMinutes = null,
                generationStartedAt = null, elapsedSeconds = 0, error = null)
        }
        persist()
        loadActiveComparison()
        loadDraftOverview(draft.id)
    }

    /**
     * 生成任务丢失(服务端重启/超过 30 分钟 TTL)时,草稿通常已经落库;
     * 回捞最近一份未确认草稿,别让考生白等一次生成。
     */
    private suspend fun recoverDraftAfterJobLoss(): Boolean {
        val draft = runCatching { ApiClient.api().getLatestDraft().plan }
            .getOrNull()?.takeIf { it.status == "draft" } ?: return false
        applyDraft(draft)
        return true
    }

    private suspend fun loadActiveComparison() {
        // 对比只是辅助信息；读取失败不阻塞草稿预览或确认。
        runCatching { ApiClient.api().getActivePlan().plan }.onSuccess { active ->
            _ui.update { it.copy(activePlan = active, activeCompared = true) }
        }
    }

    /** 预算 overview 与草稿预览并行读取;失败只是少了预算表,不影响确认 */
    private fun loadDraftOverview(planId: Long) {
        viewModelScope.launch {
            val overview = runCatching { ApiClient.api().getPlanOverview(planId) }.getOrNull()
                ?: return@launch
            if (_ui.value.plan?.id == planId) _ui.update { it.copy(draftOverview = overview) }
        }
    }

    private fun failGeneration(message: String) {
        activeJobId = null
        noticeJob?.cancel()
        noticeJob = null
        stopElapsedTicker()
        _ui.update {
            it.copy(generating = false, progress = null, progressMessage = null,
                etaMinutes = null, generationStartedAt = null, elapsedSeconds = 0, error = message)
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
                    if (claimGeneration(jobId) && !recoverDraftAfterJobLoss()) {
                        failGeneration("生成任务已过期，请重新生成")
                    }
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
            activeCompared = false, draftOverview = null, done = true, options = emptyList(), error = null) }
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
                    adjustment = resp.adjustment ?: it.adjustment, sending = false,
                    clarifyOptions = resp.clarifyOptions.orEmpty(), lockedConflict = null)
            }
            persist()
        }, { e ->
            val serverError = e.planServerError()
            if (serverError?.code == "LOCKED_ITEMS_CONFLICT") {
                // 锁定冲突不是「没算好」:列出冲突日,让考生先去解锁再试
                _ui.update {
                    it.copy(sending = false,
                        lockedConflict = serverError.message.ifBlank { "锁定任务和这次调整冲突，需要先解锁" })
                }
            } else {
                _ui.update { it.copy(sending = false, error = "这次没算好(${e.userMessage()})，点重试继续或换个说法") }
            }
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
                    val serverError = e.planServerError()
                    if (serverError?.code == "LOCKED_ITEMS_CONFLICT") {
                        _ui.update {
                            it.copy(confirming = false,
                                lockedConflict = serverError.message.ifBlank { "锁定任务和这次调整冲突，需要先解锁" })
                        }
                    } else {
                        _ui.update { it.copy(confirming = false, error = "确认失败(${e.userMessage()})，请重试或重新描述") }
                    }
                })
        }
    }

    /** 多方案切换:服务端把 adjustment.changes 换成所选方案,确认前仍然零副作用 */
    fun selectOption(optionId: String) {
        val adjustment = _ui.value.adjustment?.takeIf {
            it.status == "draft" && !it.options.isNullOrEmpty() && it.selectedOption != optionId
        } ?: return
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(selectingOption = true, error = null) }
            runCatching {
                ApiClient.api().selectAdjustmentOption(adjustment.id, SelectOptionReq(optionId)).adjustment
            }.fold({ updated ->
                _ui.update { it.copy(selectingOption = false, adjustment = updated ?: it.adjustment) }
                persist()
            }, { e ->
                _ui.update { it.copy(selectingOption = false, error = "方案切换失败：${e.userMessage()}") }
            })
        }
    }

    /** 「重说一次」:只收起卡片;服务端的 draft 会被下一次 adjust 原地覆盖 */
    fun dismissAdjustmentCard() { _ui.update { it.copy(adjustment = null) } }

    fun retry() {
        if (_ui.value.busy) return
        when {
            _ui.value.phase == InterviewPhase.DRAFT -> confirm()
            _ui.value.canGenerate -> requestGeneration()
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
            jobId = activeJobId, generationStartedAt = s.generationStartedAt,
            etaFrom = s.etaMinutes?.first ?: 0, etaTo = s.etaMinutes?.last ?: 0))
    }

    /** 把异常翻译成人话；不要把服务端原始 body / HTTP 400 直接甩给考生 */
    private fun Throwable.userMessage(): String {
        planServerError()?.friendlyPlanError()?.takeIf { it.isNotBlank() }?.let { return it }
        return when (this) {
            is HttpException -> when (val code = code()) {
                400, 422 -> "这次请求没被接受，多半是输入太长，精简一下再试"
                401, 403 -> "登录已过期，请重新登录"
                503 -> "服务端还没配置好 AI"
                in 500..599 -> "服务端暂时不可用($code)"
                else -> "请求失败($code)"
            }
            else -> message ?: "未知错误"
        }
    }
}

/** 考试日期 → 计划跨度(天);缺失或格式错误返回 null,ETA 就不显示区间 */
internal fun horizonDaysFrom(examDate: String?, today: LocalDate = LocalDate.now()): Int? {
    val exam = examDate?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    return ChronoUnit.DAYS.between(today, exam).toInt().takeIf { it > 0 }
}
