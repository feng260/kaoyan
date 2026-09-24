package com.yanzhong.app.data.repo

import androidx.room.withTransaction
import com.yanzhong.app.data.db.CountdownNodeEntity
import com.yanzhong.app.data.db.DailyReviewEntity
import com.yanzhong.app.data.db.MonthlyReviewEntity
import com.yanzhong.app.data.db.NodeType
import com.yanzhong.app.data.db.PomodoroSessionEntity
import com.yanzhong.app.data.db.RepeatRule
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.SyncTombstoneEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.db.TaskStatus
import com.yanzhong.app.data.db.WeeklyReviewEntity
import com.yanzhong.app.data.db.YanZhongDatabase
import com.yanzhong.app.data.remote.*
import com.yanzhong.app.util.PersonalPlan
import com.yanzhong.app.util.TimeUtils
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 今日待办视图模型:一次性(含过期) + 重复模板按规则展开(PRD 3.3) */
data class TodayView(
    val open: List<TaskEntity> = emptyList(),
    val done: List<TaskEntity> = emptyList()
)

/** 全量数据交换格式:导出与导入共用(PRD 3.11 数据自有) */
@kotlinx.serialization.Serializable
data class ExportPayload(
    val subjects: List<SubjectEntity>,
    val nodes: List<CountdownNodeEntity>,
    val tasks: List<TaskEntity>,
    val sessions: List<com.yanzhong.app.data.db.PomodoroSessionEntity> = emptyList()
)

/** 导入结果摘要 */
data class ImportResult(
    val subjectsAdded: Int,
    val nodesAdded: Int,
    val tasksAdded: Int,
    val skipped: Int
) {
    val summary: String
        get() = "科目 +$subjectsAdded · 节点 +$nodesAdded · 任务 +$tasksAdded" +
            if (skipped > 0) " · 跳过重复 $skipped" else ""
}

/** 批量添加科目的配色盘(ARGB 长整型,避免仓库层依赖 Compose 颜色类型) */
private val subjectPalette = longArrayOf(
    0xFF4F79E0, 0xFF26A69A, 0xFFEF5350, 0xFF7E57C2,
    0xFFFFA726, 0xFF66BB6A, 0xFFEC407A, 0xFF5C6BC0
)

/** 批量节点缺省目标:30 天后 08:00 */
private fun defaultNodeTarget(): Long =
    TimeUtils.dayStartOf() + 30L * 24 * 3600 * 1000 + 8 * 3600_000L

/** 解析一行节点文本 → (名称, targetAt);名称为空返回 null。日期后缀可省略。 */
private fun parseNodeLine(line: String, zone: ZoneId): Pair<String, Long>? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(Regex("[\\s|，,]+")).filter { it.isNotEmpty() }
    var name = trimmed
    var targetAt = defaultNodeTarget()
    if (parts.size >= 2) {
        val parsed = parseNodeDate(parts.last(), zone)
        if (parsed != null) {
            targetAt = parsed
            name = parts.dropLast(1).joinToString(" ")
        }
    }
    return name.trim() to targetAt
}

/** 解析节点日期:yyyy-MM-dd 或 MM-dd(默认当年);非法返回 null */
private fun parseNodeDate(token: String, zone: ZoneId): Long? = try {
    val full = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(token)
    val short = Regex("^(\\d{1,2})-(\\d{1,2})$").find(token)
    val date = when {
        full != null -> LocalDate.of(
            full.groupValues[1].toInt(), full.groupValues[2].toInt(), full.groupValues[3].toInt()
        )
        short != null -> LocalDate.of(
            LocalDate.now(zone).year, short.groupValues[1].toInt(), short.groupValues[2].toInt()
        )
        else -> return null
    }
    date.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
} catch (_: Exception) {
    null
}

/** 同步 JSON:推送序列化用(编码默认值,墓碑行 isDeleted 显式可见) */
private val syncJson = Json { encodeDefaults = true }

private fun SubjectEntity.stamped(now: Long) = copy(
    clientGuid = clientGuid.ifEmpty { UUID.randomUUID().toString() },
    updatedAt = now,
    dirty = true
)

private fun CountdownNodeEntity.stamped(now: Long) = copy(
    clientGuid = clientGuid.ifEmpty { UUID.randomUUID().toString() },
    updatedAt = now,
    dirty = true
)

private fun TaskEntity.stamped(now: Long) = copy(
    clientGuid = clientGuid.ifEmpty { UUID.randomUUID().toString() },
    updatedAt = now,
    dirty = true
)

private fun PomodoroSessionEntity.stamped(now: Long) = copy(
    clientGuid = clientGuid.ifEmpty { UUID.randomUUID().toString() },
    updatedAt = now,
    dirty = true
)

class StudyRepository(private val db: YanZhongDatabase) {
    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * 本地数据变更信号(自动增量同步的防抖触发源)。
     * 拉取应用走 DAO 直写不发信号,避免「拉取→误判本地改动→回推」的回声循环。
     */
    private val _dirtySignal = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val dirtySignal: SharedFlow<Unit> = _dirtySignal.asSharedFlow()

    /** 非仓储层路径(引擎直写 DB)落库后调用,触发自动同步 */
    fun notifyDataChanged() {
        _dirtySignal.tryEmit(Unit)
    }

    private fun signalDirty() {
        _dirtySignal.tryEmit(Unit)
    }

    /** 删除打墓碑:guid 为空说明该行从未推送过,云端无此行,无需墓碑 */
    private suspend fun tombstone(resource: String, clientGuid: String, now: Long) {
        if (clientGuid.isEmpty()) return
        db.tombstoneDao().insertAll(listOf(SyncTombstoneEntity(clientGuid, resource, now)))
    }

    /** 四张业务表补齐空 guid(SQL 内联 UUID),推送前的兜底 */
    private suspend fun ensureAllGuids() {
        db.subjectDao().fillEmptyGuids()
        db.countdownNodeDao().fillEmptyGuids()
        db.taskDao().fillEmptyGuids()
        db.sessionDao().fillEmptyGuids()
    }

    // ---------- 科目 ----------

    fun observeSubjects(): Flow<List<SubjectEntity>> = db.subjectDao().observeAll()

