package com.yanzhong.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.remote.PlanItemDto
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppSheet
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.SecondaryButton
import com.yanzhong.app.ui.theme.TextAction
import com.yanzhong.app.ui.theme.ToggleChip
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 任务编辑的本地变更集:null 表示该字段不动 */
data class PlanItemEdit(
    val planDate: String? = null,
    val minutes: Int? = null,
    val title: String? = null,
    val subject: String? = null,
    val locked: Boolean? = null
)

/** 乐观更新:先把编辑合并进本地计划,失败时由 VM 用旧计划整体回滚 */
internal fun optimisticItemPatch(item: PlanItemDto, edit: PlanItemEdit): PlanItemDto = item.copy(
    planDate = edit.planDate ?: item.planDate,
    minutes = edit.minutes ?: item.minutes,
    title = edit.title ?: item.title,
    subject = edit.subject ?: item.subject,
    locked = edit.locked ?: item.locked
)

/** 当天还能再排多少分钟;容量未知(<=0)时不假装有额度 */
internal fun dayRemainingMinutes(items: List<PlanItemDto>, capacity: Int, day: LocalDate): Int {
    if (capacity <= 0) return 0
    val planned = items.filter { it.planDate == day.toString() }.sumOf { it.minutes }
    return (capacity - planned).coerceAtLeast(0)
}

/**
 * 单条任务的编辑弹层:日期 / 时长 / 标题 / 科目 / 锁定。
 * 手工编辑走硬上限(100% 空档),超了服务端会返回当天容量与建议缩短量。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanItemEditSheet(
    initial: PlanItemDto?,
    defaultDate: String,
    subjectOptions: List<String>,
    busy: Boolean,
    error: String?,
    suggestShrinkMinutes: Int?,
    onSave: (PlanItemEdit) -> Unit,
    onDelete: ((PlanItemDto) -> Unit)?,
    onDismiss: () -> Unit
) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var subject by remember(initial) { mutableStateOf(initial?.subject ?: subjectOptions.firstOrNull().orEmpty()) }
    var dateText by remember(initial) { mutableStateOf(initial?.planDate ?: defaultDate) }
    var minutes by remember(initial) { mutableIntStateOf(initial?.minutes?.takeIf { it > 0 } ?: 30) }
    var locked by remember(initial) { mutableStateOf(initial?.locked == true) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    AppSheet(
        title = if (initial == null) "添加任务" else "编辑任务",
        onDismiss = onDismiss
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("任务内容") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        FormLabel("科目")
        if (subjectOptions.isEmpty()) {
            Text("计划里还没有科目，先在档案里补上", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                subjectOptions.forEach { option ->
                    ToggleChip(text = option, selected = subject == option,
                        onClick = { if (!busy) subject = option })
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        FormLabel("日期")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dateText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextAction("改日期", onClick = { showDatePicker = true }, enabled = !busy)
        }
        Spacer(Modifier.height(12.dp))

        FormLabel("时长（15 分钟为一档）")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextAction(
                text = "− 15",
                onClick = { if (minutes > 5) minutes = (minutes - 15).coerceAtLeast(5) },
                enabled = !busy && minutes > 5
            )
            Text(
                "$minutes 分钟",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).widthIn(min = 90.dp)
            )
            TextAction(
                text = "+ 15",
                onClick = { if (minutes < 480) minutes = (minutes + 15).coerceAtMost(480) },
                enabled = !busy && minutes < 480
            )
        }
        suggestShrinkMinutes?.let { target ->
            TextAction(
                text = "缩短到 $target 分钟",
                onClick = { minutes = target.coerceIn(5, 480) },
                enabled = !busy
            )
        }
        Spacer(Modifier.height(12.dp))

        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("锁定这项", style = MaterialTheme.typography.bodyMedium)
                    Text("锁定后 AI 调整时不会移动、改写或删除它",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = locked, onCheckedChange = { if (!busy) locked = it },
                    enabled = !busy)
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (initial != null && onDelete != null) {
                SecondaryButton(
                    text = "删除",
                    onClick = { showDeleteConfirm = true },
                    enabled = !busy,
                    danger = true,
                    modifier = Modifier.weight(1f)
                )
            }
            PrimaryButton(
                text = if (busy) "正在保存…" else "保存",
                onClick = {
                    onSave(
                        PlanItemEdit(
                            planDate = dateText,
                            minutes = minutes.coerceIn(5, 480),
                            title = title.trim().takeIf { it.isNotEmpty() },
                            subject = subject.takeIf { it.isNotBlank() },
                            locked = locked
                        )
                    )
                },
                enabled = !busy && title.isNotBlank() && subject.isNotBlank(),
                loading = busy,
                modifier = Modifier.weight(1f)
            )
        }
        TextAction(
            text = "取消",
            onClick = onDismiss,
            enabled = !busy,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }

    if (showDatePicker) {
        val initialMillis = runCatching {
            LocalDate.parse(dateText).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        dateText = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) {
            DatePicker(state = picker)
        }
    }

    if (showDeleteConfirm && initial != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除这项任务？") },
            text = { Text("「${initial.title}」会从计划里移除，确认后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete(initial)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp))
}
