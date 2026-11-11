package com.yanzhong.app.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ---------- DTO ----------

@Serializable
data class UserDto(
    val guid: String, val username: String, val email: String? = null, val createdAt: Long = 0,
    val nickname: String? = null, val avatarPreset: String? = null
)

@Serializable
data class DeviceDto(val id: Long, val guid: String? = null, val name: String)

@Serializable
data class DeviceFullDto(
    val id: Long,
    val guid: String? = null,
    val name: String,
    val platform: String = "",
    val lastIp: String? = null,
    val lastActiveAt: Long = 0,
    val online: Boolean = false
)

@Serializable
data class TokensDto(val access: String, val refresh: String, val expiresIn: Long)

/**
 * 注册请求。
 *
 * 邮箱在注册时必须通过验证码验证,所以 [email] / [code] 都是必填;
 * [inviteCode] 由服务端在发码和注册两处各校验一次,客户端只做"非空"这一层拦截。
 *
 * [termsAccepted] / [privacyAccepted] 是服务端的硬校验字段:首启同意门已经把协议留档,
 * 这里固定传 true,只是为了让服务端确认"注册这个动作本身也带着同意"。
 */
@Serializable
data class RegisterReq(
    val username: String,
    val password: String,
    val email: String,
    val code: String,
    val inviteCode: String? = null,
    val termsAccepted: Boolean = false,
    val privacyAccepted: Boolean = false
)

/** 服务端 POST /auth/register 返回 { user: {...} }，与登录响应保持同样的信封结构。 */
@Serializable
data class RegisterResp(val user: UserDto)

@Serializable
data class LoginReq(
    val username: String,
    val password: String,
    val deviceGuid: String,
    val deviceName: String,
    val platform: String = "android",
    val rememberMe: Boolean = false
)

@Serializable
data class LoginResp(val user: UserDto, val device: DeviceDto, val tokens: TokensDto)

@Serializable
data class RefreshReq(val refresh: String)

@Serializable
data class RefreshResp(val user: UserDto, val device: DeviceDto, val tokens: TokensDto)

/** 注册第一步:往邮箱发 6 位验证码;邀请码在发码时就会校验一次,别让用户填完整个表单才被拒 */
@Serializable
data class EmailCodeReq(val email: String, val purpose: String, val inviteCode: String? = null)

@Serializable
data class EmailCodeResp(val sent: Boolean = false)

/** 找回密码第一步:只按邮箱发码;响应统一为 {"sent": true},不暴露邮箱是否注册 */
@Serializable
data class ForgotReq(val email: String)

@Serializable
data class ResetReq(val email: String, val code: String, val newPassword: String)

@Serializable
data class ChangePasswordReq(val oldPassword: String, val newPassword: String)

@Serializable
data class DevicesResp(val devices: List<DeviceFullDto>)

@Serializable
data class RejectedSyncRow(
    val index: Int,
    val reasons: List<String>
)

@Serializable
data class SyncResult(
    val applied: Int = 0,
    val skipped: Int = 0,
    val rejected: List<RejectedSyncRow>? = null,
    val truncated: Int? = null,
    val serverTime: Long = 0
)

@Serializable
data class RestoreResourceStat(
    val applied: Int = 0,
    val skipped: Int = 0,
    val rejected: List<RejectedSyncRow> = emptyList()
)

@Serializable
data class RestoreResp(
    val restored: Boolean = false,
    val resources: Map<String, RestoreResourceStat> = emptyMap(),
    val serverTime: Long = 0
)

@Serializable
data class SettingsResp(val settings: Map<String, kotlinx.serialization.json.JsonElement>, val serverTime: Long = 0)

@Serializable
data class PutSettingsReq(val settings: Map<String, kotlinx.serialization.json.JsonElement>)

// ---------- 备考档案与计划 ----------
//
// 这一组数据是服务端权威的（不在 clientGuid 墓碑同步协议里），
// 所以字段形状严格对齐服务端 PublicProfile / PublicPlan，宁可直接照抄也不要"猜"。
// 日期一律是 YYYY-MM-DD 字符串，服务端已经把它们归一到 UTC 零点。

/** 对齐服务端 SUPPORTED_TARGET_TYPES */
val TARGET_TYPES = listOf("考研", "专升本", "考公", "法考", "其他")

/** 对齐服务端 STUDY_WINDOWS */
val STUDY_WINDOWS = listOf("早晨", "上午", "下午", "晚上", "深夜")

/** 对齐服务端 FOUNDATION_LEVELS */
val FOUNDATION_LEVELS = listOf("零基础", "一般", "较好")

/** GET /profile 返回的档案；onboardingDoneAt 非空才算"填过" */
@Serializable
data class ProfileDto(
    val targetType: String,
    val examDate: String,
    /** 服务端旧字段,已不再参与排计划(容量由课表空闲决定),保留仅为兼容响应 */
    val dailyMinutes: Int = 0,
    val studyWindows: List<String> = emptyList(),
    val foundation: String? = null,
    val weakSubjects: List<String> = emptyList(),
    /** AI 面谈产出的考生画像简报；没做过面谈时为 null */
    val brief: PlanBriefDto? = null,
    val onboardingDoneAt: Long? = null,
    val updatedAt: Long? = null
) {
    /** 是否完成过首次问卷；老账号可能只有部分字段，所以两个条件都要满足 */
    val isComplete: Boolean get() = onboardingDoneAt != null && examDate.isNotBlank()
}

/**
 * PUT /profile 的请求体，字段与服务端 profileInputSchema 一一对应。
 *
 * 问卷把「关键事实」一次问全（正式科目名、按星期的真实空闲窗口、固定占用），
 * 面谈才不用一遍遍复问同一批问题；两个 confirmed 位是「用户明确确认」的凭证，
 * 缺了它们服务端不会放行计划生成。
 */
