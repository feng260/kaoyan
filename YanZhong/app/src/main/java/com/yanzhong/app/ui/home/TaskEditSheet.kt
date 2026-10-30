package com.yanzhong.app.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.yanzhong.app.data.db.RepeatRule
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.ui.theme.AppSheet
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.SegmentedChips
import com.yanzhong.app.ui.theme.TextAction
import com.yanzhong.app.ui.theme.ToggleChip
import com.yanzhong.app.util.TimeUtils
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 新增 / 编辑任务:3 个字段即可创建,其余可后补(PRD 3.3) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditSheet(
    initial: TaskEntity?,
    subjects: List<SubjectEntity>,
    onSave: (TaskEntity) -> Unit,
    onDelete: ((TaskEntity) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var subjectId by remember { mutableStateOf(initial?.subjectId ?: subjects.firstOrNull()?.id ?: 0L) }
    var priority by remember { mutableIntStateOf(initial?.priority ?: 1) }
    var pomodoroEstimate by remember { mutableIntStateOf(initial?.pomodoroEstimate ?: 1) }
    var repeatRule by remember { mutableIntStateOf(initial?.repeatRule ?: RepeatRule.NONE) }
    var repeatDays by remember { mutableIntStateOf(initial?.repeatDays ?: 0) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var dueAt by remember { mutableStateOf(initial?.dueAt) }
    var showDatePicker by remember { mutableStateOf(false) }
    /** 删除二次确认:防止误触丢失任务及其专注记录 */
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val save: () -> Unit = {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) {
            onSave(
                (initial ?: TaskEntity(subjectId = subjectId, title = trimmed)).copy(
                    subjectId = subjectId,
                    title = trimmed,
                    priority = priority,
                    pomodoroEstimate = pomodoroEstimate,
                    dueAt = dueAt,
                    repeatRule = repeatRule,
                    repeatDays = repeatDays,
                    note = note
                )
            )
            onDismiss()
        }
    }

    AppSheet(
        title = if (initial == null) "新增任务" else "编辑任务",
        onDismiss = onDismiss
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("标题") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))

        FormLabel("科目")
        SegmentedChips(
            options = subjects.map { it.name },
            selectedIndex = subjects.indexOfFirst { it.id == subjectId },
            onSelect = { index -> subjects.getOrNull(index)?.let { subjectId = it.id } }
        )
        Spacer(Modifier.height(14.dp))

        FormLabel("截止日期")
        SegmentedChips(
            options = listOf("无", "今天", "明天", "选择日期"),
            selectedIndex = when (val due = dueAt) {
                null -> 0
                else -> when (TimeUtils.daysBetween(TimeUtils.now(), due)) {
                    0L -> 1
                    1L -> 2
                    else -> -1
                }
            },
            onSelect = { index ->
                when (index) {
                    0 -> dueAt = null
                    1 -> dueAt = TimeUtils.dayStartOf() + 9 * 3600_000L
                    2 -> dueAt = TimeUtils.dayStartOf() + 24 * 3600_000L + 9 * 3600_000L
                    3 -> showDatePicker = true
                }
            }
        )
        Spacer(Modifier.height(14.dp))

        FormLabel("优先级")
        SegmentedChips(
            options = listOf("高", "中", "低"),
            selectedIndex = priority,
            onSelect = { priority = it }
        )
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("预计番茄数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextAction(
                text = "−",
                onClick = { if (pomodoroEstimate > 1) pomodoroEstimate-- },
                enabled = pomodoroEstimate > 1
            )
            Text(
                "$pomodoroEstimate",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 36.dp)
            )
            TextAction(
                text = "+",
                onClick = { if (pomodoroEstimate < 20) pomodoroEstimate++ },
                enabled = pomodoroEstimate < 20
            )
        }
        Spacer(Modifier.height(14.dp))

        FormLabel("重复")
        SegmentedChips(
            options = listOf("不重复", "每日", "每周"),
            selectedIndex = repeatRule,
            onSelect = { repeatRule = it }
        )
        if (repeatRule == RepeatRule.WEEKLY) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, day ->
                    val bit = 1 shl i
                    ToggleChip(
                        text = day,
                        selected = (repeatDays and bit) != 0,
                        onClick = {
                            repeatDays = if ((repeatDays and bit) != 0) repeatDays and bit.inv()
                            else repeatDays or bit
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("备注（可选）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1
        )
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (initial != null && onDelete != null) {
                TextAction(text = "删除", onClick = { showDeleteConfirm = true }, danger = true)
            }
            PrimaryButton(
                text = "保存",
                onClick = save,
                enabled = title.isNotBlank(),
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dueAt ?: TimeUtils.now()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val zone = ZoneId.systemDefault()
                        dueAt = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            .atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showDeleteConfirm && initial != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除这个任务？") },
            text = {
                Text("「${initial.title}」及其专注记录将被永久删除，删除后无法恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(initial)
                        onDismiss()
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 弹层内小节标签:表单区块不再用 16sp 标题,避免多级标题压垮表单 */
@Composable
private fun FormLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}
