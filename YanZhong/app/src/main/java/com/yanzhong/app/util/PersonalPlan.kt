package com.yanzhong.app.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 《468 天考研全程作战计划》个人定制数据源:
 * 六套日模板 / 本学期课表 / 四科全程规划 / 启动四周 / 资料清单 / 十条军规 / 关键日期。
 * 计划内容调整时同步更新本文件;阶段骨架日期在 [Phases] 中维护。
 */
object PersonalPlan {

    // ---------- 科目标签 ----------
    const val TAG_MATH = "数学"
    const val TAG_CS = "408"
    const val TAG_EN = "英语"
    const val TAG_POL = "政治"
    const val TAG_GEN = "通用"
    const val TAG_REST = "休息"
    const val TAG_COURSE = "课程"

    /** 日模板中的一个时间块;oddOnly/evenOnly 标记单双周分支块,展示时按教学周过滤 */
    data class TBlock(
        val time: String,
        val content: String,
        val short: String,
        val tag: String,
        val oddOnly: Boolean = false,
        val evenOnly: Boolean = false
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
        /** 按教学周过滤后的学习块(去掉休息与课程),用于今日节奏摘要 */
        fun studyBlocksFor(date: LocalDate): List<TBlock> =
            filterByWeek(blocks, date).filter { it.tag != TAG_REST && it.tag != TAG_COURSE }

        /** 按教学周过滤后的全部时间块 */
        fun blocksFor(date: LocalDate): List<TBlock> = filterByWeek(blocks, date)
    }

    /** 六套模板分组(对应计划第 04 节) */
    data class TemplateGroup(
        val name: String,
        val applies: String,
        val templates: List<DayTemplate>
    )

    // ---------- ① 大三上 · 真实周课表 ----------

    private val goldDay = DayTemplate(
        id = "gold",
        name = "黄金自习日 · 周四（＋双周周三）",
        whenText = "周四全天无课;周三双周全天、单周仅早八有课",
        hours = "≈ 8–8.5 h",
        note = "周三侧重推进新内容,周四侧重做题巩固——两天一深一练。单周周三 7:20 起赶 8:00 AI 课,下课 10:00 直接进馆从数学块开始,单词挂早餐 + 通勤耳机。",
        blocks = listOf(
            TBlock("07:20–09:40", "仅单周周三:起床 · 早餐 · 通勤(耳机挂单词)→ 人工智能 8:00–9:40(YB504),下课后 10:00 进馆", "", TAG_COURSE, oddOnly = true),
            TBlock("09:00–09:30", "起床 · 早餐(耳机放单词音频,单周周三已并入上一行)", "", TAG_REST),
            TBlock("09:30–10:05", "背新单词 80–100(到馆第一件事,开口读)", "单词 35m", TAG_EN),
            TBlock("10:05–12:05", "数学:1 讲视频(≤60min)+ 当讲配套习题动笔(≥60min)", "数学 2h", TAG_MATH),
            TBlock("12:05–14:00", "午餐 · 午休(睡前 5min 快过今早新词)", "", TAG_REST),
            TBlock("14:00–17:30", "408:王道当日章节精读 + 课后选择题(每 80min 起身活动一次)", "408 3.5h", TAG_CS),
            TBlock("17:30–19:00", "晚餐 + 快走/慢跑 30min(体力是 468 天的本钱)", "", TAG_REST),
            TBlock("19:00–20:30", "英语:长难句 2–3 句 + 精读/泛读", "英语 1.5h", TAG_EN),
            TBlock("20:30–21:25", "数学错题:当天习题错题重做,写进错题本", "数学错题 55m", TAG_MATH),
            TBlock("21:30 闭馆", "回宿舍:单词复习 + 复盘三问 + 洗漱", "", TAG_GEN),
            TBlock("23:30 前", "入睡(9 点起也要 23:30 睡,睡 8.5–9h)", "", TAG_REST)
        )
    )

    private val monday = DayTemplate(
        id = "mon",
        name = "半课日 · 周一",
        whenText = "周一 · 编译原理 + 双周计网加课",
        hours = "≈ 4.5–6 h(视单双周)",
        note = "上午只有 10:00 一节课,9 点起足够。单词全走碎片(早餐 20min + 通勤 15min + 课间 + 睡前)。双周一下午若不去馆:宿舍过计网课件 + 做王道计网题——这本身就是 408 学习。",
        blocks = listOf(
            TBlock("09:00–09:50", "起床 · 早餐 · 通勤(耳机听单词音频)", "", TAG_REST),
            TBlock("10:00–11:40", "编译原理课(YA510,认真听=期末省一周)", "", TAG_COURSE),
            TBlock("11:50–14:00", "午餐 · 午休", "", TAG_REST),
            TBlock("14:00–16:20", "数学:视频 + 习题 2h", "数学 2h", TAG_MATH),
            TBlock("16:30–17:30", "408:王道当日章节精读 + 课后题", "408 1h", TAG_CS, oddOnly = true),
            TBlock("16:30–18:10", "计网加课(YE102,考研主课,坐前排)", "", TAG_COURSE, evenOnly = true),
            TBlock("17:30–19:00", "晚餐 · 运动(双周课到 18:10 顺延半小时)", "", TAG_REST),
            TBlock("19:00–21:25", "408 1.5h + 英语 1h(长难句 + 单词)", "408 + 英语", TAG_CS),
            TBlock("21:30 闭馆后", "宿舍:单词复习 + 复盘三问,23:30 前入睡", "", TAG_GEN)
        )
    )

