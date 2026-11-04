# 研钟 · 计划定制与对话式调整设计

日期：2026-11-02
状态：已实施（2026-11-04，服务端 81 项测试通过，客户端模拟器全流程验证）
范围：Android 客户端（YanZhong）+ 服务端（server）
关联：`2026-09-29-dynamic-plan-adjustment-design.md`、`2026-11-01-server-value-loop-design.md`

## 1. 背景与目标

计划是研钟的核心，但目前「定制」和「调整」两条链路都还停在可用而非可靠：

- 生成计划时没有总工作量预算，生成器按每天空闲容量尽可能填满。实测一份 400+ 天的计划排了 272 项、15,660 分钟，而用户确认的剩余任务只有约 3,000 分钟；计划越生成越长，用户越不可能完成。
- 用户无法手工改一条任务、加一条任务或锁住一条任务；AI 调整也不认识「锁定」。
- 对话调整只覆盖「没空 / 时间变少 / 新增固定占用」，遇到容量缺口只有压缩或报错，没有可比较的取舍方案。
- 调整确认与撤销缺少对「生成后手工改过」的保护；`change_commitment` 甚至在确认前就写档案。
- 调整窗口只有当前阶段，落后到阶段外的任务无法处理；L1 顺延可能把任务推过科目里程碑。
- 客户端生成提示写「约需 1-3 分钟」，实际为 10-25 分钟；确认调整后时段与开始时刻不会重算。

本轮目标：

1. **计划量可控**：生成前让用户确认「计划口径」（逐科剩余量、里程碑、可用时间），生成结果受总预算与每日上限约束，草案能一眼看出每科目标与实排。
2. **用户能掌控**：任务可改日期/时长/标题/科目，可新增/删除，可锁定；锁定项 AI 不得移动、改内容或删除；所有手工修改立刻生效且可同步。
3. **调整有取舍**：简单情况走 L1 纯算法（快、免费、可预测）；出现容量缺口时给出「压缩 / 顺延 / 减少任务 / 降低目标」多方案，每个方案都有明确的量化影响，用户选完再确认。
4. **安全可撤销**：确认前零副作用（包括档案变更）；确认/撤销都做指纹校验，手工改动后不会覆盖或悄悄回滚；撤销把档案一并还原。
5. **对话更自然**：识别「把数学那项挪到周末」「加一项英语阅读」「以后每天只能学两小时」「别再动数学」等意图，允许一次追问，闲聊不产调整单。

### 非目标

- 不做拖拽式全天课表编辑器；手工编辑只到「单条任务」粒度。
- 不做多计划并行、不做日历/系统日程双向同步、不做团队计划。
- 不自动应用任何调整；所有调整必须用户确认。
- 不做跨阶段重新生成（仍走「重新生成计划」）。
- 不引入数据库迁移目录；继续使用 `prisma db push`，只做加列。
- 不重写现有生成链路，只在预算、校验、细化目标与兜底生成器上增量改造。

## 2. 现状问题与证据

| # | 问题 | 影响 | 位置 |
| --- | --- | --- | --- |
| 1 | 生成无总预算，兜底生成器按每天容量填满 | 计划总量远超剩余任务，永远做不完 | `generator.ts:153-193`、`refineStage.ts:106-108` |
| 2 | 校验只查「够不够」，不查「是否超发」 | 超发计划仍可通过 | `service.ts:1358-1368` |
| 3 | 估算科目不参与覆盖校验，也不设上限 | 估算科目可无限膨胀 | `service.ts:1355-1358` |
| 4 | 生成前没有预算确认 | 用户不知道 AI 排了多少、依据是什么 | `buildPlan` 入口 |
| 5 | 草案预览没有科目预算表 | 无法判断计划是否合理 | `PlanDraftPreview.kt` |
| 6 | 只能勾选，不能改/加/删/锁 | 小改动也要重新生成整份计划 | `routes.ts:266` |
| 7 | 调整后不重算 `slot/startTime` | 日期变了，时间轴还是旧的 | `service.ts:409-423` |
| 8 | 指纹不含科目/标题/锁定 | 手工改内容后调整单仍能套用，覆盖用户修改 | `adjust.ts:169-176` |
| 9 | 撤销不检测生效后的手工修改 | 撤销会把用户后来的修改一起回滚 | `service.ts:1203-1235` |
| 10 | `change_commitment` 确认前写档案 | 用户不确认调整，档案也被改了 | `service.ts:1043-1055` |
| 11 | L2 强制总量守恒 | 用户想减量/降目标时无法表达 | `adjustL2.ts` |
| 12 | L1 只按日期窗口排，不看科目里程碑 | 任务可能被推到里程碑之后 | `adjust.ts:95-119` |
| 13 | 调整窗口只有当前阶段 | 阶段外的落后任务无法处理 | `service.ts:369-378` |
| 14 | 容量缺口只有「压缩或报错」 | 用户没有可比较的取舍 | `service.ts:1107-1123` |
| 15 | 生成耗时提示严重失真 | 用户以为卡死 | `PlanInterviewScreen.kt:220` |
| 16 | `THIN_DAY` 会逼模型把每天排满 | 与预算模型直接冲突 | `planSpec.ts:560-568` |

## 3. 总体架构

```
┌──────────────────────── Android ────────────────────────┐
│ PlanSetup / PlanInterview                                │
│  ├ PlanBriefSheet（口径确认 + 预算预览）                  │
│  ├ CapacityOptionsDialog（生成缺口：调作息/降目标/尽力版） │
│  └ 生成进度（真实 ETA / 阶段 / WS 推送）                  │
│ PlanScreen                                               │
│  ├ 日程行 → PlanItemEditSheet（改/加/删/锁）              │
│  ├ 锁定标识 / 添加任务 / 体检卡 → 预填调整                 │
│  └ 对话调整                                                │
│      ├ 快捷话术 chips                                     │
│      ├ AdjustmentOptionCard × N（量化影响 + 预览 + 选择）  │
│      └ 确认 / 撤销                                        │
└───────────────────────────┬──────────────────────────────┘
                            │ HTTP / WS
┌───────────────────────────▼──────────────────────────────┐
│ Koa routes                                                │
│  GET/PATCH /plans/brief                                   │
│  POST /plans/generate/jobs（briefHash / allowShortfall）  │
│  GET /plans/:id/overview                                  │
│  PATCH/POST/DELETE /plans/items…                          │
│  POST /plans/adjust（options）                             │
│  POST /plans/adjustments/:id/select|confirm|undo          │
├───────────────────────────────────────────────────────────┤
│ planning 纯函数层                                          │
│  planBudget.ts   reflow.ts   planOptions.ts               │
│  adjust.ts       adjustL2.ts adjustIntent.ts              │
├───────────────────────────────────────────────────────────┤
│ Prisma / MySQL（PlanItem.locked、PlanAdjustment 新列）     │
└───────────────────────────────────────────────────────────┘
```

