# 首次使用分页引导 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 五个主 tab（今日 / 计划 / 专注 / 统计 / 我的）首次进入时各展示一次 2–3 步高亮引导，完成后不再自动出现，并可从「我的」重置重看。

**Architecture:** 在 `YanZhongAppRoot` 根层放一个覆盖导航栏与中央 FAB 的引导层；每个页面用 `ProvideGuideSpec` 声明步骤、用 `GuideAnchor` 登记首屏锚点。引导已看状态存在本机 settings DataStore 的字符串集合里，不进 `AppSettings`、不进云同步。

**Tech Stack:** Kotlin、Jetpack Compose（BOM 2024.09.03 / Compose 1.7.3）、Material3、Navigation Compose、DataStore Preferences、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-11-11-first-run-page-guide-design.md`

## Global Constraints

- 不新增依赖，不改服务端接口，不做云同步字段。
- 已看状态只存本机 DataStore，按设备记，退出登录不清理。
- 所有文案以规格 §5 为准，逐字复用，不自行改写。
- 只高亮当前页首屏元素，不自动切 tab、不自动滚动。
- 不升版本号、不 push、不部署。
- **不要自行 `git commit`**：每个任务验证通过后向用户汇报，等用户确认后再提交（项目约束：no commit/push/deploy without approval）。
- 构建必须用本机 PowerShell 方式（MSYS 下 `./gradlew` 不可用）：
  ```bash
  powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
  ```
- 单元测试使用 JUnit 4（`junit:junit:4.13.2` 已在 `app/build.gradle.kts`）。
- 不写 instrumentation 测试；UI 用模拟器手工验收。

---

### Task 1: 引导规格、纯规则与控制器状态机

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/guide/GuideSpec.kt`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/guide/GuideTargets.kt`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/guide/GuideController.kt`
- Test: `YanZhong/app/src/test/java/com/yanzhong/app/ui/guide/GuideSpecTest.kt`
- Test: `YanZhong/app/src/test/java/com/yanzhong/app/ui/guide/GuideEligibilityTest.kt`
- Test: `YanZhong/app/src/test/java/com/yanzhong/app/ui/guide/GuideStepNavigatorTest.kt`

**Interfaces:**
- Consumes: `com.yanzhong.app.ui.nav.Routes`（五个 tab 的路由常量）。
- Produces:
  - `GuidePage`（`key` / `route`，含 `fromRoute`）
  - `GuideStep(target, title, body)`、`GuidePageSpec(page, steps)`
  - `GuideAnchors` 常量与 `GuideAnchors.ALL`
  - `homeGuide(hasActivePlan: Boolean, tablet: Boolean)`、`planGuide(hasPlan: Boolean)`、`focusGuide()`、`statsGuide()`、`mineGuide()`
  - `GuideTargetRegistry`（`bounds: Map<String, Rect>`）、`LocalGuideTargets`
  - `GuideController(onFinished)`（自带 `targets`）：`declare` / `clearDeclared` / `start` / `next` / `onTargetsChanged` / `finish` / `resetDismissed`
  - 纯函数 `shouldStartGuide(route, deepPage, ready, declaredPage, seenPages, overlayVisible)`、`availableSteps(steps, targets)`、`nextAvailableIndex(steps, targets, current)`

- [ ] **Step 1: 先写三个失败测试**

`GuideSpecTest.kt`：

```kotlin
package com.yanzhong.app.ui.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideSpecTest {

    @Test
    fun `今日有生效计划时按倒计时、进度、导航三步`() {
        val spec = homeGuide(hasActivePlan = true, tablet = false)
        assertEquals(GuidePage.HOME, spec.page)
        assertEquals(
            listOf(
                GuideAnchors.HOME_COUNTDOWN,
                GuideAnchors.HOME_PROGRESS,
                GuideAnchors.NAV_BAR
            ),
            spec.steps.map { it.target }
        )
    }

    @Test
    fun `今日无计划时第二步换成计划引导卡`() {
        val spec = homeGuide(hasActivePlan = false, tablet = false)
        assertEquals(
            listOf(
                GuideAnchors.HOME_COUNTDOWN,
                GuideAnchors.HOME_PLAN_GUIDE,
                GuideAnchors.NAV_BAR
            ),
            spec.steps.map { it.target }
        )
    }

    @Test
    fun `平板导航文案指向左侧导航栏`() {
        val phone = homeGuide(hasActivePlan = true, tablet = false).steps.last().body
        val tablet = homeGuide(hasActivePlan = true, tablet = true).steps.last().body
        assertTrue(phone.contains("在底部"))
        assertTrue(tablet.contains("在左侧"))
    }

    @Test
    fun `计划页有计划和没计划各自两步且锚点正确`() {
        assertEquals(
            listOf(GuideAnchors.PLAN_OVERVIEW, GuideAnchors.PLAN_PRIMARY_ACTION),
            planGuide(hasPlan = true).steps.map { it.target }
        )
        assertEquals(
            listOf(GuideAnchors.PLAN_ACTION_CARD, GuideAnchors.PLAN_HISTORY_ACTION),
            planGuide(hasPlan = false).steps.map { it.target }
        )
    }

    @Test
    fun `专注统计我的步骤数与锚点正确`() {
        assertEquals(
            listOf(GuideAnchors.FOCUS_START, GuideAnchors.FOCUS_TASK_ROW, GuideAnchors.FOCUS_MODE),
            focusGuide().steps.map { it.target }
        )
        assertEquals(
            listOf(GuideAnchors.STATS_RANGE, GuideAnchors.STATS_KPI),
            statsGuide().steps.map { it.target }
        )
        assertEquals(
            listOf(
                GuideAnchors.MINE_HEADER,
                GuideAnchors.MINE_ACCOUNT_GROUP,
                GuideAnchors.MINE_GUIDE_ROW
            ),
            mineGuide().steps.map { it.target }
        )
    }

    @Test
    fun `所有锚点都来自约定集合且文案非空`() {
        val specs = listOf(
            homeGuide(true, false), homeGuide(false, true),
            planGuide(true), planGuide(false),
            focusGuide(), statsGuide(), mineGuide()
        )
        specs.flatMap { it.steps }.forEach {
            assertTrue("未知锚点 ${it.target}", it.target in GuideAnchors.ALL)
            assertTrue("步骤文案不能为空", it.title.isNotBlank() && it.body.isNotBlank())
        }
    }
}
```

`GuideEligibilityTest.kt`：

