# 动态计划调整（AI 行程小助手）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-09-29-dynamic-plan-adjustment-design.md`

**Goal:** 把 AI 从一次性计划生成器升级为常驻的行程调整小助手：用户一句话描述突发情况（临时有事/生病/加课），服务端解析意图并按档位（L1 顺延 / L2 重排）在「今天 → 当前阶段末」窗口内调整近期计划，产出待确认调整单；确认后就地更新 active plan 并经既有 `planChanged` → `applyPlanProjection` 链路自动同步到 App；另提供体检（checkup）与每天至多一条的主动提醒。

**Architecture:** 沿用既有权威分工——模型只做「意图解析」与「说明文」，日期/容量/档位由服务端纯函数判定。确认前零副作用（draft 调整单不触碰计划项）；确认时以窗口指纹防并发错乱，事务内把目标快照同步进 `plan_items`（历史项、done 项、阶段末之后的项绝不修改）。调整不建新版本（不动 `Plan.version`）。L1 顺延为纯函数 `planL1Shuffle`；L2 重排沿用「模型给相对序列（dayOffset）、服务端按硬账本展开校验」。撤销回写 `beforeJson`。

**Tech Stack:** 服务端 Node 18 + TypeScript 5.7 + Koa 2.15 + Zod 3.24 + Prisma 6.2/MySQL + ws 8.18，测试用 Node 内置 test runner + `assert/strict`（命令 `npm run verify`）；Android Kotlin 2.0 + Jetpack Compose + Retrofit/kotlinx-serialization + WebSocket。

**工作目录（所有 Gradle 命令）:** `F:\kaoyan-app-prd\YanZhong`

**服务端:** `F:\kaoyan-app-prd\server`（测试命令在该目录执行；单文件测试 `node --import tsx --test src/modules/planning/<file>.test.ts`；项目无 migrations 目录，**禁止 `prisma migrate dev`**，用 `npx prisma validate` + `prisma db push`）

**对 spec 分期的两处实现细化（已确认）:**
1. spec §8 把「指纹校验」排在 D3，但 D2 的确认/撤销端点依赖指纹，故 `confirmAdjustment`/`undoAdjustment` 连同指纹校验前置到 D1（Task 4）。
2. spec §3.10 写 WorkManager，但项目无 WorkManager 依赖，本地提醒复用 `DataSyncer` 既有 15 分钟周期循环（等价效果、零新依赖）。

---

## 文件变更地图

- Modify: `server/prisma/schema.prisma`
  新增 `PlanAdjustment` 模型与 `Plan.adjustments` 关系（不动 Plan/PlanStage/PlanItem 现有结构）。
- Create: `server/src/modules/planning/adjust.ts`
  L1 顺延纯函数 + 窗口快照差异/指纹工具（不碰库、不调模型）。
- Create: `server/src/modules/planning/adjust.test.ts`
  L1 顺延、跨日填充、溢出、diff、指纹的单测。
- Create: `server/src/modules/planning/adjustIntent.ts`
  意图解析（模型调用 + `normalizeAdjustIntent` 清洗）。
- Create: `server/src/modules/planning/adjustIntent.test.ts`
  意图清洗规则单测（窗口裁剪、kind 降级、追问透传、summary 兜底）。
- Create: `server/src/modules/planning/adjustL2.ts`
  L2 硬账本 + 模型相对序列展开 + 校验（总量守恒/每日容量/里程碑）。
- Create: `server/src/modules/planning/adjustL2.test.ts`
  L2 账本/展开/校验单测。
- Modify: `server/src/modules/planning/service.ts`
  `PlanningDb`/`PlanningAi` 扩展；新增 `adjust`/`latestAdjustment`/`confirmAdjustment`/`undoAdjustment`/`checkup` 及窗口同步、指纹校验助手。
- Modify: `server/src/modules/planning/service.test.ts`
  fake db 增加 `planAdjustment` 存储；播种/注入助手 + 7 个集成用例（A-G）+ L2 用例 + checkup 用例。
- Modify: `server/src/modules/planning/routes.ts`
  新增 `POST /plans/adjust`、`GET /plans/adjustments/latest`、`POST /plans/adjustments/:id/confirm`、`POST /plans/adjustments/:id/undo`、`GET /plans/checkup`（全部注册在 `/plans/:id` 之前）。
- Create: `server/src/modules/planning/planReminder.ts`
  `PlanNotifier` 适配器（在线 = WebSocket，厂商推送留接口）+ 每人每天 1 条防打扰。
- Modify: `YanZhong/.../data/remote/Api.kt`
  新增 AdjustmentDto 等 DTO 与 5 个接口方法；`ServerError.friendly()` 补 2 个错误码文案（在 ViewModel）。
- Create: `YanZhong/.../ui/plan/AdjustmentCard.kt`
  L1 变动清单卡片。
- Modify: `YanZhong/.../ui/plan/PlanInterviewViewModel.kt`
  `InterviewMode.BUILD|ADJUST` 分流；ADJUST 发消息走 `/plans/adjust`；确认调整；错误码文案。
- Modify: `YanZhong/.../ui/plan/PlanInterviewScreen.kt`
  ADJUST 模式标题/副标题、隐藏三枚事实 chip、内嵌 AdjustmentCard、L2 整页预览浮层。
- Modify: `YanZhong/.../ui/plan/PlanDraftPreview.kt`
  `renderDraftHtml` 新增「本次调整」章节；`buildAdjustmentPreview` 合成预览计划。
- Modify: `YanZhong/.../ui/plan/PlanViewModel.kt` + `PlanScreen.kt`
  计划页「撤销上次调整」入口。
- Modify: `YanZhong/.../data/remote/StatusSyncClient.kt`
  `SyncNotice.PlanReminder` + `planReminder` 下行分支。
- Modify: `YanZhong/.../data/remote/DataSyncer.kt`
  15 分钟周期轮询 `GET /plans/checkup` + WS 提醒落地为本地通知（每天 1 条去重）。
- Modify: `YanZhong/.../service/FocusService.kt`
  companion object 新增 `postPlanReminder`（复用 CHANNEL_EVENT，不抢屏不发声）。

---

## 阶段一：D1 服务端调整单底座

### Task 1: Prisma 新增 PlanAdjustment 表

**Files:**
- Modify: `server/prisma/schema.prisma`

**背景（当前缺陷）:** 计划只有整份重建一条路（`Plan.version` + 归档）。「明天有事」也要重排整份计划，代价大且丢失节奏；没有承载「待确认调整单」的表。

**设计说明（对 spec 的实现细化）:** 按 spec §3.3 全字段建表；`status` 含 `expired` 为预留值（本阶段无端点写入它，draft 被下一次 adjust 原地覆盖）。项目无 migrations 目录，只做 `prisma validate`，建表交给部署时的 `prisma db push`（`npm run verify` 内的 `prisma generate` 会更新客户端类型）。

- [ ] **Step 1: 在 `PlanItem` 模型之后新增模型**

在 `schema.prisma` 的 `model PlanItem { ... }` 结束后插入：

```prisma
// 行程调整单:AI 按用户一句突发情况算出的「待确认新排法」。
// 确认前是 draft(不碰计划项);确认后 applied 并把 afterJson 写进 plan_items;
// undo 把 beforeJson 写回去。调整是就地变更,不动 Plan.version。
model PlanAdjustment {
  id          Int       @id @default(autoincrement())
  userGuid    String    @db.VarChar(36)
  planId      Int
  tier        String    @db.VarChar(4) // L1 顺延 / L2 重排
  status      String    @default("draft") @db.VarChar(16) // draft/applied/undone/expired
  reason      String?   @db.Text // 用户原始诉求(意图 note 或原话)
  summary     String?   @db.Text // 面向用户的调整说明
  windowFrom  DateTime
  windowTo    DateTime
  beforeJson  String?   @db.Text // 调整前窗口内计划项快照
  afterJson   String?   @db.Text // 调整后窗口内计划项快照(确认即落这份)
  fingerprint String    @db.VarChar(64) // 确认时校验窗口未被第三方改过
  createdAt   DateTime  @default(now())
  appliedAt   DateTime?
  undoneAt    DateTime?

  plan Plan @relation(fields: [planId], references: [id], onDelete: Cascade)

  @@index([userGuid, status])
  @@index([planId])
  @@map("plan_adjustments")
}
```

- [ ] **Step 2: 在 `Plan` 模型的关系区补反向关系**

在 `model Plan` 内 `items PlanItem[]` 之后加一行：

```prisma
  adjustments PlanAdjustment[]
```

- [ ] **Step 3: 校验 schema**

在 `F:\kaoyan-app-prd\server` 下执行：

```powershell
npx prisma validate
```

### Task 2: 新建 adjust.ts —— L1 顺延纯函数 + 快照/指纹工具

**Files:**
- Create: `server/src/modules/planning/adjust.ts`
- Create: `server/src/modules/planning/adjust.test.ts`

**背景（当前缺陷）:** 顺延/差异/指纹逻辑还不存在；它们必须是不碰库、不调模型的纯函数，才能按 spec §7 独立单测。

**设计说明（对 spec 的实现细化）:** 容量口径与 `assertGeneratedPlanValid` 完全一致（`min(dailyMinutes, netAvailableMinutes)`），`document.ts` 的 `clockMinutes` 未导出，故一律经 `netAvailableMinutes` 取净空闲。L1「只顺延不提前」：每项最早落回原日期（`floor`）。指纹只覆盖非 done 项——打卡随时发生，不能让正常打卡废掉一张调整单。

- [ ] **Step 1: 写入 adjust.ts 全文**

```ts
import { createHash } from 'node:crypto'
import { netAvailableMinutes, type PlanBrief } from './document'

/**
 * 行程调整的纯函数层:档位判定、L1 顺延、窗口快照差异与指纹。
 * 这里不碰数据库、不调模型 —— service 负责取数,模型负责意图,这里只管算。
 */

/** 调整档位:L1 = 只挪日期的小顺延;L2 = 允许增删改的重排 */
export type AdjustmentTier = 'L1' | 'L2'

/** 可以被算法挪动的窗口内待办项(排进算法前已剔除 done 与窗口外) */
export interface AdjustableItem {
  id: number
  subject: string
  title: string
  planDate: string
  minutes: number
  priority: number
  sortOrder: number
}

/** 调整窗口快照:确认时用它做指纹比对,撤销时用它还原 */
export interface WindowSnapshotItem {
  id: number
  subject: string
  title: string
  planDate: string
  minutes: number
  status: string
}

/** L1 的单条挪动:只改 planDate,其余字段原样保留 */
export interface L1Move {
  item: AdjustableItem
  from: string
  to: string
}

export interface L1Result {
  moves: L1Move[]
  /** 窗口内放到最后一天也放不下的项(按顺序溢出) */
  overflow: AdjustableItem[]
}

export interface L1Input {
  pending: AdjustableItem[]
  /** 每日剩余容量(已扣除当天 done 项),key 为 YYYY-MM-DD */
  capacity: Map<string, number>
  windowFrom: string
  windowTo: string
}

/** 给确认卡片看的单条变动 */
export interface AdjustmentChange {
  kind: 'moved' | 'added' | 'removed' | 'updated'
  id: number
  subject: string
  title: string
  from?: { planDate: string; minutes: number; title: string }
  to?: { planDate: string; minutes: number; title: string }
}

/** L1 的「影响天数」上限:被挪动任务涉及的 from∪to 日期数超过它就升级到 L2(默认 2 天,PLAN_L1_MAX_DAYS 可覆盖) */
export const L1_MAX_AFFECTED_DAYS = Number(process.env.PLAN_L1_MAX_DAYS ?? 2) || 2

/** 'YYYY-MM-DD' 加减天数,返回 'YYYY-MM-DD' */
export function shiftDate(day: string, days: number): string {
  const date = new Date(`${day}T00:00:00.000Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

/** 窗口内全部日期(含首尾);from > to 时返回空数组 */
export function datesBetween(from: string, to: string): string[] {
  const days: string[] = []
  let cursor = from
  while (cursor <= to) {
    days.push(cursor)
    cursor = shiftDate(cursor, 1)
  }
  return days
}

/** 某天容量 = min(每日目标分钟, 当天净空闲);无 brief 时退化为 dailyMinutes。与 assertGeneratedPlanValid 同口径 */
export function dailyCapacity(brief: PlanBrief | null, dailyMinutes: number, date: string): number {
  if (!brief) return Math.max(0, dailyMinutes)
  return Math.max(0, Math.min(dailyMinutes, netAvailableMinutes(brief, new Date(`${date}T00:00:00.000Z`))))
}

/**
 * L1 顺延:按原计划顺序逐项找「不早于原日期」的第一个有容量的日子放下。
 * 只顺延不提前、只改日期不改内容;放不进窗口的进 overflow,由 service 决定报缺口还是升级 L2。
 */
export function planL1Shuffle(input: L1Input): L1Result {
  const days = datesBetween(input.windowFrom, input.windowTo)
  const left = new Map(input.capacity)
  const moves: L1Move[] = []
  const overflow: AdjustableItem[] = []
  const ordered = [...input.pending].sort((a, b) =>
    a.planDate.localeCompare(b.planDate) || a.priority - b.priority
    || a.sortOrder - b.sortOrder || a.id - b.id)
  for (const item of ordered) {
    // 顺延不提前:最早只能落回它原本的日子(原本的日子已在窗口外时,从窗口头开始)
    const floor = item.planDate > input.windowFrom ? item.planDate : input.windowFrom
    let placed: string | null = null
    for (const day of days) {
      if (day < floor) continue
      if ((left.get(day) ?? 0) >= item.minutes) {
        left.set(day, (left.get(day) ?? 0) - item.minutes)
        placed = day
        break
      }
    }
    if (placed == null) overflow.push(item)
    else if (placed !== item.planDate) moves.push({ item, from: item.planDate, to: placed })
  }
  return { moves, overflow }
}

/** L1 影响天数:被挪动任务涉及的 from∪to 去重日期数 */
export function affectedDays(moves: L1Move[]): number {
  const days = new Set<string>()
  for (const move of moves) {
    days.add(move.from)
    days.add(move.to)
  }
  return days.size
}

/** 对比窗口前后快照,产出给人看的变动清单(按 日期 → 类型 → 科目 排序,输出稳定) */
export function diffSnapshots(before: WindowSnapshotItem[], after: WindowSnapshotItem[]): AdjustmentChange[] {
  const beforeById = new Map(before.filter(item => item.id > 0).map(item => [item.id, item]))
  const afterIds = new Set(after.filter(item => item.id > 0).map(item => item.id))
  const changes: AdjustmentChange[] = []
  for (const item of after) {
    const old = item.id > 0 ? beforeById.get(item.id) : undefined
    if (!old) {
      changes.push({ kind: 'added', id: item.id, subject: item.subject, title: item.title,
        to: { planDate: item.planDate, minutes: item.minutes, title: item.title } })
    } else if (old.planDate !== item.planDate) {
      changes.push({ kind: 'moved', id: item.id, subject: item.subject, title: item.title,
        from: { planDate: old.planDate, minutes: old.minutes, title: old.title },
        to: { planDate: item.planDate, minutes: item.minutes, title: item.title } })
    } else if (old.minutes !== item.minutes || old.title !== item.title || old.subject !== item.subject) {
      changes.push({ kind: 'updated', id: item.id, subject: item.subject, title: item.title,
        from: { planDate: old.planDate, minutes: old.minutes, title: old.title },
        to: { planDate: item.planDate, minutes: item.minutes, title: item.title } })
    }
  }
  for (const item of before) {
    if (item.id > 0 && !afterIds.has(item.id)) {
      changes.push({ kind: 'removed', id: item.id, subject: item.subject, title: item.title,
        from: { planDate: item.planDate, minutes: item.minutes, title: item.title } })
    }
  }
  const order = { moved: 0, updated: 1, added: 2, removed: 3 } as const
  return changes.sort((a, b) => {
    const dayA = a.to?.planDate ?? a.from!.planDate
    const dayB = b.to?.planDate ?? b.from!.planDate
    return dayA.localeCompare(dayB) || order[a.kind] - order[b.kind] || a.subject.localeCompare(b.subject)
  })
}

/**
 * 窗口指纹:调整单生成时记一份,确认时重算比对 —— 不一致说明窗口被人动过,调整单作废(409 ADJUSTMENT_STALE)。
 * done 项不参与:打卡随时发生,不该让一张调整单因为正常打卡而全部失效。
 */
