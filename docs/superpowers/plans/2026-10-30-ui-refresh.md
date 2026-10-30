# 研钟全量 UI 精修实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用一套令牌与一组共享组件替换全 App 分叉的页头、卡片、胶囊、按钮与空态，修复底部导航，把 5 个主页面与全部二级页迁移到同一套视觉规格，同时不改动任何数据、业务、路由与 ViewModel 行为。

**Architecture:** 先建立唯一事实来源——`Type.kt` 排版令牌、`Color.kt`/`Theme.kt` 颜色与表面令牌、`Components.kt` + 新建 `Controls.kt` 组件原语；再改导航；最后按「今日 → 专注 → 计划 → 统计 → 我的 → 二级页」逐页迁移。每一步结束时全 App 可编译、可运行，页面迁移不跨步混在一起。

**Tech Stack:** Kotlin 2.0、Jetpack Compose（BOM 2024.09.03）、Material3、navigation-compose、compose-icons-lucide；Gradle Kotlin DSL；minSdk 29 / target 34。无 Compose UI 测试基建，只有 JUnit4 单元测试。

**Spec:** `docs/superpowers/specs/2026-10-30-ui-refresh-design.md`

**工作目录（所有 Gradle 命令）:** `F:\kaoyan-app-prd\YanZhong`

**代码根目录:** `YanZhong/app/src/main/java/com/yanzhong/app`

---

## Global Constraints

- 只改 UI 层文件：`ui/theme/**`、`ui/nav/**`、`ui/home/**`、`ui/focus/**`、`ui/plan/**`、`ui/stats/**`、`ui/mine/**`、`ui/account/**`、`ui/auth/**`、`ui/onboarding/**`、`ui/supermode/**`、`ui/legal/**`、`MainActivity.kt`。`data/`、`service/`、`timer/`、`util/` 必须零 diff。
- 不新增依赖、页面、路由、数据库字段或同步协议；不改动 ViewModel 的公开方法与调用时机；不改动手势阈值、严格模式、紧急退出、计时状态机。
- minSdk 29：可用 `java.time.LocalDate` / `ChronoUnit`；禁用 30+ API，lint 的 `NewApi` 是 fatal。
- 每个 Task 结束时 `./gradlew :app:compileDebugKotlin` 必须通过；最后一个 Task 追加 `:app:testDebugUnitTest` 与 `:app:lintDebug`。
- 页面文件里不得新增 `shadowElevation`、`tonalElevation`、`headlineLarge`、`displayMedium`、`displaySmall`、`FilterChip`、emoji、`RoundedCornerShape(999.dp)`（胶囊只允许出现在组件层）。唯一例外：底栏 FAB 的 Material 默认阴影，以及深页悬浮计时胶囊 `RunningTimerPill` 现有的 6dp 阴影（悬浮层需要与页面区分，不改）。
- 数值、金额、倒计时统一使用 `tnum`；不再手写 `fontSize` / `fontWeight`，字重由 `Type.kt` 槽位决定。
- 可点击元素触达 ≥ 48×48dp；图标按钮必须有 `contentDescription`，装饰图标为 `null`；文本不设固定高度，系统字体放大 1.3× 不裁切（大数字样式除外）。
- 触感只在四个动作上触发：任务完成、开始专注、暂停/继续、结束专注；统一用 `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.TextHandleMove)`，不做全局震动。
- 开工前记录基线：`git rev-parse HEAD` 与 `git status --porcelain -- YanZhong/app/src/main/java/com/yanzhong/app` 各存一份（如 `/tmp/ui-refresh-base.sha`、`/tmp/ui-refresh-base.status`）。结束验收用基线比对，避免把开工前已有的改动算进本次成果。
- 空态标题 ≤ 12 字、说明 ≤ 24 字；正文不写实现口径，口径进 `InfoAction`。
- 每个 Task 一个独立提交，提交信息用 `feat(ui):` / `refactor(ui):` / `fix(ui):`。仅在用户批准本计划后执行提交。
- **测试说明：** 本项目没有 `androidTest` 与 Compose 测试依赖，本计划不新增测试框架（属于 spec 非目标）。UI 任务的验证 = 编译 + 现有单测不回归 + （可选）模拟器截图。不要为了“看起来像 TDD”而新增无意义的抽象或测试。

---

## 对 spec 的实现修正（已回写 spec）

1. **计划页不恢复本地示例计划。** 当前 `PlanScreen` 没有本地 468 天计划，也没有“查看示例计划”入口；spec §4.3 原条目已删除，实施时不得新增该功能。
2. **专注页“更多设置”只有两个开关。** `FocusViewModel` 只有 `toggleAutoChain` / `toggleContinuous`，静音/震动/音效在我的页；spec §4.2 已改为两个开关。
3. **我的页“关于”使用现有 `PolicyLinksRow`。** `ui/legal/PolicyLinks.kt` 已有协议链接组件，直接复用，不需要新后端或新路由。
4. **未生成计划时保留“今日待办”。** spec §4.1 写“任务区不显示”，但本地任务不依赖云端计划，隐藏会让无计划用户无法管理待办；实施为：阶段条、概览、节奏不显示，倒计时后插入 `HeroCard`，今日待办保留（空态即“今天还没有任务”）。
5. **统计页没有加载态。** `StatsUiState` 全字段有默认值（0/空列表），没有 `loading` 字段；不新增状态字段，因此统计页不使用 `SkeletonBlock`。Skeleton 只用于计划、规划文档、历史计划三处。
6. **Snackbar 保留在页面内。** 各页错误来自自己的 `UiState`，没有全局事件通道；把 `SnackbarHost` 提到根 `Scaffold` 需要改 ViewModel/事件流，超出“不改行为”范围。沿用页面内 `SnackbarHost`，文案按 spec §5 收敛为“操作失败，请重试”。

---

## 文件变更地图

**令牌与组件**

- Modify `YanZhong/app/src/main/java/com/yanzhong/app/ui/theme/Type.kt`
- Modify `YanZhong/app/src/main/java/com/yanzhong/app/ui/theme/Color.kt`
- Modify `YanZhong/app/src/main/java/com/yanzhong/app/ui/theme/Theme.kt`
- Rewrite `YanZhong/app/src/main/java/com/yanzhong/app/ui/theme/Components.kt`
- Create `YanZhong/app/src/main/java/com/yanzhong/app/ui/theme/Controls.kt`

**导航**

- Modify `YanZhong/app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt`

**主页面**

- Modify `ui/home/HomeScreen.kt`、`ui/home/CountdownCard.kt`、`ui/home/TaskListSection.kt`、`ui/home/ReviewCard.kt`、`ui/home/NodeManageSheet.kt`、`ui/home/TaskEditSheet.kt`
- Modify `ui/focus/FocusScreen.kt`
- Modify `ui/plan/PlanScreen.kt`
- Modify `ui/stats/StatsScreen.kt`、`ui/stats/Charts.kt`
- Modify `ui/mine/MineScreen.kt`

**二级页**

- Modify `ui/account/AccountScreen.kt`、`ui/supermode/SuperModeScreen.kt`、`ui/supermode/AppPickerScreen.kt`
- Modify `ui/plan/PlanSetupScreen.kt`、`ui/plan/PlanInterviewScreen.kt`、`ui/plan/PlanDocumentScreen.kt`、`ui/plan/PlanHistoryScreen.kt`、`ui/plan/AdjustmentCard.kt`
- Modify `ui/auth/LoginScreen.kt`、`ui/onboarding/OnboardingScreen.kt`
- Modify `MainActivity.kt`（仅启动过场）

**清扫**

- 删除 `ui/theme/SubjectArtwork.kt`（若迁移后无引用）；`ui/stats/WeeklyReport.kt` 保留（ViewModel 依赖）
- 删除 `Components.kt` 中的 `SectionCard`

---

## Task 1: 排版令牌

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/theme/Type.kt`

**Interfaces:**
- Produces: `AppTypography` 新规格；`MetricCompactStyle`（20/26 SemiBold tnum）；`CountdownNumberStyle`（40/44 Bold tnum）；`FocusTimerStyle`（48/52 Bold tnum）。

- [ ] **Step 1: 用下面的内容替换 `Type.kt` 中 `AppTypography` 与两个扩展样式**

保留文件头的 import 与 `private val base = Typography()`，替换其余内容：

```kotlin
val AppTypography = Typography(
    // ---- display：仅倒计时主数字 ----
    displayLarge = base.displayLarge.copy(
        fontSize = 40.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum"
    ),
    displayMedium = base.displayMedium.copy(fontSize = 34.sp),
    displaySmall = base.displaySmall.copy(fontSize = 30.sp),

    // ---- headline：页面标题 / KPI ----
    headlineLarge = base.headlineLarge.copy(
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold
    ),
    headlineMedium = base.headlineMedium.copy(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum"
    ),
    headlineSmall = base.headlineSmall.copy(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold
    ),

    // ---- title：卡片 / 列表 ----
    titleLarge = base.titleLarge.copy(
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = base.titleMedium.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleSmall = base.titleSmall.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium
    ),

    // ---- body：正文 ----
    bodyLarge = base.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = "tnum"
    ),
    bodyMedium = base.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = "tnum"
    ),
    bodySmall = base.bodySmall.copy(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = "tnum"
    ),

    // ---- label：按钮 / 标签 ----
    labelLarge = base.labelLarge.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum"
    ),
    labelMedium = base.labelMedium.copy(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    ),
    labelSmall = base.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum"
    )
)

/** 紧凑指标（我的页头部、纪录卡）：20/26，仍走 tnum */
val MetricCompactStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    fontFeatureSettings = "tnum"
)

/** 倒计时与专注主数字：40/44 Bold tnum */
val CountdownNumberStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    lineHeight = 44.sp,
    fontFeatureSettings = "tnum"
)

/** 专注运行态主数字：48/52 Bold tnum（全 App 唯一的大于 40sp 的数字） */
val FocusTimerStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 48.sp,
    lineHeight = 52.sp,
    fontFeatureSettings = "tnum"
)
```

- [ ] **Step 2: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。若失败，只允许调整 `Type.kt`，不要改页面。

- [ ] **Step 3: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/theme/Type.kt
git commit -m "refactor(ui): establish 7-role type scale with tabular numerals"
```

---

## Task 2: 颜色与表面令牌

**Files:**
- Modify: `ui/theme/Color.kt`
- Modify: `ui/theme/Theme.kt`
- Modify: `ui/theme/Components.kt`（仅 `StatBanner` 默认色，避免删除常量后编译失败）
- Modify: `MainActivity.kt`（仅 `AuthChecking` 的渐变）

**Interfaces:**
- Produces: `BrandGradient`、`CtaStart/CtaEnd/CtaStartDark/CtaEndDark`、`actionCardGradient()` 新返回值、`insetSurfaceColor()`。
- Consumes: 无。

- [ ] **Step 1: 在 `Color.kt` 增加品牌渐变与 CTA 色，删除废弃常量**

在 `BrandContainerDark` 之后加：

```kotlin
/** 品牌深靛 → 深紫：白字对比度 8.4:1 / 6.4:1，深浅模式共用 */
val BrandGradientStart = Color(0xFF4338CA)
val BrandGradientEnd = Color(0xFF6D28D9)
val BrandGradient = listOf(BrandGradientStart, BrandGradientEnd)

/** 珊瑚行动渐变：白字 ≥ 4.7:1；深色模式降一档但保持对比度 */
val CtaStart = Color(0xFFD9482E)
val CtaEnd = Color(0xFFB3321F)
val CtaStartDark = Color(0xFFB3321F)
val CtaEndDark = Color(0xFF8F2717)
```

删除这些常量：

- `CoralRedNight`、`CoralRedDeepNight`
- `NightSkyTop`、`NightSkyMid`、`NightSkyBottom`、`NightMountainFar`、`NightMountainNear`
- `StatBannerStart`、`StatBannerEnd`

- [ ] **Step 2: 更新 `Theme.kt`**

把 `actionCardGradient()` 的返回改为：

```kotlin
@Composable
fun actionCardGradient(): List<Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) listOf(CtaStartDark, CtaEndDark) else listOf(CtaStart, CtaEnd)
}
```

在 `actionCardGradient()` 之后新增：

```kotlin
/** 内嵌槽统一底色：图表底、次级块、输入框底色都从这里取 */
@Composable
fun insetSurfaceColor(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
```

- [ ] **Step 3: 更新 `Components.kt` 的 `StatBanner` 默认色**

把 `colors: List<Color> = listOf(StatBannerStart, StatBannerEnd)` 改为 `colors: List<Color> = BrandGradient`。其余不动。

- [ ] **Step 4: 更新 `MainActivity.kt` 的启动过场**

删除 `NightSkyTop` / `NightSkyBottom` 的 import，把 `AuthChecking()` 里的背景改为：

```kotlin
.background(Brush.linearGradient(BrandGradient))
```

并 import `com.yanzhong.app.ui.theme.BrandGradient`。图标与进度条保持白色。

