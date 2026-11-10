# 近期弹性计划调整 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 AI 行程调整从「30 天整案重排」改造成「5 天弹性局部调整」：只动被事件波及的任务，按重要性缩/挪，确认后只改相关日期。

**Architecture:** 新增确定性纯函数模块 `elasticAdjust.ts`（定级、待动项、局部放置、缩短/后挤/延窗）；模型只在确定性引擎溢出或冲突时输出受限增量，由服务端校验。`service.adjust()` 与 `confirmAdjustment()` 复用现有快照、指纹、撤销机制，只收紧窗口与落库范围。

**Tech Stack:** Node.js + TypeScript + Koa + Prisma（服务端）、Kotlin/Compose（客户端）、node:test（单测）。

**Spec:** `docs/superpowers/specs/2026-11-10-near-term-elastic-adjustment-design.md`

## Global Constraints

- 默认窗口 5 天（今天+4）；用户明确范围时按总天数夹到 `[5, 14]`；只有默认窗口允许自动后延最多 3 天（总长 ≤8）。
- 可让任务最多缩到原时长 50%，普通任务最多缩 25%，单条不低于 15 分钟；按 5 分钟取整。
- 固定项（done/locked）不得改；保护项不得缩短，只有自己所在日被事件波及才允许改日期。
- 确认只改写调整单窗口内的计划项；不升 `Plan.version`、不重新生成计划/文档。
- `adjust_scope` 保持现有逻辑与 30 天窗口。
- 回滚开关 `PLAN_ADJUST_ENGINE=legacy|elastic`，默认 `elastic`。
- **不执行 git commit / push / 部署**（用户尚未批准）；每个任务结束只跑验证。
- 测试命令（服务端）：`cd server && npm run verify`；
  单文件：`cd server && node --import tsx --test src/modules/planning/<file>.test.ts`。
- 客户端测试/构建用 PowerShell：
  `powershell.exe -NoProfile -Command "cd 'F:\kaoyan-app-prd\YanZhong'; .\gradlew.bat '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' :app:testDebugUnitTest :app:assembleDebug"`

---

### Task 1: 配置开关、窗口参数化与意图字段

**Files:**
- Modify: `server/src/config/env.ts`（`env` 顶层加 `planAdjustEngine`）
- Modify: `server/.env.example`
- Modify: `server/src/modules/planning/adjust.ts:182-198`（`adjustWindow`）
- Modify: `server/src/modules/planning/adjustIntent.ts:15-77, 135-178, 217-404`
- Test: `server/src/modules/planning/adjust.test.ts`、`server/src/modules/planning/adjustIntent.test.ts`

**Interfaces:**
- Produces:
  - `adjustWindow(input, options?: { horizonDays?: number; maxDays?: number }): { from: string; to: string } | null`
  - `DEFAULT_ADJUST_DAYS = 5`、`MAX_ADJUST_DAYS = 14`、`AUTO_EXTEND_DAYS = 3`
  - `AdjustIntent.importance?: AdjustImportanceHint[]`、`AdjustIntent.horizonDays?: number`
  - `env.planAdjustEngine: 'elastic' | 'legacy'`

- [ ] **Step 1: 写窗口与意图的失败测试**

在 `adjust.test.ts` 末尾追加：

```ts
test('adjustWindow: 默认 5 天;计划调整最多 14 天;scope 可显式放宽到 30 天', () => {
  const base = { planStart: '2026-09-01', examDate: '2027-06-01', today: '2026-11-03', unfinishedDates: [] }
  assert.equal(adjustWindow(base)?.to, '2026-11-07') // 今天+4
  assert.equal(adjustWindow(base, { horizonDays: 10 })?.to, '2026-11-12') // 今天+9
  assert.equal(adjustWindow(base, { horizonDays: 30 })?.to, '2026-11-16') // 夹到 14 天
  assert.equal(adjustWindow(base, { horizonDays: 1 })?.to, '2026-11-07') // 夹到 5 天
  assert.equal(adjustWindow(base, { horizonDays: 30, maxDays: 30 })?.to, '2026-12-02') // scope 保留 30 天
})
```

在 `adjustIntent.test.ts` 末尾追加：

```ts
test('importance: 科目白名单、窗口内日期、级别清洗,最多 8 条', () => {
  const intent = normalizeAdjustIntent({
    kind: 'unavailable',
    days: ['2026-09-30'],
    importance: [
      { subject: '数学', level: 'protect' },
      { subject: '数学', title: '真题', date: '2026-09-30', level: 'elastic' },
      { subject: '物理', level: 'protect' },        // 不在白名单
      { subject: '英语', level: 'bad' },            // 非法级别
      { subject: '英语', date: '2026-10-20', level: 'elastic' }, // 窗口外
      ...Array.from({ length: 10 }, () => ({ subject: '数学', level: 'protect' })),
    ],
  }, input)
  assert.equal(intent.importance?.length, 8)
  assert.deepEqual(intent.importance?.slice(0, 2), [
    { subject: '数学', level: 'protect' },
    { subject: '数学', title: '真题', date: '2026-09-30', level: 'elastic' },
  ])
})

test('horizonDays: 只有给出合法值才保留,并夹到 5..14', () => {
  assert.equal(normalizeAdjustIntent({ kind: 'reschedule' }, input).horizonDays, undefined)
  assert.equal(normalizeAdjustIntent({ kind: 'reschedule', horizonDays: 8 }, input).horizonDays, 8)
  assert.equal(normalizeAdjustIntent({ kind: 'reschedule', horizonDays: 1 }, input).horizonDays, 5)
  assert.equal(normalizeAdjustIntent({ kind: 'reschedule', horizonDays: 99 }, input).horizonDays, 14)
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/adjust.test.ts src/modules/planning/adjustIntent.test.ts`
Expected: FAIL（`adjustWindow` 仍返回 30 天；`importance`/`horizonDays` 为 `undefined`）。

