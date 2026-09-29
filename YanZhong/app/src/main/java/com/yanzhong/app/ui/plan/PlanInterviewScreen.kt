package com.yanzhong.app.ui.plan

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.yanzhong.app.data.remote.InterviewMessageDto
import com.yanzhong.app.data.remote.PlanBriefDto
import com.yanzhong.app.data.remote.PlanDto
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.theme.AppIcons
import java.time.LocalDate

@Composable
fun PlanInterviewScreen(padding: PaddingValues, navController: NavHostController) {
    val vm: PlanInterviewViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var adjustPreview by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val entry by navController.currentBackStackEntryAsState()
    val profileSaved by (entry?.savedStateHandle?.getStateFlow(Routes.EXTRA_PLAN_PROFILE_SAVED, false)
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()

    LaunchedEffect(entry, profileSaved) {
        if (entry?.destination?.route == Routes.PLAN_INTERVIEW) {
            vm.beginSession(profileJustSaved = profileSaved)
            if (profileSaved) entry?.savedStateHandle?.remove<Boolean>(Routes.EXTRA_PLAN_PROFILE_SAVED)
        }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == InterviewPhase.SUCCESS) {
            runCatching { navController.getBackStackEntry(Routes.PLAN) }.getOrNull()
                ?.savedStateHandle?.set(Routes.EXTRA_PLAN_SETUP_DONE, true)
        }
    }
    LaunchedEffect(state.messages.size, state.sending) {
        if (state.messages.isNotEmpty() && state.phase == InterviewPhase.CHATTING) {
            // 发送中的「规划师正在整理…」是列表的最后一项(下标 = messages.size)。
            // 不算上它，考生点完选项后看不到任何反应，像卡住了。
            listState.animateScrollToItem(if (state.sending) state.messages.size else state.messages.lastIndex)
        }
    }
    BackHandler(fullscreen && state.phase == InterviewPhase.DRAFT) { fullscreen = false }
    LaunchedEffect(state.phase) { if (state.phase != InterviewPhase.DRAFT) fullscreen = false }
    BackHandler(adjustPreview && state.phase == InterviewPhase.CHATTING) { adjustPreview = false }
    LaunchedEffect(state.adjustment) { if (state.adjustment == null) adjustPreview = false }

    Column(Modifier.fillMaxSize().background(Color(0xFFF7F8F6)).imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (fullscreen) fullscreen = false else navController.popBackStack() }) {
                Icon(AppIcons.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(if (fullscreen) "全屏预览 · DRAFT"
                    else if (state.mode == InterviewMode.ADJUST) "AI 行程小助手" else "AI 备考面谈",
                    style = MaterialTheme.typography.titleMedium)
                // 副标题说明「AI 在问什么/已经确认了什么」，避免只有光秃秃一个对话框
                if (!fullscreen && state.phase == InterviewPhase.CHATTING) {
                    Text(chatterSubtitle(state), style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF6B7A72), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!fullscreen && state.phase == InterviewPhase.CHATTING) {
                val answered = state.messages.count { it.role == "user" }
                if (answered > 0) Surface(color = Color(0xFFEFF6F1), shape = RoundedCornerShape(50.dp)) {
                    Text("已答 $answered 轮", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFF1F5A3D))
                }
            }
        }
        if (state.phase == InterviewPhase.CHATTING && state.mode == InterviewMode.BUILD) {
            InterviewProgress(state.brief,
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
        when (state.phase) {
            InterviewPhase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            InterviewPhase.NEED_PROFILE -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("先补齐备考档案，再开始面谈", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { navController.navigate(Routes.planSetup(profileOnly = true)) }) { Text("填写备考档案") }
            }
            InterviewPhase.CHATTING -> {
                if (adjustPreview && state.adjustment?.tier == "L2") {
                    AdjustmentPreviewColumn(
                        state = state,
                        onBack = { adjustPreview = false },
                        onConfirm = vm::confirmAdjustment,
                        onRetry = vm::retry,
                        onDismiss = vm::dismissError,
                        bottom = padding.calculateBottomPadding(),
                    )
                } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.messages.size) { index ->
                        val isLast = index == state.messages.lastIndex
                        val isAi = state.messages[index].role == "assistant"
                        // 快捷答案跟着它要回答的那句问题走:贴在问题卡片下面,
                        // 而不是沉在输入框上方,省得考生来回对照「这几个选项答的是哪一句」
                        MessageBubble(
                            message = state.messages[index],
                            options = if (isLast && isAi) state.options else emptyList(),
                            optionsEnabled = !state.busy,
                            onOption = vm::send,
                        )
                    }
                    if (state.sending) item { TypingRow() }
                }
                state.adjustment?.let { adjustment ->
                    AdjustmentCard(
                        summary = adjustment.summary ?: "已按你的情况算好新排法",
                        tier = adjustment.tier,
                        changes = adjustment.changes,
                        confirming = state.confirming,
                        onConfirm = vm::confirmAdjustment,
                        onDismiss = vm::dismissAdjustmentCard,
                        onPreview = if (adjustment.tier == "L2") ({ adjustPreview = true }) else null,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
                Surface(color = Color.White, shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        state.error?.let { ErrorRow(it, vm::retry, vm::dismissError) }
                        if (state.done) {
                            Surface(color = Color(0xFFEFF6F1), shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text("面谈已完成", style = MaterialTheme.typography.labelLarge,
                                        color = Color(0xFF1F5A3D))
                                    Text("确认前不会改变当前计划，草稿可先预览再决定。",
                                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF4A5C51))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = vm::generate, enabled = state.canGenerate, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state.generating) "正在生成草稿…" else "生成计划草稿")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = input, onValueChange = { input = it },
                                enabled = !state.busy, modifier = Modifier.weight(1f), maxLines = 4,
                                placeholder = { Text(if (state.done) "还有要纠正的内容？" else "回答面谈问题") })
                            IconButton(onClick = { vm.send(input); input = "" },
                                enabled = !state.busy && input.isNotBlank()) {
                                Icon(AppIcons.Send, contentDescription = "发送")
                            }
                        }
                        Spacer(Modifier.height(padding.calculateBottomPadding()))
                    }
                }
                }
            }
            InterviewPhase.DRAFT -> {
                val draft = state.plan
                if (draft != null) {
                    val today = LocalDate.now()
                    val diff = remember(draft, state.activePlan, today) { compareTodayItems(draft, state.activePlan, today) }
                    val html = remember(draft, diff, today, state.activeCompared) {
                        renderDraftHtml(draft, diff, today, state.activeCompared)
                    }
                    DraftContent(draft, diff, html, fullscreen, state.activeCompared,
                        onFullscreen = { fullscreen = !fullscreen },
                        onConfirm = vm::confirm, onChat = { fullscreen = false; vm.backToChat() },
                        onProfile = { fullscreen = false; navController.navigate(Routes.planSetup(profileOnly = true)) },
                        onRetry = vm::retry, onDismiss = vm::dismissError,
                        canConfirm = state.canConfirm, error = state.error, bottom = padding.calculateBottomPadding())
                }
            }
            InterviewPhase.SUCCESS -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("计划已生效", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    navController.navigate(Routes.PLAN_DOCUMENT) {
                        popUpTo(Routes.PLAN_INTERVIEW) { inclusive = true }
                    }
                }) { Text("查看生效文档") }
                TextButton(onClick = { navController.popBackStack() }) { Text("返回计划页") }
            }
        }
    }
}

