package com.yanzhong.app.ui.plan

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.data.remote.InterviewMessageDto
import com.yanzhong.app.data.remote.PlanBriefDto
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.PlanAccent
import com.yanzhong.app.ui.theme.PlanAccent2
import com.yanzhong.app.ui.theme.PlanBg
import com.yanzhong.app.ui.theme.PlanInk
import com.yanzhong.app.ui.theme.PlanMuted
import com.yanzhong.app.ui.theme.PlanTeal

/**
 * 「AI 备考面谈」页。
 *
 * 这一页存在的理由:一份 400+ 天的计划排得好不好,取决于排之前了解了多少——
 * 目标院校与专业、跨考与否、在职还是全职、每科真实水平、手上有什么资料、上次自测多少分。
 * 这些用两页问卷问不出来,所以这里做成一次对话:AI 逐条追问,答够了它才动笔。
 *
 * 三条状态各有各的处理,不能混:
 * - 还没档案 → 先把档案补上(面谈产出的简报得有地方落库,计划也要求档案完整)
 * - 在聊 → 消息流 + 快捷选项 + 输入框;想早点结束也有出口
 * - 聊完 → 先让用户看一眼"AI 理解成了什么",确认后再生成
 */
@Composable
fun PlanInterviewScreen(padding: PaddingValues, navController: NavHostController) {
    val vm: PlanInterviewViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 唯一入口:进页面(含从"补档案"页返回)时读一次交接标记,再决定要不要重开会话。
    // 只放一个 LaunchedEffect,避免和 beginSession 自己的幂等判断互相打架。
    LaunchedEffect(Unit) {
        val saved = navController.currentBackStackEntry
            ?.savedStateHandle?.remove<Boolean>(Routes.EXTRA_PLAN_SETUP_DONE) == true
        vm.beginSession(profileJustSaved = saved)
    }

    // 计划生成成功:回手在主界面上一层(计划页)留个标记,返回时它会自己去刷新
    LaunchedEffect(state.phase) {
        if (state.phase == InterviewPhase.SUCCESS) {
            navController.previousBackStackEntry
                ?.savedStateHandle?.set(Routes.EXTRA_PLAN_SETUP_DONE, true)
        }
    }

    // 新消息进来就滚到底,别让用户以为 AI 没回
    LaunchedEffect(state.messages.size, state.sending) {
        if (state.messages.isNotEmpty()) {
            val target = if (state.sending) state.messages.size else state.messages.lastIndex
            listState.animateScrollToItem(target.coerceAtLeast(0))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(PlanBg, Color.White)))
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            InterviewTopBar(onBack = { navController.popBackStack() })

            when (state.phase) {
                InterviewPhase.LOADING -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) { LoadingBlock() }

                InterviewPhase.NEED_PROFILE -> Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    NeedProfileCard(
                        onFillProfile = { navController.navigate(Routes.planSetup(profileOnly = true)) },
                        onBack = { navController.popBackStack() }
                    )
                }

                InterviewPhase.CHATTING -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            count = state.messages.size,
                            key = { index -> index }
                        ) { index ->
                            MessageBubble(state.messages[index])
                        }
                        if (state.sending) {
                            item(key = "typing") { TypingBubble() }
                        }
                    }
                    ChatComposer(
                        input = input,
                        onInputChange = { input = it },
                        options = state.options,
                        busy = state.busy,
                        done = state.done,
                        canFinishEarly = state.hasUserTurn && !state.done,
                        error = state.error,
                        onSend = { text ->
                            input = ""
                            vm.send(text)
                        },
                        onOption = { vm.send(it) },
                        onFinishEarly = vm::finishNow,
                        onRetry = vm::retry,
                        onDismissError = vm::dismissError,
                        bottomPadding = padding.calculateBottomPadding()
                    )
                }

                InterviewPhase.SUCCESS -> Column(
                    Modifier.fillMaxWidth().weight(1f).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    SuccessBlock(
                        brief = state.brief,
                        onOpenDocument = {
                            navController.navigate(Routes.PLAN_DOCUMENT) {
                                popUpTo(Routes.PLAN_INTERVIEW) { inclusive = true }
                            }
                        },
                        onDone = { navController.popBackStack() }
                    )
                }
            }
        }

        if (state.generating) GeneratingOverlay()
    }
}

/** 顶部返回栏:深层页没有底部导航,返回只能由本页自己给 */
@Composable
private fun InterviewTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(AppIcons.ArrowBack, contentDescription = "返回", tint = PlanInk)
        }
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "AI 备考面谈",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = PlanInk
            )
            Text(
                "聊几句,让它摸清你的情况再排计划",
                style = MaterialTheme.typography.bodySmall,
                color = PlanMuted
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = PlanAccent)
        Spacer(Modifier.height(12.dp))
        Text("正在看看你已有哪些信息…", style = MaterialTheme.typography.bodyMedium, color = PlanMuted)
    }
}

/**
 * 档案缺失时的岔路口。
 *
 * 不是"挡住不让走",而是说清楚为什么得先填:面谈产出的简报要落在档案上,
 * 计划生成也要求档案完整——先花 30 秒填完,后面的对话才作数。
 */
