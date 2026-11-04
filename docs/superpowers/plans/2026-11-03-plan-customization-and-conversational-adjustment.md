# 计划定制与对话式调整实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让计划生成受总预算约束、每日任务可手工改/加/删/锁，并让对话调整在容量不足时给出可比较的多方案，确认/撤销全程可回滚且不覆盖手工修改。

**Architecture:** 在现有 Plan/PlanStage/PlanItem/PlanAdjustment 上增量扩展：新增纯函数层 `planBudget.ts`、`reflow.ts`、`planOptions.ts`；调整引擎沿用 L1（纯算法顺延）→ L2（AI 重排）→ 确定性方案兜底；客户端新增口径确认、任务编辑、方案选择与主动教练入口。所有模型输出仍由服务端硬规则校验。

**Tech Stack:** Koa + TypeScript + Prisma/MySQL（服务端）；Kotlin + Jetpack Compose Material3（Android）；Node 原生 test runner + tsx；JUnit4。

**Spec:** `docs/superpowers/specs/2026-11-02-plan-customization-and-conversational-adjustment-design.md`

## Global Constraints

- 只在 `main` 工作，不新建分支。
- 不自动 `git push` / 部署；提交按阶段批次执行，部署需用户单独确认。
- 不把服务器凭据写进仓库或日志；只用一次性测试账号验证。
- 服务端数据库只做加列，继续 `prisma db push`，不建迁移目录。
- 旧客户端（无 `briefHash` / 无 `options` 请求）必须继续可用。
- 生成阶段每日目标 85%，手工编辑硬上限 100%，任何写操作不允许 `force` 绕过。
- 锁定项在 L1/L2/方案引擎中永不移动、不改内容、不删除；`lock_task` 是唯一确认前直接写库的意图。
- 所有面向用户的错误必须有中文文案；服务端错误信封为 `{ error, message, details? }`。
- 验证命令：
  - 服务端单测：`cd server && node --import tsx --test src/modules/planning/<name>.test.ts`
  - 服务端全量：`cd server && npm run verify`
  - Android 单测/构建（Windows）：`powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK'"`

---

## File Structure

**服务端新增**
- `server/src/modules/planning/planBudget.ts`：预算、规范化 brief、哈希、草稿 overview。
- `server/src/modules/planning/reflow.ts`：单日时段重排。
- `server/src/modules/planning/planOptions.ts`：compress/spill/drop/lower_target 方案引擎。
- `server/src/modules/planning/planBudget.test.ts`
- `server/src/modules/planning/reflow.test.ts`
- `server/src/modules/planning/planOptions.test.ts`
- `server/src/modules/planning/adjust.test.ts`
- `server/src/modules/planning/generationJobs.test.ts`

**服务端修改**
- `server/prisma/schema.prisma`
- `server/src/middlewares/error.ts`
- `server/src/modules/planning/document.ts`（导出可复用的日期辅助）
- `server/src/modules/planning/adjust.ts`
- `server/src/modules/planning/adjustIntent.ts`
- `server/src/modules/planning/adjustL2.ts`
- `server/src/modules/planning/generator.ts`
- `server/src/modules/planning/planSpec.ts`
- `server/src/modules/planning/refineStage.ts`
- `server/src/modules/planning/aiGenerator.ts`
- `server/src/modules/planning/service.ts`
- `server/src/modules/planning/routes.ts`
- `server/src/modules/planning/generationJobs.ts`
- `server/src/modules/planning/checkup.ts`
- 现有测试同步更新。

**客户端新增**
- `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanBriefSheet.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanItemEditSheet.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AdjustmentOptions.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/CapacityOptionsDialog.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanBudgetTable.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanErrors.kt`
- `YanZhong/app/src/test/java/com/yanzhong/app/ui/plan/PlanBudgetTest.kt`
- `YanZhong/app/src/test/java/com/yanzhong/app/ui/plan/PlanEditTest.kt`

**客户端修改**
- `data/remote/Api.kt`
- `ui/plan/PlanInterviewViewModel.kt`
- `ui/plan/PlanInterviewScreen.kt`
- `ui/plan/PlanViewModel.kt`
- `ui/plan/PlanScreen.kt`
- `ui/plan/PlanDraftPreview.kt`
- `ui/plan/AdjustmentCard.kt`
- `ui/home/HomeScreen.kt`（体检卡按钮复用 `suggestedMessage`，若已有预填入口则只改文案来源）

---

### Task 1: 数据模型、错误信封与公开类型

**Files:**
- Modify: `server/prisma/schema.prisma`
- Modify: `server/src/middlewares/error.ts`
- Modify: `server/src/modules/planning/service.ts`
- Test: `server/src/middlewares/error.test.ts`（新建）

**Interfaces:**
- Produces: `ApiError(status, code, message, details?)`；`PublicPlanItem.locked`；`PublicAdjustment.options`、`selectedOption`。

- [ ] **Step 1: 写失败测试**

```ts
// server/src/middlewares/error.test.ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError, errorMiddleware } from './error'

test('ApiError details 随错误信封返回', async () => {
  const ctx: any = { status: 200, body: null }
  const next = async () => { throw new ApiError(409, 'PLAN_DAY_OVERLOADED', '当天排不下', { date: '2026-11-03', overflow: 30 }) }
  await errorMiddleware()(ctx, next)
  assert.equal(ctx.status, 409)
  assert.equal(ctx.body.error, 'PLAN_DAY_OVERLOADED')
  assert.deepEqual(ctx.body.details, { date: '2026-11-03', overflow: 30 })
})
```

- [ ] **Step 2: 运行并确认失败**

Run: `cd server && node --import tsx --test src/middlewares/error.test.ts`
Expected: FAIL（`details` 未返回 / 构造器参数不足）。

- [ ] **Step 3: 实现**

`error.ts`：

```ts
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public details?: unknown) {
    super(message)
  }
}
// errorMiddleware 的 ApiError 分支：
ctx.body = { error: err.code, message: err.message, ...(err.details === undefined ? {} : { details: err.details }) }
```

`schema.prisma`：

```prisma
model PlanItem {
  // ...现有字段
  locked Boolean @default(false)
}

model PlanAdjustment {
  // ...现有字段
  afterFingerprint  String? @db.VarChar(64)
  optionsJson       String? @db.MediumText
  selectedOption    String? @db.VarChar(32)
  profileBeforeJson String? @db.Text
  profileAfterJson  String? @db.Text
}
```

`service.ts`：`PublicPlanItem` 增加 `locked: boolean`；`serializePlan` 的 items 映射增加 `locked: i.locked === true`；`PublicAdjustment` 增加：

```ts
options: Array<{
  id: string; label: string; summary: string
  impact: Record<string, number>
  changes: ReturnType<typeof diffSnapshots>
}> | null
selectedOption: string | null
```

- [ ] **Step 4: 运行测试并 typecheck**

Run: `cd server && node --import tsx --test src/middlewares/error.test.ts && npm run typecheck`
Expected: PASS；typecheck 通过。

- [ ] **Step 5: 提交**

```bash
git add server/prisma/schema.prisma server/src/middlewares/error.ts server/src/middlewares/error.test.ts server/src/modules/planning/service.ts
git commit -m "feat(plan): add item lock and adjustment option fields"
```

---

### Task 2: 预算纯函数模块

**Files:**
- Create: `server/src/modules/planning/planBudget.ts`
- Create: `server/src/modules/planning/planBudget.test.ts`

**Interfaces:**
- Consumes: `PlanBrief`、`netAvailableMinutes`、`assessPlanningFacts`。
- Produces: `PLAN_LOAD_FACTOR`、`PlanBudget`、`PlanBudgetView`、`buildPlanBudget(brief,startDate,examDate,{allowShortfall?})`、`budgetView(budget)`、`canonicalBrief(brief,examDate)`、`briefHash(brief,examDate)`、`briefContextHash(brief,examDate)`、`buildPlanOverview(plan,brief,stages)`。

- [ ] **Step 1: 写失败测试**

覆盖：
1. 每天 4 小时、10 天、确认科目剩余 1000 分钟 → `effectiveCapacityMinutes = floor(2400*0.85)=2040`，`targetMinutes=1000`。
2. 估算科目剩余 5000、确认 3000、容量 4000 → 估算预算 400；按比例分到各估算科目。
3. 确认剩余 5000、有效容量 4000 → `shortfallMinutes=1000`；`allowShortfall` 后预算缩放到 4000。
4. `briefHash` 对 `summary/goals/notes` 不敏感，对剩余量/里程碑敏感。
5. `dayCap` 为每日净空闲的 85%。

测试骨架：

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { buildPlanBudget, briefHash, type PlanBudget } from './planBudget'
import type { PlanBrief } from './document'

function brief(over: Partial<PlanBrief> = {}): PlanBrief {
  return {
    summary: '', goals: [], constraints: [], focus: [], materials: [], notes: [],
    examSubjects: [
      { name: '数学', progress: '一轮', scope: '高数', remainingMinutes: 1000, milestone: '强化', milestoneDate: '2026-11-20', milestoneMinutes: 400, estimated: false },
    ],
    availability: [1, 2, 3, 4, 5, 6, 7].map(weekday => ({ weekday, windows: [{ start: '08:00', end: '12:00' }] })),
    fixedCommitments: [], periods: [], availabilityConfirmed: true, commitmentsConfirmed: true,
    ...over,
  }
}
const start = new Date('2026-11-03T00:00:00.000Z')
const exam = new Date('2026-11-13T00:00:00.000Z')

test('预算按 85% 留白并保留剩余任务', () => {
  const budget = buildPlanBudget(brief(), start, exam)
  assert.equal(budget.capacityMinutes, 2400)
  assert.equal(budget.effectiveCapacityMinutes, 2040)
  assert.equal(budget.targetMinutes, 1000)
})
```

- [ ] **Step 2: 运行并确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/planBudget.test.ts`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 实现**

