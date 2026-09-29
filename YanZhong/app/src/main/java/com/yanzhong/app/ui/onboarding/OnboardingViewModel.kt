package com.yanzhong.app.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.remote.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 首次问卷的状态机。
 *
 * 五态是刻意分开的,因为它们对应五种完全不同的用户处境:
 * - LOADING    刚登录,还不知道要不要填;此时只显示过场,不要闪一下表单
 * - EDITING    确实缺档案(或刚填一半),用户在答问卷
 * - SUBMITTING 正在提交;此时按钮要锁住,但**不能**清空已填内容
 * - SUCCESS    档案齐了,放行主界面
 * - FALLBACK   服务器连不上/计划没排出来;绝不挡住用户学习,给重试入口即可
 *
 * 一条硬约束:任何失败路径都不许动本地的 468 天计划。本地计划是兜底,
 * 服务端计划是加成——顺序反了,用户在高铁上就会看到一个空白的计划页。
 */
enum class OnboardingPhase { LOADING, EDITING, SUBMITTING, SUCCESS, FALLBACK }

data class OnboardingUiState(
    val phase: OnboardingPhase = OnboardingPhase.LOADING,
    /** 0 = 目标与考期,1 = 时间与科目 */
    val step: Int = 0,
    val targetType: String = TARGET_TYPES.first(),
    val examDate: String = "",
    val dailyMinutes: Int = 180,
    val studyWindows: Set<String> = setOf("上午", "下午", "晚上"),
    val foundation: String = "一般",
    val weakSubjects: Set<String> = emptySet(),
    /** 薄弱科目可选清单:优先用本地已有科目,没有则给一组常用科目 */
    val subjectOptions: List<String> = emptyList(),
    val message: String = "",
    val messageIsError: Boolean = false,
    /** 服务端已落库的档案(回填问卷用) */
    val profile: ProfileDto? = null,
    /** 本次会话是否已经把档案写进服务端:决定重试时是补档案还是只重排计划 */
    val profileSaved: Boolean = false,
    /** 生成好的服务端计划,成功页拿来报个数字 */
    val plan: PlanDto? = null,
    /**
     * 有一条需要用户当场看到的提示(目前只有"计划没排出来")。
     * 只有它挡住主界面;启动时连不上服务器不会挡人——那是 FALLBACK 但不需要用户做任何事。
     */
    val awaitingChoice: Boolean = false
) {
    val busy: Boolean get() = phase == OnboardingPhase.SUBMITTING
    /** 档案已存但计划没出来:只差最后一步 */
    val onlyPlanMissing: Boolean get() = profileSaved && plan == null
}

private val DEFAULT_WEAK_SUBJECTS = listOf("政治", "英语", "数学", "专业课")

