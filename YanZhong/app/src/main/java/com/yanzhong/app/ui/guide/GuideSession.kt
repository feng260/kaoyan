package com.yanzhong.app.ui.guide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

val LocalGuideController = staticCompositionLocalOf<GuideController?> { null }

/** 页面在组合期声明本页步骤与就绪状态;离开组合时清掉自己的声明 */
@Composable
fun ProvideGuideSpec(spec: GuidePageSpec, ready: Boolean) {
    val controller = LocalGuideController.current ?: return
    SideEffect { controller.declare(spec, ready) }
    DisposableEffect(controller, spec.page) {
        onDispose { controller.clearDeclared(spec.page) }
    }
}

/**
 * 根层驱动:页面就绪并声明了 spec 后,等首步锚点出现再开始;
 * 引导可见期间页面切换、深层页或就绪状态回退都按结束处理。
 */
@Composable
fun GuideHost(
    controller: GuideController,
    currentRoute: String?,
    deepPage: Boolean,
    seenPages: Set<String>
) {
    val spec = controller.declaredSpec
    val ready = controller.declaredReady
    val visible = controller.visible
    val dismissed = controller.dismissedPages

    LaunchedEffect(visible, currentRoute, deepPage, ready, spec?.page) {
        if (!visible) return@LaunchedEffect
        val page = spec?.page ?: return@LaunchedEffect
        if (deepPage || page.route != currentRoute || !ready) controller.finish()
    }

    LaunchedEffect(currentRoute, deepPage, ready, spec, seenPages, dismissed, visible) {
        if (spec == null) return@LaunchedEffect
        if (!shouldStartGuide(
                route = currentRoute,
                deepPage = deepPage,
                ready = ready,
                declaredPage = spec.page,
                seenPages = seenPages + dismissed,
                overlayVisible = visible
            )
        ) {
            return@LaunchedEffect
        }
        delay(300)
        val anchorReady = withTimeoutOrNull(1500) {
            snapshotFlow { spec.steps.any { it.target in controller.targets.bounds } }.first { it }
        }
        if (anchorReady == true && !controller.visible) controller.start(spec)
    }

    LaunchedEffect(
        visible,
        controller.stepIndex,
        controller.activeSpec?.page,
        controller.targets.bounds.keys.toSet()
    ) {
        if (visible) controller.onTargetsChanged()
    }

    val active = controller.activeSpec
    if (visible && active != null) {
        GuideOverlay(
            spec = active,
            stepIndex = controller.stepIndex,
            targets = controller.targets,
            onNext = controller::next,
            onSkip = controller::finish
        )
    }
}
