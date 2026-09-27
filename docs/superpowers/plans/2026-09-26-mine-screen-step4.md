# Step 4 个人界面重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变服务端接口、Room 表结构和现有设置操作的前提下，将 Android `MineScreen` 重组为学习档案、账号与安全、偏好设置三个主区块，并接入与统计页一致的真实本地学习数据。

**Architecture:** 以 `pomodoro_session` 的有效会话为唯一统计来源，新增与 UI 无关的纯 Kotlin 学习档案模型和聚合函数。`StudyRepository` 继续作为 Room 边界，`MineViewModel` 组合会话、科目和设置 Flow，`MineScreen` 只消费 `MineUiState` 并保留现有导出、注销、科目、备份和偏好操作。最近 7 天和峰值时段由共享聚合层计算，避免在 Composable 中直接访问数据库或复制统计口径。

**Tech Stack:** Kotlin, Jetpack Compose, Room, Kotlin Coroutines/Flow, JUnit 4, Gradle Android plugin。

---

## 文件变更地图

- Create: `YanZhong/app/src/main/java/com/yanzhong/app/data/stats/LearningProfile.kt`
  - 定义学习档案快照、科目投入项、每日趋势项和纯聚合函数。
- Create: `YanZhong/app/src/test/java/com/yanzhong/app/data/stats/LearningProfileTest.kt`
  - 覆盖有效会话过滤、时长、活跃日、连续打卡、科目、峰值小时和七日趋势。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/timer/PomodoroEngine.kt`
  - 为现有连续打卡函数增加可注入的本地日期参数，保留旧调用点行为。`StudyRepository` 已提供所需 Flow，无需修改。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineViewModel.kt`
  - 扩展 `MineUiState`，组合学习档案状态，并继续保留现有设置、数据和账号方法。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt`
  - 按学习档案、账号与安全、偏好设置、数据与其他重组布局，迁移现有控件与弹窗行为。
- Modify: `docs/superpowers/specs/2026-09-26-mine-screen-step4-design.md`
  - 实现完成后补充实际验证结果，不在实现前修改设计决策。
- Create: `docs/superpowers/verification/2026-09-26-mine-screen-step4-verification.md`
  - 记录构建、单元测试、回归检查和已知环境限制。

### Task 1: 建立共享学习档案模型

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/data/stats/LearningProfile.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/timer/PomodoroEngine.kt`

- [ ] **Step 1: 定义与 UI 无关的数据类型**

定义以下类型，字段名称在后续 ViewModel 和 Composable 中保持不变：

```kotlin
data class LearningProfile(
    val totalFocusMin: Int = 0,
    val activeDays: Int = 0,
    val streakDays: Int = 0,
    val perSubject: List<SubjectFocus> = emptyList(),
    val peakHour: Int? = null,
    val recentDaily: List<DailyFocus> = emptyList()
)

data class SubjectFocus(
    val subjectId: Long,
    val name: String,
    val colorArgb: Long,
    val totalFocusMin: Int
)

data class DailyFocus(
    val date: LocalDate,
    val totalFocusMin: Int
)
```

`SubjectFocus` 对已删除或找不到的科目使用名称 `"未分类"` 和稳定的中性颜色，不丢弃有效会话；没有 `subjectId` 的会话也归入 `"未分类"`，以保证总时长与科目分布可解释。

- [ ] **Step 2: 定义显式时间参数的聚合入口**

新增纯函数，避免函数内部读取系统时间导致测试不稳定：

```kotlin
fun buildLearningProfile(
    sessions: List<PomodoroSessionEntity>,
    subjects: List<SubjectEntity>,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault()
): LearningProfile
```

实现规则：只保留 `valid == true` 的会话；`totalFocusMin` 为 `durationMin` 总和；`activeDays` 为有效会话对应的本地日期去重数；`streakDays` 调用 `com.yanzhong.app.timer.streakDays(activeDays, now.atZone(zone).toLocalDate())`；科目按总分钟降序、同分钟按名称升序；峰值小时按累计分钟降序、同值按小时升序取第一项；`recentDaily` 固定返回 `now` 所在本地日期向前 6 天到今天的 7 项，日期升序且缺失日为 0。

