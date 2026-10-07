package com.yanzhong.app.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

// ---------- DTO ----------

@Serializable
data class UserDto(val guid: String, val username: String, val email: String? = null, val createdAt: Long = 0)

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
 * [termsAccepted] / [privacyAccepted] 是服务端的必填项：它既用来留档（同意时间点），
 * 也是"数据属于我自己"这句话的凭证。客户端在提交前会明确校验这两个布尔值，
 * 而不是靠服务端 400 才弹提示——那样用户填了半天密码才被拒，很败好感。
 */
@Serializable
data class RegisterReq(
    val username: String,
    val password: String,
    val email: String? = null,
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

@Serializable
data class ForgotReq(val account: String)

@Serializable
data class ResetReq(val token: String, val newPassword: String)

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
    val availabilityConfirmed: Boolean = false,
    val commitmentsConfirmed: Boolean = false
)

@Serializable
data class ProfileResp(val profile: ProfileDto? = null, val serverTime: Long = 0)

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
    val completedAt: Long? = null
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
data class PlanResp(val plan: PlanDto? = null, val serverTime: Long = 0)

/** 勾选计划项的 slim 响应:只回被改的那一项与进度(全量 plan 数百 KB,勾选是高频操作) */
@Serializable
data class SlimToggleResp(
    val item: PlanItemDto,
    val progress: PlanProgressDto = PlanProgressDto(),
    val serverTime: Long = 0
)

/** 长文档轻量响应:文档页只需要这份 8 章文档,不拉全量 items */
@Serializable
data class PlanDocumentResp(val document: PlanDocumentDto? = null, val serverTime: Long = 0)

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

/** POST /account/delete 的请求体：confirm 必须与用户名完全一致，password 是本人密码 */
@Serializable
data class DeleteAccountReq(val confirm: String, val password: String)

@Serializable
data class DeleteAccountResp(val deleted: Int = 0, val serverTime: Long = 0)

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
data class BriefCommitmentDto(val weekday: Int, val start: String, val end: String, val label: String)

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
    val to: AdjustmentFieldDto? = null
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
    val createdAt: Long? = null,
    val appliedAt: Long? = null
)

@Serializable
data class AdjustReq(val message: String)

@Serializable
data class AdjustResp(
    val adjustment: AdjustmentDto? = null,
    val reply: String? = null,
    val plan: PlanDto? = null,
    val serverTime: Long = 0
)

@Serializable
data class LatestAdjustmentResp(val adjustment: AdjustmentDto? = null, val serverTime: Long = 0)

@Serializable
data class CheckupResp(
    val behindMinutes: Long = 0,
    val overdueCount: Long = 0,
    val suggestion: String = "",
    val serverTime: Long = 0
)

// ---------- API 接口 ----------

interface YanzhongApi {
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
    suspend fun restoreBackup(@Body payload: BackupUpload): SyncResult

    /** 增量拉取:updated_at > since 的行(含墓碑);since=0 全量 */
    @GET("api/v1/sync")
    suspend fun pullChanges(@retrofit2.http.Query("since") since: Long): PullResp

    /** 批量推送:同资源脏行 + 墓碑行(isDeleted=true),服务端 LWW 幂等 upsert */
    @POST("api/v1/sync/{resource}")
    suspend fun pushResource(
        @retrofit2.http.Path("resource") resource: String,
        @Body rows: List<kotlinx.serialization.json.JsonElement>
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

    /**
     * 依据当前档案生成待确认草稿；旧计划保持生效，直到调用 confirmPlan。
     * 该接口可能耗时一两分钟（含长文档生成），调用方请走 [ApiClient.aiApi]。
     */
    @POST("api/v1/plans/generate")
    suspend fun generatePlan(): PlanResp

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

    /** 历史计划列表（含当前 active 与所有 archived），按版本从新到旧，只返回摘要 */
    @GET("api/v1/plans/history")
    suspend fun getPlanHistory(): PlanHistoryResp

    /** 按 id 读取某一版计划的完整明细（含 stages/items），用于历史详情只读查看 */
    @GET("api/v1/plans/{id}")
    suspend fun getPlanById(@Path("id") id: Long): PlanResp

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
        @retrofit2.http.Query("slim") slim: Boolean = true,
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

    @POST("api/v1/plans/adjustments/{id}/confirm")
    suspend fun confirmAdjustment(@Path("id") id: Long): PlanResp

    @POST("api/v1/plans/adjustments/{id}/undo")
    suspend fun undoAdjustment(@Path("id") id: Long): PlanResp

    /** 计划体检:落后分钟数/过期任务数/一句话建议(suggestion 为空表示不用提醒) */
    @GET("api/v1/plans/checkup")
    suspend fun planCheckup(): CheckupResp

    /**
     * 全量导出账号数据。直接返回服务端 JSON（含账号、档案、计划、同步数据），
     * 客户端原样落盘即可，不需要理解内部结构——所以这里用 JsonElement 而不是建一堆 DTO。
     */
    @GET("api/v1/account/export")
    suspend fun exportAccount(): kotlinx.serialization.json.JsonElement

    /** 注销账号：不可撤销，服务端要求「完整用户名 + 密码」双重确认 */
    @POST("api/v1/account/delete")
    suspend fun deleteAccount(@Body body: DeleteAccountReq): DeleteAccountResp
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

    /** 专注状态 WebSocket:登录后连接,重连成功后回调(hello + presence 恢复) */
    fun statusWs(
        token: String,
        deviceName: String,
        onEvent: (String) -> Unit,
        onOpened: () -> Unit
    ): WebSocket {
        val server = runBlocking { effectiveServerUrl() }
        val wsUrl = server.replaceFirst("http", "ws") + "/ws?token=" + token
        val client = OkHttpClient()
        return client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"hello","deviceName":"$deviceName"}""")
                    onOpened()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onEvent(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onEvent("""{"type":"error","message":"${t.message ?: "连接断开"}"}""")
                }
            }
        )
    }
}