原则：

- **纯函数先行**：预算、重排时段、方案生成、指纹都做成不碰 DB/不调模型的纯函数，便于测试与复用。
- **服务端硬规则，模型只做语义**：容量、里程碑、锁定、总量由服务端校验；模型只负责理解意图与产出任务序列。
- **渐进增强**：新增字段/接口全部向后兼容，旧客户端继续走原路径；新客户端通过能力探测决定是否显示新入口。

## 4. 计划预算与生成质量

### 4.1 预算模型

新增 `server/src/modules/planning/planBudget.ts`，输入归一化后的 `PlanBrief`、`startDate`、`examDate`，输出：

```ts
interface PlanBudget {
  capacityMinutes: number          // 规划区间内净空闲总量
  effectiveCapacityMinutes: number // floor(capacity * PLAN_LOAD_FACTOR)
  confirmedMinutes: number         // 已确认科目剩余量之和
  estimatedMinutes: number         // 估算科目剩余量之和
  estimatedBudgetMinutes: number   // 估算科目实际分配预算
  targetMinutes: number            // 确认 + 估算预算
  shortfallMinutes: number         // 确认任务超出有效容量的部分
  deficits: Array<{ subject: string; milestone: string; missingMinutes: number }>
  dayCap: Map<string, number>      // 每天生成目标上限
  subjects: Array<{
    name: string
    estimated: boolean
    remainingMinutes: number
    budgetMinutes: number          // 本科计划总量
    milestone: string
    milestoneDate: string
    milestoneMinutes: number       // 里程碑前至少完成量
    milestoneBudgetMinutes: number // 里程碑前计划量（正常 = milestoneMinutes）
  }>
  warnings: string[]
}
```

计算规则：

1. `capacity(day) = netAvailableMinutes(brief, day)`，区间为 `[startDate, examDate)`。
2. `capacityMinutes = Σ capacity(day)`；`effectiveCapacityMinutes = floor(capacityMinutes * 0.85)`。0.85 是计划留白系数，给临时事件、复盘和状态波动留缓冲；常量 `PLAN_LOAD_FACTOR = 0.85` 可被环境变量覆盖。
3. `confirmedMinutes = Σ subjects[!estimated].remainingMinutes`。
4. `estimatedBudgetMinutes = max(0, min(estimatedMinutes, effectiveCapacity - confirmedMinutes))`；估算科目按各自剩余量比例分配，缩放系数 `scale = estimatedBudget / estimatedMinutes`。
5. `targetMinutes = confirmedMinutes + estimatedBudgetMinutes`。**科目任务总量不得超过 `targetMinutes` 的 105%**；加上复盘后总排量不得超过 `targetMinutes` 的 110%。确认科目不得低于 `remainingMinutes`（尽力版除外）。
6. 每天生成目标 `dayCap(day) = floor(capacity(day) * 0.85)`；硬上限仍是 `capacity(day)`。
7. `shortfallMinutes = max(0, confirmedMinutes - effectiveCapacityMinutes)`。大于 0 时生成前必须让用户选择处理方式（见 4.5）。
8. 里程碑：`milestoneBudgetMinutes = milestoneMinutes`。若里程碑前容量不足，计入 `deficits`：
   ```ts
   deficits: Array<{ subject: string; milestone: string; missingMinutes: number }>
   ```
   由 `assessPlanningFacts` 的同一口径计算，避免两套数字。

`PlanBudgetView` 是 `PlanBudget` 的客户端视图：去掉 `dayCap`，`deficits` 与 `subjects` 只保留展示字段（名称、估算标记、剩余量、预算、里程碑、缺口）。

约束：

- **确认科目**：`scheduled >= remainingMinutes`（正常模式）且 `scheduled <= remainingMinutes * 1.05 + 30`；按科目统计，允许最多 30 分钟取整余量。
- **估算科目**：`scheduled <= budgetMinutes * 1.05`；不要求覆盖，但不得膨胀。
- **复盘任务**：`subject === '复盘'` 不占科目预算，但计入每日与总量；复盘总量不得超过 `targetMinutes * 0.1`。
- **手工编辑**：不受总预算约束（用户可自行加量），但受每日实际容量约束；`overview` 显示与预算的差异。
- **每日**：生成阶段 `scheduled(day) <= dayCap(day)`；手工编辑硬上限 `capacity(day)`。
- **锁定项**：锁定任务的分钟数照常计入每日容量与总量。

### 4.2 计划口径确认（Brief）

新增接口：

```http
GET /plans/brief
→ {
  brief: PlanBrief,
  briefHash: string,
  profile: { targetType, examDate, foundation, weakSubjects },
  budget: PlanBudgetView,
  capacityByWeekday: Array<{ weekday: number; minutes: number }>,
  warnings: string[]
}

PATCH /plans/brief
body: {
  examSubjects?: Array<{
    name: string
    remainingMinutes?: number
    milestone?: string
    milestoneDate?: string
    milestoneMinutes?: number
    estimated?: boolean
    scope?: string
    progress?: string
  }>
  fixedCommitments?: BriefCommitment[]
}
→ 同 GET；写回 UserProfile.briefJson
```

规则：

- `briefHash = sha256(规范化 brief + examDate)`；规范化顺序固定、忽略展示性字段（`summary/goals/notes` 不参与哈希）。
- 修改 `examSubjects` 时按 `name` 合并；不允许新增科目（新增科目走面谈/档案），可以改剩余量、里程碑、截止日、估算标记、范围与进度。
- `remainingMinutes` 限制 `0..500000`；`milestoneMinutes <= remainingMinutes`；`milestoneDate` 必须 `>= today` 且 `< examDate`；非法值返回 400 `INVALID_PARAMS`，字段级信息放在 `details`。
- 有生成任务运行时 `PATCH` 返回 409 `PLAN_GENERATION_RUNNING`，避免生成中改口径。
- `profileSnapshot` 已包含 `briefJson`，因此 PATCH 后旧草稿会自动判定为 `PLAN_PROFILE_CHANGED`，无需额外清理。
- 生成接口新增可选 `briefHash`：
  ```http
  POST /plans/generate/jobs
  body: { briefHash?: string; allowShortfall?: boolean }
  ```
  - 新客户端必须带 `briefHash`；缺失按旧客户端处理（兼容）。
  - 带了但与当前不符 → 409 `PLAN_BRIEF_CHANGED`，`details.brief` 返回最新口径，客户端重新确认。
  - `allowShortfall=true` 时必须同时带 `briefHash`，且只在用户明确选择「生成尽力版」后使用。
  - 同步接口 `POST /plans/generate` 接受同样的可选字段，保持两条生成路径口径一致。