```ts
import { createHash } from 'node:crypto'
import { assessPlanningFacts, netAvailableMinutes, type PlanBrief } from './document'
import { dayStart, diffDays } from './schemas'

export const PLAN_LOAD_FACTOR = Math.min(1, Math.max(0.5, Number(process.env.PLAN_LOAD_FACTOR ?? 0.85) || 0.85))
export const REVIEW_SUBJECT = '复盘'
const DAY_MS = 86_400_000

export interface PlanBudgetSubject {
  name: string
  estimated: boolean
  remainingMinutes: number
  budgetMinutes: number
  upperMinutes: number
  milestone: string
  milestoneDate: string
  milestoneMinutes: number
  milestoneBudgetMinutes: number
}

export interface PlanBudget {
  capacityMinutes: number
  effectiveCapacityMinutes: number
  confirmedMinutes: number
  estimatedMinutes: number
  estimatedBudgetMinutes: number
  targetMinutes: number
  shortfallMinutes: number
  deficits: Array<{ subject: string; milestone: string; missingMinutes: number }>
  dayCap: Map<string, number>
  subjects: PlanBudgetSubject[]
  warnings: string[]
}

export interface PlanBudgetView { /* 去掉 dayCap；subjects 只留展示字段 */ }

export function buildPlanBudget(brief: PlanBrief, startDate: Date, examDate: Date,
  options: { allowShortfall?: boolean } = {}): PlanBudget {
  const start = dayStart(startDate)
  const exam = dayStart(examDate)
  const dayCap = new Map<string, number>()
  let capacityMinutes = 0
  for (let cursor = start.getTime(); cursor < exam.getTime(); cursor += DAY_MS) {
    const date = new Date(cursor)
    const day = date.toISOString().slice(0, 10)
    const capacity = Math.max(0, netAvailableMinutes(brief, date))
    capacityMinutes += capacity
    dayCap.set(day, Math.floor(capacity * PLAN_LOAD_FACTOR))
  }
  const effectiveCapacityMinutes = Math.floor(capacityMinutes * PLAN_LOAD_FACTOR)
  const confirmed = brief.examSubjects.filter(subject => !subject.estimated)
  const estimated = brief.examSubjects.filter(subject => subject.estimated)
  const sum = (list: typeof confirmed) => list.reduce((total, subject) => total + Math.max(0, subject.remainingMinutes), 0)
  const confirmedMinutes = sum(confirmed)
  const estimatedMinutes = sum(estimated)
  const shortfallMinutes = Math.max(0, confirmedMinutes - effectiveCapacityMinutes)
  const confirmedScale = options.allowShortfall && shortfallMinutes > 0 && confirmedMinutes > 0
    ? effectiveCapacityMinutes / confirmedMinutes : 1
  const confirmedBudget = Math.floor(confirmedMinutes * confirmedScale)
  const estimatedBudgetMinutes = Math.max(0, Math.min(estimatedMinutes, Math.max(0, effectiveCapacityMinutes - confirmedBudget)))
  const estimatedScale = estimatedMinutes > 0 ? estimatedBudgetMinutes / estimatedMinutes : 1
  const subjects = brief.examSubjects.map(subject => {
    const budget = Math.floor(subject.remainingMinutes * (subject.estimated ? estimatedScale : confirmedScale))
    const milestoneBudget = Math.floor(subject.milestoneMinutes * (subject.estimated ? 1 : confirmedScale))
    return {
      name: subject.name, estimated: subject.estimated,
      remainingMinutes: subject.remainingMinutes, budgetMinutes: budget,
      upperMinutes: subject.estimated ? Math.floor(budget * 1.05) : Math.floor(budget * 1.05) + 30,
      milestone: subject.milestone, milestoneDate: subject.milestoneDate,
      milestoneMinutes: subject.milestoneMinutes,
      milestoneBudgetMinutes: Math.max(0, Math.min(milestoneBudget, budget)),
    }
  })
  const facts = assessPlanningFacts(brief, start, exam, true)
  const warnings: string[] = []
  if (shortfallMinutes > 0 && !options.allowShortfall) warnings.push(`已确认任务超出有效容量 ${shortfallMinutes} 分钟`)
  if (estimatedMinutes > estimatedBudgetMinutes) warnings.push(`估算科目已按剩余容量缩放至 ${estimatedBudgetMinutes} 分钟`)
  return {
    capacityMinutes, effectiveCapacityMinutes, confirmedMinutes, estimatedMinutes,
    estimatedBudgetMinutes, targetMinutes: subjects.reduce((total, subject) => total + subject.budgetMinutes, 0),
    shortfallMinutes, deficits: options.allowShortfall ? [] : facts.deficits,
    dayCap, subjects, warnings,
  }
}
```

`canonicalBrief` 只挑影响排课的字段并排序：`examSubjects` 按 name，`availability` 按 weekday、windows 按 start，`fixedCommitments` 按 weekday/start/end/label，`periods` 按 start/end。`briefHash` 与 `briefContextHash` 都返回 `sha256(canonicalBrief + examDate)`（口径一致，避免两套哈希漂移）。

`buildPlanOverview(plan, brief, stages)` 返回 spec §4.4 的结构：逐日 `capacityMinutes/dayCapMinutes/scheduledMinutes/itemCount`、逐科 target/scheduled/beforeMilestone/delta、阶段 target/scheduled、warnings（shortfall、`SUBJECT_OVER_BUDGET`、`MILESTONE_RISK`）。

- [ ] **Step 4: 运行测试**

Run: `cd server && node --import tsx --test src/modules/planning/planBudget.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/planBudget.ts server/src/modules/planning/planBudget.test.ts
git commit -m "feat(plan): add budget model and overview builders"
```

---

### Task 3: Brief 读取/修改接口

**Files:**
- Modify: `server/src/modules/planning/service.ts`
- Modify: `server/src/modules/planning/routes.ts`
- Test: `server/src/modules/planning/service.test.ts`

**Interfaces:**
- Produces: `getBrief(userGuid)`、`patchBrief(userGuid, input)`；`GET/PATCH /api/v1/plans/brief`。
- Consumes: `buildPlanBudget`、`briefHash`、`generationJobs.runningFor`。

- [ ] **Step 1: 写失败测试**

在 `service.test.ts` 增加：
1. `getBrief` 返回 `briefHash`、`budget.targetMinutes`、`capacityByWeekday` 7 项。
2. `patchBrief` 按 name 修改剩余量与里程碑，返回的 hash 改变。
3. 未知科目 → `INVALID_PARAMS`，`details` 含字段名。
4. `milestoneMinutes > remainingMinutes` → 400。
5. 有运行中生成任务时 PATCH → 409 `PLAN_GENERATION_RUNNING`。

- [ ] **Step 2: 运行并确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/service.test.ts`
Expected: FAIL（方法不存在）。

- [ ] **Step 3: 实现**

在 `createPlanningService` 的返回对象中加：

```ts
async getBrief(userGuid: string) {
  const row = await requireProfileRow(userGuid)
  const brief = briefFromRow(row)
  const today = dayStart(new Date())
  const examDate = toDay(row.examDate)
  const budget = buildPlanBudget(brief, today, examDate)
  return {
    brief, briefHash: briefHash(brief, dateOnly(row.examDate)),
    profile: {
      targetType: row.targetType, examDate: dateOnly(row.examDate),
      foundation: row.foundation ?? null, weakSubjects: jsonArray(row.weakSubjectsJson),
    },
    budget: budgetView(budget),
    capacityByWeekday: capacityByWeekday(brief, today, examDate),
    warnings: budget.warnings,
  }
},

async patchBrief(userGuid: string, input: BriefPatchInput) {
  if (generationJobs.runningFor(userGuid)) {
    throw new ApiError(409, 'PLAN_GENERATION_RUNNING', '计划正在生成,完成后再改口径')
  }
  const row = await requireProfileRow(userGuid)
  const brief = briefFromRow(row)
  const examDate = dateOnly(row.examDate)
  const today = dateOnly(dayStart(new Date()))
  const details: Array<{ field: string; message: string }> = []
  const known = new Map(brief.examSubjects.map(subject => [subject.name, subject]))
  for (const patch of input.examSubjects ?? []) {
    const subject = known.get(patch.name)
    if (!subject) { details.push({ field: `examSubjects.${patch.name}`, message: '科目不在已确认的考试科目中' }); continue }
    if (patch.remainingMinutes !== undefined) {
      if (!Number.isSafeInteger(patch.remainingMinutes) || patch.remainingMinutes < 0 || patch.remainingMinutes > 500000) {
        details.push({ field: `examSubjects.${patch.name}.remainingMinutes`, message: '剩余量需在 0..500000 分钟' })
      } else subject.remainingMinutes = patch.remainingMinutes
    }
    if (patch.milestone !== undefined) subject.milestone = String(patch.milestone).slice(0, 64)
    if (patch.milestoneDate !== undefined) {
      if (patch.milestoneDate < today || patch.milestoneDate >= examDate) {
        details.push({ field: `examSubjects.${patch.name}.milestoneDate`, message: `截止日需在 ${today} 与考前一天之间` })
      } else subject.milestoneDate = patch.milestoneDate
    }
    if (patch.milestoneMinutes !== undefined) {
      if (!Number.isSafeInteger(patch.milestoneMinutes) || patch.milestoneMinutes < 0 || patch.milestoneMinutes > subject.remainingMinutes) {
        details.push({ field: `examSubjects.${patch.name}.milestoneMinutes`, message: '里程碑量不能超过剩余量' })
      } else subject.milestoneMinutes = patch.milestoneMinutes
    }
    if (patch.estimated !== undefined) subject.estimated = patch.estimated
    if (patch.scope !== undefined) subject.scope = String(patch.scope).slice(0, 200)
    if (patch.progress !== undefined) subject.progress = String(patch.progress).slice(0, 200)
  }
  if (input.fixedCommitments !== undefined) {
    brief.fixedCommitments = input.fixedCommitments
    brief.commitmentsConfirmed = true
  }
  if (details.length > 0) throw new ApiError(400, 'INVALID_PARAMS', '计划口径有字段不合法', { fields: details })
  await db.userProfile.update({ where: { userGuid }, data: { briefJson: JSON.stringify(brief), updatedAt: new Date() } })
  return this.getBrief(userGuid)
}
```

`capacityByWeekday`：遍历 `[today, examDate)`，按 ISO weekday 累加 `netAvailableMinutes`，除以该星期出现次数并取整。

路由（必须注册在 `/plans/:id` 之前）：

```ts
const briefPatchSchema = z.object({
  examSubjects: z.array(z.object({
    name: z.string().min(1).max(32),
    remainingMinutes: z.number().int().min(0).max(500000).optional(),
    milestone: z.string().min(1).max(64).optional(),
    milestoneDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
    milestoneMinutes: z.number().int().min(0).max(500000).optional(),
    estimated: z.boolean().optional(),
    scope: z.string().max(200).optional(),
    progress: z.string().max(200).optional(),
  })).max(20).optional(),
  fixedCommitments: z.array(z.object({
    weekday: z.number().int().min(1).max(7), start: z.string(), end: z.string(), label: z.string().max(32),
  })).max(50).optional(),
})
router.get('/plans/brief', requireAuth, async ctx => { ctx.body = { ...await planningService.getBrief(...), serverTime: Date.now() } })
router.patch('/plans/brief', requireAuth, apiLimit(), async ctx => {
  const parsed = briefPatchSchema.safeParse(ctx.request.body)
  if (!parsed.success) throw parsed.error
  ctx.body = { ...await planningService.patchBrief(...), serverTime: Date.now() }
})
```

- [ ] **Step 4: 运行测试 + typecheck**

Run: `cd server && node --import tsx --test src/modules/planning/service.test.ts && npm run typecheck`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/service.ts server/src/modules/planning/routes.ts server/src/modules/planning/service.test.ts
git commit -m "feat(plan): add brief read and patch endpoints"
```