export function windowFingerprint(items: WindowSnapshotItem[]): string {
  const payload = items
    .filter(item => item.id > 0 && item.status !== 'done')
    .map(item => `${item.id}:${item.planDate}:${item.minutes}`)
    .sort()
    .join('|')
  return createHash('sha256').update(payload).digest('hex')
}
```

- [ ] **Step 2: 写入 adjust.test.ts 全文**

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  affectedDays, datesBetween, diffSnapshots, planL1Shuffle, shiftDate, windowFingerprint,
  type AdjustableItem, type WindowSnapshotItem,
} from './adjust'

function item(overrides: Partial<AdjustableItem> & { id: number; planDate: string; minutes: number }): AdjustableItem {
  return { subject: '数学', title: `任务${overrides.id}`, priority: 0, sortOrder: 0, ...overrides }
}

function snap(overrides: Partial<WindowSnapshotItem> & { id: number; planDate: string; minutes: number }): WindowSnapshotItem {
  return { subject: '数学', title: `任务${overrides.id}`, status: 'pending', ...overrides }
}

test('shiftDate/datesBetween 处理 UTC 日界与空窗口', () => {
  assert.equal(shiftDate('2026-09-29', 1), '2026-09-30')
  assert.equal(shiftDate('2026-09-30', 1), '2026-10-01')
  assert.deepEqual(datesBetween('2026-09-29', '2026-10-01'), ['2026-09-29', '2026-09-30', '2026-10-01'])
  assert.deepEqual(datesBetween('2026-10-02', '2026-10-01'), [])
})

test('L1:冲突日顺延到次日', () => {
  const result = planL1Shuffle({
    pending: [item({ id: 1, planDate: '2026-09-29', minutes: 60 })],
    capacity: new Map([['2026-09-29', 0], ['2026-09-30', 120]]),
    windowFrom: '2026-09-29',
    windowTo: '2026-09-30',
  })
  assert.equal(result.overflow.length, 0)
  assert.deepEqual(result.moves.map(move => [move.item.id, move.from, move.to]), [[1, '2026-09-29', '2026-09-30']])
})

test('L1:多项跨日填充,先来的先挑日子', () => {
  const result = planL1Shuffle({
    pending: [
      item({ id: 1, planDate: '2026-09-29', minutes: 100 }),
      item({ id: 2, planDate: '2026-09-29', minutes: 100 }),
      item({ id: 3, planDate: '2026-09-29', minutes: 50 }),
    ],
    capacity: new Map([['2026-09-29', 120], ['2026-09-30', 120], ['2026-10-01', 120]]),
    windowFrom: '2026-09-29',
    windowTo: '2026-10-01',
  })
  // id1 留在原日;id2 顺延到 30 日;id3 在 30 日只剩 20 放不下 → 10-01
  assert.deepEqual(result.moves.map(move => [move.item.id, move.to]), [[2, '2026-09-30'], [3, '2026-10-01']])
  assert.equal(result.overflow.length, 0)
})

test('L1:窗口末仍放不下 → overflow 报缺口', () => {
  const result = planL1Shuffle({
    pending: [item({ id: 1, planDate: '2026-09-29', minutes: 200 })],
    capacity: new Map([['2026-09-29', 0], ['2026-09-30', 120]]),
    windowFrom: '2026-09-29',
    windowTo: '2026-09-30',
  })
  assert.equal(result.moves.length, 0)
  assert.equal(result.overflow.length, 1)
  assert.equal(result.overflow[0].id, 1)
})

test('L1:只顺延不提前', () => {
  const result = planL1Shuffle({
    pending: [item({ id: 1, planDate: '2026-09-30', minutes: 60 })],
    capacity: new Map([['2026-09-29', 120], ['2026-09-30', 120]]),
    windowFrom: '2026-09-29',
    windowTo: '2026-09-30',
  })
  assert.equal(result.moves.length, 0)
  assert.equal(result.overflow.length, 0)
})

test('affectedDays:from∪to 去重计数', () => {
  const moves = [
    { item: item({ id: 1, planDate: '2026-09-29', minutes: 10 }), from: '2026-09-29', to: '2026-09-30' },
    { item: item({ id: 2, planDate: '2026-09-30', minutes: 10 }), from: '2026-09-30', to: '2026-10-01' },
  ]
  assert.equal(affectedDays(moves), 3)
})

test('diffSnapshots:moved/added/removed/updated 各归各位且排序稳定', () => {
  const before = [
    snap({ id: 1, planDate: '2026-09-29', minutes: 60 }),
    snap({ id: 2, planDate: '2026-09-29', minutes: 30 }),
    snap({ id: 3, planDate: '2026-09-30', minutes: 45 }),
  ]
  const after = [
    snap({ id: 1, planDate: '2026-09-30', minutes: 60 }),
    snap({ id: 2, planDate: '2026-09-29', minutes: 50, title: '改了名' }),
    snap({ id: 0, planDate: '2026-09-30', minutes: 30, title: '新增' }),
  ]
  const changes = diffSnapshots(before, after)
  // 09-29:updated(id2);09-30:moved(id1) → added(id0) → removed(id3)
  assert.deepEqual(changes.map(change => change.kind), ['updated', 'moved', 'added', 'removed'])
  const removed = changes.find(change => change.kind === 'removed')!
  assert.equal(removed.id, 3)
})

test('windowFingerprint:done 项不参与,内容变了指纹必变,顺序无关', () => {
  const base = [snap({ id: 1, planDate: '2026-09-29', minutes: 60 })]
  const withDone = [...base, snap({ id: 2, planDate: '2026-09-30', minutes: 30, status: 'done' })]
  assert.equal(windowFingerprint(base), windowFingerprint(withDone))
  assert.notEqual(windowFingerprint(base), windowFingerprint([snap({ id: 1, planDate: '2026-09-29', minutes: 90 })]))
  assert.notEqual(windowFingerprint(base), windowFingerprint([snap({ id: 1, planDate: '2026-09-30', minutes: 60 })]))
  assert.equal(
    windowFingerprint([snap({ id: 1, planDate: '2026-09-29', minutes: 60 }), snap({ id: 2, planDate: '2026-09-30', minutes: 30 })]),
    windowFingerprint([snap({ id: 2, planDate: '2026-09-30', minutes: 30 }), snap({ id: 1, planDate: '2026-09-29', minutes: 60 })]),
  )
})
```

- [ ] **Step 3: 运行单测**

在 `F:\kaoyan-app-prd\server` 下执行：

```powershell
node --import tsx --test src/modules/planning/adjust.test.ts
```

### Task 3: 新建 adjustIntent.ts —— 意图解析（模型 + 清洗）

**Files:**
- Create: `server/src/modules/planning/adjustIntent.ts`
- Create: `server/src/modules/planning/adjustIntent.test.ts`

**背景（当前缺陷）:** 没有「把一句话拆成结构化意图」的模块；面谈（aiCoach）以补齐备考事实为目标，语义不适用。

**设计说明（对 spec 的实现细化）:** 模型输出的日期越界一律丢弃（窗口 `[today, windowTo]`）；数据撑不起 kind 就降级为 `chat`；`needClarify` 优先于降级（先问清再动手）。清洗是纯函数可独立单测，模型调用失败统一翻成 `AiUnavailable`（service 再转 502 `PLAN_INTERVIEW_FAILED`，spec §6「沿用既有 LlmError 分类」）。

- [ ] **Step 1: 写入 adjustIntent.ts 全文**

```ts
import { chatComplete, extractJson, LlmError } from '../../shared/llm/client'
import { AiUnavailable } from './aiGenerator'
import type { PlanBrief } from './document'

/**
 * 行程调整的「意图解析」:模型唯一的工作是把用户这句话拆成结构化意图,
 * 档位、日期、容量全部由服务端判定 —— 模型不输出任何计划内容。
 */

/** unavailable=某几天完全没空 / reduce_capacity=几天可用时间变少 / change_commitment=每周固定占用变了 / chat=闲聊或没说清 */
export type AdjustIntentKind = 'unavailable' | 'reduce_capacity' | 'change_commitment' | 'chat'

/** change_commitment:每周固定的新占用(与 brief.fixedCommitments 同构) */
export interface AdjustCommitment {
  weekday: number
  start: string
  end: string
  label: string
}

/** reduce_capacity:某天可用时间只剩 minutes 分钟 */
export interface AdjustCapacityWindow {
  day: string
  minutes: number
}

export interface AdjustIntent {
  kind: AdjustIntentKind
  /** unavailable:完全没空的日期(YYYY-MM-DD,已裁剪到调整窗口内) */
  days: string[]
  windows: AdjustCapacityWindow[]
  commitments: AdjustCommitment[]
  /** 用户原话的补充说明,写入调整单 reason */
  note: string
  /** 给确认卡片的一句话摘要(模型写,服务端兜底) */
  summary: string
  needClarify: boolean
  clarifyQuestion: string
}

export interface AdjustIntentInput {
  message: string
  today: string
  /** 调整窗口上限(当前阶段末),模型给出的日期越界一律丢弃 */
  windowTo: string
  /** 当前计划涉及的科目名 */
  subjects: string[]
  brief: PlanBrief | null
}

const KINDS: AdjustIntentKind[] = ['unavailable', 'reduce_capacity', 'change_commitment', 'chat']
const DAY_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/
const ADJUST_MAX_TOKENS = 700

export async function runAdjustIntent(input: AdjustIntentInput): Promise<AdjustIntent> {
  try {
    const raw = await chatComplete({
      messages: [
        { role: 'system', content: buildSystemPrompt() },
        { role: 'user', content: buildUserPrompt(input) },
      ],
      json: true,
      temperature: 0.2,
      maxTokens: ADJUST_MAX_TOKENS,
      timeoutMs: 30_000,
    })
    return normalizeAdjustIntent(extractJson<any>(raw), input)
  } catch (error) {
    if (error instanceof LlmError) throw new AiUnavailable(error.message)
    throw error
  }
}

function buildSystemPrompt(): string {
  return [
    '你是考研备考计划助手「研钟」的行程调整分析师。',
    '用户会告诉你一件突发事情(临时有事、生病、加课、固定安排变了等)。',
    '你的任务是把这句话解析成结构化意图,只输出 JSON,不要输出多余文本。',
    'JSON 字段:',
    '- kind: "unavailable"(某几天完全没空) | "reduce_capacity"(某几天可用时间变少) | "change_commitment"(每周固定占用变了,如换课表) | "chat"(闲聊或信息不足)',
    '- days: 完全没空的日期数组,格式 YYYY-MM-DD,必须在给定窗口内',
    '- windows: 数组,每项 {day: "YYYY-MM-DD", minutes: 数字},表示那天只能学这么多分钟',
    '- commitments: 数组,每项 {weekday: 1-7(周一=1), start: "HH:mm", end: "HH:mm", label: "事项名"},表示每周固定的新占用',
    '- note: 从用户原话提取的补充说明,一句话',
    '- summary: 用一句话向用户复述你的理解,不超过 30 字',
    '- needClarify: 布尔,信息不足需要追问时为 true',
    '- clarifyQuestion: 需要追问时的问题,不超过 50 字',
    '规则:',
    '- 用户没说清日期时先追问,不要猜测。',
    '- 「明天」「后天」按给出的今天日期换算。',
    '- 生病、临时有事按 unavailable;换课表、长期占用按 change_commitment。',
    '- 与备考计划无关的闲聊一律 kind=chat。',
  ].join('\n')
}

function buildUserPrompt(input: AdjustIntentInput): string {
  const briefLines: string[] = []
  if (input.brief) {
    for (const subject of input.brief.examSubjects) {
      briefLines.push(`科目:${subject.name},剩余约${subject.remainingMinutes}分钟` +
        (subject.milestone ? `,里程碑「${subject.milestone}」${subject.milestoneDate ?? ''}` : ''))
    }
  }
  return [
    `今天:${input.today}`,
    `本次最多可以调整到:${input.windowTo}(只允许这个日期及之前,且不早于今天)`,
    `当前计划涉及的科目:${input.subjects.join('、') || '未知'}`,
    ...(briefLines.length > 0 ? ['', '各科概况:', ...briefLines] : []),
    '',
    `用户说:${input.message}`,
  ].join('\n')
}

/** 清洗模型输出:越界日期丢弃、非法条目丢弃、数据撑不起 kind 降级为 chat、summary 缺省走兜底文案 */
export function normalizeAdjustIntent(raw: any, input: AdjustIntentInput): AdjustIntent {
  const text = (value: unknown, max: number) => typeof value === 'string' ? value.trim().slice(0, max) : ''
  const kind = KINDS.includes(raw?.kind) ? raw.kind as AdjustIntentKind : 'chat'
  const needClarify = raw?.needClarify === true

  // 模型给出的日期必须落在调整窗口内(不早于今天、不晚于阶段末),越界一律丢弃
  const days = Array.isArray(raw?.days)
    ? [...new Set(raw.days.filter((day: unknown) => typeof day === 'string' && DAY_PATTERN.test(day)
        && day >= input.today && day <= input.windowTo) as string[])].sort()
    : []

  const windows: AdjustCapacityWindow[] = []
  if (Array.isArray(raw?.windows)) {
    for (const entry of raw.windows) {
      const day = typeof entry?.day === 'string' ? entry.day : ''
      const minutes = Math.round(Number(entry?.minutes))
      if (!DAY_PATTERN.test(day) || day < input.today || day > input.windowTo) continue
      if (!Number.isFinite(minutes) || minutes < 0 || minutes > 1440) continue
      if (windows.some(existing => existing.day === day)) continue
      windows.push({ day, minutes })
    }
  }
  windows.sort((a, b) => a.day.localeCompare(b.day))

  const commitments: AdjustCommitment[] = []
  if (Array.isArray(raw?.commitments)) {
    for (const entry of raw.commitments) {
      const weekday = Math.round(Number(entry?.weekday))
      const start = typeof entry?.start === 'string' ? entry.start : ''
      const end = typeof entry?.end === 'string' ? entry.end : ''
      const label = text(entry?.label, 60) || '固定占用'
      if (!Number.isInteger(weekday) || weekday < 1 || weekday > 7) continue
      if (!TIME_PATTERN.test(start) || !TIME_PATTERN.test(end) || start >= end) continue
      if (commitments.some(existing => existing.weekday === weekday && existing.start === start
        && existing.end === end && existing.label === label)) continue
      commitments.push({ weekday, start, end, label })
    }
  }

  // 降级:解析出的数据撑不起这个 kind,就当闲聊处理(needClarify 优先,让模型先问清楚)
  let finalKind = kind
  if (!needClarify) {
    if (finalKind === 'unavailable' && days.length === 0 && windows.length === 0) finalKind = 'chat'
    if (finalKind === 'reduce_capacity' && windows.length === 0) finalKind = 'chat'
    if (finalKind === 'change_commitment' && commitments.length === 0) finalKind = 'chat'
  }

  const note = text(raw?.note, 300)
  const summary = text(raw?.summary, 300) || defaultSummary(finalKind, days, windows, commitments)
  return {
    kind: finalKind,
    days,
    windows,
    commitments,
    note,
    summary,
    needClarify,
    clarifyQuestion: text(raw?.clarifyQuestion, 200),
  }
}

function defaultSummary(
  kind: AdjustIntentKind, days: string[], windows: AdjustCapacityWindow[], commitments: AdjustCommitment[],
): string {
  const fmtDay = (day: string) => `${day.slice(5, 7)}月${day.slice(8, 10)}日`
  if (kind === 'unavailable') {
    return days.length > 0 ? `${days.map(fmtDay).join('、')}没空,把任务顺延` : '近期没空,把任务顺延'
  }
  if (kind === 'reduce_capacity') {
    return windows.length > 0
      ? `${windows.map(window => `${fmtDay(window.day)}只剩${window.minutes}分钟`).join('、')},按新时间重排`
      : '近期时间变少,按新时间重排'
  }
  if (kind === 'change_commitment') {
    return commitments.length > 0
      ? `每周固定安排多了${commitments.length}项,重新平衡近期计划`
      : '每周固定安排变了,重新平衡近期计划'
  }
  return '聊聊近期的备考安排'
}
```

