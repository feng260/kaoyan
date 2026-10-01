package com.yanzhong.app.ui.onboarding

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.remote.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
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
    /** 0 = 目标与考期,1 = 节奏与短板,2 = 正式科目与真实空闲(含课表上传) */
    val step: Int = 0,
    val targetType: String = TARGET_TYPES.first(),
    val examDate: String = "",
    val studyWindows: Set<String> = setOf("上午", "下午", "晚上"),
    val foundation: String = "一般",
    val weakSubjects: Set<String> = emptySet(),
    /** 薄弱科目可选清单:优先用本地已有科目,没有则给一组常用科目 */
    val subjectOptions: List<String> = emptyList(),
    /** 正式考试科目名;逐科的进度与里程碑留给面谈 */
    val examSubjects: Set<String> = emptySet(),
    /** 按星期记的真实空闲:weekday(1=周一) → 时段名(STUDY_WINDOWS 之一) */
    val availability: Map<Int, Set<String>> = emptyMap(),
    /** 固定占用(上课/上班/通勤),可由课表图片识别得到,也可手动补 */
    val commitments: List<BriefCommitmentDto> = emptyList(),
    /** 课表识别的进行中/结果提示,和表单校验提示分开,免得互相覆盖 */
    val timetableBusy: Boolean = false,
    val timetableNotice: String = "",
    val timetableNoticeIsError: Boolean = false,
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

/** 考研兜底模板 */
private val DEFAULT_WEAK_SUBJECTS = listOf("政治", "英语", "数学", "专业课")

/**
 * 按 [TARGET_TYPES] 分型的科目预设(F3):选法考不该看到"数学"。
 * 用户仍可自由增删,本地已有科目会追加在预设之后供选择。
 */
private val SUBJECT_PRESETS: Map<String, List<String>> = mapOf(
    // 与旧版首启种子、内置 468 计划包的科目名完全一致(「专业课 408」带空格),
    // 这样考研用户保存问卷后,本地科目名能精确匹配,内置参考任务才能挂上
    "考研" to listOf("政治", "英语", "数学", "专业课 408"),
    "法考" to listOf("民法", "刑法", "行政法", "理论法", "商经法", "三国法", "刑诉", "民诉"),
    "考公" to listOf("行测", "申论"),
    "专升本" to listOf("英语", "政治", "大学语文", "高等数学"),
    "其他" to DEFAULT_WEAK_SUBJECTS,
)

/**
 * 科目候选清单:**题型预设在前**,本地已有科目追加在后。
 *
 * 不能"本地优先、为空才用预设"——本地科目在老安装里是首启硬编码种入的考研四科,
 * 本地永远不空,预设就永远轮不到,选了考公看到的还是 408(用户实测踩中)。
 * 预设先行保证选项跟着备考类型走;本地科目去重后追加,用户自建的不丢。
 */
private fun subjectOptionsFor(targetType: String, localSubjects: List<String>): List<String> =
    ((SUBJECT_PRESETS[targetType] ?: DEFAULT_WEAK_SUBJECTS) + localSubjects).distinct()

/** 问卷总步数:目标与考期 → 节奏与短板 → 正式科目与真实空闲 */
const val ONBOARDING_STEPS = 3

/** 周一~周日,给格子当行头 */
internal val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 时段名 → 钟点。
 * 问卷里让人点「晚上」比让人填 19:00-22:00 省事得多,但排容量必须落到钟点上,
 * 所以这里做一次翻译:点选可以是粗的,落库必须是 HH:mm。
 */
internal val WINDOW_CLOCK: Map<String, Pair<String, String>> = linkedMapOf(
    "早晨" to ("06:30" to "08:00"),
    "上午" to ("08:30" to "11:30"),
    "下午" to ("14:00" to "17:30"),
    "晚上" to ("19:00" to "22:00"),
    "深夜" to ("22:30" to "23:59")
)

/** 课表截图最长边压到 1600 像素:再大也只是让上传变慢,小字照样认得出 */
private const val TIMETABLE_MAX_SIDE = 1600

/** 固定占用上限,和服务端 fixedCommitmentsSchema 保持一致 */
private const val MAX_COMMITMENTS = 20

