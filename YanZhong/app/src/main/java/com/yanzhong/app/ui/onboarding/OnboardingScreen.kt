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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanzhong.app.data.remote.FOUNDATION_LEVELS
import com.yanzhong.app.data.remote.STUDY_WINDOWS
import com.yanzhong.app.data.remote.TARGET_TYPES
import com.yanzhong.app.ui.legal.PolicyLinksRow
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CompletionCircle
import com.yanzhong.app.ui.theme.ListRow
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.ProgressLine
import com.yanzhong.app.ui.theme.SecondaryButton
import com.yanzhong.app.ui.theme.SegmentedChips
import com.yanzhong.app.ui.theme.SubPageHeader
import com.yanzhong.app.ui.theme.SuccessGreen
import com.yanzhong.app.ui.theme.TextAction
import com.yanzhong.app.ui.theme.successContentColor
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
    val scrollState = rememberScrollState()
    // 换一步就回到题头:上一屏停在最底下时,"下一步"后不该让人从半截开始读
    LaunchedEffect(state.step) { scrollState.scrollTo(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // 步进指示:第 x / y 步 + 细进度线,进度不单靠颜色表达;无路可退的第一步不显示返回
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .align(Alignment.CenterHorizontally)
        ) {
            SubPageHeader(
                title = stepTitle(state.step),
                onBack = if (state.step > 0) vm::back else null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "第 ${state.step + 1} / $ONBOARDING_STEPS 步",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                ProgressLine(progress = (state.step + 1f) / ONBOARDING_STEPS)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState)
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                stepSubtitle(state.step),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            AppCard {
                when (state.step) {
                    0 -> StepTarget(state, vm)
                    1 -> StepRhythm(state, vm)
                    else -> StepFacts(state, vm)
                }
            }
            Spacer(Modifier.height(16.dp))
            PolicyLinksRow(prefix = "计划由服务器按你填的内容生成,依据")
            Spacer(Modifier.height(16.dp))
        }

        BottomActionBar(state, vm)
    }
}

private fun stepTitle(step: Int): String = when (step) {
    0 -> "先定个目标"
    1 -> "说说你的节奏"
    else -> "把科目和时间说准"
}

private fun stepSubtitle(step: Int): String = when (step) {
    0 -> "知道你要考什么、哪天考,我才能把日子排开"
    1 -> "这几个答案决定每天给你排多少、先补哪一科"
    else -> "科目写全、空闲和固定占用标准,排出来的计划才不用返工"
}

/** 底部主按钮固定在安全区:键盘弹起时随 imePadding 抬起,不遮住正在填的题 */
@Composable
private fun BottomActionBar(state: OnboardingUiState, vm: OnboardingViewModel) {
    val lastStep = state.step >= ONBOARDING_STEPS - 1
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // 校验/保存的反馈贴着按钮:内容再长也不滚走,按下「下一步」当场看得见
                if (state.message.isNotEmpty()) {
                    OnboardingMessage(state.message, state.messageIsError)
                    Spacer(Modifier.height(8.dp))
                }
                PrimaryButton(
                    // 末步只存档案:一份几百天的计划要先和 AI 聊清楚才排得出来,问卷这一屏不越权替 AI 落笔
                    text = if (lastStep) "保存档案,去和 AI 聊计划" else "下一步",
                    onClick = { if (lastStep) vm.saveProfileOnly() else vm.next() },
                    enabled = !state.busy,
                    loading = state.busy
                )
                if (state.step > 0) {
                    TextAction(
                        text = "回去改一下",
                        onClick = vm::back,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 顶部步进指示(「备考档案」页复用):用等宽横条,比"1/3"更像进度而不像页码。
 * 首次引导本身改用了「第 x / y 步 + ProgressLine」,这里保留给计划设置页。
 */
@Composable
internal fun StepDots(step: Int, total: Int = ONBOARDING_STEPS) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(if (index <= step) colors.primary else colors.surfaceVariant)
            )
        }
    }
}

