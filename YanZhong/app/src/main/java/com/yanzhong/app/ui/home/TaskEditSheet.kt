package com.yanzhong.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.db.RepeatRule
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                if (initial == null) "新增任务" else "编辑任务",
                style = MaterialTheme.typography.headlineLarge
            )
            Spacer(Modifier.padding(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.padding(10.dp))

            Text("科目", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                subjects.forEach { subject ->
                    FilterChip(
                        selected = subjectId == subject.id,
                        onClick = { subjectId = subject.id },
                        label = { Text(subject.name) }
                    )
                }
            }
            Spacer(Modifier.padding(10.dp))

            Text("截止日期", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = dueAt == null,
                    onClick = { dueAt = null },
                    label = { Text("无") }
                )
                FilterChip(
                    selected = dueAt != null && TimeUtils.daysBetween(
                        TimeUtils.now(), dueAt ?: 0
                    ) == 0L,
                    onClick = { dueAt = TimeUtils.dayStartOf() + 9 * 3600_000L },
                    label = { Text("今天") }
                )
                FilterChip(
                    selected = dueAt != null && TimeUtils.daysBetween(
                        TimeUtils.now(), dueAt ?: 0
                    ) == 1L,
                    onClick = { dueAt = TimeUtils.dayStartOf() + 24 * 3600_000L + 9 * 3600_000L },
                    label = { Text("明天") }
                )
                FilterChip(
                    selected = false,
                    onClick = { showDatePicker = true },
                    label = { Text("选择日期") }
                )
            }
            Spacer(Modifier.padding(10.dp))

            Text("优先级", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = priority == 0, onClick = { priority = 0 }, label = { Text("高") })
                FilterChip(selected = priority == 1, onClick = { priority = 1 }, label = { Text("中") })
                FilterChip(selected = priority == 2, onClick = { priority = 2 }, label = { Text("低") })
            }
            Spacer(Modifier.padding(10.dp))

            Text("预计番茄数:$pomodoroEstimate", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { if (pomodoroEstimate > 1) pomodoroEstimate-- }) { Text("− 1") }
                TextButton(onClick = { if (pomodoroEstimate < 20) pomodoroEstimate++ }) { Text("+ 1") }
            }
            Spacer(Modifier.padding(6.dp))

            Text("重复", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = repeatRule == RepeatRule.NONE,
                    onClick = { repeatRule = RepeatRule.NONE },
                    label = { Text("不重复") }
                )
                FilterChip(
                    selected = repeatRule == RepeatRule.DAILY,
                    onClick = { repeatRule = RepeatRule.DAILY },
                    label = { Text("每日") }
                )
                FilterChip(
                    selected = repeatRule == RepeatRule.WEEKLY,
                    onClick = { repeatRule = RepeatRule.WEEKLY },
                    label = { Text("每周") }
                )
            }
            if (repeatRule == RepeatRule.WEEKLY) {
                Spacer(Modifier.padding(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, day ->
                        val bit = 1 shl i
                        FilterChip(
                            selected = (repeatDays and bit) != 0,
                            onClick = {
                                repeatDays = if ((repeatDays and bit) != 0) repeatDays and bit.inv()
                                else repeatDays or bit
                            },
                            label = { Text(day) }
                        )
                    }
                }
            }
            Spacer(Modifier.padding(10.dp))

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注(可选)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1
            )
            Spacer(Modifier.padding(14.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (initial != null && onDelete != null) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("删除") }
                }
                Button(
                    onClick = {
                        val trimmed = title.trim()
                        if (trimmed.isEmpty()) return@Button
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
                    },
                    modifier = Modifier.weight(1f),
                    enabled = title.isNotBlank()
                ) { Text("保存") }
            }
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
            title = { Text("删除这个任务?") },
            text = {
                Text("「${initial.title}」及其专注记录将被永久删除,删除后无法恢复。")
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