@Serializable
data class ProfileReq(
    val targetType: String,
    val examDate: String,
    val studyWindows: List<String>,
    val foundation: String,
    val weakSubjects: List<String>,
    /** 正式考试科目名；逐科进度与里程碑由面谈补齐 */
    val examSubjects: List<String> = emptyList(),
    /** 按星期记录的真实空闲窗口 */
    val availability: List<BriefAvailabilityDto> = emptyList(),
    /** 固定占用（上课、上班、通勤等），可由课表图片识别得到 */
    val fixedCommitments: List<BriefCommitmentDto> = emptyList(),
    /** 校历第 1 周的周一(YYYY-MM-DD);空串表示不启用单双周 */
    val termWeekOneStart: String = "",
    val availabilityConfirmed: Boolean = false,
    val commitmentsConfirmed: Boolean = false
)

@Serializable
data class ProfileResp(val profile: ProfileDto? = null, val serverTime: Long = 0)

/** 个人身份资料(头像/昵称/签名/性别/生日);avatar 为自定义头像裸 base64,与 avatarPreset 互斥 */
@Serializable
data class IdentityDto(
    val guid: String = "", val username: String = "", val email: String? = null,
    val nickname: String? = null, val signature: String? = null, val gender: String? = null,
    val birthday: String? = null, val avatarPreset: String? = null, val avatar: String? = null
)

@Serializable
data class AccountMeResp(val identity: IdentityDto = IdentityDto(), val serverTime: Long = 0)

/** PATCH /account/me 请求体;编辑页保存整份表单,值或 null(清空) */
@Serializable
data class UpdateIdentityReq(
    val nickname: String? = null, val signature: String? = null, val gender: String? = null,
    val birthday: String? = null, val avatarPreset: String? = null, val avatar: String? = null
)

/** 计划里的一个阶段（基础 / 强化 / 冲刺） */
@Serializable
data class PlanStageDto(
    val id: Long,
    val name: String,
    val startDate: String,
    val endDate: String,
    val sortOrder: Int = 0,
    /** 阶段主线(多轮生成的策略轮产出);旧计划/生成失败降级时为 null */
    val strategy: String? = null,
    /** 阶段可验收里程碑 */
    val milestones: List<String> = emptyList()
)

/** 计划里的一天一件事 */
@Serializable
data class PlanItemDto(
    val id: Long,
    val stageId: Long = 0,
    val subject: String,
    val title: String,
    val planDate: String,
    val minutes: Int = 0,
    val priority: Int = 0,
    val status: String = "pending",
    val sortOrder: Int = 0,
    val completedAt: Long? = null,
    /** 服务端编译出的时段(morning/afternoon/evening),旧计划为 null */
    val slot: String? = null,
    /** 服务端编译出的开始时刻 HH:mm,客户端据此按时间轴展示与排序 */
    val startTime: String? = null,
    /** 锁定项不参与 AI 调整与自动重排;手工编辑仍可解锁后修改 */
    val locked: Boolean = false
)

@Serializable
data class PlanProgressDto(
    val totalItems: Int = 0,
    val pendingItems: Int = 0,
    val doneItems: Int = 0,
    val totalMinutes: Int = 0,
    val totalDays: Int = 0
)