- [ ] **Step 3: 实现窗口与开关**

`adjust.ts`：

```ts
export const DEFAULT_ADJUST_DAYS = 5
export const MAX_ADJUST_DAYS = 14
export const AUTO_EXTEND_DAYS = 3

export function adjustWindow(input: {
  planStart: string
  examDate: string
  today: string
  unfinishedDates: string[]
}, options: { horizonDays?: number; maxDays?: number } = {}): { from: string; to: string } | null {
  const examLast = shiftDate(input.examDate, -1)
  const maxDays = options.maxDays ?? MAX_ADJUST_DAYS
  const days = Math.min(maxDays, Math.max(DEFAULT_ADJUST_DAYS, options.horizonDays ?? DEFAULT_ADJUST_DAYS))
  const futureLast = shiftDate(input.today, days - 1)
  const to = examLast < futureLast ? examLast : futureLast
  if (to < input.today) return null
  const overdue = input.unfinishedDates
    .filter(day => day >= shiftDate(input.today, -30) && day < input.today)
    .sort()[0]
  const start = overdue ?? input.today
  const from = start > input.planStart ? start : input.planStart
  return from <= to ? { from, to } : null
}
```

`env.ts` 顶层：

```ts
planAdjustEngine: process.env.PLAN_ADJUST_ENGINE === 'legacy' ? 'legacy' as const : 'elastic' as const,
```

`.env.example` 追加：

```ini
# 行程调整引擎:elastic(默认,近期弹性调整)| legacy(旧版整窗重排,仅回滚用)
PLAN_ADJUST_ENGINE=elastic
```

- [ ] **Step 4: 实现意图字段**

`adjustIntent.ts` 增加：

```ts
export interface AdjustImportanceHint {
  subject: string
  title?: string
  date?: string
  level: 'protect' | 'elastic'
}

export interface AdjustIntent {
  // ...现有字段
  /** 可选:旧测试/旧客户端不带该字段时按空数组处理 */
  importance?: AdjustImportanceHint[]
  horizonDays?: number
}
```

`normalizeAdjustIntent` 里清洗：

```ts
const importance: AdjustImportanceHint[] = []
const allowedSubjects = new Set(input.subjects)
if (Array.isArray(raw?.importance)) {
  for (const entry of raw.importance) {
    if (importance.length >= 8) break
    const subject = text(entry?.subject, 32)
    const level = entry?.level === 'protect' || entry?.level === 'elastic' ? entry.level : null
    const title = text(entry?.title, 64) || undefined
    const date = text(entry?.date, 10)
    if (!subject || !level) continue
    if (allowedSubjects.size > 0 && !allowedSubjects.has(subject)) continue
    if (date && (!DAY_PATTERN.test(date) || !inWindow(date))) continue
    importance.push({ subject, level, ...(title ? { title } : {}), ...(date ? { date } : {}) })
  }
}
const horizonRaw = Math.round(Number(raw?.horizonDays))
const horizonDays = Number.isFinite(horizonRaw) && horizonRaw > 0
  ? Math.max(DEFAULT_ADJUST_DAYS, Math.min(MAX_ADJUST_DAYS, horizonRaw))
  : undefined
```

返回值追加 `importance, ...(horizonDays ? { horizonDays } : {})`。

提示词追加（`buildSystemPrompt` 规则段）：

```ts
'- importance: 仅当用户明确说「哪科/哪项重要、别动」或「可以少点、先放一放」时输出,每项 {subject, title?, date?, level}',
'  level 只能是 "protect"(重要,不许缩/不许因别的事挪) 或 "elastic"(可让,可以缩/后移);没有明确说法时不要输出该字段。',
'- horizonDays: 仅当用户明确说「这周/到周末/未来 N 天」时输出含今天在内的总天数(5..14);没说就省略。',
'- reschedule 默认只处理今天之前未完成和今天已超载的任务;只有用户明确要求「重新安排这周/未来 N 天」时才把该范围内非保护项一起重排。',
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd server && node --import tsx --test src/modules/planning/adjust.test.ts src/modules/planning/adjustIntent.test.ts`
Expected: PASS（旧 `adjustWindow` 测试改为默认 5 天后同步更新）。

---

### Task 2: 重要性定级