---

### Task 4: 生成器与校验接入预算

**Files:**
- Modify: `server/src/modules/planning/generator.ts`
- Modify: `server/src/modules/planning/planSpec.ts`
- Modify: `server/src/modules/planning/refineStage.ts`
- Modify: `server/src/modules/planning/aiGenerator.ts`
- Modify: `server/src/modules/planning/service.ts`
- Test: `server/src/modules/planning/generator.test.ts`、`planSpec.test.ts`、`service.test.ts`

**Interfaces:**
- Produces: `BuildPlanSpecInput.budget?`、`PlanSpec.budget/dayCaps`、`assertGeneratedPlanValid(generated, examDate, brief, budget?, allowShortfall?)`。
- Consumes: `buildPlanBudget`。

- [ ] **Step 1: 写失败测试**

1. `generator.test.ts`：400 天、每天 4 小时、确认科目总剩余 3000 → `generateRulePlan({...input, budget})` 非复盘总量 ≤3150，含复盘 ≤3300。
2. `planSpec.test.ts`：给 spec 注入 1000 分钟预算但生成 2000 分钟 → `SUBJECT_OVER_BUDGET`；总排量超 target×1.05 → `TOTAL_OVER_BUDGET`；日排量超 dayCap → `DAILY_OVER_BUDGET`；THIN_DAY 在预算耗尽时不再出现。
3. `service.test.ts`：假 AI 返回超发计划 → `PLAN_GENERATION_FAILED`，repairHint 含科目名；容量不足 → 409 details 含 `choices`。

- [ ] **Step 2: 运行并确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/generator.test.ts src/modules/planning/planSpec.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

`generator.ts`：

```ts
export type PlanningInput = Pick<PlanBrief, 'availability' | 'fixedCommitments'> & {
  examDate: Date; startDate: Date; weakSubjects: string[]; studyWindows: string[]
  budget?: PlanBudget
}
```

- `dayCap(day) = min(capacity, budget.dayCap.get(day) ?? floor(capacity*0.85))`。
- 维护 `remaining = Map(subject -> budget.subjects[].budgetMinutes)`。
- 每日只排 `min(dayCap, Σremaining)`；按「接近里程碑优先 → 薄弱科目 → 轮转」选科目，每项 30–60 分钟，不超过该科剩余。
- 预算耗尽停止新增；复盘仅在当天学习分钟 ≥60 且复盘总量 < `targetMinutes*0.1` 时追加。
- 无 `budget` 时保留旧行为，现有测试不变。

`planSpec.ts`：

- `SpecSubject` 增加 `budgetMinutes`、`upperMinutes`、`milestoneBudgetMinutes`；`PlanSpec` 增加 `budget: PlanBudgetView | null`、`dayCaps: Record<string, number>`。
- `buildPlanSpec` 接收 `budget`，在 stages 构建完成后调用 `applyBudgetCaps(stages, subjects, budget)`：按科目把任务总量压到 `upperMinutes` 内（等比缩到 5 分钟粒度，最低 15；仍超则从最后一周起删任务），并把每周 `targetMinutes` 改为 `min(原目标, 本周任务分钟和)`。
- `compileDay` 使用 `Math.min(capacity, spec.dayCaps[day] ?? capacity)` 作为预算。
- `verifyPlanSpec` 新增错误码 `SUBJECT_OVER_BUDGET`、`TOTAL_OVER_BUDGET`、`DAILY_OVER_BUDGET`；`MILESTONE_GAP` 改用 `milestoneBudgetMinutes`；`MISSING_SUBJECT/SUBJECT_MIX` 仅在该科预算 ≥120 时检查；`THIN_DAY` 仅在「仍有未分配预算 ≥60」时给 warn，且预算存在时不参与修复。
- `repairPlanSpec`：`if (issue.code === 'THIN_DAY' && spec.budget) continue`；`appendTask` 前检查该科剩余预算。

`refineStage.ts` 提示词：把「接近可投入值（±20%）」改为「接近本阶段预算（±15%），不要为了填满空闲而加量」。

`aiGenerator.ts`：`AiPlanningInput` 增加 `budget?: PlanBudget`；两次 `buildPlanSpec` 都传 `budget`；`actionable` 列表移除 `THIN_DAY`。

`service.ts` `assertGeneratedPlanValid`：

```ts
function assertGeneratedPlanValid(generated, examDate, brief, budget?: PlanBudget, allowShortfall = false) {
  // 现有阶段/日期/每日净空闲检查保留
  if (budget) {
    const bySubject = new Map<string, number>()
    for (const item of generated.items) bySubject.set(item.subject, (bySubject.get(item.subject) ?? 0) + item.minutes)
    for (const subject of budget.subjects) {
      const scheduled = bySubject.get(subject.name) ?? 0
      if (scheduled > subject.upperMinutes + 1) fail(`${subject.name} 排了 ${scheduled} 分钟,超过预算上限 ${subject.upperMinutes} 分钟`)
      if (scheduled < subject.budgetMinutes) fail(`${subject.name} 只排了 ${scheduled} 分钟,未覆盖目标 ${subject.budgetMinutes} 分钟`)
      const before = generated.items.filter(item => item.subject === subject.name && dateOnly(item.planDate) <= subject.milestoneDate)
        .reduce((sum, item) => sum + item.minutes, 0)
      if (!subject.estimated && before < subject.milestoneBudgetMinutes) {
        fail(`${subject.name}「${subject.milestone}」在 ${subject.milestoneDate} 前差 ${subject.milestoneBudgetMinutes - before} 分钟`)
      }
    }
    const total = generated.items.filter(item => item.subject !== REVIEW_SUBJECT).reduce((sum, item) => sum + item.minutes, 0)
    const review = generated.items.filter(item => item.subject === REVIEW_SUBJECT).reduce((sum, item) => sum + item.minutes, 0)
    if (total > Math.floor(budget.targetMinutes * 1.05)) fail(`总排量 ${total} 分钟超过目标 ${budget.targetMinutes} 分钟的 105%`)
    if (review > Math.floor(budget.targetMinutes * 0.1) + 15) fail(`复盘 ${review} 分钟超过目标 10%`)
  }
}
```

`buildPlan`：在 `remainingBrief` 后计算 `budget = brief ? buildPlanBudget(remainingBrief, today, examDate, { allowShortfall }) : null`；`budget.shortfallMinutes > 0 && !allowShortfall` → `throw new ApiError(409, 'PLAN_CAPACITY_INSUFFICIENT', ..., { availableMinutes: budget.effectiveCapacityMinutes, requiredMinutes: budget.confirmedMinutes, deficits: budget.deficits, choices: ['adjust_schedule','lower_target','best_effort'] })`；`ai.generate({... , budget})`；`assertGeneratedPlanValid(result.plan, examDate, brief, budget, allowShortfall)`。

- [ ] **Step 4: 运行测试与全量 verify**