/** 服务端下发的计划。[stale] 表示档案改过但计划还没重建，界面要提示"重新生成"而不是装作没变。 */
@Serializable
data class PlanDto(
    val id: Long,
    val title: String,
    val targetType: String = "",
    val source: String = "",
    val status: String = "",
    val startDate: String,
    val examDate: String,
    val version: Int = 1,
    val stale: Boolean = false,
    val stages: List<PlanStageDto> = emptyList(),
    val items: List<PlanItemDto> = emptyList(),
    val progress: PlanProgressDto = PlanProgressDto(),
    /** AI 产出的全程规划长文档；旧计划或模型未产出时为 null */
    val document: PlanDocumentDto? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

@Serializable
data class PlanResp(
    val plan: PlanDto? = null,
    val usage: UsageSummaryDto? = null,
    val serverTime: Long = 0
)

/**
 * 一次 AI 请求/一次生成消耗的 token 汇总。旧服务端不返回 usage:
 * 字段全部有默认值,解析失败/缺省时界面整体隐藏用量行。
 */
@Serializable
data class UsageSummaryDto(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val calls: Int = 0,
    val estimatedCalls: Int = 0
)

@Serializable
data class AiUsageFeatureDto(
    val feature: String = "",
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val calls: Int = 0,
    val estimatedCalls: Int = 0
)

@Serializable
data class AiUsageResp(
    val today: UsageSummaryDto = UsageSummaryDto(),
    val month: UsageSummaryDto = UsageSummaryDto(),
    val byFeature: List<AiUsageFeatureDto> = emptyList(),
    val serverTime: Long = 0
)

/** 异步生成任务的进度(与服务端 PlanProgress 对齐) */
@Serializable
data class PlanJobProgressDto(
    val percent: Int = 0,
    /** skeleton/refining/compiling/verifying/repairing/reviewing/documenting/done */
    val phase: String = "",
    val message: String = ""
)

@Serializable
data class PlanJobErrorDto(val code: String = "", val message: String = "")

@Serializable
data class PlanGenerationJobDto(
    val id: String = "",
    /** running / done / failed */
    val status: String = "running",
    val progress: PlanJobProgressDto = PlanJobProgressDto(),
    val planId: Long? = null,
    val error: PlanJobErrorDto? = null,
    /** 本次生成消耗的 token 汇总;成功、失败都会回填 */
    val usage: UsageSummaryDto? = null
)

@Serializable
data class PlanGenerationJobResp(
    val jobId: String = "",
    val job: PlanGenerationJobDto = PlanGenerationJobDto(),
    val serverTime: Long = 0
)

@Serializable
data class PlanGenerationStatusResp(
    val job: PlanGenerationJobDto = PlanGenerationJobDto(),
    val serverTime: Long = 0
)

/** 勾选计划项的 slim 响应:只回被改的那一项与进度(全量 plan 数百 KB,勾选是高频操作) */
@Serializable
data class SlimToggleResp(
    val item: PlanItemDto,
    val progress: PlanProgressDto = PlanProgressDto(),
    val serverTime: Long = 0
)

/** 长文档轻量响应:文档页只需要这份 8 章文档,不拉全量 items */
@Serializable
data class PlanDocumentResp(
    val document: PlanDocumentDto? = null,
    val usage: UsageSummaryDto? = null,
    val serverTime: Long = 0
)

/**
 * 历史计划列表里的一条摘要。服务端刻意不带 stages/items 明细——
 * 一份计划动辄上千条计划项，列表页只需要"哪一版、多少天、完成多少"，
 * 详情在用户点进某一条时再用 getPlanById 单独拉，避免列表接口把内存撑爆。
 */
@Serializable
data class PlanSummaryDto(
    val id: Long,
    val title: String,
    val targetType: String = "",
    val source: String = "",
    val status: String = "",
    val startDate: String,
    val examDate: String,
    val version: Int = 1,
    val totalItems: Int = 0,
    val doneItems: Int = 0,
    val totalDays: Int = 0,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

@Serializable
data class PlanHistoryResp(val plans: List<PlanSummaryDto> = emptyList(), val serverTime: Long = 0)

/** 勾选/取消勾选计划项:服务端只认 pending / done 两个值 */
@Serializable
data class PlanItemStatusReq(val status: String)

// ---------- 计划预算、生成口径与手工编辑 ----------
//
// 这一组与 server modules/planning/planBudget.ts 的响应字段一一对应。
// 旧服务端没有这些路由/字段,所以全部给默认值:解析旧响应不报错,界面按空数据降级。

/** 预算里的一个缺口:某科在里程碑前还差多少分钟 */
@Serializable
data class BudgetDeficitDto(
    val subject: String = "",
    val milestone: String = "",
    val missingMinutes: Int = 0
)

/**
 * 逐科预算。[budgetMinutes]/[milestoneBudgetMinutes] 来自 brief 的预算视图,
 * [targetMinutes]/[scheduledMinutes]/[deltaMinutes]/[beforeMilestoneMinutes] 来自草案 overview,
 * 两份响应共用同一个 DTO,缺的字段按默认值处理。
 */
@Serializable
data class PlanBudgetSubjectDto(
    val name: String = "",
    val estimated: Boolean = false,
    val remainingMinutes: Int = 0,
    val budgetMinutes: Int = 0,
    val milestone: String = "",
    val milestoneDate: String = "",
    val milestoneMinutes: Int = 0,
    val milestoneBudgetMinutes: Int = 0,
    val targetMinutes: Int = 0,
    val scheduledMinutes: Int = 0,
    val beforeMilestoneMinutes: Int = 0,
    val deltaMinutes: Int = 0
)

/** 计划总预算:确认任务量、有效容量、目标量、缺口与逐科明细 */
@Serializable
data class PlanBudgetDto(
    val capacityMinutes: Int = 0,
    val effectiveCapacityMinutes: Int = 0,
    val confirmedMinutes: Int = 0,
    val estimatedMinutes: Int = 0,
    val estimatedBudgetMinutes: Int = 0,
    val targetMinutes: Int = 0,
    val shortfallMinutes: Int = 0,
    val scheduledMinutes: Int = 0,
    val utilization: Double = 0.0,
    val deficits: List<BudgetDeficitDto> = emptyList(),
    val subjects: List<PlanBudgetSubjectDto> = emptyList(),
    val warnings: List<String> = emptyList()
)

/** brief 里附带的档案摘要,确认页用来显示目标与考期 */
@Serializable
data class BriefProfileDto(
    val targetType: String = "",
    val examDate: String = "",
    val foundation: String? = null,
    val weakSubjects: List<String> = emptyList()
)

@Serializable
data class WeekdayCapacityDto(val weekday: Int = 0, val minutes: Int = 0)

/** GET /plans/brief:计划口径确认页的数据源(brief + 哈希 + 预算 + 每周容量) */
@Serializable
data class PlanBriefResp(
    val brief: PlanBriefDto? = null,
    val briefHash: String = "",
    val profile: BriefProfileDto = BriefProfileDto(),
    val budget: PlanBudgetDto = PlanBudgetDto(),
    val capacityByWeekday: List<WeekdayCapacityDto> = emptyList(),
    val warnings: List<String> = emptyList(),
    val serverTime: Long = 0
)

/** 草案预算表里的一天负荷 */
@Serializable
data class PlanDayLoadDto(
    val date: String = "",
    val capacityMinutes: Int = 0,
    val dayCapMinutes: Int = 0,
    val scheduledMinutes: Int = 0,
    val itemCount: Int = 0
)

@Serializable
data class PlanStageLoadDto(
    val name: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val targetMinutes: Int = 0,
    val scheduledMinutes: Int = 0
)

@Serializable
data class PlanWarningDto(
    val code: String = "",
    val message: String = "",
    val date: String? = null,
    val subject: String? = null
)

/** GET /plans/:id/overview:草案/生效计划的预算、逐日负荷、阶段目标与警告(服务端平铺返回) */
@Serializable
data class PlanOverviewDto(
    val planId: Long = 0,
    val title: String = "",
    val version: Int = 1,
    val startDate: String = "",
    val examDate: String = "",
    val status: String = "",
    val budget: PlanBudgetDto = PlanBudgetDto(),
    val subjects: List<PlanBudgetSubjectDto> = emptyList(),
    val days: List<PlanDayLoadDto> = emptyList(),
    val stages: List<PlanStageLoadDto> = emptyList(),
    val warnings: List<PlanWarningDto> = emptyList(),
    val horizonDays: Int = 0,
    val serverTime: Long = 0
)

/** 一条调整方案的影响指标 */
@Serializable
data class AdjustmentImpactDto(
    val affectedDays: Int = 0,
    val movedCount: Int = 0,
    val addedCount: Int = 0,
    val removedCount: Int = 0,
    val totalMinutesBefore: Int = 0,
    val totalMinutesAfter: Int = 0,
    val maxDayLoad: Int = 0,
    val maxDayCapacity: Int = 0,
    val shortfallMinutes: Int = 0,
    val milestoneRiskCount: Int = 0,
    val lockedKeptCount: Int = 0
)

/** 一个可选方案:id 为 compress/spill/drop/lower_target/manual */
@Serializable
data class AdjustmentOptionDto(
    val id: String = "",
    val label: String = "",
    val summary: String = "",
    val tier: String = "",
    val impact: AdjustmentImpactDto = AdjustmentImpactDto(),
    val changes: List<AdjustmentChangeDto> = emptyList()
)

/** 生成计划请求:带 briefHash 表示用户已确认口径;allowShortfall 只能与 hash 同时出现 */
@Serializable
data class GeneratePlanReq(
    val briefHash: String? = null,
    val allowShortfall: Boolean? = null
)

/** 手工改口径:逐科剩余量/里程碑/截止日/估算位 + 固定占用 */
@Serializable
data class BriefSubjectPatch(
    val name: String,
    val remainingMinutes: Int? = null,
    val milestone: String? = null,
    val milestoneDate: String? = null,
    val milestoneMinutes: Int? = null,
    val estimated: Boolean? = null,
    val scope: String? = null,
    val progress: String? = null
)

@Serializable
data class PatchBriefReq(
    val examSubjects: List<BriefSubjectPatch>? = null,
    val fixedCommitments: List<BriefCommitmentDto>? = null
)

/** 手工编辑任务内容(status 勾选仍走 PlanItemStatusReq,两者不能混在一次请求里) */
@Serializable
data class PlanItemEditReq(
    val status: String? = null,
    val planDate: String? = null,
    val minutes: Int? = null,
    val title: String? = null,
    val subject: String? = null,
    val locked: Boolean? = null
)

@Serializable
data class AddPlanItemReq(
    val planId: Long? = null,
    val subject: String,
    val title: String,
    val planDate: String,
    val minutes: Int,
    val locked: Boolean? = null
)

@Serializable
data class PlanItemEditResp(
    val plan: PlanDto? = null,
    val item: PlanItemDto? = null,
    val warnings: List<String> = emptyList(),
    val serverTime: Long = 0
)

@Serializable
data class SelectOptionReq(val optionId: String)

/** POST /account/delete 的请求体：confirm 必须与用户名完全一致，password 是本人密码 */
@Serializable
data class DeleteAccountReq(val confirm: String, val password: String)

@Serializable
data class DeleteAccountResp(val deleted: Int = 0, val serverTime: Long = 0)

/**
 * 首启同意门的服务端补报。
 *
 * [version] 必须与服务端 POLICY_VERSION 完全一致(客户端常量在 ConsentStore.CONSENT_VERSION),
 * 不一致服务端会回 409 —— 那是「协议换了新版」的信号,而不是可以静默忽略的错误。
 */
@Serializable
data class ConsentReq(val version: String)

// ---------- 计划长文档与 AI 面谈 ----------
//
// 与服务端 modules/planning/document.ts 的结构一一对应。
// 长文档是「可选的加分项」：老计划没有 document，模型偶尔产不出也不阻断每日清单，
// 所以这里所有集合字段都给默认值、document 在 PlanDto 里可空，界面据此决定是否显示「查看全程规划」。

/** Hero 顶部的一个数据格（如「备考天数 468」「每日 6.5h」） */
@Serializable
data class DocStatDto(val label: String = "", val value: String = "")

/** 文档头：徽标 + 主标题（lead/accent/tail 三段，accent 做渐变高亮）+ 副标题 + 科目标签 + 数据格 */
@Serializable
data class DocHeroDto(
    val badge: String = "",
    val titleLead: String = "",
    val titleAccent: String = "",
    val titleTail: String = "",
    val subtitle: String = "",
    val subjects: List<String> = emptyList(),
    val stats: List<DocStatDto> = emptyList()
)

/** 玻璃拟态卡：标题 + 可选副标题 + 若干要点行 */
@Serializable
data class DocCardDto(
    val icon: String? = null,
    val title: String = "",
    val subtitle: String? = null,
    val lines: List<String> = emptyList()
)

/** 表格：columns 定列头，rows 每行长度已按 columns 对齐 */
@Serializable
data class DocTableDto(
    val title: String = "",
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList()
)

/**
 * 文档中的一个内容块。服务端是 text | cards | tables 的判别联合，
 * 这里用 [type] 字段区分，另外两组字段留空——客户端按 type 分支渲染即可。
 */
@Serializable
data class DocBlockDto(
    val type: String = "text",
    val text: String? = null,
    val emph: Boolean = false,
    val cards: List<DocCardDto> = emptyList(),
    val tables: List<DocTableDto> = emptyList()
)

/** 一章：序号 + 标题 + 导语 + 内容块 */
@Serializable
data class DocChapterDto(
    val no: String = "",
    val title: String = "",
    val intro: String? = null,
    val blocks: List<DocBlockDto> = emptyList()
)

/** 整份计划长文档（对标「468 天考研全程作战计划」） */
@Serializable
data class PlanDocumentDto(
    val title: String = "",
    val hero: DocHeroDto = DocHeroDto(),
    val chapters: List<DocChapterDto> = emptyList()
)

/** AI 面谈产出的考生画像简报：补上问卷问不到的信息，生成计划时作为上下文 */
@Serializable
data class PlanBriefDto(
    val summary: String = "",
    val goals: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val focus: List<String> = emptyList(),
    val materials: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val examSubjects: List<BriefSubjectDto> = emptyList(),
    val availability: List<BriefAvailabilityDto> = emptyList(),
    val fixedCommitments: List<BriefCommitmentDto> = emptyList(),
    val termWeekOneStart: String = "",
    val availabilityConfirmed: Boolean = false,
    val commitmentsConfirmed: Boolean = false
)

@Serializable
data class BriefSubjectDto(
    val name: String, val progress: String = "", val scope: String = "",
    val remainingMinutes: Int = 0, val milestone: String = "",
    val milestoneDate: String = "", val milestoneMinutes: Int = 0,
    /** true 表示这些逐科细节是 AI 按经验估的，考生可在对话里逐项更正 */
    val estimated: Boolean = false
)

@Serializable
data class BriefWindowDto(val start: String, val end: String)

@Serializable
data class BriefAvailabilityDto(val weekday: Int, val windows: List<BriefWindowDto>)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class BriefCommitmentDto(
    val weekday: Int,
    val start: String,
    val end: String,
    val label: String,
    /** 单双周:"odd"=单周 / "even"=双周 / null=每周都占。只在填了 termWeekOneStart 时生效 */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val weekParity: String? = null
)

/** 面谈消息：role 只认 user / assistant */
@Serializable
data class InterviewMessageDto(val role: String, val content: String)

/** POST /plans/interview 请求体。[force] 为 true 时即使轮次不够也强制收尾产出简报 */
@Serializable
data class InterviewReq(
    val messages: List<InterviewMessageDto> = emptyList(),
    val force: Boolean = false
)

/** 面谈一轮的结果。[done] 为 true 表示 AI 认为信息够了，[brief] 随之给出 */
@Serializable
data class InterviewResp(
    val reply: String = "",
    val options: List<String> = emptyList(),
    val done: Boolean = false,
    val brief: PlanBriefDto? = null,
    /** 服务端判定档案未就绪:客户端据此切回「填写备考档案」阶段，给用户可点击的出口 */
    val needProfile: Boolean = false,
    val usage: UsageSummaryDto? = null,
    val serverTime: Long = 0
)

/**
 * POST /plans/timetable 请求体。图片走 base64 + JSON（服务端 bodyparser 上限 8mb），
 * 客户端必须先把课表截图压缩到几百 KB 再上传，否则会被直接拒掉。
 */
@Serializable
data class TimetableReq(
    /** data:image/...;base64,xxx 或裸 base64（服务端会自动补前缀） */
    val image: String,
    val mimeType: String? = null
)

/** 课表识别结果。[warnings] 是看不清/存疑的地方，界面要提示用户手工核对 */
@Serializable
data class TimetableResp(
    val summary: String = "",
    val warnings: List<String> = emptyList(),
    val fixedCommitments: List<BriefCommitmentDto> = emptyList(),
    val usage: UsageSummaryDto? = null,
    val serverTime: Long = 0
)

// ---------- 行程调整(D2) ----------

/** 调整单里一条变动的字段快照 */
@Serializable
data class AdjustmentFieldDto(
    val planDate: String,
    val minutes: Long = 0,
    val title: String = ""
)

/** 一条变动:moved=挪日期 / added=新增 / removed=删除 / updated=改内容 */
@Serializable
data class AdjustmentChangeDto(
    val kind: String,
    val id: Long = 0,
    val subject: String = "",
    val title: String = "",
    val from: AdjustmentFieldDto? = null,
    val to: AdjustmentFieldDto? = null,
    /** 服务端给出的变动说明(如「缩短 60→45 分钟（可让）」);旧服务端为空 */
    val note: String? = null
)

/** 待确认/已生效的调整单;changes 由服务端从前后快照重算 */
@Serializable
data class AdjustmentDto(
    val id: Long,
    val planId: Long = 0,
    val tier: String = "",
    val status: String = "",
    val reason: String? = null,
    val summary: String? = null,
    val windowFrom: String = "",
    val windowTo: String = "",
    val changes: List<AdjustmentChangeDto> = emptyList(),
    /** 多方案时服务端下发 2–4 条方案;单方案/旧服务端为 null */
    val options: List<AdjustmentOptionDto>? = null,
    /** 当前选中的方案 id;单方案时也指向那张调整单本身的方案 */
    val selectedOption: String? = null,
    val createdAt: Long? = null,
    val appliedAt: Long? = null
)

/**
 * 行程调整请求。[history] 携带本次消息之前的最近几轮对话(旧到新,不含 message 本身):
 * 「对」「嗯」这类对上一轮追问的简短确认,没有历史服务端就无从理解。旧服务端会忽略未知字段。
 */
@Serializable
data class AdjustReq(
    val message: String,
    val history: List<InterviewMessageDto> = emptyList()
)

@Serializable
data class AdjustResp(
    val adjustment: AdjustmentDto? = null,
    val reply: String? = null,
    val plan: PlanDto? = null,
    /** 追问时的可点选答案;点击后把 value 作为下一句发送 */
    val clarifyOptions: List<ClarifyOptionDto>? = null,
    /** lock_task 这类直接生效的意图为 true,无调整单也会改计划 */
    val changedPlan: Boolean = false,
    val usage: UsageSummaryDto? = null,
    val serverTime: Long = 0
)

@Serializable
data class ClarifyOptionDto(val label: String = "", val value: String = "")

@Serializable
data class LatestAdjustmentResp(val adjustment: AdjustmentDto? = null, val serverTime: Long = 0)

@Serializable
data class CheckupOverloadDayDto(
    val date: String = "",
    val planned: Long = 0,
    val capacity: Long = 0
)

@Serializable
data class CheckupResp(
    val behindMinutes: Long = 0,
    val overdueCount: Long = 0,
    val suggestion: String = "",
    /** 可一键带进调整对话的预填话术;没有需要主动干预的问题时为 null */
    val suggestedMessage: String? = null,
    val weekPlanDone: Long = 0,
    val weekPlanTotal: Long = 0,
    val weekFocusMinutes: Long = 0,
    val lastWeekFocusMinutes: Long = 0,
    val weekActiveDays: Long = 0,
    val next7dPlannedMinutes: Long = 0,
    val next7dCapacityMinutes: Long = 0,
    val overloadedDays: List<CheckupOverloadDayDto> = emptyList(),
    val serverTime: Long = 0
)

// ---------- 健康探针 ----------

@Serializable
data class HealthFeatures(val emailReset: Boolean? = null)

@Serializable
data class HealthResp(
    val ok: Boolean = false,
    val serverTime: Long = 0,
    val features: HealthFeatures? = null
)

// ---------- API 接口 ----------

interface YanzhongApi {
    /** 健康探针:无需登录即可调,告知服务端时间与可用能力(如邮件重置开关) */
    @GET("healthz")
    suspend fun health(): HealthResp

    /** 注册第一步:请求邮箱验证码 */
    @POST("api/v1/auth/email/code")
    suspend fun requestEmailCode(@Body body: EmailCodeReq): EmailCodeResp

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterReq): RegisterResp

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginReq): LoginResp

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshReq): RefreshResp

    @POST("api/v1/auth/logout")
    suspend fun logout()

    @POST("api/v1/auth/password/forgot")
    suspend fun forgotPassword(@Body body: ForgotReq)

    @POST("api/v1/auth/password/reset")
    suspend fun resetPassword(@Body body: ResetReq)

    @POST("api/v1/auth/password/change")
    suspend fun changePassword(@Body body: ChangePasswordReq)

    @GET("api/v1/devices")
    suspend fun devices(): DevicesResp

    @DELETE("api/v1/devices/{id}")
    suspend fun revokeDevice(@Path("id") id: Long)

    @DELETE("api/v1/devices")
    suspend fun revokeAllDevices()

    @GET("api/v1/backup")
    suspend fun backup(): BackupDownload

    @POST("api/v1/backup/restore")
    suspend fun restoreBackup(@Body payload: BackupUpload): RestoreResp

    /** 增量拉取:updated_at > since 的行(含墓碑);since=0 全量 */
    @GET("api/v1/sync")
    suspend fun pullChanges(@retrofit2.http.Query("since") since: Long): PullResp

    /** 批量推送:同资源脏行 + 墓碑行(isDeleted=true),服务端 LWW 幂等 upsert */
    @POST("api/v1/sync/{resource}")
    suspend fun pushResource(
        @retrofit2.http.Path("resource") resource: String,
        // Kotlin 的 List 是协变的,不加 @JvmSuppressWildcards 会生成 List<? extends JsonElement>,Retrofit 直接拒收
        @Body rows: List<@kotlin.jvm.JvmSuppressWildcards kotlinx.serialization.json.JsonElement>
    ): SyncResult

    @GET("api/v1/settings")
    suspend fun getSettings(): SettingsResp

    @PUT("api/v1/settings")
    suspend fun putSettings(@Body body: PutSettingsReq): SyncResult

    /** 读取备考档案；没填过时 profile 为 null（不是报错），界面据此决定要不要弹问卷 */
    @GET("api/v1/profile")
    suspend fun getProfile(): ProfileResp

    /** 保存备考档案（只写档案，不生成计划） */
    @PUT("api/v1/profile")
    suspend fun putProfile(@Body body: ProfileReq): ProfileResp

    /** 生成前口径确认页:简报 + 口径哈希 + 预算 + 按星期容量 */
    @GET("api/v1/plans/brief")
    suspend fun getPlanBrief(): PlanBriefResp

    /** 手工改口径(剩余量/里程碑/固定占用);计划生成中服务端会拒绝 */
    @retrofit2.http.PATCH("api/v1/plans/brief")
    suspend fun patchPlanBrief(@Body body: PatchBriefReq): PlanBriefResp

    /**
     * 依据当前档案生成待确认草稿；旧计划保持生效，直到调用 confirmPlan。
     * [GeneratePlanReq.briefHash] 是用户看过口径的凭证,服务端不匹配会回 409 PLAN_BRIEF_CHANGED。
     * 该接口可能耗时一两分钟（含长文档生成），调用方请走 [ApiClient.aiApi]。
     */
    @POST("api/v1/plans/generate")
    suspend fun generatePlan(@Body body: GeneratePlanReq = GeneratePlanReq()): PlanResp

    /**
     * 异步生成计划:立刻拿到 jobId,后台跑完整链路,进度经 WebSocket 的 planGeneration
     * 事件推送,也可以用 [getPlanGenerationJob] 轮询兜底。旧服务端无此路由时返回 404,
     * 调用方回退到同步的 [generatePlan]。
     */
    @POST("api/v1/plans/generate/jobs")
    suspend fun startPlanGeneration(
        @Body body: GeneratePlanReq = GeneratePlanReq()
    ): PlanGenerationJobResp

    @GET("api/v1/plans/generate/jobs/{id}")
    suspend fun getPlanGenerationJob(@Path("id") id: String): PlanGenerationStatusResp

    /** 草案/生效计划的预算摘要:预算表、逐日负荷、阶段目标与警告(服务端平铺返回) */
    @GET("api/v1/plans/{id}/overview")
    suspend fun getPlanOverview(@Path("id") id: Long): PlanOverviewDto

    @POST("api/v1/plans/{id}/confirm")
    suspend fun confirmPlan(@Path("id") id: Long): PlanResp

    /**
     * AI 面谈一轮：把已有对话发给服务端，拿回 AI 的下一个问题与快捷选项，
     * 信息够了（done=true）时一并返回考生画像简报。
     * 同样可能耗时较久，调用方请走 [ApiClient.aiApi]。
     */
    @POST("api/v1/plans/interview")
    suspend fun interview(@Body body: InterviewReq): InterviewResp

    /**
     * 课表图片识别：把单双周课表转成每周固定占用，返回结果供用户在问卷里核对。
     * 视觉模型耗时较久，调用方请走 [ApiClient.aiApi]。
     */
    @POST("api/v1/plans/timetable")
    suspend fun parseTimetable(@Body body: TimetableReq): TimetableResp

    /** 取当前有效计划；没有计划时 plan 为 null */
    @GET("api/v1/plans/active")
    suspend fun getActivePlan(): PlanResp

    /** 取最近一份未确认草稿；生成任务过期/服务重启后用它恢复草稿预览 */
    @GET("api/v1/plans/draft")
    suspend fun getLatestDraft(): PlanResp

    /** 历史计划列表（含当前 active 与所有 archived），按版本从新到旧，只返回摘要 */
    @GET("api/v1/plans/history")
    suspend fun getPlanHistory(): PlanHistoryResp

    /** 按 id 读取某一版计划的完整明细（含 stages/items），用于历史详情只读查看 */
    @GET("api/v1/plans/{id}")
    suspend fun getPlanById(@Path("id") id: Long): PlanResp

    /** 手工编辑任务内容(日期/时长/标题/科目/锁定);成功返回最新 plan 与被改的那一项 */
    @retrofit2.http.PATCH("api/v1/plans/items/{id}")
    suspend fun editPlanItem(
        @Path("id") id: Long,
        @Body body: PlanItemEditReq
    ): PlanItemEditResp

    /** 手工新增任务;默认加到当前生效计划,也可指定 planId(草案) */
    @POST("api/v1/plans/items")
    suspend fun addPlanItem(@Body body: AddPlanItemReq): PlanItemEditResp

    /** 删除未完成任务;done 项需要先取消勾选 */
    @DELETE("api/v1/plans/items/{id}")
    suspend fun deletePlanItem(@Path("id") id: Long): PlanResp

    /** 回写单个计划项的完成状态,成功返回整份最新计划(服务端会同步广播给同账号其它设备) */
    @retrofit2.http.PATCH("api/v1/plans/items/{id}")
    suspend fun patchPlanItem(
        @Path("id") id: Long,
        @Body body: PlanItemStatusReq
    ): PlanResp

    /**
     * 同 [patchPlanItem],但 slim=1 时服务端只回被改的那一项与进度 ——
     * 全量 plan 数百 KB 而勾选是高频操作,客户端用本地 plan 合并即可。走 [ApiClient.aiApi]。
     */
    @retrofit2.http.PATCH("api/v1/plans/items/{id}")
    suspend fun patchPlanItemSlim(
        @Path("id") id: Long,
        @retrofit2.http.Query("slim") slim: Int = 1,
        @Body body: PlanItemStatusReq
    ): SlimToggleResp

    /** 长文档轻量读取:只回 document 不回全量 items;旧服务端无此路由时由调用方 fallback */
    @GET("api/v1/plans/active/document")
    suspend fun getActivePlanDocument(): PlanDocumentResp

    /**
     * 长文档补生成:生成计划时文档部分失败(服务端设计上文档失败不连累每日清单),
     * 用当前生效计划已落库的阶段/任务重出一份,耗时约 1-2 分钟,走 [ApiClient.aiApi]。
     * 旧服务端无此路由时返回 404,调用方据此降级提示。
     */
    @POST("api/v1/plans/active/document")
    suspend fun regeneratePlanDocument(): PlanDocumentResp

    /** 行程调整:一句话描述突发情况,服务端产出待确认调整单(adjustment 为 null 时 reply 是追问/闲聊) */
    @POST("api/v1/plans/adjust")
    suspend fun adjustPlan(@Body body: AdjustReq): AdjustResp

    /** 最近一张待确认/已生效的调整单(冷启动恢复卡片用) */
    @GET("api/v1/plans/adjustments/latest")
    suspend fun getLatestAdjustment(): LatestAdjustmentResp

    /** 本人 AI token 用量:今日/本月(按设备时区)与本月按功能分项 */
    @GET("api/v1/usage/ai")
    suspend fun getAiUsage(@Query("utcOffsetMinutes") utcOffsetMinutes: Int): AiUsageResp

    /** 多方案调整:选中其中一条,服务端把 adjustment.changes 换成该方案的变动 */
    @POST("api/v1/plans/adjustments/{id}/select")
    suspend fun selectAdjustmentOption(
        @Path("id") id: Long,
        @Body body: SelectOptionReq
    ): LatestAdjustmentResp

    @POST("api/v1/plans/adjustments/{id}/confirm")
    suspend fun confirmAdjustment(@Path("id") id: Long): PlanResp

    @POST("api/v1/plans/adjustments/{id}/undo")
    suspend fun undoAdjustment(@Path("id") id: Long): PlanResp

    /** 计划体检:落后分钟数/过期任务数/一句话建议(suggestion 为空表示不用提醒) */
    @GET("api/v1/plans/checkup")
    suspend fun planCheckup(
        @Query("tzOffset") tzOffset: Int = 480,
        @Query("notify") notify: Int = 1
    ): CheckupResp

    /**
     * 全量导出账号数据。直接返回服务端 JSON（含账号、档案、计划、同步数据），
     * 客户端原样落盘即可，不需要理解内部结构——所以这里用 JsonElement 而不是建一堆 DTO。
     */
    /** 读取本人完整身份资料(含头像 base64) */
    @GET("api/v1/account/me")
    suspend fun getAccountMe(): AccountMeResp

    /** 局部更新身份资料;返回更新后的完整身份 */
    @PATCH("api/v1/account/me")
    suspend fun updateAccountMe(@Body body: UpdateIdentityReq): AccountMeResp

    @GET("api/v1/account/export")
    suspend fun exportAccount(): kotlinx.serialization.json.JsonElement

    /** 注销账号：不可撤销，服务端要求「完整用户名 + 密码」双重确认 */
    @POST("api/v1/account/delete")
    suspend fun deleteAccount(@Body body: DeleteAccountReq): DeleteAccountResp

    /** 已登录用户接受新政策后补报服务端 */
    @POST("api/v1/account/consent")
    suspend fun acceptConsent(@Body body: ConsentReq)
}

