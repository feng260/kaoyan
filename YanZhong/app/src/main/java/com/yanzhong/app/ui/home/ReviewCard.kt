package com.yanzhong.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.db.DailyReviewEntity
import com.yanzhong.app.data.db.MonthlyReviewEntity
import com.yanzhong.app.data.db.WeeklyReviewEntity
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.util.PersonalPlan

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
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            if (yesterdayGoal != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            AppIcons.Target,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "昨日定下的今日要事",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                yesterdayGoal,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 今日复盘三问
            Surface(
                onClick = onOpenDaily,
                shape = MaterialTheme.shapes.medium,
                color = if (dailyDone) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (dailyDone) AppIcons.CheckCircle else AppIcons.Sparkles,
                        contentDescription = null,
                        tint = if (dailyDone) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (dailyDone) "今日复盘 · 已完成" else "今日复盘 · 三问未写",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            when {
                                dailyDone -> todayReview.q1Done.ifBlank { "点击查看 / 修改" }
                                else -> "睡前 3 问 30 秒闭环:完成什么 · 哪里模糊 · 明天要事"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 周复盘:周日晚雷打不动;本周未写时其余天也保留次级入口
            if (isSunday || !weeklyDone) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = onOpenWeekly,
                    shape = MaterialTheme.shapes.medium,
                    color = if (isSunday && !weeklyDone) {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            AppIcons.Repeat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (weeklyDone) "本周复盘 · 已完成" else "本周复盘",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                when {
                                    weeklyDone && isSunday -> "点击修改 · 下周 3 要事已定"
                                    weeklyDone -> "点击查看 / 修改"
                                    isSunday -> "周日晚 30min 雷打不动:盘点 · 薄弱点 · 下周要事"
                                    else -> "周日晚写,先点开看本周自动盘点"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 月复盘:月底(最后一天)雷打不动;本月未写时其余天保留次级入口
            if (isMonthEnd || !monthlyDone) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = onOpenMonthly,
                    shape = MaterialTheme.shapes.medium,
                    color = if (isMonthEnd && !monthlyDone) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            AppIcons.Calendar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (monthlyDone) "本月复盘 · 已完成" else "本月复盘",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                when {
                                    monthlyDone && isMonthEnd -> "点击修改 · 下月 3 要事已定"
                                    monthlyDone -> "点击查看 / 修改"
                                    isMonthEnd -> "月底 30min 复盘:总结 · 盘点 · 下月要事"
                                    else -> "月底写,先点开看本月自动盘点"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 每日复盘三问 Sheet:提示语与作战计划一致(计划第 04/05 节) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyReviewSheet(
    initial: DailyReviewEntity?,
    onSave: (q1: String, q2: String, q3: String) -> Unit,
    onDismiss: () -> Unit
) {
    val questions = PersonalPlan.reviewQuestions
    var q1 by remember { mutableStateOf(initial?.q1Done ?: "") }
    var q2 by remember { mutableStateOf(initial?.q2Weak ?: "") }
    var q3 by remember { mutableStateOf(initial?.q3Tomorrow ?: "") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("今日复盘 · 三问", style = MaterialTheme.typography.headlineLarge)
            Text(
                "每个日模板 21:30 的固定块 · 对照日计划,诚实一点",
                style = MaterialTheme.typography.bodyMedium,
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
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
                Button(
                    onClick = { onSave(q1, q2, q3); onDismiss() },
                    modifier = Modifier.weight(2f)
                ) {
                    Text(if (initial == null) "保存复盘" else "更新复盘")
                }
            }
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
@OptIn(ExperimentalMaterial3Api::class)
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("本周复盘", style = MaterialTheme.typography.headlineLarge)
            Text(
                "周日晚 30min 雷打不动 · 排下周任务表",
                style = MaterialTheme.typography.bodyMedium,
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
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
                Button(
                    onClick = { onSave(weak, t1, t2, t3); onDismiss() },
                    modifier = Modifier.weight(2f)
                ) {
                    Text(if (initial == null) "保存周复盘" else "更新周复盘")
                }
            }
        }
    }
}

/** 月复盘 Sheet:本月自动盘点 + 总结 + 下月 3 要事(月底 30min 雷打不动) */
@OptIn(ExperimentalMaterial3Api::class)
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("本月复盘", style = MaterialTheme.typography.headlineLarge)
            Text(
                "月底 30min 雷打不动 · 定下月作战方向",
                style = MaterialTheme.typography.bodyMedium,
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
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
                Button(
                    onClick = { onSave(summary, t1, t2, t3); onDismiss() },
                    modifier = Modifier.weight(2f)
                ) {
                    Text(if (initial == null) "保存月复盘" else "更新月复盘")
                }
            }
        }
    }
}
