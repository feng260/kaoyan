package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.AdjustmentOptionDto
import com.yanzhong.app.data.remote.BriefSubjectPatch
import com.yanzhong.app.data.remote.BudgetDeficitDto
import com.yanzhong.app.data.remote.PlanBriefDto
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/**
 * 服务端错误信封 { error, message, details }：
 * error 是稳定业务码，message 是给考生看的中文说明，details 视业务码携带结构化字段。
 */
data class PlanServerError(
    val code: String,
    val message: String,
    val details: JsonObject? = null
)

private val planErrorJson = Json { ignoreUnknownKeys = true }

/** 解析错误体；任何一步失败都当作「没有服务端说明」，由调用方按 HTTP 状态码兜底 */
fun Throwable.planServerError(): PlanServerError? {
    val http = this as? HttpException ?: return null
    val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: return null
    val obj = runCatching { planErrorJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val code = obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    return PlanServerError(
        code = code,
        message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        details = obj["details"] as? JsonObject
    )
}

/** 业务码 → 考生能照着做的提示；拿不准的码原样透出服务端说明 */
fun PlanServerError.friendlyPlanError(): String? = when (code) {
    "PLAN_BRIEF_CHANGED" -> "计划口径刚被改过，重新确认一下再生成"
    "PLAN_CAPACITY_INSUFFICIENT" -> message.ifBlank { "考前可用时间不足以覆盖已确认的剩余任务" }
    "PLAN_DAY_OVERLOADED" -> message.ifBlank { "当天时间不够，先缩短或挪走一项" }
    "PLAN_ITEM_NOT_EDITABLE" -> message.ifBlank { "这条任务现在不能改" }
    "PLAN_ITEM_NOT_FOUND" -> "这条任务已不存在，刷新后再试"
    "LOCKED_ITEMS_CONFLICT" -> "锁定任务和这次调整冲突，需要先解锁"
    "ADJUSTMENT_UNDO_STALE" -> "调整后你手工改过计划，撤销会覆盖这些修改；请重新描述一次调整"
    "ADJUSTMENT_STALE" -> "计划刚被改过，这张调整单已失效"
    "ADJUSTMENT_NOT_FOUND" -> "调整单不存在，可能已经确认或撤销过了"
    "ADJUSTMENT_OPTION_NOT_FOUND" -> "这个调整方案已失效，重新描述一次即可"
    "PLAN_GENERATION_RUNNING" -> "计划正在生成，完成后再改口径"
    "PLANNING_FACTS_INCOMPLETE" -> message.ifBlank { "还有关键信息没确认，先在对话里补上" }
    "PROFILE_INCOMPLETE" -> "请先完成备考问卷，再生成计划"
    "PLAN_PROFILE_CHANGED", "PLAN_PROGRESS_CHANGED" -> "备考档案或进度已变化，请重新生成草稿"
    "PLAN_GENERATION_FAILED" -> message.ifBlank { "这次生成的计划不合规，可回聊天补充信息后重试" }
    "PLAN_INTERVIEW_FAILED" -> message.ifBlank { "AI 这次没接上，稍后重试" }
    "AI_NOT_CONFIGURED", "AI_VISION_NOT_CONFIGURED" -> message.ifBlank { "服务端还没配置好 AI，请稍后再试" }
    "PLAN_NOT_FOUND" -> "相关计划已失效，请重新生成"
    else -> message.takeIf { it.isNotBlank() }
}

/** details 里的整数字段；缺失或类型不对都返回 null */
fun PlanServerError.detailInt(key: String): Int? =
    runCatching { details?.get(key)?.jsonPrimitive?.intOrNull }.getOrNull()

fun PlanServerError.detailString(key: String): String? =
    runCatching { details?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()

/** 预算差异的展示文案：持平 / 多 1 小时 30 分钟 / 少 45 分钟 */
internal fun formatDeltaMinutes(minutes: Int): String = when {
    minutes == 0 -> "持平"
    minutes > 0 -> "多 ${formatMinutesCn(minutes)}"
    else -> "少 ${formatMinutesCn(abs(minutes))}"
}

/**
 * 一个调整方案的影响指标，按「影响面 → 变动量 → 负荷/风险」的顺序给 0–5 条，
 * 让考生不用展开明细就能比较几套方案。
 */
internal fun optionImpactLines(option: AdjustmentOptionDto): List<String> = buildList {
    val impact = option.impact
    if (impact.affectedDays > 0) add("涉及 ${impact.affectedDays} 天")
    if (impact.movedCount > 0) add("移动 ${impact.movedCount} 项")
    if (impact.addedCount > 0) add("新增 ${impact.addedCount} 项")
    if (impact.removedCount > 0) add("删除 ${impact.removedCount} 项")
    val delta = impact.totalMinutesAfter - impact.totalMinutesBefore
    if (delta < 0) add("总量减少 ${formatMinutesCn(-delta)}")
    if (delta > 0) add("总量增加 ${formatMinutesCn(delta)}")
    if (impact.maxDayCapacity > 0) add("最大日负荷 ${impact.maxDayLoad}/${impact.maxDayCapacity} 分钟")
    if (impact.shortfallMinutes > 0) add("仍缺口 ${formatMinutesCn(impact.shortfallMinutes)}")
    if (impact.milestoneRiskCount > 0) add("里程碑风险 ${impact.milestoneRiskCount} 项")
}

/** PLAN_CAPACITY_INSUFFICIENT 的结构化缺口:可用容量、需要容量与逐科缺额 */
data class PlanCapacityIssue(
    val availableMinutes: Int,
    val requiredMinutes: Int,
    val deficits: List<BudgetDeficitDto> = emptyList()
)

fun PlanServerError.capacityIssue(): PlanCapacityIssue? {
    val available = detailInt("availableMinutes") ?: return null
    val required = detailInt("requiredMinutes") ?: return null
    val deficits = runCatching {
        val node = details?.get("deficits") ?: return@runCatching emptyList<BudgetDeficitDto>()
        planErrorJson.decodeFromJsonElement<List<BudgetDeficitDto>>(node)
    }.getOrDefault(emptyList())
    return PlanCapacityIssue(available, required, deficits)
}

/**
 * 生成耗时预估(分钟):按计划跨度线性放大,夹在 8–30 之间;
 * 上下浮动 5 分钟作为展示区间,避免给考生一个精确到分钟却经常失准的承诺。
 */
internal fun etaRange(horizonDays: Int): IntRange {
    val center = (horizonDays.coerceAtLeast(0) / 25.0).roundToInt().coerceIn(8, 30)
    return (center - 5).coerceAtLeast(8)..(center + 5).coerceAtMost(30)
}

/** 已用时长 mm:ss;超过一小时显示 h:mm:ss */
internal fun formatElapsed(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    else "$minutes:${seconds.toString().padStart(2, '0')}"
}

/** 口径确认页里对单科的本地编辑 */
internal data class BriefSubjectEdit(
    val remainingMinutes: Int,
    val milestoneDate: String,
    val estimated: Boolean
)

/** 只把真正改过的字段放进 PATCH,避免把未触碰的字段重新写一遍 */
internal fun briefPatchFromEdits(
    original: PlanBriefDto,
    edits: Map<String, BriefSubjectEdit>
): List<BriefSubjectPatch> = original.examSubjects.mapNotNull { subject ->
    val edit = edits[subject.name] ?: return@mapNotNull null
    val patch = BriefSubjectPatch(
        name = subject.name,
        remainingMinutes = edit.remainingMinutes.takeIf { it != subject.remainingMinutes },
        milestoneDate = edit.milestoneDate.takeIf { it != subject.milestoneDate },
        estimated = edit.estimated.takeIf { it != subject.estimated }
    )
    patch.takeIf {
        it.remainingMinutes != null || it.milestoneDate != null || it.estimated != null
    }
}