- [ ] **Step 2: 写入 adjustIntent.test.ts 全文**

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeAdjustIntent, type AdjustIntentInput } from './adjustIntent'

const input: AdjustIntentInput = {
  message: '明天有事',
  today: '2026-09-29',
  windowTo: '2026-10-06',
  subjects: ['数学', '英语'],
  brief: null,
}

test('unavailable:窗口外日期被丢弃、去重,正常字段保留', () => {
  const intent = normalizeAdjustIntent({
    kind: 'unavailable',
    days: ['2026-09-30', '2026-10-10', 'bad', '2026-09-30'],
    note: '家里有事',
    summary: '9月30日没空',
  }, input)
  assert.equal(intent.kind, 'unavailable')
  assert.deepEqual(intent.days, ['2026-09-30'])
  assert.equal(intent.note, '家里有事')
  assert.equal(intent.summary, '9月30日没空')
  assert.equal(intent.needClarify, false)
})

test('数据撑不起 kind → 降级为 chat', () => {
  assert.equal(normalizeAdjustIntent({ kind: 'unavailable', days: [] }, input).kind, 'chat')
  assert.equal(normalizeAdjustIntent({ kind: 'reduce_capacity', windows: [] }, input).kind, 'chat')
  assert.equal(normalizeAdjustIntent({ kind: 'change_commitment', commitments: [] }, input).kind, 'chat')
  assert.equal(normalizeAdjustIntent({ kind: 'whatever' }, input).kind, 'chat')
})

test('needClarify 优先于降级,追问原样透传', () => {
  const intent = normalizeAdjustIntent(
    { kind: 'unavailable', days: [], needClarify: true, clarifyQuestion: '哪天没空?' }, input)
  assert.equal(intent.needClarify, true)
  assert.equal(intent.clarifyQuestion, '哪天没空?')
  assert.equal(intent.kind, 'unavailable')
})

test('windows:越界/负数/重复日被过滤,分钟取整', () => {
  const intent = normalizeAdjustIntent({
    kind: 'reduce_capacity',
    windows: [
      { day: '2026-09-30', minutes: 90.6 },
      { day: '2026-10-10', minutes: 60 },
      { day: '2026-09-30', minutes: 30 },
      { day: '2026-10-01', minutes: -5 },
    ],
  }, input)
  assert.deepEqual(intent.windows, [{ day: '2026-09-30', minutes: 91 }])
})

test('commitments:非法星期/时间段被过滤,label 缺省兜底', () => {
  const intent = normalizeAdjustIntent({
    kind: 'change_commitment',
    commitments: [
      { weekday: 1, start: '19:00', end: '21:00', label: '晚自习' },
      { weekday: 9, start: '19:00', end: '21:00', label: 'x' },
      { weekday: 2, start: '21:00', end: '19:00', label: 'x' },
      { weekday: 3, start: '08:00', end: '10:00' },
    ],
  }, input)
  assert.deepEqual(intent.commitments, [
    { weekday: 1, start: '19:00', end: '21:00', label: '晚自习' },
    { weekday: 3, start: '08:00', end: '10:00', label: '固定占用' },
  ])
})

test('summary 缺省时按 kind 生成兜底文案', () => {
  const intent = normalizeAdjustIntent({ kind: 'unavailable', days: ['2026-09-30'] }, input)
  assert.equal(intent.summary, '09月30日没空,把任务顺延')
})
```

- [ ] **Step 3: 运行单测**

```powershell
node --import tsx --test src/modules/planning/adjustIntent.test.ts
```

### Task 4: service.ts 扩展 —— PlanningDb/PlanningAi + adjust/latest/confirm/undo

**Files:**
- Modify: `server/src/modules/planning/service.ts`

**背景（当前缺陷）:** 服务层只有整份计划的生成/确认/打卡；没有调整单的产出、恢复、确认（含指纹校验）、撤销。

**设计说明（对 spec 的实现细化）:**
- 指纹校验按前面记的决策从 D3 前置到本任务（D2 客户端依赖 confirm/undo）。
- D1 只有 L1：`planL1Shuffle` 溢出即 400 `PLAN_CAPACITY_INSUFFICIENT`；**受影响天数暂不设上限**（D1 无 L2 可升级，Task 13 接入档位判定）。
- `syncWindowItems` 的 `existingById` 必须基于**全表**构建：目标快照里的 done 项可能落在窗口外，只查窗口内会把它们误判成「新增」。删除/更新只作用于「窗口内且非 done」。
- 新代码**不得给 planItem 写 `updatedAt`**（PlanItem 表无此列；`plan.updateMany` 可以写）。
- 已有 draft 时 `updateMany` 原地覆盖（spec §6：不产生第二个 draft），覆盖后重取行返回。

- [ ] **Step 1: 扩展 imports**

在 `service.ts` 头部（L9 document 导入之后）加：

```ts
import {
  dailyCapacity, datesBetween, diffSnapshots, planL1Shuffle, shiftDate, windowFingerprint,
  type AdjustableItem, type WindowSnapshotItem,
} from './adjust'
import { runAdjustIntent, type AdjustIntent, type AdjustIntentInput } from './adjustIntent'
```

（`affectedDays`、`L1_MAX_AFFECTED_DAYS`、`AdjustmentTier` 与 adjustL2 的导入在 Task 13 再加。）

- [ ] **Step 2: 扩展 PlanningDb 接口**

`planItem` 分支补 `deleteMany`，其后新增 `planAdjustment` 分支：

```ts
  planItem: {
    createMany(args: AnyArgs): Promise<any>
    findMany(args?: AnyArgs): Promise<any[]>
    updateMany(args: AnyArgs): Promise<any>
    deleteMany(args: AnyArgs): Promise<any>
    groupBy(args: AnyArgs): Promise<any[]>
  }
  planAdjustment: {
    findFirst(args?: AnyArgs): Promise<any>
    findMany(args?: AnyArgs): Promise<any[]>
    create(args: AnyArgs): Promise<any>
    updateMany(args: AnyArgs): Promise<any>
  }
```

- [ ] **Step 3: 扩展 PlanningAi 并更新默认实现**

`PlanningAi` 接口（`parseTimetable` 之后）加可选项：

```ts
  adjustIntent?: (input: AdjustIntentInput) => Promise<AdjustIntent>
```

`createPlanningService` 的默认参数对象补 `adjustIntent: runAdjustIntent`。

- [ ] **Step 4: 新增 PublicAdjustment 类型**

在 `PublicPlan` 接口定义之后加：

```ts
/** 调整单的对外结构;changes 由 before/after 快照重算,客户端拿去渲染变动清单 */
export interface PublicAdjustment {
  id: number
  planId: number
  tier: string
  status: string
  reason: string | null
  summary: string | null
  windowFrom: string
  windowTo: string
  changes: ReturnType<typeof diffSnapshots>
  createdAt: number | null
  appliedAt: number | null
}
```

- [ ] **Step 5: 新增窗口/快照助手函数**

在 `profileInputFromRow`（L276 附近）之前插入：

```ts
// ---------- 行程调整(D1):窗口快照、指纹校验与窗口同步 ----------

function toAdjustable(row: any): AdjustableItem {
  return {
    id: Number(row.id), subject: String(row.subject ?? ''), title: String(row.title ?? ''),
    planDate: dateOnly(row.planDate), minutes: Number(row.minutes ?? 0),
    priority: Number(row.priority ?? 0), sortOrder: Number(row.sortOrder ?? 0),
  }
}

function toSnapshot(items: any[]): WindowSnapshotItem[] {
  return items.map(row => ({
    id: Number(row.id), subject: String(row.subject ?? ''), title: String(row.title ?? ''),
    planDate: dateOnly(row.planDate), minutes: Number(row.minutes ?? 0), status: String(row.status ?? 'pending'),
  }))
}

function jsonList<T>(raw: unknown): T[] {
  try {
    const parsed = JSON.parse(typeof raw === 'string' ? raw : 'null')
    return Array.isArray(parsed) ? parsed as T[] : []
  } catch {
    return []
  }
}

/** 调整窗口 = [今天, 当前阶段末]:优先覆盖今天的阶段,没有则取第一个未来阶段,再没有(全部过完)报 404 */
function stageWindow(stages: any[], today: string): { from: string; to: string } {
  const sorted = [...stages].sort((a, b) => Number(a.sortOrder ?? 0) - Number(b.sortOrder ?? 0))
  const stage = sorted.find(item => dateOnly(item.startDate) <= today && today <= dateOnly(item.endDate))
    ?? sorted.find(item => dateOnly(item.startDate) > today)
  if (!stage) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划的阶段已全部结束,没有可调整的内容')
  const to = dateOnly(stage.endDate)
  if (to < today) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划的阶段已全部结束,没有可调整的内容')
  return { from: today, to }
}

function stageIdFor(stages: any[], day: string): number {
  const hit = stages.find(stage => dateOnly(stage.startDate) <= day && day <= dateOnly(stage.endDate))
  return Number(hit?.id ?? stages[0]?.id ?? 0)
}

/**
 * 把窗口内的计划项改写成目标快照:
 * - 窗口内、非 done、目标里没有的 → 删除
 * - 窗口内、非 done、字段变了 → 原地更新(注意 plan_items 没有 updatedAt 列,不写它)
 * - 目标里新增(id<=0 或库里不存在) → 插入
 * done 项与窗口外的项一律不碰。existingById 必须基于全表:
 * 目标快照里的 done 项可能落在窗口外,只查窗口内会把它们误判成「新增」。
 */
async function syncWindowItems(tx: PlanningDb, planId: number, windowFrom: string, windowTo: string,
  target: WindowSnapshotItem[], stages: any[]): Promise<void> {
  const allRows = await tx.planItem.findMany({ where: { planId } })
  const existingById = new Map<number, any>(allRows.map(row => [Number(row.id), row]))
  const targetIds = new Set(target.filter(item => item.id > 0 && existingById.has(item.id)).map(item => item.id))

  const removeIds = allRows
    .filter(row => {
      const day = dateOnly(row.planDate)
      return day >= windowFrom && day <= windowTo && row.status !== 'done' && !targetIds.has(Number(row.id))
    })
    .map(row => Number(row.id))
  if (removeIds.length > 0) {
    await tx.planItem.deleteMany({ where: { id: { in: removeIds } } })
  }

  for (const item of target) {
    const row = item.id > 0 ? existingById.get(item.id) : undefined
    if (!row || row.status === 'done') continue // 新增走 createMany;done 永不动
    const day = dateOnly(row.planDate)
    if (day < windowFrom || day > windowTo) continue
    if (day === item.planDate && Number(row.minutes) === item.minutes
      && String(row.subject) === item.subject && String(row.title) === item.title) continue
    await tx.planItem.updateMany({
      where: { id: item.id, planId, status: 'pending' },
      data: {
        planDate: new Date(`${item.planDate}T00:00:00.000Z`),
        minutes: item.minutes, subject: item.subject, title: item.title,
      },
    })
  }

  const additions = target.filter(item => item.id <= 0 || !existingById.has(item.id))
  if (additions.length > 0) {
    await tx.planItem.createMany({
      data: additions.map(item => ({
        planId,
        stageId: stageIdFor(stages, item.planDate),
        subject: item.subject,
        title: item.title,
        planDate: new Date(`${item.planDate}T00:00:00.000Z`),
        minutes: item.minutes,
        priority: 0,
        sortOrder: 0,
      })),
    })
  }
}