Run: `cd server && node --import tsx --test src/modules/planning/generator.test.ts src/modules/planning/planSpec.test.ts && npm run typecheck`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/generator.ts server/src/modules/planning/planSpec.ts server/src/modules/planning/refineStage.ts server/src/modules/planning/aiGenerator.ts server/src/modules/planning/service.ts server/src/modules/planning/*.test.ts
git commit -m "feat(plan): constrain generation by plan budget"
```

---

### Task 5: 草稿 overview 接口

**Files:**
- Modify: `server/src/modules/planning/planBudget.ts`
- Modify: `server/src/modules/planning/service.ts`
- Modify: `server/src/modules/planning/routes.ts`
- Test: `server/src/modules/planning/planBudget.test.ts`、`service.test.ts`

**Interfaces:**
- Produces: `getPlanOverview(userGuid, planId)`；`GET /api/v1/plans/:id/overview`。

- [ ] **Step 1: 写失败测试**

1. 纯函数：给定计划项和 brief，`buildPlanOverview` 的 `scheduledMinutes`、逐科 `deltaMinutes`、warnings 数字正确。
2. 服务：只能读自己的 draft/active；archived 返回 404。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/planBudget.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

`buildPlanOverview(plan, brief, stages)` 返回 spec §4.4 字段；`warnings` 包含：
- `SHORTFALL`：预算缺口；
- `SUBJECT_OVER_BUDGET`：逐科超 105%；
- `MILESTONE_RISK`：里程碑前不足；
- `ESTIMATED_SUBJECT`：估算科目提示。

`service.getPlanOverview`：

```ts
async getPlanOverview(userGuid: string, planId: number) {
  const row = await db.plan.findFirst({ where: { id: planId, userGuid, status: { in: ['draft', 'active'] } } })
  if (!row) return null
  const profile = await db.userProfile.findUnique({ where: { userGuid } })
  const brief = profile ? briefFromRow(profile) : null
  if (!brief) throw new ApiError(400, 'PLANNING_FACTS_INCOMPLETE', '缺少备考口径,无法生成预算摘要')
  const [stages, items] = await Promise.all([
    db.planStage.findMany({ where: { planId }, orderBy: { sortOrder: 'asc' } }),
    db.planItem.findMany({ where: { planId }, orderBy: [{ planDate: 'asc' }, { sortOrder: 'asc' }] }),
  ])
  return { planId, title: row.title, version: row.version, startDate: dateOnly(row.startDate), examDate: dateOnly(row.examDate), status: row.status, ...buildPlanOverview(row, brief, stages, items) }
}
```

路由放在 `/plans/:id` 之前：

```ts
router.get('/plans/:id/overview', requireAuth, async ctx => {
  const id = Number(ctx.params.id)
  if (!Number.isInteger(id) || id <= 0) throw new ApiError(400, 'INVALID_PARAMS', '计划 id 不合法')
  const overview = await planningService.getPlanOverview(ctx.state.auth!.userGuid, id)
  if (!overview) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划不存在')
  ctx.body = { overview, serverTime: Date.now() }
})
```

- [ ] **Step 4: 运行测试**

Run: `cd server && node --import tsx --test src/modules/planning/planBudget.test.ts src/modules/planning/service.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/planBudget.ts server/src/modules/planning/service.ts server/src/modules/planning/routes.ts server/src/modules/planning/*.test.ts
git commit -m "feat(plan): expose draft overview with budget warnings"
```

---

### Task 6: 生成请求 briefHash、allowShortfall 与 ETA

**Files:**
- Modify: `server/src/modules/planning/generationJobs.ts`
- Modify: `server/src/modules/planning/service.ts`
- Modify: `server/src/modules/planning/routes.ts`
- Test: `server/src/modules/planning/generationJobs.test.ts`、`service.test.ts`

**Interfaces:**
- Produces: `PlanGenerationJob.horizonDays`；`startGeneration(userGuid, onUpdate, options)`；`POST /plans/generate/jobs` body `{briefHash?, allowShortfall?}`；同步 `POST /plans/generate` 同样接受。

- [ ] **Step 1: 写失败测试**

1. `generationJobs`：`create(userGuid, 400)` 的 job 带 `horizonDays=400`，`runningFor` 返回副本。
2. `service`：错误 briefHash → 409 `PLAN_BRIEF_CHANGED`，details 含最新 `briefHash`；`allowShortfall=true` 且无 briefHash → 400。
3. 旧客户端无参数 → 正常生成。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/generationJobs.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

`generationJobs.ts`：job 增加 `horizonDays: number`；`create(userGuid, horizonDays = 0)`。

`service.startGeneration(userGuid, onUpdate, options: GenerateOptions = {})`：复用运行时返回已有 job；`create(userGuid, diffDays(exam, today))` 需要先读 profile；再 `buildPlan(userGuid, progress, options)`。

`buildPlan` 开头读取 profile 后：

```ts
const currentBrief = briefFromRow(profileRow)
const currentHash = briefHash(currentBrief, dateOnly(profileRow.examDate))
if (options.briefHash !== undefined && options.briefHash !== currentHash) {
  throw new ApiError(409, 'PLAN_BRIEF_CHANGED', '计划口径已更新,请重新确认', { briefHash: currentHash })
}
if (options.allowShortfall && options.briefHash === undefined) {
  throw new ApiError(400, 'INVALID_PARAMS', '生成尽力版必须同时确认计划口径')
}
```

`generatePlanDraft(userGuid, options)` 透传。路由 schema：

```ts
const generateSchema = z.object({
  briefHash: z.string().length(64).optional(),
  allowShortfall: z.boolean().optional(),
})
```

两条生成路由都用 `safeParse(ctx.request.body ?? {})`，把 `parsed.data` 传给 service。`startGeneration` 的同步 409 不会创建 job（先读 profile/hash 校验再 create）。

- [ ] **Step 4: 运行测试**

Run: `cd server && node --import tsx --test src/modules/planning/generationJobs.test.ts src/modules/planning/service.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/generationJobs.ts server/src/modules/planning/service.ts server/src/modules/planning/routes.ts server/src/modules/planning/generationJobs.test.ts
git commit -m "feat(plan): validate brief hash and shortfall choice on generation"
```

---

### Task 7: 单日时段重排纯函数

**Files:**
- Create: `server/src/modules/planning/reflow.ts`
- Create: `server/src/modules/planning/reflow.test.ts`

**Interfaces:**
- Produces: `reflowDay(input): Array<{ id, slot, startTime, sortOrder }>`。

- [ ] **Step 1: 写失败测试**

覆盖：done 与带 startTime 的锁定项先占位；其余按 priority→startTime→sortOrder→id 放进最早可容纳空闲段；放不下时 `startTime=null` 且 `slot` 为最早时段；同输入重复调用结果一致。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/reflow.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

```ts
import { freeSlots, type PlanBrief } from './document'

export interface ReflowItem {
  id: number; subject: string; title: string; minutes: number
  status: string; locked?: boolean
  startTime?: string | null; slot?: string | null
  sortOrder: number; priority: number
}
export interface ReflowPlacement { id: number; slot: string; startTime: string | null; sortOrder: number }

const toMinutes = (time: string) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3))
const toClock = (minutes: number) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`
const slotOf = (minutes: number) => Math.floor(minutes / 60) < 12 ? 'morning' : Math.floor(minutes / 60) < 18 ? 'afternoon' : 'evening'

export function reflowDay(input: { date: string; items: ReflowItem[]; brief: PlanBrief | null }): ReflowPlacement[] {
  const slots = input.brief
    ? freeSlots(input.brief, new Date(`${input.date}T00:00:00.000Z`)).map(slot => ({ start: toMinutes(slot.start), end: toMinutes(slot.end), used: 0 }))
    : []
  const placements = new Map<number, ReflowPlacement>()
  const fixed = input.items
    .filter(item => item.status === 'done' || (item.locked && item.startTime))
    .sort((a, b) => (a.startTime ?? '').localeCompare(b.startTime ?? '') || a.id - b.id)
  for (const item of fixed) {
    if (!item.startTime) continue
    const start = toMinutes(item.startTime)
    const end = start + item.minutes
    for (const slot of slots) {
      if (start >= slot.start && end <= slot.end) slot.used += item.minutes
    }
    placements.set(item.id, { id: item.id, slot: slotOf(start), startTime: item.startTime, sortOrder: 0 })
  }
  const rest = input.items
    .filter(item => !placements.has(item.id))
    .sort((a, b) => a.priority - b.priority || (a.startTime ?? '').localeCompare(b.startTime ?? '')
      || a.sortOrder - b.sortOrder || a.id - b.id)
  for (const item of rest) {
    const fitting = slots.find(slot => slot.end - slot.start - slot.used >= item.minutes)
    if (fitting) {
      const start = fitting.start + fitting.used
      fitting.used += item.minutes
      placements.set(item.id, { id: item.id, slot: slotOf(start), startTime: toClock(start), sortOrder: 0 })
    } else {
      const earliest = [...slots].sort((a, b) => a.start - b.start)[0]
      placements.set(item.id, { id: item.id, slot: earliest ? slotOf(earliest.start) : 'morning', startTime: null, sortOrder: 0 })
    }
  }
  return [...placements.values()]
    .sort((a, b) => (a.startTime ?? '99:99').localeCompare(b.startTime ?? '99:99') || a.id - b.id)
    .map((placement, index) => ({ ...placement, sortOrder: index }))
}
```

无 brief 时保持原 `slot/startTime`（测试覆盖：返回空数组，由调用方跳过写回）。

- [ ] **Step 4: 运行测试**

Run: `cd server && node --import tsx --test src/modules/planning/reflow.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/reflow.ts server/src/modules/planning/reflow.test.ts
git commit -m "feat(plan): add deterministic day reflow"
```

---

### Task 8: 计划项编辑、添加、删除与锁定

**Files:**
- Modify: `server/src/modules/planning/service.ts`
- Modify: `server/src/modules/planning/routes.ts`
- Test: `server/src/modules/planning/service.test.ts`

**Interfaces:**
- Produces: `updatePlanItem`、`addPlanItem`、`deletePlanItem`；`PATCH/POST/DELETE /plans/items`。

- [ ] **Step 1: 写失败测试**

1. 改日期后 `stageId` 更新、`slot/startTime` 非空。
2. 改时长/标题/科目持久化；刷新后仍在。
3. done 项改内容 → 409 `PLAN_ITEM_NOT_EDITABLE`；取消勾选成功。
4. 新增/删除 pending 成功；删除 done → 409。
5. 超容量 → 409 `PLAN_DAY_OVERLOADED`，details 含 `capacity/planned/overflow/suggestion`。
6. 锁定开关持久化；锁定项仍可手工改。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/service.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

`PlanningDb.planItem` 已够用。核心辅助：

```ts
async function loadEditableItem(tx: PlanningDb, userGuid: string, itemId: number) {
  const [item] = await tx.planItem.findMany({ where: { id: itemId } })
  if (!item) throw new ApiError(404, 'PLAN_ITEM_NOT_FOUND', '计划项不存在')
  const plan = await tx.plan.findFirst({ where: { id: item.planId, userGuid } })
  if (!plan) throw new ApiError(404, 'PLAN_ITEM_NOT_FOUND', '计划项不存在')
  if (plan.status === 'archived') throw new ApiError(409, 'PLAN_ITEM_NOT_EDITABLE', '历史计划只读,不能修改')
  return { item, plan }
}

function itemDayLoad(items: any[], day: string, excludeId?: number) {
  return items.filter(row => dateOnly(row.planDate) === day && Number(row.id) !== excludeId)
    .reduce((sum, row) => sum + Number(row.minutes ?? 0), 0)
}
```

`updatePlanItem`：
- 拆分 `status` 与内容字段；混用 → 400。
- status-only 沿用现有 `setItemStatus`，但先校验归属（现有方法已做）。
- 内容字段校验：minutes 5..480、title 1..64、subject 在 brief 科目 ∪ `复盘`、日期在 `[plan.startDate, examDate-1]` 且落在某阶段内。
- done → 409。
- 容量：`newLoad = itemDayLoad(others, targetDay) + newMinutes`；`capacity = dailyCapacity(brief, targetDay)`；超 → details + `suggestion`（在目标日后 7 天里找第一个 `capacity - load >= overflow` 的日期）。
- 写库：更新 `planDate/minutes/title/subject/locked/stageId`；然后 `reflowDates(tx, planId, [oldDay, newDay], brief)` 重排写回；更新 `plan.updatedAt`。
- 返回 `{ plan: await loadPlan(...), item: plan.items.find(...), warnings }`。

`reflowDates`：

```ts
async function reflowDates(tx: PlanningDb, planId: number, days: string[], brief: PlanBrief | null) {
  if (!brief) return
  for (const day of [...new Set(days)]) {
    const rows = await tx.planItem.findMany({ where: { planId } })
    const dayRows = rows.filter(row => dateOnly(row.planDate) === day)
    if (dayRows.length === 0) continue
    const placements = reflowDay({ date: day, brief, items: dayRows.map(row => ({
      id: Number(row.id), subject: row.subject, title: row.title, minutes: Number(row.minutes),
      status: row.status, locked: row.locked === true, startTime: row.startTime, slot: row.slot,
      sortOrder: Number(row.sortOrder ?? 0), priority: Number(row.priority ?? 0),
    })) })
    for (const placement of placements) {
      await tx.planItem.updateMany({ where: { id: placement.id, planId }, data: {
        slot: placement.slot, startTime: placement.startTime, sortOrder: placement.sortOrder,
      } })
    }
  }
}
```

`addPlanItem`：默认 active 计划；`planId` 显式时校验 active/draft；创建时 `priority=0/sortOrder=999`，`stageIdFor`；容量校验；`reflowDates`；返回 `{plan,item}`。

`deletePlanItem`：pending only；删除后 `reflowDates` 当天；返回 plan。

路由：

```ts
const itemPatchSchema = z.object({
  status: z.enum(PLAN_ITEM_STATUSES).optional(),
  planDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  minutes: z.number().int().min(5).max(480).optional(),
  title: z.string().min(1).max(64).optional(),
  subject: z.string().min(1).max(32).optional(),
  locked: z.boolean().optional(),
}).refine(value => Object.keys(value).length > 0, '至少修改一个字段')
const addItemSchema = z.object({
  planId: z.number().int().positive().optional(),
  subject: z.string().min(1).max(32), title: z.string().min(1).max(64),
  planDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  minutes: z.number().int().min(5).max(480), locked: z.boolean().optional(),
})
```

PATCH 仅在「只有 status 且 slim=1」时走 slim 响应；其余返回 `{plan,item,warnings,serverTime}`。POST/DELETE 后 `notifyPlanChanged`。

- [ ] **Step 4: 运行测试**

Run: `cd server && node --import tsx --test src/modules/planning/service.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/service.ts server/src/modules/planning/routes.ts server/src/modules/planning/service.test.ts
git commit -m "feat(plan): support item editing, creation, deletion and locking"
```

---

### Task 9: 30 天调整窗口、快照字段与指纹

**Files:**
- Modify: `server/src/modules/planning/adjust.ts`
- Modify: `server/src/modules/planning/service.ts`
- Test: `server/src/modules/planning/adjust.test.ts`

**Interfaces:**
- Produces: `adjustWindow`、`windowFingerprint(items, contextHash)`；`AdjustableItem.locked?`；`WindowSnapshotItem.locked/startTime/slot`。

- [ ] **Step 1: 写失败测试**

1. `to = min(examDate-1, today+29)`；`from` 纳入 `[today-30,today)` 最早未完成项，且不早于 planStart。
2. 指纹随 title/subject/locked/startTime/slot/contextHash 变化；done 不参与。
3. L1 不把任务推过科目里程碑（输入 milestones）。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/adjust.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

```ts
export interface WindowSnapshotItem {
  id: number; subject: string; title: string; planDate: string; minutes: number; status: string
  locked?: boolean; startTime?: string | null; slot?: string | null
}
export interface AdjustableItem { /* 现有字段 */ locked?: boolean }