// ---------- 网络装配 ----------

/**
 * 默认服务器地址:自建服务器 IP + HTTP,仅用于内测阶段。
 * 明文请求依赖 res/xml/network_security_config.xml 放行该 IP,换地址时两处要一起改。
 * 接入 HTTPS 域名后改成 "https://<域名>" 并收回明文许可;
 * 若届时仍需区分调试/正式环境,可恢复成按 BuildConfig.DEBUG 分支的写法。
 * 用户在登录页/「云同步」页手动保存过的地址优先级更高(见 [effectiveServerUrl])。
 */
const val DEFAULT_SERVER_URL: String = "http://81.71.14.219:3000"

/**
 * 本次请求实际要用的服务器地址:设备上保存过的自定义地址优先,没保存则用内置默认地址。
 * 所有网络出口(接口调用、状态 WS、更新检查、OTA 下载)都必须走这里——
 * 以前有几处直接读 TokenStore.currentServer() 且不回落到默认值,
 * 结果"没手填过地址"的设备上,更新检查和状态 WS 会直接静默失效。
 */
suspend fun effectiveServerUrl(): String =
    TokenStore.currentServer()?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_SERVER_URL

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** access token 注入 */
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { TokenStore.currentAccess() } ?: return chain.proceed(chain.request())
        return chain.proceed(
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        )
    }
}