- [ ] **Step 5: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。若 `NightSky*` 或 `StatBanner*` 仍被引用，按报错位置改为 `BrandGradient` 或 `insetSurfaceColor()`，但不要在本 Task 改页面布局。

- [ ] **Step 6: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/theme/Color.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/theme/Theme.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/theme/Components.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/MainActivity.kt
git commit -m "refactor(ui): replace ad-hoc gradients with brand/cta tokens"
```

---

## Task 3: 组件原语

**Files:**
- Rewrite: `ui/theme/Components.kt`
- Create: `ui/theme/Controls.kt`
- Modify call sites only for header argument names: `ui/home/HomeScreen.kt`、`ui/plan/PlanScreen.kt`、`ui/account/AccountScreen.kt`、`ui/supermode/SuperModeScreen.kt`、`ui/supermode/AppPickerScreen.kt`

**Interfaces:**
- Produces:
  - `PageHeader(title, meta, modifier, actions)`
  - `SubPageHeader(title, meta, onBack, modifier, actions)`
  - `AppCard(modifier, onClick, contentPadding, content)`
  - `GradientCard(modifier, colors, onClick, contentPadding, content)`
  - `RowGroup(modifier, content)`、`RowDivider(startIndent)`
  - `ListRow(title, modifier, icon, subtitle, onClick, showChevron, trailing, enabled)`
  - `SwitchRow(title, checked, onCheckedChange, modifier, subtitle, enabled, dimmed)`
  - `SectionHeader(title, modifier, meta, action)`
  - `MetricTile(value, label, modifier, unit, compact, tone)` + `MetricTone`
  - `ProgressLine(progress, modifier, color, height)`
  - `PillButton(text, onClick, modifier)`：32dp 行内胶囊按钮（仅组件层出现 999dp 圆角）
  - `SegmentedChips(options, selectedIndex, onSelect, modifier)`
  - `ToggleChip(text, selected, onClick, modifier)`：多选胶囊，与 SegmentedChips 同款视觉（仅组件层出现 999dp 圆角）
  - `PrimaryButton(text, onClick, modifier, enabled, loading, icon)`
  - `SecondaryButton(text, onClick, modifier, enabled, danger, icon)`
  - `TextAction(text, onClick, modifier, icon, danger, enabled)`
  - `CompletionCircle(checked, onClick, modifier, enabled)`
  - `InfoAction(title, message, tint)`
  - `OptionChip(text, onClick, modifier)`：面谈快捷答案用的描边胶囊（仅组件层）
  - `SkeletonBlock(modifier, height)`
  - `AppSheet(title, onDismiss, content)`
  - `EmptyState(icon, title, hint, modifier, compact, action)`
  - 保留并升级：`HeroCard(title, desc, icon, colors, onClick, modifier, actionLabel)`、`StatBanner(title, colors, action, content)`、`StatBannerCell(value, unit, label, modifier)`、`StatusPill(text, tone, modifier)` + `PillTone`
  - 新增 `GradientTag(text, modifier)`：渐变卡上的半透明白胶囊（仅组件层使用）
- Consumes: Task 1 排版、Task 2 颜色。

- [ ] **Step 1: 重写 `Components.kt`**

```kotlin
package com.yanzhong.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 页面大标题：22/Bold + 12sp 元信息 + 右侧操作 */
@Composable
fun PageHeader(
    title: String,
    meta: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (meta != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** 二级页头部：返回 + 标题 + 元信息 + 操作 */
@Composable
fun SubPageHeader(
    title: String,
    meta: String? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(AppIcons.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (meta != null) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** 全 App 唯一卡片：surface + 1dp 描边，零阴影 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = MaterialTheme.shapes.large
    val color = MaterialTheme.colorScheme.surface
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = color,
            border = border,
            modifier = modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(shape = shape, color = color, border = border, modifier = modifier.fillMaxWidth()) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** 渐变主卡：默认品牌渐变，白字；每屏最多一张 */
@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    colors: List<Color> = BrandGradient,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val base = Modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.large)
        .background(Brush.linearGradient(colors))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    Column(base.padding(contentPadding), content = content)
}

/** 设置类分组：一张卡内多行，行间用 RowDivider */
@Composable
fun RowGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(modifier = modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun RowDivider(startIndent: Dp = 16.dp) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = startIndent)
    )
}

/** 列表行：高 ≥56，图标 32 primary 12% 底，整行可点；enabled=false 时置灰且不可点 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    trailing: @Composable RowScope.() -> Unit = {},
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 开关行：dimmed 只降透明度，不禁用（静音时仍可预改震动/音效） */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    dimmed: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val alpha = if (dimmed) 0.5f else 1f
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** 卡片外的分组标题 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    action: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (meta != null) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = action)
    }
}

/** 行动类功能卡：只用于“制定计划 / 开始专注”级别的主 CTA */
@Composable
fun HeroCard(
    title: String,
    desc: String,
    icon: ImageVector,
    colors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(colors))
            .clickable(onClick = onClick)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.15f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 8.dp, y = 12.dp)
                .size(96.dp)
        )
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f)
            )
            if (actionLabel != null) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White
                ) {
                    Text(
                        actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}

/** 渐变数据横幅：统计 KPI、我的页头部可用 */
@Composable
fun StatBanner(
    title: String,
    colors: List<Color> = BrandGradient,
    action: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(colors))
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 24.dp, y = (-36).dp)
                .size(120.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
        )
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                action()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** 横幅内指标：委托 MetricTile，保持旧调用签名 */
@Composable
fun StatBannerCell(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier
) {
    MetricTile(
        value = value,
        label = label,
        unit = unit,
        modifier = modifier,
        tone = MetricTone.ON_GRADIENT
    )
}

/** 状态胶囊语义色档：调用方只传枚举，深浅自动成立 */
enum class PillTone { SOLID, ACCENT, NEUTRAL, WARNING, DANGER }

@Composable
fun StatusPill(text: String, tone: PillTone = PillTone.NEUTRAL, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val container = when (tone) {
        PillTone.SOLID -> colors.primary
        PillTone.ACCENT -> colors.primaryContainer
        PillTone.NEUTRAL -> colors.surfaceVariant
        PillTone.WARNING -> colors.tertiaryContainer
        PillTone.DANGER -> colors.errorContainer
    }
    val content = when (tone) {
        PillTone.SOLID -> colors.onPrimary
        PillTone.ACCENT -> colors.onPrimaryContainer
        PillTone.NEUTRAL -> colors.onSurfaceVariant
        PillTone.WARNING -> colors.onTertiaryContainer
        PillTone.DANGER -> colors.onErrorContainer
    }
    Surface(shape = RoundedCornerShape(999.dp), color = container, contentColor = content, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

/** 渐变卡上的半透明标签：白 18% 底 + 白字，只用于深色渐变表面 */
@Composable
fun GradientTag(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.White.copy(alpha = 0.18f),
        contentColor = Color.White,
        modifier = modifier
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

/** 空态：普通 72dp 图标 / 紧凑 56dp；动作只留一个 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = if (compact) 20.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(if (compact) 56.dp else 72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (compact) 24.dp else 32.dp)
            )
        }
        Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

/**
 * 旧分组卡：迁移期保留，逐步被 AppCard/RowGroup 取代；本 Task 先去掉阴影。
 * 所有调用点迁移完成后在 Task 15 删除。
 */
@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    val shape = MaterialTheme.shapes.large
    val header = @Composable {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                trailing()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            header()
        }
    } else {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            header()
        }
    }
}
```

- [ ] **Step 2: 新建 `Controls.kt`**

```kotlin
package com.yanzhong.app.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class MetricTone { DEFAULT, ON_GRADIENT, SUCCESS }

/** KPI 指标：24/30 SemiBold，紧凑变体 20/26 */
@Composable
fun MetricTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String = "",
    compact: Boolean = false,
    tone: MetricTone = MetricTone.DEFAULT
) {
    val valueColor = when (tone) {
        MetricTone.ON_GRADIENT -> Color.White
        MetricTone.SUCCESS -> SuccessGreen
        MetricTone.DEFAULT -> MaterialTheme.colorScheme.onSurface
    }
    val labelColor = if (tone == MetricTone.ON_GRADIENT) {
        Color.White.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = if (compact) MetricCompactStyle else MaterialTheme.typography.headlineMedium,
                color = valueColor,
                maxLines = 1
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = labelColor, maxLines = 1)
    }
}

/** 行内小胶囊按钮：32dp 高，primaryContainer 底；只用于任务行“开始” */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.height(32.dp)
    ) {
        Box(
            Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 进度线：圆角轨道 + 300ms 动画；调用方仍需给文字数值 */
@Composable
fun ProgressLine(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 6.dp
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "progress-line"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(color)
        )
    }
}

/** 单行分段选择：选中 primaryContainer，未选中描边；48dp 触达 */
@Composable
fun SegmentedChips(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val shape = RoundedCornerShape(999.dp)
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .clip(shape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                    .then(
                        if (selected) Modifier
                        else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 多选胶囊：与 SegmentedChips 同款视觉，可同时选中多个；48dp 触达 */
@Composable
fun ToggleChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .then(
                if (selected) Modifier
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current
            )
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.dp,
            if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        val color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger) color else LocalContentColor.current,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** 24dp 完成圆环 + 48dp 触达区；勾选态实心 primary */
@Composable
fun CompletionCircle(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String = if (checked) "标记为未完成" else "标记为完成"
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(
                    1.5.dp,
                    if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    AppIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/** 口径说明：标题 + 弹窗正文，长解释只放这里 */
@Composable
fun InfoAction(
    title: String,
    message: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
        Icon(
            AppIcons.Info,
            contentDescription = title,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title, style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("知道了") }
            }
        )
    }
}

/** 面谈快捷答案：描边胶囊；换行由调用方的 FlowRow 负责 */
@Composable
fun OptionChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** 骨架块：单一 alpha 脉冲，替代转圈与整卡脉冲 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeleton-alpha"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
    )
}

/** 统一底部弹层：20dp 顶圆角、拖拽条、最大 90% 高、imePadding */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
```

- [ ] **Step 3: 迁移页头调用点（只改参数名，不改布局）**

- `HomeScreen.kt` 两处 `PageHeader(title = "今日", subtitle = ...)` → `meta = ...`；把尾随 lambda 改为 `actions = { ... }`，其中原来的 `TextButton("＋ 添加任务")` 先保留原文，Task 6 再换成 `IconButton(AppIcons.Add)`。
- `PlanScreen.kt` 的 `PageHeader`：`subtitle` → `meta`，尾随 lambda → `actions = { ... }`。
- `AccountScreen.kt`、`SuperModeScreen.kt`、`AppPickerScreen.kt` 的 `SubPageHeader`：`subtitle` → `meta`，`trailing` → `actions`。

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。页面此时仍是旧布局，只是换了组件实现与参数名。

- [ ] **Step 5: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/theme/Components.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/theme/Controls.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/home/HomeScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/account/AccountScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/supermode/SuperModeScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/supermode/AppPickerScreen.kt
git commit -m "refactor(ui): add shared card, row, metric and control primitives"
```

---

## Task 4: 底部导航与专注 FAB

**Files:**
- Modify: `ui/nav/AppNav.kt`

**Interfaces:**
- Consumes: `BrandGradient`、`formatPillTime`（已有）、`timerRunning`（已有）。
- Produces: `showNav = !deepPage && !(currentRoute == Routes.FOCUS && timerRunning)`，供后续页面验收引用。

- [ ] **Step 1: 调整显隐规则**

在 `YanZhongAppRoot` 中：

```kotlin
val showNav = !deepPage && !(currentRoute == Routes.FOCUS && timerRunning)
```

把右下角迷你计时胶囊的显示条件从 `timerRunning && currentRoute != Routes.FOCUS` 改为：

```kotlin
if (timerRunning && deepPage) {
    RunningTimerPill(
        navController,
        modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
    )
}
```

专注运行中在 4 个 tab 上改为显示底栏 + FAB 倒计时，不再整栏隐藏；只有专注页运行态与深层页隐藏底栏。

- [ ] **Step 2: 重写手机底栏**

用下面的实现替换 `YanZhongBottomBar`（保留 `TabSpec`）：

