package com.yanzhong.app.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.data.plan.isServerPlanItem
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CompletionCircle
import com.yanzhong.app.ui.theme.EmptyState
import com.yanzhong.app.ui.theme.ListRow
import com.yanzhong.app.ui.theme.PillButton
import com.yanzhong.app.ui.theme.ProgressLine
import com.yanzhong.app.ui.theme.SectionHeader
import com.yanzhong.app.ui.theme.SuccessGreen
import com.yanzhong.app.ui.theme.WarnRed
import com.yanzhong.app.util.TimeUtils

/** 今日待办列表:点圆环或右滑完成、左滑顺延、点「开始」直接专注(PRD 3.3 / 6.5)。
 *  LazyListScope 扩展:待办逐项成 item(key=id),增删带位移动画且互不牵连重组 */
fun LazyListScope.taskListSection(
    openTasks: List<TaskEntity>,
    doneTasks: List<TaskEntity>,
    subjects: List<SubjectEntity>,
    today: Long,
    planSlots: Map<Long, String> = emptyMap(),
    onComplete: (TaskEntity) -> Unit,
    onPostpone: (TaskEntity) -> Unit,
    onPostponeRequest: (TaskEntity) -> Unit,
    onStartFocus: (TaskEntity) -> Unit,
    onTaskClick: (TaskEntity) -> Unit
) {
    item(key = "task-section-header") {
        Column {
            SectionHeader(
                title = "今日待办",
                meta = "${doneTasks.size}/${openTasks.size + doneTasks.size} 已完成"
            )
            if (openTasks.isEmpty()) {
                EmptyState(
                    icon = AppIcons.SubjectDefault,
                    title = "今天还没有任务",
                    hint = "添加一个任务，或直接开始自由专注",
                    compact = true
                )
            }
        }
    }

    items(openTasks, key = { it.id }) { task ->
        val subject = subjects.firstOrNull { it.id == task.subjectId }
        TaskRow(
            task = task,
            subjectColor = subject?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary,
            subjectName = subject?.name ?: "未分类",
            overdue = task.dueAt != null && task.dueAt < TimeUtils.dayStartOf(today),
            planSlot = planSlots[task.id],
            onComplete = { onComplete(task) },
            onPostpone = { onPostpone(task) },
            onPostponeRequest = { onPostponeRequest(task) },
            onStartFocus = { onStartFocus(task) },
            onClick = { onTaskClick(task) },
            modifier = Modifier.animateItem()
        )
    }

    if (doneTasks.isNotEmpty()) {
        item(key = "task-done-section") {
            DoneSection(doneTasks = doneTasks, subjects = subjects, onTaskClick = onTaskClick)
        }
    }
}

/** 已完成折叠区:展开/收起带尺寸动画 */
@Composable
private fun DoneSection(
    doneTasks: List<TaskEntity>,
    subjects: List<SubjectEntity>,
    onTaskClick: (TaskEntity) -> Unit
) {
    var doneExpanded by remember { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        AppCard(contentPadding = PaddingValues(0.dp)) {
            ListRow(
                title = "已完成 ${doneTasks.size} 项",
                icon = if (doneExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                onClick = { doneExpanded = !doneExpanded }
            )
        }
        if (doneExpanded) {
            Spacer(Modifier.height(8.dp))
            AppCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                doneTasks.forEach { task ->
                    DoneTaskRow(task = task, subjects = subjects, onClick = { onTaskClick(task) })
                }
            }
        }
    }
}

/** 任务行卡:左圆环一键完成,右侧「开始」胶囊进入专注,整卡点开编辑 */
@Composable
private fun TaskRow(
    task: TaskEntity,
    subjectColor: Color,
    subjectName: String,
    overdue: Boolean,
    onComplete: () -> Unit,
    onPostpone: () -> Unit,
    onPostponeRequest: () -> Unit,
    onStartFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    planSlot: String? = null
) {
    val haptic = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onComplete()
                    !task.isServerPlanItem()
                }
                // 云端计划项不能本地顺延；左滑直接回弹
                SwipeToDismissBoxValue.EndToStart -> {
                    if (!task.isServerPlanItem()) onPostponeRequest()
                    false
                }
                else -> false
            }
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val (bg, icon, tint) = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(
                    SuccessGreen.copy(alpha = 0.15f),
                    AppIcons.Check,
                    SuccessGreen
                )
                else -> Triple(
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                    AppIcons.Schedule,
                    MaterialTheme.colorScheme.tertiary
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = if (direction == SwipeToDismissBoxValue.StartToEnd)
                    Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint)
            }
        }
    ) {
        AppCard(
            onClick = onClick,
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.animateContentSize()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompletionCircle(
                    checked = false,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onComplete()
                    }
                )
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(subjectColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            listOfNotNull(
                                subjectName,
                                planSlot ?: task.dueAt?.let { TimeUtils.formatHm(it) },
                                task.pomodoroEstimate
                                    .takeIf { it > 0 }
                                    ?.let { "${task.completedPomodoros}/$it 番茄" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overdue) WarnRed else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (task.repeatRule != 0 || task.postponeCount > 0) {
                        Text(
                            listOfNotNull(
                                task.repeatRule.takeIf { it != 0 }?.let { repeatLabel(task) },
                                task.postponeCount.takeIf { it > 0 }?.let { "顺延×$it" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                PillButton(text = "开始", onClick = onStartFocus)
            }
            if (task.pomodoroEstimate > 0) {
                Spacer(Modifier.height(10.dp))
                ProgressLine(
                    progress = task.completedPomodoros.toFloat() / task.pomodoroEstimate,
                    color = if (task.completedPomodoros >= task.pomodoroEstimate) SuccessGreen else subjectColor,
                    height = 4.dp
                )
            }
        }
    }
}

private fun repeatLabel(task: TaskEntity): String = when (task.repeatRule) {
    1 -> "每日"
    2 -> {
        val days = buildString {
            for (i in 0..6) if ((task.repeatDays shr i) and 1 == 1) {
                if (isNotEmpty()) append("·")
                append(listOf("一", "二", "三", "四", "五", "六", "日")[i])
            }
        }
        "每周$days"
    }
    else -> ""
}

@Composable
private fun DoneTaskRow(task: TaskEntity, subjects: List<SubjectEntity>, onClick: () -> Unit) {
    val subject = subjects.firstOrNull { it.id == task.subjectId }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            AppIcons.CheckCircle,
            contentDescription = "已完成",
            tint = SuccessGreen,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            task.title,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = TextDecoration.LineThrough,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            subject?.name ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