### 4.3 生成器改造

1. **骨架/阶段配额**：`buildPlan` 计算 `PlanBudget` 并传给 AI 生成器与校验器。阶段/周目标由预算按「周容量占比」分配，而不是按每天容量填满。
2. **细化**：`refineStage` 的 `weeklyTargetMinutes` 改为该阶段预算周均值（含里程碑前置约束），提示词从「接近可投入值（±20%）」改为「接近本阶段预算（±15%）」；不再要求排满容量。
3. **兜底生成器**：`generator.ts` 不再逐日填满：
   - 维护逐科剩余预算；
   - 每天按 `dayCap` 与剩余预算安排，优先薄弱科目与接近里程碑的科目；
   - 预算耗尽后停止排新任务；复盘只在当天已有学习任务且预算有余量时追加；
   - 科目任务总量、里程碑量由预算约束。
4. **`compileDay`**：保持现有窗口/时段分配逻辑，只增加「当天计划量不超过 `dayCap`」的预算裁剪；`slot/startTime` 逻辑不变。
5. **`verifyPlanSpec`**：
   - 移除「排得不满」式的 `THIN_DAY` 警告；改为仅当「仍有未分配预算且某天明显低于 `dayCap`」时给 `warn`，且不触发修复循环。
   - 新增错误码：`SUBJECT_OVER_BUDGET`、`TOTAL_OVER_BUDGET`、`MILESTONE_SHORT`。
   - `MISSING_SUBJECT` / `SUBJECT_MIX` 只在本周有该科任务时检查，避免预算很小或无任务的科目被误报。
6. **`assertGeneratedPlanValid`**：
   - 确认科目覆盖与上限、估算科目上限、复盘上限、每日 `dayCap`、里程碑覆盖、总预算。
   - 失败时把具体科目与差额写进 `repairHint` 重试；确定性缺口（容量真不够）直接抛 `PLAN_CAPACITY_INSUFFICIENT`，不浪费重试。
   - 正常模式不满足覆盖 → 重试；重试仍失败 → `PLAN_GENERATION_FAILED`，并附 `details.deficits`。
7. **尽力版**：`allowShortfall=true` 时，确认科目预算按 `effectiveCapacity / confirmedMinutes` 等比缩放，里程碑目标同比例缩放；草案 `overview.warnings` 明确逐科缺口，确认页必须展示。生成器不因缺口抛错。

### 4.4 草稿摘要接口

新增：

```http
GET /plans/:id/overview
→ {
  planId, title, version, startDate, examDate, status,
  budget: {
    capacityMinutes, effectiveCapacityMinutes,
    targetMinutes, scheduledMinutes,
    confirmedMinutes, estimatedMinutes,
    utilization, shortfallMinutes
  },
  subjects: Array<{
    name, estimated,
    targetMinutes, scheduledMinutes,
    milestone, milestoneDate, milestoneMinutes,
    beforeMilestoneMinutes, deltaMinutes
  }>,
  days: Array<{ date, capacityMinutes, dayCapMinutes, scheduledMinutes, itemCount }>,
  stages: Array<{ name, startDate, endDate, targetMinutes, scheduledMinutes }>,
  warnings: Array<{ code, message, date?, subject? }>
}
```

- 只允许读取自己的 draft/active 计划；其它状态返回 404。
- 草案预览 HTML 增加「科目预算」表与总量条；`warnings` 置顶。
- 旧接口 `GET /plans/:id` 保持不变，避免大改客户端。

### 4.5 生成缺口的选择

`POST /plans/generate/jobs` 在以下情况同步返回 409/400（任务未创建）：

```json
{
  "code": "PLAN_CAPACITY_INSUFFICIENT",
  "message": "按当前作息，考前的有效时间不足以完成已确认的任务",
  "details": {
    "availableMinutes": 42000,
    "requiredMinutes": 51000,
    "deficits": [{ "subject": "数学", "milestone": "完成强化", "missingMinutes": 3200 }],
    "choices": ["adjust_schedule", "lower_target", "best_effort"]
  }
}
```

客户端弹窗：

| 选项 | 行为 |
| --- | --- |
| 调整作息 | 跳到档案/作息编辑；保存后重新计算 brief 与预算 |
| 降低目标 | 打开 BriefSheet 编辑剩余量/里程碑；保存后重新确认 |
| 生成尽力版 | 二次确认后带 `allowShortfall=true` 重发；草案标注缺口，不承诺全覆盖 |

### 4.6 生成体验

- 客户端 ETA 改为按计划跨度计算：`max(8, min(30, round(horizonDays / 25)))` 分钟，展示「预计 10-20 分钟」区间；显示已用时。
- 阶段文案与 `PlanProgress.phase` 对齐：骨架 / 细化 / 编译 / 校验 / 修复 / 评审 / 文档。
- 生成中可离开页面；完成通过 WS `planGeneration` 推送，回页面后轮询兜底。
- 生成失败展示服务端 `error.message` 与下一步动作（重试 / 调整口径）。

## 5. 手工微调与锁定

### 5.1 数据模型

`PlanItem` 增加：

```prisma
locked Boolean @default(false) // 锁定后 AI 调整不得移动/改内容/删除;手工仍可改
```

`PlanAdjustment` 增加（见第 6 节）：`afterFingerprint`、`optionsJson`、`selectedOption`、`profileBeforeJson`、`profileAfterJson`。

不做数据回填：`locked` 默认 false。

### 5.2 编辑接口

扩展 `PATCH /plans/items/:id`，保留 `status` 兼容：

```http
PATCH /plans/items/:id
body: {
  status?: "pending" | "done",
  planDate?: "YYYY-MM-DD",
  minutes?: number,
  title?: string,
  subject?: string,
  locked?: boolean
}
```