    suspend fun addSubject(name: String, color: Long) {
        val now = TimeUtils.now()
        val maxSort = db.subjectDao().getAll().maxOfOrNull { it.sort } ?: -1
        db.subjectDao().insert(
            SubjectEntity(name = name, colorArgb = color, sort = maxSort + 1).stamped(now)
        )
        signalDirty()
    }

    suspend fun deleteSubject(subject: SubjectEntity) {
        tombstone("subjects", subject.clientGuid, TimeUtils.now())
        db.subjectDao().delete(subject)
        signalDirty()
    }

    /**
     * 批量录入自定义科目(多行文本,一行一条):同名自动跳过(幂等),
     * 颜色从调色板轮流分配并避开已占用色,返回实际新增数。
     */
    suspend fun batchAddSubjects(text: String): Int {
        val names = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return 0
        val existing = db.subjectDao().getAll()
        val existingNames = existing.map { it.name }.toSet()
        val usedColors = existing.map { it.colorArgb }.toMutableSet()
        var sort = existing.maxOfOrNull { it.sort } ?: -1
        var added = 0
        db.withTransaction {
            val now = TimeUtils.now()
            names.forEach { name ->
                if (name in existingNames) return@forEach
                val color = subjectPalette.firstOrNull { it !in usedColors }
                    ?: subjectPalette[added % subjectPalette.size]
                usedColors.add(color)
                sort++
                db.subjectDao().insert(
                    SubjectEntity(name = name, colorArgb = color, sort = sort).stamped(now)
                )
                added++
            }
        }
        if (added > 0) signalDirty()
        return added
    }

    // ---------- 倒计时节点 ----------

    fun observeNodes(): Flow<List<CountdownNodeEntity>> = db.countdownNodeDao().observeAll()

    fun observePinnedNode(): Flow<CountdownNodeEntity?> = db.countdownNodeDao().observePinned()

    suspend fun addNode(node: CountdownNodeEntity): Long {
        val id = db.countdownNodeDao().insert(node.stamped(TimeUtils.now()))
        signalDirty()
        return id
    }

    suspend fun updateNode(node: CountdownNodeEntity) {
        db.countdownNodeDao().update(node.stamped(TimeUtils.now()))
        signalDirty()
    }

    suspend fun deleteNode(node: CountdownNodeEntity) {
        val now = TimeUtils.now()
        db.withTransaction {
            tombstone("countdownNodes", node.clientGuid, now)
            db.countdownNodeDao().delete(node)
            if (node.pinned) {
                // 删除置顶节点后自动顺延到下一节点(PRD 3.1 验收)
                val next = db.countdownNodeDao().getAll().firstOrNull()
                next?.let { db.countdownNodeDao().setPinned(it.id, now) }
            }
        }
        signalDirty()
    }

    suspend fun pinNode(id: Long) {
        val now = TimeUtils.now()
        db.withTransaction {
            db.countdownNodeDao().clearPinned(now)
            db.countdownNodeDao().setPinned(id, now)
        }
        signalDirty()
    }

    suspend fun toggleNodePin(node: CountdownNodeEntity) {
        val now = TimeUtils.now()
        db.withTransaction {
            if (node.pinned) db.countdownNodeDao().clearPinned(now)
            else {
                db.countdownNodeDao().clearPinned(now)
                db.countdownNodeDao().setPinned(node.id, now)
            }
        }
        signalDirty()
    }

    /**
     * 批量录入倒计时节点(多行文本,一行一条):格式「名称 [日期]」,
     * 日期可写 yyyy-MM-dd 或 MM-dd(默认当年),缺省统一 30 天后 08:00;
     * 同名自动跳过(幂等),类型默认自定义,返回实际新增数。
     */
    suspend fun batchAddNodes(text: String): Int {
        val zone = ZoneId.systemDefault()
        val entries = text.lines()
            .mapNotNull { parseNodeLine(it, zone) }
            .filter { it.first.isNotEmpty() }
        if (entries.isEmpty()) return 0
        val seen = db.countdownNodeDao().getAll().map { it.name }.toMutableSet()
        var added = 0
        db.withTransaction {
            val now = TimeUtils.now()
            entries.forEach { (name, targetAt) ->
                if (!seen.add(name)) return@forEach
                db.countdownNodeDao().insert(
                    CountdownNodeEntity(name = name, type = NodeType.CUSTOM, targetAt = targetAt)
                        .stamped(now)
                )
                added++
            }
        }
        if (added > 0) signalDirty()
        return added
    }

    // ---------- 今日视图 ----------

    fun observeTodayView(): Flow<TodayView> {
        return combine(
            db.taskDao().observeOpenTasks(),
            db.taskDao().observeRepeatTemplates(),
            db.taskDao().observeDoneTasks(),
            tickingNow()
        ) { allOpen, templates, allDone, now ->
            val dayStart = TimeUtils.dayStartOf(now)
            val dayEnd = TimeUtils.dayEndOf(now)
            val todayDow = Instant.ofEpochMilli(now).atZone(zone).dayOfWeek
            val expanded = templates.filter { template ->
                when (template.repeatRule) {
                    RepeatRule.DAILY -> true
                    RepeatRule.WEEKLY -> (template.repeatDays shr (todayDow.value - 1)) and 1 == 1
                    else -> false
                }
            }
            TodayView(
                open = (allOpen.filter { it.repeatRule == RepeatRule.NONE && it.dueAt != null && it.dueAt <= dayEnd } +
                    // 当日已完成的重复模板不再出现在待办(repeatParentId 标记今日 DONE 实例)
                    expanded.filter { template ->
                        allDone.none { it.repeatParentId == template.id && it.completedAt != null && it.completedAt in dayStart..dayEnd }
                    })
                    .sortedWith(compareBy({ it.priority }, { it.dueAt ?: Long.MAX_VALUE })),
                done = allDone.filter { it.completedAt?.let { completedAt -> completedAt in dayStart..dayEnd } == true }
            )
        }
    }

    /** 完成语义:重复模板完成时生成当日实例(带 repeatParentId),模板本身保留(PRD 3.3) */
    suspend fun completeTask(task: TaskEntity) {
        val now = TimeUtils.now()
        if (task.repeatRule == RepeatRule.NONE) {
            db.taskDao().update(task.copy(status = TaskStatus.DONE, completedAt = now).stamped(now))
        } else {
            db.taskDao().insert(
                task.copy(
                    id = 0,
                    dueAt = TimeUtils.dayStartOf(),
                    repeatRule = RepeatRule.NONE,
                    repeatDays = 0,
                    status = TaskStatus.DONE,
                    completedAt = now,
                    repeatParentId = task.id
                ).stamped(now)
            )
        }
        signalDirty()
    }

