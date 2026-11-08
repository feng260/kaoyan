# 第 3 步自动铺满七天空闲 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让新手引导第 3 步（「正式科目与真实空闲」）一进入就按用户第 2 步选的时段预设铺满七天空闲窗口，从而在上传课表后不再被「七天里至少给一天加一段空闲时段」拦住。

**Architecture:** 纯 App 端改动，服务端零改动。新增一个纯函数 `seedAvailability(studyWindows, existing)`，把 `STUDY_WINDOWS`（时段名）经 `WINDOW_CLOCK` 映射成七天各自的 `TimeWindow` 列表；仅在 `existing` 为空时生效，绝不覆盖用户已填内容。在 `next()` 与 `loadInternal(manage = true)` 两个进入第 3 步的入口调用。因为课表 OCR 的结果只写 `fixedCommitments`，而服务端 `netAvailableMinutes()` 本就是「窗口减占用」，铺上窗口后课程会自动挖成洞。

**Tech Stack:** Kotlin、Jetpack Compose、JUnit 4（`app/src/test/` JVM 单测，无 Android runtime 依赖）、Gradle Wrapper。

**Spec:** `docs/superpowers/specs/2026-11-08-timetable-availability-and-week-parity-design.md`（本计划实现其中「4.1 A 部分」）

## Global Constraints

- 所有颜色、字号、圆角必须从 `com.yanzhong.app.ui.theme.*` 引用，业务 Screen 里禁止硬编码 `Color(...)` 或 `Xx.sp`。
- 面向用户的文案用「备考者」口径，不用「考生」；仅考研特有时点明考研。文案风格照抄同文件既有句子：口语、短句、说清「为什么」。
- 严禁覆盖用户已填的 `availability`：`existing.isNotEmpty()` 时原样返回。
- 时段→钟点的唯一来源是 `WINDOW_CLOCK`（`OnboardingViewModel.kt:116-122`），不得在别处重写钟点常量。
- 本机 Gradle 只能走 PowerShell + `.\gradlew.bat`，并整串传入 `'-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK'`；`./gradlew` 在 Git Bash 下会报 `cd: ./gradlew: Not a directory`。
- 不得提交、推送、部署，除非产品负责人明确批准。

---