/** 正式科目上限,和服务端 examSubjectsSchema 保持一致 */
private const val MAX_EXAM_SUBJECTS = 12

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val yanzhongApp = app as YanZhongApp
    private val repo = yanzhongApp.repository
    private val _ui = MutableStateFlow(OnboardingUiState(examDate = suggestedExamDate(TARGET_TYPES.first())))
    val state: StateFlow<OnboardingUiState> = _ui

    /** 本地已有科目名:追加在题型预设之后供选择(预设在前,见 subjectOptionsFor) */
    private var localSubjectNames: List<String> = emptyList()

    init {
        viewModelScope.launch {
            // 本地科目只是候选的"补充项"而不是主体:选项主体永远跟着备考类型的预设走,
            // 本地的(可能是历史预置的考研四科)去重后追加在后
            val names = runCatching {
                repo.observeSubjects().first().map { it.name.trim() }.filter { it.isNotEmpty() }
            }.getOrDefault(emptyList())
            localSubjectNames = names
            _ui.update {
                it.copy(subjectOptions = subjectOptionsFor(it.targetType, names))
            }
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
                examSubjects = emptySet(),
                availability = emptyMap(),
                commitments = emptyList(),
                timetableBusy = false,
                timetableNotice = "",
                timetableNoticeIsError = false,
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
                    // 档案里带着用户真实的考期:顺手把本地倒计时节点的日子对齐一次,
                    // 免得首页一直显示首启预置的那个占位日期
                    runCatching { repo.syncExamCountdown(p.targetType, p.examDate) }
                    _ui.update { s -> s.copy(phase = OnboardingPhase.SUCCESS, profile = p) }
                } else {
                    // 老账号可能只有半份档案:能回填的全部回填,别让人重填一遍
                    _ui.update { s ->
                        s.copy(
                            phase = OnboardingPhase.EDITING,
                            // 主动来改档案、且档案本来就完整:直接落在最后一题。
                            // 上次的答案已全部回填,没必要再从第 1 题一路点「下一步」重走一遍;
                            // 真要改前面的题,点「返回上一步」即可。
                            step = if (manage && p?.isComplete == true) ONBOARDING_STEPS - 1 else 0,
                            profile = p,
                            profileSaved = p != null,
                            targetType = p?.targetType?.takeIf { it in TARGET_TYPES } ?: s.targetType,
                            examDate = p?.examDate?.takeIf { it.isNotBlank() } ?: s.examDate,
                            studyWindows = p?.studyWindows?.toSet()?.takeIf { it.isNotEmpty() } ?: s.studyWindows,
                            foundation = p?.foundation?.takeIf { it in FOUNDATION_LEVELS } ?: s.foundation,
                            weakSubjects = p?.weakSubjects?.toSet() ?: s.weakSubjects,
                            // 之前答过的正式科目与真实空闲一并回填:重填问卷不该把上次确认过的事实抹掉
                            examSubjects = p?.brief?.examSubjects?.map { it.name }?.toSet()?.takeIf { it.isNotEmpty() }
                                ?: s.examSubjects,
                            availability = p?.brief?.let { briefToGrid(it.availability) }?.takeIf { it.isNotEmpty() }
                                ?: s.availability,
                            commitments = p?.brief?.fixedCommitments?.takeIf { it.isNotEmpty() } ?: s.commitments
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
        val to = (s.step + 1).coerceAtMost(ONBOARDING_STEPS - 1)
        // 上一屏已经勾过「最没底的几门」,这一屏的科目别再让用户重勾一遍;
        // 只在用户还没动过这一屏的科目时补齐,免得把他在这一屏的删改覆盖掉
        val subjects = if (to == ONBOARDING_STEPS - 1 && s.examSubjects.isEmpty())
            s.weakSubjects.take(MAX_EXAM_SUBJECTS).toSet() else s.examSubjects
        _ui.update {
            it.copy(step = to, examSubjects = subjects, message = "", messageIsError = false)
        }
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
     * 问卷这一屏只负责把可结构化的那部分(考什么、考期、真实空闲)落库,真正的排计划交给面谈。
     */
    fun saveProfileOnly() {
        val s = _ui.value
        if (s.phase == OnboardingPhase.SUBMITTING) return
        val err = validate(s, ONBOARDING_STEPS - 1)
        if (err != null) {
            _ui.update { it.copy(message = err, messageIsError = true) }
            return
        }
        _ui.update { it.copy(phase = OnboardingPhase.SUBMITTING, message = "", messageIsError = false) }

        viewModelScope.launch {
            val body = ProfileReq(
                targetType = s.targetType,
                examDate = s.examDate.trim(),
                studyWindows = STUDY_WINDOWS.filter { it in s.studyWindows },
                foundation = s.foundation,
                weakSubjects = s.weakSubjects.toList(),
                examSubjects = s.examSubjects.toList(),
                availability = availabilityPayload(s.availability),
                fixedCommitments = s.commitments,
                // 走到这一步就已经把科目、空闲和占用逐条看过并确认了,面谈不用再复问同一批问题
                availabilityConfirmed = true,
                commitmentsConfirmed = true
            )
            runCatching { ApiClient.api().putProfile(body) }.fold({ saved ->
                // 档案落库即完成:计划由「AI 面谈 → 生成」这条独立的路去产生
                // 考期同时落到本地倒计时节点,首页显示的是用户自己填的日子
                runCatching { repo.syncExamCountdown(s.targetType, s.examDate) }
                // 本地科目与确认结果对齐:确认的建出来,历史预置的考研科目(未被真实使用)清掉。
                // 「我的」页头像下的科目标志、问卷的科目候选,读的都是本地科目 —— 必须对齐。
                runCatching { repo.syncLocalSubjects(s.examSubjects) }
                    .onSuccess {
                        localSubjectNames = runCatching {
                            repo.observeSubjects().first().map { it.name.trim() }.filter { it.isNotEmpty() }
                        }.getOrDefault(localSubjectNames)
                        // 科目就位后补导一次内置参考计划包:首启时科目还不存在,参考任务挂不上;
                        // 导入按标题幂等,考研用户的 468 参考计划此刻才真正就位(考公用户则自然挂不上)
                        yanzhongApp.importBuiltinPlanPack(force = true)
                    }
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
                        step = ONBOARDING_STEPS - 1,
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
                // 换备考类型,科目候选跟着换(F3):选法考后不该还挂着"数学/政治"。
                // 用户自己加过的科目保留在候选里,不丢他敲过的字。
                subjectOptions = (subjectOptionsFor(value, localSubjectNames) + s.subjectOptions).distinct(),
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

    // ---------------- 正式科目与真实空闲 ----------------

    fun toggleExamSubject(name: String) {
        _ui.update { s ->
            val next = if (name in s.examSubjects) s.examSubjects - name else s.examSubjects + name
            if (next.size > MAX_EXAM_SUBJECTS) {
                s.copy(message = "科目最多 $MAX_EXAM_SUBJECTS 门,先填最要紧的", messageIsError = true)
            } else {
                s.copy(examSubjects = next, message = "", messageIsError = false)
            }
        }
    }

    /** 清单里没有的科目(自命题专业课的名字我们猜不到)让用户自己加 */
    fun addExamSubject(name: String) {
        val trimmed = name.trim().take(16)
        if (trimmed.isEmpty()) return
        _ui.update { s ->
            if (trimmed in s.examSubjects) {
                s.copy(message = "", messageIsError = false)
            } else if (s.examSubjects.size >= MAX_EXAM_SUBJECTS) {
                s.copy(message = "科目最多 $MAX_EXAM_SUBJECTS 门,先填最要紧的", messageIsError = true)
            } else {
                s.copy(
                    subjectOptions = (s.subjectOptions + trimmed).distinct(),
                    examSubjects = s.examSubjects + trimmed,
                    message = "",
                    messageIsError = false
                )
            }
        }
    }

    /** 点一下格子里的时段:来回切换「这天这个时段我空着」 */
    fun toggleAvailability(weekday: Int, window: String) {
        _ui.update { s ->
            val day = s.availability[weekday].orEmpty()
            val next = if (window in day) day - window else day + window
            val grid = if (next.isEmpty()) s.availability - weekday else s.availability + (weekday to next)
            s.copy(availability = grid, message = "", messageIsError = false)
        }
    }

    fun removeCommitment(index: Int) {
        _ui.update { s ->
            if (index !in s.commitments.indices) s
            else s.copy(commitments = s.commitments.filterIndexed { i, _ -> i != index })
        }
    }

    /**
     * 手动补一条固定占用(没传课表或课表上没印全的时候用)。
     * [start]/[end] 是用户敲的钟点,这里只做格式与先后校验,不猜。
     * 返回是否真的记下,好让输入框只在成功时清空 —— 校验没过就留着让用户改。
     */
    fun addCommitment(weekday: Int, start: String, end: String, label: String): Boolean {
        val s = _ui.value
        val from = normalizeClock(start)
        val to = normalizeClock(end)
        val name = label.trim().take(16)
        val error = when {
            name.isEmpty() -> "这条占用是什么事,写两个字就行(比如 上课)"
            from == null || to == null -> "时间按 8:00 或 08:00 这样填"
            from >= to -> "结束时间要晚于开始时间"
            s.commitments.size >= MAX_COMMITMENTS -> "固定占用最多 $MAX_COMMITMENTS 条"
            s.commitments.any { it.weekday == weekday && it.start == from && it.end == to && it.label == name } ->
                "这条已经记下了"
            else -> null
        }
        if (error != null) {
            _ui.update { it.copy(message = error, messageIsError = true) }
            return false
        }
        _ui.update {
            it.copy(
                commitments = it.commitments + BriefCommitmentDto(
                    weekday = weekday,
                    start = from!!,
                    end = to!!,
                    label = name
                ),
                message = "",
                messageIsError = false
            )
        }
        return true
    }

    /**
     * 课表截图 → 每周固定占用。
     *
     * 识图是可选路径:用户没课表、或者图太糊,都能跳过手动补。
     * 识别结果只往 [OnboardingUiState.commitments] 里加,不直接落库——
     * 落库要等用户看到列表、把认错的那几条删掉之后,随问卷一起提交。
     */
    fun parseTimetable(uri: Uri) {
        if (_ui.value.timetableBusy) return
        _ui.update {
            it.copy(timetableBusy = true, timetableNotice = "正在认这张课表,可能要十几秒…", timetableNoticeIsError = false)
        }
        viewModelScope.launch {
            val payload = runCatching { compressToBase64(uri) }.getOrNull()
            if (payload == null) {
                _ui.update {
                    it.copy(
                        timetableBusy = false,
                        timetableNotice = "这张图读不出来。换一张相册里的课表截图,或者干脆手动补几条",
                        timetableNoticeIsError = true
                    )
                }
                return@launch
            }
            runCatching { ApiClient.aiApi().parseTimetable(TimetableReq(image = payload, mimeType = "image/jpeg")) }
                .fold({ resp ->
                    _ui.update { s ->
                        val merged = (s.commitments + resp.fixedCommitments)
                            .distinctBy { "${it.weekday}-${it.start}-${it.end}-${it.label}" }
                            .take(MAX_COMMITMENTS)
                        s.copy(
                            commitments = merged,
                            timetableBusy = false,
                            timetableNotice = buildString {
                                append(resp.summary.ifBlank { "认到 ${resp.fixedCommitments.size} 段固定占用" })
                                if (resp.warnings.isNotEmpty()) {
                                    append("\n有几处没看清,自己核对一下:")
                                    append(resp.warnings.joinToString("；"))
                                }
                            },
                            timetableNoticeIsError = false
                        )
                    }
                }, { e ->
                    val http = e as? retrofit2.HttpException
                    val notice = when {
                        http?.code() == 503 -> "服务端还没配认课表的模型,先手动把占用补上就行"
                        else -> "课表没认出来(${e.userMessage()})。手动补几条,或者先跳过"
                    }
                    _ui.update { it.copy(timetableBusy = false, timetableNotice = notice, timetableNoticeIsError = true) }
                })
        }
    }

    fun clearTimetableNotice() {
        if (_ui.value.timetableNotice.isNotEmpty()) {
            _ui.update { it.copy(timetableNotice = "", timetableNoticeIsError = false) }
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
            if (s.studyWindows.isEmpty()) return "至少选一个你真能固定下来的时段,哪怕只有「晚上」"
            if (s.weakSubjects.isEmpty()) return "挑一门最想补的科目,后面对它的排课会多一些"
            if (s.weakSubjects.size > 6) return "薄弱科目最多 6 门,先抓最要紧的几门"
            if (s.foundation !in FOUNDATION_LEVELS) return "选一下你现在的基础情况"
        }
        if (step >= 2) {
            if (s.examSubjects.isEmpty()) return "把要考的科目写全,一门也算"
            if (s.availability.isEmpty()) return "七天里至少标出一天真能坐下学的时段,点一下那一格就行"
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

    // ---------------- 载荷与图片 ----------------

    /** 格子 → 服务端要的按星期窗口表;一天都没选中的星期不出现在载荷里 */
    private fun availabilityPayload(grid: Map<Int, Set<String>>): List<BriefAvailabilityDto> =
        (1..7).mapNotNull { weekday ->
            val windows = WINDOW_CLOCK.filterKeys { it in grid[weekday].orEmpty() }
                .values
                .map { BriefWindowDto(start = it.first, end = it.second) }
            if (windows.isEmpty()) null else BriefAvailabilityDto(weekday = weekday, windows = windows)
        }

    /**
     * 把相册里的图压成能上传的 base64。
     *
     * 为什么要自己压:课表截图动辄好几 MB,服务端 JSON 上限 8mb,原图直传十有八九被拒;
     * 缩到最长边 1600、按 JPEG 82 编码后通常只剩几百 KB,课表上的字照样认得出。
     */
    private fun compressToBase64(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val scaled = scaleDown(decoded, TIMETABLE_MAX_SIDE)
        val out = ByteArrayOutputStream()
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        if (!ok) return null
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}

/** 把落库的钟点窗口翻回问卷里的粗时段;对不上的自定义窗口不还原(问卷才是空闲的唯一入口) */
internal fun briefToGrid(availability: List<BriefAvailabilityDto>): Map<Int, Set<String>> =
    availability.associate { slot ->
        slot.weekday to slot.windows.mapNotNull { w ->
            WINDOW_CLOCK.entries.firstOrNull { it.value.first == w.start && it.value.second == w.end }?.key
        }.toSet()
    }.filterValues { it.isNotEmpty() }

/** "8:00" / "08:00" / "8：00" → "08:00";认不出来给 null */
internal fun normalizeClock(raw: String): String? {
    val match = Regex("^(\\d{1,2})[:：](\\d{2})$").find(raw.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return "%02d:%02d".format(hour, minute)
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