```kotlin
package com.yanzhong.app.ui.guide

import com.yanzhong.app.ui.nav.Routes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideEligibilityTest {

    @Test
    fun `主 tab 未看过且就绪时允许展示`() {
        assertTrue(
            shouldStartGuide(
                route = Routes.HOME,
                deepPage = false,
                ready = true,
                declaredPage = GuidePage.HOME,
                seenPages = emptySet(),
                overlayVisible = false
            )
        )
    }

    @Test
    fun `深层页不允许展示`() {
        assertFalse(
            shouldStartGuide(
                route = Routes.HOME,
                deepPage = true,
                ready = true,
                declaredPage = GuidePage.HOME,
                seenPages = emptySet(),
                overlayVisible = false
            )
        )
    }

    @Test
    fun `已看过的页面不再展示`() {
        assertFalse(
            shouldStartGuide(
                route = Routes.HOME,
                deepPage = false,
                ready = true,
                declaredPage = GuidePage.HOME,
                seenPages = setOf("home"),
                overlayVisible = false
            )
        )
    }

    @Test
    fun `专注计时中页面未就绪不展示`() {
        assertFalse(
            shouldStartGuide(
                route = Routes.FOCUS,
                deepPage = false,
                ready = false,
                declaredPage = GuidePage.FOCUS,
                seenPages = emptySet(),
                overlayVisible = false
            )
        )
    }

    @Test
    fun `引导已可见时不重复开始`() {
        assertFalse(
            shouldStartGuide(
                route = Routes.PLAN,
                deepPage = false,
                ready = true,
                declaredPage = GuidePage.PLAN,
                seenPages = emptySet(),
                overlayVisible = true
            )
        )
    }

    @Test
    fun `声明页与当前路由不一致不展示`() {
        assertFalse(
            shouldStartGuide(
                route = Routes.PLAN,
                deepPage = false,
                ready = true,
                declaredPage = GuidePage.HOME,
                seenPages = emptySet(),
                overlayVisible = false
            )
        )
    }

    @Test
    fun `没有声明页时不展示`() {
        assertFalse(
            shouldStartGuide(
                route = "plan_interview?mode=&prefill=",
                deepPage = false,
                ready = true,
                declaredPage = null,
                seenPages = emptySet(),
                overlayVisible = false
            )
        )
    }
}
```

`GuideStepNavigatorTest.kt`：

```kotlin
package com.yanzhong.app.ui.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideStepNavigatorTest {

    private fun step(target: String) = GuideStep(target, "t-$target", "b-$target")

    @Test
    fun `缺失锚点的步骤被过滤`() {
        val steps = listOf(step("a"), step("b"), step("c"))
        assertEquals(listOf("a", "c"), availableSteps(steps, setOf("a", "c")).map { it.target })
    }

    @Test
    fun `没有锚点时没有可展示步骤`() {
        assertTrue(availableSteps(listOf(step("a")), emptySet()).isEmpty())
    }

    @Test
    fun `下一步跳到后面第一个有锚点的步骤`() {
        val steps = listOf(step("a"), step("b"), step("c"))
        assertEquals(2, nextAvailableIndex(steps, setOf("a", "c"), 0))
    }

    @Test
    fun `后面没有锚点步骤时返回空表示结束`() {
        val steps = listOf(step("a"), step("b"), step("c"))
        assertNull(nextAvailableIndex(steps, setOf("a"), 0))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest --tests=com.yanzhong.app.ui.guide.* '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```

Expected: 编译失败，`Unresolved reference: homeGuide / GuidePage / shouldStartGuide ...`。

- [ ] **Step 3: 实现 `GuideSpec.kt`**

