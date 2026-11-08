# 课表空闲自动补全 与 单双周建模 设计文档

日期：2026-11-08
状态：已与产品负责人确认方案（见「已确认的决策」）
实现计划：
- `docs/superpowers/plans/2026-11-08-onboarding-availability-seeding.md`（A 部分，纯 App）
- `docs/superpowers/plans/2026-11-08-timetable-week-parity.md`（B 部分，服务端 + App）

---

## 1. 问题

用户在新手引导第 3 步（「正式科目与真实空闲」）上传课表截图后，识别成功、占用列表也出来了，
但点「下一步」仍被拦住，提示「七天里至少给一天加一段空闲时段」。用户的原话是
「这直接根据课表有的补一下不就是了」。同时用户指出「课表还不分单双周」。

## 2. 根因（已定位，附证据）

### 2.1 空闲与占用是两个互不推导的字段

| 字段 | 类型 | 谁写入 |
|---|---|---|
| `availability` | `[{weekday, windows:[{start,end}]}]` | 只能由用户在第 3 步手工点「+」添加 |
| `fixedCommitments` | `[{weekday,start,end,label}]` | 课表 OCR 或手工补占用 |

- `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt:528-533`
  注释明说「识别结果只往 `OnboardingUiState.commitments` 里加」。`parseTimetable()` 全程不碰 `availability`。
- 同文件 `:605` 的闸门读的是另一个字段：
  `if (s.availability.isEmpty()) return "七天里至少给一天加一段空闲时段,点那天的「+」就行"`。
- `server/src/modules/planning/timetable.ts:8-12` 记录了服务端同样的取舍：
  「为什么落到 fixedCommitments 而不是直接算出空闲……空闲窗口仍由问卷里的按星期时段给出,两者叠加即可」。

### 2.2 占用单独存在时净空闲恒为 0

`server/src/modules/planning/document.ts:490-502` `netAvailableMinutes()`：
只统计「落在 `windows` 内 **且** 不在 `occupied` 内」的分钟数。

因此这不是显示 bug，而是数据模型上 `commitments` 无法单独产生容量 —— 必须有 `windows` 打底。
反过来这也说明用户的建议天然可行：`freeSlots()`（同文件 `:505-525`）本来就是「窗口减占用」，
只要把窗口铺上去，课表的课会自动挖成洞，不需要为 free-time 另造结构。

### 2.3 单双周只在输出侧存在，输入侧完全没有

- 输入侧：`document.ts:87` `BriefCommitment = { weekday, start, end, label }`，无 parity 字段。
  `timetable.ts:37-38` 的 OCR 提示词明确要求把单双周压平成「每周都占」，只在 label 尾巴标 `(单周)/(双周)`。
  这是刻意的保守策略（`:11-12`「宁可少算可用时间」），代价是双周整天空闲被白白扣掉。
- 输出侧：`aiGenerator.ts:68` 已有 `weekParity?: 'odd' | 'even'`，`:349` 按
  `Math.floor(offset / 7) % 2` 展开，`:357/:366` 用它筛任务槽。
- 结论：模型能排「奇周做 A、偶周做 B」，用户却无法告诉它「奇周周三才有课」。
  而 `OnboardingScreen.kt:498` 的文案写着「单双周课表都行」，承诺超出了实际能力。
- 已有的 `BriefPeriod`（按日期区间覆盖作息）装不下逐周交替：`document.ts:333` 上限 8 段。

### 2.4 两个必须先解决的技术约束

1. **briefHash 稳定性**：`planBudget.ts:170-197` `canonicalBrief()` 把 `fixedCommitments`
   序列化进 `briefHash`，`briefContextHash()` 又用它做调整单指纹。新增字段若被无条件写入，
   会让线上所有已有计划的调整单失效。
   → `JSON.stringify` 自动省略值为 `undefined` 的属性，所以给 commitment 加可选 `weekParity`
   对旧简报的哈希无影响；但 `termWeekOneStart` 必须**仅在非空时**进入 canonical JSON。
2. **奇偶锚点不统一**：`aiGenerator.ts:349` 的奇偶从「阶段起点」算，课表的单双周从「校历第 1 周」算
   （见 `6a9d046ffd291c32150bb6c4_kaoyan-468day-plan/kaoyan-468day-plan.html:1027`，按 2026.09.07 为第 1 周）。
   两个锚点不同步，输入侧标了「双周」的课和输出侧排到「双周」的任务会对不上。
   → 引入单一绝对锚点 `termWeekOneStart`，输出侧在有锚点时改用它，无锚点时保持现有阶段起点行为。

## 3. 已确认的决策