object ApiClient {
    @Volatile private var cached: Pair<String, YanzhongApi>? = null
    @Volatile private var cachedAi: Pair<String, YanzhongApi>? = null
    @Volatile private var cachedTransfer: Pair<String, YanzhongApi>? = null
    private val refreshLock = Any()

    /** 普通接口：20s 内必须出结果，卡住就尽快失败给用户反馈 */
    fun api(): YanzhongApi {
        val server = runBlocking { effectiveServerUrl() }
        cached?.let { (s, api) -> if (s == server) return api }
        val api = build(server, callSeconds = 20, connectSeconds = 10, readSeconds = 60, writeSeconds = 10)
        cached = server to api
        return api
    }

    /**
     * 长耗时 AI 接口（AI 面谈 / 生成计划，含长文档生成）。
     *
     * 为什么要单开一个客户端：OkHttp 的 Chain 只能改 connect/read/write 三项超时，
     * 改不了「整次调用」的 callTimeout；而 AI 请求动辄一两分钟，会被 20s 的全局上限直接掐死。
     * 两个客户端共用同一份 token 注入与 401 单飞刷新逻辑，登录态行为保持一致。
     */
    fun aiApi(): YanzhongApi {
        val server = runBlocking { effectiveServerUrl() }
        cachedAi?.let { (s, api) -> if (s == server) return api }
        // 多轮生成流水线(骨架重试 + 逐阶段细化并行)典型 2-3 分钟,最坏 ~10 分钟:
        // call 7 分钟 / read 6.5 分钟,确保客户端不会先于服务端掐断
        val api = build(server, callSeconds = 420, connectSeconds = 10, readSeconds = 390, writeSeconds = 60)
        cachedAi = server to api
        return api
    }

