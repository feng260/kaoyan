package com.yanzhong.app.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanzhong.app.data.remote.FOUNDATION_LEVELS
import com.yanzhong.app.data.remote.STUDY_WINDOWS
import com.yanzhong.app.data.remote.TARGET_TYPES
import com.yanzhong.app.ui.legal.PolicyLinksRow
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.SuccessGreen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 首次问卷。
 *
 * 分三步不是为了"看起来很专业",是因为这三步问的东西性质不同:
 * 第一步是目标和日子——不用想,谁都答得上来;
 * 第二步要用户回忆自己的作息和短板——这需要停下来想一想;
 * 第三步是正式科目与真实空闲,还得顺手把课表传上来——信息最重,放最后,
 * 前面两屏轻,用户愿意走到这里,也才愿意认真对准时间。
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
                val (title, subtitle) = when (state.step) {
                    0 -> "先定个目标" to "知道你要考什么、哪天考,我才能把日子排开"
                    1 -> "说说你的节奏" to "这几个答案决定每天给你排多少、先补哪一科"
                    else -> "把科目和时间说准" to "科目写全、空闲和固定占用标准,排出来的计划才不用返工"
                }
                Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = colors.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        when (state.step) {
                            0 -> StepTarget(state, vm)
                            1 -> StepRhythm(state, vm)
                            else -> StepFacts(state, vm)
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

/** 顶部步进指示:用等宽横条,比"1/3"更像进度而不像页码 */
@Composable
internal fun StepDots(step: Int, total: Int = ONBOARDING_STEPS) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
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
internal fun StepTarget(state: OnboardingUiState, vm: OnboardingViewModel) {
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
    ExamDateField(
        value = state.examDate,
        onValueChange = vm::setExamDate,
        onImeNext = vm::next
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

/**
 * 考试日期输入(问卷第一步与「备考档案」共用)。
 *
 * 原本这里只是一个带 Calendar 图标的普通文本框 —— 图标画着日历却点不动,
 * 用户照着图标去点,自然"日历打不开"。现在左侧图标改成真的按钮,点开 M3 的
 * DatePickerDialog;文本框本身仍可编辑,想直接敲或从系统日历粘过来都行
 * (setExamDate 会把 2026/12/19 这类写法归一化成 2026-12-19)。
 *
 * DatePicker 的 selectedDateMillis 是 **UTC 午夜**,所以读回日期必须按 UTC 解,
 * 否则东八区会整体差一天(与 NodeManageSheet 里同一处理)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExamDateField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onImeNext: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        val initialMillis = remember(value) {
            runCatching { LocalDate.parse(value.trim()) }.getOrNull()
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        }
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        onValueChange(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
                                .toLocalDate().toString()
                        )
                    }
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            }
        ) { DatePicker(state = dateState) }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("考试日期") },
        placeholder = { Text("2026-12-19") },
        leadingIcon = {
            IconButton(onClick = { showPicker = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    AppIcons.Calendar,
                    contentDescription = "打开日历选择日期",
                    tint = colors.primary
                )
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onImeNext() }),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outline.copy(alpha = 0.7f)
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StepRhythm(state: OnboardingUiState, vm: OnboardingViewModel) {
    val colors = MaterialTheme.colorScheme
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
    CustomAddRow(label = "写不下就自己加一门", placeholder = "比如 数据结构") { vm.addWeakSubject(it) }
}

/**
 * 第三步:正式科目 + 真实空闲 + 课表。
 *
 * 这一屏把面谈最爱反复追问的三件事一次问全,后面 AI 才有余力聊"你这科到哪了、还剩多少"。
 * 课表上传是可选路径:传得上去就是白捡,传不上也挡不住人往前走。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StepFacts(state: OnboardingUiState, vm: OnboardingViewModel) {
    val colors = MaterialTheme.colorScheme

    FieldLabel("你要考的科目", "只填科目名就行。每科现在到哪了、还剩多少,面谈里再细聊")
    Spacer(Modifier.height(10.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.subjectOptions.forEach { name ->
            FilterChip(
                selected = name in state.examSubjects,
                onClick = { vm.toggleExamSubject(name) },
                label = { Text(name) }
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    CustomAddRow(label = "没有你的科目就自己加", placeholder = "比如 数据结构") { vm.addExamSubject(it) }

    Spacer(Modifier.height(20.dp))
    FieldLabel("每周哪几天、哪些时段真坐得下来", "点格子就行。这天没空就不点,排课不会往空格子里塞任务")
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..7).forEach { day ->
            // 每周固定一行、五格等宽(A7):FlowRow 放不下五个时段会换行,
            // 星期标签和格子就错开了,选没选对自己都看不出来。等宽压进一行后标签永远对齐。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "周${WEEKDAY_LABELS[day - 1]}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(40.dp)
                )
                Spacer(Modifier.width(6.dp))
                val selected = state.availability[day].orEmpty()
                STUDY_WINDOWS.forEach { window ->
                    WeekWindowChip(
                        label = window,
                        selected = window in selected,
                        onClick = { vm.toggleAvailability(day, window) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "上一屏选的是「平时固定学得进去」的时段,这里标的是这一周具体哪天有空——不完全一样是正常的。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant
    )

    Spacer(Modifier.height(20.dp))
    FieldLabel("有课表就传一张,我替你认", "单双周课表都行。认出来的占用会列在下面,和实际不符的直接删掉")
    Spacer(Modifier.height(10.dp))
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.parseTimetable(uri)
    }
    OutlinedButton(
        onClick = { picker.launch("image/*") },
        enabled = !state.timetableBusy,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.timetableBusy) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("正在认这张课表…")
        } else {
            Text("从相册选一张课表截图")
        }
    }
    if (state.timetableNotice.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        OnboardingMessage(state.timetableNotice, state.timetableNoticeIsError)
    }

    if (state.commitments.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        FieldLabel("已经记下的固定占用", "每周固定被占掉的时间,排课时不会往里塞任务")
        Spacer(Modifier.height(8.dp))
        state.commitments.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "周${WEEKDAY_LABELS[item.weekday - 1]} ${item.start}-${item.end}  ${item.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.removeCommitment(index) }) { Text("删掉") }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    FieldLabel("没有课表?手动补一条", "上课、上班、通勤这类每周固定占掉的时间,补几条就够")
    Spacer(Modifier.height(8.dp))
    ManualCommitmentRow { weekday, start, end, label -> vm.addCommitment(weekday, start, end, label) }
}

/** 周空闲格子(等宽):选中显主题容器色,未选中细描边;固定单行高度,七行始终对齐 */
@Composable
private fun RowScope.WeekWindowChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Text(
            label,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 7.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualCommitmentRow(onAdd: (Int, String, String, String) -> Boolean) {
    var weekday by remember { mutableStateOf(1) }
    var label by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }

    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..7).forEach { day ->
                FilterChip(
                    selected = weekday == day,
                    onClick = { weekday = day },
                    label = { Text("周${WEEKDAY_LABELS[day - 1]}") }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(16) },
            label = { Text("是什么事") },
            placeholder = { Text("比如 上课") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = start,
                onValueChange = { start = it.take(5) },
                label = { Text("开始") },
                placeholder = { Text("08:00") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = end,
                onValueChange = { end = it.take(5) },
                label = { Text("结束") },
                placeholder = { Text("11:30") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                // 只有真记下了才清空:校验没过(时间格式/先后顺序)留着让用户改,不白打一遍
                if (onAdd(weekday, start, end, label)) {
                    label = ""
                    start = ""
                    end = ""
                }
            },
            shape = RoundedCornerShape(12.dp)
        ) { Text("记下这条") }
    }
}

/** 「清单里没有就自己加」那一行:输入框 + 加上按钮,两处都用得上 */
@Composable
private fun CustomAddRow(label: String, placeholder: String, onAdd: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var custom by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it.take(16) },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                onAdd(custom)
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
                onAdd(custom)
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
    val lastStep = state.step >= ONBOARDING_STEPS - 1
    Button(
        // 末步只存档案:一份几百天的计划要先和 AI 聊清楚才排得出来,问卷这一屏不越权替 AI 落笔
        onClick = { if (lastStep) vm.saveProfileOnly() else vm.next() },
        enabled = !state.busy,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        if (state.busy) {
            CircularProgressIndicator(color = colors.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(21.dp))
        } else {
            Text(
                if (lastStep) "保存档案,去和 AI 聊计划" else "下一步",
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
internal fun OnboardingMessage(message: String, isError: Boolean) {
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