@Composable
internal fun StepTarget(state: OnboardingUiState, vm: OnboardingViewModel) {
    val colors = MaterialTheme.colorScheme
    FieldLabel("你要准备的是", "不同考试的节奏差得很远,后面阶段的划分会跟着变")
    Spacer(Modifier.height(8.dp))
    Column {
        TARGET_TYPES.forEach { type ->
            ChoiceRow(
                title = type,
                selected = state.targetType == type,
                multi = false,
                onClick = { vm.setTargetType(type) }
            )
        }
    }

    Spacer(Modifier.height(20.dp))
    FieldLabel("考试是哪天", "留出这一天,倒计时和阶段划分都以它为终点")
    Spacer(Modifier.height(8.dp))
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

/** 单选题用完成圆环收尾,多选题只在选中时露出勾 */
@Composable
private fun ChoiceRow(
    title: String,
    selected: Boolean,
    multi: Boolean,
    onClick: () -> Unit
) {
    ListRow(
        title = title,
        onClick = onClick,
        showChevron = false,
        modifier = Modifier.semantics { this.selected = selected },
        trailing = {
            if (multi) {
                if (selected) {
                    Icon(
                        AppIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                CompletionCircle(
                    checked = selected,
                    onClick = onClick,
                    description = if (selected) "已选择 $title" else "选择 $title"
                )
            }
        }
    )
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
            // 48dp 触达:日历图标本身就是入口,不再只是装饰
            IconButton(onClick = { showPicker = true }, modifier = Modifier.size(48.dp)) {
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
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outline.copy(alpha = 0.7f)
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
internal fun StepRhythm(state: OnboardingUiState, vm: OnboardingViewModel) {
    FieldLabel("哪些时段你基本固定学得进去", "可以多选。排课会优先落进这些时段")
    Spacer(Modifier.height(8.dp))
    Column {
        STUDY_WINDOWS.forEach { window ->
            ChoiceRow(
                title = window,
                selected = window in state.studyWindows,
                multi = true,
                onClick = { vm.toggleStudyWindow(window) }
            )
        }
    }

    Spacer(Modifier.height(20.dp))
    FieldLabel("现在的基础大致是", "只影响前期的强度,后面会随着进度自己调整")
    Spacer(Modifier.height(8.dp))
    // 三档基础是分档题,用分段选择而不是一排可勾选行
    SegmentedChips(
        options = FOUNDATION_LEVELS,
        selectedIndex = FOUNDATION_LEVELS.indexOf(state.foundation),
        onSelect = { vm.setFoundation(FOUNDATION_LEVELS[it]) }
    )

    Spacer(Modifier.height(20.dp))
    FieldLabel("哪几门最让你没底", "最多 6 门。选中的科目会分到更多时间")
    Spacer(Modifier.height(8.dp))
    Column {
        state.subjectOptions.forEach { name ->
            ChoiceRow(
                title = name,
                selected = name in state.weakSubjects,
                multi = true,
                onClick = { vm.toggleWeakSubject(name) }
            )
        }
    }
    Spacer(Modifier.height(8.dp))
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
    Spacer(Modifier.height(8.dp))
    Column {
        state.subjectOptions.forEach { name ->
            ChoiceRow(
                title = name,
                selected = name in state.examSubjects,
                multi = true,
                onClick = { vm.toggleExamSubject(name) }
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    CustomAddRow(label = "没有你的科目就自己加", placeholder = "比如 数据结构") { vm.addExamSubject(it) }

    Spacer(Modifier.height(20.dp))
    FieldLabel(
        "每周课表:哪天几点到几点真坐得下来",
        "点「+」给那天加空闲时段,任意钟点都行;这天没空就空着,排课不会往里塞任务"
    )
    Spacer(Modifier.height(8.dp))
    var editorDay by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..7).forEach { day ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "周${WEEKDAY_LABELS[day - 1]}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(40.dp)
                )
                Spacer(Modifier.width(8.dp))
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.availability[day].orEmpty().forEach { window ->
                        WindowChip(window = window, onRemove = { vm.removeAvailability(day, window) })
                    }
                    AddWindowChip(onClick = { editorDay = day })
                }
            }
        }
    }
    if (editorDay != null) {
        WindowEditorDialog(
            weekday = editorDay!!,
            onDismiss = { editorDay = null },
            onAdd = { start, end -> vm.addAvailability(editorDay!!, start, end) }
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "想更快:加窗口时点「早晨/上午/下午/晚上」一键填好常见钟点,再自己改数字。这份课表就是排课的依据,填得越真,计划越贴身。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant
    )

    Spacer(Modifier.height(20.dp))
    FieldLabel("有课表就传一张,我替你认", "单双周课表都行。认出来的占用会列在下面,和实际不符的直接删掉")
    Spacer(Modifier.height(8.dp))
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.parseTimetable(uri)
    }
    if (state.timetableBusy) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "正在认这张课表…",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }
    } else {
        SecondaryButton(
            text = "从相册选一张课表截图",
            onClick = { picker.launch("image/*") }
        )
    }
    if (state.timetableNotice.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        OnboardingMessage(state.timetableNotice, state.timetableNoticeIsError)
    }

    if (state.commitments.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        FieldLabel("已经记下的固定占用", "每周固定被占掉的时间,排课时不会往里塞任务")
        Spacer(Modifier.height(4.dp))
        state.commitments.forEachIndexed { index, item ->
            ListRow(
                title = "周${WEEKDAY_LABELS[item.weekday - 1]} ${item.start}-${item.end}  ${item.label}",
                trailing = {
                    TextAction(
                        text = "删掉",
                        onClick = { vm.removeCommitment(index) },
                        danger = true
                    )
                }
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    FieldLabel("没有课表?手动补一条", "上课、上班、通勤这类每周固定占掉的时间,补几条就够")
    Spacer(Modifier.height(8.dp))
    ManualCommitmentRow { weekday, start, end, label -> vm.addCommitment(weekday, start, end, label) }
}

/** 课表里的一个钟点窗口胶囊:显示 "08:30-11:30",点一下删除(48dp 触达) */
@Composable
private fun WindowChip(window: TimeWindow, onRemove: () -> Unit) {
    Surface(
        onClick = onRemove,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.heightIn(min = 48.dp)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${window.start}-${window.end}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                AppIcons.Close,
                contentDescription = "删掉这段",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/** 行尾的「+」:弹出钟点编辑对话框给这天加一段空闲(48dp 触达) */
@Composable
private fun AddWindowChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                AppIcons.Add,
                contentDescription = "加一段空闲时段",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 空闲时段编辑对话框:起止两个 HH:mm 输入框 + 快捷模板一键填充。
 * 校验在本地做完(格式/先后/重复),只有真记下才关对话框,报错留在原地让用户改。
 */
@Composable
private fun WindowEditorDialog(weekday: Int, onDismiss: () -> Unit, onAdd: (String, String) -> Boolean) {
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val from = normalizeClock(start)
        val to = normalizeClock(end)
        error = when {
            from == null || to == null -> "时间写成 08:00 这样,冒号别忘"
            from >= to -> "结束要比开始晚"
            !onAdd(from, to) -> "这段时段已经加过了"
            else -> null
        }
        if (error == null) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("周${WEEKDAY_LABELS[weekday - 1]} · 加一段空闲") },
        text = {
            Column {
                val presets = WINDOW_CLOCK.keys.toList()
                SegmentedChips(
                    options = presets,
                    selectedIndex = presets.indexOfFirst { name ->
                        val clock = WINDOW_CLOCK.getValue(name)
                        start == clock.first && end == clock.second
                    },
                    onSelect = { index ->
                        val clock = WINDOW_CLOCK.getValue(presets[index])
                        start = clock.first
                        end = clock.second
                        error = null
                    }
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it.take(5); error = null },
                        label = { Text("开始") },
                        placeholder = { Text("08:00") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = end,
                        onValueChange = { end = it.take(5); error = null },
                        label = { Text("结束") },
                        placeholder = { Text("11:30") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    )
                }
                val currentError = error
                if (currentError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        currentError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = ::submit) { Text("加上") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ManualCommitmentRow(onAdd: (Int, String, String, String) -> Boolean) {
    var weekday by remember { mutableStateOf(1) }
    var label by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }

    Column {
        SegmentedChips(
            options = (1..7).map { "周${WEEKDAY_LABELS[it - 1]}" },
            selectedIndex = weekday - 1,
            onSelect = { weekday = it + 1 }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(16) },
            label = { Text("是什么事") },
            placeholder = { Text("比如 上课") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
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
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = end,
                onValueChange = { end = it.take(5) },
                label = { Text("结束") },
                placeholder = { Text("11:30") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            text = "记下这条",
            onClick = {
                // 只有真记下了才清空:校验没过(时间格式/先后顺序)留着让用户改,不白打一遍
                if (onAdd(weekday, start, end, label)) {
                    label = ""
                    start = ""
                    end = ""
                }
            }
        )
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
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.outline.copy(alpha = 0.7f)
            ),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        SecondaryButton(
            text = "加上",
            onClick = {
                onAdd(custom)
                custom = ""
            },
            modifier = Modifier.width(88.dp)
        )
    }
}

/** 题目内的分组标题:标题 titleMedium、说明 bodySmall,不手写字号与字重 */
@Composable
private fun FieldLabel(title: String, hint: String? = null) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (hint != null) {
        Spacer(Modifier.height(2.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun OnboardingMessage(message: String, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    val content = if (isError) colors.error else successContentColor
    Surface(
        shape = MaterialTheme.shapes.medium,
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
                tint = content,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = content)
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        AppCard(
            contentPadding = PaddingValues(20.dp),
            modifier = Modifier.widthIn(max = 440.dp)
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    AppIcons.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "档案存好了,计划还差一步",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    state.message.ifEmpty { "服务器这次没把计划排出来。你本机的计划没受影响,可以照常学。" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                PrimaryButton(
                    text = "再试一次",
                    onClick = vm::retry,
                    enabled = !state.busy,
                    loading = state.busy
                )
                Spacer(Modifier.height(4.dp))
                TextAction(
                    text = "先不管,照样开始学",
                    onClick = vm::dismissFallback,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