- 至少一个字段；`status` 与原逻辑一致（slim 响应仍可用）。
- 只允许编辑归属自己、状态为 `active` 或 `draft` 的计划项；`archived` 返回 409 `PLAN_ITEM_NOT_EDITABLE`。
- done 项只允许改 `status`（取消勾选），不允许改日期/时长/内容/锁定。
- `minutes` 5..480；`title` 1..64；`subject` 必须在 brief 科目表内，或为 `复盘`（brief 缺失时只允许计划内已有科目或 `复盘`）；`planDate` 必须在 `[planStart, examDate-1]` 且在计划阶段覆盖范围内。
- `status` 不能与内容字段同请求混用；勾选走独立请求，避免一次操作既改内容又改完成状态。
- 改日期时自动更新 `stageId` 到目标日期所属阶段。
- 成功后返回 `{ plan, item, serverTime }`（全量 plan 便于客户端合并；后续可加 `slim=1` 返回单条）。

新增：

```http
POST /plans/items
body: { planId?: number, subject, title, planDate, minutes, locked?: boolean }
→ { plan, item }

DELETE /plans/items/:id
→ { plan }
```

- `POST` 默认作用于当前 active 计划；显式 `planId` 必须是自己的 active/draft。
- 新增项 `priority=0`、`sortOrder` 由服务端按当天排序生成；`slot/startTime` 由重排计算。
- `DELETE` 只允许 pending 项；done/archived 返回 409 `PLAN_ITEM_NOT_EDITABLE`。删除后释放容量。
- 所有写操作成功后更新 `Plan.updatedAt` 并广播 `planChanged` 给同账号其它设备。

### 5.3 容量约束

- 手工编辑的硬上限是当天实际净空闲 `dailyCapacity(brief, day)`（不是 85% 目标）。
- 写入前计算 `dayLoad = Σ 当天所有未删除项的 minutes`（含 done 与锁定项）；若 `dayLoad > capacity`：
  - 返回 409 `PLAN_DAY_OVERLOADED`，`details: { date, capacity, planned, overflow, suggestion }`；
  - `suggestion` 给出「缩短 X 分钟 / 移到 Y 日（有 Z 分钟余量）」的具体建议。
- 若 brief 缺失（历史计划），跳过容量校验，但仍重排时段；响应 `warnings` 提示「缺少作息，无法校验每日容量」。
- 不允许 `force` 绕过；用户想超量必须先调作息或删/缩其它任务。

### 5.4 锁定语义

- `locked=true` 的任务：
  - L1 不移动、L2 不改内容/不删除；
  - 方案引擎的 `compress`/`spill`/`drop` 都不碰锁定项；
  - 调整计算时，锁定项分钟数从每日容量中扣除；
  - 手工编辑仍可改（用户自己改），改完保持锁定。
- 调整请求与锁定项冲突时：
  - 若请求把锁定项所在的日期整日设为不可用，或压缩后仍超过容量 → 409 `LOCKED_ITEMS_CONFLICT`，`details.items` 列出锁定项与冲突原因；
  - 客户端弹窗提供「查看并解锁」与「取消」；不自动解锁。
- 客户端日程行展示锁图标；编辑面板有「锁定，不让 AI 调整」开关。

### 5.5 时间重排

新增 `server/src/modules/planning/reflow.ts`：

```ts
reflowDay(input: {
  date: string
  items: Array<{ id, subject, title, minutes, status, locked, startTime?, sortOrder, priority }>
  brief: PlanBrief
}): Array<{ id, slot, startTime }>
```

规则：

- `freeSlots(brief, date)` 给出连续空闲段；固定占用已扣除。
- done 项与「有 startTime 的锁定项」视为固定占用，先占位。
- 其余项排序：优先级升序 → 原 startTime → sortOrder → id；逐项放进能容纳的最早空闲段。
- 单条任务无法整段放下时不拆任务，`startTime=null`、`slot` 取最早可用时段；计划仍可展示。
- 重排是幂等的：同一组输入重复调用结果一致。
- `syncWindowItems` 扩展为写回 `slot/startTime`，并在日期变化时更新 `stageId`；确认调整、撤销、手工编辑共用。
- `compileDay` 的窗口选择逻辑抽成可复用纯函数（`pickSlot`/`freeSlots`），避免两套排时段算法漂移。

### 5.6 客户端交互

- 日程行：点复选框 = 勾选；点行其它区域 = 打开编辑面板（不再整行切换状态，避免误触）。
- `PlanItemEditSheet`：标题、科目下拉、日期选择、时长步进（15 分钟）、锁定开关、保存、删除。
- 日期/时长改动后先本地乐观更新，失败回滚并展示服务端 `details` 的具体余量。
- 当日卡标题栏增加「+ 添加任务」；空状态也可添加。
- 锁定项在标题前显示锁图标；编辑面板可解锁。
- 历史计划只读，不显示编辑入口。

## 6. 对话式调整引擎

### 6.1 调整窗口

替换 `stageWindow`：

```ts
adjustWindow({ planStart, examDate, today }): { from, to }
```

- `to = min(examDate - 1, today + 29)`。
- `overdueFrom = [today-30, today)` 内最早的未完成项日期；没有则 `from = today`。
- `from = max(planStart, overdueFrom ?? today)`；保证最多 30 天内的落后任务可被修复，同时窗口始终包含今天。
- 考试已结束返回 404 `PLAN_NOT_FOUND`。
- `GET /plans/adjustments/latest` 与调整单存储使用同一窗口。
- 调整单 `windowFrom/windowTo` 仍然落库，确认/撤销按窗口同步。

### 6.2 意图分类

扩展 `adjustIntent.ts` 的 `kind`：

| kind | 典型说法 | 处理 |
| --- | --- | --- |
| `unavailable` | 明天有事、周六全天没空 | 指定日期/时段容量归零 |
| `reduce_capacity` | 这周每天只能学 2 小时、最近很忙 | 指定区间每日容量下调 |
| `change_commitment` | 每周三晚上有课、下周开始实习 | 新增固定占用，容量重算 |
| `move_task` | 把数学那项挪到周末、英语阅读改到明天 | 指定任务/科目改日期 |
| `add_task` | 加一项英语阅读 30 分钟、明天多做一套数学卷 | 新增任务 |
| `drop_task` | 数学那项不做了、去掉周日复盘 | 删除任务 |
| `adjust_scope` | 数学目标降到 700 题、英语阅读先不做了 | 调整科目剩余量/里程碑 |
| `lock_task` | 别再动数学、把今天那项锁住 | 设锁定，不产调整单 |
| `chat` | 我是不是进度太慢、怎么办 | 只回复，不产调整单 |

输出结构（zod 强校验）：