/** 聊天区副标题：把「AI 现在在做什么/已经确认了什么」显式说出来，减少人机感 */
private fun chatterSubtitle(state: InterviewUiState): String {
    if (state.mode == InterviewMode.ADJUST) return "说说发生了什么,我帮你重排近期计划"
    if (state.done) return "关键信息已齐，可以生成计划草稿了"
    val summary = state.brief?.summary?.trim().orEmpty()
    if (summary.isNotEmpty()) return "已确认：$summary"
    return "AI 会先补齐关键事实，再按你的空档排计划"
}

/**
 * 面谈进度的三个粗项：正式科目 / 空闲时段 / 固定占用，已确认显绿、未确认显灰。
 * 判定条件与服务端生成前的检查一致(assessPlanningFacts)，但这里只报粗项——
 * 逐科的范围、剩余量、里程碑等细项仍由服务端在回复里点名，免得两端各写一套规则互相漂移。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterviewProgress(brief: PlanBriefDto?, modifier: Modifier = Modifier) {
    val subjectsReady = !brief?.examSubjects.isNullOrEmpty()
    val availabilityReady = brief != null && brief.availabilityConfirmed &&
        brief.availability.isNotEmpty() && brief.availability.any { it.windows.isNotEmpty() }
    val commitmentsReady = brief?.commitmentsConfirmed == true
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FactChip("正式科目", subjectsReady)
        FactChip("空闲时段", availabilityReady)
        FactChip("固定占用", commitmentsReady)
    }
}

@Composable
private fun FactChip(label: String, ready: Boolean) {
    Surface(color = if (ready) Color(0xFFEFF6F1) else Color(0xFFF1F2F0),
        shape = RoundedCornerShape(50.dp)) {
        Text(if (ready) "✓ $label" else "待确认 · $label",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (ready) Color(0xFF1F5A3D) else Color(0xFF8A948D))
    }
}

/** 一条回复拆成「一句接话」和「本轮要回答的问题」两类,只有后者需要被看见 */
private data class ReplyPart(val text: String, val isQuestion: Boolean)

