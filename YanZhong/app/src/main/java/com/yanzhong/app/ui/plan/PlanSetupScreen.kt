package com.yanzhong.app.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.onboarding.OnboardingMessage
import com.yanzhong.app.ui.onboarding.OnboardingPhase
import com.yanzhong.app.ui.onboarding.OnboardingUiState
import com.yanzhong.app.ui.onboarding.OnboardingViewModel
import com.yanzhong.app.ui.onboarding.StepDots
import com.yanzhong.app.ui.onboarding.StepRhythm
import com.yanzhong.app.ui.onboarding.StepTarget
import com.yanzhong.app.ui.theme.AppIcons

/**
 * 「制定 / 调整备考档案」页。
 *
 * 为什么需要它:计划页原来是"有档案才有计划"的被动展示——档案完整而计划缺失时,
 * 用户找不到任何生成入口,页面就死在「暂无计划」上。这一页把入口补回来。
 *
 * [profileOnly] 区分两种来意:
 * - false(默认):存完档案直接让 AI 排计划,并重建当前计划(计划页"调整档案"走这条)
 * - true:只把档案存下来就走(首次问卷、AI 面谈前补档案走这条)——正式的计划要等面谈谈完才排
 *
 * 复用首次问卷的两步表单,而不是另起一套编辑器——同一个档案只有一个真相来源,
 * 两套表单迟早会在字段和校验上分叉。
 */
@Composable
fun PlanSetupScreen(
    padding: PaddingValues,
    navController: NavHostController,
    profileOnly: Boolean = false
) {
    val vm: OnboardingViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    /** 是否已有一份有效计划:决定末步按钮是"生成"还是"重新生成 + 二次确认" */
    var hasExistingPlan by remember { mutableStateOf(false) }
    var showReplaceConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.startManage() }
    // 单独问一次"有没有计划":manage 模式只拉档案,判断不了要不要弹替换确认。
    // profileOnly 模式下不排计划,也就没有"替换"这回事,不必多打一次接口。
    LaunchedEffect(profileOnly) {
        if (profileOnly) return@LaunchedEffect
        hasExistingPlan = runCatching { ApiClient.api().getActivePlan().plan != null }.getOrDefault(false)
    }
    // 生成成功:告诉计划页刷新,然后退出本页(本页不该留下任何中间态)
    LaunchedEffect(state.phase) {
        if (state.phase == OnboardingPhase.SUCCESS) {
            navController.previousBackStackEntry
                ?.savedStateHandle?.set(Routes.EXTRA_PLAN_SETUP_DONE, true)
            navController.popBackStack()
        }
    }

    Box(
        modifier = Modifier
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
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
                SetupTopBar(
                    profileOnly = profileOnly,
                    onBack = { navController.popBackStack() }
                )
                Spacer(Modifier.height(16.dp))

                when (state.phase) {
                    OnboardingPhase.LOADING -> LoadingBlock()

                    OnboardingPhase.FALLBACK -> FallbackBlock(
                        state = state,
                        // profileOnly 下"重试"只该重读档案,绝不能顺手把计划也排了
                        onRetry = {
                            if (!profileOnly && state.profileSaved) vm.regenerate() else vm.startManage()
                        },
                        onExit = { navController.popBackStack() }
                    )

                    else -> SetupForm(
                        state = state,
                        vm = vm,
                        profileOnly = profileOnly,
                        hasExistingPlan = hasExistingPlan,
                        onRequestGenerate = {
                            when {
                                profileOnly -> vm.saveProfileOnly()
                                hasExistingPlan -> showReplaceConfirm = true
                                else -> vm.submit()
                            }
                        }
                    )
                }

                Spacer(Modifier.height(padding.calculateBottomPadding() + 24.dp))
            }
        }

        if (state.busy) GeneratingOverlay()
    }

    if (showReplaceConfirm) {
        AlertDialog(
            onDismissRequest = { showReplaceConfirm = false },
            title = { Text("替换当前计划?") },
            text = {
                Text(
                    "新计划会替换当前计划,已完成的打卡不会保留。\n" +
                        "旧计划会归档保存,之后可以在「历史计划」里查看,不会丢。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showReplaceConfirm = false
                    vm.submit()
                }) { Text("重新生成") }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceConfirm = false }) { Text("再想想") }
            }
        )
    }
}

/** 顶部返回栏:深层页隐藏了底部导航,返回必须由本页自己提供 */
@Composable
private fun SetupTopBar(profileOnly: Boolean, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(AppIcons.ArrowBack, contentDescription = "返回")
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text("备考档案", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (profileOnly) "这两步只需 30 秒;存完就接着和 AI 聊计划"
                else "AI 按这份档案排计划,改完就会重新生成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("正在读取你的备考档案…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SetupForm(
    state: OnboardingUiState,
    vm: OnboardingViewModel,
    profileOnly: Boolean,
    hasExistingPlan: Boolean,
    onRequestGenerate: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val lastStep = state.step >= 1
    StepDots(step = state.step)
    Spacer(Modifier.height(18.dp))
    Text(
        if (state.step == 0) "你要考什么" else "说说你的节奏",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (state.step == 0) "这两项决定计划的起止和阶段划分"
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
            if (state.step == 0) StepTarget(state, vm) else StepRhythm(state, vm)
        }
    }

    if (state.message.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        OnboardingMessage(state.message, state.messageIsError)
    }

    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { if (lastStep) onRequestGenerate() else vm.next() },
        enabled = !state.busy,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Text(
            if (lastStep) {
                when {
                    profileOnly -> "保存档案,继续和 AI 聊计划"
                    hasExistingPlan -> "保存并重新生成计划"
                    else -> "用 AI 制定我的计划"
                }
            } else "下一步",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
    if (lastStep && !profileOnly && hasExistingPlan) {
        Spacer(Modifier.height(8.dp))
        Text(
            "重新生成会按新档案重排整份计划,已完成的打卡不会保留(旧计划会归档)。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
    if (state.step > 0) {
        TextButton(onClick = { vm.back() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("回去改一下")
        }
    }
}

/** 生成失败/档案没拉出来:停在原页给重试,不给"跳过"——用户来这就是为了拿到计划 */
@Composable
private fun FallbackBlock(state: OnboardingUiState, onRetry: () -> Unit, onExit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(AppIcons.Info, contentDescription = null, tint = colors.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text("这次没成功", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            state.message.ifEmpty { "服务器这次没能完成。你原有的计划没受影响。" },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRetry,
            enabled = !state.busy,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("再试一次", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onExit, enabled = !state.busy) { Text("返回计划页") }
    }
}

/** 生成中的全屏遮罩:挡住表单防止重复提交,并说明"要等一会儿"而不是让人觉得卡死 */
@Composable
private fun GeneratingOverlay() {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = colors.primary)
                Text("AI 正在为你制定计划…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "按你的档案连排几百天,通常几秒就好,先别退出这一页",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}