| 议题 | 决策 |
|---|---|
| 空闲自动补全策略 | **进第 3 步就铺满七天**：网格为空时，用第 2 步已选的时段预设（`STUDY_WINDOWS` → `WINDOW_CLOCK` 钟点）给七天各铺一遍；识别出的课程自动挖洞。用户从「创建」变成「删改」，闸门恒可通过，且完全可编辑。 |
| 单双周做到哪一层 | **占用侧加 `weekParity` 字段** + 「校历第 1 周」锚点，打通 `daySchedule`/`netAvailableMinutes`/`freeSlots`。`availability` 窗口本次不加 parity。 |

## 4. 设计

### 4.1 A 部分：第 3 步自动铺满空闲（纯 App）

`STUDY_WINDOWS = listOf("早晨","上午","下午","晚上","深夜")`（`Api.kt:143`）与
`WINDOW_CLOCK`（`OnboardingViewModel.kt:116-122`）的键一一对应，可直接映射：

| 时段 | 钟点 |
|---|---|
| 早晨 | 06:30-08:00 |
| 上午 | 08:30-11:30 |
| 下午 | 14:00-17:30 |
| 晚上 | 19:00-22:00 |
| 深夜 | 22:30-23:59 |

新增纯函数 `seedAvailability(studyWindows: Set<String>): Map<Int, List<TimeWindow>>`，
按 `STUDY_WINDOWS` 的固定顺序（而非 Set 迭代序）取交集映射，七天同构。
纯函数才能进 `app/src/test/` 的 JVM 单测，不依赖 Android runtime。

触发点有两处，都只在 `availability.isEmpty()` 时生效，绝不覆盖用户已填内容：
1. `next()`（`:259-274`）中 `to == ONBOARDING_STEPS - 1` 时 —— 正常从第 2 步走进第 3 步。
2. `loadInternal(manage = true)`（`:207-241`）中直接把 `step` 落到最后一题时 —— 从「计划」页进来改档案。

界面文案同步：`OnboardingScreen.kt:455-458` 的说明要讲清「已按你选的时段铺好，不符的直接删/改」。

### 4.2 B 部分：单双周

**数据模型**（`document.ts`）

```ts
export type BriefCommitment = {
  weekday: number; start: string; end: string; label: string
  weekParity?: 'odd' | 'even'   // 省略 = 每周都占（旧行为，向后兼容）
}
export interface PlanBrief {
  // ……
  termWeekOneStart?: string     // 校历第 1 周的周一，YYYY-MM-DD；空 = 不启用单双周
}
```

**奇偶判定**（`document.ts` 新增导出）

```ts
export function weekParityForDate(termWeekOneStart: string, date: Date): 'odd' | 'even' | null
```

锚点为空或非法日期 → 返回 `null`（调用方一律退回「每周都占」）。
否则 `Math.floor(diffDays(date, anchorMonday) / 7) % 2 === 0 ? 'odd' : 'even'`。
锚点先归一到所在周的周一，这样用户填周三也不会整体错位。

**当日作息过滤**（`document.ts:463-488` `daySchedule`）

`weekly` 的筛选条件从 `event.weekday === weekday` 变为
`event.weekday === weekday && (!event.weekParity || parity === null || event.weekParity === parity)`。
`netAvailableMinutes()` 与 `freeSlots()` 都建立在 `daySchedule()` 之上，无需改动即自动生效。
假期区间（`dropWeeklyCommitments`）的行为不变。

**归一与合并**

- `normalizeCommitments()`（`:304-312`）解析 parity，非 `'odd'/'even'` 一律丢弃该属性（不是丢整条）。
- `normalizeBrief()`（`:337-360`）解析 `termWeekOneStart`，非法日期归一为 `''`。
- `mergeBrief()`（`:411-414`）去重键加入 parity；`termWeekOneStart` 取补丁非空优先。
- `withCommitments()`（`service.ts:1050-1063`）去重键同样加入 parity。

**哈希兼容**（`planBudget.ts:170-193`）

`canonicalBrief()` 中 `termWeekOneStart` 用条件展开，仅非空时写入；
`fixedCommitments` 排序键追加 parity（`undefined` 排在最前，保持旧序稳定）。
旧简报（无 parity、无锚点）的 canonical JSON 与改动前逐字节一致 → `briefHash` 不变 → 调整单不失效。

**OCR**（`timetable.ts`）