    suspend fun uncompleteTask(task: TaskEntity) {
        val now = TimeUtils.now()
        when {
            // 重复模板的当日完成实例:直接删除,模板回到待办,不产生幽灵任务
            task.repeatParentId != null -> {
                val from = TimeUtils.dayStartOf()
                val to = TimeUtils.dayEndOf()
                db.withTransaction {
                    // 实例删除前打墓碑,删除动作随增量同步传播到其他端
                    db.taskDao().getRepeatDoneInstances(task.repeatParentId, from, to)
                        .forEach { tombstone("tasks", it.clientGuid, now) }
                    db.taskDao().deleteRepeatDoneInstance(task.repeatParentId, from, to)
                }
            }
            task.repeatRule == RepeatRule.NONE && task.status == TaskStatus.DONE -> {
                db.taskDao().uncomplete(task.id, now)
            }
        }
        signalDirty()
    }

    /**
     * 左滑顺延:推迟到明天同一时刻,postponeCount + 1(PRD 6.5)。
     * 重复模板按规则展开,顺延无意义,直接忽略。
     */
    suspend fun postponeTask(task: TaskEntity) {
        if (task.repeatRule != RepeatRule.NONE) return
        val base = task.dueAt ?: TimeUtils.now()
        val time = Instant.ofEpochMilli(base).atZone(zone).toLocalTime()
        val newDue = LocalDate.now(zone).plusDays(1).atTime(time).atZone(zone)
            .toInstant().toEpochMilli()
        db.taskDao().postpone(task.id, newDue, TimeUtils.now())
        signalDirty()
    }

    /** 撤销顺延:恢复原 dueAt,postponeCount -1(不低于 0) */
    suspend fun undoPostpone(task: TaskEntity, oldDue: Long?) {
        db.taskDao().undoPostpone(task.id, oldDue, TimeUtils.now())
        signalDirty()
    }

    suspend fun insertTask(task: TaskEntity): Long {
        val id = db.taskDao().insert(task.stamped(TimeUtils.now()))
        signalDirty()
        return id
    }

    /**
     * 批量录入今日待办(多行文本,一行一条):标题按关键词自动归入数学/408/英语/政治科目,
     * 无关键词或匹配不到时落默认科目;dueAt 统一定为今天,立即出现在今日待办。
     */
    suspend fun batchInsertTasks(text: String): Int {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return 0
        val subjects = db.subjectDao().getAll()
        val dueAt = TimeUtils.dayStartOf() + 9 * 3600_000L
        var added = 0
        db.withTransaction {
            val now = TimeUtils.now()
            lines.forEach { line ->
                val tag = PersonalPlan.tagOfSubject(line)
                val subject = if (tag == PersonalPlan.TAG_GEN) subjects.firstOrNull()
                else subjects.firstOrNull { PersonalPlan.tagOfSubject(it.name) == tag }
                    ?: subjects.firstOrNull()
                db.taskDao().insert(
                    TaskEntity(
                        subjectId = subject?.id ?: 0L,
                        title = line,
                        priority = 1,
                        pomodoroEstimate = 1,
                        dueAt = dueAt
                    ).stamped(now)
                )
                added++
            }
        }
        if (added > 0) signalDirty()
        return added
    }

    suspend fun updateTask(task: TaskEntity) {
        db.taskDao().update(task.stamped(TimeUtils.now()))
        signalDirty()
    }

    suspend fun deleteTask(task: TaskEntity) {
        tombstone("tasks", task.clientGuid, TimeUtils.now())
        db.taskDao().delete(task)
        signalDirty()
    }

    suspend fun getTask(id: Long): TaskEntity? = db.taskDao().getById(id)

    /** 本周(周一始)任务视图 */
    fun observeWeekView(): Flow<List<TaskEntity>> {
        return combine(db.taskDao().observeOpenTasks(), tickingNow()) { tasks, now ->
            val weekStart = TimeUtils.weekStartOf(now)
            val weekEnd = TimeUtils.weekEndOf(now)
            tasks.filter {
                it.repeatRule == RepeatRule.NONE &&
                    it.dueAt?.let { dueAt -> dueAt in weekStart..weekEnd } == true
            }
                .sortedWith(compareBy({ it.dueAt }, { it.priority }))
        }
    }

    /** 分钟级心跳(今日/周视图跨日自动刷新用),按需 map 成日键可避免高频重组 */
    fun tickingNow(): Flow<Long> = flow {
        while (true) {
            emit(TimeUtils.now())
            kotlinx.coroutines.delay(60_000L)
        }
    }

    /** 全部未完成任务(周期模板 + 一次性 + 里程碑):计划页周视图展开与里程碑概览用 */
    fun observeOpenTasks(): Flow<List<TaskEntity>> = db.taskDao().observeOpenTasks()

    // ---------- 统计 ----------

    fun observeDurationMin(from: Long, to: Long) = db.sessionDao().observeDurationMin(from, to)
    fun observeCount(from: Long, to: Long) = db.sessionDao().observeCount(from, to)
    fun observePerSubject(from: Long, to: Long) = db.sessionDao().observePerSubject(from, to)
    fun observeDailyTrend(from: Long, to: Long) = db.sessionDao().observeDailyTrend(from, to)
    fun observeHourly(from: Long, to: Long) = db.sessionDao().observeHourlyHistogram(from, to)
    fun observeActiveDays(from: Long) = db.sessionDao().observeActiveDays(from)
    fun observeSessions(): Flow<List<com.yanzhong.app.data.db.PomodoroSessionEntity>> =
        db.sessionDao().observeAll()

    suspend fun allSessions() = db.sessionDao().getAll()
    suspend fun allSubjects() = db.subjectDao().getAll()
    suspend fun allTasks() = db.taskDao().getAllTasks()
    suspend fun allNodes() = db.countdownNodeDao().getAll()

    // ---------- 复盘(作战计划雷打不动动作:每日三问 / 周日晚周复盘) ----------

    fun observeDailyReview(date: LocalDate): Flow<DailyReviewEntity?> =
        db.dailyReviewDao().observeByDay(date.toEpochDay().toInt())