    /**
     * 大包传输接口:导出/备份/恢复都是 7.5MB 级 JSON,20s 全局上限会把正常请求掐死,
     * 服务端恢复还要逐行重插。给足 2 分钟,避免"服务端已提交、客户端先超时"的假失败。
     */
    fun transferApi(): YanzhongApi {
        val server = runBlocking { effectiveServerUrl() }
        cachedTransfer?.let { (s, api) -> if (s == server) return api }
        val api = build(server, callSeconds = 120, connectSeconds = 10, readSeconds = 110, writeSeconds = 60)
        cachedTransfer = server to api
        return api
    }

    private fun build(
        server: String,
        callSeconds: Long,
        connectSeconds: Long,
        readSeconds: Long,
        writeSeconds: Long
    ): YanzhongApi {
        val client = OkHttpClient.Builder()
            .callTimeout(callSeconds, TimeUnit.SECONDS)
            .connectTimeout(connectSeconds, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeSeconds, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor())
            .authenticator { _: Route?, response: Response ->
                if (response.request.url.encodedPath.contains("/auth/refresh")) return@authenticator null
                // 单飞串行化 refresh 轮换:并发 401 时避免多个请求用同一 refresh 各自轮换导致互相吊销
                synchronized(refreshLock) {
                    val refresh = runBlocking { TokenStore.currentRefresh() }
                    if (refresh == null) {
                        null
                    } else {
                        val newTokens = runBlocking {
                            runCatching {
                                val req = Request.Builder()
                                    .url("${server}/api/v1/auth/refresh")
                                    .post(
                                        okhttp3.RequestBody.create(
                                            "application/json".toMediaType(),
                                            json.encodeToString(RefreshReq.serializer(), RefreshReq(refresh))
                                        )
                                    )
                                    .header("No-Auth", "1")
                                    .build()
                                OkHttpClient().newCall(req).execute().use { resp ->
                                    if (resp.isSuccessful) json.decodeFromString(
                                        RefreshResp.serializer(), resp.body?.string() ?: ""
                                    ) else null
                                }
                            }.getOrNull()
                        }?.tokens
                        if (newTokens != null) {
                            runBlocking { TokenStore.saveTokens(newTokens.access, newTokens.refresh) }
                            response.request.newBuilder().header("Authorization", "Bearer ${newTokens.access}").build()
                        } else {
                            runBlocking { TokenStore.clearAll() }
                            null
                        }
                    }
                }
            }
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl("$server/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return retrofit.create(YanzhongApi::class.java)
    }
}