export function adjustWindow(input: {
  planStart: string; examDate: string; today: string; unfinishedDates: string[]
}): { from: string; to: string } | null {
  const to = shiftDate(input.examDate, -1) < shiftDate(input.today, 29)
    ? shiftDate(input.examDate, -1) : shiftDate(input.today, 29)
  if (to < input.today) return null
  const overdue = input.unfinishedDates.filter(day => day >= shiftDate(input.today, -30) && day < input.today).sort()[0]
  const from = (overdue ?? input.today) > input.planStart ? (overdue ?? input.today) : input.planStart
  return from <= to ? { from, to } : null
}

export function windowFingerprint(items: WindowSnapshotItem[], contextHash = ''): string {
  const payload = items.filter(item => item.id > 0 && item.status !== 'done')
    .map(item => `${item.id}:${item.planDate}:${item.minutes}:${item.subject}:${item.title}:${item.locked === true ? 1 : 0}:${item.startTime ?? ''}:${item.slot ?? ''}`)
    .sort().join('|')
  return createHash('sha256').update(`${payload}|${contextHash}`).digest('hex')
}
```

`planL1Shuffle` 增加可选 `milestones: Array<{subject,date}>`：若任务原日期 ≤ 科目里程碑日，则目标日不得 > 里程碑日；否则不受限。

`service.ts`：
- `toAdjustable`/`toSnapshot` 带 `locked/startTime/slot`。
- 用 `adjustWindow` 替换 `stageWindow`：读取计划全部 items，收集 `pending && planDate < today` 的日期；考试结束返回 404。
- `latestAdjustment` 不变（仍按状态）。
- 指纹调用处传 `briefContextHash(brief, examDate)`。

- [ ] **Step 4: 运行测试**

Run: `cd server && node --import tsx --test src/modules/planning/adjust.test.ts src/modules/planning/service.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/adjust.ts server/src/modules/planning/service.ts server/src/modules/planning/adjust.test.ts
git commit -m "feat(plan): widen adjustment window and harden fingerprint"
```

---

### Task 10: 意图扩展与任务消歧

**Files:**
- Modify: `server/src/modules/planning/adjustIntent.ts`
- Test: `server/src/modules/planning/adjustIntent.test.ts`（新建）

**Interfaces:**
- Produces: `AdjustIntentKind` 扩展为 9 种；`AdjustIntent.tasks/newTasks/scope`；`clarifyOptions`。

- [ ] **Step 1: 写失败测试**

用固定模型输出 JSON（注入 `chatComplete` 不可行时直接测 `normalizeAdjustIntent`）：
1. `move_task/add_task/drop_task/lock_task/adjust_scope` 正确归一化。
2. 日期超出窗口 → `needClarify=true`。
3. `add_task` 缺 minutes/date → 追问。
4. `adjust_scope` 缺目标值 → 追问。
5. 复合请求 → 一次追问。
6. 闲聊 → `kind='chat'`。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/adjustIntent.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

类型：

```ts
export type AdjustIntentKind =
  | 'unavailable' | 'reduce_capacity' | 'change_commitment'
  | 'move_task' | 'add_task' | 'drop_task' | 'adjust_scope' | 'lock_task' | 'chat'