    private val tuesday = DayTemplate(
        id = "tue",
        name = "半课日 · 周二",
        whenText = "周二 · 计网主课 + 单周 AI 课(到 18:10)",
        hours = "≈ 4.5–6 h(视单双周)",
        note = "计网 = 考研主课:坐前排、课件当天回顾、作业当日清。单周二 AI 课到 18:10,晚餐运动顺延,晚自修 19:00 照常;双周同时段空出,顺势接 408。",
        blocks = listOf(
            TBlock("09:00–09:50", "起床 · 早餐 · 通勤(耳机听单词音频)", "", TAG_REST),
            TBlock("10:00–11:40", "计网课(YE202,考研主课,坐前排)", "", TAG_COURSE),
            TBlock("11:50–14:00", "午餐 · 午休", "", TAG_REST),
            TBlock("14:00–16:20", "数学:视频 + 习题 2h", "数学 2h", TAG_MATH),
            TBlock("16:30–18:10", "人工智能课(YE302,作业课上清)", "", TAG_COURSE, oddOnly = true),
            TBlock("16:30–17:30", "408:王道当日章节精读 + 课后题", "408 1h", TAG_CS, evenOnly = true),
            TBlock("17:30–19:00", "晚餐 · 运动(单周 AI 课到 18:10 顺延半小时)", "", TAG_REST),
            TBlock("19:00–21:25", "408 1.5h + 英语 1h(长难句 + 单词)", "408 + 英语", TAG_CS),
            TBlock("21:30 闭馆后", "宿舍:单词复习 + 复盘三问,23:30 前入睡", "", TAG_GEN)
        )
    )

    private val fullDay = DayTemplate(
        id = "full",
        name = "满课日 · 保底",
        whenText = "周五 · 上下午都有课",
        hours = "≈ 3 h",
        note = "周五定位保底日:课最重,只求数学 + 单词不断线,多学的都是赚的。状态差就把数学换成看例题 + 整理笔记的轻任务,别透支周末。",
        blocks = listOf(
            TBlock("09:00–09:50", "起床 · 早餐 · 通勤(耳机听单词音频)", "", TAG_REST),
            TBlock("10:00–11:40", "企业级框架开发技术课(单周 YA315 / 双周 RY·B506)", "", TAG_COURSE),
            TBlock("11:50–14:20", "午餐 · 午休(机房课提前 10min 到 B 楼 513)", "", TAG_REST),
            TBlock("14:30–16:10", "数据库高级编程课(B 楼 513 机房)", "", TAG_COURSE),
            TBlock("16:30–18:30", "机动:运动 / 宿舍休整 / 轻量单词(连上两段课,别硬学)", "", TAG_REST),
            TBlock("19:00–20:40", "数学:错题重做 + 公式(保底任务,不让数学断线)", "数学 100m", TAG_MATH),
            TBlock("20:40–21:25", "单词 45min:新词 40 + 复习", "单词 45m", TAG_EN),
            TBlock("21:30 闭馆后", "宿舍:复盘三问 · 23:30 前入睡", "", TAG_GEN)
        )
    )

    // ---------- ② 学期周末 ----------

    private val saturday = DayTemplate(
        id = "sat",
        name = "补进度日",
        whenText = "周六 · 全天无课",
        hours = "≈ 8.5 h",
        note = "周六 = 补进度日:本周欠账今天清零;没有欠账就加量推进数学 / 408 新内容。",
        blocks = listOf(
            TBlock("09:00–09:40", "起床 · 早餐", "", TAG_REST),
            TBlock("09:40–10:15", "单词:新词 60 + 滚动复习", "单词 35m", TAG_EN),
            TBlock("10:20–12:20", "数学:本周内容集中刷题 + 错题重做(以动笔为主,几乎不看新课)", "数学 2h", TAG_MATH),
            TBlock("12:20–14:00", "午餐 · 午休", "", TAG_REST),
            TBlock("14:00–17:30", "408:推进新章节 / 章节综合题 + 代码题动笔", "408 3.5h", TAG_CS),
            TBlock("17:30–19:00", "晚餐 · 运动 · 放空(必须离开书桌)", "", TAG_REST),
            TBlock("19:00–21:00", "英语:真题阅读精读 1 篇(限时 18min → 逐句翻译 → 选项复盘 → 生词入库)", "英语精读 2h", TAG_EN),
            TBlock("21:00–21:25", "本周数学 + 408 错题快速过一遍", "错题 25m", TAG_GEN),
            TBlock("21:30 后", "宿舍:复盘 · 23:30 前入睡", "", TAG_REST)
        )
    )

    private val sunday = DayTemplate(
        id = "sun",
        name = "半休日",
        whenText = "周日 · 上午强制休息",
        hours = "≈ 5 h",
        note = "周日晚雷打不动:复盘 + 排下周。大三下课表变化后,按课 = 固定块、其余进馆、数学拿第一个整块重排。",
        blocks = listOf(
            TBlock("上午", "睡到自然醒 · 自由活动(补觉/运动/朋友/游戏都行,别碰书)", "", TAG_REST),
            TBlock("12:00–14:00", "午餐 · 午休", "", TAG_REST),
            TBlock("14:00–17:30", "错题二刷:只做本周标记过的数学 + 408 错题", "错题二刷 3.5h", TAG_GEN),
            TBlock("17:30–19:00", "晚餐", "", TAG_REST),
            TBlock("19:00–19:30", "周复盘:完成率 / 薄弱点 / 下周 3 件要事", "周复盘 30m", TAG_GEN),
            TBlock("19:30–21:25", "排下周任务表 + 英语单词 1.5h", "排表 + 单词 2h", TAG_EN),
            TBlock("21:30 闭馆后", "23:30 前入睡,周一满血", "", TAG_REST)
        )
    )

    // ---------- ③ 假期全天 ----------

    private val holiday = DayTemplate(
        id = "holiday",
        name = "假期全天",
        whenText = "寒假 + 清明/五一/端午小长假",
        hours = "≈ 9 h",
        note = "在家效率打 8 折很正常——目标按 8 折设定、完成率按 100% 执行,宁可少排不可烂尾;每周日下午起强制休息半天。",
        blocks = listOf(
            TBlock("08:00–08:40", "起床 · 早餐", "", TAG_REST),
            TBlock("08:40–09:20", "单词:新词 80 + 复习", "单词 40m", TAG_EN),
            TBlock("09:30–12:30", "数学:寒假主打线代基础一轮(讲义例题全部动笔)", "数学 3h", TAG_MATH),
            TBlock("12:30–14:00", "午餐 · 午休", "", TAG_REST),
            TBlock("14:00–16:40", "408:机组一轮收尾 + 查漏补缺(对照一轮薄弱清单逐项清)", "408 160m", TAG_CS),
            TBlock("16:40–19:00", "运动 · 晚餐 · 自由时间", "", TAG_REST),
            TBlock("19:00–21:00", "英语:12 月起英一精读启动(每天 1 篇);之前为长难句 + 泛读", "英语 2h", TAG_EN),
            TBlock("21:10–22:10", "当日错题整理 + 复盘 3 行", "错题整理 1h", TAG_GEN),
            TBlock("23:30 前", "睡前单词快过 + 入睡", "", TAG_REST)
        )
    )

