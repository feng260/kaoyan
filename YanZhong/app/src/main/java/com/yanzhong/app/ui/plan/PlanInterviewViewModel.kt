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

/**
 * 「AI 备考面谈」的状态机。
 *
 * 为什么要有这一页:一份 400+ 天的全程计划,靠两页问卷是排不出来的——
 * 目标院校、跨考与否、在职还是全职、每科真实水平、手上有什么资料、最近一次自测多少分,
 * 这些只有聊出来。所以计划不再是"填完表就掉下来",而是"先和一个规划师聊几轮,他再落笔"。
 *
 * 四态:
 * - LOADING      进页面就读档案,决定是能直接开聊还是得先补档案
 * - NEED_PROFILE 服务端上没有一份完整的备考档案;面谈产出的简报没有地方落库,
 *                所以先请用户去填一次档案再回来(回来后本页会自动重新开始)
 * - CHATTING     正在聊;每答一轮服务端推进一轮,直到 AI 说信息够了
 * - SUCCESS      计划已生成
 */
enum class InterviewPhase { LOADING, NEED_PROFILE, CHATTING, SUCCESS }

data class InterviewUiState(
    val phase: InterviewPhase = InterviewPhase.LOADING,
    /** 从早到晚的完整对话(含 AI 的开场白),每次请求都整段上传——服务端接口是无状态的 */
    val messages: List<InterviewMessageDto> = emptyList(),
    /** AI 给的快捷回答:一键点选,省得用户在手机上敲字 */
    val options: List<String> = emptyList(),
    /** 正在等 AI 回话(一轮面谈) */
    val sending: Boolean = false,
    /** 正在生成计划(含长文档,可能要一两分钟) */
    val generating: Boolean = false,
    /** AI 认为信息够了,可以生成计划了 */
    val done: Boolean = false,
    /** done 时的考生画像简报,生成前先让用户过一眼"AI 理解成了什么" */
    val brief: PlanBriefDto? = null,
    val error: String? = null,
    val plan: PlanDto? = null
) {
    val busy: Boolean get() = sending || generating
    /** 已经答过至少一轮:决定要不要给出「不想聊了,直接生成」的出口 */
    val hasUserTurn: Boolean get() = messages.any { it.role == "user" }
    /** 简报里一个字都没有:说明这轮收尾没拿到有效信息,UI 上别把它当成"AI 已经了解你" */
    val briefIsEmpty: Boolean
        get() = brief?.let { b ->
            b.summary.isBlank() && b.goals.isEmpty() && b.constraints.isEmpty() &&
                b.focus.isEmpty() && b.materials.isEmpty() && b.notes.isEmpty()
        } ?: true
}

class PlanInterviewViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(InterviewUiState())
    val state: StateFlow<InterviewUiState> = _ui

    /** 本次会话是否已经开过场:防止旋转/重组把对话清空 */
    private var started = false

    /**
     * 进页面调一次。
     *
     * [profileJustSaved] 为 true 表示用户刚从「备考档案」页回来,此时必须重跑一遍——
     * 否则会卡在 NEED_PROFILE 上(之前那次读档案时档案还不存在)。
     */
    fun beginSession(profileJustSaved: Boolean = false) {
        if (started && !profileJustSaved) return
        started = true
        _ui.value = InterviewUiState()

        viewModelScope.launch {
            val profile = runCatching { ApiClient.api().getProfile() }.getOrNull()?.profile
            if (profile?.isComplete != true) {
                // 没有档案行时简报无处落库、生成计划也会被判"档案不完整",所以先补档案
                _ui.update { it.copy(phase = InterviewPhase.NEED_PROFILE) }
                return@launch
            }
            _ui.update { it.copy(phase = InterviewPhase.CHATTING) }
            // 空对话上传 = 请 AI 发开场白并问第一个问题
            advance(force = false, generateAfter = false)
        }
    }

    /** 用户发了一句话(也用于点快捷选项) */
    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _ui.value.busy) return
        _ui.update {
            it.copy(
                messages = it.messages + InterviewMessageDto(role = "user", content = content),
                options = emptyList(),
                error = null
            )
        }
        viewModelScope.launch { advance(force = false, generateAfter = false) }
    }

    /**
     * 「不想再聊了,直接开始」:让 AI 用手上已有的信息强制收尾,拿到简报后立刻排计划。
     * force 是服务端认的语义——即使轮次不够也会收尾,所以这条路一定出得来东西。
     */
    fun finishNow() {
        if (_ui.value.busy) return
        viewModelScope.launch { advance(force = true, generateAfter = true) }
    }

    /** AI 说了 done 之后,用户点「生成我的全程计划」 */
    fun generate() {
        if (_ui.value.busy) return
        viewModelScope.launch { generateInternal() }
    }

    /** 出错后的统一重试:该聊天就补聊一轮,该生成就重新生成 */
    fun retry() {
        if (_ui.value.busy) return
        viewModelScope.launch {
            if (_ui.value.done) generateInternal() else advance(force = false, generateAfter = false)
        }
    }

    fun dismissError() {
        if (_ui.value.error != null) _ui.update { it.copy(error = null) }
    }

    /**
     * 推进一轮面谈。
     *
     * 全程走 aiApi():模型想一句话要几秒到几十秒,默认 20s 的 callTimeout 会把请求掐死。
     */
    private suspend fun advance(force: Boolean, generateAfter: Boolean) {
        _ui.update { it.copy(sending = !generateAfter, generating = generateAfter, error = null) }
        val snapshot = _ui.value.messages

        runCatching { ApiClient.aiApi().interview(InterviewReq(messages = snapshot, force = force)) }
            .fold({ resp ->
                _ui.update {
                    it.copy(
                        messages = snapshot + InterviewMessageDto(role = "assistant", content = resp.reply),
                        options = resp.options,
                        done = resp.done,
                        brief = resp.brief ?: it.brief,
                        sending = false,
                        error = null
                    )
                }
                if (generateAfter) generateInternal() else _ui.update { it.copy(generating = false) }
            }, { e ->
                _ui.update {
                    it.copy(
                        sending = false,
                        generating = false,
                        error = "AI 这次没回上话(${e.userMessage()})。你的回答都还在,点「重试」就行"
                    )
                }
            })
    }

    private suspend fun generateInternal() {
        _ui.update { it.copy(generating = true, sending = false, error = null) }
        runCatching { ApiClient.aiApi().generatePlan() }.fold({ resp ->
            _ui.update {
                it.copy(
                    phase = InterviewPhase.SUCCESS,
                    plan = resp.plan,
                    generating = false,
                    sending = false,
                    error = null
                )
            }
        }, { e ->
            _ui.update {
                it.copy(
                    generating = false,
                    sending = false,
                    error = "计划没排出来(${e.userMessage()})。面谈记录还在,点「重试」我再跑一遍"
                )
            }
        })
    }

    private fun Throwable.userMessage(): String {
        val retrofitHttp = this as? retrofit2.HttpException
        return when {
            retrofitHttp != null -> {
                val body = retrofitHttp.response()?.errorBody()?.string().orEmpty().take(80)
                "HTTP ${retrofitHttp.code()} $body"
            }
            else -> message ?: "未知错误"
        }
    }
}