```ts
{
  kind,
  summary: string,                 // 给用户看的一句话
  needClarify?: boolean,
  clarifyQuestion?: string,
  note?: string,                   // 用户原话摘要
  days?: string[],                 // unavailable
  windows?: Array<{ day, minutes }>,// reduce_capacity
  commitments?: BriefCommitment[],
  tasks?: Array<{                   // move/drop/lock
    subject: string,
    title?: string,
    date?: string,                  // 当前日期，用于消歧
    targetDate?: string             // move 目标
  }>,
  newTasks?: Array<{                // add
    subject: string, title: string, date: string, minutes: number
  }>,
  scope?: Array<{                   // adjust_scope
    subject: string,
    remainingMinutes?: number,
    milestone?: string,
    milestoneDate?: string,
    milestoneMinutes?: number,
    estimated?: boolean
  }>
}
```

规则：

- 一次只允许一个意图；复合请求（“明天没空，顺便把数学减一点”）先追问拆成一次主意图，或选择「先处理没空」。
- `lock_task` 是用户明确指令，服务端直接写 `locked=true` 并广播 `planChanged`，不产调整单；返回 `reply` 说明锁定了哪几项。锁定属于低风险、可立即撤销的操作，是「确认前零副作用」的唯一例外。
- 日期输出必须是 `YYYY-MM-DD`，且落在调整窗口；超出窗口 → 追问或拒绝并说明截止日。
- 任务消歧：`tasks` 必须能在窗口内匹配到唯一 pending 项（subject+title+date）；匹配不到或多条匹配 → 一次追问；仍不明确 → 回复选项让用户选。
- `add_task` 缺少时长/日期 → 追问；`adjust_scope` 缺少目标值 → 追问。
- 闲聊与情绪支持只回复；涉及计划事实时引用今天的任务与体检数据。
- 解析失败/模型不可用：保留现有的「没听懂 + 稍后重试」文案；不抛 500。
- 意图解析上下文增加：窗口内每日任务摘要（最多 60 条）、锁定项、逐科里程碑、预算余量、今天日期。

### 6.3 处理管线（L1 优先）

```
intent
 ├─ chat / clarify → 直接回复
 ├─ lock_task → 直接 PATCH locked=true（无需调整单）
 ├─ 纯日期类（unavailable / reduce_capacity / move_task / change_commitment）
 │    ├─ 计算目标容量（锁定项先扣除）
 │    ├─ L1 顺延（只动未锁定项，遵守科目里程碑截止）
 │    ├─ 成功且影响天数 ≤ L1_MAX_AFFECTED_DAYS → 单方案（tier=L1）
 │    └─ overflow / 影响过大 / 锁定冲突 → 方案引擎（见 6.4）
 ├─ add_task / drop_task
 │    ├─ 直接构造 after 快照
 │    ├─ 容量与预算校验通过 → 单方案
 │    └─ 不通过 → 方案引擎
 └─ adjust_scope → 预算/档案变更 + 任务调整 → 单方案或多方案
```

- L1 只改日期，不改时长/内容/总数；不得把原本在里程碑前的任务推到 `milestoneDate` 之后（原本已在里程碑后的任务不再恶化即可）。
- L1 的容量计算扣除 done 与锁定项；锁定项不参与移动。
- L1 成功后仍需重排时段（`reflowDay`），保证 `startTime` 与日期一致。
- `move_task` 先尝试单条直接移动（允许早于原日期，只看目标日容量），不触发整体 L1 重排；目标日放不下再进方案引擎。
- `add_task` / `drop_task` 直接构造 after 快照：新增校验科目/预算/容量，删除只允许未锁定 pending 项；不调 L2。
- 语义重排（“重新分配重点”“补数学”）才调用 L2 AI；纯日期类绝不调用模型。
- L2 失败一次后不再直接 502：降级到确定性方案引擎；仍无可行方案才返回 `PLAN_CAPACITY_INSUFFICIENT`/`LOCKED_ITEMS_CONFLICT`。
- `adjust_scope` 即使只改档案、不产生任何计划项变化，也要生成调整单（`changes` 可为空），确认后才写档案；不能按「计划不用改」直接返回。

### 6.4 多方案引擎

新增 `server/src/modules/planning/planOptions.ts`，纯函数：

```ts
buildAdjustmentOptions(input: {
  pending: AdjustableItem[]   // 含 locked
  done: WindowSnapshotItem[]
  capacity: Map<string, number>
  windowFrom: string
  windowTo: string
  examDate: string
  brief: PlanBrief
  intent: AdjustIntent
}): {
  options: AdjustmentOption[]
  recommendedId: string | null
  lockedConflicts: WindowSnapshotItem[]
  deficits: Array<{ subject: string; missingMinutes: number }>
}

interface AdjustmentOption {
  id: 'compress' | 'spill' | 'drop' | 'lower_target'
  label: string
  summary: string
  impact: {
    affectedDays: number
    movedCount: number
    addedCount: number
    removedCount: number
    totalMinutesBefore: number
    totalMinutesAfter: number
    maxDayLoad: number
    maxDayCapacity: number
    shortfallMinutes: number
    milestoneRiskCount: number
    lockedKeptCount: number
  }
  changes: AdjustmentChange[]
  after: WindowSnapshotItem[]   // 仅服务端存储，不直接下发全量
  profileAfter?: string         // lower_target 的 brief JSON
}
```

四类方案：

1. **压缩（compress）**
   - 保留全部任务与锁定项，按剩余容量等比缩短未锁定任务，单条最低 15 分钟。
   - 日期优先不变；整日不可用时，任务顺延到最近有容量的日期，仍保持缩短后的时长。
   - 适用条件：总容量 ≥ 15 × 未锁定任务数，且能覆盖到里程碑（里程碑量也按比例下调？不——里程碑量不降，压缩后若里程碑不足则此方案标 `milestoneRiskCount > 0`，仍可选但明确风险）。
2. **顺延（spill）**
   - 保留时长与任务，把溢出项移到后续日期；允许超出窗口最多 `SPILL_EXTRA_DAYS = 14` 天，但不得晚于考前一天和科目里程碑日。
   - 后续日期按容量装填，不超每日硬上限。
   - 里程碑装不下则此方案不可行。
3. **减少任务（drop）**
   - 保留日期与时长，删除最少的未锁定任务以腾出容量。
   - 删除优先级：非薄弱科目 → 低 priority → 无里程碑任务 → 日期更晚；同一优先级按 `sortOrder` 倒序。
   - 锁定项永不删除；删除清单逐条展示。
