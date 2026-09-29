package com.yanzhong.app.ui.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.onboarding.OnboardingPhase
import com.yanzhong.app.ui.onboarding.OnboardingViewModel
import com.yanzhong.app.ui.onboarding.StepDots
import com.yanzhong.app.ui.onboarding.StepRhythm
import com.yanzhong.app.ui.onboarding.StepTarget
import com.yanzhong.app.ui.theme.AppIcons

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
                navController.navigate(Routes.PLAN_INTERVIEW) {
                    popUpTo(Routes.PLAN_SETUP) { inclusive = true }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
        .padding(horizontal = 20.dp, vertical = 12.dp)) {
        IconButton(onClick = { navController.popBackStack() }) {
            Icon(AppIcons.ArrowBack, contentDescription = "返回")
        }
        Text("备考档案", style = MaterialTheme.typography.titleLarge)
        Text("保存后继续面谈，确认草稿前不会替换现有计划", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        when (state.phase) {
            OnboardingPhase.LOADING, OnboardingPhase.SUBMITTING -> {
                CircularProgressIndicator()
                Text(if (state.busy) "正在保存档案…" else "正在读取档案…")
            }
            OnboardingPhase.FALLBACK -> {
                Text(state.message)
                Button(onClick = vm::startManage) { Text("重试") }
            }
            else -> {
                StepDots(state.step)
                Spacer(Modifier.height(20.dp))
                if (state.step == 0) StepTarget(state, vm) else StepRhythm(state, vm)
                if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { if (state.step == 0) vm.next() else vm.saveProfileOnly() },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (state.step == 0) "下一步" else "保存档案并继续面谈")
                }
                if (state.step > 0) TextButton(onClick = vm::back) { Text("返回上一步") }
            }
        }
        Spacer(Modifier.height(padding.calculateBottomPadding() + 24.dp))
    }
}
