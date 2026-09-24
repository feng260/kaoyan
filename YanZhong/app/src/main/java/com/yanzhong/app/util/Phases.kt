package com.yanzhong.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 五阶段备考体系(源自 468 天考研全程作战计划):
 * 基础唤醒 → 寒假衔接 → 强化一轮 → 暑期封闭 → 冲刺模考。
 * 阶段日期按 2028 届(初试 2027-12-18)硬编码,跨届修改 [Phases.all] 即可。
 */
data class StudyPhase(
    val name: String,
    val shortName: String,
    val slogan: String,
    val start: LocalDate,
    val endInclusive: LocalDate,
    val weeklyHours: String = "",
    val milestone: String = "",
    val alloc: String = "",
    val mainLine: String = ""
) {
    fun contains(date: LocalDate): Boolean =
        !date.isBefore(start) && !date.isAfter(endInclusive)

    fun daysCount(): Long = ChronoUnit.DAYS.between(start, endInclusive) + 1
}

data class PhaseInfo(
    val phase: StudyPhase,
    val dayIdx: Long,
    val totalDays: Long,
    val daysToEnd: Long,
    val next: StudyPhase?
)

object Phases {

    val EXAM_DATE: LocalDate = LocalDate.of(2027, 12, 18)

    val all: List<StudyPhase> = listOf(
        StudyPhase(
            "基础唤醒期", "基础", "高数一轮 + 408 唤醒 + 单词一轮",
            LocalDate.of(2026, 9, 7), LocalDate.of(2027, 1, 31),
            weeklyHours = "40–44",
            milestone = "高数基础过完 · DS + 机组王道一轮 · 单词一轮背完 · 计网跟住校课无欠账",
            alloc = "数学 40% · 408 40% · 英语 20%",
            mainLine = "高数基础一轮 + 408 数据结构/组成原理一轮 + 计网跟学校课 + 英语单词一轮 & 长难句;学期内周三/周四/周六全天自习为主力,周一/二/五课余保底(见模板①)。"
        ),
        StudyPhase(
            "寒假衔接期", "寒假", "线代一轮 + 机组收尾 + 英一精读启动",
            LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28),
            weeklyHours = "48",
            milestone = "线代基础过完 · 408 三科无硬伤 · 英一精读完成 6–8 篇",
            alloc = "数学 40% · 408 30% · 英语 30%",
            mainLine = "线代基础一轮 + 机组一轮收尾查漏 + 英语一早年真题精读启动;在家效率按 8 折规划,目标定低一点、完成率定高一点。"
        ),
        StudyPhase(
            "强化一轮期", "强化", "数学强化 + OS 一轮 + 计网二轮",
            LocalDate.of(2027, 3, 1), LocalDate.of(2027, 6, 30),
            weeklyHours = "42",
            milestone = "数学强化完毕 · 408 四科一轮完成 · 英一精读 20 篇以上 · 模考定择校区间",
            alloc = "数学 44% · 408 34% · 英语 22%"
        ),
        StudyPhase(
            "暑期封闭期", "暑期", "黄金九周 · 政治入场 · 真题启动",
            LocalDate.of(2027, 7, 1), LocalDate.of(2027, 8, 31),
            weeklyHours = "62",
            milestone = "数二真题刷完 2010–2019 · 408 真题分科刷完 2009–2018 · 政治强化马原+毛中特 · 单词第四轮",
            alloc = "数学 30% · 408 28% · 英语 18% · 政治 10% · 订正 14%",
            mainLine = "全程黄金九周:数学强化收尾 + 刷题 + 8 月真题按套限时;408 二轮刷题 + 8 月真题分科;英语二真题启动;政治正式入场(徐涛强化 + 肖 1000)。尽量留校或找固定自习室。"
        ),
        StudyPhase(
            "冲刺模考期", "冲刺", "真题为王 · 全真模考 · 生物钟对齐",
            LocalDate.of(2027, 9, 1), EXAM_DATE,
            weeklyHours = "52",
            milestone = "11 月底数学与 408 真题二刷完成 · 肖八选择稳定 40+ · 12 月中旬肖四背完 · 每周两次全真模考",
            alloc = "数学 28% · 408 26% · 政治 20% · 英语 16% · 复盘 10%",
            mainLine = "一切围着真题与模拟卷转:数学/408 真题套卷限时 + 二刷错题 + 模拟卷;英语作文成型;政治肖八(11 月)肖四(12 月);作息全面对齐考试生物钟——上午数学、下午 408、晚上政治英语。"
        )
    )

    fun current(date: LocalDate): StudyPhase? = all.firstOrNull { it.contains(date) }

    /** 备考期外(太早/考完)返回 null,UI 自行隐藏阶段条 */
    fun phaseInfo(now: Long): PhaseInfo? {
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val cur = current(date) ?: return null
        val idx = all.indexOf(cur)
        return PhaseInfo(
            phase = cur,
            dayIdx = ChronoUnit.DAYS.between(cur.start, date) + 1,
            totalDays = cur.daysCount(),
            daysToEnd = ChronoUnit.DAYS.between(date, cur.endInclusive),
            next = all.getOrNull(idx + 1)
        )
    }
}