```kotlin
package com.yanzhong.app.ui.guide

import com.yanzhong.app.ui.nav.Routes

enum class GuidePage(val key: String, val route: String) {
    HOME("home", Routes.HOME),
    PLAN("plan", Routes.PLAN),
    FOCUS("focus", Routes.FOCUS),
    STATS("stats", Routes.STATS),
    MINE("mine", Routes.MINE);

    companion object {
        fun fromRoute(route: String?): GuidePage? = entries.firstOrNull { it.route == route }
    }
}

data class GuideStep(val target: String, val title: String, val body: String)

data class GuidePageSpec(val page: GuidePage, val steps: List<GuideStep>)

object GuideAnchors {
    const val NAV_BAR = "nav_bar"
    const val HOME_COUNTDOWN = "home_countdown"
    const val HOME_PROGRESS = "home_progress"
    const val HOME_PLAN_GUIDE = "home_plan_guide"
    const val PLAN_ACTION_CARD = "plan_action_card"
    const val PLAN_HISTORY_ACTION = "plan_history_action"
    const val PLAN_OVERVIEW = "plan_overview"
    const val PLAN_PRIMARY_ACTION = "plan_primary_action"
    const val FOCUS_START = "focus_start"
    const val FOCUS_TASK_ROW = "focus_task_row"
    const val FOCUS_MODE = "focus_mode"
    const val STATS_RANGE = "stats_range"
    const val STATS_KPI = "stats_kpi"
    const val MINE_HEADER = "mine_header"
    const val MINE_ACCOUNT_GROUP = "mine_account_group"
    const val MINE_GUIDE_ROW = "mine_guide_row"

    val ALL: Set<String> = setOf(
        NAV_BAR, HOME_COUNTDOWN, HOME_PROGRESS, HOME_PLAN_GUIDE,
        PLAN_ACTION_CARD, PLAN_HISTORY_ACTION, PLAN_OVERVIEW, PLAN_PRIMARY_ACTION,
        FOCUS_START, FOCUS_TASK_ROW, FOCUS_MODE,
        STATS_RANGE, STATS_KPI,
        MINE_HEADER, MINE_ACCOUNT_GROUP, MINE_GUIDE_ROW
    )
}

fun homeGuide(hasActivePlan: Boolean, tablet: Boolean): GuidePageSpec = GuidePageSpec(
    page = GuidePage.HOME,
    steps = buildList {
        add(
            GuideStep(
                GuideAnchors.HOME_COUNTDOWN,
                "考试倒计时",
                "左右滑可以切换节点；点「添加节点」或「管理」记下模考、报名、复试这些日子。"
            )
        )
        if (hasActivePlan) {
            add(
                GuideStep(
                    GuideAnchors.HOME_PROGRESS,
                    "今日进度",
                    "今天的完成情况和本周目标都在这张卡里。下面的今日待办右滑完成、左滑顺延，点「开始」直接开专注。"
                )
            )
        } else {
            add(
                GuideStep(
                    GuideAnchors.HOME_PLAN_GUIDE,
                    "先有一份计划",
                    "还没有计划时，这里会带你去和 AI 排一份；有了计划，今日待办会按天送到这里。"
                )
            )
        }
        add(
            GuideStep(
                GuideAnchors.NAV_BAR,
                "主入口",
                if (tablet) {
                    "今日、计划、统计、我的在左侧；上面的专注按钮随时开钟。"
                } else {
                    "今日、计划、统计、我的在底部；中间这个按钮随时开专注。"
                }
            )
        )
    }
)

fun planGuide(hasPlan: Boolean): GuidePageSpec = GuidePageSpec(
    page = GuidePage.PLAN,
    steps = if (hasPlan) {
        listOf(
            GuideStep(
                GuideAnchors.PLAN_OVERVIEW,
                "全程计划",
                "当前阶段、总进度和每天的安排都从这张卡往下看。"
            ),
            GuideStep(
                GuideAnchors.PLAN_PRIMARY_ACTION,
                "改档案或调近期",
                "档案变了（科目、课表、目标）用这里重新生成；临时有事就用下面的「调整近期计划」，说一句就重排。"
            )
        )
    } else {
        listOf(
            GuideStep(
                GuideAnchors.PLAN_ACTION_CARD,
                "让 AI 排计划",
                "点「开始制定」，和 AI 聊几分钟，它会按你的档案排一份全程计划。"
            ),
            GuideStep(
                GuideAnchors.PLAN_HISTORY_ACTION,
                "历史版本",
                "右上角可以回看以前生成的计划版本。"
            )
        )
    }
)

fun focusGuide(): GuidePageSpec = GuidePageSpec(
    page = GuidePage.FOCUS,
    steps = listOf(
        GuideStep(
            GuideAnchors.FOCUS_START,
            "直接开始",
            "点这里开一次自由专注，不用先选任务。"
        ),
        GuideStep(
            GuideAnchors.FOCUS_TASK_ROW,
            "记到任务上",
            "想把这次专注记到某个任务上，先「选择任务」，番茄数会记进那个任务。"
        ),
        GuideStep(
            GuideAnchors.FOCUS_MODE,
            "三种计时",
            "番茄钟、正计时、倒计时；番茄方案和更多设置在下面。"
        )
    )
)

fun statsGuide(): GuidePageSpec = GuidePageSpec(
    page = GuidePage.STATS,
    steps = listOf(
        GuideStep(
            GuideAnchors.STATS_RANGE,
            "先选范围",
            "今日、本周、本月、累计，下面的数字跟着变。"
        ),
        GuideStep(
            GuideAnchors.STATS_KPI,
            "四个关键数",
            "净专注、番茄数、任务完成率、连续打卡都在这；往下还有科目占比和长期趋势。"
        )
    )
)

fun mineGuide(): GuidePageSpec = GuidePageSpec(
    page = GuidePage.MINE,
    steps = listOf(
        GuideStep(
            GuideAnchors.MINE_HEADER,
            "个人资料",
            "点头像和昵称可以改个人资料；账号和云端数据在下面这一组。"
        ),
        GuideStep(
            GuideAnchors.MINE_ACCOUNT_GROUP,
            "账号与档案",
            "备考档案变了（科目、课表、目标）从这里改，之后可以重新生成计划。"
        ),
        GuideStep(
            GuideAnchors.MINE_GUIDE_ROW,
            "随时重看",
            "以后想再看这套引导，点这里重置。"
        )
    )
)
```

- [ ] **Step 4: 实现 `GuideTargets.kt`**

```kotlin
package com.yanzhong.app.ui.guide

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

class GuideTargetRegistry {
    internal val bounds = mutableStateMapOf<String, Rect>()

    internal fun update(id: String, rect: Rect) {
        bounds[id] = rect
    }

    internal fun remove(id: String) {
        bounds.remove(id)
    }
}

val LocalGuideTargets = staticCompositionLocalOf<GuideTargetRegistry?> { null }
```

- [ ] **Step 5: 实现 `GuideController.kt`**

```kotlin
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
```

- [ ] **Step 6: 运行测试，确认通过**

```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest --tests=com.yanzhong.app.ui.guide.* '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```

Expected: `BUILD SUCCESSFUL`，三个测试类全部通过。

- [ ] **Step 7: 汇报并等待确认提交**（不要自行 commit）

---

### Task 2: 本机已看状态存储 + 删除旧「欢迎来到研钟」弹窗

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/prefs/SettingsStore.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/home/HomeViewModel.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/home/HomeScreen.kt`

**Interfaces:**
- Consumes: 现有 `context.settingsStore` DataStore、`Keys` 对象。
- Produces:
  - `SettingsRepository.guideSeenPages: Flow<Set<String>>`
  - `SettingsRepository.markGuideSeen(page: String)`
  - `SettingsRepository.resetGuides()`
  - 删除 `AppSettings.onboardingDone`、`Keys.ONBOARDING`、`setOnboardingDone()`、`HomeUiState.onboardingDone`、`dismissOnboarding()`

- [ ] **Step 1: `SettingsStore.kt` 增加本地已看集合**

在 import 区加：

```kotlin
import androidx.datastore.preferences.core.stringSetPreferencesKey
```

在 `Keys` 里删除：

```kotlin
val ONBOARDING = booleanPreferencesKey("onboarding_done")
```

改为新增：

```kotlin
val GUIDE_SEEN = stringSetPreferencesKey("guide_seen_pages")
```

从 `AppSettings` 删除：

```kotlin
val onboardingDone: Boolean = false,
```

从 `settings` 的 `map` 块删除：

```kotlin
onboardingDone = p[Keys.ONBOARDING] ?: false,
```

删除：

```kotlin
suspend fun setOnboardingDone() = context.settingsStore.edit { it[Keys.ONBOARDING] = true }
```

在 `relayGuideShown` 同区域（仅设备本地、不进云同步）追加：

```kotlin
    // ---- 首次分页引导:仅设备本地,不进 AppSettings/云同步 ----

    val guideSeenPages: Flow<Set<String>> = context.settingsStore.data
        .catch { e ->
            if (e is java.io.IOException) emit(emptyPreferences()) else throw e
        }
        .map { it[Keys.GUIDE_SEEN] ?: emptySet() }

    suspend fun markGuideSeen(page: String) = context.settingsStore.edit { prefs ->
        prefs[Keys.GUIDE_SEEN] = (prefs[Keys.GUIDE_SEEN] ?: emptySet()) + page
    }

    suspend fun resetGuides() = context.settingsStore.edit { it[Keys.GUIDE_SEEN] = emptySet() }