export interface AdjustTaskRef { subject: string; title?: string; date?: string; targetDate?: string }
export interface AdjustNewTask { subject: string; title: string; date: string; minutes: number }
export interface AdjustScopeItem {
  subject: string; remainingMinutes?: number; milestone?: string
  milestoneDate?: string; milestoneMinutes?: number; estimated?: boolean
}
export interface AdjustIntent {
  // 现有字段
  tasks: AdjustTaskRef[]
  newTasks: AdjustNewTask[]
  scope: AdjustScopeItem[]
  clarifyOptions: Array<{ label: string; value: string }>
}
```

系统提示词补充 9 类意图、一次只允许一个意图、日期窗口、任务引用必须含当前日期、`adjust_scope` 必须给目标值。`normalizeAdjustIntent` 增加：
- 日期不在 `[windowFrom,today+29]` → clarify；
- `move_task` 缺 targetDate → clarify；
- `add_task` 缺 minutes/date → clarify；
- `drop_task/lock_task` 无 tasks → clarify；
- `adjust_scope` scope 全部没有数值/日期目标 → clarify；
- `clarifyOptions` 原样保留并最多 4 个。

`buildUserPrompt` 增加：窗口内每日任务摘要（最多 60 条）、锁定项、逐科里程碑、预算余量、今天日期。

- [ ] **Step 4: 运行测试**

Run: `cd server && node --import tsx --test src/modules/planning/adjustIntent.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/adjustIntent.ts server/src/modules/planning/adjustIntent.test.ts
git commit -m "feat(plan): understand task move add drop lock and scope intents"
```

---

### Task 11: 多方案引擎

**Files:**
- Create: `server/src/modules/planning/planOptions.ts`
- Create: `server/src/modules/planning/planOptions.test.ts`

**Interfaces:**
- Produces: `AdjustmentImpact`、`AdjustmentOption`、`buildAdjustmentOptions(input)`。

- [ ] **Step 1: 写失败测试**

1. compress：总容量够但某天不可用 → 任务缩短并顺延；锁定项不动；`removedCount=0`。
2. spill：溢出项移到后续 14 天内；越过里程碑 → 不可行。
3. drop：删除最少的非锁定项；锁定项 0 次删除。
4. lower_target：只对持续性容量下降提供；`profileAfter` 中逐科剩余量与里程碑下调。
5. 去重、推荐顺序 compress→spill→drop→lower_target、上限 4。
6. 所有 `after` 都包含 done 项。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/planOptions.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

实现四类方案，统一：
- `keep = done`；`locked = pending.filter(locked)`；`open = pending.filter(!locked)`。
- 先把 done+locked 的分钟从 `capacity` 扣除，得到 `free`；`lockedConflicts` 为某天 done+locked 已超容量。
- `after = [...done, ...locked, ...placedOpen]`，按日期/id 排序。
- `changes = diffSnapshots(before, after)`；空 changes 丢弃。
- `impact` 由 after 计算：affectedDays、movedCount、addedCount、removedCount、totalMinutesBefore/After、maxDayLoad/Capacity、shortfallMinutes、milestoneRiskCount、lockedKeptCount。
- compress：`scale = min(1, totalFree / totalOpen)`；每条 `max(15, round(minutes*scale/5)*5)`；按原日期优先、容量不足顺延到最早可容纳日；总量仍超 → null。
- spill：保留原日期，放不下时移到 `windowTo+1..min(windowTo+14, examDate-1)`；不得晚于该科里程碑（原日期在里程碑前时）；额外天数容量用 `dailyCapacity`。
- drop：按天删除最少的 open 项；删除排序 = 非薄弱优先 → priority 低优先 → 无里程碑优先 → 日期晚优先 → sortOrder 倒序；仍放不下 → null；没有删除 → null。
- lower_target：仅 intent 为 `reduce_capacity/change_commitment` 且（compress 或 spill 不可行或 `compress.milestoneRiskCount>0`）时提供；按科目比例削减剩余量，`milestoneMinutes=min(milestoneMinutes,newRemaining)`；从后往前删/缩任务直到总容量内；`profileAfter=JSON.stringify(newBrief)`。
- 去重 key = `JSON.stringify(changes)`。
- 推荐顺序 compress→spill→drop→lower_target；只保留可行项，最多 4。

- [ ] **Step 4: 运行测试**

Run: `cd server && node --import tsx --test src/modules/planning/planOptions.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/planOptions.ts server/src/modules/planning/planOptions.test.ts
git commit -m "feat(plan): generate quantified adjustment options"
```

---

### Task 12: L2 四种校验模式与锁定保护

**Files:**
- Modify: `server/src/modules/planning/adjustL2.ts`
- Test: `server/src/modules/planning/adjustL2.test.ts`（新建）

**Interfaces:**
- Produces: `L2Mode = 'conserve'|'add'|'drop'|'scope'`；`validateL2(target, ledger, options)`。

- [ ] **Step 1: 写失败测试**

1. conserve：总量差 2 分钟 → fail；差 1 分钟 → pass。
2. add：总量 = 原总量 + 新增；多 1 分钟 → fail。
3. drop：总量 ≤ 原总量，指定删除项缺席。
4. scope：允许减少。
5. 锁定项被移动/改内容/删除 → fail。
6. 每日超容量/里程碑 → fail。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/adjustL2.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

```ts
export type L2Mode = 'conserve' | 'add' | 'drop' | 'scope'
export interface L2ValidateOptions {
  mode: L2Mode
  allowedDelta?: number
  droppedIds?: number[]
  locked?: WindowSnapshotItem[]
}
export function validateL2(target, ledger, options: L2ValidateOptions = { mode: 'conserve' }) {
  const total = target.reduce((sum, item) => sum + item.minutes, 0)
  const expected = ledger.totalMinutes + (options.allowedDelta ?? 0)
  if (options.mode === 'conserve' && Math.abs(total - expected) > 1) return { ok: false, reason: ... }
  if (options.mode === 'add' && Math.abs(total - expected) > 1) return { ok: false, reason: ... }
  if ((options.mode === 'drop' || options.mode === 'scope') && total > expected) return { ok: false, reason: ... }
  for (const id of options.droppedIds ?? []) {
    if (target.some(item => item.id === id)) return { ok: false, reason: `任务 ${id} 应被删除却仍在计划里` }
  }
  for (const locked of options.locked ?? []) {
    const hit = target.find(item => item.id === locked.id)
    if (!hit || hit.subject !== locked.subject || hit.title !== locked.title
      || hit.planDate !== locked.planDate || hit.minutes !== locked.minutes) {
      return { ok: false, reason: `锁定任务「${locked.title}」被改动` }
    }
  }
  // 现有每日容量与里程碑检查保留
}
```

L2 提示词按模式说明允许的操作；`runAdjustL2` 增加 `mode` 输入并写入 prompt。`expandL2` 调用方在模型任务之外追加 done + locked，保持原样。

- [ ] **Step 4: 运行测试**

Run: `cd server && node --import tsx --test src/modules/planning/adjustL2.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/adjustL2.ts server/src/modules/planning/adjustL2.test.ts
git commit -m "feat(plan): support conserve add drop and scope L2 modes"
```

---

### Task 13: 调整管线接入意图、方案与延迟档案

**Files:**
- Modify: `server/src/modules/planning/service.ts`
- Modify: `server/src/modules/planning/routes.ts`
- Test: `server/src/modules/planning/service.test.ts`

**Interfaces:**
- Produces: `selectAdjustmentOption(userGuid,id,optionId)`；`PublicAdjustment.options`；`POST /plans/adjustments/:id/select`；adjust 返回 `options`/`clarifyOptions`/`changedPlan`。

- [ ] **Step 1: 写失败测试**

1. `lock_task` 直接置锁并返回 `changedPlan=true`，不产调整单。
2. `move_task` 直接改日期，单方案；目标日放不下 → 多方案。
3. `add_task/drop_task` 不调用 L2。
4. `change_commitment` 确认前档案不变，调整单有 `profileAfterJson`，容量按新占用计算。
5. `adjust_scope` 即使 changes 为空也产调整单。
6. 多方案：`options.length>=2`，默认推荐 compress；`select` 切换后 `afterJson` 变化；旧客户端无 options 仍可确认。
7. L2 失败 → 确定性方案，不 502。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/service.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

`adjust` 重构：
- 读取全部 items；`window = adjustWindow({planStart, examDate, today, unfinishedDates})`；null → 404。
- `briefBefore`；`briefAfter = intent.kind === 'change_commitment' ? mergeCommitments(...) : intent.kind === 'adjust_scope' ? applyScope(...) : briefBefore`；不改 DB。
- 容量按 `briefAfter` 计算，先扣 done，再扣锁定项。
- `lock_task`：用 `resolveTasks`（subject/title/date 唯一匹配）设置 locked=true，`reflowDates`，返回 `{adjustment:null, reply, plan, changedPlan:true}`；不明确 → 追问。
- `move_task/add_task/drop_task`：构造 after 快照，校验容量/预算；通过 → 单方案（自定义 `manual` 选项，tier L1）；不通过 → `buildAdjustmentOptions`。
- 纯日期类：L1（pending 去掉 locked，milestones）→ 单方案；overflow/影响大 → options。
- 语义重排：L2 `conserve`；失败重试一次；仍失败 → options；无方案 → 409 details。
- `options = buildAdjustmentOptions(...)`；若 `options.length >= 2` 用多方案；否则单方案。`optionsJson` 存全部（含 after/profileAfter），`afterJson` 存推荐 after，`selectedOption` 存推荐 id。
- 调整单 payload 增加 `profileBeforeJson`（仅延迟写档案时）与 `profileAfterJson`。
- `serializeAdjustment` 返回 options 公开视图（无 after/profileAfter）与 `selectedOption`。
- `selectAdjustmentOption`：draft + 指纹校验 + 选项存在 → 更新 `afterJson/selectedOption` → 返回 adjustment。
- `routes`：select 路由；adjust 响应透传 `options`/`clarifyOptions`。

- [ ] **Step 4: 运行测试**

Run: `cd server && node --import tsx --test src/modules/planning/service.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/service.ts server/src/modules/planning/routes.ts server/src/modules/planning/service.test.ts
git commit -m "feat(plan): route adjustments through options and defer profile writes"
```

---

### Task 14: 确认/撤销安全与时段重排

**Files:**
- Modify: `server/src/modules/planning/service.ts`
- Test: `server/src/modules/planning/service.test.ts`

**Interfaces:**
- Produces: `afterFingerprint` 写入；撤销检测手工修改；确认时重排并写档案。

- [ ] **Step 1: 写失败测试**

1. 确认后 `slot/startTime` 与目标日期一致。
2. 确认后手工改一项 → 撤销 409 `ADJUSTMENT_UNDO_STALE`。
3. `change_commitment` 确认后档案变；撤销后完全还原。
4. 确认前档案 0 副作用。
5. 指纹覆盖 subject/title/locked。
6. done 项不使调整单失效。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/service.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

`confirmAdjustment` 事务内：
1. 读 row/plan/stages/items。
2. `contextBefore = row.profileBeforeJson ? normalizeBrief(JSON.parse(row.profileBeforeJson)) : currentBrief`；`windowFingerprint(toSnapshot(inWindow), briefContextHash(contextBefore, examDate))` 比对 `row.fingerprint`。
3. `syncWindowItems` 应用 after；`syncWindowItems` 内：更新/新增后调用 `reflowDates`，新增加 `stageId`；删除按窗口。
4. 若有 `profileAfterJson` → 写 `UserProfile.briefJson`（同一事务）。
5. 重新读窗口 items，`afterFingerprint = windowFingerprint(snapshot, briefContextHash(briefAfter, examDate))`；写 row。
6. `status='applied'`。

`undoAdjustment`：
- 最近 applied + 无新打卡（现有）。
- `currentFingerprint = windowFingerprint(nowSnapshot, briefContextHash(currentBrief, examDate))`；`afterFingerprint` 不匹配 → 409 `ADJUSTMENT_UNDO_STALE`。
- `syncWindowItems(before)`；`reflowDates`；`profileBeforeJson` → 恢复 `briefJson`；标记 undone。

`syncWindowItems` 扩展：
- 更新时也写 `locked`（手工改锁通过其它路径，不在 after 中）。
- 新增加 `slot/startTime` 由 reflow 写。
- 日期变化更新 `stageId`。
- 结束后对涉及日期 `reflowDates`。

- [ ] **Step 4: 运行测试**

Run: `cd server && node --import tsx --test src/modules/planning/service.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/service.ts server/src/modules/planning/service.test.ts
git commit -m "fix(plan): reflow times and guard confirm/undo against manual edits"
```

---

### Task 15: 体检建议驱动主动教练

**Files:**
- Modify: `server/src/modules/planning/checkup.ts`
- Modify: `server/src/modules/planning/service.ts`（如 `CheckupSummary` 透传）
- Test: `server/src/modules/planning/checkup.test.ts`

**Interfaces:**
- Produces: `CheckupSummary.suggestedMessage`。

- [ ] **Step 1: 写失败测试**

1. 落后/过期 → `suggestedMessage` 含「落后」「重新排」。
2. 未来 7 天超载 → 含「压缩」。
3. 完成率低 → 含「强度调低」。
4. 正常 → `suggestedMessage=null`。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/checkup.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现**

`issueSuggestion` 返回 `{ text, suggestedMessage }` 或在 `buildCheckup` 根据 issue 类型映射：
- `overdue` → 「我有几项任务落后了，帮我把未来几天重新排一下」
- `overload` → 「接下来几天安排太满，帮我压缩一下」
- `low_rate` → 「最近完成率偏低，帮我把计划强度调低一点」
- 其它 → null

`CheckupSummary` 增加 `suggestedMessage: string | null`。

- [ ] **Step 4: 运行测试**

Run: `cd server && node --import tsx --test src/modules/planning/checkup.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/checkup.ts server/src/modules/planning/service.ts server/src/modules/planning/checkup.test.ts
git commit -m "feat(plan): add coach message to checkup"
```

---

### Task 16: 客户端 DTO、接口与错误解析

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanErrors.kt`
- Test: `YanZhong/app/src/test/java/com/yanzhong/app/ui/plan/PlanBudgetTest.kt`