    // ---------- ④ 暑期封闭 ----------

    private val summer = DayTemplate(
        id = "summer",
        name = "暑期封闭",
        whenText = "2027.07 – 08 · 黄金九周",
        hours = "≈ 10.5 h",
        note = "暑期是全程分水岭:争取留校或固定自习室;每周日下午起强制休息半天,周日晚排下周。8:30 开卷训练从暑期开始;学期结束(6 月底)后用两周把起床从 9 点过渡到 7:30(每 3 天提前 20 分钟)。",
        blocks = listOf(
            TBlock("07:30–08:20", "起床 · 早餐 · 到馆", "", TAG_REST),
            TBlock("08:30–11:30", "数学:7 月强化收尾刷题;8 月起真题按套限时——严格 8:30 开卷", "数学 3h", TAG_MATH),
            TBlock("11:30–12:10", "单词:新词 60–80 + 复习(进入第四轮)", "单词 40m", TAG_EN),
            TBlock("12:10–14:00", "午餐 · 午休", "", TAG_REST),
            TBlock("14:00–17:00", "408:7 月课后题错题二刷;8 月起真题分科限时(大题完整写过程)", "408 3h", TAG_CS),
            TBlock("17:00–18:40", "晚餐 + 运动 40min", "", TAG_REST),
            TBlock("18:40–19:40", "政治:徐涛强化课 1.5–2 倍速 + 肖 1000 对应章节选择题", "政治 1h", TAG_POL),
            TBlock("19:50–21:40", "英语:英二真题精读(阅读/新题型/翻译)/ 石雷鹏写作课跟写", "英语 110m", TAG_EN),
            TBlock("21:50–22:40", "当日错题回顾 + 明日计划", "错题回顾 50m", TAG_GEN),
            TBlock("23:30 前", "入睡", "", TAG_REST)
        )
    )

    // ---------- ⑤ 冲刺模考 ----------

    private val sprint = DayTemplate(
        id = "sprint",
        name = "冲刺模考",
        whenText = "2027.09 – 12.17",
        hours = "≈ 9.5 h",
        note = "生物钟对齐考试:上午数学、下午 408、晚上政治英语。12 月起每周六日全真模考(完全仿真,含涂卡)。生物钟前移:从 2027 年 9 月起每两周把起床提前 30 分钟(9:00→8:30→8:00→…),11 月底完成向 6:50 起的过渡——初试上午 8:30 开考是刚性的。",
        blocks = listOf(
            TBlock("06:50–07:30", "起床 · 早餐(音频:政治带背 / 单词)", "", TAG_REST),
            TBlock("07:40–08:20", "政治:背诵手册带背(11 月前选择考点,11 月起分析题考点)", "政治带背 40m", TAG_POL),
            TBlock("08:30–11:30", "数学:9–10 月专题突破;11 月起真题/模拟卷按套限时——严格 8:30 开卷", "数学 3h", TAG_MATH),
            TBlock("11:30–14:00", "午餐 · 午休", "", TAG_REST),
            TBlock("14:00–17:00", "408:真题套卷限时 / 大题专项——严格 14:00 开卷,训练下午兴奋点", "408 3h", TAG_CS),
            TBlock("17:00–18:30", "晚餐 + 运动 30min", "", TAG_REST),
            TBlock("18:30–19:30", "政治:9–10 月刷题订正;11–12 月肖八/肖四选择题", "政治 1h", TAG_POL),
            TBlock("19:40–21:10", "英语:真题二刷 / 作文限时写作 + 模板默写", "英语 90m", TAG_EN),
            TBlock("21:20–22:20", "当日试卷订正归档(当天卷当天清,不过夜)", "订正 1h", TAG_GEN),
            TBlock("23:30 前", "复盘 + 入睡(12 月起严禁熬夜)", "", TAG_REST)
        )
    )

    // ---------- ⑥ 考前一周 ----------

    private val finalWeek = DayTemplate(
        id = "final_week",
        name = "考前一周",
        whenText = "2027.12.11 – 12.17",
        hours = "≈ 6 h",
        note = "三原则:不做新题、不开新坑、只背 + 保温 + 调整。事务清单:身份证/准考证打印、文具、异地住宿、考点踩点。12.18–19 初试。",
        blocks = listOf(
            TBlock("08:30–11:30", "数学 1.5h(错题本 + 公式默写)→ 政治 1.5h(肖四 / 时政带背)", "数学 + 政治", TAG_MATH),
            TBlock("11:30–14:00", "午餐 · 午休(作息完全对齐考试日)", "", TAG_REST),
            TBlock("14:00–17:00", "408 1.5h(高频简答背诵 + 核心算法默写)→ 英语 1.5h(作文模板默写 + 熟题保温)", "408 + 英语", TAG_CS),
            TBlock("19:00–22:00", "政治 + 英语背诵为主(两科记忆最佳时段),22:30 前睡", "政治 + 英语", TAG_POL)
        )
    )

    private val byId = listOf(
        goldDay, monday, tuesday, fullDay, saturday, sunday, holiday, summer, sprint, finalWeek
    ).associateBy { it.id }

    val templateGroups = listOf(
        TemplateGroup("① 大三上 · 真实周课表", "基础唤醒期 / 强化一轮期(有课学期)", listOf(goldDay, monday, tuesday, fullDay)),
        TemplateGroup("② 学期周末", "有课学期的周六周日", listOf(saturday, sunday)),
        TemplateGroup("③ 假期全天", "寒假衔接期 + 小长假", listOf(holiday)),
        TemplateGroup("④ 暑期封闭", "暑期封闭期(2027.07–08)", listOf(summer)),
        TemplateGroup("⑤ 冲刺模考", "冲刺模考期(2027.09–12)", listOf(sprint)),
        TemplateGroup("⑥ 考前一周", "2027.12.11–12.17 · 只做三件事", listOf(finalWeek))
    )

