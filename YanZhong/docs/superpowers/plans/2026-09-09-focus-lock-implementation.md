# 专注模式应用内锁定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将专注页改造成应用内严格专注模式，隐藏应用导航、拦截返回、控制屏幕常亮，并通过长按和确认弹窗退出，同时保持现有番茄计时引擎不变。

**Architecture:** `PomodoroEngine` 继续作为计时状态唯一来源；`YanZhongAppRoot` 根据 `TimerState.isRunning` 决定是否渲染底部导航；`FocusScreen` 负责返回拦截、窗口常亮生命周期和退出确认。系统级 Home、任务切换和其他应用拦截不在范围内。

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Navigation Compose, Android Activity Window flags, existing `PomodoroEngine` and `FocusViewModel`.

---

## 文件映射

- Modify: `app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt`，控制专注运行时的底部导航和中央入口。
- Modify: `app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt`，增加返回拦截、窗口常亮、放弃确认，并保持现有计时控件。
- Modify: `app/src/main/java/com/yanzhong/app/MainActivity.kt`，仅在需要时暴露稳定的窗口访问方式；优先保持无改动。
- Modify: `app/src/main/java/com/yanzhong/app/ui/focus/FocusViewModel.kt`，仅在退出确认调用需要时增加薄封装；不修改计时状态机。
- Test/verification: 使用现有 Gradle Kotlin 编译任务和静态搜索；若缺少本机缓存依赖，记录环境阻塞。

## Task 1: Add navigation lock behavior

**Files:**
- Modify: `app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt`

- [ ] **Step 1: Make the scaffold bottom bar conditional**

将 `Scaffold` 的底部栏从无条件渲染改为仅在 `!timerState.isRunning` 时渲染：

```kotlin
Scaffold(
    bottomBar = {
        if (!timerState.isRunning) {
            YanZhongBottomBar(navController, timerState)
        }
    }
) { padding ->
```

这样专注、暂停、短休息和长休息都保持应用内锁定，进入 `IDLE` 后自动恢复导航。

- [ ] **Step 2: Keep the route stable**

不要新增路由或修改 `NavHost` 的起始页；专注状态只影响底部栏可见性，避免改变现有返回栈和计时恢复逻辑。

- [ ] **Step 3: Verify navigation source**

Run: `Select-String -Path 'app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt' -Pattern 'if \(!timerState.isRunning\)'`
Expected: 命中条件渲染代码，且 `NavHost` 路由仍包含 `Routes.FOCUS`。

## Task 2: Add focus page back interception

**Files:**
- Modify: `app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt`

- [ ] **Step 1: Add Compose back handler import**

加入：

```kotlin
import androidx.activity.compose.BackHandler
```

- [ ] **Step 2: Consume back while timer is active**

在 `FocusScreen` 取得 `timer` 后加入：

```kotlin
if (timer.phase != Phase.IDLE) {
    BackHandler(enabled = true) {
        // 专注期间不允许通过系统返回误触退出，退出必须走页面长按入口。
    }
}
```

该处理只在 `IDLE` 之外启用，空闲态仍保留默认导航返回行为。

- [ ] **Step 3: Verify state boundary**

Run: `Select-String -Path 'app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt' -Pattern 'BackHandler|timer.phase != Phase.IDLE'`
Expected: 两个关键片段均存在，且未调用 `vm.abandon()`。

## Task 3: Add screen-keep-awake lifecycle handling

**Files:**
- Modify: `app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt`

- [ ] **Step 1: Add window and lifecycle imports**

加入：

```kotlin
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
```

- [ ] **Step 2: Pair flag add/remove with composition lifecycle**

在 `FocusScreen` 中加入：

```kotlin
val view = LocalView.current
DisposableEffect(timer.phase != Phase.IDLE) {
    val window = (view.context as? Activity)?.window
    if (timer.phase != Phase.IDLE) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    onDispose {
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
```

同时补充 `import android.app.Activity`。如当前 `LocalView` 上下文可能被包装，可在同一文件增加一个最小的 `Context.findActivity()` 辅助函数；优先先使用直接 Activity cast，避免不必要抽象。

- [ ] **Step 3: Verify lifecycle cleanup**

确认 `DisposableEffect` 的 key 使用 `timer.phase != Phase.IDLE`，并且 `onDispose` 无条件清除 flag，确保离开专注页后不会持续常亮。

## Task 4: Add abandon confirmation dialog

**Files:**
- Modify: `app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt`

- [ ] **Step 1: Change running content callback**

将：

```kotlin
RunningContent(vm, state)
```

改为：

```kotlin
RunningContent(vm, state)
```

并在 `RunningContent` 内部增加确认状态：

```kotlin
var showAbandonConfirm by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Route long-press completion to confirmation**

将：

```kotlin
AbandonHoldButton(holdingText = "长按 1.5 秒放弃", onAbandon = { vm.abandon() })
```

改为：

```kotlin
AbandonHoldButton(
    holdingText = "长按 1.5 秒放弃",
    onAbandon = { showAbandonConfirm = true }
)
```

- [ ] **Step 3: Render Material 3 confirmation dialog**

在 `RunningContent` 的 `Column` 外层或末尾加入：

```kotlin
if (showAbandonConfirm) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { showAbandonConfirm = false },
        title = { Text("放弃本次专注？") },
        text = { Text("已完成的专注记录会保留，当前计时将结束。") },
        confirmButton = {
            TextButton(
                onClick = {
                    showAbandonConfirm = false
                    vm.abandon()
                }
            ) { Text("确认放弃") }
        },
        dismissButton = {
            TextButton(onClick = { showAbandonConfirm = false }) { Text("继续专注") }
        }
    )
}
```

- [ ] **Step 4: Preserve cancel semantics**

确认弹窗取消、点击外部或系统返回时，只关闭弹窗，不调用 `vm.abandon()`，计时状态保持原样。

- [ ] **Step 5: Verify abandon call sites**

Run: `Select-String -Path 'app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt' -Pattern 'vm\.abandon\(\)'`
Expected: 仅确认按钮路径调用 `vm.abandon()`。

## Task 5: Compile and static verification

**Files:**
- No new files.

- [ ] **Step 1: Check imports and symbols**

Run: `Select-String -Path 'app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt' -Pattern 'BackHandler|DisposableEffect|WindowManager|AlertDialog'`
Expected: 所有新增符号都有对应 import 或完整限定名，无重复或未使用的旧逻辑。

- [ ] **Step 2: Compile Kotlin**

Run: `./gradlew.bat :app:compileDebugKotlin --no-daemon`
Expected: 编译成功；若再次因 Kotlin/Gradle 本地缓存缺失依赖失败，记录具体缺失依赖，不将环境错误归因于本次代码。

- [ ] **Step 3: Review behavior coverage**

逐项确认：运行态隐藏底部导航；系统返回不退出；运行态保持常亮；页面销毁清除常亮；长按后弹确认；取消确认不结束；确认后结束并恢复导航；后台和熄屏计时仍由现有引擎处理。

- [ ] **Step 4: Report verification status**

最终说明已修改文件、可验证行为，以及是否受到本机 Gradle/Kotlin 依赖缓存问题影响。