function serializeAdjustment(row: any): PublicAdjustment {
  const before = jsonList<WindowSnapshotItem>(row.beforeJson)
  const after = jsonList<WindowSnapshotItem>(row.afterJson)
  return {
    id: Number(row.id),
    planId: Number(row.planId),
    tier: String(row.tier ?? ''),
    status: String(row.status ?? ''),
    reason: row.reason ?? null,
    summary: row.summary ?? null,
    windowFrom: dateOnly(row.windowFrom),
    windowTo: dateOnly(row.windowTo),
    changes: diffSnapshots(before, after),
    createdAt: ms(row.createdAt),
    appliedAt: ms(row.appliedAt),
  }
}
```

- [ ] **Step 6: 在返回对象里追加四个方法**

插在 `setItemStatus` 方法结束（L617 附近的 `},`）之后、返回对象右花括号之前：

```ts
    /**
     * 行程调整入口:一句话描述突发情况 → 模型解析意图 → 服务端按 L1 算新排法 → 产出待确认调整单。
     * 确认前零副作用(唯一例外:change_commitment 把新固定占用并进档案,那是用户陈述的事实本身)。
     * D1 只有 L1 顺延;溢出直接报缺口不落数据。Task 13 会在这里接入 L2 档位判定。
     */
    async adjust(userGuid: string, input: { message: string }):
      Promise<{ adjustment: PublicAdjustment | null; reply: string; plan: PublicPlan | null }> {
      const message = String(input?.message ?? '').trim()
      if (!message) throw new ApiError(400, 'INVALID_PARAMS', '请说一说发生了什么')
      const planRow = await db.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
      if (!planRow) throw new ApiError(404, 'PLAN_NOT_FOUND', '当前没有生效中的计划')
      if (!ai.adjustIntent || !ai.configured()) {
        throw new ApiError(503, 'AI_NOT_CONFIGURED', '服务端还没有配置大模型,暂时无法理解你的描述')
      }

      const profileRow = await db.userProfile.findUnique({ where: { userGuid } })
      const brief = profileRow ? briefFromRow(profileRow) : normalizeBrief(null)
      const dailyMinutes = Number(profileRow?.dailyMinutes ?? 0)
      const stages = await db.planStage.findMany({ where: { planId: planRow.id }, orderBy: { sortOrder: 'asc' } })
      const today = dayStart(new Date()).toISOString().slice(0, 10)
      const { from, to } = stageWindow(stages, today)

      let intent: AdjustIntent
      try {
        const subjects = [...new Set((await db.planItem.findMany({ where: { planId: planRow.id } }))
          .map(row => String(row.subject)).filter(Boolean))]
        intent = await ai.adjustIntent({ message, today, windowTo: to, subjects, brief })
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error)
        console.warn(`[planning] 调整意图解析失败:${reason}`)
        throw new ApiError(502, 'PLAN_INTERVIEW_FAILED', `这句没听懂:${reason}`)
      }

      // 追问 / 闲聊:只回话,不产调整单
      if (intent.needClarify || intent.kind === 'chat') {
        const reply = intent.needClarify ? (intent.clarifyQuestion || intent.summary) : intent.summary
        return { adjustment: null, reply, plan: null }
      }

      // change_commitment:每周固定占用变了。先把新占用并进档案(并集去重,与课表识别同一口径),
      // 之后的容量计算自然按新占用扣减。
      if (intent.kind === 'change_commitment' && intent.commitments.length > 0 && profileRow) {
        const known = brief.fixedCommitments
        const added = intent.commitments.filter(item => !known.some(existing => existing.weekday === item.weekday
          && existing.start === item.start && existing.end === item.end && existing.label === item.label))
        brief.fixedCommitments = [...known, ...added]
        brief.commitmentsConfirmed = true
        await db.userProfile.update({
          where: { userGuid },
          data: { briefJson: JSON.stringify(brief), updatedAt: new Date() },
        })
      }

      const items = await db.planItem.findMany({ where: { planId: planRow.id } })
      const inWindow = items.filter(row => {
        const day = dateOnly(row.planDate)
        return day >= from && day <= to
      })

      // 每日容量 = min(每日目标, 当天净空闲),再逐项扣掉窗口内已完成的分钟
      const capacity = new Map<string, number>()
      for (const day of datesBetween(from, to)) capacity.set(day, dailyCapacity(brief, dailyMinutes, day))
      for (const row of inWindow) {
        if (row.status === 'done') {
          const day = dateOnly(row.planDate)
          capacity.set(day, Math.max(0, (capacity.get(day) ?? 0) - Number(row.minutes ?? 0)))
        }
      }

      const pending = inWindow.filter(row => row.status !== 'done').map(row => toAdjustable(row))
      const before = toSnapshot(inWindow)

      if (intent.kind === 'unavailable') {
        for (const day of intent.days) {
          if (capacity.has(day)) capacity.set(day, 0)
        }
      } else if (intent.kind === 'reduce_capacity') {
        if (intent.windows.length === 0) {
          // 用户只说「时间变少」没说哪天:按今明后三天各降 30% 估
          for (const day of datesBetween(from, shiftDate(today, 2))) {
            const base = capacity.get(day) ?? 0
            if (base > 0) capacity.set(day, Math.round(base * 0.7))
          }
        } else {
          for (const window of intent.windows) {
            if (!capacity.has(window.day)) continue
            capacity.set(window.day, window.minutes <= 0 ? 0 : Math.min(capacity.get(window.day) ?? 0, window.minutes))
          }
        }
      }

      const l1 = planL1Shuffle({ pending, capacity, windowFrom: from, windowTo: to })
      if (l1.overflow.length > 0) {
        const missing = l1.overflow.reduce((sum, item) => sum + item.minutes, 0)
        throw new ApiError(400, 'PLAN_CAPACITY_INSUFFICIENT',
          `这个阶段到 ${to} 之前排不下,还差约 ${missing} 分钟。要么把休息日让出来,要么等下一阶段再补`)
      }

      const movedById = new Map(l1.moves.map(move => [move.item.id, move.to]))
      const after: WindowSnapshotItem[] = before.map(item => {
        const nextDay = movedById.get(item.id)
        return nextDay && item.status !== 'done' ? { ...item, planDate: nextDay } : item
      })
      const changes = diffSnapshots(before, after)
      if (changes.length === 0) {
        return { adjustment: null, reply: '这个阶段里本来就排得下,计划不用改。', plan: null }
      }

      // 同一用户同一时刻最多一张 draft:已有就原地覆盖,不产生第二张(spec §6)
      const payload = {
        userGuid,
        planId: planRow.id,
        tier: 'L1',
        status: 'draft',
        reason: intent.note || message,
        summary: intent.summary,
        windowFrom: new Date(`${from}T00:00:00.000Z`),
        windowTo: new Date(`${to}T00:00:00.000Z`),
        beforeJson: JSON.stringify(before),
        afterJson: JSON.stringify(after),
        fingerprint: windowFingerprint(before),
      }
      const existingDraft = await db.planAdjustment.findFirst({ where: { userGuid, status: 'draft' } })
      let row: any
      if (existingDraft) {
        await db.planAdjustment.updateMany({ where: { id: existingDraft.id }, data: payload })
        row = await db.planAdjustment.findFirst({ where: { id: existingDraft.id } })
      } else {
        row = await db.planAdjustment.create({ data: payload })
      }
      return { adjustment: serializeAdjustment(row), reply: `已按「${intent.summary}」算好新排法,确认后生效。`, plan: null }
    },

    /** 最近一张待确认/已生效的调整单:App 冷启动/切页恢复卡片用。按事件时间倒序(draft 看 createdAt,applied 看 appliedAt) */
    async latestAdjustment(userGuid: string): Promise<PublicAdjustment | null> {
      const rows = await db.planAdjustment.findMany({ where: { userGuid, status: { in: ['draft', 'applied'] } } })
      const eventTime = (row: any) => ms(row.status === 'applied' ? row.appliedAt : row.createdAt) ?? 0
      const sorted = [...rows].sort((a, b) => eventTime(b) - eventTime(a) || Number(b.id) - Number(a.id))
      return sorted.length > 0 ? serializeAdjustment(sorted[0]) : null
    },

    /** 确认调整单:指纹校验(窗口没被第三方改过)→ 事务内把目标快照写进计划项 → 标记 applied */
    async confirmAdjustment(userGuid: string, adjustmentId: number): Promise<PublicPlan> {
      const appliedPlanId = await db.$transaction(async tx => {
        const row = await tx.planAdjustment.findFirst({ where: { id: adjustmentId, userGuid, status: 'draft' } })
        if (!row) throw new ApiError(404, 'ADJUSTMENT_NOT_FOUND', '调整单不存在,或已经确认/撤销过了')
        const planRow = await tx.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
        if (!planRow || Number(planRow.id) !== Number(row.planId)) {
          throw new ApiError(404, 'PLAN_NOT_FOUND', '这份调整单对应的计划已经不在了')
        }
        const stages = await tx.planStage.findMany({ where: { planId: planRow.id }, orderBy: { sortOrder: 'asc' } })
        const items = await tx.planItem.findMany({ where: { planId: planRow.id } })
        const from = dateOnly(row.windowFrom)
        const to = dateOnly(row.windowTo)
        const inWindow = items.filter(item => {
          const day = dateOnly(item.planDate)
          return day >= from && day <= to
        })
        if (windowFingerprint(toSnapshot(inWindow)) !== String(row.fingerprint)) {
          throw new ApiError(409, 'ADJUSTMENT_STALE', '计划在生成调整单之后又被改过,这张调整单已失效,请重新说一遍')
        }
        await syncWindowItems(tx, planRow.id, from, to, jsonList<WindowSnapshotItem>(row.afterJson), stages)
        await tx.planAdjustment.updateMany({
          where: { id: row.id },
          data: { status: 'applied', appliedAt: new Date() },
        })
        await tx.plan.updateMany({ where: { id: planRow.id }, data: { updatedAt: new Date() } })
        return Number(planRow.id)
      })
      const planRow = await db.plan.findFirst({ where: { id: appliedPlanId } })
      if (!planRow) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划不存在')
      return loadPlan(userGuid, planRow)
    },

    /** 撤销:把 beforeJson 写回去。只允许撤销最近一次 applied,且其后没有新打卡(spec §2 可撤销原则) */
    async undoAdjustment(userGuid: string, adjustmentId: number): Promise<PublicPlan> {
      const rows = await db.planAdjustment.findMany({ where: { userGuid, status: 'applied' } })
      const latestApplied = [...rows].sort((a, b) => Number(b.id) - Number(a.id))[0]
      if (!latestApplied || Number(latestApplied.id) !== Number(adjustmentId)) {
        throw new ApiError(409, 'ADJUSTMENT_STALE', '只能撤销最近一次调整')
      }
      const appliedPlanId = await db.$transaction(async tx => {
        const planRow = await tx.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
        if (!planRow || Number(planRow.id) !== Number(latestApplied.planId)) {
          throw new ApiError(404, 'PLAN_NOT_FOUND', '这份调整单对应的计划已经不在了')
        }
        const appliedAt = ms(latestApplied.appliedAt)
        const items = await tx.planItem.findMany({ where: { planId: planRow.id } })
        const touched = items.some(item => item.status === 'done' && appliedAt != null
          && ms(item.completedAt) != null && ms(item.completedAt)! > appliedAt)
        if (touched) {
          throw new ApiError(409, 'ADJUSTMENT_STALE', '调整生效后你已经打了新的卡,撤销会把记录搞乱;再用一句话描述新的调整即可')
        }
        const stages = await tx.planStage.findMany({ where: { planId: planRow.id }, orderBy: { sortOrder: 'asc' } })
        await syncWindowItems(tx, planRow.id, dateOnly(latestApplied.windowFrom), dateOnly(latestApplied.windowTo),
          jsonList<WindowSnapshotItem>(latestApplied.beforeJson), stages)
        await tx.planAdjustment.updateMany({
          where: { id: latestApplied.id },
          data: { status: 'undone', undoneAt: new Date() },
        })
        await tx.plan.updateMany({ where: { id: planRow.id }, data: { updatedAt: new Date() } })
        return Number(planRow.id)
      })
      const planRow = await db.plan.findFirst({ where: { id: appliedPlanId } })
      if (!planRow) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划不存在')
      return loadPlan(userGuid, planRow)
    },
```

- [ ] **Step 7: 类型检查**

```powershell
npx tsc --noEmit
```

### Task 5: routes.ts 新增五个端点

**Files:**
- Modify: `server/src/modules/planning/routes.ts`

**背景（当前缺陷）:** 调整能力没有 HTTP 入口。

**设计说明（对 spec 的实现细化）:** 全部注册在 `GET /plans/active`（L131）之前、`POST /plans/timetable` 之后——`/plans/checkup`、`/plans/adjust` 是单段静态路径，**必须早于 `GET /plans/:id`（L151）注册**，否则会被参数路由吞掉。confirm/undo 成功后广播 `planChanged`（与 confirmPlan 同款），多端自动更新。

- [ ] **Step 1: 新增请求体 schema 与导入**

`timetableSchema` 之后加：

```ts
/** 行程调整:一句话描述突发情况;长度口径与面谈消息一致 */
const adjustSchema = z.object({
  message: z.string().min(1).max(1000),
})
```

- [ ] **Step 2: 注册路由**

在 `POST /plans/timetable` 路由之后、`GET /plans/active` 之前插入：

```ts
/**
 * 行程调整:一句话描述突发情况,服务端解析意图并产出待确认调整单。
 * 返回 { adjustment, reply, plan }:adjustment 为 null 时 reply 是追问/闲聊回复。
 * 必须注册在 `/plans/:id` 之前(与 interview/timetable 同理)。
 */
router.post('/plans/adjust', requireAuth, apiLimit(), async ctx => {
  const parsed = adjustSchema.safeParse(ctx.request.body)
  if (!parsed.success) throw parsed.error
  const result = await planningService.adjust(ctx.state.auth!.userGuid, { message: parsed.data.message })
  ctx.body = { ...result, serverTime: Date.now() }
})

/** 最近一张待确认/已生效调整单:App 冷启动、切页恢复卡片 */
router.get('/plans/adjustments/latest', requireAuth, async ctx => {
  const adjustment = await planningService.latestAdjustment(ctx.state.auth!.userGuid)
  ctx.body = { adjustment, serverTime: Date.now() }
})

router.post('/plans/adjustments/:id/confirm', requireAuth, apiLimit(), async ctx => {
  const id = Number(ctx.params.id)
  if (!Number.isInteger(id) || id <= 0) throw new ApiError(400, 'INVALID_PARAMS', '调整单 id 不合法')
  const plan = await planningService.confirmAdjustment(ctx.state.auth!.userGuid, id)
  ctx.body = { plan, serverTime: Date.now() }
  notifyPlanChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
})

router.post('/plans/adjustments/:id/undo', requireAuth, apiLimit(), async ctx => {
  const id = Number(ctx.params.id)
  if (!Number.isInteger(id) || id <= 0) throw new ApiError(400, 'INVALID_PARAMS', '调整单 id 不合法')
  const plan = await planningService.undoAdjustment(ctx.state.auth!.userGuid, id)
  ctx.body = { plan, serverTime: Date.now() }
  notifyPlanChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
})
```

### Task 6: service.test.ts —— fake db 扩展 + 集成用例 A-G

**Files:**
- Modify: `server/src/modules/planning/service.test.ts`

**背景（当前缺陷）:** fake db 只有 profiles/plans/stages/items 四张表；调整单链路无法测试。

**设计说明（对 spec 的实现细化）:** `seedActivePlan` 使用**固定 id**（plan=900 / stage=901 / item=1000+i），避免与 `++seq.*` 冲突；stage `startDate/endDate` 可控以造窄窗口；profile 带 `briefJson`（每天 19:00-22:00 = 180 分钟、无固定占用）保证容量可预期。`serviceWithAdjust` 注入固定的 `adjustIntent` 返回值。

- [ ] **Step 1: 扩展 fake db（state/seq/snapshot/restore + planAdjustment 访问器）**

`createFakeDb` 内四处修改：

```ts
  const state = {
    profiles: [] as Row[],
    plans: [] as Row[],
    stages: [] as Row[],
    items: [] as Row[],
    adjustments: [] as Row[],
  }
  let seq = { plan: 0, stage: 0, item: 0, adjustment: 0 }
```

`snapshot()` 加一行、`restore()` 加一行：

```ts
    adjustments: structuredClone(state.adjustments),
```

```ts
    state.adjustments = snap.adjustments
```

`db` 对象里（`planItem` 之后）加访问器：

```ts
    planAdjustment: {
      findFirst: async ({ where }: any = {}) =>
        state.adjustments.filter(r => !where || whereMatch(r, where))[0] ?? null,
      findMany: async ({ where }: any = {}) =>
        state.adjustments.filter(r => !where || whereMatch(r, where)),
      create: async ({ data }: any) => {
        const row = { id: ++seq.adjustment, createdAt: new Date(), ...structuredClone(data) }
        state.adjustments.push(row)
        return row
      },
      updateMany: async ({ where, data }: any) => {
        const hit = state.adjustments.filter(r => whereMatch(r, where))
        for (const row of hit) Object.assign(row, structuredClone(data))
        return { count: hit.length }
      },
    },
```

- [ ] **Step 2: 新增播种与注入助手**

加在 `confirmGeneratedPlan` 函数之后：

```ts
/** 行程测试的 brief:每天 19:00-22:00 空闲(180 分钟)、无固定占用,容量完全可预期 */
function adjustBrief(): PlanBrief {
  const brief = qualityBrief()
  brief.availability = [1, 2, 3, 4, 5, 6, 7].map(weekday => ({
    weekday, windows: [{ start: '19:00', end: '22:00' }],
  }))
  brief.fixedCommitments = []
  return brief
}

const dayOnlyRow = (value: any) => new Date(value).toISOString().slice(0, 10)

/** 播种一份 active plan:plan=900 / stage=901 / item=1000+i,stage 窗口与每日项均可控 */
async function seedActivePlan(
  fake: ReturnType<typeof createFakeDb>, userGuid = USER,
  entries: Array<{ day: number; minutes: number; subject?: string; title?: string; status?: string }>,
  options: { stageFrom?: number; stageTo?: number; brief?: PlanBrief; dailyMinutes?: number } = {},
) {
  fake.state.profiles.push({
    id: 1, userGuid,
    targetType: '考研', examDate: dayIso(120), dailyMinutes: options.dailyMinutes ?? 180,
    studyWindowsJson: '["上午","晚上"]', foundation: '一般', weakSubjectsJson: '[]',
    briefJson: JSON.stringify(options.brief ?? adjustBrief()), updatedAt: new Date(),
  })
  fake.state.plans.push({
    id: 900, userGuid, profileId: 1, title: '测试计划', targetType: '考研',
    source: 'rule', status: 'active', version: 1,
    startDate: new Date(`${dayIso(options.stageFrom ?? 0)}T00:00:00.000Z`),
    examDate: new Date(`${dayIso(120)}T00:00:00.000Z`),
    updatedAt: new Date(),
  })
  fake.state.stages.push({
    id: 901, planId: 900, name: '第一阶段', sortOrder: 0,
    startDate: new Date(`${dayIso(options.stageFrom ?? 0)}T00:00:00.000Z`),
    endDate: new Date(`${dayIso(options.stageTo ?? 6)}T00:00:00.000Z`),
  })
  entries.forEach((entry, index) => {
    fake.state.items.push({
      id: 1000 + index, planId: 900, stageId: 901,
      subject: entry.subject ?? '数学', title: entry.title ?? `任务${1000 + index}`,
      planDate: new Date(`${dayIso(entry.day)}T00:00:00.000Z`),
      minutes: entry.minutes, priority: 0, sortOrder: index,
      status: entry.status ?? 'pending', completedAt: null,
    })
  })
}

/** 注入固定意图返回值的 service;intent 里的字段覆盖默认值 */
function serviceWithAdjust(fake: ReturnType<typeof createFakeDb>, intent: Row) {
  return createPlanningService(fake.db, {
    configured: () => true,
    generate: async input => ({ title: `${input.targetType}备考计划`, plan: generateRulePlan(input) }),
    adjustIntent: async () => ({
      kind: 'unavailable', days: [], windows: [], commitments: [],
      note: '', summary: '临时有事', needClarify: false, clarifyQuestion: '',
      ...intent,
    }),
  })
}
```

- [ ] **Step 3: 追加集成用例 A-G**

文件末尾追加：

```ts
// ---------- 行程调整(D1) ----------

test('adjust A:明天没空 → 当天任务顺延次日;确认前零副作用,确认后计划项被挪动', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [
    { day: 1, minutes: 120 },
    { day: 2, minutes: 60 },
  ])
  const service = serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(1)], summary: '明天没空' })

  const result = await service.adjust(USER, { message: '明天有事,学不了' })
  assert.ok(result.adjustment)
  assert.equal(result.adjustment.status, 'draft')
  assert.equal(result.adjustment.tier, 'L1')
  assert.equal(result.adjustment.changes.length, 1)
  assert.equal(result.adjustment.changes[0].kind, 'moved')
  // 草稿不改变 active plan
  assert.equal(dayOnlyRow(fake.state.items.find(row => row.id === 1000)!.planDate), dayIso(1))

  const plan = await service.confirmAdjustment(USER, result.adjustment.id)
  const moved = plan.items.find(row => row.id === 1000)!
  assert.equal(moved.planDate, dayIso(2))
  // 未受影响的原项保持原日期
  assert.equal(plan.items.find(row => row.id === 1001)!.planDate, dayIso(2))
})