**Interfaces:**
- Produces: `PlanBudgetDto`、`PlanBriefResp`、`PlanOverviewDto`、`AdjustmentOptionDto`、请求体；API 方法；`Throwable.planError()`。

- [ ] **Step 1: 写失败测试**

`PlanBudgetTest` 测 `formatDeltaMinutes`、`optionImpactLines`（新纯函数在 `PlanErrors.kt`/`PlanBudgetTable.kt`）。

- [ ] **Step 2: 运行确认失败**

Run: Android 单测命令（见 Global Constraints）。
Expected: FAIL（函数不存在）。

- [ ] **Step 3: 实现**

DTO（全部 `@Serializable`，字段给默认值以兼容旧服务端）：

```kotlin
@Serializable data class PlanBudgetSubjectDto(
    val name: String = "", val estimated: Boolean = false,
    val remainingMinutes: Int = 0, val budgetMinutes: Int = 0,
    val milestone: String = "", val milestoneDate: String = "",
    val milestoneMinutes: Int = 0, val milestoneBudgetMinutes: Int = 0,
    val targetMinutes: Int = 0, val scheduledMinutes: Int = 0,
    val beforeMilestoneMinutes: Int = 0, val deltaMinutes: Int = 0,
)
@Serializable data class PlanBudgetDto(
    val capacityMinutes: Int = 0, val effectiveCapacityMinutes: Int = 0,
    val confirmedMinutes: Int = 0, val estimatedMinutes: Int = 0,
    val estimatedBudgetMinutes: Int = 0, val targetMinutes: Int = 0,
    val shortfallMinutes: Int = 0, val scheduledMinutes: Int = 0,
    val utilization: Double = 0.0, val deficits: List<BudgetDeficitDto> = emptyList(),
    val subjects: List<PlanBudgetSubjectDto> = emptyList(), val warnings: List<String> = emptyList(),
)
@Serializable data class PlanBriefResp(
    val brief: PlanBriefDto? = null, val briefHash: String = "",
    val profile: BriefProfileDto = BriefProfileDto(), val budget: PlanBudgetDto = PlanBudgetDto(),
    val capacityByWeekday: List<WeekdayCapacityDto> = emptyList(), val warnings: List<String> = emptyList(),
)
@Serializable data class PlanOverviewResp(val overview: PlanOverviewDto? = null)
@Serializable data class PlanOverviewDto(
    val planId: Long = 0, val title: String = "", val version: Int = 1,
    val startDate: String = "", val examDate: String = "", val status: String = "",
    val budget: PlanBudgetDto = PlanBudgetDto(), val subjects: List<PlanBudgetSubjectDto> = emptyList(),
    val days: List<PlanDayLoadDto> = emptyList(), val stages: List<PlanStageLoadDto> = emptyList(),
    val warnings: List<PlanWarningDto> = emptyList(),
)
@Serializable data class AdjustmentOptionDto(
    val id: String = "", val label: String = "", val summary: String = "",
    val impact: AdjustmentImpactDto = AdjustmentImpactDto(),
    val changes: List<AdjustmentChangeDto> = emptyList(),
)
@Serializable data class AdjustmentImpactDto(
    val affectedDays: Int = 0, val movedCount: Int = 0, val addedCount: Int = 0,
    val removedCount: Int = 0, val totalMinutesBefore: Int = 0, val totalMinutesAfter: Int = 0,
    val maxDayLoad: Int = 0, val maxDayCapacity: Int = 0, val shortfallMinutes: Int = 0,
    val milestoneRiskCount: Int = 0, val lockedKeptCount: Int = 0,
)
```

`PlanItemDto` 增加 `val locked: Boolean = false`；`AdjustmentDto` 增加 `val options: List<AdjustmentOptionDto> = emptyList()`、`selectedOption: String? = null`；`PlanBriefDto` 保持。

请求体：

```kotlin
@Serializable data class GeneratePlanReq(val briefHash: String? = null, val allowShortfall: Boolean? = null)
@Serializable data class BriefSubjectPatch(...); @Serializable data class PatchBriefReq(
    val examSubjects: List<BriefSubjectPatch>? = null,
    val fixedCommitments: List<BriefCommitmentDto>? = null,
)
@Serializable data class PlanItemEditReq(
    val status: String? = null, val planDate: String? = null, val minutes: Int? = null,
    val title: String? = null, val subject: String? = null, val locked: Boolean? = null,
)
@Serializable data class AddPlanItemReq(
    val planId: Long? = null, val subject: String, val title: String,
    val planDate: String, val minutes: Int, val locked: Boolean? = null,
)
@Serializable data class SelectOptionReq(val optionId: String)
```

API：

```kotlin
@GET("api/v1/plans/brief") suspend fun getPlanBrief(): PlanBriefResp
@retrofit2.http.PATCH("api/v1/plans/brief") suspend fun patchPlanBrief(@Body body: PatchBriefReq): PlanBriefResp
@GET("api/v1/plans/{id}/overview") suspend fun getPlanOverview(@Path("id") id: Long): PlanOverviewResp
@POST("api/v1/plans/generate/jobs") suspend fun startPlanGeneration(@Body body: GeneratePlanReq = GeneratePlanReq()): PlanGenerationJobResp
@POST("api/v1/plans/generate") suspend fun generatePlan(@Body body: GeneratePlanReq = GeneratePlanReq()): PlanResp
@retrofit2.http.PATCH("api/v1/plans/items/{id}") suspend fun editPlanItem(@Path("id") id: Long, @Body body: PlanItemEditReq): PlanItemEditResp
@POST("api/v1/plans/items") suspend fun addPlanItem(@Body body: AddPlanItemReq): PlanItemEditResp
@DELETE("api/v1/plans/items/{id}") suspend fun deletePlanItem(@Path("id") id: Long): PlanResp
@POST("api/v1/plans/adjustments/{id}/select") suspend fun selectAdjustmentOption(@Path("id") id: Long, @Body body: SelectOptionReq): AdjustmentResp
```

`PlanItemEditResp(plan: PlanDto? = null, item: PlanItemDto? = null, warnings: List<String> = emptyList())`。

`PlanErrors.kt`：

```kotlin
data class PlanServerError(val code: String, val message: String, val details: JsonObject?)
fun Throwable.planServerError(): PlanServerError? { /* 读 errorBody，解析 error/message/details */ }
fun PlanServerError.friendlyPlanError(): String? = when (code) {
    "PLAN_BRIEF_CHANGED" -> "计划口径刚被改过，重新确认一下再生成"
    "PLAN_DAY_OVERLOADED" -> message.ifBlank { "当天时间不够，先缩短或挪走一项" }
    "PLAN_ITEM_NOT_EDITABLE" -> message.ifBlank { "这条任务现在不能改" }
    "LOCKED_ITEMS_CONFLICT" -> "锁定任务和这次调整冲突，需要先解锁"
    "ADJUSTMENT_UNDO_STALE" -> "调整后你手工改过计划，撤销会覆盖这些修改；请重新描述一次调整"
    "ADJUSTMENT_STALE" -> "计划刚被改过，这张调整单已失效"
    "PLAN_GENERATION_RUNNING" -> "计划正在生成，完成后再改口径"
    else -> message.takeIf { it.isNotBlank() }
}
```

- [ ] **Step 4: 运行单测与编译**

Run: Android 单测命令。
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanErrors.kt YanZhong/app/src/test/java/com/yanzhong/app/ui/plan/PlanBudgetTest.kt
git commit -m "feat(app): add plan budget and editing API contracts"
```

---

### Task 17: 生成前口径确认、缺口弹窗与真实 ETA

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanBriefSheet.kt`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/CapacityOptionsDialog.kt`
- Modify: `PlanInterviewViewModel.kt`、`PlanInterviewScreen.kt`

**Interfaces:**
- Produces: `BriefSheet`、`CapacityOptionsDialog`；VM 状态 `briefResp/briefSheet/capacityIssue/etaMinutes/startedAt`。

- [ ] **Step 1: 写失败测试**

VM 纯辅助函数：`etaRange(horizonDays)` 返回 `8..30`、`formatElapsed`；`briefPatchFromEdits` 只包含改动字段。

- [ ] **Step 2: 运行确认失败**

Run: Android 单测命令。
Expected: FAIL。

- [ ] **Step 3: 实现**

VM：
- `InterviewUiState` 增加 `briefResp: PlanBriefResp?`、`showBriefSheet: Boolean`、`pendingGenerate: Boolean`、`capacityIssue: PlanCapacityIssue?`、`etaMinutes: IntRange?`、`generationStartedAt: Long?`、`elapsedSeconds: Int`。
- `generate()` 改为 `requestGeneration()`：
  1. `GET /plans/brief`（走 `ApiClient.api()`，短请求）。
  2. 读取 `SharedPreferences("plan_interview").getString("confirmed_brief_hash")`；与 `briefHash` 不同 → `showBriefSheet=true, pendingGenerate=true`，否则 `startGeneration(null, false)`。
- `confirmBrief(patches)`：`PATCH /plans/brief` → 用返回的 hash 存本地 → 关闭 sheet → `startGeneration(hash, false)`。
- `startGeneration(hash, allowShortfall)`：调用 `startPlanGeneration(GeneratePlanReq(hash, allowShortfall))`；保存 `jobId`；`etaMinutes = etaRange(horizonDays)`；`generationStartedAt=now`。
- 409 处理：
  - `PLAN_BRIEF_CHANGED` → 重新拉 brief，`showBriefSheet=true`；
  - `PLAN_CAPACITY_INSUFFICIENT` → 解析 details 到 `capacityIssue`，`showCapacityDialog=true`。
- `chooseCapacity(choice)`：`adjust_schedule` → 发导航事件 `openPlanSetup`；`lower_target` → `showBriefSheet=true`；`best_effort` → 二次确认后 `startGeneration(hash,true)`。
- 进度：`etaMinutes` 与 `generationStartedAt` 在 `applyJob` 更新；`PlanInterviewScreen` 用 `LaunchedEffect` 每秒更新已用时。

UI：
- `PlanBriefSheet`：ModalBottomSheet，标题「生成前，先确认计划口径」；科目行可编辑剩余量/截止日/估算开关；只读展示按星期容量与固定占用；预算预览（确认任务/有效容量/留白/缺口）；主按钮「确认无误，生成计划」。
- `CapacityOptionsDialog`：三选一（调整作息/降低目标/生成尽力版），显示 `availableMinutes`、`requiredMinutes`、逐科 deficits；尽力版二次确认文案。
- `PlanInterviewScreen`：生成卡展示 `etaRange` 区间 + 已用时，不再出现「1-3 分钟」；新增 sheet/dialog 挂载；`Routes.planInterview` 的 `prefill` 参数在 `beginSession` 后自动 `send(prefill)` 一次并清空参数。

- [ ] **Step 4: 运行单测 + 编译**

Run: Android 单测命令。
Expected: PASS；`assembleDebug` 成功。

- [ ] **Step 5: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanBriefSheet.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/CapacityOptionsDialog.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewViewModel.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewScreen.kt
git commit -m "feat(app): confirm plan brief and show real generation ETA"
```