/** 每日可投入时长的快捷档位(分钟):给手感,不给用户算数 */
val DAILY_MINUTE_CHOICES = listOf(60, 120, 180, 240, 300, 360, 480)

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as YanZhongApp).repository
    private val _ui = MutableStateFlow(OnboardingUiState(examDate = suggestedExamDate(TARGET_TYPES.first())))
    val state: StateFlow<OnboardingUiState> = _ui

    init {
        viewModelScope.launch {
            // 科目清单跟着本地已有科目走,用户看到的就是自己那几门课,
            // 而不是一份通用的"数学/英语/政治"模板——问卷里出现陌生名词最容易劝退
            val names = runCatching {
                repo.observeSubjects().first().map { it.name.trim() }.filter { it.isNotEmpty() }
            }.getOrDefault(emptyList())
            _ui.update { it.copy(subjectOptions = names.ifEmpty { DEFAULT_WEAK_SUBJECTS }) }
        }
    }

    // ---------------- 状态流转 ----------------

    /**
     * 进门第一步:把上一个账号留下的痕迹抹掉,再去问服务端。
     *
     * 为什么需要这一步:`viewModel()` 是跟着 Activity 走的,同一个实例会跨越
     * "退出登录 → 换个人登录"。上一位用户留下的 SUCCESS 会在新用户进门的第一帧
     * 直接放行主界面——而且那一帧里显示的是上一个人的计划,这既尴尬又不对。
     * 所以进门的瞬间先清零,把判定权交回给服务端。
     */
    fun beginSession() {
        _ui.update {
            it.copy(
                phase = OnboardingPhase.LOADING,
                step = 0,
                profile = null,
                profileSaved = false,
                plan = null,
                awaitingChoice = false,
                message = "",
                messageIsError = false
            )
        }
        load()
    }

    /**
     * 登录后调用:问服务端要档案,决定是放行还是弹问卷。
     * 只在"第一次登录"或"用户手动重试"时调用,不做轮询。
     */
    fun load() = loadInternal(manage = false)

    /**
     * 从「计划」页进来调整备考档案时调用。
     *
     * 与登录门的区别只有一点:档案再完整也要停在问卷里,不能自动放行——
     * 用户是主动来改东西的,直接把他弹回主界面等于没给他改的机会。
     */
    fun startManage() = loadInternal(manage = true)

    private fun loadInternal(manage: Boolean) {
        _ui.update { it.copy(phase = OnboardingPhase.LOADING, message = "", messageIsError = false) }
        viewModelScope.launch {
            runCatching { ApiClient.api().getProfile() }.fold({ resp ->
                val p = resp.profile
                if (p != null && p.isComplete && !manage) {
                    _ui.update { s -> s.copy(phase = OnboardingPhase.SUCCESS, profile = p) }
                } else {
                    // 老账号可能只有半份档案:能回填的全部回填,别让人重填一遍
                    _ui.update { s ->
                        s.copy(
                            phase = OnboardingPhase.EDITING,
                            step = 0,
                            profile = p,
                            profileSaved = p != null,
                            targetType = p?.targetType?.takeIf { it in TARGET_TYPES } ?: s.targetType,
                            examDate = p?.examDate?.takeIf { it.isNotBlank() } ?: s.examDate,
                            dailyMinutes = p?.dailyMinutes?.takeIf { it > 0 } ?: s.dailyMinutes,
                            studyWindows = p?.studyWindows?.toSet()?.takeIf { it.isNotEmpty() } ?: s.studyWindows,
                            foundation = p?.foundation?.takeIf { it in FOUNDATION_LEVELS } ?: s.foundation,
                            weakSubjects = p?.weakSubjects?.toSet() ?: s.weakSubjects
                        )
                    }
                }
            }, { e ->
                // 网络不通不该变成"你必须先填问卷":本地计划照常用,只是没有云端的加成
                _ui.update { s ->
                    s.copy(
                        phase = OnboardingPhase.FALLBACK,
                        message = if (manage) {
                            "暂时连不上服务器(${e.userMessage()}),档案没能调出来。网络好了再试一次就行,你自己的计划没受影响"
                        } else {
                            "暂时连不上服务器(${e.userMessage()})。你本机的计划不受影响,可以照常学,回头在「我的」里重试就行"
                        },
                        messageIsError = false
                    )
                }
            })
        }
    }

    fun next() {
        val s = _ui.value
        val err = validate(s, s.step)
        if (err != null) {
            _ui.update { it.copy(message = err, messageIsError = true) }
            return
        }
        _ui.update { it.copy(step = (s.step + 1).coerceAtMost(1), message = "", messageIsError = false) }
    }

    fun back() {
        _ui.update { it.copy(step = (it.step - 1).coerceAtLeast(0), message = "", messageIsError = false) }
    }

    /** 旧入口只保存档案，计划必须经面谈和草稿确认。 */
    fun submit() = saveProfileOnly()

    /**
     * 只把档案存到服务端,不排计划。
     *
     * 首次问卷和 AI 面谈前的"补档案"走这条路:一份几百天的计划得先和 AI 聊清楚才排得出来,
     * 问卷这一屏只负责把可结构化的那部分(考什么、考期、每天多久)落库,真正的排计划交给面谈。
     */
    fun saveProfileOnly() {
        val s = _ui.value
        if (s.phase == OnboardingPhase.SUBMITTING) return
        val err = validate(s, 1)
        if (err != null) {
            _ui.update { it.copy(message = err, messageIsError = true) }
            return
        }
        _ui.update { it.copy(phase = OnboardingPhase.SUBMITTING, message = "", messageIsError = false) }

        viewModelScope.launch {
            val body = ProfileReq(
                targetType = s.targetType,
                examDate = s.examDate.trim(),
                dailyMinutes = s.dailyMinutes,
                studyWindows = STUDY_WINDOWS.filter { it in s.studyWindows },
                foundation = s.foundation,
                weakSubjects = s.weakSubjects.toList()
            )
            runCatching { ApiClient.api().putProfile(body) }.fold({ saved ->
                // 档案落库即完成:计划由「AI 面谈 → 生成」这条独立的路去产生
                _ui.update {
                    it.copy(
                        phase = OnboardingPhase.SUCCESS,
                        profile = saved.profile,
                        profileSaved = true,
                        message = "",
                        messageIsError = false
                    )
                }
            }, { e ->
                _ui.update { st ->
                    st.copy(
                        phase = OnboardingPhase.EDITING,
                        step = 1,
                        message = "档案没存上:${e.userMessage()}。你填的内容都还在,网络好了再点一次就好",
                        messageIsError = true
                    )
                }
            })
        }
    }

    /** 兼容旧调用方；不允许绕过面谈及草稿确认。 */
    fun regenerate() = saveProfileOnly()

    fun retry() = load()

    /**
     * 用户选择「先不管,照样开始学」。
     * 档案其实已经存到服务端了,缺的只是一份计划;放行进主界面不会让任何事变坏,
     * 用户想补的时候在账户页点一下就行。
     */
    fun dismissFallback() {
        _ui.update { it.copy(phase = OnboardingPhase.SUCCESS, awaitingChoice = false, message = "", messageIsError = false) }
    }

    /** 校验失败时的提示清理(用户开始改输入时可以主动调用) */
    fun clearMessage() {
        if (_ui.value.message.isNotEmpty()) _ui.update { it.copy(message = "", messageIsError = false) }
    }

    // ---------------- 表单编辑 ----------------

    fun setTargetType(value: String) {
        _ui.update { s ->
            val oldSuggestion = suggestedExamDate(s.targetType)
            s.copy(
                targetType = value,
                // 只在日期还是"上一步的自动建议"或空着时才跟着换,已经手填过的日期不动
                examDate = if (s.examDate.isBlank() || s.examDate == oldSuggestion) suggestedExamDate(value) else s.examDate,
                message = "",
                messageIsError = false
            )
        }
    }

    fun setExamDate(value: String) {
        // 只留数字和连字符,顺手把用户从系统日历粘过来的 "2026/12/19" 归一化
        val cleaned = value.replace('/', '-').filter { it.isDigit() || it == '-' }.take(10)
        _ui.update { it.copy(examDate = cleaned, message = "", messageIsError = false) }
    }

    fun setDailyMinutes(minutes: Int) {
        _ui.update { it.copy(dailyMinutes = minutes, message = "", messageIsError = false) }
    }

    fun toggleStudyWindow(window: String) {
        _ui.update { s ->
            val next = if (window in s.studyWindows) s.studyWindows - window else s.studyWindows + window
            s.copy(studyWindows = next, message = "", messageIsError = false)
        }
    }

    fun setFoundation(value: String) {
        _ui.update { it.copy(foundation = value, message = "", messageIsError = false) }
    }

    fun toggleWeakSubject(name: String) {
        _ui.update { s ->
            val next = if (name in s.weakSubjects) s.weakSubjects - name else s.weakSubjects + name
            s.copy(weakSubjects = next, message = "", messageIsError = false)
        }
    }

    /** 清单里没有的科目让用户自己加:他的专业课名字我们猜不到 */
    fun addWeakSubject(name: String) {
        val trimmed = name.trim().take(16)
        if (trimmed.isEmpty()) return
        _ui.update { s ->
            s.copy(
                subjectOptions = (s.subjectOptions + trimmed).distinct(),
                weakSubjects = if (trimmed in s.weakSubjects) s.weakSubjects else s.weakSubjects + trimmed,
                message = "",
                messageIsError = false
            )
        }
    }

    // ---------------- 校验 ----------------

    /**
     * 客户端先按服务端同一套规则挡一遍。
     * 不是为了替服务端把关,而是为了让人在自己填的那一页就看到问题,
     * 而不是点了提交、转了三秒圈、再被告知"日期格式错误"。
     */
    private fun validate(s: OnboardingUiState, step: Int): String? {
        if (s.targetType !in TARGET_TYPES) return "先选一个备考目标吧"
        if (step >= 1) {
            if (s.dailyMinutes !in 15..720) return "每天能拿出的时间请填 15 到 720 分钟之间"
            if (s.studyWindows.isEmpty()) return "至少选一个你真能固定下来的时段,哪怕只有「晚上」"
            if (s.weakSubjects.isEmpty()) return "挑一门最想补的科目,后面对它的排课会多一些"
            if (s.weakSubjects.size > 6) return "薄弱科目最多 6 门,先抓最要紧的几门"
            if (s.foundation !in FOUNDATION_LEVELS) return "选一下你现在的基础情况"
        }
        return validateExamDate(s.examDate)
    }

    private fun validateExamDate(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return "考试日期还没填,按 2026-12-19 这样写就行"
        val date = try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            return "考试日期要写成 2026-12-19 这样的格式(年月日全都要)"
        }
        val today = LocalDate.now()
        if (!date.isAfter(today)) return "考试日期得晚于今天,至少给自己留一天"
        if (date.toEpochDay() - today.toEpochDay() > 365L * 3) return "这个日期有点远,先填三年以内的目标"
        return null
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

/**
 * 考研初试在 12 月下旬,先替用户填一个最可能的日期。
 * 只是个起点:拖着日期选择器改两下,比从空白开始敲 10 个字符轻松得多。
 */
internal fun suggestedExamDate(targetType: String): String {
    if (targetType != "考研") return ""
    val today = LocalDate.now()
    val thisYear = LocalDate.of(today.year, 12, 20)
    return (if (thisYear.isAfter(today)) thisYear else LocalDate.of(today.year + 1, 12, 20)).toString()
}