test('adjust B:窗口内塞不下 → PLAN_CAPACITY_INSUFFICIENT 且不落调整单', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [{ day: 1, minutes: 240 }], { stageTo: 1 })
  const service = serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(0), dayIso(1)], summary: '今明都没空' })

  await assert.rejects(
    () => service.adjust(USER, { message: '今明两天都没空' }),
    (error: any) => error.code === 'PLAN_CAPACITY_INSUFFICIENT',
  )
  assert.equal(fake.state.adjustments.length, 0)
})

test('adjust C:已有 draft 时再次调整原地覆盖,不产生第二张', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [{ day: 1, minutes: 120 }, { day: 2, minutes: 60 }])
  const first = await serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(1)], summary: '明天有事' })
    .adjust(USER, { message: '明天有事' })
  const second = await serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(2)], summary: '后天有事' })
    .adjust(USER, { message: '后天也有事' })

  assert.ok(first.adjustment && second.adjustment)
  assert.equal(second.adjustment.id, first.adjustment.id)
  assert.equal(fake.state.adjustments.filter(row => row.status === 'draft').length, 1)
})

test('adjust D:确认前窗口内计划被第三方改过 → ADJUSTMENT_STALE', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [{ day: 1, minutes: 120 }])
  const service = serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(1)], summary: '明天没空' })
  const result = await service.adjust(USER, { message: '明天有事' })
  assert.ok(result.adjustment)

  // 模拟另一台设备在确认前改了窗口内计划项的分钟数
  const row = fake.state.items.find(item => item.id === 1000)!
  row.minutes = 90

  await assert.rejects(
    () => service.confirmAdjustment(USER, result.adjustment.id),
    (error: any) => error.code === 'ADJUSTMENT_STALE',
  )
})

test('adjust E:追问/闲聊只回话不产调整单', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [{ day: 1, minutes: 120 }])
  const service = serviceWithAdjust(fake,
    { kind: 'chat', needClarify: true, clarifyQuestion: '哪天没空?', summary: '' })

  const result = await service.adjust(USER, { message: '有点事' })
  assert.equal(result.adjustment, null)
  assert.equal(result.reply, '哪天没空?')
  assert.equal(fake.state.adjustments.length, 0)
})

test('adjust F:确认后 latest 取到 applied,撤销后窗口内项回到原日期', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [{ day: 1, minutes: 120 }])
  const service = serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(1)], summary: '明天没空' })
  const result = await service.adjust(USER, { message: '明天有事' })
  assert.ok(result.adjustment)
  await service.confirmAdjustment(USER, result.adjustment.id)

  const latest = await service.latestAdjustment(USER)
  assert.ok(latest)
  assert.equal(latest.id, result.adjustment.id)
  assert.equal(latest.status, 'applied')

  const plan = await service.undoAdjustment(USER, result.adjustment.id)
  assert.equal(plan.items.find(row => row.id === 1000)!.planDate, dayIso(1))
  // 撤销后不再有可展示的调整单
  assert.equal(await service.latestAdjustment(USER), null)
})

test('adjust G:调整生效后打了新卡 → 拒绝撤销', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [{ day: 1, minutes: 120 }])
  const service = serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(1)], summary: '明天没空' })
  const result = await service.adjust(USER, { message: '明天有事' })
  assert.ok(result.adjustment)
  await service.confirmAdjustment(USER, result.adjustment.id)

  // 调整生效后完成了一次打卡
  await service.setItemStatus(USER, 1000, 'done')

  await assert.rejects(
    () => service.undoAdjustment(USER, result.adjustment.id),
    (error: any) => error.code === 'ADJUSTMENT_STALE',
  )
})
```

- [ ] **Step 4: 跑全量验证（阶段一完成标志）**

在 `F:\kaoyan-app-prd\server` 下执行：

```powershell
npm run verify
```

（= typecheck + 全部测试 + build；prisma generate 会顺带生成 PlanAdjustment 客户端类型。）

---

## 阶段二：D2 App 调整模式

### Task 7: Api.kt 新增 DTO 与接口方法 + 错误码文案

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewViewModel.kt`（仅 `friendly()`）

**背景（当前缺陷）:** 客户端没有调整单的模型与调用入口。

**设计说明（对 spec 的实现细化）:** confirm/undo 服务端返回 `{plan, serverTime}`，与 `confirmPlan` 同形，直接复用既有 `PlanResp`。DTO 全部 `@Serializable` + 默认值，保持 `ignoreUnknownKeys` 兼容。

- [ ] **Step 1: 在 `TimetableResp`（L413 附近）之后插入 DTO**

```kotlin
// ---------- 行程调整(D2) ----------

/** 调整单里一条变动的字段快照 */
@Serializable
data class AdjustmentFieldDto(
    val planDate: String,
    val minutes: Long = 0,
    val title: String = ""
)

/** 一条变动:moved=挪日期 / added=新增 / removed=删除 / updated=改内容 */
@Serializable
data class AdjustmentChangeDto(
    val kind: String,
    val id: Long = 0,
    val subject: String = "",
    val title: String = "",
    val from: AdjustmentFieldDto? = null,
    val to: AdjustmentFieldDto? = null
)

/** 待确认/已生效的调整单;changes 由服务端从前后快照重算 */
@Serializable
data class AdjustmentDto(
    val id: Long,
    val planId: Long = 0,
    val tier: String = "",
    val status: String = "",
    val reason: String? = null,
    val summary: String? = null,
    val windowFrom: String = "",
    val windowTo: String = "",
    val changes: List<AdjustmentChangeDto> = emptyList(),
    val createdAt: Long? = null,
    val appliedAt: Long? = null
)

@Serializable
data class AdjustReq(val message: String)

@Serializable
data class AdjustResp(
    val adjustment: AdjustmentDto? = null,
    val reply: String? = null,
    val plan: PlanDto? = null,
    val serverTime: Long = 0
)

@Serializable
data class LatestAdjustmentResp(val adjustment: AdjustmentDto? = null, val serverTime: Long = 0)

@Serializable
data class CheckupResp(
    val behindMinutes: Long = 0,
    val overdueCount: Long = 0,
    val suggestion: String = "",
    val serverTime: Long = 0
)
```

- [ ] **Step 2: 在 `patchPlanItem` 之后、`exportAccount` 之前加接口方法**

```kotlin
    /** 行程调整:一句话描述突发情况,服务端产出待确认调整单(adjustment 为 null 时 reply 是追问/闲聊) */
    @POST("api/v1/plans/adjust")
    suspend fun adjustPlan(@Body body: AdjustReq): AdjustResp

    /** 最近一张待确认/已生效的调整单(冷启动恢复卡片用) */
    @GET("api/v1/plans/adjustments/latest")
    suspend fun getLatestAdjustment(): LatestAdjustmentResp

    @POST("api/v1/plans/adjustments/{id}/confirm")
    suspend fun confirmAdjustment(@Path("id") id: Long): PlanResp

    @POST("api/v1/plans/adjustments/{id}/undo")
    suspend fun undoAdjustment(@Path("id") id: Long): PlanResp
```

（`GET /plans/checkup` 的 `planCheckup()` 在 Task 17 一并加，避免本任务出现未使用 DTO。）

- [ ] **Step 3: `ServerError.friendly()` 补两个错误码**

在 `PlanInterviewViewModel.kt` 的 `friendly()` 里 `"PLAN_NOT_FOUND", "PLAN_ITEM_NOT_FOUND"` 行之前插入：

```kotlin
        "ADJUSTMENT_STALE" -> or("计划在生成调整单之后又被改过，这张调整单失效了，重新说一遍即可")
        "ADJUSTMENT_NOT_FOUND" -> or("调整单不存在，可能已经确认或撤销过了")
```

### Task 8: 新建 AdjustmentCard.kt —— L1 变动清单卡片

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AdjustmentCard.kt`

**背景（当前缺陷）:** 没有承载 `summary` + 逐条差异 + 确认/重说操作的卡片（spec §3.8）。

**设计说明（对 spec 的实现细化）:** 「重说一次」只收起卡片——服务端的 draft 会被下一次 adjust 原地覆盖，不需要 reject 端点。L2 时额外提供「查看整页预览」入口（Task 14 接线）。图标按项目规约走 `AppIcons`（Lucide 单一入口）。

- [ ] **Step 1: 写入 AdjustmentCard.kt 全文**

```kotlin
package com.yanzhong.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.remote.AdjustmentChangeDto

/** 单条变动的一行描述,如「挪动 · 数学 · 09-30 → 10-01」 */
internal fun adjustmentChangeLine(change: AdjustmentChangeDto): String {
    val subject = change.subject.ifBlank { "任务" }
    return when (change.kind) {
        "moved" -> {
            val from = change.from?.planDate.orEmpty().takeLast(5)
            val to = change.to?.planDate.orEmpty().takeLast(5)
            "挪动 · $subject · $from → $to"
        }
        "added" -> "新增 · $subject · ${change.to?.title.orEmpty()}"
        "removed" -> "删除 · $subject · ${change.from?.title.orEmpty()}"
        else -> "调整 · $subject · ${change.from?.title.orEmpty()}"
    }
}

/**
 * 行程调整的变动清单卡:确认前零副作用,「确认调整」才生效;
 * 「重说一次」只收起卡片(服务端 draft 会被下一次 adjust 原地覆盖)。
 */
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
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("调整清单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(summary, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            changes.take(8).forEach { change ->
                Text(
                    adjustmentChangeLine(change),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (changes.size > 8) {
                Text("…共 ${changes.size} 条变动,确认后可在计划页查看全部",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onPreview != null) {
                TextButton(onClick = onPreview, modifier = Modifier.align(Alignment.Start)) {
                    Text("查看整页预览")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, enabled = !confirming, modifier = Modifier.weight(1f)) {
                    Text("重说一次")
                }
                Button(onClick = onConfirm, enabled = !confirming && changes.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text(if (confirming) "正在生效…" else "确认调整")
                }
            }
        }
    }
}
```

### Task 9: PlanInterviewViewModel —— BUILD | ADJUST 分流与调整流程

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewViewModel.kt`

**背景（当前缺陷）:** 面谈 ViewModel 只有「制定计划」一条流程；无法复用对话框做行程调整（spec §3.9）。

**设计说明（对 spec 的实现细化）:**
- 模式判定：`profileJustSaved`（刚改完档案）→ BUILD 重新生成；有本地会话存档 → 续上次的模式；都没有 → 有 active plan 即 ADJUST，否则 BUILD。
- ADJUST 不调用面谈推进（`advance`），发消息走 `POST /plans/adjust`；`adjustment == null` 时 reply 就是普通对话气泡。
- 冷启动用 `GET /plans/adjustments/latest` 恢复 draft 卡片；applied 的撤销入口在计划页（Task 11），不在对话里。
- 会话存档新增 `mode` 字段，续聊时模式不漂移。

- [ ] **Step 1: 新增模式枚举与 UiState 字段**

`InterviewPhase` 枚举之后加：

```kotlin
/** 对话模式:BUILD=制定/重新生成计划;ADJUST=已有生效计划,对话即行程小助手 */
enum class InterviewMode { BUILD, ADJUST }
```

`InterviewUiState` 加两个字段（放在 `activeCompared` 之前）：

```kotlin
    val mode: InterviewMode = InterviewMode.BUILD,
    val adjustment: AdjustmentDto? = null,
```

- [ ] **Step 2: 会话存档加 mode**

`InterviewSessionSnapshot` 加字段：

```kotlin
    val mode: InterviewMode = InterviewMode.BUILD
```

`persist()` 的 `session.save(...)` 补 `mode = s.mode`。

- [ ] **Step 3: 重写 beginSession 的模式分流**

`beginSession` 内从 `val saved = ...` 起到方法结束，替换为：

```kotlin
            val saved = if (profileJustSaved) null else session.load()
                ?.takeIf { it.messages.isNotEmpty() && it.accountGuid == accountGuid }
            // 模式分流:刚改完档案 → 重新生成(BUILD);有存档 → 续上次的模式;
            // 都没有 → 已有生效计划就是行程小助手,否则从头制定
            val activePlan = runCatching { ApiClient.api().getActivePlan().plan }.getOrNull()
            val mode = when {
                profileJustSaved -> InterviewMode.BUILD
                saved != null -> saved.mode
                else -> if (activePlan != null) InterviewMode.ADJUST else InterviewMode.BUILD
            }
            _ui.update { it.copy(mode = mode) }
            if (mode == InterviewMode.ADJUST) {
                if (saved != null) {
                    _ui.value = InterviewUiState(phase = InterviewPhase.CHATTING, mode = mode,
                        messages = saved.messages, activePlan = activePlan)
                    // 存档停在「刚发完、AI 还没回」:补发一次
                    if (saved.messages.last().role == "user") adjust(saved.messages.last().content)
                } else {
                    _ui.value = InterviewUiState(phase = InterviewPhase.CHATTING, mode = mode,
                        activePlan = activePlan,
                        messages = listOf(InterviewMessageDto("assistant",
                            "计划正在跑。临时有事、生病、换课表,直接跟我说一句,我帮你把近期计划调好——确认前不会改动现在的安排。")))
                }
                // 恢复还没确认的调整单:冷启动、切页回来不断片
                runCatching { ApiClient.api().getLatestAdjustment() }.getOrNull()?.adjustment
                    ?.takeIf { it.status == "draft" }
                    ?.let { latest -> _ui.update { it.copy(adjustment = latest) } }
                return@launch
            }
            if (saved != null) {
                _ui.value = InterviewUiState(phase = InterviewPhase.CHATTING, mode = mode,
                    messages = saved.messages, options = saved.options, done = saved.done, brief = saved.brief)
                // 存档停在"考生刚发完、AI 还没回"：补问一次，否则续上也没得可点
                if (saved.messages.last().role == "user") advance()
                return@launch
            }
            _ui.update { it.copy(phase = InterviewPhase.CHATTING) }
            advance()
```

- [ ] **Step 4: send 按模式分流 + 新增 adjust/confirmAdjustment/dismissAdjustmentCard**

`send` 的 `viewModelScope.launch { advance() }` 替换为：

```kotlin
        viewModelScope.launch {
            if (_ui.value.mode == InterviewMode.ADJUST) adjust(content) else advance()
        }
```

`confirm()` 方法之后新增三个方法：

```kotlin
    /** 调整模式的一轮:这句话交给 POST /plans/adjust,回来的是追问、闲聊或一张待确认调整单 */
    private suspend fun adjust(message: String) {
        _ui.update { it.copy(sending = true, error = null) }
        runCatching { ApiClient.aiApi().adjustPlan(AdjustReq(message = message)) }.fold({ resp ->
            _ui.update {
                it.copy(messages = it.messages + InterviewMessageDto("assistant", resp.reply.orEmpty()),
                    adjustment = resp.adjustment ?: it.adjustment, sending = false)
            }
            persist()
        }, { e ->
            _ui.update { it.copy(sending = false, error = "这次没算好(${e.userMessage()})，点重试继续或换个说法") }
            persist()
        })
    }

    /** 确认调整单:服务端就地改写计划项并广播 planChanged,计划页自动更新 */
    fun confirmAdjustment() {
        val adjustment = _ui.value.adjustment?.takeIf { it.status == "draft" } ?: return
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(confirming = true, error = null) }
            runCatching { ApiClient.api().confirmAdjustment(adjustment.id).plan }
                .fold({ plan ->
                    _ui.update {
                        it.copy(confirming = false, adjustment = null,
                            phase = InterviewPhase.SUCCESS, plan = plan)
                    }
                    persist()
                }, { e ->
                    _ui.update { it.copy(confirming = false, error = "确认失败(${e.userMessage()})，请重试或重新描述") }
                })
        }
    }

    /** 「重说一次」:只收起卡片;服务端的 draft 会被下一次 adjust 原地覆盖 */
    fun dismissAdjustmentCard() { _ui.update { it.copy(adjustment = null) } }
```