4. **降低目标（lower_target）**
   - 仅在 `reduce_capacity`/`change_commitment` 等持续性容量下降，且压缩/顺延都不可行或风险过高时提供。
   - 计算受影响科目的新剩余量（当前剩余 − 无法安排的分钟），并同步下调 `milestoneMinutes`（不超过新剩余量）；生成新的 brief。
   - 方案摘要必须写明「数学剩余量 3000 → 2400 分钟」这类具体变化；确认时写档案，撤销时还原。

选择与推荐：

- 所有方案的 `after` 都保留窗口内 done 项不变，否则确认时 diff 会把打卡项误判成删除。
- 过滤掉 `after` 与 before 相同、或违反硬约束（每日超容量、动锁定项、超预算、无必要变更）的方案。
- 里程碑风险不是硬失败，但必须在 `impact.milestoneRiskCount` 与方案摘要里明确展示，由用户决定。
- 去重：`changes` 完全相同的方案只保留一个。
- 推荐顺序：`compress`（不丢任务）→ `spill`（里程碑安全）→ `drop` → `lower_target`。
- 方案数 ≥ 2 时返回 `options`；只有 1 个时按普通调整单返回（仍带 `recommendedId`）。
- 方案数量上限 4，避免客户端选择过载。
- 所有方案共用同一份 `before` 快照与指纹；`afterJson` 初始为推荐方案，`optionsJson` 存全部方案的 after 与 impact。
- 对外序列化只返回每个方案的 `changes`、`impact` 与摘要；`after`/`optionsJson` 仅服务端使用，避免把整窗快照重复下发。

### 6.5 L2 改造

- 输入增加：锁定项、逐科预算、里程碑、用户明确诉求、允许的操作（add/drop/scope）。
- 输出仍为 `{ tasks: [{dayOffset, subject, title, minutes}] }`；服务端展开为 after 快照。
- 校验模式：
  - `conserve`（unavailable/reduce_capacity/change_commitment）：任务集合与总分钟数守恒（允许压缩后的四舍五入 ±1 分钟），锁定项不变；
  - `add`：允许新增指定任务，总量 = 原总量 + 新增分钟；
  - `drop`：允许删除指定任务；
  - `scope`：允许减少任务总量以匹配新的目标。
- 任何模式下都校验：科目在 brief 内、每日 ≤ 容量、未锁定项可改、锁定项字段完全一致、里程碑不因移动而恶化。
- 校验失败把原因作为 `repairHint` 重试一次；仍失败 → 方案引擎，不直接 502。
- 新增任务使用 `id<=0` 的临时项，确认时插入并分配 stageId；删除项在 after 中缺席，确认时按窗口删除。

### 6.6 确认与撤销安全

`windowFingerprint` 扩展：

```ts
hash(`${id}:${planDate}:${minutes}:${subject}:${title}:${locked}:${startTime ?? ''}:${slot ?? ''}`)
+ '|' + briefContextHash
```

- 只统计窗口内、非 done 项；done 的打卡不使调整单失效。
- `briefContextHash` 覆盖 `examDate + studyWindows + brief`，作息/口径变化也会使旧调整单失效。
- 调整单生成时存 `fingerprint`；确认时重算，不一致 → 409 `ADJUSTMENT_STALE`。
- `change_commitment` / `adjust_scope` 的 `fingerprint` 使用**调整前**的档案上下文（`profileBeforeJson`），确认后写入 `afterFingerprint` 时使用**调整后**的档案上下文（`profileAfterJson`）；撤销按调整后上下文比对。

确认流程（单事务）：

1. 校验调整单属于用户、状态 draft、对应 active 计划未变。
2. 若存在 `profileAfterJson`：校验当前 brief 仍等于 `profileBeforeJson`，否则 409 `ADJUSTMENT_STALE`。
3. 指纹校验。
4. 应用 `afterJson`（含新增/删除/改期/改内容），保留 done 与锁定项。
5. 对窗口内所有日期重排时段，写回 `slot/startTime`；更新 `stageId`。
6. 若有 `profileAfterJson`，写回 `UserProfile.briefJson`。
7. 读取最终窗口快照，计算并写入 `afterFingerprint`。
8. 标记 applied，更新计划 `updatedAt`。

撤销流程：

- 只允许最近一次 applied，且其后没有新打卡（现有规则）。
- 重新计算当前窗口指纹并与 `afterFingerprint` 比较；不一致 → 409 `ADJUSTMENT_UNDO_STALE`（“调整后你手工改过计划，撤销会覆盖这些修改；请再描述一次新的调整”）。
- 恢复 `beforeJson`，重排时段；若有 `profileBeforeJson`，恢复档案。
- 标记 undone。

`change_commitment` / `adjust_scope` 调整单：

- 生成时只构造 `briefAfter`，**不写档案**；`profileBeforeJson/profileAfterJson` 落调整单。
- 容量计算用 `briefAfter`（例如新增周三晚课后的净空闲），保证调整结果与确认后的档案一致。
- 用户不确认 → 档案完全不变。

### 6.7 方案选择接口

```http
POST /plans/adjustments/:id/select
body: { optionId: "compress" | "spill" | "drop" | "lower_target" }
→ { adjustment, serverTime }
```

- 校验 draft、指纹、选项存在；把该选项的 after 写入 `afterJson`，`selectedOption=optionId`，重新计算 changes 并返回。
- 不改变 `beforeJson`/`fingerprint`。
- 客户端可在确认前多次切换；确认时以最近一次选择为准。
- 旧客户端不调用此接口，默认使用推荐方案（`afterJson`）。

### 6.8 追问与文案

- 追问只问一个最关键的问题：哪一天 / 哪项任务 / 调整到哪天 / 新增多久。
- 追问时附带可点的快捷选项（客户端根据 `clarifyOptions` 渲染）；服务端可选返回：
  ```ts
  clarifyOptions?: Array<{ label: string; value: string }>
  ```
- 调整单回复文案区分档位：
  - L1：「已按快速顺延算好，确认后生效。」
  - L2：「已重新排过，确认后生效。」
  - 多方案：「有 3 种处理方式，影响不同，选一个再确认。」
- 闲聊回复不承诺「已经改好」，统一用「可以这样调整，需要你确认」。

## 7. 客户端交互

### 7.1 生成入口与口径确认

- 面谈收尾 / 重新生成前，调用 `GET /plans/brief`。
- 若 `briefHash` 与本机记录的上次确认值不同，弹出 `PlanBriefSheet`：
  - 标题「生成前，先确认计划口径」；
  - 科目行：科目、剩余量、里程碑、截止日、估算标记；可编辑剩余量/截止日/估算；
  - 可用时间：按星期汇总（只读）+「调整作息」入口；
  - 固定占用：列表（只读）+「调整」入口；
  - 预算预览：确认任务 X 分钟 / 有效容量 Y 分钟 / 预计留白 Z%；缺口警告；
  - 主按钮「确认无误，生成计划」；次按钮「先改口径」。
