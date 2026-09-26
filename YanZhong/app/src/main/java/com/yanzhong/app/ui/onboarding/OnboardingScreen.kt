package com.yanzhong.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanzhong.app.data.remote.FOUNDATION_LEVELS
import com.yanzhong.app.data.remote.STUDY_WINDOWS
import com.yanzhong.app.data.remote.TARGET_TYPES
import com.yanzhong.app.ui.legal.PolicyLinksRow
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.SuccessGreen

/**
 * 首次问卷。
 *
 * 分两步不是为了"看起来很专业",是因为这一步问的东西性质不同:
 * 第一步是目标和日子——不用想,谁都答得上来;
 * 第二步要用户回忆自己的作息和短板——这需要停下来想一想。
 * 拆开之后,第一屏轻,用户愿意往下点;第二屏才谈得上认真回答。
 *
 * 全篇避免"请填写""提交表单"这类词。我们是在给一个真实的人排他的备考节奏,
 * 不是在收集数据。
 */
@Composable
fun OnboardingScreen(vm: OnboardingViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.primaryContainer.copy(alpha = 0.55f), colors.background, colors.background)
                )
            )
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
                StepDots(step = state.step)
                Spacer(Modifier.height(18.dp))
                Text(
                    if (state.step == 0) "先定个目标" else "说说你的节奏",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.step == 0) "知道你要考什么、哪天考,我才能把日子排开"
                    else "这几个答案决定每天给你排多少、先补哪一科",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = colors.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        if (state.step == 0) {
                            StepTarget(state, vm)
                        } else {
                            StepRhythm(state, vm)
                        }
                    }
                }

                if (state.message.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    OnboardingMessage(state.message, state.messageIsError)
                }

                Spacer(Modifier.height(20.dp))
                PrimaryAction(state, vm)
                Spacer(Modifier.height(10.dp))
                PolicyLinksRow(prefix = "计划由服务器按你填的内容生成,依据")
            }
        }
    }
}

/** 顶部两步指示:用两段横条,比"1/2"更像进度而不像页码 */
@Composable
private fun StepDots(step: Int) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (index <= step) colors.primary else colors.surfaceVariant
                    )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepTarget(state: OnboardingUiState, vm: OnboardingViewModel) {
    val colors = MaterialTheme.colorScheme
    FieldLabel("你要准备的是", "不同考试的节奏差得很远,后面阶段的划分会跟着变")
    Spacer(Modifier.height(10.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TARGET_TYPES.forEach { type ->
            FilterChip(
                selected = state.targetType == type,
                onClick = { vm.setTargetType(type) },
                label = { Text(type) }
            )
        }
    }

    Spacer(Modifier.height(20.dp))
    FieldLabel("考试是哪天", "留出这一天,倒计时和阶段划分都以它为终点")
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = state.examDate,
        onValueChange = vm::setExamDate,
        label = { Text("考试日期") },
        placeholder = { Text("2026-12-19") },
        leadingIcon = { Icon(AppIcons.Calendar, contentDescription = null, tint = colors.onSurfaceVariant) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { vm.next() }),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outline.copy(alpha = 0.7f)
        ),
        modifier = Modifier.fillMaxWidth()
    )
    if (state.targetType == "考研") {
        Spacer(Modifier.height(6.dp))
        Text(
            "按考研初试的惯例先填了 12 月下旬。如果日期不一样,直接改就行。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepRhythm(state: OnboardingUiState, vm: OnboardingViewModel) {
    val colors = MaterialTheme.colorScheme
    FieldLabel("平时一天能拿出多少时间", "按真实情况填。填多了计划会一直在欠账,反而容易放弃")
    Spacer(Modifier.height(10.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DAILY_MINUTE_CHOICES.forEach { minutes ->
            FilterChip(
                selected = state.dailyMinutes == minutes,
                onClick = { vm.setDailyMinutes(minutes) },
                label = { Text(minutesLabel(minutes)) }
            )
        }
    }

    Spacer(Modifier.height(20.dp))
    FieldLabel("哪些时段你基本固定学得进去", "可以多选。排课会优先落进这些时段")
    Spacer(Modifier.height(10.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        STUDY_WINDOWS.forEach { window ->
            FilterChip(
                selected = window in state.studyWindows,
                onClick = { vm.toggleStudyWindow(window) },
                label = { Text(window) }
            )
        }
    }

    Spacer(Modifier.height(20.dp))
    FieldLabel("现在的基础大致是", "只影响前期的强度,后面会随着进度自己调整")
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FOUNDATION_LEVELS.forEach { level ->
            FilterChip(
                selected = state.foundation == level,
                onClick = { vm.setFoundation(level) },
                label = { Text(level) }
            )
        }
    }

    Spacer(Modifier.height(20.dp))
    FieldLabel("哪几门最让你没底", "最多 6 门。选中的科目会分到更多时间")
    Spacer(Modifier.height(10.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.subjectOptions.forEach { name ->
            FilterChip(
                selected = name in state.weakSubjects,
                onClick = { vm.toggleWeakSubject(name) },
                label = { Text(name) }
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    var custom by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it.take(16) },
            label = { Text("写不下就自己加一门") },
            placeholder = { Text("比如 数据结构") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                vm.addWeakSubject(custom)
                custom = ""
            }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.outline.copy(alpha = 0.7f)
            ),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = {
                vm.addWeakSubject(custom)
                custom = ""
            },
            shape = RoundedCornerShape(12.dp)
        ) { Text("加上") }
    }
}

@Composable
private fun FieldLabel(title: String, hint: String? = null) {
    val colors = MaterialTheme.colorScheme
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (hint != null) {
        Spacer(Modifier.height(2.dp))
        Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun PrimaryAction(state: OnboardingUiState, vm: OnboardingViewModel) {
    val colors = MaterialTheme.colorScheme
    val lastStep = state.step >= 1
    Button(
        onClick = { if (lastStep) vm.submit() else vm.next() },
        enabled = !state.busy,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        if (state.busy) {
            CircularProgressIndicator(color = colors.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(21.dp))
        } else {
            Text(
                if (lastStep) "生成我的计划" else "下一步",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    if (state.step > 0) {
        TextButton(
            onClick = { vm.back() },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("回去改一下") }
    }
}

@Composable
private fun OnboardingMessage(message: String, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isError) colors.errorContainer else SuccessGreen.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                if (isError) AppIcons.Info else AppIcons.CheckCircle,
                contentDescription = null,
                tint = if (isError) colors.error else SuccessGreen,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) colors.error else SuccessGreen
            )
        }
    }
}

/**
 * 档案存好了、计划没排出来时的那一屏。
 * 它只说两件事:你填的东西没丢;现在可以怎么走。不写"系统繁忙请稍后再试"这种谁也不信的话。
 */
@Composable
fun OnboardingFallbackScreen(vm: OnboardingViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.primaryContainer.copy(alpha = 0.55f), colors.background, colors.background)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                AppIcons.Info,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text("档案存好了,计划还差一步", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                state.message.ifEmpty { "服务器这次没把计划排出来。你本机的计划没受影响,可以照常学。" },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = vm::retry,
                enabled = !state.busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                if (state.busy) {
                    CircularProgressIndicator(color = colors.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text("再试一次", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = vm::dismissFallback, enabled = !state.busy) {
                Text("先不管,照样开始学")
            }
        }
    }
}

/** 时长档位的人话写法:180 → "3 小时",90 → "1 小时 30 分" */
internal fun minutesLabel(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m 分钟"
        m == 0 -> "$h 小时"
        else -> "$h 小时 $m 分"
    }
}
