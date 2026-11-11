package com.yanzhong.app.ui.guide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class GuideController(private val onFinished: (GuidePage) -> Unit) {
    val targets = GuideTargetRegistry()

    var declaredSpec by mutableStateOf<GuidePageSpec?>(null)
        private set
    var declaredReady by mutableStateOf(false)
        private set
    var activeSpec by mutableStateOf<GuidePageSpec?>(null)
        private set
    var stepIndex by mutableIntStateOf(0)
        private set
    var visible by mutableStateOf(false)
        private set
    var dismissedPages by mutableStateOf<Set<String>>(emptySet())
        private set

    fun declare(spec: GuidePageSpec?, ready: Boolean) {
        declaredSpec = spec
        declaredReady = ready
    }

    fun clearDeclared(page: GuidePage) {
        if (declaredSpec?.page == page) {
            declaredSpec = null
            declaredReady = false
        }
    }

    fun start(spec: GuidePageSpec): Boolean {
        if (visible) return false
        val first = availableSteps(spec.steps, targets.bounds.keys).firstOrNull() ?: return false
        activeSpec = spec
        stepIndex = spec.steps.indexOf(first)
        visible = true
        return true
    }

    fun next() {
        val spec = activeSpec ?: return
        val nextIndex = nextAvailableIndex(spec.steps, targets.bounds.keys, stepIndex)
        if (nextIndex == null) finish() else stepIndex = nextIndex
    }

    fun onTargetsChanged() {
        if (!visible) return
        val spec = activeSpec ?: return
        if (spec.steps.getOrNull(stepIndex)?.target in targets.bounds) return
        next()
    }

    fun finish() {
        val spec = activeSpec ?: return
        visible = false
        activeSpec = null
        stepIndex = 0
        dismissedPages = dismissedPages + spec.page.key
        onFinished(spec.page)
    }

    fun resetDismissed() {
        dismissedPages = emptySet()
    }
}

fun shouldStartGuide(
    route: String?,
    deepPage: Boolean,
    ready: Boolean,
    declaredPage: GuidePage?,
    seenPages: Set<String>,
    overlayVisible: Boolean
): Boolean {
    if (deepPage || !ready || overlayVisible) return false
    if (declaredPage == null || declaredPage.route != route) return false
    return declaredPage.key !in seenPages
}

fun availableSteps(steps: List<GuideStep>, targets: Set<String>): List<GuideStep> =
    steps.filter { it.target in targets }

fun nextAvailableIndex(steps: List<GuideStep>, targets: Set<String>, current: Int): Int? =
    (current + 1 until steps.size).firstOrNull { steps[it].target in targets }