@Composable
private fun NeedProfileCard(onFillProfile: () -> Unit, onBack: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp).padding(24.dp)
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(PlanAccent, PlanAccent2))),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.Target, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("先花 30 秒填一下备考档案", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = PlanInk)
            Spacer(Modifier.height(8.dp))
            Text(
                "考什么、哪天考、每天能学多久——这三项是排计划的骨架,也让面谈不必从头问起。" +
                    "填完回到这里,我们就接着聊。",
                style = MaterialTheme.typography.bodyMedium,
                color = PlanMuted
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onFillProfile,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PlanAccent),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("去填备考档案", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onBack) { Text("先不看计划了", color = PlanMuted) }
        }
    }
}

/** 一条对话气泡:AI 白卡在左,自己渐变气泡在右 */
@Composable
private fun MessageBubble(message: InterviewMessageDto) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(PlanAccent, PlanAccent2))),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.Chat, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 18.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            color = if (isUser) Color.Transparent else Color.White,
            shadowElevation = if (isUser) 0.dp else 3.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Box(
                Modifier
                    .then(
                        if (isUser) Modifier.background(Brush.linearGradient(listOf(PlanAccent, PlanAccent2)))
                        else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) Color.White else PlanInk
                )
            }
        }
    }
}

/** AI 正在想:三个点 + 一行小字,比一个转圈更不像"卡住了" */
@Composable
private fun TypingBubble() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(PlanAccent, PlanAccent2))),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Chat, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(8.dp))
        Surface(shape = RoundedCornerShape(18.dp), color = Color.White, shadowElevation = 3.dp) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = PlanAccent
                )
                Text("正在想怎么问你…", style = MaterialTheme.typography.bodySmall, color = PlanMuted)
            }
        }
    }
}

/**
 * 底部输入区:快捷选项 + 输入框 + 发送。
 *
 * 快捷选项不是装饰——手机上敲一句"在职备考、目标某校计算机"要十几秒,
 * 而 AI 给的选项大多能覆盖常见情况,点一下就走一轮。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatComposer(
    input: String,
    onInputChange: (String) -> Unit,
    options: List<String>,
    busy: Boolean,
    done: Boolean,
    canFinishEarly: Boolean,
    error: String?,
    onSend: (String) -> Unit,
    onOption: (String) -> Unit,
    onFinishEarly: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp
) {
    Surface(color = Color.White, shadowElevation = 10.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (error != null) {
                ErrorBar(error = error, onRetry = onRetry, onDismiss = onDismissError)
                Spacer(Modifier.height(8.dp))
            }

            if (options.isNotEmpty() && !busy) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        Surface(
                            onClick = { onOption(option) },
                            shape = RoundedCornerShape(999.dp),
                            color = PlanAccent.copy(alpha = 0.08f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(
                                option,
                                style = MaterialTheme.typography.labelLarge,
                                color = PlanAccent,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    enabled = !busy,
                    placeholder = { Text(if (done) "还想补充点什么吗" else "也可以自己打字回答…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { onSend(input) },
                    enabled = !busy && input.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (!busy && input.isNotBlank()) Brush.linearGradient(listOf(PlanAccent, PlanAccent2))
                            else Brush.linearGradient(listOf(Color(0xFFBFC3D8), Color(0xFFBFC3D8)))
                        )
                ) {
                    Icon(AppIcons.Send, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            if (canFinishEarly) {
                TextButton(onClick = onFinishEarly, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("不想聊了,直接开始排计划", color = PlanMuted)
                }
            }
            Spacer(Modifier.height(bottomPadding))
        }
    }
}

@Composable
private fun ErrorBar(error: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) { Text("重试") }
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

/**
 * 聊完之后的确认屏。
 *
 * 为什么要把简报摊开给用户看:AI 理解错了(比如把"在职"听成"全职")，
 * 与其等几百天的计划生成出来才发现,不如在这四五行里当场纠正。
 */
@Composable
private fun SuccessBlock(brief: PlanBriefDto?, onOpenDocument: () -> Unit, onDone: () -> Unit) {
    val empty = brief == null
    Column(
        Modifier.fillMaxWidth().widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(PlanTeal, PlanAccent))),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("计划排好了", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = PlanInk)
        Spacer(Modifier.height(6.dp))
        Text(
            if (empty) "按你刚才聊的排完了。"
            else "这是我从谈话里记下的你的情况,已按它排好整份计划。",
            style = MaterialTheme.typography.bodyMedium,
            color = PlanMuted
        )
        Spacer(Modifier.height(16.dp))

        if (brief != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (brief.summary.isNotBlank()) {
                        Text(brief.summary, style = MaterialTheme.typography.bodyMedium, color = PlanInk)
                    }
                    BriefRow("目标", brief.goals)
                    BriefRow("约束", brief.constraints)
                    BriefRow("重点", brief.focus)
                    BriefRow("资料", brief.materials)
                    BriefRow("其它", brief.notes)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Button(
            onClick = onOpenDocument,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PlanAccent),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Icon(AppIcons.Doc, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("查看我的全程规划", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onDone) { Text("回到计划页", color = PlanMuted) }
    }
}

@Composable
private fun BriefRow(label: String, values: List<String>) {
    if (values.isEmpty()) return
    Row {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = PlanAccent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(40.dp)
        )
        Text(
            values.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = PlanInk
        )
    }
}

/** 生成中的全屏遮罩:几百天的计划是长任务,要解释清楚"在等什么" */
@Composable
private fun GeneratingOverlay() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = PlanAccent)
                Text("正在按你的情况排几百天…", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = PlanInk)
                Text(
                    "这一份要连阶段、各科、作息和资料一起写,比填表慢一些,先别退出这一页",
                    style = MaterialTheme.typography.bodySmall,
                    color = PlanMuted
                )
            }
        }
    }
}
