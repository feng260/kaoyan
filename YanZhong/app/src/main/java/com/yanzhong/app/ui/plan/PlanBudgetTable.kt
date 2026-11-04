package com.yanzhong.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.remote.PlanBudgetDto
import com.yanzhong.app.data.remote.PlanBudgetSubjectDto
import com.yanzhong.app.data.remote.PlanWarningDto
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.CoralRed
import com.yanzhong.app.ui.theme.ProgressLine
import com.yanzhong.app.ui.theme.RowDivider
import com.yanzhong.app.ui.theme.SectionHeader
import com.yanzhong.app.ui.theme.StatusPill
import com.yanzhong.app.ui.theme.PillTone
import kotlin.math.abs
import kotlin.math.max

/** 单科「已排 vs 目标」的偏差档位:超过 5% 才算偏,避免几分钟的取整差异一直标红 */
internal enum class BudgetDeltaTone { BALANCED, OVER, UNDER }

internal fun subjectDeltaTone(deltaMinutes: Int, targetMinutes: Int): BudgetDeltaTone {
    if (targetMinutes <= 0) {
        return when {
            deltaMinutes > 0 -> BudgetDeltaTone.OVER
            deltaMinutes < 0 -> BudgetDeltaTone.UNDER
            else -> BudgetDeltaTone.BALANCED
        }
    }
    val tolerance = max(1, (targetMinutes * 0.05).toInt())
    return when {
        deltaMinutes > tolerance -> BudgetDeltaTone.OVER
        deltaMinutes < -tolerance -> BudgetDeltaTone.UNDER
        else -> BudgetDeltaTone.BALANCED
    }
}

/** 计划警告的统一文案:服务端 message 已带具体数字优先用,缺失时按 code 兜底 */
internal fun budgetWarningText(warning: PlanWarningDto): String {
    val base = warning.message.ifBlank {
        val subject = warning.subject?.takeIf { it.isNotBlank() } ?: "某科"
        when (warning.code) {
            "SHORTFALL" -> "已确认任务超出有效容量"
            "SUBJECT_OVER_BUDGET" -> "$subject 实排超过目标"
            "MILESTONE_RISK" -> "$subject 里程碑前可能完不成"
            "ESTIMATED_SUBJECT" -> "$subject 的剩余量是估算值"
            "TOTAL_OVER_BUDGET" -> "总排量超过目标"
            "DAY_OVERLOADED" -> "有日期的安排超出当天容量"
            else -> "计划有需要留意的地方"
        }
    }
    return listOfNotNull(warning.date?.takeIf { it.isNotBlank() }, base).joinToString(" · ")
}

/**
 * 草案预算表:总量条 + 逐科目标/已排/偏差 + 警告。
 * 用途是让考生在确认前就能看出「哪科排多了、哪科里程碑前完不成」。
 */
@Composable
internal fun PlanBudgetTable(
    budget: PlanBudgetDto,
    subjects: List<PlanBudgetSubjectDto>,
    warnings: List<PlanWarningDto> = emptyList(),
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (warnings.isNotEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("需要留意", style = MaterialTheme.typography.titleSmall)
                    warnings.forEach { warning ->
                        Text("· ${budgetWarningText(warning)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("预算表", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (budget.shortfallMinutes > 0) {
                    StatusPill("缺口 ${formatMinutesCn(budget.shortfallMinutes)}", PillTone.WARNING)
                } else {
                    StatusPill("已排 ${formatMinutesCn(budget.scheduledMinutes)}", PillTone.NEUTRAL)
                }
            }
            Spacer(Modifier.height(8.dp))
            ProgressLine(progress = budget.utilization.toFloat().coerceIn(0f, 1f))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BudgetStat("已排", formatMinutesCn(budget.scheduledMinutes), Modifier.weight(1f))
                BudgetStat("目标", formatMinutesCn(budget.targetMinutes), Modifier.weight(1f))
                BudgetStat("有效容量", formatMinutesCn(budget.effectiveCapacityMinutes), Modifier.weight(1f))
            }
            if (budget.shortfallMinutes > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "已确认任务比有效容量多 ${formatMinutesCn(budget.shortfallMinutes)}，排不下的部分会标成缺口",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            budget.warnings.forEach { warning ->
                Spacer(Modifier.height(4.dp))
                Text("· $warning", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (subjects.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SectionHeader("逐科对照")
                subjects.forEachIndexed { index, subject ->
                    if (index > 0) RowDivider(startIndent = 0.dp)
                    SubjectBudgetRow(subject)
                }
            }
        }
    }
}

@Composable
private fun BudgetStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SubjectBudgetRow(subject: PlanBudgetSubjectDto) {
    val tone = subjectDeltaTone(subject.deltaMinutes, subject.targetMinutes)
    val deltaColor = when (tone) {
        BudgetDeltaTone.OVER -> CoralRed
        BudgetDeltaTone.UNDER -> MaterialTheme.colorScheme.tertiary
        BudgetDeltaTone.BALANCED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(subject.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (subject.estimated) {
                Text("估算", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                "已排 ${formatMinutesCn(subject.scheduledMinutes)} / 目标 ${formatMinutesCn(subject.targetMinutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val milestone = if (subject.milestoneMinutes > 0) {
                "里程碑前 ${formatMinutesCn(subject.beforeMilestoneMinutes)} / ${formatMinutesCn(subject.milestoneMinutes)}" +
                    if (subject.milestoneDate.isNotBlank()) "（${subject.milestoneDate}）" else ""
            } else ""
            Text(milestone, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                when (tone) {
                    BudgetDeltaTone.BALANCED -> "持平"
                    BudgetDeltaTone.OVER -> "多 ${formatMinutesCn(abs(subject.deltaMinutes))}"
                    BudgetDeltaTone.UNDER -> "少 ${formatMinutesCn(abs(subject.deltaMinutes))}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = deltaColor
            )
        }
    }
}
