package com.yanzhong.app.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 今日节奏排程引擎:与具体考试目标无关的通用排程算法,从原 PersonalPlan 拆出。
 *
 * 职责:把今日待办按科目标签装入日模板的学习槽,逐番茄展开并智能插入休息,
 * 产出首页「今日节奏」卡的行序列。模板由调用方给出(当前是本文件的
 * [defaultTemplate] 中性模板;科目个性化由任务的 tag 决定,槽位本身保持中性)。
 */
object RhythmEngine {

    // ---------- 科目标签(不透明键,仅用于槽位匹配与配色,不直接展示) ----------
    const val TAG_MATH = "数学"
    const val TAG_CS = "408"
    const val TAG_EN = "英语"
    const val TAG_POL = "政治"
    const val TAG_GEN = "通用"
    const val TAG_REST = "休息"

    /** 日模板中的一个时间块:short 非空即学习槽(可装入任务),为空即锚点(休息/三餐等固定块) */
    data class TBlock(
        val time: String,
        val content: String,
        val short: String,
        val tag: String
    )

    /** 一天的具体形态(时间块是骨架,任务量是弹性) */
    data class DayTemplate(
        val id: String,
        val name: String,
        val whenText: String,
        val hours: String,
        val note: String,
        val blocks: List<TBlock>
    ) {
        /** 学习块(去掉锚点),用于无待办时的模板原样展示 */
        fun studyBlocks(): List<TBlock> = blocks.filter { it.tag != TAG_REST }

        /** 全部时间块 */
        fun blocks(): List<TBlock> = blocks
    }

    // ---------- 中性默认模板(工作日/周末) ----------

    /**
     * 不含任何个人课表信息的中性模板:上午 2 槽 + 下午 2 槽 + 晚 2 槽,
     * 午/晚为 REST 锚点。所有学习槽都是 TAG_GEN 通用槽 —— 待办无论什么科目
     * 都能按顺序装入,不会因为「今天没有某科目专属槽」而落空。
     */
    private val weekdayTemplate = DayTemplate(
        id = "weekday",
        name = "标准学习日",
        whenText = "工作日",
        hours = "≈ 9.5 h",
        note = "六个学习槽按待办顺序自动装配;锚点(三餐/休整)保持固定时间。",
        blocks = listOf(
            TBlock("07:30–08:00", "起床 · 早餐", "", TAG_REST),
            TBlock("08:00–10:00", "上午学习 ①", "上午 ①", TAG_GEN),
            TBlock("10:00–12:00", "上午学习 ②", "上午 ②", TAG_GEN),
            TBlock("12:00–14:00", "午餐 · 午休", "", TAG_REST),
            TBlock("14:00–16:00", "下午学习 ①", "下午 ①", TAG_GEN),
            TBlock("16:00–18:00", "下午学习 ②", "下午 ②", TAG_GEN),
            TBlock("18:00–19:00", "晚餐 · 休整", "", TAG_REST),
            TBlock("19:00–21:00", "晚间学习 ①", "晚间 ①", TAG_GEN),
            TBlock("21:00–22:30", "晚间学习 ②(当日收尾)", "晚间 ②", TAG_GEN),
            TBlock("23:00 前", "复盘当日 · 入睡", "", TAG_REST)
        )
    )

    private val weekendTemplate = DayTemplate(
        id = "weekend",
        name = "周末学习日",
        whenText = "周六 / 周日",
        hours = "≈ 8.5 h",
        note = "比工作日晚起半小时,留出运动放空段;槽位装配规则与工作日相同。",
        blocks = listOf(
            TBlock("08:30–09:00", "起床 · 早餐", "", TAG_REST),
            TBlock("09:00–11:00", "上午学习 ①", "上午 ①", TAG_GEN),
            TBlock("11:00–12:00", "上午学习 ②", "上午 ②", TAG_GEN),
            TBlock("12:00–14:00", "午餐 · 午休", "", TAG_REST),
            TBlock("14:00–16:00", "下午学习 ①", "下午 ①", TAG_GEN),
            TBlock("16:00–17:30", "下午学习 ②", "下午 ②", TAG_GEN),
            TBlock("17:30–19:00", "晚餐 · 运动 · 放空", "", TAG_REST),
            TBlock("19:00–21:00", "晚间学习 ①", "晚间 ①", TAG_GEN),
            TBlock("21:00–22:00", "晚间学习 ②(错题/复盘等轻任务)", "晚间 ②", TAG_GEN),
            TBlock("23:00 前", "复盘当日 · 入睡", "", TAG_REST)
        )
    )