**Files:**
- Create: `server/src/modules/planning/elasticAdjust.ts`
- Test: `server/src/modules/planning/elasticAdjust.test.ts`

**Interfaces:**
- Consumes: `WindowSnapshotItem`（`adjust.ts`）、`AdjustImportanceHint`（`adjustIntent.ts`）
- Produces:
  - `type Tier = 'fixed' | 'protect' | 'normal' | 'elastic'`
  - `rankImportance(items: WindowSnapshotItem[], input: { hints: AdjustImportanceHint[]; weakSubjects: string[]; milestones: Map<string, string>; windowTo: string }): Map<number, Tier>`

- [ ] **Step 1: 写失败测试**

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { rankImportance } from './elasticAdjust'
import type { WindowSnapshotItem } from './adjust'

const item = (id: number, patch: Partial<WindowSnapshotItem> = {}): WindowSnapshotItem => ({
  id, subject: '数学', title: `任务${id}`, planDate: '2026-11-10', minutes: 60,
  status: 'pending', locked: false, ...patch,
})

test('rankImportance: done/locked 固定,弱科与窗口内里程碑默认保护', () => {
  const tiers = rankImportance([
    item(1, { status: 'done' }),
    item(2, { locked: true }),
    item(3, { subject: '英语' }),
    item(4, { subject: '政治' }),
    item(5, { subject: '专业课' }),
  ], {
    hints: [], weakSubjects: ['英语'],
    milestones: new Map([['政治', '2026-11-12'], ['专业课', '2026-12-01']]),
    windowTo: '2026-11-14',
  })
  assert.equal(tiers.get(1), 'fixed')
  assert.equal(tiers.get(2), 'fixed')
  assert.equal(tiers.get(3), 'protect')
  assert.equal(tiers.get(4), 'protect')   // 里程碑在窗口内
  assert.equal(tiers.get(5), 'normal')    // 里程碑在窗口外
})

test('rankImportance: 用户明确可让覆盖默认保护,明确保护优先于明确可让', () => {
  const tiers = rankImportance([item(1, { subject: '英语' }), item(2, { subject: '数学' })], {
    hints: [
      { subject: '英语', level: 'elastic' },
      { subject: '数学', level: 'elastic' },
      { subject: '数学', level: 'protect' },
    ],
    weakSubjects: ['英语'], milestones: new Map(), windowTo: '2026-11-14',
  })
  assert.equal(tiers.get(1), 'elastic')
  assert.equal(tiers.get(2), 'protect')
})