- [ ] **Step 5: retry 兼容 ADJUST 模式**

`retry()` 里 `_ui.value.phase == InterviewPhase.CHATTING -> viewModelScope.launch { advance() }` 替换为：

```kotlin
            _ui.value.phase == InterviewPhase.CHATTING -> viewModelScope.launch {
                if (_ui.value.mode == InterviewMode.ADJUST) {
                    val last = _ui.value.messages.lastOrNull { it.role == "user" }?.content
                    if (last != null) adjust(last) else advance()
                } else {
                    advance()
                }
            }
```

- [ ] **Step 6: 补 import**

```kotlin
import com.yanzhong.app.data.remote.AdjustmentDto
import com.yanzhong.app.data.remote.AdjustReq
```

### Task 10: PlanInterviewScreen —— ADJUST 接入

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewScreen.kt`

**背景（当前缺陷）:** 界面只有 BUILD 形态：三枚事实 chip、副标题与标题都是制定语境；没有调整卡片与 L2 预览入口。

**设计说明（对 spec 的实现细化）:** ADJUST 隐藏三枚事实 chip（spec §3.9）；卡片挂在聊天列表与输入框之间；L2 额外给「查看整页预览」，用本地合成的预览计划（Task 14 的 `buildAdjustmentPreview`）复用 `renderDraftHtml` + `DraftWebView` 渲染整页浮层。预览计划需要在 `beginSession` 时存进 `state.activePlan`（Task 9 已做）。

- [ ] **Step 1: 标题与副标题按模式切换**

标题行（L106）：

```kotlin
                Text(if (fullscreen) "全屏预览 · DRAFT"
                    else if (state.mode == InterviewMode.ADJUST) "AI 行程小助手" else "AI 备考面谈",
                    style = MaterialTheme.typography.titleMedium)
```

`chatterSubtitle` 开头加：

```kotlin
    if (state.mode == InterviewMode.ADJUST) return "说说发生了什么,我帮你重排近期计划"
```

- [ ] **Step 2: ADJUST 隐藏三枚事实 chip**

L122 的 `if (state.phase == InterviewPhase.CHATTING)` 改为：

```kotlin
        if (state.phase == InterviewPhase.CHATTING && state.mode == InterviewMode.BUILD) {
```

- [ ] **Step 3: CHATTING 分支插入 AdjustmentCard 与 L2 预览浮层**

屏幕顶部状态区加局部状态（`fullscreen` 声明附近）：

```kotlin
    var adjustPreview by rememberSaveable { mutableStateOf(false) }
```

返回键处理区加：

```kotlin
    BackHandler(adjustPreview && state.phase == InterviewPhase.CHATTING) { adjustPreview = false }
    LaunchedEffect(state.adjustment) { if (state.adjustment == null) adjustPreview = false }
```

`InterviewPhase.CHATTING -> {` 分支改为 if/else 结构：分支体整体包进 `else`，预览浮层放 `if`：

```kotlin
            InterviewPhase.CHATTING -> {
                if (adjustPreview && state.adjustment?.tier == "L2") {
                    AdjustmentPreviewColumn(
                        state = state,
                        onBack = { adjustPreview = false },
                        onConfirm = vm::confirmAdjustment,
                        onRetry = vm::retry,
                        onDismiss = vm::dismissError,
                        bottom = padding.calculateBottomPadding(),
                    )
                } else {
                    // ↓ 原 CHATTING 分支体原样搬进 else：LazyColumn(消息) → AdjustmentCard → 输入 Surface
                    ……
                }
            }
```

原 `LazyColumn(...)` 结束后、输入 `Surface(...)` 之前插入卡片：

```kotlin
                state.adjustment?.let { adjustment ->
                    AdjustmentCard(
                        summary = adjustment.summary ?: "已按你的情况算好新排法",
                        tier = adjustment.tier,
                        changes = adjustment.changes,
                        confirming = state.confirming,
                        onConfirm = vm::confirmAdjustment,
                        onDismiss = vm::dismissAdjustmentCard,
                        onPreview = if (adjustment.tier == "L2") ({ adjustPreview = true }) else null,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
```

- [ ] **Step 4: 新增 AdjustmentPreviewColumn composable**

文件末尾（`ErrorRow` 之前）加：

```kotlin
/**
 * L2 整页预览浮层:用「active plan + 调整单 changes」本地合成预览计划,
 * 复用 renderDraftHtml(含「本次调整」章节)与 DraftWebView,底部直接确认。
 */
@Composable
private fun AdjustmentPreviewColumn(
    state: InterviewUiState,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    bottom: androidx.compose.ui.unit.Dp,
) {
    val adjustment = state.adjustment ?: return
    val preview = remember(state.activePlan, adjustment) { buildAdjustmentPreview(state.activePlan, adjustment) }
    if (preview == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("当前计划还没读到,无法整页预览", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onBack) { Text("回聊天") }
        }
        return
    }
    val today = LocalDate.now()
    val diff = remember(preview, state.activePlan, today) { compareTodayItems(preview, state.activePlan, today) }
    val html = remember(preview, diff, today, adjustment) {
        renderDraftHtml(preview, diff, today, activeCompared = true, adjustment = adjustment)
    }
    Column(Modifier.fillMaxSize()) {
        DraftWebView(html, Modifier.weight(1f).fillMaxWidth())
        Surface(shadowElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                state.error?.let { ErrorRow(it, onRetry, onDismiss) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("回聊天") }
                    Button(onClick = onConfirm, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Text(if (state.confirming) "正在生效…" else "确认调整")
                    }
                }
                Spacer(Modifier.height(bottom))
            }
        }
    }
}
```

### Task 11: 计划页「撤销上次调整」入口

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanViewModel.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanScreen.kt`

**背景（当前缺陷）:** 调整一旦确认无法反悔（spec §2「可撤销」原则）。

**设计说明（对 spec 的实现细化）:** 入口只在「最近一张调整单为 applied」时显示；撤销走既有 `requestLock` 互斥与 `applyPlanProjection` 投影链路；完成后重拉 latest 刷新入口可见性。图标复用已在 PlanScreen 使用的 `AppIcons.RotateCcw`。

- [ ] **Step 1: PlanViewModel 加状态与方法**

字段区（`_updatingItemId` 之后）加：

```kotlin
    private val _latestAdjustment = MutableStateFlow<AdjustmentDto?>(null)
    val latestAdjustment: StateFlow<AdjustmentDto?> = _latestAdjustment
    private val _undoing = MutableStateFlow(false)
    val undoing: StateFlow<Boolean> = _undoing
```

`refreshPlan()`：未登录分支加 `_latestAdjustment.value = null`；成功应用计划后（`_serverPlan.value = plan` 之后）加：

```kotlin
                    _latestAdjustment.value = runCatching { ApiClient.api().getLatestAdjustment().adjustment }.getOrNull()
```

类末尾（`uiState` 之前）加：

```kotlin
    /** 撤销最近一次已生效的调整:服务端把 beforeJson 写回,planChanged 广播后各端自动更新 */
    fun undoLatestAdjustment() {
        val latest = _latestAdjustment.value?.takeIf { it.status == "applied" } ?: return
        if (_undoing.value) return
        viewModelScope.launch {
            _undoing.value = true
            try {
                requestLock.withLock {
                    val account = TokenStore.currentAccountGuid()
                    if (TokenStore.currentAccess() == null || account.isNullOrBlank()) return@withLock
                    val plan = ApiClient.api().undoAdjustment(latest.id).plan
                        ?: throw IllegalStateException("服务端未返回计划")
                    repo.applyPlanProjection(plan, account)
                    _serverPlan.value = plan
                    _planError.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _planError.value = "撤销失败，请重试；计划未改变"
            } finally {
                _undoing.value = false
                _latestAdjustment.value = runCatching { ApiClient.api().getLatestAdjustment().adjustment }.getOrNull()
            }
        }
    }
```

import 区补：

```kotlin
import com.yanzhong.app.data.remote.AdjustmentDto
```

- [ ] **Step 2: PlanScreen 传参与入口**

`PlanScreen` 里加状态收集：

```kotlin
    val latestAdjustment by vm.latestAdjustment.collectAsStateWithLifecycle()
    val undoing by vm.undoing.collectAsStateWithLifecycle()
```

`ServerPlanSummary(...)` 调用处补三个参数：

```kotlin
                    latestAdjustment = latestAdjustment,
                    undoing = undoing,
                    onUndo = vm::undoLatestAdjustment,
```

`ServerPlanSummary` 签名（`onOpenHistory` 之前）加：

```kotlin
    latestAdjustment: AdjustmentDto?,
    undoing: Boolean,
    onUndo: () -> Unit
```

函数体内、「查看全程规划文档」`OutlinedButton` 块之后加：

```kotlin
        // 撤销最近一次已生效的行程调整(spec §2 可撤销):无新打卡时才可用,服务端会再校验一次
        if (latestAdjustment?.status == "applied") {
            TextButton(onClick = onUndo, enabled = !busy && !undoing, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.RotateCcw, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (undoing) "正在撤销…"
                    else "撤销上次调整${latestAdjustment?.summary?.let { " · $it" }.orEmpty()}")
            }
        }
```

import 区补：

```kotlin
import com.yanzhong.app.data.remote.AdjustmentDto
```

- [ ] **Step 3: 编译验证（阶段二完成标志）**

在 `F:\kaoyan-app-prd\YanZhong` 下执行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

（注意：Android 定向单测在本机无法执行断言——Gradle Test Executor 无法加载 `GradleWorkerMain`；编译通过即为本阶段验证标准，逻辑正确性由服务端用例背书。）

---

## 阶段三：D3 L2 重排 + 整页预览「本次调整」

### Task 12: 新建 adjustL2.ts —— 硬账本 + 模型序列展开 + 校验

**Files:**
- Create: `server/src/modules/planning/adjustL2.ts`
- Create: `server/src/modules/planning/adjustL2.test.ts`

**背景（当前缺陷）:** L1 只能顺延；需求是「合并/拆分/降强度/换科目」的重排，且窗口塞不下时不能只报缺口。

**设计说明（对 spec 的实现细化）:** spec §3.6 说「复用 expandStage/scheduleBacklog」，但那是整份计划从零展开的函数，不带「复用既有项 id」能力；L2 需要保留非 done 项的连续性，故自建 `expandL2`：按 `subject` 池复用 pending 项 id（同科同题优先，其次同科），未被消费的项即被删除——语义等价于 spec 要求的「服务端权威展开」，且支持逐项 id 级 diff 与撤销。模型输出 `dayOffset` 相对序号，**不输出绝对日期**。

- [ ] **Step 1: 写入 adjustL2.ts 全文**

```ts
import { chatComplete, extractJson, LlmError } from '../../shared/llm/client'
import { AiUnavailable } from './aiGenerator'
import { shiftDate, type AdjustableItem, type WindowSnapshotItem } from './adjust'

/**
 * L2 重排:模型只给「第 N 天、哪科、什么任务、多少分钟」的相对序列(不输出绝对日期),
 * 服务端按硬账本展开成窗口快照并校验 —— 与整计划生成同一分工,模型不给日期就不存在日期算歪。
 */

/** 模型输出的一个任务槽位:dayOffset 0 = 窗口第一天 */
export interface L2Task {
  dayOffset: number
  subject: string
  title: string
  minutes: number
}

export interface L2Ledger {
  /** 窗口内待办总分钟(重排必须守恒) */
  totalMinutes: number
  /** dayCapacity[i] = 第 i 天剩余容量(已扣 done) */
  dayCapacity: number[]
  /** 第 i 天的日期 key */
  dayKeys: string[]
  /** 科目白名单:brief 正式科目 ∪ 窗口内既有科目 */
  subjects: string[]
  /** 落在窗口内的里程碑(科目/日期/名称):对应科目的任务不得排到该日期之后 */
  milestones: Array<{ subject: string; date: string; name: string }>
}

export interface L2Input {
  message: string
  windowFrom: string
  windowTo: string
  ledger: L2Ledger
  /** 窗口内现有待办(展开时按它复用 id) */
  pending: AdjustableItem[]
}

const L2_MAX_TOKENS = 1600

const L2_SYSTEM_PROMPT = [
  '你是考研备考计划的重排引擎。给你一个调整窗口:每天的可用分钟数(账本)、窗口内现有的任务清单(科目/标题/分钟)、科目白名单和里程碑。',
  '请输出一份重排方案,只输出 JSON:{"tasks":[{"dayOffset":数字,"subject":"科目","title":"标题","minutes":数字}]}。',
  '规则:',
  '- dayOffset 从 0 开始,0 表示窗口第一天;所有任务必须落在窗口内。',
  '- subject 必须来自科目白名单;title 尽量沿用原任务标题,可以合并拆分,但每条不超过 30 字。',
  '- 全部任务的 minutes 总和必须等于窗口内现有任务的总分钟数(总量守恒,不凭空加减)。',
  '- 每天的 minutes 总和不得超过那天的可用分钟数。',
  '- 里程碑日期之前要排紧凑,不能把该科任务排到里程碑日期之后。',
  '- 可用分钟为 0 的日子(用户没空)不要排任何任务。',
].join('\n')

export async function runAdjustL2(input: L2Input): Promise<L2Task[]> {
  try {
    const raw = await chatComplete({
      messages: [
        { role: 'system', content: L2_SYSTEM_PROMPT },
        { role: 'user', content: buildL2UserPrompt(input) },
      ],
      json: true,
      temperature: 0.3,
      maxTokens: L2_MAX_TOKENS,
      timeoutMs: 45_000,
    })
    const parsed = extractJson<{ tasks?: unknown }>(raw)
    return Array.isArray(parsed?.tasks) ? parsed.tasks as L2Task[] : []
  } catch (error) {
    if (error instanceof LlmError) throw new AiUnavailable(error.message)
    throw error
  }
}

function buildL2UserPrompt(input: L2Input): string {
  const lines: string[] = []
  lines.push(`调整窗口:${input.windowFrom} 到 ${input.windowTo}`)
  lines.push('每天可用分钟:')
  input.ledger.dayKeys.forEach((day, index) =>
    lines.push(`- dayOffset ${index} = ${day},可用 ${input.ledger.dayCapacity[index]} 分钟`))
  lines.push('窗口内现有任务:')
  for (const item of input.pending) {
    lines.push(`- ${item.subject} | ${item.title} | ${item.minutes} 分钟 | 现在排在 ${item.planDate}`)
  }
  if (input.ledger.milestones.length > 0) {
    lines.push('窗口内的里程碑:')
    for (const milestone of input.ledger.milestones) {
      lines.push(`- ${milestone.subject}:${milestone.name} @ ${milestone.date}`)
    }
  }
  lines.push(`科目白名单:${input.ledger.subjects.join('、')}`)
  lines.push(`用户的情况:${input.message}`)
  return lines.join('\n')
}

/** 服务端先算的硬账本:窗口内待办总量、每日容量、科目白名单与窗口内里程碑 */
export function buildLedger(
  pending: AdjustableItem[],
  capacity: Map<string, number>,
  days: string[],
  subjects: string[],
  milestones: Array<{ subject: string; date: string; name: string }>,
): L2Ledger {
  const first = days[0]
  const last = days[days.length - 1] ?? ''
  return {
    totalMinutes: pending.reduce((sum, item) => sum + item.minutes, 0),
    dayCapacity: days.map(day => capacity.get(day) ?? 0),
    dayKeys: [...days],
    subjects: [...new Set([...subjects, ...pending.map(item => item.subject)])],
    milestones: milestones.filter(m => first !== undefined && m.date >= first && m.date <= last),
  }
}

/** 逐条清洗模型输出:dayOffset 落窗口内、科目在白名单、分钟为正;非法条目直接丢弃 */
export function normalizeL2Tasks(raw: unknown, ledger: L2Ledger): L2Task[] {
  if (!Array.isArray(raw)) return []
  const tasks: L2Task[] = []
  for (const entry of raw as any[]) {
    const dayOffset = Math.round(Number(entry?.dayOffset))
    const subject = typeof entry?.subject === 'string' ? entry.subject.trim() : ''
    const title = typeof entry?.title === 'string' ? entry.title.trim().slice(0, 64) : ''
    const minutes = Math.round(Number(entry?.minutes))
    if (!Number.isInteger(dayOffset) || dayOffset < 0 || dayOffset >= ledger.dayKeys.length) continue
    if (!ledger.subjects.includes(subject)) continue
    if (!title) continue
    if (!Number.isFinite(minutes) || minutes <= 0) continue
    tasks.push({ dayOffset, subject, title, minutes })
  }
  return tasks
}

/**
 * 把模型的任务序列展开成窗口快照,尽量复用既有 pending 项:
 * 同科同题 → 原项改日期/分钟;同科不同题 → 原项改标题/分钟;找不到同科 → 新增(id=0)。
 * 未被消费的 pending 项即被删除(由 diffSnapshots 呈现为 removed)。分钟是否合规交给 validateL2。
 */
export function expandL2(tasks: L2Task[], windowFrom: string, pending: AdjustableItem[]): WindowSnapshotItem[] {
  const pool = new Map<string, AdjustableItem[]>()
  for (const item of pending) {
    const list = pool.get(item.subject) ?? []
    list.push(item)
    pool.set(item.subject, list)
  }
  const usedIds = new Set<number>()
  const target: WindowSnapshotItem[] = []
  for (const task of [...tasks].sort((a, b) => a.dayOffset - b.dayOffset)) {
    const day = shiftDate(windowFrom, task.dayOffset)
    const list = pool.get(task.subject) ?? []
    const match = list.find(item => !usedIds.has(item.id) && item.title === task.title)
      ?? list.find(item => !usedIds.has(item.id))
    if (match != null) {
      usedIds.add(match.id)
      target.push({ id: match.id, subject: task.subject, title: task.title, planDate: day, minutes: task.minutes, status: 'pending' })
    } else {
      target.push({ id: 0, subject: task.subject, title: task.title, planDate: day, minutes: task.minutes, status: 'pending' })
    }
  }
  return target.sort((a, b) => a.planDate.localeCompare(b.planDate) || a.id - b.id)
}

/** 三条硬规则:总量守恒、每日不超容量、里程碑不推迟。返回第一个失败原因,便于回喂重试 */
export function validateL2(target: WindowSnapshotItem[], ledger: L2Ledger): { ok: boolean; reason?: string } {
  const total = target.reduce((sum, item) => sum + item.minutes, 0)
  if (total !== ledger.totalMinutes) {
    return { ok: false, reason: `重排后总分钟 ${total} 与原来的 ${ledger.totalMinutes} 不一致,总量必须守恒` }
  }
  const perDay = new Map<string, number>()
  for (const item of target) {
    perDay.set(item.planDate, (perDay.get(item.planDate) ?? 0) + item.minutes)
  }
  for (const [index, day] of ledger.dayKeys.entries()) {
    const planned = perDay.get(day) ?? 0
    if (planned > ledger.dayCapacity[index]) {
      return { ok: false, reason: `${day} 安排 ${planned} 分钟,超出当天可用的 ${ledger.dayCapacity[index]} 分钟` }
    }
  }
  for (const milestone of ledger.milestones) {
    const late = target.find(item => item.subject === milestone.subject && item.planDate > milestone.date)
    if (late) {
      return { ok: false, reason: `科目「${milestone.subject}」的里程碑「${milestone.name}」在 ${milestone.date},任务不能排到它之后` }
    }
  }
  return { ok: true }
}
```

（`expandL2` 不做容量判断——分钟是否合规统一归 `validateL2`。）

- [ ] **Step 2: 写入 adjustL2.test.ts 全文**

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { buildLedger, expandL2, normalizeL2Tasks, validateL2, type L2Task } from './adjustL2'
import type { AdjustableItem, WindowSnapshotItem } from './adjust'

const DAYS = ['2026-09-29', '2026-09-30', '2026-10-01']
const CAPACITY = new Map([['2026-09-29', 180], ['2026-09-30', 0], ['2026-10-01', 180]])
const PENDING: AdjustableItem[] = [
  { id: 1, subject: '数学', title: '线性代数', planDate: '2026-09-29', minutes: 120, priority: 0, sortOrder: 0 },
  { id: 2, subject: '英语', title: '阅读真题', planDate: '2026-09-30', minutes: 60, priority: 0, sortOrder: 1 },
]
const SUBJECTS = ['数学', '英语', '政治']

test('buildLedger:总量/每日容量/白名单/里程碑裁剪', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS,
    [{ subject: '英语', date: '2026-10-01', name: '阅读一轮完成' },
     { subject: '英语', date: '2026-09-01', name: '窗口外会被裁掉' }])
  assert.equal(ledger.totalMinutes, 180)
  assert.deepEqual(ledger.dayCapacity, [180, 0, 180])
  assert.deepEqual(ledger.subjects, ['数学', '英语', '政治'])
  assert.deepEqual(ledger.milestones, [{ subject: '英语', date: '2026-10-01', name: '阅读一轮完成' }])
})