- 生成请求带 `briefHash`。收到 `PLAN_BRIEF_CHANGED` → 重新拉取并展示最新口径。
- 收到 `PLAN_CAPACITY_INSUFFICIENT` → `CapacityOptionsDialog`（调作息 / 降目标 / 生成尽力版）。
- 尽力版二次确认文案：「部分科目可能无法在里程碑前完成，草稿会标注缺口。生成后仍可手工调整。」

### 7.2 生成进度

- ETA 使用 4.6 的区间，不再写「1-3 分钟」。
- 显示阶段清单与当前阶段高亮、已用时、可离开提示。
- 失败卡片展示错误码映射文案 + 「重试」「去改口径」。

### 7.3 草案预览

- 顶部：标题、版本、日期区间、总量进度（已排 / 目标 / 有效容量）。
- 科目预算表：科目、目标、已排、差异、里程碑前完成量；差异超过 5% 标色。
- 警告区：容量缺口、里程碑风险、估算科目提示。
- 今日变化：沿用现有 diff。
- 阶段表：阶段目标 vs 实排。
- 「确认启用」仍要求档案/进度未变化。

### 7.4 计划页编辑

- 日程行左侧复选框独立点击；行点击打开编辑面板。
- 编辑面板字段与 5.6 一致；保存中禁用。
- 删除需二次确认；删除后释放的容量实时反映在当日进度。
- 添加任务面板默认选中当前日期；科目下拉来自 brief；时长默认 30 分钟。
- 错误处理：
  - `PLAN_DAY_OVERLOADED` → 在日期/时长字段下方显示「当天只剩 X 分钟，超出 Y 分钟」，并提供「缩短到 X 分钟」快捷按钮；
  - `PLAN_ITEM_LOCKED` 等只读错误 → 顶部横幅；
  - 网络错误 → 保留输入，可重试。
- 锁定项显示锁图标；锁定开关切换调用 PATCH，乐观更新。

### 7.5 调整对话

- 输入区上方快捷 chips：
  - 「今天临时有事」
  - 「这周时间变少」
  - 「想多练数学」
  - 「任务太多，减一点」
  - 「帮我重新排一下」
- 助手回复：
  - 追问 → 气泡 + 可点选项；
  - 单方案 → 现有 AdjustmentCard（tier、summary、changes、预览、确认）；
  - 多方案 → 方案卡列表，默认选中推荐项；每张卡显示 label、summary、影响指标、风险提示；点击调用 select，再展开预览。
- 预览使用现有 HTML 预览 + 选项 changes；`buildAdjustmentPreview` 支持按选中方案应用 changes。
- 确认按钮文案「按这个方案调整」；确认后卡片变为「已生效 · 撤销」，撤销失败展示 `ADJUSTMENT_UNDO_STALE` 文案。
- `ADJUSTMENT_STALE` → 提示「计划刚被改过，这张调整单已失效」，保留输入并给「重新计算」按钮。
- `LOCKED_ITEMS_CONFLICT` → 弹窗列出锁定项，提供「去解锁」跳转计划页。
- 调整中禁用发送；L2 显示「正在重新安排…」，L1/方案引擎通常秒回。

### 7.6 主动教练

- `GET /plans/checkup` 增加 `suggestedMessage`：
  - 落后/过期：「我有几项任务落后了，帮我把未来几天重新排一下」
  - 超载：「接下来几天安排太满，帮我压缩一下」
  - 完成率偏低：「最近完成率偏低，帮我把计划强度调低一点」
- 计划页顶部体检卡展示建议 + 「让 AI 调整」；点击进入对话并预填 `suggestedMessage`。
- 首页现有体检卡复用同一文案；只建议，不自动应用。

### 7.7 客户端文件拆分

- 新增：
  - `ui/plan/PlanBriefSheet.kt`
  - `ui/plan/PlanItemEditSheet.kt`
  - `ui/plan/AdjustmentOptions.kt`
  - `ui/plan/CapacityOptionsDialog.kt`
  - `ui/plan/PlanBudgetTable.kt`
- 修改：
  - `PlanInterviewViewModel`：brief 拉取/确认、生成错误分支、方案选择、快捷话术；
  - `PlanViewModel`：手工编辑/添加/删除/锁定、乐观更新与回滚；
  - `PlanScreen`：行点击/复选框分离、添加任务、锁定图标、教练卡；
  - `PlanDraftPreview`：预算表与警告；
  - `Api.kt`：新 DTO 与接口；
  - `strings`/文案映射：新错误码。

## 8. 测试与验收

### 8.1 服务端单元测试

- `planBudget.test.ts`
  - 确认/估算科目预算、85% 留白、里程碑缺口、尽力版缩放、复盘上限。
  - 边界：无 brief、零容量、考试日边界、剩余量为 0。
- `reflow.test.ts`
  - 固定占用、done/锁定占位、窗口碎片、跨时段排序、幂等。
- `planOptions.test.ts`
  - 四类方案的可行性、锁定项不变、每日不超容量、里程碑风险、去重、推荐顺序、changes 与 impact 一致。
- `adjust.test.ts`
  - 30 天窗口、过期任务纳入、指纹字段、L1 里程碑截止、锁定冲突。
- `adjustL2.test.ts`
  - conserve/add/drop/scope 四种模式、锁定保持、失败降级。
- `adjustIntent.test.ts`
  - 新意图解析、日期窗口校验、任务消歧、追问、闲聊。
- `generator.test.ts` / `planSpec.test.ts`
  - 预算约束、兜底生成器不再每日填满、THIN_DAY 与预算兼容、超发拦截。
- `service.test.ts`
  - 手工编辑、添加/删除、容量错误、锁定、确认/撤销、档案延迟写入与恢复、afterFingerprint。
- `checkup.test.ts`
  - `suggestedMessage`。

### 8.2 服务端接口测试

用一次性测试账号跑真实 API（不读其它用户数据）：

1. `GET/PATCH /plans/brief` 往返，briefHash 变化。
2. 生成带错误 hash → 409；正确 hash → job；进度 → draft。
3. 预算不足 → 409 details；`allowShortfall` → draft warnings。
4. 手工改期/改时长/新增/删除/锁定 → 读回一致；超容量 → 409。
5. 调整 L1 → confirm → slot/startTime 更新 → undo。
6. 调整多方案 → select → confirm → undo；手工改后 undo → 409。
7. `change_commitment` 确认前档案不变；确认后变；undo 还原。
8. 锁定项在 20 组随机调整中保持不变。
9. 30 天窗口外日期请求 → 追问/拒绝。
10. 旧客户端请求（无 briefHash/options）仍成功。