```

- [ ] **Step 2: `HomeViewModel.kt` 删除旧弹窗状态，新增 `loaded`**

`HomeUiState` 删除：

```kotlin
val onboardingDone: Boolean = true,
```

在 `HomeUiState` 末尾（`monthDoneTasks` 之后）加：

```kotlin
    /** 首帧真实数据(不是 stateIn 初始值)已产出;首次引导据此判断页面就绪 */
    val loaded: Boolean = false
```

`SettingsSlice` 删除：

```kotlin
val onboardingDone: Boolean = true,
```

设置流映射删除：

```kotlin
onboardingDone = it.onboardingDone,
```

`HomeUiState(...)` 构造删除：

```kotlin
onboardingDone = settings.onboardingDone,
```

并在构造末尾加：

```kotlin
loaded = true
```

删除函数：

```kotlin
    fun dismissOnboarding() {
        viewModelScope.launch { settingsRepo.setOnboardingDone() }
    }
```

- [ ] **Step 3: `HomeScreen.kt` 删除旧弹窗**

删除状态：

```kotlin
var showOnboarding by remember { mutableStateOf(false) }
```

删除：

```kotlin
    LaunchedEffect(state.onboardingDone) {
        showOnboarding = !state.onboardingDone
    }
```

删除弹窗调用块（整段 `if (showOnboarding) { OnboardingCard(...) }`）：

```kotlin
    if (showOnboarding) {
        OnboardingCard(
            presetLabel = state.nodes.getOrNull(state.pinnedIndex)
                ?.let { "${it.name} ${TimeUtils.formatYmd(it.targetAt)}" },
            onManage = {
                showOnboarding = false
                showNodeManager = true
                vm.dismissOnboarding()
            },
            onDismiss = {
                showOnboarding = false
                vm.dismissOnboarding()
            }
        )
    }
```

删除文件末尾的整个组件：

```kotlin
/** 首次启动引导:只讲一件事——添加倒计时节点(PRD 1.4 ③) */
@Composable
private fun OnboardingCard(presetLabel: String?, onManage: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("欢迎来到研钟") },
        text = {
            // 预置节点是按用户档案同步出来的,别再把日期写死——考期因人而异
            Text("30 秒开始:为你的考研路添加第一个倒计时节点——\n\n已为你预置「${presetLabel ?: "考研初试"}」,你可以直接使用,或添加更多节点(模考、报名、复试)。")
        },
        confirmButton = { TextButton(onClick = onManage) { Text("管理节点") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("开始使用") } }
    )
}
```

- [ ] **Step 4: 编译 + 跑现有单测**

```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```

Expected: `BUILD SUCCESSFUL`；全局搜索 `onboardingDone` 只剩 `data/remote/Api.kt` 的 `onboardingDoneAt`。

- [ ] **Step 5: 汇报并等待确认提交**

---

### Task 3: 锚点组件、根层引导 Host、遮罩与说明卡

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/guide/GuideTargets.kt`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/guide/GuideSession.kt`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/guide/GuideOverlay.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt`

**Interfaces:**
- Consumes: Task 1 的 `GuideController`、`GuidePageSpec`、`GuideAnchors`。
- Produces:
  - `GuideAnchor(id, modifier, expandTop, content)`
  - `LocalGuideController`
  - `ProvideGuideSpec(spec, ready)`
  - `GuideHost(controller, currentRoute, deepPage, seenPages)`
  - `navigateTab` 从 `private` 改为 `internal`

- [ ] **Step 1: 给 `GuideTargets.kt` 增加 `GuideAnchor`**

在文件末尾追加：

```kotlin
@Composable
fun GuideAnchor(
    id: String,
    modifier: Modifier = Modifier,
    expandTop: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val registry = LocalGuideTargets.current
    if (registry == null) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val extraTop = with(density) { expandTop.toPx() }
    Layout(
        content = content,
        modifier = modifier.onGloballyPositioned { coordinates ->
            val rect = coordinates.boundsInWindow()
            registry.update(
                id,
                if (extraTop > 0f) {
                    Rect(rect.left, rect.top - extraTop, rect.right, rect.bottom)
                } else {
                    rect
                }
            )
        }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
    DisposableEffect(registry, id) {
        onDispose { registry.remove(id) }
    }
}
```

补齐 import：

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
```

- [ ] **Step 2: 新建 `GuideSession.kt`**

```kotlin
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
```

- [ ] **Step 3: 新建 `GuideOverlay.kt`**

```kotlin
package com.yanzhong.app.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.TextAction
import kotlin.math.roundToInt