test('normalizeL2Tasks:越界/白名单外/非法分钟一律丢弃', () => {
  const raw = [
    { dayOffset: 0, subject: '数学', title: '线性代数', minutes: 120 },
    { dayOffset: 3, subject: '数学', title: '越界', minutes: 30 },
    { dayOffset: 0, subject: '体育', title: '白名单外', minutes: 30 },
    { dayOffset: 1, subject: '英语', title: '负数', minutes: -10 },
  ]
  const tasks = normalizeL2Tasks(raw, buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS, []))
  assert.deepEqual(tasks, [{ dayOffset: 0, subject: '数学', title: '线性代数', minutes: 120 }])
})

test('expandL2:同科同题复用原 id,日期按 dayOffset 展开', () => {
  const tasks: L2Task[] = [
    { dayOffset: 2, subject: '数学', title: '线性代数', minutes: 120 },
    { dayOffset: 2, subject: '英语', title: '阅读真题', minutes: 60 },
  ]
  const target = expandL2(tasks, '2026-09-29', PENDING)
  assert.deepEqual(target.map(item => item.id), [1, 2])
  assert.deepEqual(target.map(item => item.planDate), ['2026-10-01', '2026-10-01'])
})

test('expandL2:同科不同题也复用;无匹配新增 id=0', () => {
  const tasks: L2Task[] = [
    { dayOffset: 0, subject: '数学', title: '换一批题', minutes: 120 },
    { dayOffset: 2, subject: '政治', title: '马原', minutes: 60 },
  ]
  const target = expandL2(tasks, '2026-09-29', PENDING)
  assert.deepEqual(target.map(item => item.id), [1, 0])
  const added = target.find(item => item.id === 0)!
  assert.equal(added.subject, '政治')
  assert.equal(added.planDate, '2026-10-01')
})

function snapOf(id: number, day: string, minutes: number, subject = '数学'): WindowSnapshotItem {
  return { id, subject, title: `任务${id}`, planDate: day, minutes, status: 'pending' }
}

test('validateL2:总量守恒被破坏 → 不通过', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS, [])
  const broken = [snapOf(1, '2026-09-29', 60), snapOf(2, '2026-10-01', 60)]
  assert.equal(validateL2(broken, ledger).ok, false)
})

test('validateL2:排进容量为 0 的日子 → 不通过', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS, [])
  const over = [snapOf(1, '2026-09-30', 120), snapOf(2, '2026-10-01', 60)]
  assert.equal(validateL2(over, ledger).ok, false)
})

test('validateL2:里程碑不推迟;当日或之前允许', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS,
    [{ subject: '英语', date: '2026-09-29', name: '单词一轮' }])
  const late = [snapOf(1, '2026-09-29', 120), snapOf(2, '2026-10-01', 60, '英语')]
  assert.equal(validateL2(late, ledger).ok, false)
  const onTime = [snapOf(1, '2026-09-29', 120), snapOf(2, '2026-09-29', 60, '英语')]
  assert.equal(validateL2(onTime, ledger).ok, true)
})
```

- [ ] **Step 3: 运行单测**

```powershell
node --import tsx --test src/modules/planning/adjustL2.test.ts
```

### Task 13: service adjust 接入档位判定与 L2 路径

**Files:**
- Modify: `server/src/modules/planning/service.ts`
- Modify: `server/src/modules/planning/service.test.ts`

**背景（当前缺陷）:** D1 的 adjust 溢出即报错，受影响天数也不设上限；spec §3.2 要求「不满足 L1 条件即升级 L2」。

**设计说明（对 spec 的实现细化）:** L2 的模型调用经 `PlanningAi.adjustL2` 注入（与 adjustIntent 同款），否则服务层测试无法覆盖 L2。L2 的 `after` 必须包含窗口内 done 项（否则 diff 会把它们误判为 removed）。预检 `totalPending > totalCapacity` 直接 400，不浪费模型调用；校验失败回喂重试一次，仍失败 502 `PLAN_GENERATION_FAILED` 且不落库（事务外计算，天然不落）。

- [ ] **Step 1: imports 与 PlanningAi 扩展**

Task 4 Step 1 的 adjust 导入改为：

```ts
import {
  affectedDays, dailyCapacity, datesBetween, diffSnapshots, planL1Shuffle, shiftDate,
  windowFingerprint, L1_MAX_AFFECTED_DAYS,
  type AdjustmentTier, type AdjustableItem, type WindowSnapshotItem,
} from './adjust'
import { buildLedger, expandL2, normalizeL2Tasks, runAdjustL2, validateL2, type L2Input, type L2Task } from './adjustL2'
```

`PlanningAi` 加可选方法：

```ts
  adjustL2?: (input: L2Input) => Promise<L2Task[]>
```

默认参数补 `adjustL2: runAdjustL2`。

- [ ] **Step 2: 新增 runAdjustL2Plan 私有函数**

放在 `syncWindowItems`/`serializeAdjustment` 附近（模块级）：

```ts
/**
 * L2 重排:模型给相对序列 → 服务端展开校验。校验失败把原因回喂重试一次,仍失败抛
 * PLAN_GENERATION_FAILED(502)。整个过程在事务外、落库前,失败天然不产生任何数据。
 */
async function runAdjustL2Plan(ai: PlanningAi, args: {
  message: string
  pending: AdjustableItem[]
  capacity: Map<string, number>
  from: string
  to: string
  brief: PlanBrief
}): Promise<WindowSnapshotItem[]> {
  if (!ai.adjustL2) throw new ApiError(503, 'AI_NOT_CONFIGURED', '服务端还没有配置大模型,暂时无法重排计划')
  const days = datesBetween(args.from, args.to)
  if (days.length === 0) throw new ApiError(400, 'PLAN_CAPACITY_INSUFFICIENT', '调整窗口为空,没有可重排的日期')
  const milestones: Array<{ subject: string; date: string; name: string }> = []
  for (const subject of args.brief.examSubjects) {
    if (subject.milestone && subject.milestoneDate) {
      milestones.push({ subject: subject.name, date: subject.milestoneDate, name: subject.milestone })
    }
  }
  const ledger = buildLedger(
    args.pending, args.capacity, days,
    args.brief.examSubjects.map(subject => subject.name),
    milestones,
  )
  const run = (message: string) => ai.adjustL2!({
    message, windowFrom: args.from, windowTo: args.to, ledger, pending: args.pending,
  }).then(tasks => normalizeL2Tasks(tasks, ledger))

  let tasks = await run(args.message)
  let target = expandL2(tasks, args.from, args.pending)
  let verdict = validateL2(target, ledger)
  if (!verdict.ok) {
    // 回喂一次:把失败原因告诉模型,多数情况一次就能收敛
    tasks = await run(`${args.message}\n(上一次重排没通过:${verdict.reason};请修正后重新输出完整方案)`)
    target = expandL2(tasks, args.from, args.pending)
    verdict = validateL2(target, ledger)
  }
  if (!verdict.ok) {
    throw new ApiError(502, 'PLAN_GENERATION_FAILED', `重排方案没有通过校验:${verdict.reason}`)
  }
  return target
}
```

- [ ] **Step 3: 替换 adjust() 的 L1 定档段**

把 Task 4 Step 6 中从 `const l1 = planL1Shuffle(...)` 到 `const changes = diffSnapshots(before, after)` 的整段替换为：

```ts
      const l1 = planL1Shuffle({ pending, capacity, windowFrom: from, windowTo: to })
      // 档位判定(服务端硬规则,spec §3.2):塞得下且影响天数 ≤ 阈值 → L1 顺延;否则 L2 重排
      const tier: AdjustmentTier = l1.overflow.length === 0 && affectedDays(l1.moves) <= L1_MAX_AFFECTED_DAYS
        ? 'L1'
        : 'L2'

      let after: WindowSnapshotItem[]
      if (tier === 'L1') {
        const movedById = new Map(l1.moves.map(move => [move.item.id, move.to]))
        after = before.map(item => {
          const nextDay = movedById.get(item.id)
          return nextDay && item.status !== 'done' ? { ...item, planDate: nextDay } : item
        })
      } else {
        // L2 预检:窗口总量塞不下直接报缺口,不浪费一次模型调用
        const totalPending = pending.reduce((sum, item) => sum + item.minutes, 0)
        const totalCapacity = [...capacity.values()].reduce((sum, value) => sum + value, 0)
        if (totalPending > totalCapacity) {
          throw new ApiError(400, 'PLAN_CAPACITY_INSUFFICIENT',
            `这个阶段到 ${to} 之前只剩 ${totalCapacity} 分钟,排不下 ${totalPending} 分钟的任务。要么把休息日让出来,要么等下一阶段再补`)
        }
        const target = await runAdjustL2Plan(ai, { message, pending, capacity, from, to, brief })
        // L2 的 after 必须带上窗口内的 done 项,否则 diff 会把打卡项误判成 removed
        after = [...before.filter(item => item.status === 'done'), ...target]
      }
      const changes = diffSnapshots(before, after)
```

并把 payload 里的 `tier: 'L1',` 改为 `tier,`。

- [ ] **Step 4: 追加 L2 服务层用例**

`service.test.ts` 文件末尾追加：

```ts
// ---------- 行程调整(D3):档位升级与 L2 重排 ----------

test('adjust L2-1:影响天数超阈值 → 升级 L2,模型序列由服务端展开并复用原项', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [
    { day: 0, minutes: 60 }, { day: 1, minutes: 60 }, { day: 2, minutes: 60 }, { day: 3, minutes: 60 },
  ])
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async input => ({ title: `${input.targetType}备考计划`, plan: generateRulePlan(input) }),
    adjustIntent: async () => ({
      kind: 'unavailable', days: [dayIso(0), dayIso(1), dayIso(2)], windows: [], commitments: [],
      note: '前三天都没空', summary: '前三天没空', needClarify: false, clarifyQuestion: '',
    }),
    adjustL2: async () => [
      { dayOffset: 3, subject: '数学', title: '任务1000', minutes: 60 },
      { dayOffset: 3, subject: '数学', title: '任务1001', minutes: 60 },
      { dayOffset: 4, subject: '数学', title: '任务1002', minutes: 60 },
      { dayOffset: 4, subject: '数学', title: '任务1003', minutes: 60 },
    ],
  })

  const result = await service.adjust(USER, { message: '前三天都有事' })
  assert.ok(result.adjustment)
  assert.equal(result.adjustment.tier, 'L2')
  const plan = await service.confirmAdjustment(USER, result.adjustment.id)
  const dates = plan.items.filter(item => item.id >= 1000 && item.id <= 1003)
    .map(item => item.planDate).sort()
  assert.deepEqual(dates, [dayIso(3), dayIso(3), dayIso(4), dayIso(4)])
})

test('adjust L2-2:校验两次不过 → PLAN_GENERATION_FAILED 且窗口内任务原封不动', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [
    { day: 0, minutes: 60 }, { day: 1, minutes: 60 }, { day: 2, minutes: 60 }, { day: 3, minutes: 60 },
  ])
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async input => ({ title: `${input.targetType}备考计划`, plan: generateRulePlan(input) }),
    adjustIntent: async () => ({
      kind: 'unavailable', days: [dayIso(0), dayIso(1), dayIso(2)], windows: [], commitments: [],
      note: '', summary: '前三天没空', needClarify: false, clarifyQuestion: '',
    }),
    // 240 分钟排进 180 分钟的一天 → 每日容量校验两次失败
    adjustL2: async () => [{ dayOffset: 3, subject: '数学', title: 'x', minutes: 240 }],
  })

  await assert.rejects(
    () => service.adjust(USER, { message: '前三天都有事' }),
    (error: any) => error.code === 'PLAN_GENERATION_FAILED',
  )
  assert.equal(fake.state.adjustments.length, 0)
  assert.equal(fake.state.items.filter(item => item.status === 'pending').length, 4)
})
```

- [ ] **Step 5: 跑全量验证（阶段三服务端部分完成标志）**

```powershell
npm run verify
```

### Task 14: PlanDraftPreview「本次调整」章节 + 合成预览计划

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanDraftPreview.kt`

