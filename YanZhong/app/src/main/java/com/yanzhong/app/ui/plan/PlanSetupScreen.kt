package com.yanzhong.app.ui.plan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.onboarding.ExamDateField
import com.yanzhong.app.ui.onboarding.ONBOARDING_STEPS
import com.yanzhong.app.ui.onboarding.OnboardingPhase
import com.yanzhong.app.ui.onboarding.OnboardingViewModel
import com.yanzhong.app.ui.onboarding.StepDots
import com.yanzhong.app.ui.onboarding.StepFacts
import com.yanzhong.app.ui.onboarding.StepRhythm
import com.yanzhong.app.ui.onboarding.StepSubjects
import com.yanzhong.app.ui.onboarding.StepTarget
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.SectionHeader
import com.yanzhong.app.ui.theme.SubPageHeader
import com.yanzhong.app.ui.theme.TextAction

@Composable
fun PlanSetupScreen(padding: PaddingValues, navController: NavHostController, profileOnly: Boolean = false) {
    val vm: OnboardingViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.startManage() }
    LaunchedEffect(state.phase) {
        if (state.phase == OnboardingPhase.SUCCESS) {
            if (profileOnly) {
                navController.previousBackStackEntry?.savedStateHandle?.set(Routes.EXTRA_PLAN_PROFILE_SAVED, true)
                navController.popBackStack()
            } else {
                // 这条路径就是「调整档案并重新生成」:档案已改、计划必然重排,所以把模式直接写进路由,
                // 让面谈页确定性地走制定模式,而不是靠「有没有生效计划」去猜成行程小助手
                navController.navigate(Routes.planInterview("build")) {
                    popUpTo(Routes.PLAN_SETUP) { inclusive = true }
                }
            }
        }
    }
    // 表单阶段的保存按钮固定在底部安全区;加载 / 重试阶段不显示
    val showForm = state.phase == OnboardingPhase.EDITING || state.phase == OnboardingPhase.SUCCESS
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(bottom = 96.dp)
        ) {
            SubPageHeader(
                title = "制定备考档案",
                meta = "AI 按这份档案排计划",
                onBack = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "保存后继续面谈，确认草稿前不会替换现有计划",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                when (state.phase) {
                    OnboardingPhase.LOADING, OnboardingPhase.SUBMITTING -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(if (state.busy) "正在保存档案…" else "正在读取档案…")
                    }
                    OnboardingPhase.FALLBACK -> {
                        Text(state.message)
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton(text = "重试", onClick = vm::startManage)
                    }
                    else -> {
                        StepDots(state.step)
                        Spacer(Modifier.height(20.dp))
                        // 档案完整时直接落在最后一题,可考期在第一步 —— 这里常驻一块考期,
                        // 想改日期的人不必先点两次「返回上一步」才找得到。
                        if (state.step > 0) {
                            AppCard {
                                Text("考试是哪天", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "倒计时和阶段划分都以它为终点",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(10.dp))
                                // 与问卷第一步同一个组件:日历图标可点,不再是摆设
                                ExamDateField(value = state.examDate, onValueChange = vm::setExamDate)
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                        SectionHeader(stepSectionTitle(state.step))
                        Spacer(Modifier.height(8.dp))
                        AppCard {
                            when (state.step) {
                                0 -> StepTarget(state, vm)
                                1 -> StepSubjects(state, vm)
                                2 -> StepRhythm(state, vm)
                                else -> StepFacts(state, vm)
                            }
                        }
                        if (state.message.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (state.step > 0) {
                            Spacer(Modifier.height(8.dp))
                            TextAction(
                                text = "返回上一步",
                                onClick = vm::back,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (showForm) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            ) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        val lastStep = state.step >= ONBOARDING_STEPS - 1
                        PrimaryButton(
                            text = if (lastStep) "保存档案并继续面谈" else "下一步",
                            onClick = { if (lastStep) vm.saveProfileOnly() else vm.next() },
                            enabled = !state.busy
                        )
                    }
                }
            }
        }
    }
}

/** 当前步骤在卡片外的分组标题 */
private fun stepSectionTitle(step: Int): String = when (step) {
    0 -> "目标与考期"
    1 -> "考试科目"
    2 -> "节奏与短板"
    else -> "空闲与课表"
}
