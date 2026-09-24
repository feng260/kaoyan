package com.yanzhong.app.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.db.SubjectEntity
import com.yanzhong.app.data.db.TaskEntity
import com.yanzhong.app.ui.theme.AmberGold
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.Subject408Color
import com.yanzhong.app.ui.theme.SubjectArtwork
import com.yanzhong.app.ui.theme.SuccessGreen
import com.yanzhong.app.ui.theme.WarnRed
import com.yanzhong.app.util.TimeUtils

/** 今日待办列表:右滑完成、左滑顺延、▶ 开钟(PRD 3.3 / 6.5,视觉参考番茄ToDo 待办区块)。
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
        Column(Modifier.padding(top = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("今日待办", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "${doneTasks.size}/${openTasks.size + doneTasks.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (openTasks.isEmpty()) EmptyTaskHint()
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
        Surface(
            onClick = { doneExpanded = !doneExpanded },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (doneExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "已完成 ${doneTasks.size} 项",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (doneExpanded) {
            Spacer(Modifier.height(8.dp))
            doneTasks.forEach { task ->
                DoneTaskRow(task = task, subjects = subjects, onClick = { onTaskClick(task) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 任务区块卡(参考番茄ToDo 待办集):清新插画 + 标题信息 + 番茄进度条 */
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
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onComplete(); true }
                // 左滑不直接顺延,弹确认框;返回 false 让卡片回弹
                SwipeToDismissBoxValue.EndToStart -> { onPostponeRequest(); false }
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
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .animateContentSize()
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 科目清新小插画(参考番茄ToDo 待办集插图)
                    SubjectArtwork(
                        subjectName = subjectName,
                        color = subjectColor,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 3.dp)
                        ) {
                            // 时间显示:优先节奏计划时段(随排程动态调整),无排程回退 dueAt
                            when {
                                planSlot != null -> {
                                    Text(
                                        planSlot,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = SuccessGreen
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                task.dueAt != null -> {
                                    Text(
                                        TimeUtils.formatHm(task.dueAt!!),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (overdue) WarnRed else subjectColor
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                            }
                            Text(
                                subjectName,
                                style = MaterialTheme.typography.labelMedium,
                                color = subjectColor
                            )
                            if (task.repeatRule != 0) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    repeatLabel(task),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (task.postponeCount > 0) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "顺延×${task.postponeCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                        if (overdue) {
                            Text(
                                "已过期 · ${TimeUtils.dueLabel(task.dueAt!!)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = WarnRed,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    // 开钟圆按钮
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(subjectColor.copy(alpha = 0.14f))
                            .clickable(onClick = onStartFocus),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            AppIcons.Play,
                            contentDescription = "为「${task.title}」开始番茄钟",
                            tint = subjectColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                // 番茄进度条:区块卡底部完成度可视化
                if (task.pomodoroEstimate > 0) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val fraction =
                            (task.completedPomodoros.toFloat() / task.pomodoroEstimate)
                                .coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(
                                        if (task.completedPomodoros >= task.pomodoroEstimate)
                                            SuccessGreen else subjectColor
                                    )
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "🍅 ${task.completedPomodoros}/${task.pomodoroEstimate}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (task.completedPomodoros >= task.pomodoroEstimate)
                                SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
    val subjectColor = subject?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(SuccessGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    AppIcons.Check,
                    contentDescription = "已完成",
                    tint = SuccessGreen,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                subject?.name ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/** 空状态:清新日出山丘小插画(Canvas 代码绘制,不加图片资源) */
@Composable
private fun EmptyTaskHint() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .padding(horizontal = 36.dp)
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                // 日出山丘场景
                val skyTop = MaterialTheme.colorScheme.primaryContainer
                val skyBottom = MaterialTheme.colorScheme.surface
                val hillFar = Subject408Color.copy(alpha = 0.30f)
                val hillNear = Subject408Color.copy(alpha = 0.45f)
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(skyTop, skyBottom)),
                        cornerRadius = CornerRadius(24f, 24f),
                        size = Size(w, h)
                    )
                    // 太阳 + 光晕
                    val sun = Offset(w * 0.76f, h * 0.26f)
                    drawCircle(AmberGold.copy(alpha = 0.25f), radius = 30f, center = sun)
                    drawCircle(AmberGold, radius = 17f, center = sun)
                    // 远山
                    val far = Path().apply {
                        moveTo(0f, h * 0.78f)
                        cubicTo(w * 0.22f, h * 0.58f, w * 0.5f, h * 0.82f, w * 0.78f, h * 0.66f)
                        lineTo(w, h * 0.74f)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    drawPath(far, hillFar)
                    // 近山
                    val near = Path().apply {
                        moveTo(0f, h * 0.92f)
                        cubicTo(w * 0.3f, h * 0.78f, w * 0.7f, h * 0.9f, w, h * 0.84f)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    drawPath(near, hillNear)
                }
                // 场景中央:书本
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        AppIcons.SubjectDefault,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("今天没有安排?", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "从添加一个任务开始,或者点下方新番茄直接专注",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
