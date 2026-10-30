package com.yanzhong.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.db.DailyReviewEntity
import com.yanzhong.app.data.db.MonthlyReviewEntity
import com.yanzhong.app.data.db.WeeklyReviewEntity
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.AppSheet
import com.yanzhong.app.ui.theme.ListRow
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.RowDivider
import com.yanzhong.app.ui.theme.RowGroup
import com.yanzhong.app.ui.theme.SecondaryButton
import com.yanzhong.app.ui.theme.insetSurfaceColor

/** 每日复盘三问(文案与 DailyReviewEntity 字段语义一一对应,改动需同步) */
private val REVIEW_QUESTIONS = listOf(
    "今天完成了什么?" to "对照日计划打勾,诚实一点",
    "哪个知识点最模糊?" to "写进错题本,变成明天第一件事",
    "明天最重要的 1 件事?" to "睡前定好,早起不犹豫"
)

/**
 * 复盘卡(作战计划的雷打不动动作落地):
 * - 昨日「明天最重要的 1 件事」今晨置顶提示(睡前定好,早起不犹豫);
 * - 今日复盘三问:每个日模板 21:30 的固定块;
 * - 周复盘:周日晚 30min(完成率自动盘点 / 薄弱点 / 下周 3 要事)。
 */
@Composable
fun ReviewCard(
    yesterdayReview: DailyReviewEntity?,
    todayReview: DailyReviewEntity?,
    weeklyReview: WeeklyReviewEntity?,
    isSunday: Boolean,
    weekDoneTasks: Int,
    weekMinutes: Int,
    weekGoalMin: Int,
    monthlyReview: MonthlyReviewEntity?,
    isMonthEnd: Boolean,
    onOpenDaily: () -> Unit,
    onOpenWeekly: () -> Unit,
    onOpenMonthly: () -> Unit
) {
    val yesterdayGoal = yesterdayReview?.q3Tomorrow?.takeIf { it.isNotBlank() }
    val dailyDone = todayReview != null
    val weeklyDone = weeklyReview != null
    val monthlyDone = monthlyReview != null

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (yesterdayGoal != null) {
            AppCard {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(insetSurfaceColor())
                        .padding(12.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                AppIcons.Target,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "昨日定下的今日要事",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            yesterdayGoal,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        RowGroup {
            ListRow(
                title = "今日复盘",
                icon = if (dailyDone) AppIcons.CheckCircle else AppIcons.Sparkles,
                subtitle = if (dailyDone) "点击查看 / 修改" else "睡前 3 问，30 秒闭环",
                onClick = onOpenDaily,
                trailing = {
                    Text(
                        if (dailyDone) "已完成" else "未写",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (dailyDone) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
            )

            // 周复盘:周日晚雷打不动;本周未写时其余天也保留次级入口
            if (isSunday || !weeklyDone) {
                RowDivider()
                ListRow(
                    title = "本周复盘",
                    icon = AppIcons.Repeat,
                    onClick = onOpenWeekly,
                    trailing = {
                        Text(
                            when {
                                weeklyDone -> "已完成"
                                isSunday -> "周日提醒"
                                else -> "未写"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (weeklyDone) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }

            // 月复盘:月底(最后一天)雷打不动;本月未写时其余天保留次级入口
            if (isMonthEnd || !monthlyDone) {
                RowDivider()
                ListRow(
                    title = "本月复盘",
                    icon = AppIcons.Calendar,
                    onClick = onOpenMonthly,
                    trailing = {
                        Text(
                            when {
                                monthlyDone -> "已完成"
                                isMonthEnd -> "月底提醒"
                                else -> "未写"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (monthlyDone) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }
        }
    }
}

/** 每日复盘三问 Sheet:提示语与作战计划一致(计划第 04/05 节) */
@Composable
fun DailyReviewSheet(
    initial: DailyReviewEntity?,
    onSave: (q1: String, q2: String, q3: String) -> Unit,
    onDismiss: () -> Unit
) {
    val questions = REVIEW_QUESTIONS
    var q1 by remember { mutableStateOf(initial?.q1Done ?: "") }
    var q2 by remember { mutableStateOf(initial?.q2Weak ?: "") }
    var q3 by remember { mutableStateOf(initial?.q3Tomorrow ?: "") }

    AppSheet(title = "今日复盘 · 三问", onDismiss = onDismiss) {
        Text(
            "每个日模板 21:30 的固定块 · 对照日计划,诚实一点",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        ReviewQuestionField(
            index = 1,
            question = questions.getOrNull(0)?.first.orEmpty(),
            hint = questions.getOrNull(0)?.second.orEmpty(),
            value = q1,
            onValueChange = { q1 = it }
        )
        Spacer(Modifier.height(12.dp))
        ReviewQuestionField(
            index = 2,
            question = questions.getOrNull(1)?.first.orEmpty(),
            hint = questions.getOrNull(1)?.second.orEmpty(),
            value = q2,
            onValueChange = { q2 = it }
        )
        Spacer(Modifier.height(12.dp))
        ReviewQuestionField(
            index = 3,
            question = questions.getOrNull(2)?.first.orEmpty(),
            hint = questions.getOrNull(2)?.second.orEmpty(),
            value = q3,
            onValueChange = { q3 = it },
            highlight = true
        )

        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = if (initial == null) "保存复盘" else "更新复盘",
                onClick = { onSave(q1, q2, q3); onDismiss() },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ReviewQuestionField(
    index: Int,
    question: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    highlight: Boolean = false
) {
    Column {
        Text(
            "Q$index $question",
            style = MaterialTheme.typography.titleMedium,
            color = if (highlight) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
        if (hint.isNotBlank()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            placeholder = { Text("写一句就够,别追求完美") }
        )
    }
}

/** 周复盘 Sheet:本周自动盘点 + 薄弱点 + 下周 3 要事(周日晚 30min 雷打不动) */
@Composable
fun WeeklyReviewSheet(
    initial: WeeklyReviewEntity?,
    weekDoneTasks: Int,
    weekMinutes: Int,
    weekGoalMin: Int,
    onSave: (weakPoints: String, top1: String, top2: String, top3: String) -> Unit,
    onDismiss: () -> Unit
) {
    var weak by remember { mutableStateOf(initial?.weakPoints ?: "") }
    var t1 by remember { mutableStateOf(initial?.nextWeekTop1 ?: "") }
    var t2 by remember { mutableStateOf(initial?.nextWeekTop2 ?: "") }
    var t3 by remember { mutableStateOf(initial?.nextWeekTop3 ?: "") }

    AppSheet(title = "本周复盘", onDismiss = onDismiss) {
        Text(
            "周日晚 30min 雷打不动 · 排下周任务表",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        // 本周自动盘点(完成率 / 净学习,来自本地数据,无需手填)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "本周自动盘点",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("完成任务", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "$weekDoneTasks 个",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("净学习", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "%.1fh / 目标 ${weekGoalMin / 60}h".format(weekMinutes / 60f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("本周薄弱点", style = MaterialTheme.typography.titleMedium)
        Text(
            "写进下周第一件事,别让它滑走",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = weak,
            onValueChange = { weak = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        Spacer(Modifier.height(16.dp))
        Text("下周 3 件要事", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = t1,
            onValueChange = { t1 = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("① 要事 1") }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = t2,
            onValueChange = { t2 = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("② 要事 2") }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = t3,
            onValueChange = { t3 = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("③ 要事 3") }
        )

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = if (initial == null) "保存周复盘" else "更新周复盘",
                onClick = { onSave(weak, t1, t2, t3); onDismiss() },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 月复盘 Sheet:本月自动盘点 + 总结 + 下月 3 要事(月底 30min 雷打不动) */
@Composable
fun MonthlyReviewSheet(
    initial: MonthlyReviewEntity?,
    monthDoneTasks: Int,
    monthMinutes: Int,
    onSave: (summary: String, top1: String, top2: String, top3: String) -> Unit,
    onDismiss: () -> Unit
) {
    var summary by remember { mutableStateOf(initial?.summary ?: "") }
    var t1 by remember { mutableStateOf(initial?.nextMonthTop1 ?: "") }
    var t2 by remember { mutableStateOf(initial?.nextMonthTop2 ?: "") }
    var t3 by remember { mutableStateOf(initial?.nextMonthTop3 ?: "") }

    AppSheet(title = "本月复盘", onDismiss = onDismiss) {
        Text(
            "月底 30min 雷打不动 · 定下月作战方向",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        // 本月自动盘点(完成任务 / 净学习,来自本地数据,无需手填)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "本月自动盘点",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("完成任务", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "$monthDoneTasks 个",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("净学习", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "%.1fh".format(monthMinutes / 60f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("本月总结", style = MaterialTheme.typography.titleMedium)
        Text(
            "一句话回顾:进度如何 · 哪里卡住 · 方向要不要调",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = summary,
            onValueChange = { summary = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        Spacer(Modifier.height(16.dp))
        Text("下月 3 件要事", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = t1,
            onValueChange = { t1 = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("① 要事 1") }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = t2,
            onValueChange = { t2 = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("② 要事 2") }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = t3,
            onValueChange = { t3 = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("③ 要事 3") }
        )

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = if (initial == null) "保存月复盘" else "更新月复盘",
                onClick = { onSave(summary, t1, t2, t3); onDismiss() },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