```kotlin
@Composable
private fun YanZhongBottomBar(navController: NavHostController) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val colors = MaterialTheme.colorScheme
    Box {
        Surface(color = colors.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.navigationBarsPadding()) {
                HorizontalDivider(color = colors.outlineVariant)
                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.BottomCenter) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        BottomTab(
                            selected = currentRoute == Routes.HOME,
                            label = "今日",
                            icon = AppIcons.TabToday,
                            onClick = { navigateTab(navController, Routes.HOME) },
                            modifier = Modifier.weight(1f)
                        )
                        BottomTab(
                            selected = currentRoute == Routes.PLAN,
                            label = "计划",
                            icon = AppIcons.TabPlan,
                            onClick = { navigateTab(navController, Routes.PLAN) },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.weight(1f))
                        BottomTab(
                            selected = currentRoute == Routes.STATS,
                            label = "统计",
                            icon = AppIcons.TabStats,
                            onClick = { navigateTab(navController, Routes.STATS) },
                            modifier = Modifier.weight(1f)
                        )
                        BottomTab(
                            selected = currentRoute == Routes.MINE,
                            label = "我的",
                            icon = AppIcons.TabMine,
                            onClick = { navigateTab(navController, Routes.MINE) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "专注",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (currentRoute == Routes.FOCUS) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
        }
        FocusFab(
            navController = navController,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-18).dp)
        )
    }
}

private fun navigateTab(navController: NavHostController, route: String) {
    navController.navigate(route) {
        launchSingleTop = true
        popUpTo(Routes.HOME) { saveState = true }
        restoreState = true
    }
}

@Composable
private fun BottomTab(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** 中央 56dp 专注 FAB：计时中显示 mm:ss；切换只做一次淡入淡出，文字每秒更新不触发动画 */
@Composable
private fun FocusFab(navController: NavHostController, modifier: Modifier = Modifier) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as YanZhongApp
    val timer by app.engine.state.collectAsState()
    AnimatedContent(
        targetState = timer.isRunning,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
        label = "focus-fab",
        modifier = modifier
    ) { running ->
        FloatingActionButton(
            onClick = { navigateTab(navController, Routes.FOCUS) },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(56.dp)
        ) {
            if (running) {
                Text(
                    formatPillTime(timer.remainingMs),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            } else {
                Icon(AppIcons.TabFocus, contentDescription = "专注", modifier = Modifier.size(24.dp))
            }
        }
    }
}
```

- [ ] **Step 3: 平板导航栏保持 4 tab + 同一个 `FocusFab`**

`YanZhongNavRail` 的 `header` 改为 `{ FocusFab(navController, Modifier.padding(top = 8.dp)) }`，删除原来的静态 `FloatingActionButton`；tab 点击改为 `navigateTab(navController, tab.route)`。不要加第 5 个“专注”项。

- [ ] **Step 4: 清理 import**

删除 `NavigationBar`、`NavigationBarItem`、`NavigationBarItemDefaults`（`NavigationRail` / `NavigationRailItem` 仍被平板栏使用，保留）。`RoundedCornerShape` 仍被 `RunningTimerPill` 使用，保留。新增 `AnimatedContent`、`togetherWith`、`fadeIn`、`fadeOut`、`tween`、`CircleShape`、`clickable`、`fillMaxHeight`、`fillMaxSize`、`navigationBarsPadding`、`HorizontalDivider`、`ImageVector`、`Column`、`Arrangement`、`Alignment`（已有则跳过）。