- [ ] **Step 3: 给连续打卡函数注入可测试日期**

在 `PomodoroEngine.kt` 将签名改为 `fun streakDays(activeDays: List<String>, today: LocalDate = LocalDate.now(ZoneId.systemDefault())): Int`，删除函数体内原有的 `val today = ...`，其余循环逻辑保持原样。原 `StatsViewModel` 和其他单参数调用无需改动，聚合测试可固定 `today`。

- [ ] **Step 4: 保持日期边界使用 `ZoneId`**

使用 `Instant.atZone(zone).toLocalDate()` 和 `LocalDateTime` 计算日期、小时，不使用固定 24 小时毫秒差来生成七日标签。这样夏令时地区仍按本地自然日工作，并与统计页的 SQLite `localtime` 语义一致。

- [ ] **Step 5: 完成纯函数编译前检查**

运行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

工作目录：`F:\kaoyan-app-prd\YanZhong`。

预期：任务成功，且新增文件不依赖 Compose、Android Context 或数据库实例。

- [ ] **Step 6: 提交共享模型**

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/data/stats/LearningProfile.kt YanZhong/app/src/main/java/com/yanzhong/app/timer/PomodoroEngine.kt
git commit -m "feat(app): add shared learning profile aggregation"
```

### Task 2: 为统计聚合补齐单元测试

**Files:**
- Create: `YanZhong/app/src/test/java/com/yanzhong/app/data/stats/LearningProfileTest.kt`

- [ ] **Step 1: 准备固定时区和固定当前时间**

使用 `ZoneId.of("Asia/Shanghai")` 和固定 `Instant`，通过实体工厂创建跨不同日期、不同小时、不同科目的会话。测试不得使用 `System.currentTimeMillis()` 或依赖运行机器时区。

- [ ] **Step 2: 编写空数据测试**

断言空会话返回：`totalFocusMin == 0`、`activeDays == 0`、`streakDays == 0`、`perSubject.isEmpty()`、`peakHour == null`，并且 `recentDaily` 有 7 项且每项 `totalFocusMin == 0`。

- [ ] **Step 3: 编写有效和无效会话测试**

构造两条有效会话和一条同日期的 `valid = false` 会话，断言总分钟、活跃日和峰值小时均不包含无效会话。额外断言分钟累计不会因同一天多条有效记录而增加活跃日数量。

- [ ] **Step 4: 编写科目和峰值小时测试**

构造至少两个科目和一个无科目会话，断言科目按投入分钟降序排列、同值按名称排序，且未分类投入不会改变总分钟。让两个小时各有多条记录，断言峰值小时使用累计分钟而不是单条最长会话。

- [ ] **Step 5: 编写七日趋势和连续天数测试**

固定今天及前六天，构造只覆盖其中三天的有效会话，断言七日结果日期升序、长度为 7、空缺日期为 0。再分别覆盖“今天连续”“昨天连续”“断档”三种输入，断言结果与 `streakDays` 现有算法一致。

- [ ] **Step 6: 运行单元测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.yanzhong.app.data.stats.LearningProfileTest"
```

预期：所有新增测试通过。若当前 Windows 用户路径导致 Gradle worker 无法启动，记录具体失败信息，不改变测试断言来绕过环境问题，并使用 ASCII `GRADLE_USER_HOME` 重试。

- [ ] **Step 7: 提交测试**

```powershell
git add YanZhong/app/src/test/java/com/yanzhong/app/data/stats/LearningProfileTest.kt
git commit -m "test(app): cover learning profile aggregation"
```