    private val finalWeekRange = LocalDate.of(2027, 12, 11)..LocalDate.of(2027, 12, 17)

    /** 按日期自动选模板:阶段 + 星期 → 今天的具体形态 */
    fun templateFor(date: LocalDate): DayTemplate? {
        if (date in finalWeekRange) return byId["final_week"]
        return when (Phases.current(date)?.shortName) {
            "寒假" -> byId["holiday"]
            "暑期" -> byId["summer"]
            "冲刺" -> byId["sprint"]
            "基础", "强化" -> when (date.dayOfWeek) {
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY -> byId["gold"]
                DayOfWeek.MONDAY -> byId["mon"]
                DayOfWeek.TUESDAY -> byId["tue"]
                DayOfWeek.FRIDAY -> byId["full"]
                DayOfWeek.SATURDAY -> byId["sat"]
                DayOfWeek.SUNDAY -> byId["sun"]
            }
            else -> null
        }
    }

    fun todayTemplate(nowMillis: Long): DayTemplate? =
        templateFor(Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate())

    // ---------- 教学周(单双周判定) ----------

    /** 2026–2027 上学期第 1 周的周一(与启动四周 W1 = 09.07–09.13 对齐) */
    private val semesterStart = LocalDate.of(2026, 9, 7)

    /** 学期末(寒假起)——下学期课表变化后模板重排,不再按单双周过滤 */
    private val semesterEnd = LocalDate.of(2027, 1, 31)

    /** 教学周序号:第 1 周 = 2026.09.07–09.13,奇数 = 单周;学期外返回 null */
    fun teachingWeekOf(date: LocalDate): Int? {
        if (date.isBefore(semesterStart) || date.isAfter(semesterEnd)) return null
        return (ChronoUnit.DAYS.between(semesterStart, date) / 7).toInt() + 1
    }

    /** 教学周标签,如「第 1 周 · 单周」;学期外返回 null */
    fun teachingWeekLabel(date: LocalDate): String? =
        teachingWeekOf(date)?.let { "第 $it 周 · ${if (it % 2 == 1) "单周" else "双周"}" }

    /** 按教学周过滤时间块:单周只留 oddOnly 块,双周只留 evenOnly 块,未标记的常驻;学期外隐藏分支块 */
    private fun filterByWeek(blocks: List<TBlock>, date: LocalDate): List<TBlock> {
        val week = teachingWeekOf(date) ?: return blocks.filter { !it.oddOnly && !it.evenOnly }
        return blocks.filter { block ->
            when {
                block.oddOnly -> week % 2 == 1
                block.evenOnly -> week % 2 == 0
                else -> true
            }
        }
    }

    // ---------- 今日节奏(待办驱动排程) ----------