---

### Task 18: 草案预算表与警告

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanBudgetTable.kt`
- Modify: `PlanInterviewViewModel.kt`、`PlanInterviewScreen.kt`、`PlanDraftPreview.kt`

**Interfaces:**
- Produces: `PlanBudgetTable(budget, subjects)`；草案预览 HTML 增加预算表。

- [ ] **Step 1: 写失败测试**

`PlanBudgetTest` 增加：`subjectDeltaTone`（>5% 标红）、`budgetWarningText`。

- [ ] **Step 2: 运行确认失败**

Run: Android 单测命令。
Expected: FAIL。

- [ ] **Step 3: 实现**

- VM 在 `loadDraft` 后并行 `GET /plans/:id/overview`，存入 `draftOverview`；失败不阻塞。
- `PlanInterviewScreen` 的 DRAFT 阶段在标题下插入 `PlanBudgetTable`：总量条（已排/目标/有效容量）、逐科行（目标/已排/差异/里程碑前）、警告区置顶。
- `renderDraftHtml` 增加 `overview: PlanOverviewDto?` 参数：在「今日任务变化」前渲染科目预算表与 warnings；数字与 overview 一致。
- 差异 >5% 用 `CoralRed`，缺口用 error container。

- [ ] **Step 4: 运行单测 + 编译**

Run: Android 单测命令。
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanBudgetTable.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewViewModel.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewScreen.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanDraftPreview.kt
git commit -m "feat(app): show draft budget table and warnings"
```

---

### Task 19: 计划页任务编辑、添加、删除与锁定

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanItemEditSheet.kt`
- Modify: `PlanViewModel.kt`、`PlanScreen.kt`
- Test: `PlanEditTest.kt`

**Interfaces:**
- Produces: `PlanItemEditSheet`；`PlanViewModel.editItem/addItem/deleteItem/toggleLock`。

- [ ] **Step 1: 写失败测试**

`PlanEditTest` 测 `optimisticItemPatch(item, edit)` 与 `dayRemainingMinutes(items, capacity, day)` 纯函数。

- [ ] **Step 2: 运行确认失败**

Run: Android 单测命令。
Expected: FAIL。

- [ ] **Step 3: 实现**

VM：
- `editingItem: StateFlow<PlanItemDto?>`、`editBusy`、`editError`、`editSuggestShrinkMinutes`。
- `editItem(item, patch)`：先本地合并到 `serverPlan`，调用 `editPlanItem`；成功用返回 plan 替换；失败回滚并解析 `PLAN_DAY_OVERLOADED.details`（`capacity/planned/overflow/suggestion`）。
- `addItem(req)` / `deleteItem(id)` 同理，先乐观/后回滚。
- `toggleLock(item)` 调用 `editPlanItem(id, locked=!item.locked)`。
- 所有失败文案走 `friendlyPlanError()`。

UI：
- `PlanItemEditSheet`：标题、科目下拉（brief 科目 + 复盘）、日期选择、时长 15 分钟步进（5..480）、锁定开关、保存、删除（二次确认）；只读历史不给入口。
- `PlanScreen.AgendaTaskRow`：外层 `Row` 不再整体 `clickable`；`CompletionCircle` 独立勾选；标题/信息区域 `clickable { onEdit(item) }`；锁定项标题前加 `AppIcons.Lock`（若无则用 `Icons.Outlined.Lock`）。
- 当日卡标题栏增加 `TextAction("+ 添加任务")`；空状态也显示。
- 保存中禁用行操作；错误横幅展示详情与「缩短到 X 分钟」按钮。

- [ ] **Step 4: 运行单测 + 编译**

Run: Android 单测命令。
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanItemEditSheet.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanViewModel.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanScreen.kt YanZhong/app/src/test/java/com/yanzhong/app/ui/plan/PlanEditTest.kt
git commit -m "feat(app): edit add delete and lock plan items"
```

---

### Task 20: 调整方案卡、快捷话术与教练入口

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AdjustmentOptions.kt`
- Modify: `AdjustmentCard.kt`、`PlanInterviewViewModel.kt`、`PlanInterviewScreen.kt`、`PlanScreen.kt`
- Test: `PlanEditTest.kt` 增加 `optionHeadline` 测试

**Interfaces:**
- Produces: `AdjustmentOptionCard`；`selectOption` VM 方法；快捷 chips；教练卡预填。

- [ ] **Step 1: 写失败测试**

`optionHeadline(option)`：多方案标题含「方案」与 label；`impactSummary` 包含移动/删除/风险数。

- [ ] **Step 2: 运行确认失败**

Run: Android 单测命令。
Expected: FAIL。

- [ ] **Step 3: 实现**

VM：
- `adjust` 响应把 `resp.options` 存入 state；`selectOption(id)` 调 `selectAdjustmentOption` 并替换 `adjustment`。
- `confirmAdjustment()` 文案失败处理增加 `ADJUSTMENT_UNDO_STALE`、`LOCKED_ITEMS_CONFLICT`。
- 快捷 chips 常量：`QUICK_ADJUSTMENTS = listOf("今天临时有事","这周时间变少","想多练数学","任务太多，减一点","帮我重新排一下")`。
- `beginSession` 支持 `prefill`：`viewModelScope.launch { delay(300); if (phase==CHATTING) send(prefill) }`。
- 体检卡点击：`navController.navigate(Routes.planInterview("adjust", Uri.encode(suggestedMessage)))`。

UI：
- `AdjustmentOptionCard`：label、summary、影响指标（移动/新增/删除/最大日负荷/风险）、选中态、点击调用 select；下方复用 changes 预览。
- `AdjustmentCard`：单方案保持；多方案时在列表下方显示「按这个方案调整」；成功态「已生效 · 撤销」。
- `PlanInterviewScreen`：输入区上方 chips 横向滚动；追问 `clarifyOptions` 渲染为可点选项；`LOCKED_ITEMS_CONFLICT` 弹窗列出锁定项并提供「去解锁」。
- `PlanScreen`：体检 `suggestedMessage` 存在时展示教练卡（标题「AI 建议」+ 建议 + 「让 AI 调整」），点击带 prefill 进入调整对话。
- `PlanDraftPreview.buildAdjustmentPreview` 支持 `selectedOption` 的 changes（若已 select，服务端 adjustment.changes 即所选方案，直接沿用）。

- [ ] **Step 4: 运行单测 + 编译**

Run: Android 单测命令。
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AdjustmentOptions.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AdjustmentCard.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewViewModel.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewScreen.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanScreen.kt YanZhong/app/src/test/java/com/yanzhong/app/ui/plan/PlanEditTest.kt
git commit -m "feat(app): choose quantified adjustment options and coach suggestions"
```

---

### Task 21: 全量验证、模拟器 E2E 与文档

**Files:**
- Modify: `docs/superpowers/specs/2026-11-02-plan-customization-and-conversational-adjustment-design.md`（状态改为「已实施」）
- Modify: `YanZhong/README.md`（仅当用户要求；否则不动）

- [ ] **Step 1: 服务端全量验证**

Run: `cd server && npm run verify`
Expected: typecheck + 全部测试 + build 通过。

- [ ] **Step 2: Android 全量构建**

Run: Global Constraints 中的 Gradle 命令。
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 模拟器 E2E**

按 spec §8.3 的 10 条流程逐条执行；每条记录通过/失败与截图路径。重点：
1. 面谈 → 口径确认 → 生成 → 预算表 → 确认。
2. 窄作息 → 缺口弹窗 → 尽力版 → 警告。
3. 改期/改时长/加/删/锁 → 刷新后保留；超容量提示。
4. 明天有事 → 单方案 → 确认 → 时间轴更新 → 撤销。
5. 这周每天 2 小时 → 多方案 → 选压缩 → 确认。
6. 锁定冲突 → 解锁重试。
7. 调整后手工改 → 撤销被拒。
8. change_commitment 确认前档案不变、确认后变、撤销还原。
9. 模糊请求追问；闲聊只回复。
10. 勾选/历史/文档/同步/OTA/账号安全回归。

- [ ] **Step 4: 修复 E2E 发现的问题并重跑**

每条失败必须有对应修复提交；不得跳过。

- [ ] **Step 5: 最终提交**

```bash
git add -A
git commit -m "test(plan): verify budget editing and adjustment end to end"
```

> 部署（服务端 + APK 发布）需用户单独确认后执行；本计划不包含自动部署。

---

## Self-Review

- **Spec coverage:** §4 预算 → Task 2/4/5/6；§4.2 brief → Task 3；§4.4 overview → Task 5；§4.5 缺口选择 → Task 6/17；§4.6 ETA → Task 6/17；§5 手工编辑/锁定/重排 → Task 7/8/19；§6.1 窗口 → Task 9；§6.2 意图 → Task 10；§6.3 管线 → Task 13；§6.4 方案 → Task 11；§6.5 L2 → Task 12；§6.6 确认/撤销 → Task 14；§6.7 select → Task 13；§7 客户端 → Task 17-20；§8 测试 → 各任务 + Task 21。
- **Placeholder scan:** 无 TBD/TODO；每个任务都有具体文件、接口、命令与验收。
- **Type consistency:** `PlanBudgetSubject.budgetMinutes/upperMinutes/milestoneBudgetMinutes` 在 Task 2 定义，Task 4 使用；`AdjustmentOption.id` 在 Task 11/13/16/20 一致；`windowFingerprint(items, contextHash)` 在 Task 9/14 一致；`L2Mode` 在 Task 12/13 一致；客户端 DTO 字段与 §4.4 响应一致。