**背景（当前缺陷）:** L2 走整页 HTML 预览（spec §3.8），但模板没有「本次调整」章节，也没有把调整单应用到当前计划的方法。

**设计说明（对 spec 的实现细化）:** `renderDraftHtml` 加可空 `adjustment` 参数（默认 null，既有调用零改动）；章节插在「今日任务变化」与「阶段安排」之间；复用既有 `.change`/`.note` 样式。`buildAdjustmentPreview` 在本地把 changes 应用到 active plan，供 L2 浮层渲染「确认后会长什么样」。

- [ ] **Step 1: renderDraftHtml 签名与章节**

签名改为：

```kotlin
internal fun renderDraftHtml(draft: PlanDto, diff: TodayItemDiff, today: LocalDate,
    activeCompared: Boolean = true, adjustment: AdjustmentDto? = null): String = buildString {
```

L73 的 `append("</section><section><h2>阶段安排</h2>")` 拆开，在两个 section 之间插入：

```kotlin
    append("</section>")
    adjustment?.let { adj ->
        append("<section><h2>本次调整</h2>")
        append("<p class='note'>")
        text(adj.summary ?: "")
        append("</p>")
        if (adj.changes.isEmpty()) {
            append("<p class='muted'>没有可展示的变动明细</p>")
        } else {
            adj.changes.forEach { change ->
                append("<div class='change'>")
                text(adjustmentChangeLine(change))
                append("</div>")
            }
        }
        append("</section>")
    }
    append("<section><h2>阶段安排</h2>")
```

import 区补：

```kotlin
import com.yanzhong.app.data.remote.AdjustmentDto
```

（`adjustmentChangeLine` 与本文件同包，无需 import。）

- [ ] **Step 2: 新增 buildAdjustmentPreview**

文件末尾追加：

```kotlin
/**
 * 用 active plan + 调整单 changes 本地拼出「确认后会长什么样」的预览计划:
 * L2 整页预览没有服务端新计划可拉,就把 changes 应用到当前计划上。
 */
internal fun buildAdjustmentPreview(active: PlanDto?, adjustment: AdjustmentDto?): PlanDto? {
    if (active == null || adjustment == null) return null
    val items = active.items.toMutableList()
    for (change in adjustment.changes) {
        val to = change.to
        when (change.kind) {
            "removed" -> items.removeAll { it.id == change.id }
            "moved", "updated" -> {
                val index = items.indexOfFirst { it.id == change.id }
                if (index >= 0 && to != null) {
                    items[index] = items[index].copy(
                        planDate = to.planDate, minutes = to.minutes.toInt(), title = to.title,
                    )
                }
            }
            "added" -> if (to != null) {
                items += PlanItemDto(
                    id = change.id, subject = change.subject, title = to.title,
                    planDate = to.planDate, minutes = to.minutes.toInt(),
                )
            }
        }
    }
    return active.copy(items = items)
}
```

- [ ] **Step 3: 编译验证（阶段三完成标志）**

在 `F:\kaoyan-app-prd\YanZhong` 下执行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

---

## 阶段四：D4 主动体检与提醒

### Task 15: checkup 体检 + GET /plans/checkup

**Files:**
- Modify: `server/src/modules/planning/service.ts`
- Modify: `server/src/modules/planning/service.test.ts`
- Modify: `server/src/modules/planning/routes.ts`

**背景（当前缺陷）:** 服务端不知道用户是否落后/积压，更谈不上主动提醒（spec §3.10）。

**设计说明（对 spec 的实现细化）:** `checkup` 是纯计算：统计 `planDate < today` 且未完成的项；无 active plan 返回全零 + 空建议（App 端以此判断「不用提醒」）。文案函数 `overdueSuggestion` 独立导出便于断言。

- [ ] **Step 1: service 加 checkup 与文案函数**

返回对象里（`undoAdjustment` 之后）加：

```ts
    /** 主动体检:数一数落在过去还没完成的任务,给出一句话建议(纯计算,无副作用) */
    async checkup(userGuid: string): Promise<{ behindMinutes: number; overdueCount: number; suggestion: string }> {
      const planRow = await db.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
      if (!planRow) return { behindMinutes: 0, overdueCount: 0, suggestion: '' }
      const items = await db.planItem.findMany({ where: { planId: planRow.id } })
      const today = dayStart(new Date()).toISOString().slice(0, 10)
      const overdue = items.filter(item => item.status !== 'done' && dateOnly(item.planDate) < today)
      const behindMinutes = overdue.reduce((sum, item) => sum + Number(item.minutes ?? 0), 0)
      return { behindMinutes, overdueCount: overdue.length, suggestion: overdueSuggestion(overdue.length, behindMinutes) }
    },
```

模块级（`export type PlanningService` 之前）加：

```ts
/** 主动提醒文案:没有落后任务返回空串(App 以空串判断「不用提醒」) */
export function overdueSuggestion(count: number, minutes: number): string {
  if (count <= 0) return ''
  const part = minutes > 0 ? `、约 ${minutes} 分钟` : ''
  return `有 ${count} 项${part}的任务落在过去还没完成,打开 AI 行程小助手说一句,马上帮你重新排。`
}
```

- [ ] **Step 2: routes 加 GET /plans/checkup**

`GET /plans/adjustments/latest` 之后加（`routes.ts` 顶部 import 区补 `import { maybeNotifyPlanReminder } from './planReminder'`——本步骤先写调用与导入，`planReminder.ts` 由 Task 16 落地；若想中途保持 typecheck 通过，也可把该行与导入留到 Task 16 一起加）：

```ts
/** 计划体检:落后分钟数/过期任务数/一句话建议。App 周期轮询;服务端顺手触发在线提醒( fire-and-forget ) */
router.get('/plans/checkup', requireAuth, async ctx => {
  const checkup = await planningService.checkup(ctx.state.auth!.userGuid)
  void maybeNotifyPlanReminder(ctx.state.auth!.userGuid)
  ctx.body = { ...checkup, serverTime: Date.now() }
})
```

（`maybeNotifyPlanReminder` 在 Task 16 落地；本步骤先写调用，Task 16 完成后一起编译。也可先注释掉该行，Task 16 再放开——二选一，保持 typecheck 通过即可。）

- [ ] **Step 3: 测试**

`service.test.ts` 末尾追加：

```ts
// ---------- 行程体检(D4) ----------

test('checkup:数出过去未完成的任务;没有计划返回全零', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [
    { day: -1, minutes: 60 },
    { day: 0, minutes: 30, status: 'done' },
    { day: 2, minutes: 45 },
  ])
  const checkup = await serviceWith(fake).checkup(USER)
  assert.equal(checkup.overdueCount, 1)
  assert.equal(checkup.behindMinutes, 60)
  assert.ok(checkup.suggestion.includes('1 项'))

  const none = await serviceWith(createFakeDb()).checkup('other-guid')
  assert.deepEqual(none, { behindMinutes: 0, overdueCount: 0, suggestion: '' })

  assert.equal(overdueSuggestion(0, 0), '')
})
```

import 行补 `overdueSuggestion`（从 `./service`）。执行：

```powershell
node --import tsx --test src/modules/planning/service.test.ts
```

### Task 16: planReminder.ts —— 在线推送 + 每天一条防打扰

**Files:**
- Create: `server/src/modules/planning/planReminder.ts`

**背景（当前缺陷）:** 提醒没有可插拔的通知出口，也没有防打扰去重。

**设计说明（对 spec 的实现细化）:** 在线通道 = `hub.sendToUser`（`{ type: 'planReminder', text }`）；厂商推送只留 `PlanNotifier` 接口。去重复用 `user_settings` 表（key=`plan_reminder_day`，值=日期字符串；与 sync 模块同款 upsert，`updatedAt` 为 BigInt 列传 `Date.now()` 数值，与 `sync/service.ts` 的 `now()` 惯例一致）。该 key 不在客户端 12 个设置同步键内，对设置同步无影响。任何失败只打日志，不影响主请求。

- [ ] **Step 1: 写入 planReminder.ts 全文**

```ts
import { prisma } from '../../shared/prisma'
import { hub } from '../../shared/ws/Hub'
import { planningService } from './service'

/**
 * 计划主动提醒(D4):体检命中(有落后/积压)且今天还没提醒过 → 推一条。
 * 每人每天最多 1 条(防打扰);任何失败只打日志,绝不影响触发它的主请求。
 */

/** 提醒去重键(user_settings):value = 最近一次发送的 YYYY-MM-DD */
const REMINDER_DAY_KEY = 'plan_reminder_day'

/** 可插拔通知出口:在线通道 = WebSocket;厂商推送只留接口,本阶段不实现(spec §9) */
export interface PlanNotifier {
  notify(userGuid: string, text: string): void
}

export const wsPlanNotifier: PlanNotifier = {
  notify(userGuid, text) {
    hub.sendToUser(userGuid, { type: 'planReminder', serverTime: Date.now(), text })
  },
}

export async function maybeNotifyPlanReminder(userGuid: string, notifier: PlanNotifier = wsPlanNotifier): Promise<void> {
  try {
    const checkup = await planningService.checkup(userGuid)
    if (!checkup.suggestion) return
    const today = new Date().toISOString().slice(0, 10)
    const row = await prisma.userSetting.upsert({
      where: { userGuid_key: { userGuid, key: REMINDER_DAY_KEY } },
      create: { userGuid, key: REMINDER_DAY_KEY, value: '', updatedAt: Date.now() },
      update: {},
    })
    if (String(row.value) === today) return // 今天已经提醒过
    await prisma.userSetting.updateMany({
      where: { userGuid, key: REMINDER_DAY_KEY },
      data: { value: today, updatedAt: Date.now() },
    })
    notifier.notify(userGuid, checkup.suggestion)
  } catch (error) {
    console.warn(`[planning] 计划提醒失败:${error instanceof Error ? error.message : String(error)}`)
  }
}
```

- [ ] **Step 2: 确认 Task 15 路由里的调用放开并编译**

```powershell
npm run verify
```

### Task 17: Android —— WS 提醒 + 周期轮询本地通知

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/StatusSyncClient.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/DataSyncer.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/service/FocusService.kt`

**背景（当前缺陷）:** 服务端的 `planReminder` 推送与 `/plans/checkup` 没有客户端出口；提醒无法变成用户可见的通知。

**设计说明（对 spec 的实现细化）:** 两条通道共用 `DataSyncer` 里「每天最多一条」的本地去重（`claimDailyReminder`），避免 WS + 轮询双重弹通知：WS 收到 `planReminder` → 抢当天名额 → 发本地通知；15 分钟周期循环里 `syncOnce()` 后调 `pullCheckupOnce()`（服务端 checkup 也会 WS 推，谁先到谁发）。**对 spec §3.10 字面的实现取舍：项目无 WorkManager 依赖，轮询复用既有 15 分钟周期循环**（等价效果、零新依赖）。本地通知复用 `CHANNEL_EVENT` 但不抢屏（无全屏 intent、`PRIORITY_DEFAULT`、不发声），通知 id 用 `EVENT_ID + 1` 避免顶掉番茄钟事件通知。

- [ ] **Step 1: StatusSyncClient 加 PlanReminder**

`SyncNotice` sealed interface 里（`PlanChanged` 之后）加：

```kotlin
    /** 服务端主动提醒:落后/积压(服务端已做每人每天 1 条去重) */
    data class PlanReminder(val text: String) : SyncNotice
```

`handleEvent` 的 `"planChanged"` 分支之后加：

```kotlin
            "planReminder" -> {
                val text = obj["text"]?.jsonPrimitive?.content ?: return
                _notices.tryEmit(SyncNotice.PlanReminder(text))
            }
```

- [ ] **Step 2: Api.kt 加 checkup 接口**

`undoAdjustment` 之后加：

```kotlin
    /** 计划体检:落后分钟数/过期任务数/一句话建议(suggestion 为空表示不用提醒) */
    @GET("api/v1/plans/checkup")
    suspend fun planCheckup(): CheckupResp
```

- [ ] **Step 3: FocusService 加 postPlanReminder**

companion object 内（`postEvent` 之后、`cancelOngoing` 之前）加：

```kotlin
        /**
         * 计划主动提醒(体检命中):与 postEvent 同通道但不抢屏 ——
         * 无全屏 intent、普通优先级、不发声,安静地在通知栏放一句。
         */
        fun postPlanReminder(context: Context, text: String) {
            if (text.isBlank()) return
            ensureChannels(context)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val notification = NotificationCompat.Builder(context, CHANNEL_EVENT)
                .setSmallIcon(R.drawable.ic_stat_pomodoro)
                .setContentTitle("研钟计划")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            nm.notify(EVENT_ID + 1, notification)
        }
```

- [ ] **Step 4: DataSyncer 接入两条通道**

字段/companion：`WATERMARK_ACCOUNT_KEY` 之后加：

```kotlin
        private const val PLAN_REMINDER_DAY_KEY = "plan_reminder_day"
```

import 区补：

```kotlin
import com.yanzhong.app.service.FocusService
import java.time.LocalDate
```

`start()` 的 jobs 列表：①15 分钟周期循环改为——

```kotlin
            scope.launch {
                while (true) {
                    delay(15 * 60_000L)
                    if (started) {
                        syncOnce()
                        pullCheckupOnce()
                    }
                }
            }
```

②新增 WS 提醒落地协程（`PlanChanged` 协程之后）：

```kotlin
            scope.launch {
                app.statusSync.notices.filterIsInstance<SyncNotice.PlanReminder>()
                    .collect { notice ->
                        if (started && notice.text.isNotBlank() && claimDailyReminder()) {
                            FocusService.postPlanReminder(app, notice.text)
                        }
                    }
            }
```

类内新增两个方法（`pullPlanOnce` 之后）：

```kotlin
    /** 每天最多一条主动提醒:返回 true 表示抢到当天的名额(WS 与轮询两条通道共用,防双重弹通知) */
    private fun claimDailyReminder(): Boolean {
        val today = LocalDate.now().toString()
        if (prefs.getString(PLAN_REMINDER_DAY_KEY, null) == today) return false
        prefs.edit().putString(PLAN_REMINDER_DAY_KEY, today).apply()
        return true
    }

    /** 计划体检轮询:命中且当天没提醒过 → 本地通知(spec §3.10 本地通道;无 WorkManager,复用 15 分钟周期循环) */
    private suspend fun pullCheckupOnce() {
        if (!started || TokenStore.currentAccess() == null) return
        val checkup = runCatching { ApiClient.api().planCheckup() }.getOrNull() ?: return
        if (checkup.suggestion.isBlank() || !claimDailyReminder()) return
        FocusService.postPlanReminder(app, checkup.suggestion)
    }
```

- [ ] **Step 5: 全量验证（阶段四 & 整个计划完成标志）**

服务端（在 `F:\kaoyan-app-prd\server` 下）：

```powershell
npm run verify
```

Android（在 `F:\kaoyan-app-prd\YanZhong` 下）：

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

---

## 验收对照（spec §10 完成定义）

1. 确认前 App 展示与 active plan 完全不变 → 用例 A「草稿不改变 active plan」+ Task 9 draft 不触发 `planChanged`。
2. 确认后自动更新 → confirm/undo 路由广播 `planChanged`，既有 `applyPlanProjection` 链路自动落本地。
3. 已打卡项永不丢失 → `syncWindowItems` 对 done 项只跳过不删改；指纹排除 done；用例 F/G 覆盖。
4. 阶段末之后的项不动 → 窗口过滤 `[today, 当前阶段末]`。
5. 塞不下明确报缺口 → 用例 B（L1 溢出 400）+ Task 13 L2 预检 400。
6. L1 不改科目与分钟 → `planL1Shuffle` 只改 planDate；用例 A 断言。
7. 最近一次调整可撤销 → 用例 F；有新打卡拒绝 → 用例 G。
8. `npm run verify` 与 Android 编译通过 → 各 Task 验证步骤 + 阶段四 Step 5。