### Task 3: 接入 MineViewModel

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineViewModel.kt`

- [ ] **Step 1: 复用现有仓库 Flow**

`StudyRepository` 的“统计”区域已经提供以下接口，直接复用：

```kotlin
fun observeSessions(): Flow<List<PomodoroSessionEntity>>
fun observeSubjects(): Flow<List<SubjectEntity>>
```

无需修改 `StudyRepository` 或 `SessionDao`；仅在 ViewModel 中订阅这两个现有 Flow。

- [ ] **Step 2: 扩展 `MineUiState`**

在原有字段基础上增加：

```kotlin
val learningProfile: LearningProfile = LearningProfile()
```

保留 `settings`、`subjects`、`focusDays`、`streak`，以避免当前 `MineScreen` 迁移期间破坏已有调用；迁移完成后可让顶部标签从 `learningProfile` 读取，但不删除兼容字段，除非全局搜索确认没有其他调用。

- [ ] **Step 3: 组合会话、科目和设置状态**

将 `settingsRepo.settings`、`repo.observeSubjects()`、`repo.observeSessions()` 用 `combine` 组合，调用：

```kotlin
buildLearningProfile(
    sessions = sessions,
    subjects = subjects,
    now = Instant.ofEpochMilli(TimeUtils.now()),
    zone = ZoneId.systemDefault()
)
```

`focusDays` 和 `streak` 从共享 profile 写入，避免 Mine 页与共享学习档案出现两套活跃日状态。`stateIn` 的默认值继续使用 `MineUiState()`，保留 `SharingStarted.WhileSubscribed(5000)`。

- [ ] **Step 4: 回归检查现有操作方法**

确认 `setTheme`、`setVibration`、`setSound`、`setSilent`、`setWeeklyGoal`、`setDailyPomodoroGoal`、`setAutoChain`、`setContinuousFocus`、`setCurrentPlan`、`savePlans`、科目方法、备份导入方法、账号导出和注销方法均未改变签名和副作用顺序。账号注销仍只在服务端成功回调中清理 `TokenStore`。

- [ ] **Step 5: 编译并提交状态层**

运行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

预期：成功。

提交：

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineViewModel.kt
git commit -m "feat(app): expose learning profile to mine state"
```

### Task 4: 重组 MineScreen 三个主区块

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt`

- [ ] **Step 1: 保留页面外壳和沉浸式头部**

继续使用现有 `Box`、最大宽度约束、夜空头部、白色滚动内容面板、`SectionCard`、`PillTag` 和 `AppIcons`。顶部继续显示用户概览，并将“共专注 X 天”“连续打卡 X 天”从 `state.learningProfile.activeDays` 和 `state.learningProfile.streakDays` 读取。

- [ ] **Step 2: 新增学习档案区**

在正文第一个 `SectionCard` 中渲染四项摘要：累计专注时长、活跃天数、连续打卡、峰值时段。分钟格式化复用 `TimeUtils.formatHours`；峰值为空显示“暂无峰值时段”。使用现有布局组件和 `LazyColumn`，每个指标的文本允许换行，窄屏不得依赖固定宽度。

- [ ] **Step 3: 渲染科目投入和七日趋势摘要**

在学习档案区内增加科目投入列表，最多显示前 3 项并显示“未分类”或“暂无科目投入”的空状态；增加最近 7 天轻量摘要，使用日期短标签和分钟值，不引入新的完整图表依赖。七日数据来自 `state.learningProfile.recentDaily`，不得在 Composable 中重新分组会话。

- [ ] **Step 4: 迁移账号与安全区**

将现有账号状态、服务端数据导出、账号注销和相关确认弹窗集中到“账号与安全”区。保留系统文件选择器、二次确认、Toast 文案和错误处理；不得把本地备份导入误归为服务端账号操作。

- [ ] **Step 5: 迁移偏好设置区**

将主题、番茄方案、声音与震动、静默模式、连续专注、自动接力、周目标、每日番茄目标集中到“偏好设置”区。所有控件继续调用原 `MineViewModel` 方法，不修改设置存储键或默认值。

- [ ] **Step 6: 保留数据与其他次级区**

将科目管理、本地备份与导入、关于等低频入口放入“数据与其他”区，保留现有弹窗状态、批量科目输入、方案编辑、目标编辑和本地导入导出逻辑。不要新增深层导航；学习档案摘要可保持只读。

- [ ] **Step 7: 检查布局和可访问性**

为新增指标和操作补充清晰的 `contentDescription` 或语义文本；检查长科目名、无科目、无会话、窄屏和大屏下不出现横向溢出、遮挡或固定高度截断。不要引入新的图片资源或主题系统。

- [ ] **Step 8: 编译并提交 UI**

运行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

预期：成功且无新增 Kotlin 编译错误。

提交：

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt
git commit -m "feat(app): reorganize mine screen into profile sections"
```