    fun observeWeeklyReview(weekStart: LocalDate): Flow<WeeklyReviewEntity?> =
        db.weeklyReviewDao().observeByWeek(weekStart.toEpochDay().toInt())

    fun observeMonthlyReview(monthStart: LocalDate): Flow<MonthlyReviewEntity?> =
        db.monthlyReviewDao().observeByMonth(monthStart.toEpochDay().toInt())

    /** 区间内已完成任务数(周复盘自动盘点用) */
    fun observeDoneCountBetween(from: Long, to: Long): Flow<Int> =
        db.taskDao().observeDoneBetween(from, to).map { it.size }

    /** 保存当日复盘三问:同日重复保存保留首次 createdAt */
    suspend fun saveDailyReview(q1Done: String, q2Weak: String, q3Tomorrow: String) {
        val now = TimeUtils.now()
        val today = LocalDate.now(zone)
        val epochDay = today.toEpochDay().toInt()
        val old = db.dailyReviewDao().getByDay(epochDay)
        db.dailyReviewDao().upsert(
            DailyReviewEntity(
                epochDay = epochDay,
                q1Done = q1Done.trim(),
                q2Weak = q2Weak.trim(),
                q3Tomorrow = q3Tomorrow.trim(),
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
                dirty = true
            )
        )
        signalDirty()
    }

    /** 保存本周复盘(薄弱点 + 下周 3 要事):同周重复保存保留首次 createdAt */
    suspend fun saveWeeklyReview(weakPoints: String, top1: String, top2: String, top3: String) {
        val now = TimeUtils.now()
        val weekStart = LocalDate.now(zone).with(DayOfWeek.MONDAY)
        val key = weekStart.toEpochDay().toInt()
        val old = db.weeklyReviewDao().getByWeek(key)
        db.weeklyReviewDao().upsert(
            WeeklyReviewEntity(
                weekStartEpochDay = key,
                weakPoints = weakPoints.trim(),
                nextWeekTop1 = top1.trim(),
                nextWeekTop2 = top2.trim(),
                nextWeekTop3 = top3.trim(),
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
                dirty = true
            )
        )
        signalDirty()
    }

    /** 保存本月复盘(总结 + 下月 3 要事):同月重复保存保留首次 createdAt */
    suspend fun saveMonthlyReview(summary: String, top1: String, top2: String, top3: String) {
        val now = TimeUtils.now()
        val monthStart = LocalDate.now(zone).withDayOfMonth(1)
        val key = monthStart.toEpochDay().toInt()
        val old = db.monthlyReviewDao().getByMonth(key)
        db.monthlyReviewDao().upsert(
            MonthlyReviewEntity(
                monthStartEpochDay = key,
                summary = summary.trim(),
                nextMonthTop1 = top1.trim(),
                nextMonthTop2 = top2.trim(),
                nextMonthTop3 = top3.trim(),
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
                dirty = true
            )
        )
        signalDirty()
    }

    // ---------- 导入 ----------

    /**
     * 导入全量 JSON(计划包 / 换机恢复)。
     *
     * 合并策略:科目按名称匹配(不存在则新建),任务的 subjectId 重映射到本地科目;
     * 节点与任务按「同名已存在即跳过」保证重复导入同一文件幂等,不产生脏数据。
     */
    suspend fun importJson(json: String): ImportResult {
        val payload = Json { ignoreUnknownKeys = true }
            .decodeFromString<ExportPayload>(json)
        var subjectsAdded = 0
        var nodesAdded = 0
        var tasksAdded = 0
        var skipped = 0

        db.withTransaction {
            val now = TimeUtils.now()
            val subjectIdMap = mutableMapOf<Long, Long>()
            payload.subjects.forEach { subject ->
                val existing = db.subjectDao().getAll().firstOrNull { it.name == subject.name }
                if (existing != null) {
                    subjectIdMap[subject.id] = existing.id
                } else {
                    // 导入的行落新本地 guid(源 guid 可能与云端/本机存量冲突)
                    val newId = db.subjectDao().insert(
                        subject.copy(id = 0, clientGuid = "").stamped(now)
                    )
                    subjectIdMap[subject.id] = newId
                    subjectsAdded++
                }
            }

            val existingNodeNames = db.countdownNodeDao().getAll().map { it.name }.toSet()
            payload.nodes.forEach { node ->
                if (node.name in existingNodeNames) {
                    skipped++
                } else {
                    db.countdownNodeDao().insert(node.copy(id = 0, clientGuid = "").stamped(now))
                    nodesAdded++
                }
            }

            val existingTasks = db.taskDao().getAllTasks()
            val existingTaskTitles = existingTasks.map { it.title }.toSet()
            val taskIdMap = mutableMapOf<Long, Long>()
            payload.tasks.forEach { task ->
                if (task.title in existingTaskTitles) {
                    taskIdMap[task.id] = existingTasks.first { it.title == task.title }.id
                    skipped++
                } else {
                    // 换机/备份恢复时保留原始完成状态与顺延次数,仅重映射科目引用
                    val newId = db.taskDao().insert(
                        task.copy(
                            id = 0,
                            subjectId = subjectIdMap[task.subjectId] ?: task.subjectId,
                            clientGuid = ""
                        ).stamped(now)
                    )
                    taskIdMap[task.id] = newId
                    tasksAdded++
                }
            }

            val existingSessions = db.sessionDao().getAll()
            val importedSessionKeys = mutableSetOf<String>()
            payload.sessions.forEach { session ->
                val imported = session.copy(
                    id = 0,
                    taskId = session.taskId?.let { taskIdMap[it] },
                    subjectId = session.subjectId?.let { subjectIdMap[it] }
                ).stamped(now)
                val sessionKey = "${imported.startedAt}|${imported.endedAt}|${imported.durationMin}|${imported.taskId}|${imported.subjectId}|${imported.planName}"
                val duplicate = existingSessions.any {
                    it.startedAt == imported.startedAt && it.endedAt == imported.endedAt &&
                        it.durationMin == imported.durationMin && it.taskId == imported.taskId &&
                        it.subjectId == imported.subjectId && it.planName == imported.planName
                }
                if (duplicate || !importedSessionKeys.add(sessionKey)) skipped++
                else db.sessionDao().insert(imported)
            }
        }
        if (subjectsAdded + nodesAdded + tasksAdded > 0) signalDirty()
        return ImportResult(subjectsAdded, nodesAdded, tasksAdded, skipped)
    }