    /** 按星期给出中性默认模板:周末晚起、节奏稍缓,其余走标准学习日 */
    fun defaultTemplate(dayOfWeek: DayOfWeek): DayTemplate = when (dayOfWeek) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> weekendTemplate
        else -> weekdayTemplate
    }

    // ---------- 今日节奏(待办驱动排程) ----------

    /** 今日节奏的一行:任务行(时长 = 番茄数 × focusMin)或锚点行(休息/三餐等固定块) */
    data class RhythmRow(
        val time: String,
        val label: String,
        val tag: String,
        val minutes: Int,
        val isTask: Boolean,
        /** 任务行回指待办 taskId:供今日待办卡同步显示节奏时段 */
        val taskId: Long? = null,
        /** 算法自动插入的休息行(小憩/长休),区别于模板锚点 */
        val isAutoBreak: Boolean = false
    )

    /** 参与排程的待办任务:科目标签 + 标题 + 番茄数 */
    data class RhythmTask(
        val tag: String,
        val title: String,
        val pomodoros: Int,
        val taskId: Long = 0
    )

    // ---------- 智能休息(工作-休息科学配比) ----------

    /** 连续专注后的小憩时长(分钟):记忆巩固微休 */
    const val AUTO_MICRO_BREAK_MIN = 5

    /** 连续 4 个番茄(≈100 分钟)后的长休时长(分钟):对齐超日节律(90–120min) */
    const val AUTO_LONG_BREAK_MIN = 15

    /** 高强度认知科目:更早插入休息(每 2 个番茄) */
    private val highLoadTags = setOf(TAG_MATH, TAG_CS)

    /**
     * 智能休息插入算法:任务时长内自动穿插休息。
     * - 每 breakEvery 个连续番茄插一次 [AUTO_MICRO_BREAK_MIN] 分钟小憩;
     *   高强度科目(数学/计算机专业课)每 2 个,低强度(语言/记忆/通用)每 3 个;
     * - 连续第 4 个番茄(约 100 分钟)后插 [AUTO_LONG_BREAK_MIN] 分钟长休(超日节律);
     * - 最后一个番茄后不插休息(衔接模板锚点)。
     */
    fun breakIntervalFor(tag: String): Int = if (tag in highLoadTags) 2 else 3

    /** 任务含休息的总占用(分钟):🍅数 × focusMin + 小憩/长休 */
    fun planMinutesOfFull(pomodoros: Int, tag: String, focusMinutes: Int): Int {
        if (pomodoros <= 0) return 0
        val interval = breakIntervalFor(tag)
        var minutes = pomodoros * focusMinutes
        for (i in 1 until pomodoros) { // 番茄间(最后一个后不插)
            minutes += when {
                i % 4 == 0 -> AUTO_LONG_BREAK_MIN
                i % interval == 0 -> AUTO_MICRO_BREAK_MIN
                else -> 0
            }
        }
        return minutes
    }

    /** 科目名 → 学科标签(关键词与 SubjectArtwork 场景匹配保持一致) */
    fun tagOfSubject(name: String): String {
        val n = name.trim()
        return when {
            n.contains("数学") || n.contains("高数") || n.contains("线代") || n.contains("概率") -> TAG_MATH
            n.contains("408") || n.contains("数据结构") || n.contains("组成") || n.contains("操作") ||
                n.contains("网络") || n.contains("数据库") || n.contains("编译") || n.contains("计算机") ||
                n.contains("专业课") || n.contains("人工智能") -> TAG_CS
            n.contains("英语") -> TAG_EN
            n.contains("政治") -> TAG_POL
            else -> TAG_GEN
        }
    }

    private val hmPattern = Regex("""\d{1,2}:\d{2}""")
    private val hmFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** "09:30–10:05" → 09:30 起 10:05 止;"21:30 闭馆" → 仅起点;"上午" → null */
    private fun parseBlockRange(time: String): Pair<LocalTime, LocalTime?>? {
        val ms = hmPattern.findAll(time).toList()
        if (ms.isEmpty()) return null
        fun at(i: Int) = LocalTime.parse(ms[i].value, hmFormatter)
        return at(0) to (if (ms.size >= 2) at(1) else null)
    }

    /** 时间块的分钟时长(无终点时为 null) */
    private fun rangeMinutesOf(time: String): Int? {
        val (start, end) = parseBlockRange(time) ?: return null
        return end?.let { (it.toSecondOfDay() - start.toSecondOfDay()) / 60 }
    }

    /**
     * 用今日待办的实际番茄数重排今日节奏,让每个任务的时间与今日待办(🍅数 × focusMin)完全一致:
     * 模板中带 short 摘要的块视为学习槽——同科目任务按待办顺序装入(每槽至少 1 个,之后装到模板容量,
     * 溢出进同科目下一槽);无对应科目槽的任务进入 TAG_GEN 通用槽,仍无处安放的
     * 并入最后一个学习槽顺延。休息/三餐等锚点块保持模板时间,学习槽起点 = max(模板起点, 上一块结束)。
     * 今日无待办时回退为模板原样展示。
     */
    fun buildTodayRhythm(
        template: DayTemplate,
        date: LocalDate,
        tasks: List<RhythmTask>,
        focusMinutes: Int = 25
    ): List<RhythmRow> {
        val blocks = template.blocks()
        val study = tasks.filter { it.pomodoros > 0 }
        val slotIdxs = blocks.indices.filter { blocks[it].short.isNotEmpty() }
        if (study.isEmpty() || slotIdxs.isEmpty()) {
            return template.studyBlocks().map {
                RhythmRow(it.time, it.short.ifEmpty { it.content }, it.tag, rangeMinutesOf(it.time) ?: 0, isTask = false)
            }
        }

        val assigned = mutableMapOf<Int, MutableList<RhythmTask>>()
        val leftovers = mutableListOf<RhythmTask>()
        fun fill(slot: Int, taken: List<RhythmTask>) {
            assigned.getOrPut(slot) { mutableListOf() } += taken
        }

        // ① 同科目:每槽至少 1 个,之后装到模板容量(含算法休息占用),溢出进同科目下一槽
        for ((tag, tagTasks) in study.groupBy { it.tag }) {
            val slots = slotIdxs.filter { blocks[it].tag == tag }
            if (slots.isEmpty()) {
                leftovers += tagTasks
                continue
            }
            var i = 0
            for (s in slots) {
                if (i >= tagTasks.size) break
                val capacity = rangeMinutesOf(blocks[s].time) ?: 60
                val taken = mutableListOf<RhythmTask>()
                var filled = 0
                while (i < tagTasks.size) {
                    val m = planMinutesOfFull(tagTasks[i].pomodoros, tag, focusMinutes)
                    if (taken.isNotEmpty() && filled + m > capacity) break
                    taken += tagTasks[i]
                    filled += m
                    i++
                }
                fill(s, taken)
            }
            if (i < tagTasks.size) fill(slots.last(), tagTasks.subList(i, tagTasks.size))
        }

        // ② 无同科目槽的任务(如当天模板没有该科目的专属槽)装入 TAG_GEN 通用槽
        if (leftovers.isNotEmpty()) {
            for (s in slotIdxs.filter { blocks[it].tag == TAG_GEN }) {
                if (leftovers.isEmpty()) break
                val capacity = rangeMinutesOf(blocks[s].time) ?: 60
                val taken = mutableListOf<RhythmTask>()
                var filled = 0
                while (leftovers.isNotEmpty()) {
                    val task = leftovers.first()
                    val m = planMinutesOfFull(task.pomodoros, task.tag, focusMinutes)
                    if (taken.isNotEmpty() && filled + m > capacity) break
                    taken += leftovers.removeAt(0)
                    filled += m
                }
                fill(s, taken)
            }
        }

        // ③ 仍无处安放:并入最后一个学习槽顺延
        if (leftovers.isNotEmpty()) fill(slotIdxs.last(), leftovers)

        // ④ 顺序重排:锚点保持模板时间;学习槽逐番茄排程并智能插入休息
        //    (每个番茄一段,番茄间按科目强度插入小憩/长休,工作时长时间一目了然)
        val rows = mutableListOf<RhythmRow>()
        var cursor: LocalTime? = null
        blocks.forEachIndexed { idx, block ->
            val range = parseBlockRange(block.time)
            if (block.short.isEmpty()) {
                rows += RhythmRow(block.time, block.content, block.tag, 0, isTask = false)
                range?.let { cursor = it.second ?: it.first }
                return@forEachIndexed
            }
            val slotTasks = assigned[idx] ?: return@forEachIndexed
            val floor = range?.first
            var t = when {
                floor != null -> maxOf(cursor ?: floor, floor)
                cursor != null -> cursor
                else -> return@forEachIndexed
            }
            slotTasks.forEach { task ->
                val interval = breakIntervalFor(task.tag)
                val taskId = if (task.taskId != 0L) task.taskId else null
                for (i in 1..task.pomodoros) {
                    val end = t.plusMinutes(focusMinutes.toLong())
                    rows += RhythmRow(
                        time = "${t.format(hmFormatter)}–${end.format(hmFormatter)}",
                        label = if (task.pomodoros > 1) "${task.title} · ${i}/${task.pomodoros}"
                        else task.title,
                        tag = task.tag,
                        minutes = focusMinutes,
                        isTask = true,
                        taskId = taskId
                    )
                    t = end
                    // 番茄间智能休息:最后一个番茄后不插(衔接模板锚点)
                    if (i < task.pomodoros) {
                        val breakLabel: String?
                        val breakMin: Int
                        when {
                            i % 4 == 0 -> { breakLabel = "长休 $AUTO_LONG_BREAK_MIN 分钟"; breakMin = AUTO_LONG_BREAK_MIN }
                            i % interval == 0 -> { breakLabel = "小憩 $AUTO_MICRO_BREAK_MIN 分钟"; breakMin = AUTO_MICRO_BREAK_MIN }
                            else -> { breakLabel = null; breakMin = 0 }
                        }
                        if (breakLabel != null) {
                            val bend = t.plusMinutes(breakMin.toLong())
                            rows += RhythmRow(
                                time = "${t.format(hmFormatter)}–${bend.format(hmFormatter)}",
                                label = breakLabel,
                                tag = TAG_REST,
                                minutes = breakMin,
                                isTask = false,
                                isAutoBreak = true
                            )
                            t = bend
                        }
                    }
                }
            }
            cursor = t
        }
        return rows
    }

    /**
     * 今日节奏中当前时刻所处的行下标(时间覆盖 now):
     * 任务行有自己的起止;锚点行取模板区间,仅有起点时结束取下一行起点(无则 +60min)。
     * 用于首页节奏卡高亮「现在该做什么」。无匹配返回 -1。
     */
    fun activeRhythmIndex(rows: List<RhythmRow>, now: LocalTime): Int {
        val nowSec = now.toSecondOfDay()
        val starts = rows.map { parseBlockRange(it.time)?.first?.toSecondOfDay() }
        rows.forEachIndexed { i, row ->
            val range = parseBlockRange(row.time) ?: return@forEachIndexed
            val start = range.first.toSecondOfDay()
            val end = range.second?.toSecondOfDay()
                // 仅起点块:延伸到下一可解析行的起点,否则 60 分钟
                ?: starts.drop(i + 1).firstOrNull { it != null && it > start }
                ?: (start + 60 * 60)
            if (nowSec >= start && nowSec < end) return i
        }
        return -1
    }
}