private fun looksLikeQuestion(text: String): Boolean =
    text.contains('？') || text.contains('?') || text.endsWith("吗") || text.endsWith("呢")

/**
 * 提示词要求 AI 把「接话」和「本轮唯一的问题」用换行分成两段,所以按最后一段带问号的那句找问题;
 * 模型偶尔漏掉问号时退回「最后一段就是问题」——总比整段回复都平铺、考生自己找要强。
 * 单行且不像问句(收尾那句「可以生成计划草稿了」)就按普通文字渲染,不硬套问题样式。
 */
private fun replyParts(content: String): List<ReplyPart> {
    val lines = content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return emptyList()
    val questionAt = if (lines.size == 1) {
        if (looksLikeQuestion(lines[0])) 0 else -1
    } else {
        lines.indexOfLast { looksLikeQuestion(it) }.takeIf { it >= 0 } ?: lines.lastIndex
    }
    return lines.mapIndexed { index, text -> ReplyPart(text, index == questionAt) }
}

@Composable
private fun MessageBubble(
    message: InterviewMessageDto,
    options: List<String> = emptyList(),
    optionsEnabled: Boolean = false,
    onOption: (String) -> Unit = {},
) {
    if (message.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = Color(0xFFDCEBE1), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(0.85f)) {
                Text(message.content, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFF24352C))
            }
        }
        return
    }
    // AI 侧只留两块:一句灰色接话 + 一张醒目的「本轮要回答的问题」卡片,
    // 考生扫一眼就知道该答什么,不必在整段文字里找问号
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(Modifier.fillMaxWidth(0.92f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(Color(0xFF2E7D5B), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("规划师", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D5B))
            }
            Spacer(Modifier.height(6.dp))
            Surface(color = Color.White, shape = RoundedCornerShape(14.dp), shadowElevation = 1.dp) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    replyParts(message.content).forEach { part ->
                        if (part.isQuestion) QuestionCard(part.text)
                        else Text(part.text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5A5F5C))
                    }
                    QuickAnswers(options, optionsEnabled, onOption)
                }
            }
        }
    }
}

/** 本轮唯一的问题:单独填色、加「请回答」标签并放大加粗,和上面的接话拉开层级 */
@Composable
private fun QuestionCard(text: String) {
    Surface(color = Color(0xFFEFF6F1), shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFFB8D6C4))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text("请回答", style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF2E7D5B), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold, color = Color(0xFF173D2B))
        }
    }
}

@Composable
private fun TypingRow() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("规划师正在整理…", style = MaterialTheme.typography.labelMedium, color = Color(0xFF7A8A80))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAnswers(options: List<String>, enabled: Boolean, onOption: (String) -> Unit) {
    if (!enabled || options.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            Surface(
                onClick = { onOption(option) },
                shape = RoundedCornerShape(50.dp),
                color = Color(0xFFEFF6F1),
                border = BorderStroke(1.dp, Color(0xFFCBDFD2)),
            ) {
                Text(option, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge, color = Color(0xFF1F5A3D))
            }
        }
    }
}

