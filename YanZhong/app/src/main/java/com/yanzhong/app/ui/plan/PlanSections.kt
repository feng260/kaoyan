package com.yanzhong.app.ui.plan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.EnglishColor
import com.yanzhong.app.ui.theme.MathColor
import com.yanzhong.app.ui.theme.NeutralTagColor
import com.yanzhong.app.ui.theme.PoliticsColor
import com.yanzhong.app.ui.theme.SectionCard
import com.yanzhong.app.ui.theme.Subject408Color
import com.yanzhong.app.util.PersonalPlan
import com.yanzhong.app.util.Phases
import com.yanzhong.app.util.StudyPhase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 标签 → 学科色(引用主题色板,不再复制 hex) */
fun tagColor(tag: String): Color = when (tag) {
    PersonalPlan.TAG_MATH -> MathColor
    PersonalPlan.TAG_CS -> Subject408Color
    PersonalPlan.TAG_EN -> EnglishColor
    PersonalPlan.TAG_POL -> PoliticsColor
    PersonalPlan.TAG_GEN -> NeutralTagColor
    PersonalPlan.TAG_COURSE -> Color(0xFF475569)
    else -> Color(0xFF94A3B8) // 休息
}

@Composable
private fun NoteText(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(10.dp)
        )
    }
}

/** 模板时间块行:色点 + 时间 + (单/双周徽标) + 内容 */
@Composable
private fun BlockRow(block: PersonalPlan.TBlock, compact: Boolean = false) {
    Row(
        Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(tagColor(block.tag))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            block.time,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(if (compact) 78.dp else 92.dp)
        )
        if (block.oddOnly || block.evenOnly) {
            WeekBranchBadge(isOdd = block.oddOnly)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (compact) block.short.ifEmpty { block.content } else block.content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 单双周分支徽标:标记该时间块只在单周 / 双周出现 */
@Composable
private fun WeekBranchBadge(isOdd: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
    ) {
        Text(
            if (isOdd) "单周" else "双周",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

// ---------- 日模板页 ----------

/** 今日应执行的日模板(按阶段 + 星期自动选择,时间块已按教学周过滤) */
@Composable
internal fun TodayTemplateCard(template: PersonalPlan.DayTemplate, today: LocalDate) {
    val weekLabel = PersonalPlan.teachingWeekLabel(today)
    SectionCard(
        title = "今日模板 · ${template.name}",
        subtitle = buildString {
            append("${template.whenText} · 净学习 ${template.hours}")
            if (weekLabel != null) append(" · $weekLabel")
        }
    ) {
        template.blocksFor(today).forEach { BlockRow(it) }
        Spacer(Modifier.height(8.dp))
        NoteText(template.note)
    }
}

/** 六套日模板总览:分组可折叠(默认只展开当前学期组),组内模板再逐个展开 */
@Composable
internal fun TemplateGroupsCard() {
    SectionCard(
        title = "六套日模板",
        subtitle = "按当前阶段选模板执行——时间块是骨架,任务量是弹性:状态好加量、状态差保底(保底 = 数学 + 单词)"
    ) {
        PersonalPlan.templateGroups.forEachIndexed { groupIdx, group ->
            if (groupIdx > 0) Spacer(Modifier.height(10.dp))
            TemplateGroupRow(group = group, defaultExpanded = groupIdx == 0)
        }
    }
}

@Composable
private fun TemplateGroupRow(group: PersonalPlan.TemplateGroup, defaultExpanded: Boolean) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "适用:${group.applies}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    group.templates.forEach { TemplateRow(it) }
                }
            }
        }
    }
}

@Composable
private fun TemplateRow(template: PersonalPlan.DayTemplate) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${template.name}(${template.whenText})",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "净学习 ${template.hours}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    template.blocks.forEach { BlockRow(it) }
                    Spacer(Modifier.height(6.dp))
                    NoteText(template.note)
                }
            }
        }
    }
}