test('rankImportance: 标题与日期限定只命中对应任务', () => {
  const tiers = rankImportance([
    item(1, { subject: '数学', title: '真题套卷', planDate: '2026-11-10' }),
    item(2, { subject: '数学', title: '错题整理', planDate: '2026-11-10' }),
  ], {
    hints: [{ subject: '数学', title: '错题', date: '2026-11-10', level: 'elastic' }],
    weakSubjects: [], milestones: new Map(), windowTo: '2026-11-14',
  })
  assert.equal(tiers.get(1), 'normal')
  assert.equal(tiers.get(2), 'elastic')
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/elasticAdjust.test.ts`
Expected: FAIL（模块/函数不存在）。

- [ ] **Step 3: 实现**

```ts
import type { AdjustImportanceHint } from './adjustIntent'
import type { WindowSnapshotItem } from './adjust'

export type Tier = 'fixed' | 'protect' | 'normal' | 'elastic'

export function rankImportance(
  items: WindowSnapshotItem[],
  input: { hints: AdjustImportanceHint[]; weakSubjects: string[]; milestones: Map<string, string>; windowTo: string },
): Map<number, Tier> {
  const explicit = new Map<number, 'protect' | 'elastic'>()
  for (const hint of input.hints) {
    for (const item of items) {
      if (item.subject !== hint.subject) continue
      if (hint.title && !item.title.includes(hint.title)) continue
      if (hint.date && item.planDate !== hint.date) continue
      const prev = explicit.get(item.id)
      if (hint.level === 'protect' || prev !== 'protect') explicit.set(item.id, hint.level)
    }
  }
  const weak = new Set(input.weakSubjects)
  const tiers = new Map<number, Tier>()
  for (const item of items) {
    if (item.status === 'done' || item.locked === true) { tiers.set(item.id, 'fixed'); continue }
    const hint = explicit.get(item.id)
    if (hint) { tiers.set(item.id, hint); continue }
    const milestone = input.milestones.get(item.subject)
    if (weak.has(item.subject) || (milestone != null && milestone <= input.windowTo)) {
      tiers.set(item.id, 'protect'); continue
    }
    tiers.set(item.id, 'normal')
  }
  return tiers
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && node --import tsx --test src/modules/planning/elasticAdjust.test.ts`
Expected: PASS。

---

### Task 3: 弹性放置、缩短与后挤

**Files:**
- Modify: `server/src/modules/planning/elasticAdjust.ts`
- Test: `server/src/modules/planning/elasticAdjust.test.ts`

**Interfaces:**
- Produces:
  - `interface ElasticDemand { id: number; earliest: string; targetDate?: string; reason: 'unavailable' | 'capacity' | 'overdue' | 'explicit' | 'added' }`
  - `interface ElasticInput { before: WindowSnapshotItem[]; tiers: Map<number, Tier>; demands: ElasticDemand[]; capacity: (day: string) => number; today: string; windowTo: string; hardTo: string; autoExtend: boolean; milestones: Map<string, string> }`
  - `interface ElasticResult { after: WindowSnapshotItem[]; touchedDays: string[]; overflow: WindowSnapshotItem[]; shrinkMinutes: number; extendedTo: string; notes: Map<number, string> }`
  - `planElasticAdjustment(input: ElasticInput): ElasticResult`
  - `shrinkDayByTier(before: WindowSnapshotItem[], day: string, capacity: number, tiers: Map<number, Tier>): WindowSnapshotItem[] | null`

- [ ] **Step 1: 写失败测试**

```ts
const mk = (id: number, day: string, minutes = 60, patch: Partial<WindowSnapshotItem> = {}) =>
  item(id, { planDate: day, minutes, ...patch })
const capacityOf = (map: Record<string, number>) => (day: string) => map[day] ?? 0

test('弹性:没空日的任务后移,未受影响日期与任务完全不变', () => {
  const before = [mk(1, '2026-11-10'), mk(2, '2026-11-11'), mk(3, '2026-11-12')]
  const tiers = new Map([[1, 'normal'], [2, 'normal'], [3, 'normal']])
  const result = planElasticAdjustment({
    before, tiers, demands: [{ id: 1, earliest: '2026-11-10', reason: 'unavailable' }],
    capacity: capacityOf({ '2026-11-10': 0, '2026-11-11': 60, '2026-11-12': 60, '2026-11-13': 60, '2026-11-14': 60 }),
    today: '2026-11-10', windowTo: '2026-11-14', hardTo: '2026-11-14', autoExtend: true, milestones: new Map(),
  })
  assert.equal(result.after.find(i => i.id === 1)!.planDate, '2026-11-11')
  assert.deepEqual(result.after.find(i => i.id === 2), before[1])
  assert.deepEqual(result.after.find(i => i.id === 3), before[2])
  assert.deepEqual(result.touchedDays, ['2026-11-11'])
})

test('弹性:先缩可让任务给保护任务腾位置', () => {
  const before = [mk(1, '2026-11-10'), mk(2, '2026-11-11', 120)]
  const tiers = new Map([[1, 'protect'], [2, 'elastic']])
  const result = planElasticAdjustment({
    before, tiers, demands: [{ id: 1, earliest: '2026-11-11', reason: 'unavailable' }],
    capacity: capacityOf({ '2026-11-10': 0, '2026-11-11': 120, '2026-11-12': 120, '2026-11-13': 120, '2026-11-14': 120 }),
    today: '2026-11-10', windowTo: '2026-11-14', hardTo: '2026-11-14', autoExtend: true, milestones: new Map(),
  })
  assert.equal(result.after.find(i => i.id === 2)!.minutes, 60) // 可让 120 → 60(50%)
  assert.equal(result.after.find(i => i.id === 1)!.planDate, '2026-11-11')
  assert.ok(result.shrinkMinutes >= 60)
})

test('弹性:可让任务后挤一天给保护任务让位', () => {
  const before = [mk(1, '2026-11-10', 60), mk(2, '2026-11-11', 60)]
  const tiers = new Map([[1, 'protect'], [2, 'elastic']])
  const result = planElasticAdjustment({
    before, tiers, demands: [{ id: 1, earliest: '2026-11-11', reason: 'unavailable' }],
    capacity: capacityOf({ '2026-11-10': 0, '2026-11-11': 60, '2026-11-12': 60, '2026-11-13': 60, '2026-11-14': 60 }),
    today: '2026-11-10', windowTo: '2026-11-14', hardTo: '2026-11-14', autoExtend: true, milestones: new Map(),
  })
  assert.equal(result.after.find(i => i.id === 2)!.planDate, '2026-11-12')
  assert.equal(result.after.find(i => i.id === 1)!.planDate, '2026-11-11')
})

test('弹性:默认窗口放不下自动延窗,用户指定上限时不延', () => {
  const before = [mk(1, '2026-11-10', 60)]
  const tiers = new Map([[1, 'normal']])
  const capacity = capacityOf({ '2026-11-10': 0, '2026-11-11': 0, '2026-11-12': 0, '2026-11-13': 0, '2026-11-14': 0, '2026-11-15': 60 })
  const extended = planElasticAdjustment({
    before, tiers, demands: [{ id: 1, earliest: '2026-11-10', reason: 'unavailable' }],
    capacity, today: '2026-11-10', windowTo: '2026-11-14', hardTo: '2026-11-15', autoExtend: true, milestones: new Map(),
  })
  assert.equal(extended.overflow.length, 0)
  assert.equal(extended.after.find(i => i.id === 1)!.planDate, '2026-11-15')
  const fixed = planElasticAdjustment({
    before, tiers, demands: [{ id: 1, earliest: '2026-11-10', reason: 'unavailable' }],
    capacity, today: '2026-11-10', windowTo: '2026-11-14', hardTo: '2026-11-15', autoExtend: false, milestones: new Map(),
  })
  assert.equal(fixed.overflow.length, 1)
})

test('弹性:幂等,且未受影响的保护任务不被动', () => {
  const before = [mk(1, '2026-11-10'), mk(2, '2026-11-12')]
  const tiers = new Map([[1, 'normal'], [2, 'protect']])
  const input = {
    before, tiers, demands: [{ id: 1, earliest: '2026-11-10', reason: 'unavailable' as const }],
    capacity: capacityOf({ '2026-11-10': 0, '2026-11-11': 60, '2026-11-12': 60 }),
    today: '2026-11-10', windowTo: '2026-11-12', hardTo: '2026-11-12', autoExtend: true, milestones: new Map<string, string>(),
  }
  const first = planElasticAdjustment(input)
  const second = planElasticAdjustment({ ...input, before: first.after })
  assert.deepEqual(second.after, first.after)
  assert.deepEqual(first.after.find(i => i.id === 2), before[1])
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/elasticAdjust.test.ts`
Expected: FAIL（`planElasticAdjustment` 未定义）。

- [ ] **Step 3: 实现核心算法**

实现要点（完整代码写在 `elasticAdjust.ts`）：
- `datesBetween` 复用 `adjust.ts`。
- `dayLoads` 按所有项累计；demand 项先从初始负载里剔除。
- `shrinkAllowance(item, tier)`：elastic `floor = max(15, round5(minutes * 0.5))`，normal `floor = max(15, round5(minutes * 0.75))`，protect/fixed 为 0。
- `tryPlace(item, days, state, depth)`：
  1. 找到第一个 `free >= minutes` 的日子；
  2. 否则对每天算 `othersShrink`（当天 elastic/normal 可缩空间），够则按「elastic 大者优先、再 normal」缩并放置；
  3. 否则对 demand 自身：非 protect 且缩后能放进某天，则缩自身并放置（这是「normal 240 → 180」的出口）；
  4. 否则对候选日做一次后挤：挑当天 tier 最低、分钟最大的非固定项，递归 `tryPlace(..., depth-1)` 到后一天；成功则腾出空间重试；
  5. `depth` 上限 3，visited `(id, day)` 防循环。
- 主循环：demand 按 `protect → normal → elastic`、`earliest`、`id` 排序；每轮从 `windowTo` 开始；剩余 demand 无法放置且 `autoExtend && windowTo < hardTo && windowTo < 初始windowTo+3` 时 `windowTo+1` 重跑；否则进 `overflow`。
- 输出 `notes`：`'缩短 60→45 分钟（可让）'`、`'顺延到 2026-11-11'`、`'为数学腾 30 分钟'`。
- `touchedDays` 对比 before/after 的 `planDate`、`minutes`。

`shrinkDayByTier`：只处理指定日期的非固定项，按 elastic→normal 缩到容量内，返回新数组或 null。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && node --import tsx --test src/modules/planning/elasticAdjust.test.ts`
Expected: PASS。

---

### Task 4: 服务层接入与窗口收紧

**Files:**
- Modify: `server/src/modules/planning/service.ts:1891-2246`
- Modify: `server/src/modules/planning/service.test.ts:1360-1550`
- Test: 同上

**Interfaces:**
- Consumes: `planElasticAdjustment`、`rankImportance`、`shrinkDayByTier`、`adjustWindow`、`env.planAdjustEngine`

- [ ] **Step 1: 写失败/更新测试**

在 `service.test.ts` 调整段新增：

```ts
test('adjust 弹性:只影响被波及的两天,远处任务不动,保护项不缩', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [
    { day: 1, minutes: 60, subject: '数学', title: '真题' },
    { day: 1, minutes: 60, subject: '英语', title: '整理' },
    { day: 4, minutes: 60, subject: '数学', title: '远期' },
  ], { brief: qualityBrief() })
  const service = serviceWithAdjust(fake, {
    kind: 'unavailable', days: [dayIso(1)], summary: '明天没空',
    importance: [{ subject: '数学', level: 'protect' }, { subject: '英语', level: 'elastic' }],
  })
  const result = await service.adjust(USER, { message: '明天有事,数学很重要,英语可以少点' })
  assert.ok(result.adjustment)
  const touched = new Set(result.adjustment.changes.flatMap(change =>
    [change.from?.planDate, change.to?.planDate].filter(Boolean)))
  assert.ok(touched.size <= 3)
  assert.ok(!touched.has(dayIso(4)))
  const math = result.adjustment.changes.find(change => change.subject === '数学')
  assert.equal(math?.from?.minutes, math?.to?.minutes) // 保护不缩
  assert.equal(fake.state.items.find(row => row.id === 1002)!.minutes, 60)
})

test('adjust 弹性:重排只拉落后,未来可行任务不动', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [
    { day: -1, minutes: 60, title: '落后' },
    { day: 3, minutes: 60, title: '未来可行' },
  ], { stageFrom: -5 })
  const service = serviceWithAdjust(fake, { kind: 'reschedule', summary: '把落后的排一下' })
  const result = await service.adjust(USER, { message: '有任务落后了' })
  assert.ok(result.adjustment)
  assert.deepEqual(result.adjustment.changes.map(change => change.title), ['落后'])
})
```

同步修改旧测试：
- `adjust B`：240 分钟普通任务在新引擎下会缩到 180 并后移（或按容量选择），断言改为 `kind: 'updated'`/`moved`、分钟 180，不再断言删除。
- `adjust L2-1`：大量任务仍会溢出，方案卡可能变化；按新引擎输出更新断言（以实际运行为准，但必须断言「只影响 ≤8 天」）。
- `adjust reschedule`：原有两条落后项仍可移到今天；断言保持不变。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/service.test.ts`
Expected: FAIL（新用例失败；旧用例因行为变化失败）。

- [ ] **Step 3: 重构 adjust() 窗口**

在 `adjust()` 开头（`planRow` 之后）：

```ts
const context = adjustWindow({ planStart, examDate, today, unfinishedDates }, { horizonDays: MAX_ADJUST_DAYS })
if (!context) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划的调整窗口已结束')
const { from: contextFrom, to: contextTo } = context
const contextItems = allItems.filter(row => {
  const day = dateOnly(row.planDate)
  return day >= contextFrom && day <= contextTo
})
```

意图解析继续用 `windowTo: contextTo`、`windowItems: contextItems`。

解析后：

```ts
const scopeIntent = intent.kind === 'adjust_scope'
const window = scopeIntent
  ? adjustWindow({ planStart, examDate, today, unfinishedDates }, { horizonDays: 30 })!
  : adjustWindow({ planStart, examDate, today, unfinishedDates }, { horizonDays: intent.horizonDays ?? DEFAULT_ADJUST_DAYS })!
const { from, to } = window
const inWindow = allItems.filter(row => { const day = dateOnly(row.planDate); return day >= from && day <= to })
const before = toSnapshot(inWindow)
```

- [ ] **Step 4: 接入弹性分支**

在 `lock_task` / `move_task` / `add_task` / `drop_task` / `adjust_scope` 之后、旧 L1 分支之前插入：

```ts
if (env.planAdjustEngine === 'elastic' && !['move_task', 'add_task', 'drop_task', 'lock_task', 'adjust_scope'].includes(intent.kind)) {
  const tiers = rankImportance(open, {
    hints: intent.importance ?? [],
    weakSubjects,
    milestones: new Map(milestones.map(item => [item.subject, item.date])),
    windowTo: to,
  })
  const demands = buildElasticDemands(intent, inWindow, capacity, tiers, today, to)
  const hardTo = shiftDate(today, MAX_ADJUST_DAYS - 1) < shiftDate(examDate, -1)
    ? shiftDate(today, MAX_ADJUST_DAYS - 1) : shiftDate(examDate, -1)
  const result = planElasticAdjustment({
    before, tiers, demands,
    capacity: day => capacity.get(day) ?? dailyCapacity(briefAfter, day),
    today, windowTo: to, hardTo,
    autoExtend: !intent.horizonDays,
    milestones: new Map(milestones.map(item => [item.subject, item.date])),
  })
  console.info('[planning] 行程调整', JSON.stringify({
    kind: intent.kind, windowDays: datesBetween(from, to).length,
    touchedDays: result.touchedDays.length, shrinkMinutes: result.shrinkMinutes,
    overflow: result.overflow.length, engine: 'elastic',
  }))
  if (result.overflow.length === 0) {
    const tier = result.shrinkMinutes > 0 || result.touchedDays.length > 2 ? 'L2' : 'L1'
    const option = manualOption('manual', '按重要性调整', summaryLine(result), tier, before, result.after, capacity, deferredProfileAfter, result.notes)
    if (option) return finishSingle(option, result.shrinkMinutes > 0 ? '已按重要性腾好时间,确认后生效。' : '已按你说的调整,确认后生效。')
  }
  // 溢出:先尝试模型增量兜底(Task 5),失败再走旧方案引擎
  return finishMulti(attachProfile(buildAdjustmentOptions(optionsInput), deferredProfileAfter), '窗口内放不下,给你几个处理方式。')
}
```

`buildElasticDemands` 放在 `elasticAdjust.ts`：

```ts
export function buildElasticDemands(
  intent: AdjustIntent, items: any[], capacity: Map<string, number>, tiers: Map<number, Tier>,
  today: string, windowTo: string,
): ElasticDemand[]
```

- `unavailable`：`intent.days` 上的非固定项，`earliest` = 当天。
- `reduce_capacity`/`change_commitment`：对每天算 `load - capacity`，超出的部分按 `elastic → normal → protect`、分钟从大到小取项，`earliest` = 当天。
- `reschedule`：`intent.horizonDays` 存在时取窗口内全部非 protect 项；否则取 `planDate < today` 和 `planDate === today` 且当天超载的项。

把 `move_task`/`add_task` 分支里的 `compressDaysToCapacity(...)` 替换为 `shrinkDayByTier(after, day, capacity.get(day) ?? 0, tiers)`；没有 tiers 时按全部 normal 处理。

- [ ] **Step 5: 收紧调整单窗口与快照**

在 `finishSingle` / `finishMulti` 之前统一：

```ts
function tightenWindow(before: WindowSnapshotItem[], after: WindowSnapshotItem[], today: string) {
  const days = new Set<string>()
  const byId = new Map(after.map(item => [item.id, item]))
  for (const item of before) {
    const next = byId.get(item.id)
    if (!next || next.planDate !== item.planDate || next.minutes !== item.minutes || next.title !== item.title) {
      days.add(item.planDate)
      if (next) days.add(next.planDate)
    }
  }
  if (days.size === 0) return { from: today, to: today }
  const sorted = [...days].sort()
  return { from: sorted[0], to: sorted[sorted.length - 1] }
}
```

保存前把 `before`/`after` 过滤到收紧窗口、重算 option：

```ts
const tight = tightenWindow(before, option.after, today)
const windowFrom = tight.from, windowTo = tight.to
const filteredBefore = before.filter(item => item.planDate >= windowFrom && item.planDate <= windowTo)
const filteredAfter = option.after.filter(item => item.planDate >= windowFrom && item.planDate <= windowTo)
```

`saveAdjustmentDraft` 传过滤后的快照；`manualOption` 也用过滤后的快照重算 changes/impact。

- [ ] **Step 6: 跑测试确认通过**

Run: `cd server && node --import tsx --test src/modules/planning/service.test.ts`
Expected: PASS（按新行为更新旧断言）。

---

### Task 5: 模型增量兜底与硬校验

**Files:**
- Modify: `server/src/modules/planning/adjustL2.ts`
- Modify: `server/src/modules/planning/service.ts`（`PlanningAi` 与溢出分支）
- Test: `server/src/modules/planning/adjustL2.test.ts`

**Interfaces:**
- Produces:
  - `interface IncrementalInput { before: WindowSnapshotItem[]; tiers: Map<number, Tier>; days: string[]; capacity: Map<string, number>; milestones: Map<string, string>; message: string }`
  - `interface IncrementalProposal { changes: Array<{ id: number; dayOffset?: number; minutes?: number }>; drops: number[]; notes?: string }`
  - `runIncrementalAdjust(input: IncrementalInput): Promise<IncrementalProposal>`
  - `validateIncrementalProposal(proposal: IncrementalProposal, input: IncrementalInput): { ok: boolean; reason?: string; after?: WindowSnapshotItem[] }`
- Consumes: `Tier`、`WindowSnapshotItem`

- [ ] **Step 1: 写失败测试**

```ts
test('增量校验:改保护/固定项、越窗、超容量、超缩短上限都拒绝', () => {
  const before = [
    item(1, '2026-11-10', 60, 'protect'),
    item(2, '2026-11-10', 60, 'elastic'),
  ]
  const base = { before, tiers: new Map([[1, 'protect'], [2, 'elastic']]), days: ['2026-11-10', '2026-11-11'], capacity: { '2026-11-10': 120, '2026-11-11': 120 }, milestones: new Map() }
  assert.equal(validateIncrementalProposal({ changes: [{ id: 1, minutes: 30 }], drops: [] }, base).ok, false)
  assert.equal(validateIncrementalProposal({ changes: [{ id: 2, dayOffset: 9 }], drops: [] }, base).ok, false)
  assert.equal(validateIncrementalProposal({ changes: [{ id: 2, minutes: 5 }], drops: [] }, base).ok, false)
  assert.equal(validateIncrementalProposal({ changes: [], drops: [1] }, base).ok, false)
  const ok = validateIncrementalProposal({ changes: [{ id: 2, dayOffset: 1, minutes: 45 }], drops: [] }, base)
  assert.equal(ok.ok, true)
  assert.equal(ok.after!.find(i => i.id === 2)!.minutes, 45)
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/adjustL2.test.ts`
Expected: FAIL（`validateIncrementalProposal` 未定义）。

- [ ] **Step 3: 实现**

`runIncrementalAdjust` 用 `chatComplete`，`feature: 'adjust_plan'`，系统提示强调「只输出 changes/drops，不动保护/固定项，不输出绝对日期」；输出清洗后交给 `validateIncrementalProposal`。

校验实现按 spec 4.2 逐条检查；`after` = 原快照应用 changes/drops，未提及项保持不变。

`service.ts`：

```ts
adjustIncremental?: (input: IncrementalInput) => Promise<IncrementalProposal>
```

溢出分支调用；校验失败或模型未配置时不抛 502，直接回退 `buildAdjustmentOptions`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && node --import tsx --test src/modules/planning/adjustL2.test.ts`
Expected: PASS。

---

### Task 6: 客户端 `note` 展示

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt:787-794`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AdjustmentCard.kt:25-38, 91-93`
- Test: `YanZhong/app/src/test/java/com/yanzhong/app/ui/plan/AdjustmentChangeLineTest.kt`

**Interfaces:**
- Produces: `AdjustmentChangeDto.note: String? = null`；`adjustmentChangeLine()` 在行尾追加 `note`。

- [ ] **Step 1: 写失败测试**

```kotlin
class AdjustmentChangeLineTest {
    @Test
    fun `附带说明时追加在变动行末尾`() {
        val change = AdjustmentChangeDto(
            kind = "updated", id = 1, subject = "英语", title = "整理",
            from = AdjustmentFieldDto("2026-11-10", 60, "整理"),
            to = AdjustmentFieldDto("2026-11-10", 45, "整理"),
            note = "缩短 15 分钟（可让）"
        )
        assertTrue(adjustmentChangeLine(change).contains("缩短 15 分钟（可让）"))
    }

    @Test
    fun `没有说明时保持原格式`() {
        val change = AdjustmentChangeDto(
            kind = "moved", id = 1, subject = "数学", title = "真题",
            from = AdjustmentFieldDto("2026-11-10", 60, "真题"),
            to = AdjustmentFieldDto("2026-11-11", 60, "真题")
        )
        assertFalse(adjustmentChangeLine(change).contains("null"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `powershell.exe -NoProfile -Command "cd 'F:\kaoyan-app-prd\YanZhong'; .\gradlew.bat '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' :app:testDebugUnitTest --tests 'com.yanzhong.app.ui.plan.AdjustmentChangeLineTest'"`
Expected: FAIL（`note` 参数不存在 / 行内无说明）。

- [ ] **Step 3: 实现**

`Api.kt`：

```kotlin
data class AdjustmentChangeDto(
    val kind: String,
    val id: Long = 0,
    val subject: String = "",
    val title: String = "",
    val from: AdjustmentFieldDto? = null,
    val to: AdjustmentFieldDto? = null,
    /** 服务端给出的变动说明(如「缩短 60→45 分钟（可让）」);旧服务端为空 */
    val note: String? = null
)
```

`AdjustmentCard.kt` 的 `adjustmentChangeLine` 末尾：

```kotlin
return listOfNotNull(base, change.note?.takeIf { it.isNotBlank() }).joinToString(" · ")
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同上（`--tests 'com.yanzhong.app.ui.plan.AdjustmentChangeLineTest'`）
Expected: PASS。

---

### Task 7: 服务端返回 `note` 与摘要

**Files:**
- Modify: `server/src/modules/planning/adjust.ts`（`AdjustmentChange.note?`）
- Modify: `server/src/modules/planning/service.ts`（`manualOption`/`diffSnapshots` 注入说明）
- Test: `server/src/modules/planning/adjust.test.ts`

- [ ] **Step 1: 写失败测试**

```ts
test('diffSnapshots: 传入 notes 时每条变动带说明', () => {
  const before = [snap(1, '2026-11-10', 60)]
  const after = [{ ...before[0], planDate: '2026-11-11', minutes: 45 }]
  const changes = diffSnapshots(before, after, new Map([[1, '缩短 60→45 分钟（可让）']]))
  assert.equal(changes[0].note, '缩短 60→45 分钟（可让）')
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && node --import tsx --test src/modules/planning/adjust.test.ts`
Expected: FAIL（`diffSnapshots` 第三个参数不存在/`note` 未定义）。

- [ ] **Step 3: 实现**

`AdjustmentChange` 增加 `note?: string`；`diffSnapshots(before, after, notes?)` 在每条 change 上 `...(notes?.get(item.id) ? { note: notes.get(item.id) } : {})`。

`manualOption` 增加可选 `notes?: Map<number, string>` 并传给 `diffSnapshots`；弹性分支传 `result.notes`，摘要用：

```ts
const summaryLine = (r: ElasticResult) => {
  const shrink = r.shrinkMinutes > 0 ? `,共缩短 ${r.shrinkMinutes} 分钟` : ''
  return `只影响 ${r.touchedDays.length} 天${shrink};重要任务未动`
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && node --import tsx --test src/modules/planning/adjust.test.ts`
Expected: PASS。

---

### Task 8: 全量验证

- [ ] **Step 1: 服务端全量**

Run: `cd server && npm run verify`
Expected: typecheck OK；全部测试 PASS；build OK。

- [ ] **Step 2: 客户端全量**

Run: `powershell.exe -NoProfile -Command "cd 'F:\kaoyan-app-prd\YanZhong'; .\gradlew.bat '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' :app:testDebugUnitTest :app:assembleDebug"`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 行为核对清单（人工）**

- [ ] 默认窗口 5 天；用户说 10 天时窗口 10 天；自动延窗 ≤8 天。
- [ ] 「明天没空」只改 1–3 个日期，远处任务不变。
- [ ] 保护任务分钟数不变；可让任务先缩；普通任务次之。
- [ ] 目标日有可让任务时不再报「排满了」。
- [ ] 「落后了」不重排未来可行任务。
- [ ] 确认后 `Plan.version` 不变，仅窗口内项变化；撤销可还原。
- [ ] `PLAN_ADJUST_ENGINE=legacy` 时旧测试行为仍可跑通。

- [ ] **Step 4: 不提交**

按用户约束，不执行 `git commit` / `git push` / 部署；把改动与验证结果汇报给用户，等待单独批准。