### Task 1: `seedAvailability()` 纯函数

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt`（在 `WINDOW_CLOCK` 之后、`TimeWindow` 之前，即 `:122` 与 `:124` 之间插入）
- Test: `YanZhong/app/src/test/java/com/yanzhong/app/ui/onboarding/OnboardingAvailabilitySeedTest.kt`（新建）

**Interfaces:**
- Consumes: `STUDY_WINDOWS: List<String>`（`com.yanzhong.app.data.remote.Api.kt:143`，值为 `["早晨","上午","下午","晚上","深夜"]`）；`WINDOW_CLOCK: Map<String, Pair<String,String>>`（同文件 `:116-122`，`internal`）；`TimeWindow(start: String, end: String)`（同文件 `:125-127`）。
- Produces:
  ```kotlin
  internal fun seedAvailability(
      studyWindows: Set<String>,
      existing: Map<Int, List<TimeWindow>>
  ): Map<Int, List<TimeWindow>>
  ```
  返回 `Map<Int(weekday 1..7), List<TimeWindow>>`。`existing` 非空 → 原样返回；`studyWindows` 与 `STUDY_WINDOWS` 交集为空 → 回落到 `DEFAULT_SEED_WINDOWS`；否则七天同构，每天按 `STUDY_WINDOWS` 的固定顺序映射 `WINDOW_CLOCK`。

- [ ] **Step 1: 写失败的测试**

新建 `YanZhong/app/src/test/java/com/yanzhong/app/ui/onboarding/OnboardingAvailabilitySeedTest.kt`：

```kotlin
package com.yanzhong.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingAvailabilitySeedTest {

    @Test
    fun `只勾晚上时七天各铺一段晚上钟点`() {
        val grid = seedAvailability(setOf("晚上"), emptyMap())
        assertEquals((1..7).toList(), grid.keys.sorted())
        for (day in 1..7) {
            assertEquals(listOf(TimeWindow("19:00", "22:00")), grid[day])
        }
    }

    @Test
    fun `窗口顺序跟着时段预设走而不是 Set 迭代序`() {
        // 故意用乱序 Set 传入:结果必须是 上午 → 下午 → 晚上
        val grid = seedAvailability(linkedSetOf("晚上", "上午", "下午"), emptyMap())
        assertEquals(
            listOf(
                TimeWindow("08:30", "11:30"),
                TimeWindow("14:00", "17:30"),
                TimeWindow("19:00", "22:00")
            ),
            grid[3]
        )
    }

    @Test
    fun `全部时段都勾上时早晨到深夜五段齐全`() {
        val grid = seedAvailability(setOf("早晨", "上午", "下午", "晚上", "深夜"), emptyMap())
        assertEquals(
            listOf(
                TimeWindow("06:30", "08:00"),
                TimeWindow("08:30", "11:30"),
                TimeWindow("14:00", "17:30"),
                TimeWindow("19:00", "22:00"),
                TimeWindow("22:30", "23:59")
            ),
            grid[7]
        )
    }

    @Test
    fun `用户已经填过的网格一根手指都不动`() {
        val filled = mapOf(2 to listOf(TimeWindow("09:00", "10:00")))
        val grid = seedAvailability(setOf("上午", "下午", "晚上"), filled)
        assertEquals(filled, grid)
    }

    @Test
    fun `时段交集为空时回落到默认三段`() {
        // 第 2 步的闸门保证不会为空,这里是防御:别铺出一张空网格把人又挡回去
        val grid = seedAvailability(setOf("午休"), emptyMap())
        assertEquals(
            listOf(
                TimeWindow("08:30", "11:30"),
                TimeWindow("14:00", "17:30"),
                TimeWindow("19:00", "22:00")
            ),
            grid[1]
        )
        assertEquals(7, grid.size)
    }

    @Test
    fun `铺出来的每段窗口都符合校验器的格式与先后要求`() {
        // addAvailability 用 normalizeClock 校验 HH:mm,且要求 from < to;
        // 铺出来的窗口必须天然满足,否则用户点下一步照样被拦
        val grid = seedAvailability(setOf("早晨", "上午", "下午", "晚上", "深夜"), emptyMap())
        val clock = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        grid.values.flatten().forEach { window ->
            assertTrue(window.start, clock.matches(window.start))
            assertTrue(window.end, clock.matches(window.end))
            assertTrue("${window.start} 应早于 ${window.end}", window.start < window.end)
        }
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest --tests 'com.yanzhong.app.ui.onboarding.OnboardingAvailabilitySeedTest' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```
Expected: 编译失败，`Unresolved reference: seedAvailability`。

- [ ] **Step 3: 写最小实现**

在 `OnboardingViewModel.kt` 的 `WINDOW_CLOCK` 定义之后（`:122` 的 `)` 之后）、`TimeWindow` 的注释（`:124`）之前插入：

```kotlin
/**
 * 时段交集为空时的兜底三段。
 * 第 2 步的闸门本来就要求至少勾一个时段,这里只是防御:
 * 万一铺出一张空网格,用户点下一步又会被同一个提示挡回去,那就白铺了。
 */
private val DEFAULT_SEED_WINDOWS = linkedSetOf("上午", "下午", "晚上")

/**
 * 进第 3 步时把空闲网格铺满七天。
 *
 * 为什么要铺:课表识别只往「固定占用」里写,而服务端算净空闲是「窗口 - 占用」
 * (document.ts 的 netAvailableMinutes)—— 只有占用、没有窗口,净空闲恒为 0。
 * 用户上传完课表看到「认到 12 段固定占用」,却仍被「至少给一天加一段空闲时段」拦住,
 * 就是因为这两个字段谁也不推导谁。铺上窗口之后,课表的课会自动挖成洞。
 *
 * 只在 [existing] 为空时生效:用户删改过的网格是他的真实答案,不能被预设覆盖回去。
 * 顺序按 [STUDY_WINDOWS] 而不是 Set 的迭代序 —— 勾「晚上、上午」和勾「上午、晚上」
 * 应该铺出一样的结果。
 */
internal fun seedAvailability(
    studyWindows: Set<String>,
    existing: Map<Int, List<TimeWindow>>
): Map<Int, List<TimeWindow>> {
    if (existing.isNotEmpty()) return existing
    val picked = STUDY_WINDOWS.filter { it in studyWindows && it in WINDOW_CLOCK }
    val names = picked.ifEmpty { STUDY_WINDOWS.filter { it in DEFAULT_SEED_WINDOWS } }
    val windows = names.mapNotNull { name ->
        WINDOW_CLOCK[name]?.let { (start, end) -> TimeWindow(start, end) }
    }
    if (windows.isEmpty()) return emptyMap()
    return (1..7).associateWith { windows }
}
```

无需新增 import：`OnboardingViewModel.kt:11` 已是 `import com.yanzhong.app.data.remote.*`（通配），`STUDY_WINDOWS` 与 `BriefAvailabilityDto` 都在其覆盖范围内。`WINDOW_CLOCK`、`TimeWindow`、`DEFAULT_SEED_WINDOWS` 均为同文件符号。

- [ ] **Step 4: 跑测试确认它通过**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest --tests 'com.yanzhong.app.ui.onboarding.OnboardingAvailabilitySeedTest' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```
Expected: 6 个测试全部 PASS，`BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt YanZhong/app/src/test/java/com/yanzhong/app/ui/onboarding/OnboardingAvailabilitySeedTest.kt
git commit -m "$(cat <<'EOF'
feat(onboarding): seed weekly free windows from chosen dayparts

课表识别只写固定占用,而净空闲是「窗口 - 占用」,导致用户上传完课表
仍被「至少给一天加一段空闲时段」拦住。进第 3 步时按时段预设铺满七天,
让课程自动挖洞;已填过的网格不覆盖。
EOF
)"
```

---

### Task 2: 接入两个进入第 3 步的入口

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt:259-274`（`next()`）
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt:219-240`（`loadInternal()` 的 EDITING 分支）
- Test: `YanZhong/app/src/test/java/com/yanzhong/app/ui/onboarding/OnboardingAvailabilitySeedTest.kt`（追加）

**Interfaces:**
- Consumes: Task 1 的 `seedAvailability(studyWindows: Set<String>, existing: Map<Int, List<TimeWindow>>): Map<Int, List<TimeWindow>>`；`ONBOARDING_STEPS: Int = 3`（同文件 `:106`）；`OnboardingUiState.availability`（`:50`）、`.studyWindows`（`:42`）。
- Produces: `next()` 与 `loadInternal(manage = true)` 进入 `step == ONBOARDING_STEPS - 1` 时，`OnboardingUiState.availability` 非空（除非用户此前已把七天全部删空并保存过）。

- [ ] **Step 1: 追加失败的测试（覆盖两个入口共用的判定）**

在 `OnboardingAvailabilitySeedTest.kt` 的类体内追加：

```kotlin
    @Test
    fun `服务端回填过的网格不被预设覆盖`() {
        // loadInternal 会先用档案里的 availability 回填,再走 seed;
        // 老账号已确认过的真实空闲必须原样保留
        val fromProfile = mapOf(
            1 to listOf(TimeWindow("07:00", "08:00")),
            6 to listOf(TimeWindow("09:00", "17:00"))
        )
        assertEquals(fromProfile, seedAvailability(setOf("上午", "下午", "晚上"), fromProfile))
    }

    @Test
    fun `第 2 步只勾早晨时不会混进别的时段`() {
        val grid = seedAvailability(setOf("早晨"), emptyMap())
        assertEquals(1, grid[1]!!.size)
        assertEquals(TimeWindow("06:30", "08:00"), grid[1]!![0])
    }
```

- [ ] **Step 2: 跑测试确认新增两条通过、且没有回归**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest --tests 'com.yanzhong.app.ui.onboarding.OnboardingAvailabilitySeedTest' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```
Expected: 8 个测试全部 PASS（这两条测的是 Task 1 已实现的纯函数语义，用来锁死「不覆盖已填」这条约束在接入后仍成立）。

- [ ] **Step 3: 改 `next()`**

把 `OnboardingViewModel.kt:259-274` 整段替换为：

```kotlin
    fun next() {
        val s = _ui.value
        val err = validate(s, s.step)
        if (err != null) {
            _ui.update { it.copy(message = err, messageIsError = true) }
            return
        }
        val to = (s.step + 1).coerceAtMost(ONBOARDING_STEPS - 1)
        // 上一屏已经勾过「最没底的几门」,这一屏的科目别再让用户重勾一遍;
        // 只在用户还没动过这一屏的科目时补齐,免得把他在这一屏的删改覆盖掉
        val subjects = if (to == ONBOARDING_STEPS - 1 && s.examSubjects.isEmpty())
            s.weakSubjects.take(MAX_EXAM_SUBJECTS).toSet() else s.examSubjects
        // 走进「真实空闲」这一屏就把网格按上一屏勾的时段铺满:
        // 课表识别只产出占用,而净空闲是「窗口 - 占用」,不铺窗口就永远是 0,
        // 用户传完课表照样被闸门拦住。已填过的不覆盖。
        val availability = if (to == ONBOARDING_STEPS - 1)
            seedAvailability(s.studyWindows, s.availability) else s.availability
        _ui.update {
            it.copy(
                step = to,
                examSubjects = subjects,
                availability = availability,
                message = "",
                messageIsError = false
            )
        }
    }
```

- [ ] **Step 4: 改 `loadInternal()` 的 EDITING 分支**

在 `OnboardingViewModel.kt:219-240` 的 `_ui.update { s -> s.copy(...) }` 里，把 `availability` 那一行（`:236-237`）替换为下面两行，其余字段原样保留：

```kotlin
                            // 主动来改档案时可能直接落在最后一题,这一屏的网格同样要铺满;
                            // 档案里回填过真实空闲的话,seedAvailability 会原样返回不覆盖
                            availability = seedAvailability(
                                p?.studyWindows?.toSet()?.takeIf { it.isNotEmpty() } ?: s.studyWindows,
                                p?.brief?.let { briefToGrid(it.availability) }?.takeIf { it.isNotEmpty() }
                                    ?: s.availability
                            ),
```

注意：`step = if (manage && p?.isComplete == true) ONBOARDING_STEPS - 1 else 0`（`:225`）保持不动。铺网格对 `step == 0` 也无害（用户一路点「下一步」时 `next()` 里的 seed 因为网格已非空而直接返回），但为了让「第 1 屏就看到的东西等于第 3 屏的答案」这条不变式清晰，本步骤不做条件判断。

- [ ] **Step 5: 编译并跑全量 App 单测**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```
Expected: `BUILD SUCCESSFUL`，无测试失败。若出现 `Unresolved reference`，检查 `STUDY_WINDOWS`（经 `:11` 的 `com.yanzhong.app.data.remote.*` 通配 import 引入）与 `briefToGrid`（`:667` 的 `internal fun`，顶层函数，同文件直接可用）—— 两者都无需改动。

- [ ] **Step 6: 装到设备上按验收清单走一遍**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:installDebug '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain -q"
```

然后按下列清单逐项确认（模拟器操作参照既有约定：`am start` 拉起、`uiautomator dump` 取 bounds）：

1. 用一个没有档案的账号走完第 1、2 步（第 2 步只勾「晚上」），点「下一步」→ 第 3 步七天每行都显示 `19:00-22:00` 一个胶囊，直接点「下一步」不再被拦。
2. 第 2 步改勾「上午 + 晚上」，重走 → 每天两个胶囊 `08:30-11:30`、`19:00-22:00`。
3. 第 3 步点某天胶囊删掉它 → 该天变空，点「下一步」仍能过（其余六天非空），且删掉的那天不会被重新铺回。
4. 上传一张课表截图 → 识别出的占用出现在「已经记下的固定占用」列表里，网格里的窗口保持不变。
5. 从「计划」页进「调整备考档案」（`startManage` 路径）→ 直接落在第 3 步，档案里已有的真实空闲原样显示，没有被预设覆盖。

- [ ] **Step 7: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt YanZhong/app/src/test/java/com/yanzhong/app/ui/onboarding/OnboardingAvailabilitySeedTest.kt
git commit -m "$(cat <<'EOF'
fix(onboarding): fill free-time grid on entering the schedule step

next() 与 loadInternal(manage) 两个入口都调用 seedAvailability,
上传课表后不再被「至少给一天加一段空闲时段」拦住;档案里回填过的网格不覆盖。
EOF
)"
```

---

### Task 3: 界面文案说明「已经替你铺好了」

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingScreen.kt:455-458`（`FieldLabel` 的标题与说明）
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingScreen.kt:491-495`（网格下方那段 `bodySmall` 提示）

**Interfaces:**
- Consumes: 无新接口。仅改字符串字面量。
- Produces: 无。

- [ ] **Step 1: 改网格区的标题与说明**

把 `OnboardingScreen.kt:454-458` 替换为：

```kotlin
    Spacer(Modifier.height(20.dp))
    FieldLabel(
        "每周课表:哪天几点到几点真坐得下来",
        "已经按你上一步选的时段铺好了,和实际不符的直接点胶囊删掉,或点「+」改钟点;这天没空就空着,排课不会往里塞任务"
    )
```

- [ ] **Step 2: 改网格下方那段提示**

把 `OnboardingScreen.kt:490-495` 替换为：

```kotlin
    Spacer(Modifier.height(8.dp))
    Text(
        "传了课表就更省事:认出来的上课时间会自动从这些窗口里挖掉,剩下的就是你能坐下来学的钟点。这份课表就是排课的依据,填得越真,计划越贴身。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant
    )
```

- [ ] **Step 3: 编译确认没有语法错误**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:assembleDebug '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain -q"
```
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 设备上确认文案渲染正常**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:installDebug '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain -q"
```

走到第 3 步，确认新文案完整显示、没有截断，深色模式下对比度正常（`colors.onSurfaceVariant` 走主题，不需额外改动）。

- [ ] **Step 5: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingScreen.kt
git commit -m "$(cat <<'EOF'
docs(onboarding): explain the pre-filled free-time grid

说明网格是按上一步时段预设铺好的、课表占用会自动挖洞,
用户从「创建窗口」变成「删改窗口」。
EOF
)"
```

---

## Self-Review

**Spec coverage（对照 spec 4.1 与第 6 节 A 部分验收）：**

| Spec 要求 | 对应任务 |
|---|---|
| `seedAvailability` 纯函数，按 `STUDY_WINDOWS` 顺序映射 `WINDOW_CLOCK` | Task 1 Step 3 |
| 七天同构 | Task 1 Step 3（`(1..7).associateWith`），Step 1 测试 `只勾晚上时七天各铺一段晚上钟点` |
| 触发点 1：`next()` 中 `to == ONBOARDING_STEPS - 1` | Task 2 Step 3 |
| 触发点 2：`loadInternal(manage = true)` 直接落到最后一题 | Task 2 Step 4 |
| 仅在 `availability.isEmpty()` 时生效，不覆盖用户已填 | Task 1 Step 3 首行 early return，测试 `用户已经填过的网格一根手指都不动`、Task 2 测试 `服务端回填过的网格不被预设覆盖` |
| 界面文案说明已铺好、可删改 | Task 3 |
| 验收 1（全新账号不被拦） | Task 2 Step 6 清单第 1 项 |
| 验收 2（只勾晚上 → 每天一段） | Task 2 Step 6 清单第 1 项 + Task 1 单测 |
| 验收 3（删光某天不被铺回） | Task 2 Step 6 清单第 3 项 |
| 验收 4（`startManage` 路径同样铺满） | Task 2 Step 6 清单第 5 项 |

Spec 验收清单第 4 项（上传课表后占用列表出现、网格不变）已由 Task 2 Step 6 清单第 4 项覆盖。

**Placeholder scan：** 无 TBD/TODO；所有代码步骤均给出完整可粘贴代码；所有 Run 步骤均给出完整命令与预期结果。

**Type consistency：**
- `seedAvailability(Set<String>, Map<Int, List<TimeWindow>>): Map<Int, List<TimeWindow>>` 在 Task 1 定义、Task 2 两处调用，签名一致。
- `TimeWindow(start, end)` 位置参数构造与 `OnboardingViewModel.kt:125` 的 `data class TimeWindow(val start: String, val end: String)` 一致。
- `STUDY_WINDOWS` 为 `List<String>`，`it in studyWindows`（`Set<String>`）合法。
- `WINDOW_CLOCK` 值类型为 `Pair<String, String>`，解构 `?.let { (start, end) -> ... }` 合法。
- Task 2 Step 4 使用的 `briefToGrid`、`s.studyWindows`、`p?.studyWindows` 均为该文件既有符号（分别在 `:667` 的 `internal fun briefToGrid`、`:42`、`:230`）。

**已知风险（执行时注意）：**
- `OnboardingViewModel` 是 `AndroidViewModel`，`next()`/`loadInternal()` 本身无法进 JVM 单测。因此「不覆盖已填」这条约束靠纯函数测试 + Task 2 Step 6 的设备清单第 3、5 项双重把关。
- Task 2 Step 4 让 `loadInternal` 在非 manage 路径（`step = 0`）也铺网格。若后续有人加了「第 1 屏直接跳到第 3 屏」的入口，这条铺垫已经生效，不需再补。