    // ---------- 云同步映射 ----------

    /** 本地全量 → 服务端同步行:用实体持久 guid(与增量同步同一 GUID 空间),引用改 *ClientGuid */
    suspend fun buildBackupUpload(): BackupUpload {
        ensureAllGuids()
        val subjects = allSubjects()
        val tasks = allTasks()
        val subjectGuid = subjects.associate { it.id to it.clientGuid }
        val taskGuid = tasks.associate { it.id to it.clientGuid }
        return BackupUpload(
            subjects = subjects.map { s ->
                SubjectSyncRow(
                    clientGuid = s.clientGuid, name = s.name, colorArgb = s.colorArgb,
                    sort = s.sort, archived = s.archived, updatedAt = s.updatedAt
                )
            },
            countdownNodes = allNodes().map { n ->
                CountdownNodeSyncRow(
                    clientGuid = n.clientGuid, name = n.name, type = n.type, targetAt = n.targetAt,
                    pinned = n.pinned, sort = n.sort, updatedAt = n.updatedAt
                )
            },
            tasks = tasks.map { t ->
                TaskSyncRow(
                    clientGuid = t.clientGuid, subjectClientGuid = subjectGuid[t.subjectId],
                    title = t.title, priority = t.priority, pomodoroEstimate = t.pomodoroEstimate,
                    completedPomodoros = t.completedPomodoros, dueAt = t.dueAt, repeatRule = t.repeatRule,
                    repeatDays = t.repeatDays, status = t.status, postponeCount = t.postponeCount,
                    completedAt = t.completedAt, createdAt = t.createdAt, note = t.note, updatedAt = t.updatedAt
                )
            },
            sessions = allSessions().map { s ->
                SessionSyncRow(
                    clientGuid = s.clientGuid,
                    taskClientGuid = s.taskId?.let { taskGuid[it] },
                    subjectClientGuid = s.subjectId?.let { subjectGuid[it] },
                    startedAt = s.startedAt, endedAt = s.endedAt, durationMin = s.durationMin,
                    valid = s.valid, planName = s.planName, abandonReason = s.abandonReason, updatedAt = s.updatedAt
                )
            },
            dailyReviews = db.dailyReviewDao().getAll().map { r ->
                DailyReviewSyncRow(r.epochDay, r.q1Done, r.q2Weak, r.q3Tomorrow, r.createdAt, r.updatedAt)
            },
            weeklyReviews = db.weeklyReviewDao().getAll().map { r ->
                WeeklyReviewSyncRow(
                    r.weekStartEpochDay, r.weakPoints, r.nextWeekTop1, r.nextWeekTop2, r.nextWeekTop3,
                    r.createdAt, r.updatedAt
                )
            },
            monthlyReviews = db.monthlyReviewDao().getAll().map { r ->
                MonthlyReviewSyncRow(
                    r.monthStartEpochDay, r.summary, r.nextMonthTop1, r.nextMonthTop2, r.nextMonthTop3,
                    r.createdAt, r.updatedAt
                )
            }
        )
    }

    /** 服务端备份 → 本地全量覆盖:clientGuid 引用映射回本地 id 并持久 guid(repeatParentId 跨设备无法稳定映射,置空) */
    suspend fun restoreFromBackup(download: BackupDownload) {
        db.withTransaction {
            db.subjectDao().deleteAll()
            db.countdownNodeDao().deleteAll()
            db.taskDao().deleteAll()
            db.sessionDao().deleteAll()
            db.dailyReviewDao().deleteAll()
            db.weeklyReviewDao().deleteAll()
            db.monthlyReviewDao().deleteAll()
            // 本地数据被整包替换,存量墓碑不再有意义
            db.tombstoneDao().deleteAll()

            val subjectIdMap = mutableMapOf<String, Long>()
            download.subjects.forEach { row ->
                val id = db.subjectDao().insert(
                    SubjectEntity(
                        id = 0, name = row.name, colorArgb = row.colorArgb,
                        sort = row.sort, archived = row.archived,
                        clientGuid = row.clientGuid, updatedAt = row.updatedAt, dirty = false
                    )
                )
                subjectIdMap[row.clientGuid] = id
            }
            download.countdownNodes.forEach { row ->
                db.countdownNodeDao().insert(
                    CountdownNodeEntity(
                        id = 0, name = row.name, type = row.type, targetAt = row.targetAt,
                        pinned = row.pinned, sort = row.sort,
                        clientGuid = row.clientGuid, updatedAt = row.updatedAt, dirty = false
                    )
                )
            }
            val taskIdMap = mutableMapOf<String, Long>()
            download.tasks.forEach { row ->
                val id = db.taskDao().insert(
                    TaskEntity(
                        id = 0,
                        subjectId = row.subjectClientGuid?.let { subjectIdMap[it] } ?: 0L,
                        title = row.title, priority = row.priority, pomodoroEstimate = row.pomodoroEstimate,
                        completedPomodoros = row.completedPomodoros, dueAt = row.dueAt,
                        repeatRule = row.repeatRule, repeatDays = row.repeatDays, status = row.status,
                        postponeCount = row.postponeCount, completedAt = row.completedAt,
                        createdAt = row.createdAt, note = row.note, repeatParentId = null,
                        clientGuid = row.clientGuid, updatedAt = row.updatedAt, dirty = false
                    )
                )
                taskIdMap[row.clientGuid] = id
            }
            download.sessions.forEach { row ->
                db.sessionDao().insert(
                    PomodoroSessionEntity(
                        id = 0,
                        taskId = row.taskClientGuid?.let { taskIdMap[it] },
                        subjectId = row.subjectClientGuid?.let { subjectIdMap[it] },
                        startedAt = row.startedAt, endedAt = row.endedAt, durationMin = row.durationMin,
                        valid = row.valid, planName = row.planName, abandonReason = row.abandonReason,
                        clientGuid = row.clientGuid, updatedAt = row.updatedAt, dirty = false
                    )
                )
            }
            download.dailyReviews.forEach { row ->
                db.dailyReviewDao().upsert(
                    DailyReviewEntity(
                        epochDay = row.epochDay, q1Done = row.q1Done, q2Weak = row.q2Weak,
                        q3Tomorrow = row.q3Tomorrow, createdAt = row.createdAt,
                        updatedAt = row.updatedAt, dirty = false
                    )
                )
            }
            download.weeklyReviews.forEach { row ->
                db.weeklyReviewDao().upsert(
                    WeeklyReviewEntity(
                        weekStartEpochDay = row.weekStartEpochDay, weakPoints = row.weakPoints,
                        nextWeekTop1 = row.nextWeekTop1, nextWeekTop2 = row.nextWeekTop2,
                        nextWeekTop3 = row.nextWeekTop3, createdAt = row.createdAt,
                        updatedAt = row.updatedAt, dirty = false
                    )
                )
            }
            download.monthlyReviews.forEach { row ->
                db.monthlyReviewDao().upsert(
                    MonthlyReviewEntity(
                        monthStartEpochDay = row.monthStartEpochDay, summary = row.summary,
                        nextMonthTop1 = row.nextMonthTop1, nextMonthTop2 = row.nextMonthTop2,
                        nextMonthTop3 = row.nextMonthTop3, createdAt = row.createdAt,
                        updatedAt = row.updatedAt, dirty = false
                    )
                )
            }
        }
    }