@Composable
internal fun GuideOverlay(
    spec: GuidePageSpec,
    stepIndex: Int,
    targets: GuideTargetRegistry,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    val step = spec.steps.getOrNull(stepIndex) ?: return
    val target = targets.bounds[step.target] ?: return
    val density = LocalDensity.current
    val safeTop = WindowInsets.safeDrawing.getTop(density)
    val safeBottom = WindowInsets.safeDrawing.getBottom(density)
    val margin = with(density) { 20.dp.toPx() }
    val gap = with(density) { 12.dp.toPx() }
    val inflate = with(density) { 8.dp.toPx() }
    val primary = MaterialTheme.colorScheme.primary

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    val cutLeftTarget = (target.left - inflate).coerceAtLeast(0f)
    val cutTopTarget = (target.top - inflate).coerceAtLeast(0f)
    val cutRightTarget = (target.right + inflate).coerceAtMost(containerSize.width.toFloat())
    val cutBottomTarget = (target.bottom + inflate).coerceAtMost(containerSize.height.toFloat())
    val cutLeft by animateFloatAsState(cutLeftTarget, tween(220), label = "guideCutLeft")
    val cutTop by animateFloatAsState(cutTopTarget, tween(220), label = "guideCutTop")
    val cutRight by animateFloatAsState(cutRightTarget, tween(220), label = "guideCutRight")
    val cutBottom by animateFloatAsState(cutBottomTarget, tween(220), label = "guideCutBottom")

    val dim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { dim.animateTo(1f, tween(180)) }

    BackHandler(onBack = onSkip)

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                containerSize = it.size
                containerOrigin = it.boundsInWindow().topLeft
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val dimColor = Color.Black.copy(alpha = 0.72f * dim.value)
            val left = cutLeft.coerceIn(0f, size.width)
            val top = cutTop.coerceIn(0f, size.height)
            val right = cutRight.coerceIn(0f, size.width)
            val bottom = cutBottom.coerceIn(0f, size.height)
            drawRect(dimColor, Offset.Zero, Size(size.width, top))
            drawRect(dimColor, Offset(0f, bottom), Size(size.width, size.height - bottom))
            drawRect(
                dimColor,
                Offset(0f, top),
                Size(left, (bottom - top).coerceAtLeast(0f))
            )
            drawRect(
                dimColor,
                Offset(right, top),
                Size((size.width - right).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f))
            )
            drawRoundRect(
                color = primary.copy(alpha = dim.value),
                topLeft = Offset(left, top),
                size = Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        val local = Rect(
            target.left - containerOrigin.x,
            target.top - containerOrigin.y,
            target.right - containerOrigin.x,
            target.bottom - containerOrigin.y
        )
        val screenW = containerSize.width
        val screenH = containerSize.height
        val cardW = cardSize.width
        val cardH = cardSize.height
        val position = if (cardW > 0 && cardH > 0 && screenW > 0 && screenH > 0) {
            val fullHeightTarget = local.height >= screenH * 0.8f
            if (fullHeightTarget) {
                val placeRight = screenW - local.right >= local.left
                val rawX = if (placeRight) local.right + gap else local.left - gap - cardW
                val rawY = local.center.y - cardH / 2f
                IntOffset(
                    rawX.coerceIn(margin, (screenW - margin - cardW).coerceAtLeast(margin)).roundToInt(),
                    rawY.coerceIn(
                        safeTop + margin,
                        (screenH - safeBottom - cardH - margin).coerceAtLeast(safeTop + margin)
                    ).roundToInt()
                )
            } else {
                val spaceBelow = screenH - safeBottom - local.bottom
                val spaceAbove = local.top - safeTop
                val placeBelow = spaceBelow >= cardH + gap || spaceBelow >= spaceAbove
                val rawX = local.center.x - cardW / 2f
                val rawY = if (placeBelow) local.bottom + gap else local.top - gap - cardH
                IntOffset(
                    rawX.coerceIn(margin, (screenW - margin - cardW).coerceAtLeast(margin)).roundToInt(),
                    rawY.coerceIn(
                        safeTop + margin,
                        (screenH - safeBottom - cardH - margin).coerceAtLeast(safeTop + margin)
                    ).roundToInt()
                )
            }
        } else {
            IntOffset.Zero
        }

        GuideCard(
            spec = spec,
            stepIndex = stepIndex,
            isLast = nextAvailableIndex(spec.steps, targets.bounds.keys, stepIndex) == null,
            onNext = onNext,
            onSkip = onSkip,
            modifier = Modifier
                .offset { position }
                .onSizeChanged { cardSize = it }
                .alpha(if (cardSize == IntSize.Zero) 0f else 1f)
        )
    }
}

@Composable
private fun GuideCard(
    spec: GuidePageSpec,
    stepIndex: Int,
    isLast: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.widthIn(max = 320.dp)
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Crossfade(targetState = stepIndex, animationSpec = tween(140), label = "guideStep") { index ->
                val step = spec.steps.getOrNull(index) ?: return@Crossfade
                Column {
                    Text(step.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        step.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "第 ${index + 1} / ${spec.steps.size} 步",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("跳过", onClick = onSkip)
                Spacer(Modifier.weight(1f))
                PrimaryButton(
                    text = if (isLast) "知道了" else "下一步",
                    onClick = onNext,
                    modifier = Modifier.widthIn(max = 160.dp)
                )
            }
        }
    }
}
```

- [ ] **Step 4: 接入 `AppNav.kt` 根层**

在 import 区补：

```kotlin
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yanzhong.app.ui.guide.GuideAnchor
import com.yanzhong.app.ui.guide.GuideAnchors
import com.yanzhong.app.ui.guide.GuideController
import com.yanzhong.app.ui.guide.GuideHost
import com.yanzhong.app.ui.guide.LocalGuideController
import com.yanzhong.app.ui.guide.LocalGuideTargets
import kotlinx.coroutines.launch
```

在 `YanZhongAppRoot` 的 `navController` 之后加：

```kotlin
    val seenPages by remember {
        app.settingsRepo.guideSeenPages
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    val guideController = remember {
        GuideController { page ->
            app.appScope.launch { app.settingsRepo.markGuideSeen(page.key) }
        }
    }
```

把现有的 `Box(Modifier.fillMaxSize()) { ... }` 整体包起来，并在 Box 的最上层（`RunningTimerPill` 之后）加 Host：

```kotlin
    CompositionLocalProvider(
        LocalGuideController provides guideController,
        LocalGuideTargets provides guideController.targets
    ) {
        Box(Modifier.fillMaxSize()) {
            // ...现有 Row / Scaffold / RunningTimerPill 不动...

            GuideHost(
                controller = guideController,
                currentRoute = currentRoute,
                deepPage = deepPage,
                seenPages = seenPages
            )
        }
    }
```

在 `YanZhongNavRail` 中把整个 `NavigationRail(...) { ... }` 包一层：

```kotlin
    GuideAnchor(GuideAnchors.NAV_BAR) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
            header = { FocusFab(navController, Modifier.padding(top = 8.dp)) }
        ) {
            // ...现有导航项不动...
        }
    }
```

在 `YanZhongBottomBar` 中把最外层 `Box { ... }` 包一层（`expandTop = 38.dp` 覆盖 FAB 凸出部分）：

```kotlin
    GuideAnchor(GuideAnchors.NAV_BAR, expandTop = 38.dp) {
        Box {
            // ...现有 Surface + FocusFab 不动...
        }
    }