    /** 今日节奏的一行:任务行(时长 = 番茄数 × focusMin)或锚点行(休息/课程/闭馆等固定块) */
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
     *   高强度科目(数学/408)每 2 个,低强度(英语/政治/通用)每 3 个;
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
     * 溢出进同科目下一槽);无对应科目槽的任务进入 TAG_GEN 通用槽(错题二刷/周复盘等),仍无处安放的
     * 并入最后一个学习槽顺延。休息/课程/闭馆等锚点块保持模板时间,学习槽起点 = max(模板起点, 上一块结束)。
     * 今日无待办时回退为模板原样展示。
     */
    fun buildTodayRhythm(
        template: DayTemplate,
        date: LocalDate,
        tasks: List<RhythmTask>,
        focusMinutes: Int = 25
    ): List<RhythmRow> {
        val blocks = template.blocksFor(date)
        val study = tasks.filter { it.pomodoros > 0 }
        val slotIdxs = blocks.indices.filter { blocks[it].short.isNotEmpty() }
        if (study.isEmpty() || slotIdxs.isEmpty()) {
            return template.studyBlocksFor(date).map {
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

        // ② 无同科目槽的任务(如周一无英语学习块)装入 TAG_GEN 通用槽(错题二刷/周复盘等)
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

    // ---------- 本学期课表(2026–2027 上 · 单双周) ----------

    data class Course(
        val name: String,
        val time: String,
        val weeks: String,
        val relation: String,
        val isExamRelated: Boolean
    )

    val semesterCourses = listOf(
        Course("计算机网络(尚建贞)", "周二 10:00–11:40(YE202) + 双周周一 16:30–18:10(YE102)", "1–16 周 + 双周加课", "408 直接覆盖——本学期最重要的课,坐前排", true),
        Course("编译原理(杨梦伟)", "周一 10:00–11:40(YA510)", "1–16 周每周", "非考研课:课上认真听 + 作业当日清", false),
        Course("企业级框架开发技术(赵宏娟)", "周五 10:00–11:40(单周 YA315 / 双周 RY·B506)", "1–16 周每周", "非考研课:同上", false),
        Course("数据库高级编程(王旭阳)", "周五 14:30–16:10(B 楼 513 机房)", "1–16 周每周", "非考研课(408 不考数据库):同上", false),
        Course("人工智能技术(姚明宇)", "单周周三 8:00–9:40(YB504) + 单周周二 16:30–18:10(YE302)", "1–16 周·仅单周每周两次", "非考研课:同上;单周周三 7:20 起床", false)
    )

    const val COURSE_NOTE = "关键结论:周四、周六、周日全天无课;周三双周全天无课、单周仅早 8:00–9:40 一节人工智能;周五课最重——自习主力是周四、周六和周三(双周全天 / 单周 10:00 后)。计网课 = 408:期末复习直接当 408 计网一轮用;期末前 10 天非考研课全部让位期末。"

    // ---------- 每周节奏 ----------

    val weeklyRhythm = listOf(
        "周一 / 二 / 五" to "课日模板,保底数学 + 单词",
        "周四(＋双周周三)" to "黄金自习日,数学 + 408 各拿一个整块;单周周三 7:20 起赶 8:00 AI 课,下课 10:00 进馆照常",
        "周六" to "补进度日,本周欠账今天清零",
        "周日下午" to "强制休息半天(运动/朋友/游戏,离开书桌)",
        "周日晚" to "周复盘 30min(完成率/薄弱点/下周 3 要事)+ 排下周",
        "状态崩的一周" to "保数学 + 单词,其余减半,别硬撑"
    )

    // ---------- 每日复盘三问 ----------

    val reviewQuestions = listOf(
        "今天完成了什么?" to "对照日计划打勾,诚实一点",
        "哪个知识点最模糊?" to "写进错题本,变成明天第一件事",
        "明天最重要的 1 件事?" to "睡前定好,早起不犹豫"
    )

    // ---------- 四科全程规划 ----------

    data class SubjectPlan(
        val name: String,
        val badge: String,
        val strategy: String,
        val rows: List<Pair<String, String>>,
        val note: String
    )

    val subjectPlans = listOf(
        SubjectPlan(
            "数学二", "满分 150 · 目标 110–125 · 全程约 1150h",
            "每天第一个整块时间永远给数学,全程 468 天不断线。数二只考高数 + 线代(不考无穷级数、三重积分、曲线曲面积分、空间解析几何)——资料里遇到直接跳过。",
            listOf(
                "2026.09–12" to "高数基础一轮:基础班视频逐讲过 +《1800 题》基础篇当章刷。铁律:视频与动笔 1:1,例题先遮答案自己做",
                "2027.01–02" to "线代基础一轮:李永乐讲义 + 基础课,行列式 → 矩阵 → 向量组 → 方程组 → 特征值 → 二次型,例题全部动笔",
                "2027.03–04" to "高数强化:辅导讲义/高数 18 讲 +《880/1000 题》提高篇;专题突破:极限计算、中值定理、积分技巧",
                "2027.05–06" to "线代强化 + 高数错题二刷;期末周降为每天 1h 保温不断线",
                "2027.07–08" to "强化收尾 + 真题启动:数二真题 2010–2019 按套限时(严格 8:30–11:30),每周 2 套 + 当日订正归档",
                "2027.09–11" to "真题 2020–2024 + 近年错题二刷;11 月起模拟卷每周 1–2 套限时(李林 6+4 为主)",
                "2027.12" to "保温:只碰错题本 + 公式默写 + 每天 1.5h 熟题手感,不开新难题"
            ),
            "宁可慢半个月的「真基础」,不要快一个月的「假流畅」——强化期的痛苦九成来自基础期欠账。"
        ),
        SubjectPlan(
            "408 计算机学科专业基础", "满分 150 · 目标 108–120 · 全程约 950h",
            "王道四件套是唯一主线(DS≈45 / 机组≈45 / OS≈35 / 计网≈25 分)。三科学过的科目走快速唤醒,计网跟学校课同步学——期末复习就是考研一轮。",
            listOf(
                "2026.09–11 中" to "数据结构唤醒:王道 DS + 课后选择题全做;核心代码(链表反转/二叉树遍历/图遍历/快排归并)后期默写",
                "2026.11 中–2027.01" to "组成原理一轮:王道机组 + 配套视频;难点专项:浮点运算、Cache 映射、流水线、中断与 I/O",
                "全程同步" to "计网跟校课:认真上课 + 作业当日清,课后王道计网对应章节 + 选择题巩固;期末复习 = 408 计网一轮",
                "2027.03–04" to "操作系统一轮:王道 OS;与机组联动学(中断、内存管理、I/O),两科互相印证",
                "2027.05–06" to "计网系统二轮(王道全书过完)+ DS/机组错题回炉;6 月底四科一轮盘点,列薄弱清单",
                "2027.07–08" to "二轮刷题:王道课后错题全部重做;8 月起真题分科刷(2009–2018),大题动手写完整过程",
                "2027.09–11" to "真题按套限时(2010–2024)+ 错题二刷;大题专项(算法设计/Cache/页表/磁盘调度/子网划分);模拟卷王道 8 套或研芝士 4 套选一",
                "2027.12" to "背诵 + 保温:高频简答背诵、核心算法代码手写默写、每周 1 套熟卷保手感"
            ),
            "408 题量大、真题风格稳定,真题的价值远大于一切模拟题;错题本按 DS/机组/OS/计网四栏归档。"
        ),
        SubjectPlan(
            "英语二", "满分 100 · 目标 70–80 · 全程约 550h",
            "单词是复利,每天 40–60 分钟,背到进考场前。英二真题少(2010 起),前期用英一阅读打底,后期英二会明显感到「变简单了」。",
            listOf(
                "2026.09–11" to "单词一轮 + 长难句:每天新词 80–100 + 滚动复习(App 或单词书);田静《句句真研》每天 2–3 句动手划分翻译",
                "2026.12–2027.04" to "英一阅读精读:2005–2020 每周 2–3 篇,限时 18min 做 → 逐句翻译 → 分析每个选项 → 生词入库",
                "2027.05–06" to "精读收尾 + 翻译专项:英二翻译(段落英译汉)入门,每周 2 段动手写",
                "2027.07–08" to "英二真题启动:2010–2020 阅读 + 新题型 + 翻译;跟石雷鹏写作课,开始动笔写小作文",
                "2027.09–10" to "大作文(图表)专项:每周 1 篇限时写作 + 批改复盘;真题错题二刷",
                "2027.11–12" to "模考 + 背诵:留 2021–2024 英二整卷按考试时间模考;大小作文模板默写滚瓜烂熟;单词第五轮收尾"
            ),
            "题型分值:完形 10 / 阅读 40 / 新题型 10 / 翻译 15 / 小作文 10 / 大作文 15——得阅读者得英语,阅读 40 分是训练重心。"
        ),
        SubjectPlan(
            "政治", "满分 100 · 目标 65–75 · 全程约 300h",
            "全流程启动最晚(2027 年 7 月),前期一分钟都别分给它。分析题全国均分相近,差距全在选择题(尤其多选)——刷题量决定上限。",
            listOf(
                "2026.09–2027.06" to "不启动:学有余力的话寒假听徐涛马原哲学部分当调剂,不做题、不占整块时间",
                "2027.07–08" to "强化入场:徐涛强化课 1.5–2 倍速(马原 → 毛中特 → 史纲 → 思修)+ 肖 1000 对应章节选择题,只做选择不做大题",
                "2027.09–10" to "刷题巩固:肖 1000 错题二刷 + 腿姐技巧班(选择题技巧);10 月底起冲刺背诵手册选择考点带背",
                "2027.11" to "肖八季:选择题刷 2 遍以上、目标稳定 40+;时政课过一遍;背诵手册分析题考点启动",
                "2027.12" to "肖季终章:肖四分析题全文背诵(或腿姐 9 页纸二选一);肖四肖八 + 腿四 + 徐六选择题滚动刷到考前"
            ),
            "题型分值:单选 16×1 + 多选 17×2 + 分析题 5×10——多选是政治的「数学大题」,靠刷题积累,突击无效。"
        )
    )

    // ---------- 目标分数 ----------

    data class ScoreTarget(
        val subject: String,
        val full: String,
        val current: String,
        val safe: String,
        val sprint: String,
        val positioning: String
    )

    val scoreTargets = listOf(
        ScoreTarget("政治", "100", "未接触", "65", "75", "选择题定生死,晚启动高性价比"),
        ScoreTarget("英语二", "100", "四级刚过", "70", "80", "词汇量 = 分数,日拱单词到考前"),
        ScoreTarget("数学二", "150", "学过有遗忘", "110", "125", "最大拉分项,时间投入第一"),
        ScoreTarget("408", "150", "三科学过 + 计网在学", "108", "120", "内容最多,靠「唤醒」抢出时间"),
        ScoreTarget("合计", "500", "—", "353", "400", "353 ≈ 211/普通 985 计算机线;380+ 冲强校")
    )

    // ---------- 启动四周 ----------

    data class LaunchWeek(
        val week: String,
        val name: String,
        val dates: String,
        val tasks: List<String>,
        val milestone: String
    )

    val launchWeeks = listOf(
        LaunchWeek(
            "W1", "建系统", "09.07 – 09.13",
            listOf(
                "一次购齐首批资料,装好背单词 App,建好错题本",
                "数学:基础课第 1–6 讲(函数 / 极限概念与计算)+ 配套习题 40–50 题",
                "408:DS 第 1 章绪论(时间/空间复杂度分析)+ 顺序表",
                "计网:跟住学校前 2 周课(概述 / 分层模型),课件当天回顾",
                "英语:单词 List 1–2 滚动 7 天(日新 80–100)+ 长难句 Day 1–5"
            ),
            "固定「到馆先单词、第一个整块给数学」的节奏,21 天后它会成为本能"
        ),
        LaunchWeek(
            "W2", "上强度", "09.14 – 09.20",
            listOf(
                "数学:极限计算 + 函数连续性,习题 50–60 题",
                "408:DS 线性表(链表)全节完成 + 课后选择题",
                "计网:物理层开始(按校课进度)+ 王道计网对应小节",
                "英语:单词 List 3–4 + 长难句 Day 6–10"
            ),
            "本周起周日晚开始写周复盘(三问:完成率 / 薄弱点 / 下周要事)"
        ),
        LaunchWeek(
            "W3", "稳节奏", "09.21 – 09.27",
            listOf(
                "数学:导数与微分 + 中值定理入门,习题 50–60 题",
                "408:DS 栈与队列 + 数组与特殊矩阵",
                "计网:数据链路层(差错控制 / 流量控制——与后期 OS 相互呼应)",
                "英语:单词 List 5–6 + 长难句 Day 11–15"
            ),
            "三周不间断,任何一天再晚也保住数学 + 单词底线"
        ),
        LaunchWeek(
            "W4", "第一次月检", "09.28 – 10.04(+国庆)",
            listOf(
                "数学:中值定理 + 洛必达/泰勒展开强化,收错题",
                "408:DS 串(KMP)+ 开始「树」",
                "国庆 7 天加量:每天 8h(数学 3 + 408 3 + 英语 2),目标完成 DS「树」的一半",
                "10.04 月复盘:极限/导数计算正确率 ≥ 80%?单词进度 ≥ 词汇书 1/5?计网跟课有无欠账?"
            ),
            "找到属于自己的真实节奏,据此微调 10 月日计划"
        )
    )

    // ---------- 资料清单 ----------

    data class MaterialItem(val name: String, val use: String, val timing: String, val buyNow: Boolean)

    data class MaterialGroup(val subject: String, val tag: String, val items: List<MaterialItem>)

    val materialGroups = listOf(
        MaterialGroup("数学二", TAG_MATH, listOf(
            MaterialItem("《高等数学基础篇》(武忠祥)或《基础 30 讲》(张宇)", "基础一轮主教材(二选一)", "现在", true),
            MaterialItem("《1800 题》基础篇(汤家凤)或《660 题》", "基础阶段配套刷题", "现在", true),
            MaterialItem("《线性代数辅导讲义》(李永乐)", "线代基础 + 强化通用", "2026.12 前", false),
            MaterialItem("《高等数学辅导讲义》或《高数 18 讲》+《880/1000 题》", "强化一轮", "2027.03", false),
            MaterialItem("数二历年真题(近 15 年,任一版本)", "真题套卷", "2027.07", false),
            MaterialItem("李林《6+4》(可选加张宇《8+4》)", "模拟卷", "2027.10–11", false)
        )),
        MaterialGroup("408", TAG_CS, listOf(
            MaterialItem("王道《数据结构复习指导》", "DS 一轮主线", "现在", true),
            MaterialItem("王道《计算机网络复习指导》", "配合学校计网课同步学", "现在", true),
            MaterialItem("王道《计算机组成原理复习指导》", "机组一轮(11 月中启动)", "2026.11", false),
            MaterialItem("王道《操作系统复习指导》", "OS 一轮(2027.03 启动)", "2027.03", false),
            MaterialItem("王道《408 历年真题》", "真题套卷", "2027.07", false),
            MaterialItem("王道 8 套卷或研芝士 4 套卷(选一)", "冲刺模拟", "2027.10", false)
        )),
        MaterialGroup("英语二", TAG_EN, listOf(
            MaterialItem("单词书任一(恋练有词/红宝书)+ 背单词 App 会员", "全程单词(书 + App 双轨)", "现在", true),
            MaterialItem("《句句真研》(田静)", "长难句", "现在", true),
            MaterialItem("英一真题黄皮书(2010–2020)", "英一阅读精读打底", "2026.12", false),
            MaterialItem("英二真题黄皮书(2010–2025)", "英二真题训练 + 模考", "2027.07", false),
            MaterialItem("石雷鹏《30 个功能句》/《冲刺背诵 20 篇》", "大小作文", "2027.09", false)
        )),
        MaterialGroup("政治", TAG_POL, listOf(
            MaterialItem("《核心考案》(徐涛)+ 强化课 + 肖 1000 题", "强化一轮", "2027.07", false),
            MaterialItem("《冲刺背诵手册》(腿姐)", "选择考点 + 分析题带背", "2027.10", false),
            MaterialItem("肖八(11 月上旬发售)", "选择题 + 时政", "2027.11", false),
            MaterialItem("肖四(12 月上旬发售)", "分析题背诵", "2027.12", false)
        ))
    )

    const val MATERIAL_NOTE = "同类只买一套、够用就好、按节点分批买。购买认准当年最新版,考研大纲以中国教育考试网/研招网当年公布为准。"

    // ---------- 十条军规 ----------

    data class RuleItem(val title: String, val desc: String)

    val rules = listOf(
        RuleItem("数学每天第一个整块,雷打不动", "468 天不断线。数学是四科中上限和下限差距最大的科目,你投入的每一小时都算数"),
        RuleItem("单词是复利,背到进考场前", "每天 40–60 分钟,考前完成 5 轮。中断三天,遗忘曲线会没收你两周的成果"),
        RuleItem("政治最晚启动,7 月前一门心思数英专", "启动早 ≠ 分数高,政治的边际收益随时间递减,把时间让给数学和 408"),
        RuleItem("计网课就是考研课", "大三上认真上课、做作业、准备期末——期末复习即 408 一轮,最没底的一科变送分科"),
        RuleItem("看视频的时间 ≤ 动笔的时间", "例题先遮住答案自己做。看懂 ≠ 会做,「我懂了」是考研最大的错觉"),
        RuleItem("真题为王,模拟卷只是配菜", "2027 年 9 月起一切训练以真题为中心;数学和 408 的真题至少完整刷两遍"),
        RuleItem("错题本决定上限", "每周日错题重做,考前两周只看错题本。没有错题本的考研,等于白错"),
        RuleItem("每周半天强制休息", "可持续的 9 小时 > 爆发式的 14 小时。休息是计划的一部分,不是对计划的背叛"),
        RuleItem("睡眠 7h+,每周运动 3 次", "12 月前绝不熬夜透支。冲刺期拼的是状态稳定性,不是悲壮感"),
        RuleItem("计划服从现实", "期末、实训、毕设挤压时:保数学 + 单词,其余弹性砍。完成 80% 的稳定计划,胜过 100% 的完美计划")
    )

    // ---------- 关键日期线 ----------

    data class KeyDateItem(val date: String, val event: String)

    val keyDates = listOf(
        KeyDateItem("2027.06", "两次真题模考 → 确定择校区间"),
        KeyDateItem("2027.09 下旬", "预报名(研招网,建议参加)"),
        KeyDateItem("2027.10", "正式报名"),
        KeyDateItem("2027.11 初", "网上确认(照片/材料)"),
        KeyDateItem("2027.12 中旬", "下载打印准考证"),
        KeyDateItem("2027.12.18–19", "初试(预计,以官宣为准)"),
        KeyDateItem("2028.02", "成绩公布"),
        KeyDateItem("2028.03–04", "复试 / 调剂(初试后立刻准备复试机试 + 面试)"),
        KeyDateItem("2028.06", "毕业 · 录取")
    )

    /** 初试时间安排(反推生物钟的依据) */
    val examSchedule = listOf(
        "第一天上午" to "思想政治理论 08:30 – 11:30",
        "第一天下午" to "英语二 14:00 – 17:00",
        "第二天上午" to "数学二 08:30 – 11:30",
        "第二天下午" to "408 14:00 – 17:00"
    )

    // ---------- 五阶段作战日历(M2「三阶段拆解到日」) ----------
    // 把 468 天作战计划按阶段逐一拆成「关键推进节点」，每个节点给出四科到日动作。
    // 内容全部取材自 Phases 主线/里程碑与 subjectPlans 四科时间线，不新增考点，只做粒度细化与阶段归拢。

    /** 阶段内一个推进节点:日期段 + 主题 + 四科到日动作 */
    data class CampaignNode(
        val start: LocalDate,
        val end: LocalDate,
        val title: String,
        val math: String,
        val cs: String,
        val english: String,
        val politics: String
    ) {
        fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
    }

    /** 一个阶段的作战日历 */
    data class PhaseCampaign(
        val phaseShort: String,
        val phaseName: String,
        val nodes: List<CampaignNode>
    ) {
        val start: LocalDate get() = nodes.minOf { it.start }
        val end: LocalDate get() = nodes.maxOf { it.end }
    }

    val campaign: List<PhaseCampaign> = listOf(
        PhaseCampaign("基础", "基础唤醒期", listOf(
            CampaignNode(
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 4), "启动四周 · 建系统上强度",
                "函数/极限概念与计算 → 极限计算与连续性 → 导数与微分 → 中值定理(洛必达/泰勒)动笔",
                "DS 绪论+顺序表 → 线性表(链表) → 栈队列+矩阵 → 串(KMP)+树启动",
                "单词 List1–6 滚背 + 长难句 Day1–15",
                "不启动"
            ),
            CampaignNode(
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 31), "一轮推进 · 导数微分",
                "导数与微分 + 微分中值定理 + 洛必达/泰勒展开强化，配套习题动笔",
                "DS 树(二叉树遍历)+图+查找；计网物理层/数据链路层跟课当天回顾",
                "单词一轮持续推进 + 长难句/泛读",
                "不启动"
            ),
            CampaignNode(
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "DS 收尾转机组",
                "不定积分/定积分 + 刷 1800 基础篇当章",
                "DS 排序收尾 → 机组一轮启动(数据表示/运算/存储)；计网跟课",
                "单词一轮推进(词汇书过半)",
                "不启动"
            ),
            CampaignNode(
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), "机组攻坚 · 英一启动",
                "定积分应用 + 常微分方程(数二范围)",
                "机组重点:浮点/Cache 映射/流水线/中断与 I/O",
                "单词一轮收尾；12 月起英一精读启动(每周 2–3 篇)",
                "不启动"
            ),
            CampaignNode(
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31), "一轮收官 · 期末",
                "高数一轮查漏 + 错题清理 + 线代前置(行列式)",
                "机组一轮收尾查漏；计网期末复习 = 408 计网一轮",
                "英一精读继续 + 单词滚动复习",
                "不启动"
            )
        )),
        PhaseCampaign("寒假", "寒假衔接期", listOf(
            CampaignNode(
                LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28), "线代一轮 · 在家 8 折",
                "线代基础一轮:行列式→矩阵→向量组→方程组→特征值→二次型，讲义例题全动笔",
                "机组一轮收尾 + 对照薄弱清单逐项清",
                "英一精读完成 6–8 篇",
                "不启动(学有余力可听马原哲学调剂)"
            )
        )),
        PhaseCampaign("强化", "强化一轮期", listOf(
            CampaignNode(
                LocalDate.of(2027, 3, 1), LocalDate.of(2027, 4, 30), "高数强化 + OS 一轮",
                "高数强化(辅导讲义/18讲)+ 880/1000 提高篇；专题:极限计算/中值定理/积分技巧",
                "操作系统一轮(王道 OS)，与机组联动(中断/内存管理/I/O)",
                "英一精读 2005–2020 每周 2–3 篇",
                "不启动"
            ),
            CampaignNode(
                LocalDate.of(2027, 5, 1), LocalDate.of(2027, 6, 30), "线代强化 + 一轮盘点",
                "线代强化 + 高数错题二刷；期末周降到每天 1h 保温",
                "计网二轮(王道全书)+ DS/机组错题回炉；6 月底四科一轮盘点列薄弱清单",
                "英一精读 20 篇以上 + 英二翻译专项(每周 2 段)",
                "不启动"
            )
        )),
        PhaseCampaign("暑期", "暑期封闭期", listOf(
            CampaignNode(
                LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 31), "强化收尾 + 政治入场",
                "数学强化收尾刷题(880/1000)",
                "408 二轮刷题(课后错题全部重做)",
                "英二真题启动(2010–2020)阅读+新题型+翻译；单词进入第四轮",
                "徐涛强化 1.5–2 倍速(马原→毛中特→史纲→思修)+ 肖 1000 对应章节选择题"
            ),
            CampaignNode(
                LocalDate.of(2027, 8, 1), LocalDate.of(2027, 8, 31), "真题分科启动",
                "数二真题 2010–2019 按套限时(严格 8:30 开卷)每周 2 套 + 当日订正",
                "408 真题分科 2009–2018，大题动手写完整过程",
                "英二精读 + 石雷鹏写作课跟写小作文",
                "马原 + 毛中特强化收尾"
            )
        )),
        PhaseCampaign("冲刺", "冲刺模考期", listOf(
            CampaignNode(
                LocalDate.of(2027, 9, 1), LocalDate.of(2027, 10, 31), "专题突破 + 作文成型",
                "数二真题 2020–2024 + 近年错题二刷 + 高频专题突破",
                "408 真题按套限时(2010–2024)+ 大题专项(算法设计/Cache/页表/磁盘调度/子网划分)",
                "英二大作文(图表)专项每周 1 篇限时写 + 真题错题二刷",
                "肖 1000 错题二刷 + 腿姐技巧班；10 月底背诵手册选择考点带背"
            ),
            CampaignNode(
                LocalDate.of(2027, 11, 1), LocalDate.of(2027, 11, 30), "模拟卷 + 肖八季",
                "模拟卷每周 1–2 套限时(李林 6+4 为主)",
                "408 模拟卷(王道 8 套/研芝士 4 套选一)",
                "留 2021–2024 整卷模考(按考试时间)+ 大小作文模板默写",
                "肖八选择题刷 2 遍、目标稳定 40+；时政；背诵手册分析题考点启动"
            ),
            CampaignNode(
                LocalDate.of(2027, 12, 1), LocalDate.of(2027, 12, 10), "终章 · 肖四 + 保温",
                "只碰错题本 + 公式默写 + 每天 1.5h 熟题手感",
                "高频简答背诵 + 核心算法代码手写默写 + 每周 1 套熟卷",
                "大小作文模板默写滚瓜烂熟 + 单词第五轮收尾",
                "肖四全文背诵(或腿姐 9 页纸二选一)；肖八/腿四/徐六选择题滚动刷到考前"
            ),
            CampaignNode(
                LocalDate.of(2027, 12, 11), LocalDate.of(2027, 12, 17), "考前一周 · 三原则",
                "不做新题、不开新坑:数学错题本 + 公式默写 1.5h",
                "高频简答背诵 + 核心算法默写 1.5h",
                "作文模板默写 + 熟题保温 1.5h",
                "肖四 + 时政带背 1.5h"
            )
        ))
    )

    /** 当前所在推进节点(用于作战日历高亮);备考期外返回 null */
    fun currentNode(date: LocalDate): CampaignNode? =
        campaign.asSequence().flatMap { it.nodes }.firstOrNull { it.contains(date) }

    fun currentPhaseCampaign(date: LocalDate): PhaseCampaign? =
        Phases.current(date)?.let { phase ->
            campaign.firstOrNull { it.phaseShort == phase.shortName }
        }
}