- [ ] **Step 5: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt
git commit -m "fix(ui): four-tab bottom bar with countdown-aware focus fab"
```

---

## Task 5: 今日待办列表

**Files:**
- Modify: `ui/home/TaskListSection.kt`

**Interfaces:**
- Keeps: `taskListSection(openTasks, doneTasks, subjects, today, planSlots, onComplete, onPostpone, onPostponeRequest, onStartFocus, onTaskClick)` 签名不变。
- Consumes: `AppCard`、`CompletionCircle`、`ProgressLine`、`StatusPill`、`EmptyState`、`ListRow`。

- [ ] **Step 1: 替换区块头**

把 `"task-section-header"` 改为：

```kotlin
item(key = "task-section-header") {
    SectionHeader(
        title = "今日待办",
        meta = "${doneTasks.size}/${openTasks.size + doneTasks.size} 已完成"
    )
}
```

说明：区块头只保留一个完成度表达（`meta`），不再重复放胶囊；spec §4.1.6 的“添加”动作由页头 `+` 承担（Task 6 Step 2），同屏不留两个添加入口。

- [ ] **Step 2: 替换任务行**

保留 `SwipeToDismissBox` 的 `rememberSwipeToDismissBoxState` 与 `confirmValueChange` 逻辑不变（`StartToEnd` 完成、`EndToStart` 顺延/云端计划项回弹），只加触感与替换内部内容。在 `TaskRow` 顶部：

```kotlin
val haptic = LocalHapticFeedback.current
val dismissState = rememberSwipeToDismissBoxState(
    confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.StartToEnd -> {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onComplete()
                !task.isServerPlanItem()
            }
            // 云端计划项不能本地顺延；左滑直接回弹
            SwipeToDismissBoxValue.EndToStart -> {
                if (!task.isServerPlanItem()) onPostponeRequest()
                false
            }
            else -> false
        }
    }
)
```

`SwipeToDismissBox` 内部内容替换为：

```kotlin
AppCard(
    onClick = onClick,
    contentPadding = PaddingValues(12.dp),
    modifier = Modifier.animateContentSize()
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompletionCircle(
            checked = false,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onComplete()
            }
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(subjectColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    listOfNotNull(
                        subjectName,
                        planSlot ?: task.dueAt?.let { TimeUtils.formatHm(it) },
                        task.pomodoroEstimate
                            .takeIf { it > 0 }
                            ?.let { "${task.completedPomodoros}/$it 番茄" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) WarnRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (task.repeatRule != 0 || task.postponeCount > 0) {
                Text(
                    listOfNotNull(
                        task.repeatRule.takeIf { it != 0 }?.let { repeatLabel(task) },
                        task.postponeCount.takeIf { it > 0 }?.let { "顺延×$it" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        PillButton(text = "开始", onClick = onStartFocus)
    }
    if (task.pomodoroEstimate > 0) {
        Spacer(Modifier.height(10.dp))
        ProgressLine(
            progress = task.completedPomodoros.toFloat() / task.pomodoroEstimate,
            color = if (task.completedPomodoros >= task.pomodoroEstimate) SuccessGreen else subjectColor,
            height = 4.dp
        )
    }
}
```

要求：

- 新增 import：`androidx.compose.ui.hapticfeedback.HapticFeedbackType`、`androidx.compose.ui.platform.LocalHapticFeedback`。
- 删除 `SubjectArtwork`、`🍅`、`labelLarge` 时间标签、36dp Play 圆按钮。
- `AppIcons.Play` 不再用于任务行；“开始”胶囊是唯一可见的专注入口，手势仍是完成/顺延。
- `onClick` 整卡打开编辑（原来的行为），`onStartFocus` 只由胶囊触发。

- [ ] **Step 3: 替换已完成区**

`DoneSection` 的展开条改为 `AppCard(contentPadding = PaddingValues(0.dp))` + `ListRow(title = "已完成 ${doneTasks.size} 项", icon = ChevronDown/Up, onClick = ...)`；展开后的 `DoneTaskRow` 用：

```kotlin
Row(
    Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Icon(
        AppIcons.CheckCircle,
        contentDescription = "已完成",
        tint = SuccessGreen,
        modifier = Modifier.size(18.dp)
    )
    Spacer(Modifier.width(10.dp))
    Text(
        task.title,
        style = MaterialTheme.typography.bodyMedium,
        textDecoration = TextDecoration.LineThrough,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
    )
    Text(
        subject?.name ?: "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
```

外层用 `AppCard(contentPadding = PaddingValues(vertical = 4.dp))` 包住展开行；不要再用 0.4 alpha 的私有 Surface。

- [ ] **Step 4: 替换空态**

删除 `EmptyTaskHint()` 整个 Canvas 插画，改为：

```kotlin
EmptyState(
    icon = AppIcons.SubjectDefault,
    title = "今天还没有任务",
    hint = "添加一个任务，或直接开始自由专注",
    compact = true
)
```

本 Task 不传 `action`；Task 6 增加 `onAddTask` 参数后再补“添加任务”按钮。

- [ ] **Step 5: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/home/TaskListSection.kt
git commit -m "refactor(ui): redesign today task rows around one-tap completion"
```

---

## Task 6: 今日页与倒计时卡

**Files:**
- Modify: `ui/home/HomeScreen.kt`
- Modify: `ui/home/CountdownCard.kt`
- Modify: `ui/home/TaskListSection.kt`（补 `onAddTask` / `onBatchImport`）
- Modify: `ui/home/NodeManageSheet.kt`
- Modify: `ui/home/TaskEditSheet.kt`

**Interfaces:**
- Produces: `taskListSection(..., onAddTask, onBatchImport)` 两个新参数（无默认值，只有 Home 一个调用方）。
- Consumes: `PageHeader`、`HeroCard`、`GradientCard`、`AppCard`、`MetricTile`、`ProgressLine`、`SectionHeader`、`TextAction`、`EmptyState`、`AppSheet`、`ListRow`、`RowGroup`、`RowDivider`、`SegmentedChips`、`ToggleChip`、`PrimaryButton`、`SecondaryButton`。

- [ ] **Step 1: 删除首页顶部 300dp 渐变**

删除 `HomeScreen.kt` 中 `val colors = MaterialTheme.colorScheme`、`topBrush`、顶部 `Box(height(300.dp).background(topBrush))` 整段；根 `Box(Modifier.fillMaxSize())` 保留给 Snackbar。

- [ ] **Step 2: 页头统一（平板与手机）**

手机列与平板左列都使用：

```kotlin
PageHeader(
    title = "今日",
    meta = dateLabel + (state.phase?.let { " · ${it.stageName}" } ?: ""),
    actions = {
        IconButton(
            onClick = {
                editingTask = null
                showTaskEditor = true
            },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(AppIcons.Add, contentDescription = "添加任务", modifier = Modifier.size(20.dp))
        }
    }
)
```

平板左列在倒计时之前插入 `item(key = "page-header")`；平板右列删除 `QuickStartBar`，只保留 `TodayRhythmCard`（无生效计划时整列不渲染）。

- [ ] **Step 3: 重写倒计时卡**

在 `CountdownCard.kt` 中：

- 空节点：用 `AppCard` + `EmptyState(icon = AppIcons.Timer, title = "还没有倒计时节点", hint = "添加一个节点，让每一天都有锚点", compact = true, action = { TextAction("添加节点", onClick = onManageNodes) })`。
- `CountdownFace` 的普通档：`GradientCard(colors = BrandGradient)`；琥珀/红档：`AppCard` + `WarnAmberContainer/WarnRedContainer` 背景（用 `Surface` 或 `AppCard` 外包一层，文字用对应 content 色）。
- `CountdownFace` 增加参数 `nodeCount: Int`，`CountdownCard` 调用处传 `nodes.size`；删除 `nodesCountLabel`。
- 首行：节点名 `titleMedium` 白色（预警档用 content 色）+ 右侧 `TextButton("管理 · ${nodeCount} 个")`。
- 次行：`"${NodeType.label(node.type)} · ${TimeUtils.formatYmdHm(node.targetAt)}"`，`bodySmall`，白色 70%。
- 数字行：四列，`TimeUnitCell` 数值用 `CountdownNumberStyle`，单位 `labelSmall`，白色 70%；保留 ≤3 天呼吸动画。
- 分页圆点：

```kotlin
Row(
    Modifier.fillMaxWidth().padding(top = 12.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically
) {
    if (nodes.size <= 6) {
        repeat(nodes.size) { index ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (index == pagerState.currentPage) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (index == pagerState.currentPage) 0.95f else 0.45f))
            )
        }
    } else {
        Text(
            "${pagerState.currentPage + 1}/${nodes.size}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}
```

圆点需要在 `CountdownCard` 的 Pager 外层渲染（读取 `pagerState.currentPage`），不要放进 `CountdownFace`。预警档圆点颜色改用 `contentColor`。

- [ ] **Step 4: 重写未生成计划引导卡**

`PlanGuideCard` 改为：

```kotlin
HeroCard(
    title = "还没有学习计划",
    desc = "和 AI 聊 5 分钟，按目标院校与每天可用时间排一份全程计划。",
    icon = AppIcons.Sparkles,
    colors = actionCardGradient(),
    onClick = onOpenPlan,
    actionLabel = "开始制定"
)
```

- [ ] **Step 5: 阶段条改用 AppCard**

`PhaseStrip` 外层 `Surface(shape = large, tonalElevation = 1)` 改为 `AppCard`；标题行：`Text("${info.stageName}", titleMedium)` + `Text("第 ${info.dayIdx} / ${info.totalDays} 天", bodySmall, primary)`；四段占比条高度 6dp；底部说明 `bodySmall`，只保留 `info.nextStageName` 或“距考试 N 天”其中一句。删除私有 `Surface` 与手写标题字重。

- [ ] **Step 6: 今日概览三行紧凑化**

`TodayProgress` 改为一张 `AppCard`，内部三行，每行结构固定：

```kotlin
@Composable
private fun ProgressRow(
    label: String,
    value: String,
    progress: Float,
    color: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
        Spacer(Modifier.height(6.dp))
        ProgressLine(progress = progress, color = color)
    }
}
```

三行内容（变量均已在 `TodayProgress` 形参中）：

- 今日任务：`"${done}/${total}"`，progress = done/total。
- 今日番茄：`"${todayPomodoros}/${dailyPomodoroGoal}"`，icon = `AppIcons.Timer`，progress = todayPomodoros/dailyPomodoroGoal。
- 本周净学习：`"${formatStudyMinutes(weekMinutes)} / ${formatStudyMinutes(weekGoalMin)}"`，达成时 `SuccessGreen`，progress = weekMinutes/weekGoalMin。

删除 🍅、所有“目标/还差”长句、私有 Surface 与 pill。

- [ ] **Step 7: 今日节奏摘要一行**

`TodayRhythmCard` 默认收起态改为一句话：

```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
        Text("今日节奏", style = MaterialTheme.typography.titleMedium)
        Text(
            if (slots.isEmpty()) "${tpl.name} · 净学习 ${tpl.hours}"
            else "${tpl.name} · 净学习 ${formatStudyMinutes(totalMinutes)} · ${totalPomodoros} 个番茄 · ${doneSegments}/${slots.size} 段",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    Icon(
        if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
        contentDescription = if (expanded) "收起" else "展开",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp)
    )
}
```

`tpl`、`slots`、`totalMinutes`、`totalPomodoros`、`doneSegments`、`tpl.hours` 都是 `TodayRhythmCard` 现有局部变量，直接使用。外层换成 `AppCard(onClick = { expanded = !expanded })`；展开后的时段行保留原有数据与“现在”标记，但行内容改为：时间列 64dp（`labelSmall`）、科目色点 6dp、标题 `bodyMedium`；当前时段背景 `MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)`，右侧 `StatusPill("现在", PillTone.ACCENT)`。删除 `🍅`。

- [ ] **Step 8: 删除 QuickStartBar**

- 删除手机列 `item(key = "quick-start")` 与平板右列的同一 item。
- 删除整个 `QuickStartBar` 私有 Composable（约 1045–1128 行）。
- 删除不再使用的 import（如 `HeroCard` 若仍被 PlanGuideCard 使用则保留）。
- 保留 `BatchImportDialog`。

- [ ] **Step 9: 待办列表补添加与批量录入**

`taskListSection` 签名增加：

```kotlin
onAddTask: () -> Unit,
onBatchImport: () -> Unit
```

- 空态 `TextAction("添加任务")` 调用 `onAddTask`。
- 列表末尾增加 item：

```kotlin
item(key = "task-list-footer") {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        TextAction(text = "批量录入", icon = AppIcons.Add, onClick = onBatchImport)
    }
}
```

Home 两处调用传入：

```kotlin
onAddTask = {
    editingTask = null
    showTaskEditor = true
},
onBatchImport = { showBatchImport = true }
```

- [ ] **Step 10: 任务编辑与节点管理两个弹层迁到共享组件**

`NodeManageSheet.kt` 与 `TaskEditSheet.kt` 是首页仅剩的两个手写 `ModalBottomSheet` + `FilterChip` 文件，必须在本任务内迁完，否则 Task 15 的 `FilterChip` 零检查无法通过。字段、状态变量、增删改与保存逻辑一律不动，只换容器与控件。

**`NodeManageSheet.kt`：**

- `ModalBottomSheet` 整段替换为 `AppSheet(title = "倒计时节点", onDismiss = onDismiss)`，内部第一行保留说明：

```kotlin
Text(
    "置顶节点展示在首页，左右滑可临时切换",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
Spacer(Modifier.height(12.dp))
```

- 节点列表改为 `RowGroup`，行间 `RowDivider()`（默认 16dp 缩进）；`nodes` 为空时用 `EmptyState(icon = AppIcons.Timer, title = "还没有倒计时节点", hint = "添加初试、模考或报名节点，让每一天都有锚点", compact = true)`。
- `NodeRow` 改为 `ListRow`：点整行进入编辑，尾部只保留置顶与删除两个 40dp `IconButton`（图标 18dp）：

```kotlin
ListRow(
    title = node.name,
    subtitle = "${NodeType.label(node.type)} · ${TimeUtils.formatYmdHm(node.targetAt)} · $daysLabel",
    onClick = onEdit,
    showChevron = false,
    trailing = {
        IconButton(onClick = onPin, modifier = Modifier.size(40.dp)) {
            Icon(
                AppIcons.Pin,
                contentDescription = if (node.pinned) "取消置顶" else "置顶",
                tint = if (node.pinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                AppIcons.Delete,
                contentDescription = "删除节点",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
    }
)
```

- 底部两个 `Button` 改为并排 `SecondaryButton`：`"添加节点"`（`icon = AppIcons.Add`）与 `"批量添加"`，各 `Modifier.weight(1f)`，`horizontalArrangement = Arrangement.spacedBy(12.dp)`。
- `NodeEditorDialog` 仍是 `AlertDialog`（弹层内的表单不再叠一层 sheet），只做三处替换：
  - 两行 6 个 `FilterChip` 合并为一行 `SegmentedChips`，顺序与 `NodeType` 常量一一对应：

```kotlin
private val NODE_TYPE_OPTIONS = listOf(
    NodeType.EXAM to "初试",
    NodeType.MOCK to "模考",
    NodeType.ENROLL to "报名",
    NodeType.SUBJECT_EXAM to "单科",
    NodeType.RETEST to "复试",
    NodeType.CUSTOM to "自定义"
)

SegmentedChips(
    options = NODE_TYPE_OPTIONS.map { it.second },
    selectedIndex = NODE_TYPE_OPTIONS.indexOfFirst { it.first == type },
    onSelect = { type = NODE_TYPE_OPTIONS[it].first }
)
```

  - 时间选择器弹窗的 `Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp)` 改为 `Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface)`，标题 `headlineMedium` 改为 `titleMedium`。
  - 删除 `headlineLarge` 标题与 `tonalElevation`（均随旧容器消失）。
- 删除确认与批量添加两个 `AlertDialog` 保留，只把正文里的半角逗号统一为全角（文案微调，不影响逻辑）。

**`TaskEditSheet.kt`：**

- `ModalBottomSheet` 改为 `AppSheet(title = if (initial == null) "新增任务" else "编辑任务", onDismiss = onDismiss)`，删除原 `headlineLarge` 标题。
- 在文件底部新增私有小节标签（弹层内不再用 16sp 区块标题，避免六级标题压垮表单）：

```kotlin
@Composable
private fun FormLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}
```

- 表单主体按下面顺序重排，字段与状态变量不变；保存逻辑抽成局部 lambda，内容与原来完全一致：

```kotlin
val save: () -> Unit = {
    val trimmed = title.trim()
    if (trimmed.isNotEmpty()) {
        onSave(
            (initial ?: TaskEntity(subjectId = subjectId, title = trimmed)).copy(
                subjectId = subjectId,
                title = trimmed,
                priority = priority,
                pomodoroEstimate = pomodoroEstimate,
                dueAt = dueAt,
                repeatRule = repeatRule,
                repeatDays = repeatDays,
                note = note
            )
        )
        onDismiss()
    }
}

AppSheet(
    title = if (initial == null) "新增任务" else "编辑任务",
    onDismiss = onDismiss
) {
    OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text("标题") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(14.dp))

    FormLabel("科目")
    SegmentedChips(
        options = subjects.map { it.name },
        selectedIndex = subjects.indexOfFirst { it.id == subjectId },
        onSelect = { index -> subjects.getOrNull(index)?.let { subjectId = it.id } }
    )
    Spacer(Modifier.height(14.dp))

    FormLabel("截止日期")
    SegmentedChips(
        options = listOf("无", "今天", "明天", "选择日期"),
        selectedIndex = when (val due = dueAt) {
            null -> 0
            else -> when (TimeUtils.daysBetween(TimeUtils.now(), due)) {
                0L -> 1
                1L -> 2
                else -> -1
            }
        },
        onSelect = { index ->
            when (index) {
                0 -> dueAt = null
                1 -> dueAt = TimeUtils.dayStartOf() + 9 * 3600_000L
                2 -> dueAt = TimeUtils.dayStartOf() + 24 * 3600_000L + 9 * 3600_000L
                3 -> showDatePicker = true
            }
        }
    )
    Spacer(Modifier.height(14.dp))

    FormLabel("优先级")
    SegmentedChips(
        options = listOf("高", "中", "低"),
        selectedIndex = priority,
        onSelect = { priority = it }
    )
    Spacer(Modifier.height(14.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("预计番茄数", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextAction(
            text = "−",
            onClick = { if (pomodoroEstimate > 1) pomodoroEstimate-- },
            enabled = pomodoroEstimate > 1
        )
        Text(
            "$pomodoroEstimate",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 36.dp)
        )
        TextAction(
            text = "+",
            onClick = { if (pomodoroEstimate < 20) pomodoroEstimate++ },
            enabled = pomodoroEstimate < 20
        )
    }
    Spacer(Modifier.height(14.dp))

    FormLabel("重复")
    SegmentedChips(
        options = listOf("不重复", "每日", "每周"),
        selectedIndex = repeatRule,
        onSelect = { repeatRule = it }
    )
    if (repeatRule == RepeatRule.WEEKLY) {
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, day ->
                val bit = 1 shl i
                ToggleChip(
                    text = day,
                    selected = (repeatDays and bit) != 0,
                    onClick = {
                        repeatDays = if ((repeatDays and bit) != 0) repeatDays and bit.inv()
                        else repeatDays or bit
                    }
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))

    OutlinedTextField(
        value = note,
        onValueChange = { note = it },
        label = { Text("备注（可选）") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1
    )
    Spacer(Modifier.height(16.dp))

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (initial != null && onDelete != null) {
            TextAction(text = "删除", onClick = { showDeleteConfirm = true }, danger = true)
        }
        PrimaryButton(
            text = "保存",
            onClick = save,
            enabled = title.isNotBlank(),
            modifier = Modifier.weight(1f)
        )
    }
}
```

- 日期选择弹窗与删除确认弹窗保留不动；`showDeleteConfirm` 分支仍在 `AppSheet` 之外。
- 按编译报错清理 import：两文件都应去掉 `FilterChip`、`headlineLarge`、`tonalElevation`、`Button`（`NodeManageSheet` 的 `TimePicker` 仍用 `Surface`）；`TaskEditSheet` 新增 `Alignment`、`widthIn`、`TextAlign`、`horizontalScroll`、`rememberScrollState`。

- [ ] **Step 11: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 12: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/home/HomeScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/home/CountdownCard.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/home/TaskListSection.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/home/NodeManageSheet.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/home/TaskEditSheet.kt
git commit -m "refactor(ui): rebuild today screen around countdown and compact sections"
```

---

## Task 7: 复盘卡

**Files:**
- Modify: `ui/home/ReviewCard.kt`

**Interfaces:**
- Keeps: `ReviewCard(...)` 的参数与三个 Sheet 的签名、保存逻辑、可见性规则。

- [ ] **Step 1: 重写 `ReviewCard` 主体**

- 删除最外层 `Surface(tonalElevation = 1.dp)`：昨日要事单独一张 `AppCard`，下方直接是 `RowGroup`，不再整体套一层卡（spec §4.1.7）。
- 昨日要事：`AppCard` 内嵌一块 `Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(insetSurfaceColor()).padding(12.dp))`，图标 `Target` 18dp，标题 `labelMedium`，内容 `bodyMedium`，最多 2 行。
- 今日复盘：`RowGroup` 三行 `ListRow`。
  - 今日：icon = `CheckCircle` / `Sparkles`，title = "今日复盘"，subtitle = `if (dailyDone) "点击查看 / 修改" else "睡前 3 问，30 秒闭环"`，trailing = `Text(if (dailyDone) "已完成" else "未写")` + Chevron。
  - 本周：仅在 `isSunday || !weeklyDone` 时渲染；title = "本周复盘"；状态：`weeklyDone → "已完成"`，`isSunday && !weeklyDone → "周日提醒"`，否则 `"未写"`。
  - 本月：仅在 `isMonthEnd || !monthlyDone` 时渲染；状态：`monthlyDone → "已完成"`，`isMonthEnd && !monthlyDone → "月底提醒"`，否则 `"未写"`。
  - 已完成状态用 `onSurfaceVariant`，未写/提醒用 `primary`。
- 行间插 `RowDivider()`。删除三段私有 `Surface` 与所有 `bodyLarge`。

- [ ] **Step 2: 三个 Sheet 统一规格**

- 三个 `ModalBottomSheet` + 手写 `Column(verticalScroll/padding)` 改为 `AppSheet(title = "今日复盘 · 三问" / "本周复盘" / "本月复盘", onDismiss = onDismiss)`，删除原 `headlineLarge` 标题与重复 padding（`AppSheet` 已含滚动、`imePadding` 与 20dp 边距）。
- 原副标题（如“每个日模板 21:30 的固定块…”“周日晚 30min…”“月末 30min…”）保留为 `AppSheet` 内容第一行 `bodySmall / onSurfaceVariant`。
- 主按钮 `Button` → `PrimaryButton`，占 `weight(1f)`；取消 `TextButton` → `SecondaryButton`，占 `weight(1f)`，同一行 `spacedBy(12.dp)`。
- 输入框保持 `OutlinedTextField` 与现有校验；本周/本月自动盘点块保留 `Surface(primaryContainer 35%)`，不加阴影。
- 不改动 `onSave` 参数顺序与保存内容。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/home/ReviewCard.kt
git commit -m "refactor(ui): collapse review card into three compact rows"
```

---

## Task 8: 专注页

**Files:**
- Modify: `ui/focus/FocusScreen.kt`

**Interfaces:**
- Keeps: `FocusViewModel` 的全部调用、严格模式/紧急退出/接力/科目/放弃原因逻辑、`Phase` 与 `TimerMode`。
- Consumes: `PageHeader`、`AppCard`、`RowGroup`、`ListRow`、`SegmentedChips`、`SwitchRow`、`PrimaryButton`、`SecondaryButton`、`TextAction`、`AppSheet`、`CompletionCircle`、`CountdownNumberStyle`、`FocusTimerStyle`。

- [ ] **Step 1: 空闲态页头与主按钮**

- `IdleContent` 顶部删除居中 `Text("开始专注", headlineLarge)` 与“从今日待办开始”区块头，改为左对齐 `PageHeader("专注", "选一个任务，或直接开始")`。
- 把开始按钮移到圆环正下方（任务选择行之前），文案：
  - 番茄钟：`"开始专注 · ${当前方案 focusMin} 分钟"`；
  - 倒计时：`"开始倒计时 · ${minutes} 分钟"`；
  - 正计时：`"开始正计时"`。
- 按钮用 `PrimaryButton`，`modifier.widthIn(max = 320.dp)`，居中。
- 三个开始入口（`vm.quickFocus()` / `vm.quickStopwatch()` / `vm.quickCountdown(countdownMin)`，当前在 238/255/307 行）统一包一层触感：`val haptic = LocalHapticFeedback.current`，点击时先 `haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)` 再调用原回调；任务行立即开始（`vm.startForTask` / `startForTaskStopwatch` / `startForTaskCountdown`）同样触发。不改 ViewModel。

- [ ] **Step 2: 圆环与任务选择**

- 空闲圆环 240dp（`size(240.dp)`），中心时长用 `CountdownNumberStyle`；上方模式 `bodySmall`；下方任务名或“自由专注” `bodyMedium`，单行省略。
- 任务选择改为 `ListRow(title = "选择任务", subtitle = 当前任务名 ?: "从今日待办中选一个", icon = AppIcons.ClipboardList, trailing = { ChevronRight })`，点击打开 `AppSheet("选择今日任务")`。
- 今日任务为空时该行置灰不可点：`enabled = false`、`showChevron = false`、`subtitle = "今日没有待办"`、`onClick = null`（spec §4.2.4）。
- Sheet 内任务行：`ListRow(title = task.title, subtitle = 科目 · 番茄数, onClick = { 选中并立即按当前模式开始 })`；无任务时显示 `EmptyState(..., compact = true)`。
- 保留原来的“点任务立即开始”语义，不新增确认步骤。

- [ ] **Step 3: 模式与方案**

- `ModeChip` / `FilterChip` 改为 `SegmentedChips(listOf("番茄钟", "正计时", "倒计时"), selectedIndex, onSelect)`；映射顺序与 `TimerMode` 枚举一致。
- 番茄方案用 `RowGroup` 两行 `ListRow(title = "标准 25 分钟" / "深度 50 分钟", subtitle = 参数摘要, trailing = 选中时 Check 图标)`；点击选择，保留原有持久化。选中行的背景用 `Box(Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)))` 包住 `ListRow`，未选中透明。
- 倒计时滑杆保留；快捷值 `5/15/25/45` 改为 `SegmentedChips`。

- [ ] **Step 4: 更多设置**

- 用 `AppCard` + 可点击标题行折叠（`ChevronDown/Up`），默认收起。
- 展开后：`SwitchRow("自动串联", state.settings.autoChain, vm::toggleAutoChain, subtitle = "休息结束自动开始下一个")`；`SwitchRow("连续专注", state.settings.continuousFocus, vm::toggleContinuous, subtitle = "跳过休息，一路专注下去")`。
- 只有这两个开关；删除私有 `SettingSwitchRow`。静音/震动/音效留在我的页。
- 接力卡 `PeerFocusCard` 外层 `Surface(border + tint)` 改为 `AppCard`，内容保留主机设备名、任务、实时倒计时与“跟随”按钮；按钮用 `TextAction("跟随", onClick = onFollow)`，删除手写圆角与 `Button` 默认样式，`followPeer` 逻辑不动。

- [ ] **Step 5: 运行态**

- 圆环 280dp；中心时间用 `FocusTimerStyle`（48/52 Bold tnum，全 App 唯一大号计时数字）；上方 `StatusPill` 显示 专注/短休/长休/暂停；下方任务名 `bodyMedium`。
- 删除 `+1 🍅` 文案与计数里的 `🍅`，改为 `"番茄 ${completed}/${estimate}"`。
- 控制区：暂停/继续用 `PrimaryButton`（宽度 200dp）；结束用 `TextAction`；严格模式“紧急退出”保留现有文案、次数与确认弹窗。
- 休息态圆环与按钮用 `SuccessGreen`；保留“跳过休息”。
- 暂停、继续、结束（`vm.pause()` / `vm.resume()` / `vm.finishStopwatch()` / `vm.endDuringBreak()` / `vm.skipBreak()`，以及点击“结束专注 / 紧急退出”打开确认弹窗那一下）同样先 `performHapticFeedback(HapticFeedbackType.TextHandleMove)` 再执行原逻辑；确认弹窗里的最终放弃不重复触发。新增 import `HapticFeedbackType`、`LocalHapticFeedback`。
- 所有 `ActionButton` 私有实现删除，替换为共享按钮；保留原有回调。

- [ ] **Step 6: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。若严格模式逻辑被误改，回退该段再重做。

- [ ] **Step 7: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt
git commit -m "refactor(ui): put focus start action first and unify timer controls"
```

---

## Task 9: 计划页

**Files:**
- Modify: `ui/plan/PlanScreen.kt`

**Interfaces:**
- Keeps: `PlanViewModel` 全部调用、日期选择、勾选、撤销、入口路由。
- Consumes: `PageHeader`、`AppCard`、`RowGroup`、`ListRow`、`SectionHeader`、`PrimaryButton`、`TextAction`、`SkeletonBlock`、`CompletionCircle`、`ProgressLine`、`StatusPill`。

- [ ] **Step 1: 统一三种状态卡**

`PlanActionCard` 改为 `AppCard`（无阴影）：

```kotlin
AppCard {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        PrimaryButton(primaryLabel, onPrimary, icon = AppIcons.Sparkles)
        if (secondaryLabel != null && onSecondary != null) {
            TextAction(secondaryLabel, onSecondary, modifier = Modifier.fillMaxWidth())
        }
    }
}
```

文案：

- 登录：标题“登录后就有了云端计划”，说明“计划存在账号里，换台设备登录还是同一份。”，主按钮“前往登录”。
- 无计划：标题“还没有学习计划”，说明“和 AI 聊 5 分钟，按目标院校与每天可用时间排一份全程计划。”，主按钮“开始制定”，次操作“看历史计划”。
- 网络错误：标题“未能读取云端计划”，说明用固定摘要（`planError` 含 timeout 时“请求超时，稍后再试”；否则“网络或服务暂时不可用”），不把 `planError` 原文拼进正文（避免 HTTP 状态码），主按钮“重试”。

- [ ] **Step 2: 加载态**

`PlanLoadingCard` 改为 `AppCard` 内三个 `SkeletonBlock`（宽度 100% / 72% / 86%，高度 14dp，间距 10dp），删除无限脉冲 Box 与 `rememberInfiniteTransition`。

- [ ] **Step 3: 概览卡**

替换原“计划名 + 三行元信息”：

```kotlin
val stages = plan.stages.sortedBy { it.sortOrder }
val current = plan.currentStage(today)
val nextStage = stages.firstOrNull { it.startDate > today.toString() }
val (dayInStage, stageTotalDays) = stageDayInfo(current, today)
val stageLabel = current?.let { "第 $dayInStage / $stageTotalDays 天" } ?: "当前日期不在计划阶段内"
```

如果 `ServerPlanSummary` 顶部已有 `val current = plan.currentStage(today)`（当前第 284 行），删掉重复声明，只保留一次。

```kotlin
AppCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(plan.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${current?.name ?: "全程计划"} · $stageLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusPill("进行中", PillTone.ACCENT)
    }
    Spacer(Modifier.height(10.dp))
    ProgressLine(progress = plan.progress.doneItems.toFloat() / plan.progress.totalItems.coerceAtLeast(1))
    Spacer(Modifier.height(6.dp))
    Text(
        "全程 ${plan.progress.doneItems}/${plan.progress.totalItems} 项 · 考期 ${plan.examDate}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        "下一阶段：${nextStage?.name ?: "已进入最后阶段"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
```

`currentStage` 是 `PlanViewModel.kt` 已有的 internal 扩展，直接复用；`stageDayInfo` 用 `java.time.LocalDate.parse` 与 `ChronoUnit.DAYS` 现算，抽成私有函数：

```kotlin
private fun stageDayInfo(stage: PlanStageDto?, today: LocalDate): Pair<Int, Int> {
    if (stage == null) return 0 to 0
    val start = runCatching { LocalDate.parse(stage.startDate) }.getOrDefault(today)
    val end = runCatching { LocalDate.parse(stage.endDate) }.getOrDefault(today)
    val total = (ChronoUnit.DAYS.between(start, end).toInt() + 1).coerceAtLeast(1)
    val day = (ChronoUnit.DAYS.between(start, today).toInt() + 1).coerceIn(1, total)
    return day to total
}
```

- [ ] **Step 4: 入口 RowGroup**

把三个全宽按钮/入口改为：

```kotlin
RowGroup {
    ListRow(title = "调整近期计划", subtitle = "临时有事、生病、换课表，说一句就重排", icon = AppIcons.Chat, onClick = onOpenAssistant)
    RowDivider()
    if (plan.document != null) {
        ListRow(title = "全程规划文档", subtitle = "目标 · 阶段 · 作息 · 军规", icon = AppIcons.Doc, onClick = onOpenDocument)
        RowDivider()
    }
    ListRow(title = "历史计划", subtitle = "回看已归档的旧版本", icon = AppIcons.ClipboardList, onClick = onOpenHistory)
}
```

主操作“调整档案并重新生成 / 重新生成计划”保留为 `PrimaryButton`，放在概览卡之后、入口组之前；`stale` 时按钮文案改“重新生成计划”，并在概览卡下方保留 `Surface(errorContainer)` 横幅，文案不变。

- [ ] **Step 5: 今日计划卡**

- 标题行：`Text(if (selectedDate == today) "今日计划" else "当日计划", titleMedium)` + “回到今天” `TextAction` + “选日期” `TextAction`；日期选择器逻辑不动。
- 进度：`Text("$selectedDate · 完成 $done / $total", bodySmall)` + `ProgressLine`。
- 计划项行：`CompletionCircle(checked = item.status == "done", onClick = { onToggle(item) }, enabled = !busy)` + 科目色点 + 标题 `bodyMedium` + `"${time} · ${item.subject} · ${item.minutes} 分钟"` `bodySmall`；整行 `Modifier.clickable(enabled = !busy) { onToggle(item) }`，行间 `RowDivider(startIndent = 48.dp)`。
- 删除 `Checkbox` 与超长空态文案；无项时 `EmptyState(icon = AppIcons.Calendar, title = "这一天没有安排", compact = true)`。

- [ ] **Step 6: 撤销行**

`latestAdjustment?.status == "applied"` 时：

```kotlin
ListRow(
    title = "最近调整",
    subtitle = latestAdjustment?.summary,
    icon = AppIcons.RotateCcw,
    onClick = if (!busy && !undoing) onUndo else null,
    showChevron = false,
    trailing = {
        Text(
            if (undoing) "撤销中…" else "撤销",
            style = MaterialTheme.typography.labelMedium,
            color = if (busy || undoing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
        )
    }
)
```

- [ ] **Step 7: 删除私有基元**

删除 `PlanCard`、`PlanMetaRow`、`PlanEntryRow`；统一使用 `AppCard` / `ListRow`。保留 `planSubjectColor`。

- [ ] **Step 8: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 9: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanScreen.kt
git commit -m "refactor(ui): simplify plan states and surface today plan"
```

---

## Task 10: 统计页

**Files:**
- Modify: `ui/stats/StatsScreen.kt`
- Modify: `ui/stats/Charts.kt`

**Interfaces:**
- Keeps: `StatsViewModel` 全部调用与四档 `StatsRange`。
- Consumes: `PageHeader`、`SegmentedChips`、`StatBanner`、`MetricTile`、`AppCard`、`SectionHeader`、`InfoAction`、`EmptyState`、`ProgressLine`。

- [ ] **Step 1: 页头与范围选择**

```kotlin
PageHeader(title = "统计", meta = "数据全部来自本机")
SegmentedChips(
    options = StatsRange.entries.map { it.label },
    selectedIndex = StatsRange.entries.indexOf(state.range),
    onSelect = { vm.selectRange(StatsRange.entries[it]) }
)
```

删除“小结范围”标签、FilterChip 行和“筛选作用于…”长句。

- [ ] **Step 2: KPI 横幅**

```kotlin
val (focusNum, focusUnit) = splitValueUnit(TimeUtils.formatHours(state.focusMin))
StatBanner(
    title = "${state.range.label}概览",
    action = {
        InfoAction(
            title = "统计口径",
            message = "任务完成率 = 已完成任务 / 计划任务；专注时长只统计单次满 1 分钟的会话；长期图表窗口固定为近 14 天 / 近 30 天 / 近 15 周，不随上方筛选变化。",
            tint = Color.White.copy(alpha = 0.9f)
        )
    }
) {
    Row(Modifier.fillMaxWidth()) {
        MetricTile(focusNum, "净专注", Modifier.weight(1f), unit = focusUnit, tone = MetricTone.ON_GRADIENT)
        MetricTile("${state.pomodoroCount}", "番茄", Modifier.weight(1f), unit = "个", tone = MetricTone.ON_GRADIENT)
        MetricTile(rateNumFirst(state.taskRateLabel), "任务完成率", Modifier.weight(1f), unit = rateUnitRest(state.taskRateLabel), tone = MetricTone.ON_GRADIENT)
        MetricTile("${state.streak}", "连续打卡", Modifier.weight(1f), unit = "天", tone = MetricTone.ON_GRADIENT)
    }
}
```

横幅下方一行 Caption：

```kotlin
if (state.range == StatsRange.WEEK) {
    Text(
        "较上周 ${deltaLabel(state.focusMin - state.lastWeekMin)} · 专注 ${state.weekFocusDays} 天",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
} else if (state.range == StatsRange.MONTH) {
    Text(
        "较上月 ${deltaLabel(state.focusMin - state.lastMonthMin)} · 专注 ${state.monthFocusDays} 天",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
```

`deltaLabel` 私有函数：正数 `"▲ ${TimeUtils.formatHours(delta)}"`，负数 `"▼ ${TimeUtils.formatHours(-delta)}"`，0 `"持平"`。

- [ ] **Step 3: 删除 KpiNotes / WeekReportCard / MonthReportCard 调用**

- 删除 `KpiNotes`、`WeekReportCard`、`MonthReportCard` 三个私有 Composable 及它们在 LazyColumn 里的 item；比较数据改用 Step 2 的 caption。`splitValueUnit` / `rateNumFirst` / `rateUnitRest` 仍被 KPI 与纪录卡使用，保留。
- 保留 `ui/stats/WeeklyReport.kt`：`StatsViewModel` 依赖其中的 `WeeklyReport` 数据类与 `buildWeeklyReport`，不得删除；本 Task 只是不再渲染统计页里的周报/月报卡（这两个卡本来就定义在 `StatsScreen.kt`）。

- [ ] **Step 4: 科目投入卡**

```kotlin
AppCard {
    Text("科目投入 · ${state.range.label}", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    if (state.perSubject.isEmpty()) {
        EmptyState(icon = AppIcons.Timer, title = "这个范围还没有专注记录", hint = "完成一个番茄后开始统计", compact = true)
    } else {
        SubjectRingChart(perSubject = state.perSubject, subjects = state.subjects)
    }
}
```

- [ ] **Step 5: 长期趋势标题与图表卡**

```kotlin
Column {
    SectionHeader(
        title = "长期趋势",
        meta = "近 14 天 / 近 30 天 / 近 15 周",
        action = {
            InfoAction(
                "统计口径",
                "任务完成率 = 已完成任务 / 计划任务；专注时长只统计单次满 1 分钟的会话；长期图表窗口固定为近 14 天 / 近 30 天 / 近 15 周，不随上方筛选变化。"
            )
        }
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "以下窗口固定，不随上方筛选变化",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
```

`meta` 只列窗口，Caption 保留 spec §4.4.6 要求的固定窗口提示，完整口径在 `InfoAction` 弹窗。

`ChartCard` 改用 `AppCard` + 标题 `titleMedium`；四张图表标题保持现有文案（`净专注趋势 · 近 14 天` 等）。删除 `SectionCard` 依赖。

- [ ] **Step 6: 累计纪录 2×2**

`RecordsCard` 用 `AppCard`：

```kotlin
val records = state.records
AppCard {
    Text("累计纪录", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth()) {
        MetricTile(TimeUtils.formatHours(records.longestSessionMin), "最长单次", Modifier.weight(1f), compact = true)
        MetricTile(TimeUtils.formatHours(records.bestDayMin), "最佳单日", Modifier.weight(1f), compact = true)
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth()) {
        MetricTile(TimeUtils.formatHours(records.avgPerDayMin), "日均", Modifier.weight(1f), compact = true)
        MetricTile("${records.activeDays}", "累计天数", Modifier.weight(1f), unit = "天", compact = true)
    }
}
```

字段以 `FocusRecords` 现有属性为准（`longestSessionMin` / `bestDayMin` / `avgPerDayMin` / `activeDays` 都是 `Int` 分钟或天数），用 `TimeUtils.formatHours` 格式化，不要改 ViewModel。

- [ ] **Step 7: Charts 空态紧凑化**

`Charts.kt` 中四个 `EmptyState(...)` 增加 `compact = true`；删除卡片内部多余的 `spacedBy`/padding 字面量，统一由 `AppCard` 提供。

`SubjectRingChart` 图例补齐 spec §4.4.5：每行 = 色点 8dp + 科目名 `bodyMedium` + 百分比/时长 `bodySmall` + 下方 `ProgressLine(progress = pct / 100f, color = 科目色, height = 4.dp)`；环形与中心“净专注”保持 120dp 不变。

- [ ] **Step 8: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 9: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/stats/StatsScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/stats/Charts.kt
git commit -m "refactor(ui): tighten stats kpis and remove inline methodology notes"
```

---

## Task 11: 我的页

**Files:**
- Modify: `ui/mine/MineScreen.kt`

**Interfaces:**
- Keeps: `MineViewModel` 全部调用、全部弹窗（科目编辑/批量、番茄方案、周目标、番茄目标、删除确认）、科目删除确认、`PolicyLinksRow` 可新增使用。
- Consumes: `GradientCard`、`GradientTag`、`AppCard`、`RowGroup`、`ListRow`、`SwitchRow`、`SectionHeader`、`MetricTile`、`SegmentedChips`、`TextAction`、`EmptyState`、`PolicyLinksRow`。

- [ ] **Step 1: 删除整页渐变与私有配色**

- 根 `Box` 的 `Brush.verticalGradient(...)` 删除，只保留 `background`（或不设背景）。
- 删除 `MinePalette`、`minePalette()`、`GroupCard`、`RowDivider`、`RowIcon`、`SettingRowBase`、`NavRow`、`ValueRow`、`MineSectionHead`、`StatCell`、私有的 `SwitchRow`。
- `contentPadding` 改为左右 20dp、top 12dp、`verticalArrangement = spacedBy(16.dp)`。
- 同步删除只服务旧玻璃拟态的 import（`luminance`、`SolidColor`、`SpanStyle`、`buildAnnotatedString`、`withStyle`、`alpha`、`BorderStroke`、`FlowRow` 等按编译报错清理），并 import 共享组件（`AppCard`、`RowGroup`、`RowDivider`、`ListRow`、`SwitchRow`、`SectionHeader`、`TextAction`、`EmptyState`、`GradientCard`、`GradientTag`、`MetricTile`、`SegmentedChips`、`PrimaryButton`、`SecondaryButton`、`MetricTone`）。

- [ ] **Step 2: 头部渐变卡**

用 `GradientCard` 重写 `HeaderCard`，签名改为 `HeaderCard(state: MineUiState, onOpenAccount: () -> Unit)`：

- 头像 52dp：`Color.White.copy(alpha = 0.18f)` 圆底 + `GraduationCap` 白色 26dp。
- 用户名 `titleLarge`（18/26 SemiBold）白色，未登录“本机学习档案”；状态行 `bodySmall` 白色 80%：“已登录 · 云端同步” / “数据保存在本机”。
- 科目标签：最多 4 个 `GradientTag(subject.name)`；超过 4 个追加 `GradientTag("等 N 科")`。没有科目时不显示。
- 四项指标一行（紧凑）：

```kotlin
val profile = state.learningProfile
MetricTile(TimeUtils.formatHours(profile.totalFocusMin), "累计专注", Modifier.weight(1f), compact = true, tone = MetricTone.ON_GRADIENT)
MetricTile("${profile.activeDays}", "活跃天数", Modifier.weight(1f), unit = "天", compact = true, tone = MetricTone.ON_GRADIENT)
MetricTile("${profile.streakDays}", "连续打卡", Modifier.weight(1f), unit = "天", compact = true, tone = MetricTone.ON_GRADIENT)
MetricTile(profile.peakHour?.let { "%02d:00".format(it) } ?: "--", "峰值时段", Modifier.weight(1f), compact = true, tone = MetricTone.ON_GRADIENT)
```

- 全 0（`profile.totalFocusMin == 0`）时底部一句 `"完成第一个番茄后，这里会亮起来"`，白色 70%。
- 整卡点击进入账号页：`GradientCard(onClick = onOpenAccount)`；`MineScreen` 调用处传 `onOpenAccount = { navController.navigate(Routes.ACCOUNT) { launchSingleTop = true } }`，保留原行为。

- [ ] **Step 3: 分组与行**

按顺序（`accountTitle` 等局部变量先按下面的定义声明在 `MineScreen` 的 LazyColumn 之前）：

```kotlin
val accountTitle = when (val account = state.account) {
    is AccountState.LoggedIn -> account.username.ifBlank { "已登录" }
    AccountState.LoggedOut -> "未登录"
    AccountState.Loading -> "账号"
}
val accountSubtitle = when (state.account) {
    is AccountState.LoggedIn -> "计划与进度已存服务端 · 多设备共用"
    AccountState.LoggedOut -> "登录后数据自动保存到服务端"
    AccountState.Loading -> "正在读取登录状态…"
}
val planSummary = state.settings.plans
    .find { it.name == state.settings.currentPlanName }
    ?.let { "专注${it.focusMin} · 短休${it.shortBreakMin} · 长休${it.longBreakMin}" }
    ?: "选一套，或改到顺手为止"
val superModeSubtitle = when {
    !state.settings.superModeOn -> "专注期间拦截非白名单应用 · 未开启"
    state.settings.superModeStrict -> "严格锁定 · 已开启"
    else -> "白名单放行 ${state.settings.whitelist.size} 个应用 · 已开启"
}

SectionHeader("账号与档案")
RowGroup {
    ListRow(
        title = accountTitle,
        subtitle = accountSubtitle,
        icon = AppIcons.CloudUpload,
        onClick = { navController.navigate(Routes.ACCOUNT) { launchSingleTop = true } }
    )
    RowDivider()
    ListRow(
        title = "备考档案",
        subtitle = "考试科目、目标院校、课表",
        icon = AppIcons.Target,
        onClick = { navController.navigate(Routes.planSetup()) { launchSingleTop = true } }
    )
}

AppCard { LearningProfileCard(state.learningProfile) }

SectionHeader("偏好设置")
RowGroup {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text("外观", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        SegmentedChips(
            options = listOf("浅色", "深色", "跟随系统"),
            selectedIndex = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)
                .indexOf(state.settings.themeMode),
            onSelect = {
                vm.setTheme(listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)[it])
            }
        )
    }
    RowDivider()
    ListRow(title = "番茄方案", subtitle = planSummary, icon = AppIcons.Timer, onClick = { showPlanEditor = true },
        trailing = { Text(state.settings.currentPlanName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) })
    RowDivider()
    ListRow(title = "学霸模式", subtitle = superModeSubtitle, icon = AppIcons.Shield, onClick = { navController.navigate(Routes.SUPERMODE) })
    RowDivider()
    SwitchRow("自动串联", state.settings.autoChain, vm::setAutoChain, subtitle = "休息结束自动开始下一个")
    RowDivider()
    SwitchRow("连续专注", state.settings.continuousFocus, vm::setContinuousFocus, subtitle = "跳过休息，一路专注下去")
    RowDivider()
    SwitchRow("静音模式", state.settings.silentMode, vm::setSilent, subtitle = "阶段切换不震动、不出声")
    RowDivider()
    SwitchRow("阶段切换震动", state.settings.vibrationOn, vm::setVibration, dimmed = state.settings.silentMode)
    RowDivider()
    SwitchRow("阶段切换音效", state.settings.soundOn, vm::setSound, dimmed = state.settings.silentMode)
    RowDivider()
    ListRow(title = "每周目标净学习", subtitle = "今日页与统计页的达标线", icon = AppIcons.Target,
        onClick = { showGoalEditor = true }, trailing = { Text("${state.settings.weeklyGoalHours} 小时", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) })
    RowDivider()
    ListRow(title = "每日番茄目标", subtitle = "今日页番茄数的达标线", icon = AppIcons.Target,
        onClick = { showPomodoroGoalEditor = true }, trailing = { Text("${state.settings.dailyPomodoroGoal} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) })
}
```

注意：`ListRow` 的 `trailing` 是 `RowScope`，可直接放 `Text`。外观选择把三枚 chips 放在标题下一行（360dp 宽放不下“外观 + 三个 chip”同行），仍在这张卡内。

`LearningProfileCard` 重写为 `LearningProfileCard(profile: LearningProfile)`（删除 `palette` 形参）：

- 容器 `GroupCard(palette) { Column(padding...) }` → `AppCard { Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }`（`AppCard` 已有 16dp 内边距，纵向再补 6dp 即可）。
- 标题行：`Icon(AppIcons.TrendingUp, tint = primary, size 18dp)` + `Text("投入与节奏", titleMedium)`。
- 科目行保留 Top-3，色点 10dp；科目名 `bodyMedium`、时长 `bodyMedium` 加 `tnum`；`palette.ink` → `onSurface`，`palette.muted` → `onSurfaceVariant`；行间 `RowDivider(startIndent = 28.dp)`。
- “最近 7 天”柱状图保留现有高度算法与日期标签；有数据的柱用 `Brush.verticalGradient(BrandGradient)`，空柱用 `outlineVariant` 且高度 4dp；日期标签 `labelSmall` + `onSurfaceVariant`。
- 全 0 与无科目时的空态文案保留原文，颜色改 `onSurfaceVariant`；删除 M3 `TextAlign` 之外的 palette 引用。

- [ ] **Step 4: 科目管理**

```kotlin
SectionHeader("科目管理")
RowGroup {
    if (state.subjects.isEmpty()) {
        EmptyState(icon = AppIcons.SubjectDefault, title = "还没有科目", hint = "先添加几个再开始专注", compact = true)
    } else {
        state.subjects.forEachIndexed { index, subject ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(subject.colorArgb)))
                Spacer(Modifier.width(12.dp))
                Text(subject.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { deleteSubjectCandidate = subject }) {
                    Icon(AppIcons.Delete, contentDescription = "删除科目 ${subject.name}", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
            if (index < state.subjects.size - 1) RowDivider()
        }
    }
    RowDivider()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextAction("添加科目", onClick = { showSubjectEditor = true }, icon = AppIcons.Add)
        TextAction("批量添加", onClick = { showSubjectBatch = true })
    }
}
```

- [ ] **Step 5: 关于**

```kotlin
SectionHeader("关于")
RowGroup {
    ListRow(
        title = "研钟 YanZhong",
        subtitle = "v${BuildConfig.VERSION_NAME} · 本地数据 · 零广告",
        icon = AppIcons.Info
    )
    RowDivider()
    PolicyLinksRow(prefix = "隐私与协议：")
}
```

`PolicyLinksRow` 已自带两个链接与点击行为；不要新增弹窗。`BuildConfig` 需要 `import com.yanzhong.app.BuildConfig`。

- [ ] **Step 6: 弹窗统一**

保留六个弹窗（科目编辑、批量科目、番茄方案、周目标、番茄目标、删除确认）的逻辑与字段；只把主按钮换成 `PrimaryButton`、取消换 `SecondaryButton`（宽度按弹窗内容限制为 `fillMaxWidth`），标题 `titleMedium`。不要改校验与回调。

- [ ] **Step 7: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt
git commit -m "refactor(ui): rebuild mine screen on shared rows and gradient header"
```

---

## Task 12: 计划域二级页

**Files:**
- Modify: `ui/plan/PlanSetupScreen.kt`
- Modify: `ui/plan/PlanInterviewScreen.kt`
- Modify: `ui/plan/PlanDocumentScreen.kt`
- Modify: `ui/plan/PlanHistoryScreen.kt`
- Modify: `ui/plan/AdjustmentCard.kt`

**Interfaces:**
- Keeps: 所有 ViewModel、导航参数、`planSetup(profileOnly)` / `planInterview(mode)` 路由、保存与生成逻辑。
- Consumes: `SubPageHeader`、`AppCard`、`RowGroup`、`ListRow`、`SectionHeader`、`StatusPill`、`PrimaryButton`、`SecondaryButton`、`TextAction`、`OptionChip`、`SegmentedChips`、`SkeletonBlock`、`EmptyState`、`MetricTile`、`insetSurfaceColor()`。

**本任务内的统一清理（不留给 Task 15 兜底）：**

- 五个文件里的 `tonalElevation`、`shadowElevation`、`headlineLarge` 全部删除，结构层不再写 `fontWeight =` / `fontSize =`，层级只用 Task 1 的 `titleLarge` / `titleMedium` / `titleSmall` / `body*` 表达。唯一保留：`PlanDocumentScreen.kt` 的 `md()` 内置 `SpanStyle(fontWeight = Bold)`（正文行内加粗，不属于页面层级）。
- 带阴影的容器按语义替换：内容卡 → `AppCard`；嵌套卡 → `Surface(color = insetSurfaceColor(), shape = MaterialTheme.shapes.medium)`；底部输入/操作条 → `Surface(color = MaterialTheme.colorScheme.surface)` + 上方 `HorizontalDivider(color = outlineVariant)`，不再靠 4/6dp 阴影分层。
- `surfaceContainerHigh` 一律改为 `surface` 或 `insetSurfaceColor()`。

- [ ] **Step 1: 计划设置页**

- 顶部 `SubPageHeader("制定备考档案", meta = "AI 按这份档案排计划", onBack = { navController.popBackStack() })`。
- 表单保持现有分步问卷结构（`state.step` + `StepTarget/StepRhythm/StepFacts`）；每一步的题目包进一张 `AppCard`，卡外用 `SectionHeader` 标出当前步骤（目标院校 / 每日时长 / 考试科目与课表），不把分步表单改成平铺长表单。
- 底部保存按钮固定：外层改为 `Box(fillMaxSize)`，滚动 `Column` 的底部 padding 预留 96dp，`PrimaryButton` 放在 `Box` 底部 `Surface(surface)` + `navigationBarsPadding()` + `imePadding()` 内；文案仍是“保存档案并继续面谈 / 下一步”。
- 删除私有卡片与手写标题；保留 `ExamDateField`、`StepDots`、`StepTarget/StepRhythm/StepFacts` 的题目与校验。

- [ ] **Step 2: AI 面谈页**

- 顶部 `SubPageHeader`（标题随 `mode` 变化，保留现有“AI 行程小助手 / AI 备考面谈”文案，`meta` 用现有副标题）+ 右侧 `StatusPill("已答 $answered 轮", PillTone.NEUTRAL)`（`answered > 0` 时）。
- 消息气泡：用户 `primaryContainer`，AI `surface` + 1dp `outlineVariant` 描边，圆角 16；正文 `bodyMedium`。
- `QuickAnswers` 内部改为 `OptionChip`，保留原 `FlowRow` 与 `enabled` 判断：

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAnswers(options: List<String>, enabled: Boolean, onOption: (String) -> Unit) {
    if (!enabled || options.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            OptionChip(text = option, onClick = { onOption(option) })
        }
    }
}
```

- 底部输入条 `imePadding()`；发送按钮用 `IconButton` + `AppIcons.Send`。
- 不改动对话状态机与重试逻辑；`AdjustmentCard` 的调用参数与内嵌逻辑不动，视觉在 Step 5 单独处理。

- [ ] **Step 3: 规划文档页**

从「装饰性长文」改为「排版阅读页」。数据加载、`missing` / `regenerate` 分支、`md()` 解析与 DTO 字段全部不动，只改视觉与容器。

1. 根容器与顶部：
   - 删除整页 `Brush.verticalGradient(...)` 背景，根 `Box` 只保留 `Modifier.fillMaxSize()`。
   - 删除 `DocumentTopBar` 整段，改为 `SubPageHeader(title = document?.title ?: "全程规划", meta = "规划文档 · 只读", onBack = { navController.popBackStack() }, modifier = Modifier.statusBarsPadding())`，固定在 `Column` 顶部。

2. `DocumentBody` 重写为「固定目录 + 滚动正文」：

```kotlin
@Composable
private fun DocumentBody(document: PlanDocumentDto, bottomPadding: Dp) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val tocIndex by remember {
        derivedStateOf {
            (listState.firstVisibleItemIndex - 1).coerceIn(0, document.chapters.lastIndex.coerceAtLeast(0))
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (document.chapters.size > 1) {
            SegmentedChips(
                options = document.chapters.map { it.title },
                selectedIndex = tocIndex,
                onSelect = { index ->
                    scope.launch { listState.animateScrollToItem(index + 1) }
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = bottomPadding + 40.dp
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item(key = "hero") { DocumentHero(document) }
                items(
                    count = document.chapters.size,
                    key = { index -> "ch-$index" }
                ) { index ->
                    DocumentChapter(document.chapters[index])
                }
            }
        }
    }
}
```

首 item 是 hero，所以目录点击偏移是 `index + 1`；目录高亮跟随首个可见项自动更新（`derivedStateOf` 需要新增 import）。

3. 加载态改为骨架，不再整屏转圈：

```kotlin
Column(
    Modifier
        .fillMaxWidth()
        .weight(1f)
        .padding(horizontal = 20.dp, vertical = 16.dp)
) {
    SkeletonBlock(height = 24.dp)
    Spacer(Modifier.height(16.dp))
    repeat(2) {
        SkeletonBlock()
        Spacer(Modifier.height(10.dp))
    }
    SkeletonBlock(height = 18.dp)
    Spacer(Modifier.height(10.dp))
    repeat(4) {
        SkeletonBlock()
        Spacer(Modifier.height(10.dp))
    }
}
```

4. `DocumentHero` 去除装饰：删除 badge 胶囊、渐变标题、科目标签胶囊、`StatCell` 小卡；保留文案与数据：

```kotlin
@Composable
private fun DocumentHero(document: PlanDocumentDto) {
    val hero = document.hero
    Column(Modifier.fillMaxWidth()) {
        Text(
            heroTitle(hero.titleLead, hero.titleAccent, hero.titleTail, MaterialTheme.colorScheme.primary),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (hero.subtitle.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                md(hero.subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (hero.subjects.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                hero.subjects.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        if (hero.stats.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)
            ) {
                hero.stats.forEach { stat ->
                    MetricTile(value = stat.value, label = stat.label, compact = true)
                }
            }
        }
    }
}
```

`heroTitle` 增加 `accentColor: Color` 参数，把 `SpanStyle(brush = Brush.linearGradient(...))` 改为 `SpanStyle(color = accentColor)`。

5. 章节与块：

- `DocumentChapter`：章节号 `Text(chapter.no, style = labelLarge, color = primary, fontFamily = FontFamily.Monospace)`；章节标题 `titleLarge`；intro `bodyMedium.copy(lineHeight = 24.sp)`；删除 `fontWeight` 与 `ExtraBold`。
- `ParagraphBlock` / `CalloutBlock`：正文统一 `bodyMedium.copy(lineHeight = 24.sp)`；callout 保留左侧 3dp 主色竖条 + `primaryContainer.copy(alpha = 0.5f)` 底色，删除自写圆角。
- `CardBlock`：删除 `Surface`、`surfaceContainerHigh`、`shadowElevation` 与 `card.icon` 渲染（服务端 icon 是 emoji）；改为纯 `Column(Modifier.fillMaxWidth())`：标题 `titleMedium`、副标题 `labelMedium` primary、要点 `bodySmall`。
- `TableBlock` / `TableCell`：表头底色改 `insetSurfaceColor()`，表格外框 `RoundedCornerShape(14.dp)` + 1dp `outlineVariant`；表头文字 `labelSmall`，删除 `fontWeight` 与 `letterSpacing`。
- 删除 `chipColor` 函数与 `PlanTeal`、`PlanBg`、`Brush` 相关 import（`PlanAccent` 仍被表格分隔线使用，保留）。

6. 错误卡：

```kotlin
@Composable
private fun ErrorCard(
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
    detail: String? = null,
    busy: Boolean = false,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .padding(20.dp),
        contentPadding = PaddingValues(20.dp)
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Doc,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        detail?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = if (busy) "正在重写这份规划文档…" else primaryLabel,
            onClick = onPrimary,
            enabled = !busy,
            loading = busy
        )
        Spacer(Modifier.height(4.dp))
        TextAction(
            text = "返回",
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
```

`ErrorCard` 是居中显示，调用处保持 `Box(contentAlignment = Alignment.Center)`；`AppCard` 的 `PaddingValues` 已 import。

不改动 markdown/HTML 渲染逻辑（`md()` 内的 `SpanStyle(fontWeight = Bold)` 属于正文解析，保留）。

- [ ] **Step 4: 历史计划页**

- 顶部 `SubPageHeader("历史计划", meta = "只读回看", onBack = { navController.popBackStack() })`。
- 列表用 `AppCard` + `ListRow(title = 版本名, subtitle = 日期, trailing = StatusPill("当前生效"/"已归档"))`。
- `EmptyState(compact = true)` 空态；`SkeletonBlock` 加载态。
- 点击进入只读文档的行为不变。

- [ ] **Step 5: 调整清单卡**

`AdjustmentCard.kt` 整体替换为下面的实现；`adjustmentChangeLine` 与函数签名（含 `tier`）保持不变，只换容器与按钮：

```kotlin
@Composable
internal fun AdjustmentCard(
    summary: String,
    tier: String,
    changes: List<AdjustmentChangeDto>,
    confirming: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onPreview: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier) {
        Text("调整清单", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        changes.take(8).forEach { change ->
            Text(adjustmentChangeLine(change), style = MaterialTheme.typography.bodySmall)
        }
        if (changes.size > 8) {
            Text(
                "…共 ${changes.size} 条变动，确认后可在计划页查看全部",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onPreview != null) {
            TextAction(
                text = "查看整页预览",
                onClick = onPreview,
                modifier = Modifier.align(Alignment.Start)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(
                text = "重说一次",
                onClick = onDismiss,
                enabled = !confirming,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = "确认调整",
                onClick = onConfirm,
                enabled = !confirming && changes.isNotEmpty(),
                loading = confirming,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
```

- [ ] **Step 6: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanSetupScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanDocumentScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanHistoryScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AdjustmentCard.kt
git commit -m "refactor(ui): align plan subpages with header and row system"
```

---

## Task 13: 账号 / 学霸 / 白名单

**Files:**
- Modify: `ui/account/AccountScreen.kt`
- Modify: `ui/supermode/SuperModeScreen.kt`
- Modify: `ui/supermode/AppPickerScreen.kt`

**Interfaces:**
- Keeps: 登录/注册/登出/注销、设备登出、学霸模式开关与密码、白名单勾选保存。

- [ ] **Step 1: 账号页**

- `SubPageHeader(title = "账号", meta = "多设备共用同一份计划与进度", onBack = { navController.popBackStack() })`。
- 未登录：`AppCard` 表单（用户名、密码、邮箱、记住我、协议勾选 `ConsentRow`）；底部并排 `PrimaryButton("登录")` 与 `SecondaryButton("注册")`，各 `weight(1f)`，保留原有 enabled 条件与 `vm.login` / `vm.register` 调用；保留 `PolicyLinksRow`。
- 已登录：`AppCard` 当前账号（头像、用户名、登录状态）+ `RowGroup`（登录设备）+ `SecondaryButton("退出登录", onClick = { vm.logout() }, danger = true)` + `TextAction("注销账号", danger = true)`。
- 五个 `SectionCard` 全部替换为 `AppCard`/`RowGroup`；不改变 `CloudSyncViewModel` 调用。
- 注销两步确认保留。

- [ ] **Step 2: 学霸模式页**

- `SubPageHeader("学霸模式", meta = "专注期间拦截非白名单应用", onBack = { navController.popBackStack() })`。
- 状态卡：`AppCard`，开启/关闭文案 + 说明 + 主按钮。
- 分组：严格等级、悬浮窗拦截、拦截权限、应用白名单、紧急退出密码；每项用 `SectionCard` 的位置改为 `AppCard` + `SectionHeader`，行用 `ListRow` / `SwitchRow`。
- 删除页内私有胶囊 `Surface`、手写圆角与 `tonalElevation`；状态徽标用 `StatusPill`，操作按钮用 `PrimaryButton` / `SecondaryButton`。
- 保留所有权限跳转、密码设置与确认弹窗。

- [ ] **Step 3: 白名单选择页**

- `SubPageHeader("选择允许的应用", meta = "仅学霸模式生效", onBack = { navController.popBackStack() })`。
- 搜索框 + `LazyColumn` 应用行（图标、名称、`Switch`）；行高 ≥56，行容器用 `ListRow` 的 `trailing` 放 `Switch`，不再自绘卡面与阴影。
- 底部“保存”固定按钮 `PrimaryButton`，`navigationBarsPadding()`。
- 不改动已选集合与保存回调。

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/account/AccountScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/supermode/SuperModeScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/supermode/AppPickerScreen.kt
git commit -m "refactor(ui): unify account and super-mode subpages"
```

---

## Task 14: 登录与首次引导

**Files:**
- Modify: `ui/auth/LoginScreen.kt`
- Modify: `ui/onboarding/OnboardingScreen.kt`

**Interfaces:**
- Keeps: `LoginViewModel`、`OnboardingViewModel`、所有校验与提交逻辑、题目与答案映射。

- [ ] **Step 1: 登录页**

- 全屏 `BrandGradient` 背景；顶部 Logo + “研钟” + 一句副标题，白色；标题用 `headlineSmall`，不再写 `fontWeight`。
- 表单 `AppCard` 居中，最大宽 440dp；内部：登录/注册切换（`SegmentedChips`）、用户名/密码/确认/邮箱输入框（保留现有 `AuthTextField` 校验与键盘动作）、错误文案 12sp、`PrimaryButton`。
- 删除 `tonalElevation = 2.dp` / `shadowElevation = 4.dp` / 私有渐变 / 页面内所有 `fontWeight`、`fontSize` 覆盖（`AuthTextField` 内部属于输入组件，保留）。
- 服务器设置入口保留原位置与弹窗。

- [ ] **Step 2: 首次引导**

- 顶部：步骤指示 `Text("第 x / y 步")` + `ProgressLine`；无返回时省略 `SubPageHeader` 的 `onBack`。
- 每题一张 `AppCard`：题目标题 `titleMedium`，说明 `bodySmall`；题型映射：
  - 单选 → 可勾选 `ListRow`（trailing = `CompletionCircle` 或 `Check`）；
  - 多选 → 带 `Check` 的可勾选行；
  - 分档（每日学习时长）→ `SegmentedChips`；
  - 文本 → `OutlinedTextField`。
- 底部主按钮 `PrimaryButton` 固定在安全区，`imePadding()`。
- 删除页面内 `headlineLarge` / `headlineMedium` / `fontWeight` / `fontSize` 手写覆盖与旧步骤卡私有实现；完成态标题用 `titleLarge`，说明 `bodySmall`。
- 保留全部题目、分支与提交逻辑。

- [ ] **Step 3: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/auth/LoginScreen.kt \
        YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingScreen.kt
git commit -m "refactor(ui): bring login and onboarding onto the shared system"
```

---

## Task 15: 清扫与验收

**Files:**
- Modify: `ui/theme/Components.kt`（删除 `SectionCard`）
- Delete: `ui/theme/SubjectArtwork.kt`（确认无引用后）
- Any page file with leftover literals

**不要删除 `ui/stats/WeeklyReport.kt`**：`StatsViewModel` 依赖其中的 `WeeklyReport` / `buildWeeklyReport`，它不属于 UI 清扫范围。

- [ ] **Step 1: 全仓扫描遗留**

```bash
cd F:/kaoyan-app-prd/YanZhong/app/src/main/java/com/yanzhong/app
grep -rnE "headlineLarge|displayMedium|displaySmall|shadowElevation|tonalElevation|FilterChip|🍅|RoundedCornerShape\(999\.dp\)|SectionCard|fontWeight =|fontSize =" ui MainActivity.kt
```

期望：

- `shadowElevation` 只剩 `ui/nav/AppNav.kt` 的 `RunningTimerPill`（6dp，悬浮层例外）；页面文件为零。
- `tonalElevation` 为零（Material 组件内部参数不算，页面调用处不得出现）。
- `headlineLarge` 在页面文件为零；`Type.kt` 保留槽位定义。
- `FilterChip` 为零（登录/引导里的选择若仍用 FilterChip，必须换 `SegmentedChips` 或 `ListRow`）。
- `🍅` 为零。
- `RoundedCornerShape(999.dp)` 只在 `ui/theme/Components.kt`、`ui/theme/Controls.kt`、`ui/stats/Charts.kt`（图表内圆角条）与 `ui/nav/AppNav.kt` 的 `RunningTimerPill`。
- `fontWeight =` / `fontSize =` 只在 `Type.kt`、`ui/theme/*` 组件、`ui/stats/Charts.kt`（坐标轴 9sp）、`ui/legal/PolicyLinks.kt`（共享组件）与 `PlanDocumentScreen.kt` 的 `md()` 行内加粗；页面文件其余位置为零。
- `SectionCard` 在页面为零。

- [ ] **Step 2: 清理扫描结果与死代码**

- 逐条处理 Step 1 报出的页面文件遗留（本应已由各页面任务清零，这里是最后兜底）：`headlineLarge` → `headlineSmall`（PageTitle 22/28）或 `titleLarge`，`tonalElevation`/`shadowElevation` → `AppCard` 或纯 `Surface(color = surface)`，`FilterChip` → `SegmentedChips`/`ToggleChip`，`fontWeight`/`fontSize` → 删除并改用字号令牌。例外文件按 Step 1 的清单保留，不改。
- 删除 `Components.kt` 中的 `SectionCard` 及其不再使用的 `ColumnScope`/`PaddingValues` import（若其他组件仍用则保留）。
- `grep -rn "SubjectArtwork" ui`：若无引用，删除 `ui/theme/SubjectArtwork.kt`；若仍被其他页面使用，保留并在提交说明中注明。
- `grep -rnE "WeekReportCard|MonthReportCard" ui`：应为零（这两个 Composable 已随 Task 10 删除）；`ui/stats/WeeklyReport.kt` 保留不动。
- `grep -rnE "QuickStartBar|EmptyTaskHint" ui`：应为零。

- [ ] **Step 3: 编译与单测**

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL；现有单元测试全部通过。

- [ ] **Step 4: Lint**

```bash
./gradlew :app:lintDebug
```

Expected: BUILD SUCCESSFUL，无 `NewApi` fatal。

- [ ] **Step 5: 验收：业务层零改动**

用开工时记下的基线比对，不把开工前已有改动算进来：

```bash
cd F:/kaoyan-app-prd
BASE=$(cat /tmp/ui-refresh-base.sha)
git diff --name-only "$BASE" -- YanZhong/app/src/main/java/com/yanzhong/app/data \
  YanZhong/app/src/main/java/com/yanzhong/app/service \
  YanZhong/app/src/main/java/com/yanzhong/app/timer \
  YanZhong/app/src/main/java/com/yanzhong/app/util
git status --porcelain -- YanZhong/app/src/main/java/com/yanzhong/app \
  > /tmp/ui-refresh-final.status
diff /tmp/ui-refresh-base.status /tmp/ui-refresh-final.status
```

Expected: 第一条命令的输出只包含开工前就已存在的改动（若基线是干净的则为空）；`diff` 无输出，表示工作区状态与基线一致（本计划的改动都已提交）。若 `data/`、`service/`、`timer/`、`util/` 出现在本计划提交的 diff 中，回退该改动。

- [ ] **Step 6: 截图抽查（环境允许时）**

若能启动模拟器且有可用登录账号：

```bash
emulator -avd Pixel_6a -no-snapshot &
adb wait-for-device
./gradlew :app:installDebug
adb shell am start -n com.yanzhong.app/.MainActivity
# 逐页操作后
adb exec-out screencap -p > /tmp/home.png
```

至少核对：底栏 4 tab + 中央 FAB 不遮挡“专注”标签；今日页无顶部渐变；计划页无示例计划入口；统计页无长口径正文；我的页无紫色整页渐变；深色模式渐变卡白字可读。

若模拟器/账号不可用，在最终报告中明确写“未完成模拟器截图核对”，不要声称已通过视觉验收。

- [ ] **Step 7: 提交**

```bash
git add -A YanZhong/app/src/main/java/com/yanzhong/app/ui \
        YanZhong/app/src/main/java/com/yanzhong/app/MainActivity.kt
git commit -m "chore(ui): remove legacy section card and dead visual code"
```

---

## Task 16: 最终自检

**Files:** 无新增；只读检查与必要的小修。

- [ ] **Step 1: 对照 spec 验收标准逐条核对**

1. `./gradlew :app:compileDebugKotlin` 通过。
2. `./gradlew :app:testDebugUnitTest` 通过。
3. 底栏 4 tab + 中央 FAB；无禁用项；360dp 与 1.3× 字体不裁切。
4. 五个主 tab 与所有二级页使用 `PageHeader/SubPageHeader`。
5. 页面文件无 `shadowElevation` / `tonalElevation` / `RoundedCornerShape(999.dp)` / 手写 `fontWeight`。
6. 页面文件无 emoji、无 `FilterChip`。
7. 深浅两套截图（环境允许时）。
8. 功能回归：任务完成与撤销、左滑顺延、开始/暂停/结束专注、计划项勾选与撤销、主题切换、统计范围切换、登录/退出、学霸模式开关。
9. `data/`、`service/`、`timer/`、`util/` 零 diff；路由常量与 ViewModel 无行为变化。

- [ ] **Step 2: 报告未完成项**

若截图或真机回归因环境缺失无法完成，必须在最终回复中逐条列出，不得用“应该没问题”代替。

- [ ] **Step 3: 提交（仅当 Step 1 有小修）**

```bash
git add -A YanZhong/app/src/main/java/com/yanzhong/app/ui
git commit -m "fix(ui): final acceptance touch-ups"
```
