package com.yanzhong.app.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.db.CountdownNodeEntity
import com.yanzhong.app.data.db.NodeType
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.util.TimeUtils
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 倒计时节点管理:增删 / 编辑 / 置顶切换(PRD 3.1) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeManageSheet(
    nodes: List<CountdownNodeEntity>,
    onAdd: (CountdownNodeEntity) -> Unit,
    onUpdate: (CountdownNodeEntity) -> Unit,
    onDelete: (CountdownNodeEntity) -> Unit,
    onPin: (CountdownNodeEntity) -> Unit,
    onBatchAdd: suspend (String) -> Int,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf<CountdownNodeEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showBatch by remember { mutableStateOf(false) }
    /** 删除节点二次确认(防误触) */
    var deleteCandidate by remember { mutableStateOf<CountdownNodeEntity?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("倒计时节点", style = MaterialTheme.typography.headlineLarge)
            Text(
                "置顶节点展示在首页,左右滑可临时切换",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.padding(10.dp))
            nodes.forEach { node ->
                NodeRow(
                    node = node,
                    onPin = { onPin(node) },
                    onEdit = { editing = node; showEditor = true },
                    onDelete = { deleteCandidate = node }
                )
                Spacer(Modifier.padding(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { editing = null; showEditor = true },
                    modifier = Modifier.weight(1f)
                ) { Text("+ 添加节点") }
                Button(
                    onClick = { showBatch = true },
                    modifier = Modifier.weight(1f)
                ) { Text("批量添加") }
            }
        }
    }

    deleteCandidate?.let { node ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除节点「${node.name}」?") },
            text = { Text("该倒计时节点将被永久删除,删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(node)
                        deleteCandidate = null
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("取消") }
            }
        )
    }

    if (showEditor) {
        NodeEditorDialog(
            initial = editing,
            onSave = {
                if (editing == null) onAdd(it) else onUpdate(it)
                showEditor = false
            },
            onDismiss = { showEditor = false }
        )
    }

    if (showBatch) {
        BatchNodeDialog(
            onImport = { text ->
                showBatch = false
                scope.launch {
                    val added = onBatchAdd(text)
                    Toast.makeText(
                        context,
                        if (added > 0) "已添加 $added 个节点" else "未识别到可添加的节点",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { showBatch = false }
        )
    }
}

@Composable
private fun NodeRow(
    node: CountdownNodeEntity,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val daysLeft = TimeUtils.daysBetween(TimeUtils.now(), node.targetAt)
    val daysLabel = when {
        daysLeft < 0 -> "已过期 ${-daysLeft} 天"
        daysLeft == 0L -> "今天"
        else -> "剩 $daysLeft 天"
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${NodeType.label(node.type)} · ${TimeUtils.formatYmdHm(node.targetAt)} · $daysLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPin) {
                Icon(
                    AppIcons.Pin,
                    contentDescription = if (node.pinned) "取消置顶" else "置顶",
                    tint = if (node.pinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    AppIcons.Edit,
                    contentDescription = "编辑节点"
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    AppIcons.Delete,
                    contentDescription = "删除节点",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeEditorDialog(
    initial: CountdownNodeEntity?,
    onSave: (CountdownNodeEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableIntStateOf(initial?.type ?: NodeType.EXAM) }
    var targetAt by remember { mutableStateOf(initial?.targetAt ?: defaultTarget()) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    if (showDate) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = targetAt)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { dateMillis ->
                        val zone = ZoneId.systemDefault()
                        val oldTime = Instant.ofEpochMilli(targetAt).atZone(zone)
                        val newDate = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC)
                        targetAt = newDate.toLocalDate()
                            .atTime(oldTime.hour, oldTime.minute)
                            .atZone(zone).toInstant().toEpochMilli()
                    }
                    showDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("取消") } }
        ) { DatePicker(state = dateState) }
    }

    if (showTime) {
        val zone = ZoneId.systemDefault()
        val current = Instant.ofEpochMilli(targetAt).atZone(zone)
        val timeState = rememberTimePickerState(
            initialHour = current.hour,
            initialMinute = current.minute,
            is24Hour = true
        )
        androidx.compose.ui.window.Dialog(onDismissRequest = { showTime = false }) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("节点时间", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.padding(10.dp))
                    TimePicker(state = timeState)
                    Spacer(Modifier.padding(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { showTime = false }) { Text("取消") }
                        TextButton(onClick = {
                            val date = Instant.ofEpochMilli(targetAt).atZone(zone).toLocalDate()
                            targetAt = date
                                .atTime(timeState.hour, timeState.minute)
                                .atZone(zone).toInstant().toEpochMilli()
                            showTime = false
                        }) { Text("确定") }
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initial == null) "添加节点" else "编辑节点")
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("节点名称") },
                    singleLine = true
                )
                Spacer(Modifier.padding(8.dp))
                Text("类型", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = type == NodeType.EXAM,
                        onClick = { type = NodeType.EXAM },
                        label = { Text("初试") }
                    )
                    FilterChip(
                        selected = type == NodeType.MOCK,
                        onClick = { type = NodeType.MOCK },
                        label = { Text("模考") }
                    )
                    FilterChip(
                        selected = type == NodeType.ENROLL,
                        onClick = { type = NodeType.ENROLL },
                        label = { Text("报名") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = type == NodeType.SUBJECT_EXAM,
                        onClick = { type = NodeType.SUBJECT_EXAM },
                        label = { Text("单科") }
                    )
                    FilterChip(
                        selected = type == NodeType.RETEST,
                        onClick = { type = NodeType.RETEST },
                        label = { Text("复试") }
                    )
                    FilterChip(
                        selected = type == NodeType.CUSTOM,
                        onClick = { type = NodeType.CUSTOM },
                        label = { Text("自定义") }
                    )
                }
                Spacer(Modifier.padding(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showDate = true }) {
                        Text("日期:${TimeUtils.formatMonthDay(targetAt)}")
                    }
                    TextButton(onClick = { showTime = true }) {
                        Text("时间:${TimeUtils.formatHm(targetAt)}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) return@TextButton
                    onSave(
                        (initial ?: CountdownNodeEntity(name = name, targetAt = targetAt)).copy(
                            name = name.trim(),
                            type = type,
                            targetAt = targetAt
                        )
                    )
                },
                enabled = name.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun defaultTarget(): Long =
    TimeUtils.dayStartOf() + 30L * 24 * 3600 * 1000 + 8 * 3600_000L + 30 * 60_000L

/** 批量添加节点弹窗:多行文本一行一条,格式「名称 [日期]」,提交后回调原文 */
@Composable
private fun BatchNodeDialog(
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量添加节点") },
        text = {
            Column {
                Text(
                    "一行一个节点,格式「名称 日期」;日期可写 2026-12-18 或 12-18(默认今年),缺省为 30 天后 08:00,类型默认自定义。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.padding(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如:\n数学三模 2026-12-06\n报名确认 10-25\n复试备考") },
                    minLines = 5,
                    maxLines = 9
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = text.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