    // ---------- 增量同步(拉取应用 / 推送收集) ----------

    /** 一批待推送:同资源行 + 推送成功后清除标记用的键(guid 或复盘自然键) */
    class PushBatch(val resource: String, val rows: List<JsonElement>, val keys: List<String>)

    /** 墓碑 → 服务端合法的 isDeleted 行(其余字段为满足 zod 的占位值) */
    private fun tombstoneRow(resource: String, t: SyncTombstoneEntity): JsonElement = when (resource) {
        "subjects" -> syncJson.encodeToJsonElement(
            SubjectSyncRow.serializer(),
            SubjectSyncRow(t.clientGuid, "deleted", 0, 0, false, true, t.deletedAt)
        )
        "countdownNodes" -> syncJson.encodeToJsonElement(
            CountdownNodeSyncRow.serializer(),
            CountdownNodeSyncRow(t.clientGuid, "deleted", 0, 0, false, 0, true, t.deletedAt)
        )
        "tasks" -> syncJson.encodeToJsonElement(
            TaskSyncRow.serializer(),
            TaskSyncRow(
                clientGuid = t.clientGuid, subjectClientGuid = null, title = "deleted", priority = 0,
                pomodoroEstimate = 0, completedPomodoros = 0, dueAt = null, repeatRule = 0,
                repeatDays = 0, status = 0, postponeCount = 0, completedAt = null, createdAt = 0,
                note = "", isDeleted = true, updatedAt = t.deletedAt
            )
        )
        else -> syncJson.encodeToJsonElement(
            SessionSyncRow.serializer(),
            SessionSyncRow(
                clientGuid = t.clientGuid, taskClientGuid = null, subjectClientGuid = null,
                startedAt = 0, endedAt = 0, durationMin = 0, valid = false, planName = "",
                abandonReason = null, isDeleted = true, updatedAt = t.deletedAt
            )
        )
    }

    /** 收集全部待推送内容:脏行 + 墓碑,按资源分批(行内引用改 *ClientGuid) */
    suspend fun collectPush(): List<PushBatch> {
        ensureAllGuids()
        val batches = mutableListOf<PushBatch>()
        val subjects = db.subjectDao().getAll()
        val tasks = db.taskDao().getAllTasks()
        val subjectGuid = subjects.associate { it.id to it.clientGuid }
        val taskGuid = tasks.associate { it.id to it.clientGuid }

        val tombstones = db.tombstoneDao().getAll().groupBy { it.resource }

        db.subjectDao().getDirty().takeIf { it.isNotEmpty() }?.let { dirty ->
            batches += PushBatch(
                "subjects",
                dirty.map { s ->
                    syncJson.encodeToJsonElement(
                        SubjectSyncRow.serializer(),
                        SubjectSyncRow(s.clientGuid, s.name, s.colorArgb, s.sort, s.archived, false, s.updatedAt)
                    )
                },
                dirty.map { it.clientGuid }
            )
        }
        db.countdownNodeDao().getDirty().takeIf { it.isNotEmpty() }?.let { dirty ->
            batches += PushBatch(
                "countdownNodes",
                dirty.map { n ->
                    syncJson.encodeToJsonElement(
                        CountdownNodeSyncRow.serializer(),
                        CountdownNodeSyncRow(n.clientGuid, n.name, n.type, n.targetAt, n.pinned, n.sort, false, n.updatedAt)
                    )
                },
                dirty.map { it.clientGuid }
            )
        }
        db.taskDao().getDirty().takeIf { it.isNotEmpty() }?.let { dirty ->
            batches += PushBatch(
                "tasks",
                dirty.map { t ->
                    syncJson.encodeToJsonElement(
                        TaskSyncRow.serializer(),
                        TaskSyncRow(
                            clientGuid = t.clientGuid, subjectClientGuid = subjectGuid[t.subjectId],
                            title = t.title, priority = t.priority, pomodoroEstimate = t.pomodoroEstimate,
                            completedPomodoros = t.completedPomodoros, dueAt = t.dueAt,
                            repeatRule = t.repeatRule, repeatDays = t.repeatDays, status = t.status,
                            postponeCount = t.postponeCount, completedAt = t.completedAt,
                            createdAt = t.createdAt, note = t.note, isDeleted = false, updatedAt = t.updatedAt
                        )
                    )
                },
                dirty.map { it.clientGuid }
            )
        }
        db.sessionDao().getDirty().takeIf { it.isNotEmpty() }?.let { dirty ->
            batches += PushBatch(
                "sessions",
                dirty.map { s ->
                    syncJson.encodeToJsonElement(
                        SessionSyncRow.serializer(),
                        SessionSyncRow(
                            clientGuid = s.clientGuid, taskClientGuid = s.taskId?.let { taskGuid[it] },
                            subjectClientGuid = s.subjectId?.let { subjectGuid[it] },
                            startedAt = s.startedAt, endedAt = s.endedAt, durationMin = s.durationMin,
                            valid = s.valid, planName = s.planName, abandonReason = s.abandonReason,
                            isDeleted = false, updatedAt = s.updatedAt
                        )
                    )
                },
                dirty.map { it.clientGuid }
            )
        }
        db.dailyReviewDao().getDirty().takeIf { it.isNotEmpty() }?.let { dirty ->
            batches += PushBatch(
                "dailyReviews",
                dirty.map { r ->
                    syncJson.encodeToJsonElement(
                        DailyReviewSyncRow.serializer(),
                        DailyReviewSyncRow(r.epochDay, r.q1Done, r.q2Weak, r.q3Tomorrow, r.createdAt, r.updatedAt)
                    )
                },
                dirty.map { it.epochDay.toString() }
            )
        }
        db.weeklyReviewDao().getDirty().takeIf { it.isNotEmpty() }?.let { dirty ->
            batches += PushBatch(
                "weeklyReviews",
                dirty.map { r ->
                    syncJson.encodeToJsonElement(
                        WeeklyReviewSyncRow.serializer(),
                        WeeklyReviewSyncRow(
                            r.weekStartEpochDay, r.weakPoints, r.nextWeekTop1, r.nextWeekTop2,
                            r.nextWeekTop3, r.createdAt, r.updatedAt
                        )
                    )
                },
                dirty.map { it.weekStartEpochDay.toString() }
            )
        }
        db.monthlyReviewDao().getDirty().takeIf { it.isNotEmpty() }?.let { dirty ->
            batches += PushBatch(
                "monthlyReviews",
                dirty.map { r ->
                    syncJson.encodeToJsonElement(
                        MonthlyReviewSyncRow.serializer(),
                        MonthlyReviewSyncRow(
                            r.monthStartEpochDay, r.summary, r.nextMonthTop1, r.nextMonthTop2,
                            r.nextMonthTop3, r.createdAt, r.updatedAt
                        )
                    )
                },
                dirty.map { it.monthStartEpochDay.toString() }
            )
        }

        // 墓碑并入对应资源批(键即 guid,markPushed 时一并清墓碑)
        val byResource = batches.associateBy { it.resource }.toMutableMap()
        tombstones.forEach { (resource, list) ->
            val existing = byResource[resource]
            if (existing == null) {
                if (list.isNotEmpty()) {
                    val batch = PushBatch(resource, list.map { tombstoneRow(resource, it) }, list.map { it.clientGuid })
                    batches += batch
                    byResource[resource] = batch
                }
            } else {
                val merged = existing.rows + list.map { tombstoneRow(resource, it) }
                val idx = batches.indexOfFirst { it.resource == resource }
                batches[idx] = PushBatch(resource, merged, existing.keys + list.map { it.clientGuid })
            }
        }
        return batches
    }