```

把：

```kotlin
private fun navigateTab(navController: NavHostController, route: String) {
```

改为：

```kotlin
internal fun navigateTab(navController: NavHostController, route: String) {
```

- [ ] **Step 5: 编译**

```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:compileDebugKotlin '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```

Expected: `BUILD SUCCESSFUL`。此时各页还没声明 spec，运行 App 不应出现任何引导。

- [ ] **Step 6: 汇报并等待确认提交**

---

### Task 4: 今日页引导

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/home/HomeScreen.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/home/CountdownCard.kt`

**Interfaces:**
- Consumes: `ProvideGuideSpec`、`GuideAnchor`、`GuideAnchors`、`homeGuide`（Task 1/3）、`HomeUiState.loaded`（Task 2）。
- Produces: 今日页三步引导（有/无计划两种中间步）。

- [ ] **Step 1: `HomeScreen` 顶部声明 spec**

在 `val expanded = isExpanded()` 之后加：

```kotlin
    // 今日页首次引导:加载出首帧真实数据后再允许出现,避免有/无计划两种文案闪变
    ProvideGuideSpec(
        spec = homeGuide(hasActivePlan = state.hasActivePlan, tablet = isTablet()),
        ready = state.loaded
    )
```

补 import：

```kotlin
import com.yanzhong.app.ui.guide.GuideAnchor
import com.yanzhong.app.ui.guide.GuideAnchors
import com.yanzhong.app.ui.guide.ProvideGuideSpec
import com.yanzhong.app.ui.guide.homeGuide
import com.yanzhong.app.ui.theme.isTablet
```

- [ ] **Step 2: `PlanGuideCard` 加锚点**

把：

```kotlin
private fun PlanGuideCard(onOpenPlan: () -> Unit) {
    HeroCard(
        title = "还没有学习计划",
        desc = "和 AI 聊 5 分钟，按目标院校与每天可用时间排一份全程计划。",
        icon = AppIcons.Sparkles,
        colors = actionCardGradient(),
        onClick = onOpenPlan,
        actionLabel = "开始制定"
    )
}
```

改为：

```kotlin
private fun PlanGuideCard(onOpenPlan: () -> Unit) {
    GuideAnchor(GuideAnchors.HOME_PLAN_GUIDE) {
        HeroCard(
            title = "还没有学习计划",
            desc = "和 AI 聊 5 分钟，按目标院校与每天可用时间排一份全程计划。",
            icon = AppIcons.Sparkles,
            colors = actionCardGradient(),
            onClick = onOpenPlan,
            actionLabel = "开始制定"
        )
    }
}
```

- [ ] **Step 3: `TodayProgress` 加锚点**

把函数体里的 `AppCard {` 改为：

```kotlin
    GuideAnchor(GuideAnchors.HOME_PROGRESS) {
        AppCard {
```

并在 `AppCard { ... }` 的收尾 `}` 之后、函数收尾 `}` 之前补一个 `}` 关闭 `GuideAnchor`。原有的 `AppCard` 内容与参数全部不动。

- [ ] **Step 4: `CountdownCard` 两种状态都加锚点**

空态：

```kotlin
    if (nodes.isEmpty()) {
        GuideAnchor(GuideAnchors.HOME_COUNTDOWN) {
            AppCard {
                EmptyState(
                    icon = AppIcons.Timer,
                    title = "还没有倒计时节点",
                    hint = "添加一个节点，让每一天都有锚点",
                    compact = true,
                    action = { TextAction("添加节点", onClick = onManageNodes) }
                )
            }
        }
        return
    }
```

非空态把：

```kotlin
    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(...)
        ...
    }
```

整体包成：

```kotlin
    GuideAnchor(GuideAnchors.HOME_COUNTDOWN) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalPager(...)
            ...
        }
    }
```

补 import：

```kotlin
import com.yanzhong.app.ui.guide.GuideAnchor
import com.yanzhong.app.ui.guide.GuideAnchors
```

- [ ] **Step 5: 编译 + 单测**

```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 6: 模拟器手工冒烟**

安装 `assembleDebug` 产物（先清应用数据以模拟新用户），进入今日页：应出现「考试倒计时 → 今日进度/先有一份计划 → 主入口」三步；点「跳过」后重新进今日不再出现。最后一步高亮应覆盖底部导航与中间 FAB。

- [ ] **Step 7: 汇报并等待确认提交**

---

### Task 5: 计划页引导

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanScreen.kt`

**Interfaces:**
- Consumes: `ProvideGuideSpec`、`GuideAnchor`、`GuideAnchors`、`planGuide`、`PlanDisplayState`。
- Produces: 计划页有/无计划两套两步引导。

- [ ] **Step 1: 声明 spec 并把展示状态提成变量**

在 `todayDate` 之后加：

```kotlin
    val displayState = selectPlanDisplayState(
        loggedIn = loggedIn,
        plan = serverPlan,
        networkError = planError != null,
        loading = planLoading
    )
    // 只在「已就绪」或「确定没有计划」时引导;登录门/加载中/网络错误不弹
    ProvideGuideSpec(
        spec = planGuide(hasPlan = displayState == PlanDisplayState.READY),
        ready = displayState == PlanDisplayState.READY || displayState == PlanDisplayState.NO_PLAN
    )
