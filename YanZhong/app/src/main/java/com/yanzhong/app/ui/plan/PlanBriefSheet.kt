package com.yanzhong.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.remote.BriefSubjectPatch
import com.yanzhong.app.data.remote.PlanBriefDto
import com.yanzhong.app.data.remote.PlanBriefResp
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppSheet
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.SectionHeader
import com.yanzhong.app.ui.theme.TextAction
import com.yanzhong.app.ui.theme.ToggleChip
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val weekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 生成前的口径确认:让考生先看清「AI 拿什么去排」——剩余量、截止日、每周空档、固定占用,
 * 以及按 85% 负荷算出来的预算。确认后才把 briefHash 交给生成接口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanBriefSheet(
    resp: PlanBriefResp,
    saving: Boolean,
    onConfirm: (List<BriefSubjectPatch>) -> Unit,
    onDismiss: () -> Unit
) {
    val brief = resp.brief ?: PlanBriefDto()
    val minutesTexts = remember(brief) {
        mutableStateMapOf<String, String>().apply {
            brief.examSubjects.forEach { put(it.name, it.remainingMinutes.toString()) }
        }
    }
    val milestoneDates = remember(brief) {
        mutableStateMapOf<String, String>().apply {
            brief.examSubjects.forEach { put(it.name, it.milestoneDate) }
        }
    }
    val estimatedFlags = remember(brief) {
        mutableStateMapOf<String, Boolean>().apply {
            brief.examSubjects.forEach { put(it.name, it.estimated) }
        }
    }
    var datePickerFor by remember { mutableStateOf<String?>(null) }

    AppSheet(title = "生成前，先确认计划口径", onDismiss = onDismiss) {
        Text(
            "AI 会按这份口径排到考前。改过的字段随本次生成保存，确认前不会改动现有计划。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        BudgetPreview(resp)
        Spacer(Modifier.height(16.dp))

        SectionHeader("考试科目")
        Spacer(Modifier.height(6.dp))
        if (brief.examSubjects.isEmpty()) {
            Text("还没有确认的考试科目，先在对话里补上", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        brief.examSubjects.forEach { subject ->
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(subject.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (estimatedFlags[subject.name] == true) {
                        Text("AI 估算", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                val meta = listOf(subject.progress, subject.scope).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = minutesTexts[subject.name].orEmpty(),
                        onValueChange = { minutesTexts[subject.name] = it.filter(Char::isDigit).take(5) },
                        label = { Text("剩余分钟") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    ToggleChip(
                        text = "估算值",
                        selected = estimatedFlags[subject.name] == true,
                        onClick = { estimatedFlags[subject.name] = !(estimatedFlags[subject.name] ?: false) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val date = milestoneDates[subject.name].orEmpty()
                    Text(
                        if (date.isBlank()) "未设里程碑截止日" else "里程碑 ${subject.milestone.ifBlank { "阶段" }} · $date",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextAction(text = "改日期", onClick = { datePickerFor = subject.name })
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (brief.availability.isNotEmpty() || resp.capacityByWeekday.isNotEmpty()) {
            SectionHeader("每周空档（只读）")
            Spacer(Modifier.height(6.dp))
            AppCard {
                resp.capacityByWeekday.forEach { day ->
                    val label = weekdayLabels.getOrElse(day.weekday - 1) { "周${day.weekday}" }
                    Text("$label · ${formatMinutesCn(day.minutes)}", style = MaterialTheme.typography.bodyMedium)
                }
                if (resp.capacityByWeekday.isEmpty()) {
                    brief.availability.forEach { day ->
                        val windows = day.windows.joinToString("、") { "${it.start}–${it.end}" }
                        val label = weekdayLabels.getOrElse(day.weekday - 1) { "周${day.weekday}" }
                        Text("$label · $windows", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (brief.fixedCommitments.isNotEmpty()) {
            SectionHeader("固定占用（只读）")
            Spacer(Modifier.height(6.dp))
            AppCard {
                brief.fixedCommitments.forEach { item ->
                    val label = weekdayLabels.getOrElse(item.weekday - 1) { "周${item.weekday}" }
                    Text("$label ${item.start}–${item.end} · ${item.label}",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (resp.warnings.isNotEmpty()) {
            AppCard {
                Text("生成前提醒", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error)
                resp.warnings.forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        PrimaryButton(
            text = if (saving) "正在保存口径…" else "确认无误，生成计划",
            onClick = {
                val edits = brief.examSubjects.associate { subject ->
                    subject.name to BriefSubjectEdit(
                        remainingMinutes = minutesTexts[subject.name]?.toIntOrNull()
                            ?: subject.remainingMinutes,
                        milestoneDate = milestoneDates[subject.name].orEmpty(),
                        estimated = estimatedFlags[subject.name] ?: subject.estimated
                    )
                }
                onConfirm(briefPatchFromEdits(brief, edits))
            },
            enabled = !saving && brief.examSubjects.isNotEmpty(),
            loading = saving
        )
        TextAction(
            text = "先不生成",
            onClick = onDismiss,
            enabled = !saving,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }

    datePickerFor?.let { subjectName ->
        val current = milestoneDates[subjectName].orEmpty()
        val initialMillis = runCatching {
            LocalDate.parse(current).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { datePickerFor = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        milestoneDates[subjectName] = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    datePickerFor = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { datePickerFor = null }) { Text("取消") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** 预算预览:已确认 / AI 估算补充 / 目标 / 有效容量 / 缺口,让考生生成前就知道排不排得下 */
@Composable
private fun BudgetPreview(resp: PlanBriefResp) {
    val budget = resp.budget
    AppCard {
        Text("生成预算", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        BudgetLine("已确认任务", formatMinutesCn(budget.confirmedMinutes))
        BudgetLine("AI 估算补充", formatMinutesCn(budget.estimatedBudgetMinutes))
        if (budget.targetMinutes > 0) BudgetLine("目标总量", formatMinutesCn(budget.targetMinutes))
        BudgetLine("有效容量（按 85% 负荷）", formatMinutesCn(budget.effectiveCapacityMinutes))
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        if (budget.shortfallMinutes > 0) {
            BudgetLine("容量缺口", formatMinutesCn(budget.shortfallMinutes), error = true)
        } else {
            val slack = (budget.effectiveCapacityMinutes - budget.targetMinutes).coerceAtLeast(0)
            BudgetLine("可留白", formatMinutesCn(slack))
        }
        if (budget.deficits.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            budget.deficits.forEach { deficit ->
                Text(
                    "缺 ${deficit.subject.ifBlank { "任务" }} ${formatMinutesCn(deficit.missingMinutes)}" +
                        if (deficit.milestone.isNotBlank()) "（${deficit.milestone}）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun BudgetLine(label: String, value: String, error: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}