提示词规则 2 改为输出结构化 `weekParity`，不再依赖 label 后缀；
同时新增规则要求模型在判断不出单双周时省略该字段并把疑点写进 `warnings`，不许猜。
`TimetableResult` 的形状不变（`{summary, warnings, fixedCommitments}`），**不新增 `termWeekOneStart`**：
课表图上通常没有校历日期，让模型去推违反 `:40` 既有的「严禁编造」，猜错会让整张课表错位一周。
锚点改由 App 端在问卷里单独收集。文件头 `:8-12` 的设计注释同步更新。

**输出侧统一锚点**（`aiGenerator.ts`）

- `expandStage` / `expandStageFromVariants` 的 `Pick<PlanBrief, ...>` 加入 `'termWeekOneStart'`。
- `:349` 改为：有锚点时用 `weekParityForDate(brief.termWeekOneStart, planDate)`，
  返回 `null` 或无锚点时退回 `Math.floor(offset / 7) % 2`。
- `:135` 的提示词把「从本阶段起点算起」改为说明真实锚点，避免模型自己按阶段起点理解奇偶。

**入参校验**（`schemas.ts`）

- `fixedCommitmentsSchema`（`:131-145`）对象内加 `weekParity: z.enum(['odd','even']).optional()`。
- `profileInputSchema`（`:157-175`）加
  `termWeekOneStart: z.string().trim().regex(/^\d{4}-\d{2}-\d{2}$/, '校历第 1 周要写成 2026-09-07 这样').refine(真实日期).optional().or(z.literal(''))`。
  其中「真实日期」的 refine 用来挡 `2026-02-30` 这类格式合法但日期不存在的值 —— V8 会把日溢出静默进位、月份越界给 Invalid Date，入口不挡就会存进一个读回时被归一成空串的脏值。
  **三值语义（执行时对 `.optional().default('')` 的修正）**：缺键 = 客户端没提供、写入层保留旧值；空串 = 用户显式清除；合法日期 = 设置。默认成空串会让老客户端的每次整档提交都清掉已确认的开学周。
- `BriefPatchInput`（`service.ts:175-178`）的 `fixedCommitments` 元素类型加 `weekParity?`。
- `applyProfileFacts()`（`service.ts:1014-1031`）透传 `termWeekOneStart` 同样按三值：`undefined` 保留 `brief.termWeekOneStart`，否则用入参（空串即清除）。

**App 侧**

- `Api.kt`：`BriefCommitmentDto`（`:651`）加 `weekParity: String? = null`；
  `ProfileReq`（`:175-189`）加 `termWeekOneStart: String = ""`；
  `PlanBriefDto`（`:621-633`）加同名字段以便回填。
- `OnboardingViewModel.kt`：`OnboardingUiState` 加 `termWeekOneStart: String = ""`；
  `addCommitment()` 加 parity 形参；`beginSession()` 清零；`loadInternal()` 回填；
  `ProfileReq` 构造处透传；`briefToGrid` 附近新增把 `fixedCommitments` 还原为编辑列表时保留 parity。
- `OnboardingScreen.kt`：
  - `:498` 文案改成实话：识别会标出单双周，并说明需要填校历第 1 周才生效。
  - 占用列表行（`:532-543`）显示「单周/双周/每周」标记，点击可轮换。
  - `ManualCommitmentRow`（`:682-739`）加 parity 选择（复用 `SegmentedChips`）。
  - 课表区下方新增「校历第 1 周（周一）」日期输入。

## 5. 不在本次范围

- `availability` 窗口本身的单双周（需要按奇偶周分别求容量，牵动预算与校验，另议）。
- 面谈（`PlanInterviewViewModel`）侧的单双周问答话术。
- 旧简报的数据迁移：无锚点即维持「每周都占」的保守行为，不需要迁移。

## 6. 验收

A 部分：
1. 全新账号走到第 3 步，七天各自带着第 2 步所选时段的钟点窗口，直接点「下一步」不再被拦。
2. 第 2 步只勾「晚上」→ 第 3 步每天只有 19:00-22:00 一段。
3. 在第 3 步手动删光某天窗口 → 该天保持为空，不被重新铺回。
4. 从「计划」页进「调整备考档案」（`startManage`）且档案里 availability 为空 → 同样自动铺满。

B 部分：
1. 无 `termWeekOneStart` 时，带 `weekParity` 的占用仍按每周都占计算（旧行为，回归不破）。
2. 锚点 = 2026-09-07，某条 `weekParity: 'even'` 的占用：2026-09-14（第 2 周，偶周）当天净空闲被扣，
   2026-09-21（第 3 周，奇周）当天不扣。
3. 旧简报（无新字段）的 `briefHash` 与改动前一致。
4. OCR 一张含单双周课程的课表 → 返回的 `fixedCommitments` 里出现 `weekParity` 字段，
   且 App 占用列表能看到单周/双周标记。