```

把：

```kotlin
when (selectPlanDisplayState(loggedIn, serverPlan, planError != null, planLoading)) {
```

改为：

```kotlin
when (displayState) {
```

补 import：

```kotlin
import com.yanzhong.app.ui.guide.GuideAnchor
import com.yanzhong.app.ui.guide.GuideAnchors
import com.yanzhong.app.ui.guide.ProvideGuideSpec
import com.yanzhong.app.ui.guide.planGuide
```

- [ ] **Step 2: 历史计划按钮加锚点**

把：

```kotlin
                            IconButton(onClick = { navController.navigate(Routes.PLAN_HISTORY) }) {
                                Icon(AppIcons.ClipboardList, contentDescription = "历史计划")
                            }
```

改为：

```kotlin
                            GuideAnchor(GuideAnchors.PLAN_HISTORY_ACTION) {
                                IconButton(onClick = { navController.navigate(Routes.PLAN_HISTORY) }) {
                                    Icon(AppIcons.ClipboardList, contentDescription = "历史计划")
                                }
                            }
```

- [ ] **Step 3: `PlanActionCard` 加锚点**

把 `PlanActionCard` 内的：

```kotlin
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
```

改为：

```kotlin
    GuideAnchor(GuideAnchors.PLAN_ACTION_CARD) {
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
```

并在函数收尾 `}` 之前补一个 `}` 关闭 `GuideAnchor`。该卡在登录/无计划/网络错误三种状态共用，但 `ready` 只在无计划时放行，不会误弹。

- [ ] **Step 4: 概览卡加锚点**

在 `ServerPlanSummary` 里把：

```kotlin
        // 概览卡:当前阶段与第几天、全程进度、下一阶段
        AppCard {
```

改为：

```kotlin
        // 概览卡:当前阶段与第几天、全程进度、下一阶段
        GuideAnchor(GuideAnchors.PLAN_OVERVIEW) {
            AppCard {
```

并在该 `AppCard` 的收尾 `}` 之后补一个 `}` 关闭 `GuideAnchor`。

- [ ] **Step 5: 主按钮加锚点**

把：

```kotlin
        PrimaryButton(
            text = if (plan.stale) "重新生成计划" else "调整档案并重新生成",
            onClick = onOpenSetup,
            enabled = !busy,
            icon = AppIcons.Sparkles
        )
```

改为：

```kotlin
        GuideAnchor(GuideAnchors.PLAN_PRIMARY_ACTION) {
            PrimaryButton(
                text = if (plan.stale) "重新生成计划" else "调整档案并重新生成",
                onClick = onOpenSetup,
                enabled = !busy,
                icon = AppIcons.Sparkles
            )
        }
```

- [ ] **Step 6: 编译**

```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:compileDebugKotlin '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 7: 模拟器手工冒烟**

分别用「有生效计划」和「无生效计划」两种账号进入计划页：前者高亮概览卡 → 主按钮；后者高亮空态卡 → 右上角历史按钮。

- [ ] **Step 8: 汇报并等待确认提交**

---

### Task 6: 专注页引导

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt`

**Interfaces:**
- Consumes: `ProvideGuideSpec`、`GuideAnchor`、`GuideAnchors`、`focusGuide`、`Phase.IDLE`。
- Produces: 空闲态三步引导。

- [ ] **Step 1: 声明 spec**

在 `FocusScreen` 的 `val timer = state.timer` 之后加：

```kotlin
    // 只在空闲态引导;计时中或休息中不打断
    ProvideGuideSpec(focusGuide(), ready = timer.phase == Phase.IDLE)
```

补 import：

```kotlin
import com.yanzhong.app.ui.guide.GuideAnchor
import com.yanzhong.app.ui.guide.GuideAnchors
import com.yanzhong.app.ui.guide.ProvideGuideSpec
import com.yanzhong.app.ui.guide.focusGuide
```

- [ ] **Step 2: 主开始按钮加锚点**

把 `IdleContent` 内：

```kotlin
            PrimaryButton(
                text = startLabel,
                onClick = { startByMode(null) },
                modifier = Modifier.widthIn(max = 320.dp)
            )
```

改为：

```kotlin
            GuideAnchor(GuideAnchors.FOCUS_START) {
                PrimaryButton(
                    text = startLabel,
                    onClick = { startByMode(null) },
                    modifier = Modifier.widthIn(max = 320.dp)
                )
            }
```

- [ ] **Step 3: 选择任务行加锚点**

把：

```kotlin
            RowGroup {
                ListRow(
                    title = "选择任务",
                    icon = AppIcons.ClipboardList,
                    subtitle = if (hasTasks) "从今日待办中选一个" else "今日没有待办",
                    onClick = if (hasTasks) ({ showTaskSheet = true }) else null,
                    showChevron = hasTasks,
                    enabled = hasTasks
                )
            }
```

整体包成：

```kotlin
            GuideAnchor(GuideAnchors.FOCUS_TASK_ROW) {
                RowGroup {
                    ListRow(
                        title = "选择任务",
                        icon = AppIcons.ClipboardList,
                        subtitle = if (hasTasks) "从今日待办中选一个" else "今日没有待办",
                        onClick = if (hasTasks) ({ showTaskSheet = true }) else null,
                        showChevron = hasTasks,
                        enabled = hasTasks
                    )
                }
            }
```

- [ ] **Step 4: 模式切换加锚点**

把：

```kotlin
            SegmentedChips(
                options = listOf("番茄钟", "正计时", "倒计时"),
                selectedIndex = mode.ordinal,
                onSelect = { index ->
                    mode = when (index) {
                        0 -> TimerMode.POMODORO
                        1 -> TimerMode.STOPWATCH
                        else -> TimerMode.COUNTDOWN
                    }
                }
            )
```

整体包成：

```kotlin
            GuideAnchor(GuideAnchors.FOCUS_MODE) {
                SegmentedChips(
                    options = listOf("番茄钟", "正计时", "倒计时"),
                    selectedIndex = mode.ordinal,
                    onSelect = { index ->
                        mode = when (index) {
                            0 -> TimerMode.POMODORO
                            1 -> TimerMode.STOPWATCH
                            else -> TimerMode.COUNTDOWN
                        }
                    }
                )
            }
```

- [ ] **Step 5: 编译**

```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:compileDebugKotlin '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 6: 模拟器手工冒烟**

计时进行中从底部 FAB 进专注页：不出现引导；结束计时后再进：出现三步。三处高亮分别落在主按钮、选择任务行、模式切换。

- [ ] **Step 7: 汇报并等待确认提交**

---

### Task 7: 统计页引导

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/stats/StatsScreen.kt`

**Interfaces:**
- Consumes: `ProvideGuideSpec`、`GuideAnchor`、`GuideAnchors`、`statsGuide`。
- Produces: 统计页两步引导。

- [ ] **Step 1: 声明 spec**

在 `StatsScreen` 的 `LaunchedEffect(Unit) { vm.refreshServer() }` 之后加：

```kotlin
    ProvideGuideSpec(statsGuide(), ready = true)
```

补 import：

```kotlin
import com.yanzhong.app.ui.guide.GuideAnchor
import com.yanzhong.app.ui.guide.GuideAnchors
import com.yanzhong.app.ui.guide.ProvideGuideSpec
import com.yanzhong.app.ui.guide.statsGuide
```

- [ ] **Step 2: 时间范围切换加锚点**

把 `item(key = "header")` 里的：

```kotlin
                    SegmentedChips(
                        options = StatsRange.entries.map { it.label },
                        selectedIndex = StatsRange.entries.indexOf(state.range),
                        onSelect = { vm.selectRange(StatsRange.entries[it]) }
                    )
```

包成：

```kotlin
                    GuideAnchor(GuideAnchors.STATS_RANGE) {
                        SegmentedChips(
                            options = StatsRange.entries.map { it.label },
                            selectedIndex = StatsRange.entries.indexOf(state.range),
                            onSelect = { vm.selectRange(StatsRange.entries[it]) }
                        )
                    }
```

- [ ] **Step 3: KPI 横幅加锚点**

把：

```kotlin
            item(key = "kpi") { KpiBanner(state) }
```

改为：

```kotlin
            item(key = "kpi") {
                GuideAnchor(GuideAnchors.STATS_KPI) {
                    KpiBanner(state)
                }
            }
```

- [ ] **Step 4: 编译 + 单测**

```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 汇报并等待确认提交**

---

### Task 8: 我的页引导 + 重看入口

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt`

**Interfaces:**
- Consumes: `ProvideGuideSpec`、`GuideAnchor`、`GuideAnchors`、`mineGuide`、`LocalGuideController`、`SettingsRepository.resetGuides()`、`navigateTab`（Task 3 已提升为 internal）。
- Produces: 我的页三步引导；「账号与档案」分组中的「新手引导」行与二次确认重置。

- [ ] **Step 1: 顶部声明 spec 并取到 controller**

在 `val context = LocalContext.current` 之后加：

```kotlin
    val app = remember { context.applicationContext as YanZhongApp }
    val guideController = LocalGuideController.current
    var showGuideReset by remember { mutableStateOf(false) }

    ProvideGuideSpec(
        spec = mineGuide(),
        ready = state.account != AccountState.Loading
    )
```

补 import：

```kotlin
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.ui.guide.GuideAnchor
import com.yanzhong.app.ui.guide.GuideAnchors
import com.yanzhong.app.ui.guide.LocalGuideController
import com.yanzhong.app.ui.guide.ProvideGuideSpec
import com.yanzhong.app.ui.guide.mineGuide
import com.yanzhong.app.ui.nav.navigateTab
```

- [ ] **Step 2: 头部个人卡加锚点**

把 `item(key = "header")` 里的：

```kotlin
                HeaderCard(
                    state,
                    onOpenAccount = {
                        val target = if (state.account is AccountState.LoggedIn) Routes.EDIT_PROFILE else Routes.ACCOUNT
                        navController.navigate(target) { launchSingleTop = true }
                    }
                )
```

包成：

```kotlin
                GuideAnchor(GuideAnchors.MINE_HEADER) {
                    HeaderCard(
                        state,
                        onOpenAccount = {
                            val target = if (state.account is AccountState.LoggedIn) Routes.EDIT_PROFILE else Routes.ACCOUNT
                            navController.navigate(target) { launchSingleTop = true }
                        }
                    )
                }
```

- [ ] **Step 3: 账号与档案分组加锚点并插入「新手引导」行**

把：

```kotlin
                    RowGroup {
                        ListRow(
                            title = accountTitle,
                            ...
                        )
                        RowDivider()
                        ListRow(
                            title = "备考档案",
                            ...
                        )
                    }
```

改为（保留原有两行内容原样）：

```kotlin
                    GuideAnchor(GuideAnchors.MINE_ACCOUNT_GROUP) {
                        RowGroup {
                            ListRow(
                                title = accountTitle,
                                subtitle = accountSubtitle,
                                icon = AppIcons.CloudUpload,
                                onClick = {
                                    navController.navigate(Routes.ACCOUNT) { launchSingleTop = true }
                                }
                            )
                            RowDivider()
                            ListRow(
                                title = "备考档案",
                                subtitle = "考试科目、目标院校、课表",
                                icon = AppIcons.Target,
                                onClick = {
                                    navController.navigate(Routes.planSetup()) { launchSingleTop = true }
                                }
                            )
                            RowDivider()
                            GuideAnchor(GuideAnchors.MINE_GUIDE_ROW) {
                                ListRow(
                                    title = "新手引导",
                                    subtitle = "重看各页的功能说明",
                                    icon = AppIcons.Info,
                                    onClick = { showGuideReset = true }
                                )
                            }
                        }
                    }
```

- [ ] **Step 4: 加重置确认弹窗**

在 `MineScreen` 的 `Box { LazyColumn... }` 之后、函数结束之前加：

```kotlin
    if (showGuideReset) {
        AlertDialog(
            onDismissRequest = { showGuideReset = false },
            title = { Text("重看新手引导？") },
            text = { Text("各页的功能说明会重新出现一遍。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGuideReset = false
                        navigateTab(navController, Routes.HOME)
                        guideController?.resetDismissed()
                        updateScope.launch { app.settingsRepo.resetGuides() }
                    }
                ) { Text("重看") }
            },
            dismissButton = {
                TextButton(onClick = { showGuideReset = false }) { Text("取消") }
            }
        )
    }
```

- [ ] **Step 5: 编译 + 单测**

```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 6: 模拟器手工冒烟**

进入我的页：三步引导出现；点最后一步「知道了」后，「新手引导」行点击 → 确认「重看」→ 跳回今日并立刻出现今日引导；再依次访问其它四页会各出现一次。

- [ ] **Step 7: 汇报并等待确认提交**

---

### Task 9: 全量验证

**Files:** 无新增，只验证。

- [ ] **Step 1: 运行全量单测与打包**

```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```

Expected: `BUILD SUCCESSFUL`，`app/build/outputs/apk/debug/` 下有 APK。

- [ ] **Step 2: 按规格 §7.2 清单逐项验收**

1. 全新安装并完成问卷后落到计划页：计划页引导只出现一次；完成 / 跳过 / 返回三种结束方式都能记录已看。
2. 逐页首次进入今日、专注、统计、我的：各自出现一次；之后切换 tab 不再出现。
3. 今日在有计划和没有计划两种状态下，第二步锚点不同且文案正确。
4. 专注计时进行中进入专注页：不出现引导；计时结束后进入才出现。
5. 「我的 → 新手引导」确认重看：跳回今日并出现今日引导；再访问其他页会重新出现。
6. 老用户升级场景（保留 DataStore、覆盖安装）：各页首次进入同样出现一次。
7. 深色模式、平板横屏（左侧导航栏）、小屏手机：遮罩覆盖完整，说明卡不越界，导航栏高亮位置正确。
8. 引导期间点击页面和 tab 无响应，系统返回可以跳过。

- [ ] **Step 3: 汇报验收结果并等待确认提交**

列出每项验收的实际观察结果；未通过项记录现象与复现步骤，不自行 commit / push / 部署。

---

## 自查记录

- **规格覆盖**：五个 tab 各自引导（Task 4–8）、本机已看状态（Task 2）、重看入口（Task 8）、旧弹窗删除（Task 2）、触发/结束语义（Task 1 + 3）、锚点与文案（Task 1 + 4–8）、测试（Task 1 + 9）均有对应任务。
- **占位符检查**：新文件均给出完整代码；既有文件给出可定位的前后片段，无 TBD/TODO。
- **类型一致性**：`GuidePage.key/route`、`GuideStep.target`、`GuideAnchors.*`、`GuideController.declare/start/next/finish` 在各任务间名称一致；`navigateTab` 在 Task 3 提升为 `internal`，Task 8 才使用。
