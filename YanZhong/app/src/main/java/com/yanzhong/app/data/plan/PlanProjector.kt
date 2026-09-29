package com.yanzhong.app.data.plan

import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.db.TaskStatus
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.util.PersonalPlan
import com.yanzhong.app.data.remote.PlanItemDto
import java.time.LocalDate
import java.time.ZoneId

/**
 * 把服务端计划项幂等投影为本地任务列表。
 *
 * 约束:
 * - 只处理当前账号下、来源键属于本次计划的任务,手工任务与其他账号的投影原样保留。
 * - 同一来源键重复投影复用同一条任务；完成状态以服务端为准，保留本地番茄数。
 * - 已完成任务保留其本地 ID 与番茄关联；失效的未完成任务会被移除。
 */
fun TaskEntity.isServerPlanItem(): Boolean = planItemId != null

fun projectPlanTasks(
    existing: List<TaskEntity>,
    plan: PlanDto,
    accountGuid: String,
    subjectIds: Map<String, Long>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TaskEntity> {
    val staleIds = stalePlanTaskIds(existing, accountGuid, plan).toSet()
    val result = mutableListOf<TaskEntity>()
    val projected = mutableSetOf<Long>()

    existing.forEach { task ->
        if (task.id in staleIds) return@forEach
        val isCurrentPlan = task.planId == plan.id && task.accountGuid == accountGuid
        val item = if (isCurrentPlan) plan.items.firstOrNull { it.id == task.planItemId } else null
        if (item == null) {
            result += task
        } else {
            result += projectedTask(item, task, plan, accountGuid, subjectIds, now, zone)
            projected += item.id
        }
    }

    plan.items.forEach { item ->
        if (item.id !in projected) {
            result += projectedTask(item, null, plan, accountGuid, subjectIds, now, zone)
        }
    }

    return result
}

/**
 * 找出当前账号下已经失效的计划投影任务 id。
 *
 * 失效定义:来源键属于该账号,但既不属于本次计划版本,也不属于本次计划项。
 * 其他账号的计划投影和全部手工任务都不受影响。
 */
fun stalePlanTaskIds(
    existing: List<TaskEntity>,
    accountGuid: String,
    plan: PlanDto,
): List<Long> {
    val keep = plan.items.map { it.id }.toSet()
    return existing
        .filter { it.planId != null && it.accountGuid == accountGuid && it.status != TaskStatus.DONE }
        .filter { it.planId != plan.id || it.planItemId !in keep }
        .map { it.id }
}

private fun projectedTask(
    item: PlanItemDto,
    previous: TaskEntity?,
    plan: PlanDto,
    accountGuid: String,
    subjectIds: Map<String, Long>,
    now: Long,
    zone: ZoneId,
): TaskEntity {
    val dueAt = parsePlanDate(item.planDate, zone)
    val base = previous ?: TaskEntity(
        subjectId = subjectIds[item.subject] ?: 0L,
        title = item.title,
        priority = item.priority,
        dueAt = dueAt,
        accountGuid = accountGuid,
        planId = plan.id,
        planItemId = item.id,
        createdAt = now,
    )
    return base.copy(
        subjectId = subjectIds[item.subject] ?: base.subjectId,
        title = item.title,
        priority = item.priority,
        dueAt = dueAt,
        accountGuid = accountGuid,
        planId = plan.id,
        planItemId = item.id,
        status = if (item.status == "done") TaskStatus.DONE else TaskStatus.TODO,
        completedAt = if (item.status == "done") item.completedAt ?: base.completedAt ?: dueAt else null,
        updatedAt = now,
        dirty = false,
    )
}

fun resolvePlanSubjectId(name: String, subjects: List<SubjectEntity>): Long {
    if (name.isBlank()) return 0L
    subjects.firstOrNull { it.name == name }?.let { return it.id }
    val tag = PersonalPlan.tagOfSubject(name)
    if (tag == PersonalPlan.TAG_GEN) return 0L
    return subjects.firstOrNull { PersonalPlan.tagOfSubject(it.name) == tag }?.id ?: 0L
}

private fun parsePlanDate(planDate: String, zone: ZoneId): Long? =
    runCatching {
        LocalDate.parse(planDate).atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull()