### Task 5: 验证、记录和收尾

**Files:**
- Create: `docs/superpowers/verification/2026-09-26-mine-screen-step4-verification.md`
- Modify: `docs/superpowers/specs/2026-09-26-mine-screen-step4-design.md`

- [ ] **Step 1: 运行 Android 编译和相关测试**

在 `F:\kaoyan-app-prd\YanZhong` 运行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest --tests "com.yanzhong.app.data.stats.LearningProfileTest"
```

若单测因非 ASCII 用户目录再次出现 `GradleWorkerMain` 错误，设置：

```powershell
$env:GRADLE_USER_HOME = 'C:\gradle-home'
```

然后重跑单测；记录最终结果和环境限制，不把失败伪装为通过。

- [ ] **Step 2: 做源码级行为回归检查**

检查 `MineViewModel` 和 `MineScreen` 的现有调用点，确认账号导出、账号注销、主题、声音、震动、专注模式、科目管理、备份导入、方案和目标编辑入口仍存在且参数传递不变。检查 `StatsViewModel` 未被改写，学习档案仅复用其有效会话和连续天数口径。

- [ ] **Step 3: 记录验收结果**

验证文档至少包含：变更文件、执行命令、测试结果、空数据结果、有效/无效会话结果、七日趋势结果、构建结果、是否完成窄屏/大屏人工检查，以及仍受环境限制的项目。只有所有 Step 4 完成标准满足时才写“完成”。

- [ ] **Step 4: 更新设计文档的实施记录**

在设计文档末尾增加“实施记录”小节，写入实际完成日期、提交范围、验证命令和未解决的非本功能环境限制；不得修改已确认的产品范围、统计口径或非目标。

- [ ] **Step 5: 最终检查工作树**

运行：

```powershell
git status --short
git log -5 --oneline
```

预期：只剩用户原有未提交改动或本 Step 4 明确的文档记录变更；不出现临时脚本、构建产物或调试文件。

- [ ] **Step 6: 提交验证记录**

```powershell
git add docs/superpowers/verification/2026-09-26-mine-screen-step4-verification.md docs/superpowers/specs/2026-09-26-mine-screen-step4-design.md
git commit -m "docs(app): record mine screen step4 verification"
```

## 验收清单

- [ ] `MineScreen` 已按学习档案、账号与安全、偏好设置重组，并保留数据与其他次级区。
- [ ] 学习档案展示累计专注、活跃天数、连续打卡、科目投入、峰值时段和最近 7 天趋势。
- [ ] 所有学习统计只计入 `valid == true` 的 `PomodoroSessionEntity`。
- [ ] 日期、小时和七日窗口使用设备 `ZoneId`，且七日结果包含 0 分钟日期。
- [ ] Mine 与 Stats 使用同一连续打卡函数和有效会话语义。
- [ ] 账号导出、账号注销、主题、番茄、声音、震动、专注模式、科目和备份导入行为未回归。
- [ ] 学习档案聚合单元测试覆盖空数据、无效会话、科目排序、峰值小时、七日补零和连续天数。
- [ ] Android debug 编译通过；单测结果已如实记录。
- [ ] 相关文档和验证记录已更新，未生成临时文件。

## 提交边界

建议按以下四个可回滚提交推进：

1. `feat(app): add shared learning profile aggregation`
2. `test(app): cover learning profile aggregation`
3. `feat(app): expose learning profile to mine state`
4. `feat(app): reorganize mine screen into profile sections`
5. `docs(app): record mine screen step4 verification`

每个提交完成后先运行对应的最小编译或测试，再进入下一任务；不得在验证失败时标记任务完成。