/** 本学期课表(2026–2027 上 · 单双周) */
@Composable
internal fun CourseTableCard() {
    SectionCard(
        title = "本学期课表",
        subtitle = "2026–2027 上 · 单双周(以课程表 App 为准)"
    ) {
        PersonalPlan.semesterCourses.forEach { course ->
            Row(
                Modifier.padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (course.isExamRelated) tagColor(PersonalPlan.TAG_CS)
                            else tagColor(PersonalPlan.TAG_COURSE)
                        )
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(course.name, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(6.dp))
                        WeeksBadge(course.weeks)
                    }
                    Text(
                        course.time,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        course.relation,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (course.isExamRelated) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        NoteText(PersonalPlan.COURSE_NOTE)
    }
}

/** 周次徽标:涉及单双周的课程用弱底色胶囊突出,纯每周课程弱化 */
@Composable
private fun WeeksBadge(weeks: String) {
    val hasParity = weeks.contains("单周") || weeks.contains("双周")
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (hasParity) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Text(
            weeks,
            style = MaterialTheme.typography.labelSmall,
            color = if (hasParity) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

/** 每周固定节奏 */
@Composable
internal fun WeeklyRhythmCard() {
    SectionCard(title = "每周节奏", subtitle = "固定不变,状态崩的一周按最后一条执行") {
        PersonalPlan.weeklyRhythm.forEach { (day, content) ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Text(
                    day,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(92.dp)
                )
                Text(content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 每日复盘三问 */
@Composable
internal fun ReviewQuestionsCard() {
    SectionCard(title = "每日复盘三问", subtitle = "闭馆回宿舍后 · 15 分钟") {
        PersonalPlan.reviewQuestions.forEachIndexed { idx, (q, a) ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Text(
                    "${idx + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(20.dp)
                )
                Column {
                    Text(q, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "—— $a",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------- 全程规划页 ----------

/** 五阶段总览(计划第 02 节):纵向进度轨道 + 当前阶段强调块——日期/主线/里程碑/占比分层呈现 */
@Composable
internal fun PhasesCard(todayMillis: Long) {
    val today = remember(todayMillis) {
        Instant.ofEpochMilli(todayMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    SectionCard(
        title = "五阶段总览",
        subtitle = "468 天 · 5 个阶段——阶段之间是接力关系:上一阶段里程碑没完成,下一阶段就要先补课"
    ) {
        Phases.all.forEachIndexed { idx, phase ->
            if (idx > 0) TrackGap(color = trackColorOf(phase, today))
            PhaseRow(phase = phase, index = idx, today = today)
        }
    }
}

@Composable
private fun trackColorOf(phase: StudyPhase, today: LocalDate): Color = when {
    phase.contains(today) -> MaterialTheme.colorScheme.primary
    today.isAfter(phase.endInclusive) -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    else -> MaterialTheme.colorScheme.surfaceVariant
}

/** 轨道连接段:接续上一行徽标底部到下一行徽标顶部 */
@Composable
private fun TrackGap(color: Color) {
    Box(
        Modifier
            .padding(start = 11.5.dp)
            .width(3.dp)
            .height(14.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}

/** 单个阶段行:左徽标 + 右内容;当前阶段包入浅色强调块 */
@Composable
private fun PhaseRow(phase: StudyPhase, index: Int, today: LocalDate) {
    val isCurrent = phase.contains(today)
    val isPast = today.isAfter(phase.endInclusive)
    Row(verticalAlignment = Alignment.Top) {
        PhaseBadge(index = index, isCurrent = isCurrent, isPast = isPast)
        Spacer(Modifier.width(10.dp))
        if (isCurrent) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
                modifier = Modifier.weight(1f)
            ) {
                PhaseContent(
                    phase = phase,
                    today = today,
                    isCurrent = true,
                    isPast = false,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else {
            PhaseContent(
                phase = phase,
                today = today,
                isCurrent = false,
                isPast = isPast,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 三态徽标:已完成打勾 / 进行中主色 / 未开始弱化 */
@Composable
private fun PhaseBadge(index: Int, isCurrent: Boolean, isPast: Boolean) {
    Surface(
        shape = CircleShape,
        color = when {
            isCurrent -> MaterialTheme.colorScheme.primary
            isPast -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.size(26.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isPast) {
                Icon(
                    AppIcons.Check,
                    contentDescription = "已完成",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusPill(isCurrent: Boolean, isPast: Boolean) {
    val text: String
    val bg: Color
    val fg: Color
    when {
        isCurrent -> {
            text = "进行中"; bg = MaterialTheme.colorScheme.primary
            fg = MaterialTheme.colorScheme.onPrimary
        }
        isPast -> {
            text = "已完成"; bg = MaterialTheme.colorScheme.surfaceVariant
            fg = MaterialTheme.colorScheme.onSurfaceVariant
        }
        else -> {
            text = "未开始"; bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            fg = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun PhaseContent(
    phase: StudyPhase,
    today: LocalDate,
    isCurrent: Boolean,
    isPast: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                phase.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isPast) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(8.dp))
            StatusPill(isCurrent = isCurrent, isPast = isPast)
            if (isCurrent) {
                Spacer(Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        "剩 ${ChronoUnit.DAYS.between(today, phase.endInclusive)} 天",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            buildString {
                append(fmtYmd(phase.start)).append(" – ").append(fmtYmd(phase.endInclusive))
                append(" · ").append(weeksLabel(phase.daysCount())).append(" 周")
                if (phase.weeklyHours.isNotEmpty()) append(" · ${phase.weeklyHours} h/周")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isCurrent) {
            val dayIdx = ChronoUnit.DAYS.between(phase.start, today) + 1
            val total = phase.daysCount()
            Spacer(Modifier.height(8.dp))
            Text(
                "第 $dayIdx / $total 天",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((dayIdx.toFloat() / total).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "主线:${phase.mainLine.ifEmpty { phase.slogan }}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isPast) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface.copy(alpha = if (isCurrent) 1f else 0.85f),
            maxLines = if (isCurrent) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "里程碑:${phase.milestone}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp)
            )
        }
        AllocChips(phase.alloc)
    }
}

/** 科目占比 → 彩点胶囊流,四科沿用全局学科色 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllocChips(alloc: String) {
    if (alloc.isEmpty()) return
    val tokens = alloc.split("·").map { it.trim() }.filter { it.isNotEmpty() }
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tokens.forEach { token ->
            val parts = token.split(Regex("\\s+"), limit = 2)
            val name = parts.firstOrNull().orEmpty()
            val pct = parts.getOrNull(1).orEmpty()
            val dotColor = when (name) {
                "数学" -> tagColor(PersonalPlan.TAG_MATH)
                "408" -> tagColor(PersonalPlan.TAG_CS)
                "英语" -> tagColor(PersonalPlan.TAG_EN)
                "政治" -> tagColor(PersonalPlan.TAG_POL)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (pct.isEmpty()) name else "$name $pct",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun fmtYmd(d: LocalDate): String =
    "${d.year}.${d.monthValue.toString().padStart(2, '0')}.${d.dayOfMonth.toString().padStart(2, '0')}"

private fun weeksLabel(days: Long): String {
    val weeks = days / 7.0
    return if (weeks % 1.0 == 0.0) weeks.toInt().toString()
    else String.format(Locale.ROOT, "%.1f", weeks)
}

/** 四科目标分数 */
@Composable
internal fun ScoreTargetsCard() {
    SectionCard(
        title = "目标分数",
        subtitle = "稳妥线 ≈ 211 / 普通 985 计算机线;380+ 冲强校"
    ) {
        PersonalPlan.scoreTargets.forEach { target ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        target.subject,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${target.current} · ${target.positioning}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "稳妥 ${target.safe} / 冲刺 ${target.sprint}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "满分 ${target.full}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 单科全程规划卡(策略 + 时间线可展开) */
@Composable
internal fun SubjectPlanCard(plan: PersonalPlan.SubjectPlan) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title = plan.name, subtitle = plan.badge) {
        Text(plan.strategy, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        plan.rows.take(if (expanded) plan.rows.size else 1).forEach { (period, task) ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Text(
                    period,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(108.dp)
                )
                Text(
                    task,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        TextButtonRow(
            text = if (expanded) "收起时间线" else "展开全部 ${plan.rows.size} 个时间段",
            onClick = { expanded = !expanded }
        )
        Spacer(Modifier.height(4.dp))
        NoteText(plan.note)
    }
}

/** 启动四周计划 */
@Composable
internal fun LaunchWeeksCard() {
    SectionCard(
        title = "启动四周计划",
        subtitle = "前 28 天已排到天(2026.09.07 – 10.04):固定作息、数学手感、单词惯性"
    ) {
        PersonalPlan.launchWeeks.forEach { week ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        week.week,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${week.name}(${week.dates})",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    week.tasks.forEach {
                        Text(
                            "· $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                    Text(
                        "✔ ${week.milestone}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/** 关键日期线 + 初试时间安排 */
@Composable
internal fun KeyDatesCard() {
    SectionCard(title = "关键日期线", subtitle = "以研招网及中国教育考试网当年官方公布为准") {
        PersonalPlan.keyDates.forEach { kd ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Text(
                    kd.date,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(96.dp)
                )
                Text(kd.event, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("初试时间安排", style = MaterialTheme.typography.titleMedium)
        PersonalPlan.examSchedule.forEach { (session, subject) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(
                    session,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(76.dp)
                )
                Text(subject, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------- 军规 · 资料页 ----------

/** 十条军规 */
@Composable
internal fun RulesCard() {
    SectionCard(
        title = "十条军规",
        subtitle = "计划会过时、会被打乱,但这十条全程有效。状态崩的时候,先回头看这里"
    ) {
        PersonalPlan.rules.forEachIndexed { idx, rule ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "${idx + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(rule.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        rule.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 资料清单(按购买时机) */
@Composable
internal fun MaterialsCard() {
    SectionCard(title = "资料清单", subtitle = PersonalPlan.MATERIAL_NOTE) {
        PersonalPlan.materialGroups.forEachIndexed { idx, group ->
            if (idx > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(tagColor(group.tag))
                )
                Spacer(Modifier.width(8.dp))
                Text(group.subject, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            group.items.forEach { item ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            item.use,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (item.buyNow) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            item.timing,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.buyNow) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextButtonRow(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(text) }
}

// ---------- 五阶段作战日历(M2「三阶段拆解到日」) ----------

/** 五阶段逐一拆到关键推进节点 + 四科到日动作:只读展示,当前阶段/节点自动高亮 */
@Composable
internal fun CampaignCalendarCard(today: LocalDate) {
    val currentPhaseShort = Phases.current(today)?.shortName
    val currentNode = PersonalPlan.currentNode(today)
    SectionCard(
        title = "五阶段作战日历",
        subtitle = "把 468 天拆到「阶段 → 推进节点 → 四科到日动作」:当前节点高亮「现在」,照着今天做就够"
    ) {
        PersonalPlan.campaign.forEachIndexed { idx, phase ->
            if (idx > 0) Spacer(Modifier.height(10.dp))
            PhaseCampaignBlock(
                phase = phase,
                isCurrent = phase.phaseShort == currentPhaseShort,
                isPast = today.isAfter(phase.end),
                currentNode = currentNode
            )
        }
    }
}

@Composable
private fun PhaseCampaignBlock(
    phase: PersonalPlan.PhaseCampaign,
    isCurrent: Boolean,
    isPast: Boolean,
    currentNode: PersonalPlan.CampaignNode?
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        phase.phaseName,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isPast) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(isCurrent = isCurrent, isPast = isPast)
                }
                Text(
                    "${fmtYmd(phase.start)} – ${fmtYmd(phase.end)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            phase.nodes.forEach { node ->
                CampaignNodeRow(node = node, isCurrentNode = node == currentNode)
            }
        }
    }
}

@Composable
private fun CampaignNodeRow(node: PersonalPlan.CampaignNode, isCurrentNode: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isCurrentNode) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    ) {
        Column(Modifier.padding(vertical = 6.dp, horizontal = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${fmtYmd(node.start)}–${fmtYmd(node.end)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    node.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (isCurrentNode) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            "现在",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            CampaignSubjectLine(PersonalPlan.TAG_MATH, "数学", node.math)
            CampaignSubjectLine(PersonalPlan.TAG_CS, "408", node.cs)
            CampaignSubjectLine(PersonalPlan.TAG_EN, "英语", node.english)
            CampaignSubjectLine(PersonalPlan.TAG_POL, "政治", node.politics)
        }
    }
}

@Composable
private fun CampaignSubjectLine(tag: String, label: String, text: String) {
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(tagColor(tag))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
            modifier = Modifier.weight(1f)
        )
    }
}