@Composable
private fun ColumnScope.DraftContent(
    draft: PlanDto, diff: TodayItemDiff, html: String, fullscreen: Boolean, activeCompared: Boolean,
    onFullscreen: () -> Unit, onConfirm: () -> Unit, onChat: () -> Unit,
    onProfile: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit,
    canConfirm: Boolean, error: String?, bottom: androidx.compose.ui.unit.Dp
) {
    if (!fullscreen) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("DRAFT · 尚未生效", color = Color(0xFFAA5D38), style = MaterialTheme.typography.labelMedium)
            Text(draft.title, style = MaterialTheme.typography.titleMedium)
            Text(if (activeCompared) "今天将移除 ${diff.removed.size} 项，新增 ${diff.added.size} 项"
                else "当前计划读取失败，无法对比今日移除项", style = MaterialTheme.typography.bodySmall)
            if (activeCompared && diff.removed.isNotEmpty()) Text("移除：${diff.removed.joinToString("、") { it.title }}", style = MaterialTheme.typography.bodySmall)
            if (diff.added.isNotEmpty()) Text("新增：${diff.added.joinToString("、") { it.title }}", style = MaterialTheme.typography.bodySmall)
        }
    }
    DraftWebView(html, Modifier.weight(1f).fillMaxWidth())
    Surface(shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (!fullscreen) {
                error?.let { ErrorRow(it, onRetry, onDismiss) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChat, modifier = Modifier.weight(1f)) { Text("回聊天更正") }
                    OutlinedButton(onClick = onProfile, modifier = Modifier.weight(1f)) { Text("改档案重生成") }
                }
                Button(onClick = onConfirm, enabled = canConfirm, modifier = Modifier.fillMaxWidth()) {
                    Text("确认并启用这份计划")
                }
            }
            TextButton(onClick = onFullscreen, modifier = Modifier.fillMaxWidth()) {
                Text(if (fullscreen) "退出全屏预览" else "全屏阅读草稿")
            }
            Spacer(Modifier.height(bottom))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DraftWebView(html: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val webView = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.blockNetworkLoads = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean = true
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(webView) { onDispose { webView.destroy() } }
    AndroidView(factory = { webView }, modifier = modifier, update = {
        if (it.tag != html) {
            it.tag = html
            it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    })
}

/**
 * L2 整页预览浮层:用「active plan + 调整单 changes」本地合成预览计划,
 * 复用 renderDraftHtml(含「本次调整」章节)与 DraftWebView,底部直接确认。
 */
@Composable
private fun AdjustmentPreviewColumn(
    state: InterviewUiState,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    bottom: androidx.compose.ui.unit.Dp,
) {
    val adjustment = state.adjustment ?: return
    val preview = remember(state.activePlan, adjustment) { buildAdjustmentPreview(state.activePlan, adjustment) }
    if (preview == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("当前计划还没读到,无法整页预览", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onBack) { Text("回聊天") }
        }
        return
    }
    val today = LocalDate.now()
    val diff = remember(preview, state.activePlan, today) { compareTodayItems(preview, state.activePlan, today) }
    val html = remember(preview, diff, today, adjustment) {
        renderDraftHtml(preview, diff, today, activeCompared = true, adjustment = adjustment)
    }
    Column(Modifier.fillMaxSize()) {
        DraftWebView(html, Modifier.weight(1f).fillMaxWidth())
        Surface(shadowElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                state.error?.let { ErrorRow(it, onRetry, onDismiss) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("回聊天") }
                    Button(onClick = onConfirm, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Text(if (state.confirming) "正在生效…" else "确认调整")
                    }
                }
                Spacer(Modifier.height(bottom))
            }
        }
    }
}

@Composable
private fun ErrorRow(error: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("重试") }
        TextButton(onClick = onDismiss) { Text("关闭") }
    }
}