    /** 推送成功后清脏标记与墓碑;部分失败时整批保留,下轮重推(服务端 LWW 幂等) */
    suspend fun markPushed(resource: String, keys: List<String>) {
        when (resource) {
            "subjects" -> db.subjectDao().markClean(keys)
            "countdownNodes" -> db.countdownNodeDao().markClean(keys)
            "tasks" -> db.taskDao().markClean(keys)
            "sessions" -> db.sessionDao().markClean(keys)
            "dailyReviews" -> db.dailyReviewDao().markClean(keys.map { it.toInt() })
            "weeklyReviews" -> db.weeklyReviewDao().markClean(keys.map { it.toInt() })
            "monthlyReviews" -> db.monthlyReviewDao().markClean(keys.map { it.toInt() })
        }
        db.tombstoneDao().deleteByGuids(keys)
    }

    /** 拉取的变更按 LWW 应用到本地(isDeleted 删本地行;服务端行不覆盖本地更新的脏行),返回应用行数 */
    suspend fun applyPull(c: SyncChanges): Int {
        var applied = 0
        db.withTransaction {
            val subjectGuidToId = mutableMapOf<String, Long>()
            c.subjects.forEach { row ->
                val local = db.subjectDao().getByClientGuid(row.clientGuid)
                when {
                    row.isDeleted && local != null -> {
                        db.subjectDao().deleteByClientGuid(row.clientGuid)
                        applied++
                    }
                    !row.isDeleted && local == null -> {
                        subjectGuidToId[row.clientGuid] = db.subjectDao().insert(
                            SubjectEntity(
                                id = 0, name = row.name, colorArgb = row.colorArgb,
                                sort = row.sort, archived = row.archived,
                                clientGuid = row.clientGuid, updatedAt = row.updatedAt, dirty = false
                            )
                        )
                        applied++
                    }
                    !row.isDeleted && local != null && row.updatedAt > local.updatedAt -> {
                        db.subjectDao().update(
                            local.copy(
                                name = row.name, colorArgb = row.colorArgb, sort = row.sort,
                                archived = row.archived, updatedAt = row.updatedAt, dirty = false
                            )
                        )
                        subjectGuidToId[row.clientGuid] = local.id
                        applied++
                    }
                    else -> local?.let { subjectGuidToId[row.clientGuid] = it.id }
                }
            }
            c.countdownNodes.forEach { row ->
                val local = db.countdownNodeDao().getByClientGuid(row.clientGuid)
                when {
                    row.isDeleted && local != null -> {
                        db.countdownNodeDao().deleteByClientGuid(row.clientGuid)
                        applied++
                    }
                    !row.isDeleted && local == null -> {
                        db.countdownNodeDao().insert(
                            CountdownNodeEntity(
                                id = 0, name = row.name, type = row.type, targetAt = row.targetAt,
                                pinned = row.pinned, sort = row.sort,
                                clientGuid = row.clientGuid, updatedAt = row.updatedAt, dirty = false
                            )
                        )
                        applied++
                    }
                    !row.isDeleted && local != null && row.updatedAt > local.updatedAt -> {
                        db.countdownNodeDao().update(
                            local.copy(
                                name = row.name, type = row.type, targetAt = row.targetAt,
                                pinned = row.pinned, sort = row.sort, updatedAt = row.updatedAt, dirty = false
                            )
                        )
                        applied++
                    }
                    else -> {}
                }
            }
            val taskGuidToId = mutableMapOf<String, Long>()
            c.tasks.forEach { row ->
                val local = db.taskDao().getByClientGuid(row.clientGuid)
                when {
                    row.isDeleted && local != null -> {
                        db.taskDao().deleteByClientGuid(row.clientGuid)
                        applied++
                    }
                    !row.isDeleted && local == null -> {
                        val id = db.taskDao().insert(
                            TaskEntity(
                                id = 0,
                                subjectId = row.subjectClientGuid?.let { subjectGuidToId[it] } ?: 0L,
                                title = row.title, priority = row.priority,
                                pomodoroEstimate = row.pomodoroEstimate,
                                completedPomodoros = row.completedPomodoros, dueAt = row.dueAt,
                                repeatRule = row.repeatRule, repeatDays = row.repeatDays,
                                status = row.status, postponeCount = row.postponeCount,
                                completedAt = row.completedAt, createdAt = row.createdAt, note = row.note,
                                repeatParentId = null,
                                clientGuid = row.clientGuid, updatedAt = row.updatedAt, dirty = false
                            )
                        )
                        taskGuidToId[row.clientGuid] = id
                        applied++
                    }
                    !row.isDeleted && local != null && row.updatedAt > local.updatedAt -> {
                        db.taskDao().update(
                            local.copy(
                                subjectId = row.subjectClientGuid?.let { subjectGuidToId[it] }
                                    ?: local.subjectId,
                                title = row.title, priority = row.priority,
                                pomodoroEstimate = row.pomodoroEstimate,
                                completedPomodoros = row.completedPomodoros, dueAt = row.dueAt,
                                repeatRule = row.repeatRule, repeatDays = row.repeatDays,
                                status = row.status, postponeCount = row.postponeCount,
                                completedAt = row.completedAt, note = row.note,
                                updatedAt = row.updatedAt, dirty = false
                            )
                        )
                        taskGuidToId[row.clientGuid] = local.id
                        applied++
                    }
                    else -> local?.let { taskGuidToId[row.clientGuid] = it.id }
                }
            }
            c.sessions.forEach { row ->
                val local = db.sessionDao().getByClientGuid(row.clientGuid)
                when {
                    row.isDeleted && local != null -> {
                        db.sessionDao().deleteByClientGuid(row.clientGuid)
                        applied++
                    }
                    !row.isDeleted && local == null -> {
                        db.sessionDao().insert(
                            PomodoroSessionEntity(
                                id = 0,
                                taskId = row.taskClientGuid?.let { taskGuidToId[it] },
                                subjectId = row.subjectClientGuid?.let { subjectGuidToId[it] },
                                startedAt = row.startedAt, endedAt = row.endedAt,
                                durationMin = row.durationMin, valid = row.valid, planName = row.planName,
                                abandonReason = row.abandonReason,
                                clientGuid = row.clientGuid, updatedAt = row.updatedAt, dirty = false
                            )
                        )
                        applied++
                    }
                    !row.isDeleted && local != null && row.updatedAt > local.updatedAt -> {
                        db.sessionDao().update(
                            local.copy(
                                taskId = row.taskClientGuid?.let { taskGuidToId[it] } ?: local.taskId,
                                subjectId = row.subjectClientGuid?.let { subjectGuidToId[it] }
                                    ?: local.subjectId,
                                startedAt = row.startedAt, endedAt = row.endedAt,
                                durationMin = row.durationMin, valid = row.valid,
                                planName = row.planName, abandonReason = row.abandonReason,
                                updatedAt = row.updatedAt, dirty = false
                            )
                        )
                        applied++
                    }
                    else -> {}
                }
            }
            c.dailyReviews.forEach { row ->
                val local = db.dailyReviewDao().getByDay(row.epochDay)
                if (local == null || row.updatedAt > local.updatedAt) {
                    db.dailyReviewDao().upsert(
                        DailyReviewEntity(
                            epochDay = row.epochDay, q1Done = row.q1Done, q2Weak = row.q2Weak,
                            q3Tomorrow = row.q3Tomorrow, createdAt = local?.createdAt ?: row.createdAt,
                            updatedAt = row.updatedAt, dirty = false
                        )
                    )
                    applied++
                }
            }
            c.weeklyReviews.forEach { row ->
                val local = db.weeklyReviewDao().getByWeek(row.weekStartEpochDay)
                if (local == null || row.updatedAt > local.updatedAt) {
                    db.weeklyReviewDao().upsert(
                        WeeklyReviewEntity(
                            weekStartEpochDay = row.weekStartEpochDay, weakPoints = row.weakPoints,
                            nextWeekTop1 = row.nextWeekTop1, nextWeekTop2 = row.nextWeekTop2,
                            nextWeekTop3 = row.nextWeekTop3, createdAt = local?.createdAt ?: row.createdAt,
                            updatedAt = row.updatedAt, dirty = false
                        )
                    )
                    applied++
                }
            }
            c.monthlyReviews.forEach { row ->
                val local = db.monthlyReviewDao().getByMonth(row.monthStartEpochDay)
                if (local == null || row.updatedAt > local.updatedAt) {
                    db.monthlyReviewDao().upsert(
                        MonthlyReviewEntity(
                            monthStartEpochDay = row.monthStartEpochDay, summary = row.summary,
                            nextMonthTop1 = row.nextMonthTop1, nextMonthTop2 = row.nextMonthTop2,
                            nextMonthTop3 = row.nextMonthTop3, createdAt = local?.createdAt ?: row.createdAt,
                            updatedAt = row.updatedAt, dirty = false
                        )
                    )
                    applied++
                }
            }
        }
        return applied
    }

    // ---------- WebDAV 备份 ----------

    /** 本地全量 → 备份 JSON(与云同步同一行格式,含日/周/月复盘) */
    suspend fun exportBackupJson(): String {
        val upload = buildBackupUpload()
        val download = BackupDownload(
            version = 2,
            exportedAt = System.currentTimeMillis(),
            subjects = upload.subjects,
            countdownNodes = upload.countdownNodes,
            tasks = upload.tasks,
            sessions = upload.sessions,
            dailyReviews = upload.dailyReviews,
            weeklyReviews = upload.weeklyReviews,
            monthlyReviews = upload.monthlyReviews
        )
        return Json.encodeToString(download)
    }

    /** 备份 JSON → 本地全量覆盖(清空后重插;调用方须在覆盖前弹确认) */
    suspend fun restoreBackupJson(json: String) {
        val download = Json { ignoreUnknownKeys = true }.decodeFromString<BackupDownload>(json)
        restoreFromBackup(download)
    }
}