### 8.3 客户端与模拟器 E2E

1. 新用户：面谈 → 口径确认（改一项剩余量）→ 生成 → 进度 → 草案预算表 → 确认启用。
2. 窄作息：生成缺口弹窗 → 调作息 → 重新生成成功；或尽力版 → 草案警告可见。
3. 手工编辑：改日期、改时长、加任务、删任务、锁定；超容量错误文案与建议可用。
4. 调整 L1：明天有事 → 单方案 → 预览 → 确认 → 时间轴更新 → 撤销。
5. 调整多方案：这周每天只能学 2 小时 → 3 个方案 → 选压缩 → 预览 → 确认。
6. 锁定保护：锁数学 → 请求覆盖该时间 → 冲突弹窗 → 解锁后重试。
7. 安全：调整生效后手工改任务 → 撤销被拒且文案清晰。
8. 档案延迟：说「每周三晚上有课」→ 不确认 → 档案不变；确认 → 变；撤销 → 还原。
9. 追问与闲聊：模糊请求 → 一次追问 → 补充后出方案；“我进度是不是慢了”只回复。
10. 回归：勾选、计划历史、长文档、同步、OTA、账号安全。

### 8.4 验收标准（必须全部满足）

- **预算**：正常生成中，每个确认科目实排 ≥ 剩余量且 ≤ 剩余量×1.05+30；每个估算科目 ≤ 预算×1.05；科目任务总量 ≤ target×1.05；含复盘总量 ≤ target×1.10；每日 ≤ 85% 目标（尽力版除外）；里程碑前完成量 ≥ 里程碑目标（尽力版按缩放后目标）。
- **计划量**：同一份 3,000 分钟剩余任务、400 天跨度的档案，科目任务总量不超过 3,150 分钟、含复盘不超过 3,300 分钟，不再出现 15,000+ 分钟。
- **手工编辑**：所有编辑刷新后仍在；超容量 100% 被拒且给出具体余量；锁定的 20 组随机调整中 0 次被移动/删除/改内容。
- **调整**：L1 端到端 p95 < 8 秒（含意图解析）、计算段 p95 < 2 秒；确定性方案在意图解析后 p95 < 3 秒；L2 失败必降级方案；确认/撤销全事务；撤销在手工修改后必被拒。
- **档案安全**：未确认的 `change_commitment`/`adjust_scope` 对档案 0 副作用；确认后生效；撤销后完全还原。
- **窗口**：调整只处理 `[today-30, today+29]` 与更早未完成项（不早于 today-30）；窗口外请求有明确提示。
- **兼容**：旧客户端（1.0.0-m3）在档案满足新预算约束时仍能生成、勾选、调整、确认、撤销；新字段可忽略。
- **体验**：生成 ETA 与实际差距 ≤ 5 分钟；草案预算表与 overview 数字一致；所有新错误码有中文文案；模拟器全流程无崩溃。

## 9. 上线、兼容与风险

### 9.1 数据库变更

```prisma
model PlanItem {
  locked Boolean @default(false)
}

model PlanAdjustment {
  afterFingerprint String? @db.VarChar(64)
  optionsJson      String? @db.MediumText
  selectedOption   String? @db.VarChar(32)
  profileBeforeJson String? @db.Text
  profileAfterJson  String? @db.Text
}
```

- 只用 `prisma db push` 加列，均有默认值/可空，不需要回填。
- 服务端先部署（新增接口向后兼容），再发 App。
- 旧调整单在部署后指纹口径变化会失效（30 分钟 TTL 内），属于可接受的一次性影响；部署说明中注明。

### 9.2 兼容策略

- `POST /plans/generate/jobs` 的 `briefHash`/`allowShortfall` 可选；旧客户端不带时按旧逻辑生成（但仍受新预算模型约束）。
- `POST /plans/adjust` 响应新增 `adjustment.options` 与 `clarifyOptions`，旧客户端忽略。
- `POST /plans/adjustments/:id/select` 是新增路由，旧客户端不调用。
- `PATCH /plans/items/:id` 继续接受 `{status}`，旧客户端行为不变。
- 新客户端遇到旧服务端（404/405）：隐藏 brief sheet、编辑入口与方案卡，回退到原流程，并提示「服务端需更新」。
- `GET /plans/brief`、`GET /plans/:id/overview` 仅新客户端使用。

### 9.3 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| AI 生成仍不满足预算，反复重试 | 校验明确、repairHint 具体；确定性兜底生成器按预算填充；3 次失败如实报错 |
| 多方案计算与 AI 结果不一致 | 方案引擎纯函数、单一 after 来源；AI 结果也走同一校验与方案后处理 |
| 锁定项导致无解 | 明确 `LOCKED_ITEMS_CONFLICT`，提供解锁入口，不自动改锁 |
| 手工编辑与调整并发 | 指纹 + 事务 + `ADJUSTMENT_STALE`；客户端提示重新计算 |
| 档案延迟写入遗漏撤销 | `profileBeforeJson/AfterJson` 同事务写入/恢复；单元测试覆盖 |
| 30 天窗口内任务过多，响应变大 | changes 截断展示但保留计数；必要时分页/摘要（后续） |
| 旧调整单失效 | 部署说明 + 客户端 409 文案引导重新发起 |
| 每日 85% 目标与用户手动加满冲突 | 生成目标 85%，手工硬上限 100%，语义区分清楚 |

### 9.4 实施阶段

1. **服务端预算与口径**：schema 加列、`planBudget`、brief/overview 接口、生成器/校验改造、生成缺口 details、单元测试。
2. **服务端手工编辑**：`reflow`、item PATCH/POST/DELETE、容量校验、锁定、测试。
3. **服务端调整引擎**：30 天窗口、意图扩展、L1 里程碑、方案引擎、L2 模式、指纹/撤销/档案延迟、select 接口、测试。
4. **客户端生成体验**：DTO/API、BriefSheet、缺口弹窗、ETA、草案预算表。
5. **客户端编辑与调整**：编辑面板、添加/删除/锁定、行点击拆分、方案卡、快捷话术、教练卡、错误文案。
6. **全量验证**：服务端 `npm run verify`、Android 单测/构建、模拟器 E2E、回归、文档更新；经确认后提交/推送/部署。
