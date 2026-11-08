# 课表单双周建模 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让固定占用支持单双周（`weekParity`），并用「校历第 1 周」这一个绝对锚点同时驱动输入侧的容量计算与输出侧的任务展开，使双周整天空闲不再被白白扣掉。

**Architecture:** 服务端在 `PlanBrief` 上加可选 `termWeekOneStart`（校历第 1 周的周一，YYYY-MM-DD），在 `BriefCommitment` 上加可选 `weekParity: 'odd' | 'even'`。新增纯函数 `weekParityForDate()` 做唯一奇偶判定；`daySchedule()` 按它过滤当天生效的占用，`netAvailableMinutes()` / `freeSlots()` 建立在其上因而自动生效。无锚点或无 parity 时一律退回「每周都占」的旧行为，`briefHash` 对旧简报保持逐字节不变。App 端在问卷第 3 步增加 parity 三态标记与校历第 1 周输入，OCR 提示词改为输出结构化 `weekParity`。

**Tech Stack:** TypeScript、Zod、`node:test` + `assert/strict`（`src/**/*.test.ts`）、Prisma（`briefJson` 为 `@db.Text`，本次无迁移）、Kotlin、Jetpack Compose、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-11-08-timetable-availability-and-week-parity-design.md`（本计划实现其中「4.2 B 部分」）

## Global Constraints

- **无 Prisma 迁移**：`UserProfile.briefJson` 是 `String? @db.Text`（`server/prisma/schema.prisma:221`），新字段随 JSON 走。禁止新增列、禁止跑 `prisma migrate`。
- **`briefHash` 向后兼容**：`canonicalBrief()`（`server/src/modules/planning/planBudget.ts:170-193`）序列化进 `briefHash`，后者是调整单指纹（`briefContextHash`，`:200`）。旧简报（无 `weekParity`、无 `termWeekOneStart`）的 canonical JSON 必须与改动前逐字节一致。手段：新字段一律用**条件展开**写入，绝不写 `weekParity: undefined` 或 `termWeekOneStart: ''`。
- **向后兼容的运行语义**：无锚点时，带 parity 的占用按「每周都占」计入（保守，宁可少算可用时间）。有锚点但日期早于锚点所在周时同样退回保守行为。
- **服务端约定**：错误用 `throw new ApiError(status, code, message)`；`code` 为稳定英文大写；用户可见 `message` 用中文。
- **日志约定**：服务端只用 `console.log/warn/error` + `[planning]` 前缀，不引入日志库。
- **App 文案**：用「备考者」口径；颜色/字号/圆角只能引用 `com.yanzhong.app.ui.theme.*`，禁止硬编码 `Color(...)` 或 `Xx.sp`。
- **本机命令**：Gradle 只能走 PowerShell + `.\gradlew.bat`，整串传入 `'-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK'`。服务端在 `server/` 下用 `npm test` / `npm run typecheck`。
- **OCR 不得编造**：`timetable.ts:40` 既有规则「严禁编造」继续适用 —— 模型判断不出单双周时 `weekParity` 必须省略，并把疑点写进 `warnings`，不许猜。
- 不得提交、推送、部署，除非产品负责人明确批准。

---

### Task 1: `weekParityForDate()` 奇偶判定纯函数

**Files:**
- Modify: `server/src/modules/planning/document.ts`（文件当前无任何 import；在 `:1` 之前加 import，在 `clockMinutes` 定义（`:277`）之后加函数）
- Test: `server/src/modules/planning/document.test.ts`（新建）

**Interfaces:**
- Consumes: `dayStart(input: Date): Date`、`addDays(input: Date, days: number): Date`、`diffDays(later: Date, earlier: Date): number`（均来自 `server/src/modules/planning/schemas.ts:23-36`；`schemas.ts` 只 import zod，故 `document.ts → schemas.ts` 不构成循环）。
- Produces:
  ```ts
  export function weekParityForDate(termWeekOneStart: string, date: Date): 'odd' | 'even' | null
  ```
  锚点为空 / 不是 `YYYY-MM-DD` / 非法日期 → `null`。否则把锚点归一到所在周的周一（ISO：周一=1 … 周日=7），`weeks = Math.floor(diffDays(date, monday) / 7)`；`weeks < 0` → `null`；`weeks % 2 === 0` → `'odd'`，否则 `'even'`。

- [ ] **Step 1: 写失败的测试**

新建 `server/src/modules/planning/document.test.ts`：

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { addDays } from './schemas'
import { weekParityForDate } from './document'

const utc = (day: string) => new Date(`${day}T00:00:00.000Z`)

test('weekParityForDate: 锚点当周为奇周,下一周为偶周', () => {
  // 2026-09-07 是周一(产品负责人那份 468 天计划就是按这个日子当校历第 1 周)
  const anchor = '2026-09-07'
  assert.equal(weekParityForDate(anchor, utc('2026-09-07')), 'odd')
  assert.equal(weekParityForDate(anchor, utc('2026-09-13')), 'odd')
  assert.equal(weekParityForDate(anchor, utc('2026-09-14')), 'even')
  assert.equal(weekParityForDate(anchor, utc('2026-09-20')), 'even')
  assert.equal(weekParityForDate(anchor, utc('2026-09-21')), 'odd')
})

test('weekParityForDate: 锚点填的不是周一时自动归一到所在周的周一', () => {
  // 用户填了周三 2026-09-09,不能让整张课表错位两天
  const anchorWednesday = '2026-09-09'
  assert.equal(weekParityForDate(anchorWednesday, utc('2026-09-07')), 'odd')
  assert.equal(weekParityForDate(anchorWednesday, utc('2026-09-14')), 'even')
  // 与直接填周一 2026-09-07 的结果逐日一致
  for (let offset = 0; offset < 60; offset++) {
    const day = addDays(utc('2026-09-07'), offset)
    assert.equal(
      weekParityForDate(anchorWednesday, day),
      weekParityForDate('2026-09-07', day),
      `第 ${offset} 天不一致`,
    )
  }
})

test('weekParityForDate: 锚点为空或非法时返回 null(调用方退回每周都占)', () => {
  assert.equal(weekParityForDate('', utc('2026-09-14')), null)
  assert.equal(weekParityForDate('2026-9-7', utc('2026-09-14')), null)
  assert.equal(weekParityForDate('下周一', utc('2026-09-14')), null)
  assert.equal(weekParityForDate('2026-02-30', utc('2026-09-14')), null)
})

test('weekParityForDate: 早于锚点所在周的日期返回 null', () => {
  // 计划起点早于开学日是常事(暑期就开始备考),那段日子没有单双周可言
  assert.equal(weekParityForDate('2026-09-07', utc('2026-08-31')), null)
  assert.equal(weekParityForDate('2026-09-07', utc('2026-09-06')), null)
})

test('weekParityForDate: 跨月与跨年连续交替不断档', () => {
  const anchor = '2026-12-28' // 周一
  const expected = (offset: number) => (Math.floor(offset / 7) % 2 === 0 ? 'odd' : 'even')
  for (let offset = 0; offset < 400; offset++) {
    assert.equal(
      weekParityForDate(anchor, addDays(utc(anchor), offset)),
      expected(offset),
      `偏移 ${offset} 天出错`,
    )
  }
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd server && node --import tsx --test src/modules/planning/document.test.ts`
Expected: FAIL，报错含 `does not provide an export named 'weekParityForDate'`（或 TS 编译期 `Property 'weekParityForDate' does not exist`）。

- [ ] **Step 3: 写最小实现**

在 `document.ts` 第 1 行（文件头注释块之前）加入 import：

```ts
import { addDays, dayStart, diffDays } from './schemas'
```

在 `document.ts:277`（`const clockMinutes = ...`）之后插入：

```ts
const DAY_PATTERN = /^\d{4}-\d{2}-\d{2}$/

/**
 * 某一天属于校历的奇周还是偶周。
 *
 * 为什么要一个绝对锚点:`aiGenerator` 原先按「阶段起点」算奇偶,而课表上的单双周
 * 是按「校历第 1 周」算的 —— 两个锚点不同步,输入侧标了双周的课和输出侧排到双周的
 * 任务就会错开。统一到这里之后,两端读同一个数。
 *
 * 返回 null 表示「这一天无从判断奇偶」(没填锚点、锚点非法、或日期早于开学周),
 * 调用方一律退回「每周都占」的保守口径 —— 宁可少算可用时间,也不能因为漏算占用
 * 把任务排到上课时间里。
 */
export function weekParityForDate(termWeekOneStart: string, date: Date): 'odd' | 'even' | null {
  const raw = String(termWeekOneStart ?? '').trim()
  if (!DAY_PATTERN.test(raw)) return null
  const anchor = dayStart(new Date(`${raw}T00:00:00.000Z`))
  if (Number.isNaN(anchor.getTime())) return null
  // 归一到所在周的周一:用户填了周三也不该让整张课表错位两天
  const monday = addDays(anchor, -((anchor.getUTCDay() || 7) - 1))
  const weeks = Math.floor(diffDays(date, monday) / 7)
  if (weeks < 0) return null
  return weeks % 2 === 0 ? 'odd' : 'even'
}
```

- [ ] **Step 4: 跑测试确认它通过**

Run: `cd server && node --import tsx --test src/modules/planning/document.test.ts`
Expected: 5 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/document.ts server/src/modules/planning/document.test.ts
git commit -m "$(cat <<'EOF'
feat(planning): add absolute week-parity anchor helper

课表的单双周按校历第 1 周算,而 aiGenerator 原先按阶段起点算,两个锚点
会对不上。weekParityForDate 统一判定,无法判断时返回 null 让调用方退回
「每周都占」的保守口径。
EOF
)"
```

---

### Task 2: 数据契约加 `weekParity` 与 `termWeekOneStart`

**Files:**
- Modify: `server/src/modules/planning/document.ts:86-87`（`BriefAvailability` / `BriefCommitment` 类型）
- Modify: `server/src/modules/planning/document.ts:109-139`（`PlanBrief` 接口与 `EMPTY_BRIEF`）
- Modify: `server/src/modules/planning/document.ts:304-312`（`normalizeCommitments`）
- Modify: `server/src/modules/planning/document.ts:337-360`（`normalizeBrief`）
- Modify: `server/src/modules/planning/document.ts:411-414`（`mergeBrief` 的去重与合并）
- Test: `server/src/modules/planning/document.test.ts`（追加）

**Interfaces:**
- Consumes: Task 1 的 `weekParityForDate`（本任务不用它，仅类型对齐）。
- Produces:
  ```ts
  export type BriefCommitment = {
    weekday: number; start: string; end: string; label: string
    weekParity?: 'odd' | 'even'
  }
  export interface PlanBrief {
    // ……既有字段不变……
    termWeekOneStart?: string
  }
  ```
  `EMPTY_BRIEF.termWeekOneStart === ''`。`normalizeBrief` 对非法锚点归一为 `''`；`normalizeCommitments` 对非法 parity **只丢该属性、不丢整条**。

- [ ] **Step 1: 追加失败的测试**

在 `document.test.ts` 顶部 import 行补上 `EMPTY_BRIEF, mergeBrief, normalizeBrief`：

```ts
import { EMPTY_BRIEF, mergeBrief, normalizeBrief, weekParityForDate } from './document'
```

在文件末尾追加：

```ts
test('normalizeBrief: 保留占用的单双周标记与校历锚点', () => {
  const brief = normalizeBrief({
    availability: [{ weekday: 1, windows: [{ start: '08:00', end: '12:00' }] }],
    fixedCommitments: [
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' },
      { weekday: 5, start: '10:00', end: '11:40', label: '编译原理' },
    ],
    termWeekOneStart: '2026-09-07',
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  })
  assert.equal(brief.fixedCommitments[0].weekParity, 'odd')
  assert.equal(brief.fixedCommitments[1].weekParity, undefined)
  assert.equal(brief.termWeekOneStart, '2026-09-07')
})

test('normalizeBrief: 非法 parity 只丢属性不丢整条占用', () => {
  const brief = normalizeBrief({
    fixedCommitments: [
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: '单周' },
      { weekday: 3, start: '10:00', end: '11:40', label: '数据结构', weekParity: 'even' },
    ],
    commitmentsConfirmed: true,
  })
  assert.equal(brief.fixedCommitments.length, 2, '两条占用都该留下')
  assert.equal(brief.fixedCommitments[0].weekParity, undefined)
  assert.equal(brief.fixedCommitments[1].weekParity, 'even')
})

test('normalizeBrief: 非法锚点归一为空串', () => {
  assert.equal(normalizeBrief({ termWeekOneStart: '2026-9-7' }).termWeekOneStart, '')
  assert.equal(normalizeBrief({ termWeekOneStart: '下周一' }).termWeekOneStart, '')
  assert.equal(normalizeBrief({}).termWeekOneStart, '')
  assert.equal(EMPTY_BRIEF.termWeekOneStart, '')
})

test('mergeBrief: 同一时段不同单双周算两条,不去重掉', () => {
  const base = normalizeBrief({
    fixedCommitments: [{ weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' }],
    commitmentsConfirmed: true,
  })
  const patch = normalizeBrief({
    fixedCommitments: [{ weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'even' }],
    commitmentsConfirmed: true,
  })
  const merged = mergeBrief(base, patch)
  assert.equal(merged.fixedCommitments.length, 2)
  assert.deepEqual(merged.fixedCommitments.map(item => item.weekParity), ['odd', 'even'])
})

test('mergeBrief: 补丁的锚点非空时覆盖,为空时保留旧值', () => {
  const base = normalizeBrief({ termWeekOneStart: '2026-09-07' })
  assert.equal(mergeBrief(base, normalizeBrief({ termWeekOneStart: '' })).termWeekOneStart, '2026-09-07')
  assert.equal(mergeBrief(base, normalizeBrief({ termWeekOneStart: '2027-02-22' })).termWeekOneStart, '2027-02-22')
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd server && node --import tsx --test src/modules/planning/document.test.ts`
Expected: Task 1 的 5 条 PASS，新增 5 条 FAIL（`weekParity` / `termWeekOneStart` 属性不存在或未被保留）。

- [ ] **Step 3: 改类型定义**

`document.ts:87` 替换为：

```ts
export type BriefCommitment = {
  weekday: number
  start: string
  end: string
  label: string
  /** 省略 = 每周都占(旧行为);odd/even 按 termWeekOneStart 锚点判定 */
  weekParity?: 'odd' | 'even'
}
```

`document.ts:109-124` 的 `PlanBrief` 接口里，在 `fixedCommitments` 那一行之后插入：

```ts
  /** 校历第 1 周的周一,YYYY-MM-DD。空 = 不启用单双周,带 parity 的占用按每周都占算 */
  termWeekOneStart?: string
```

`document.ts:126-139` 的 `EMPTY_BRIEF` 里，在 `fixedCommitments: [],` 之后插入一行：

```ts
  termWeekOneStart: '',
```

- [ ] **Step 4: 改归一逻辑**

`document.ts:304-312` 的 `normalizeCommitments` 整段替换为：

```ts
function normalizeCommitments(raw: unknown): BriefCommitment[] {
  return (Array.isArray(raw) ? raw : []).filter((item: any) => validWeekday(item?.weekday))
    .map((item: any) => {
      const window = briefWindow(item)
      if (!window) return null
      const commitment: BriefCommitment = {
        ...window,
        weekday: Number(item.weekday),
        label: str(item.label),
      }
      // 非法 parity 只丢属性不丢整条:模型写成「单周」时,这条课仍然是真实占用,
      // 退化成「每周都占」比整条消失安全得多
      const parity = item.weekParity
      if (parity === 'odd' || parity === 'even') commitment.weekParity = parity
      return commitment
    })
    .filter((item): item is BriefCommitment => item !== null && Boolean(item.label))
    .slice(0, 20)
}
```

`document.ts:337-360` 的 `normalizeBrief` 返回对象里，在 `fixedCommitments: normalizeCommitments(raw.fixedCommitments),` 之后插入：

```ts
    termWeekOneStart: DAY_PATTERN.test(String(raw.termWeekOneStart ?? '').trim())
      && !Number.isNaN(new Date(`${String(raw.termWeekOneStart).trim()}T00:00:00.000Z`).getTime())
      ? String(raw.termWeekOneStart).trim()
      : '',
```

- [ ] **Step 5: 改合并逻辑**

`document.ts:412-414` 的 `fixedCommitments` 合并整段替换为（去重键加入 parity）：

```ts
    fixedCommitments: [...base.fixedCommitments, ...patch.fixedCommitments.filter(item =>
      !base.fixedCommitments.some(existing => existing.weekday === item.weekday
        && existing.start === item.start && existing.end === item.end && existing.label === item.label
        && (existing.weekParity ?? '') === (item.weekParity ?? '')))],
```

同函数返回对象里，在 `commitmentsConfirmed:` 那一行之前插入：

```ts
    // 补丁为空(老客户端、或面谈没问这一项)时保留旧锚点,别把用户填过的开学周清掉
    termWeekOneStart: String(patch.termWeekOneStart ?? '').trim() || String(base.termWeekOneStart ?? '').trim(),
```

- [ ] **Step 6: 跑测试确认全部通过**

Run: `cd server && node --import tsx --test src/modules/planning/document.test.ts`
Expected: 10 个测试全部 PASS。

- [ ] **Step 7: 跑全量服务端测试与类型检查**

Run: `cd server && npm run typecheck && npm test`
Expected: typecheck 无错误；全量测试 PASS。若 `planBudget.test.ts` / `planSpec.test.ts` 因 `PlanBrief` 新字段报错，说明某处用了精确对象字面量构造 brief —— 补 `termWeekOneStart: ''` 或改用 `{ ...EMPTY_BRIEF, ... }`。

- [ ] **Step 8: 提交**

```bash
git add server/src/modules/planning/document.ts server/src/modules/planning/document.test.ts
git commit -m "$(cat <<'EOF'
feat(planning): carry weekParity on commitments and term anchor on brief

非法 parity 只丢属性不丢整条占用;mergeBrief 去重键含 parity,
同一时段的单周课与双周课算两条。
EOF
)"
```

---

### Task 3: `daySchedule()` 按奇偶过滤当天占用

**Files:**
- Modify: `server/src/modules/planning/document.ts:453`（`BriefSchedule` 类型别名）
- Modify: `server/src/modules/planning/document.ts:463-488`（`daySchedule`）
- Test: `server/src/modules/planning/document.test.ts`（追加）

**Interfaces:**
- Consumes: Task 1 `weekParityForDate`；Task 2 的 `BriefCommitment.weekParity`、`PlanBrief.termWeekOneStart`。
- Produces: `daySchedule(brief: BriefSchedule, date: Date)` 行为变更 —— 返回值形状不变（`{windows, commitments, period}`），但 `commitments` 只含当天真正生效的占用。`BriefSchedule`  widen 为 `Pick<PlanBrief, 'availability' | 'fixedCommitments' | 'termWeekOneStart'> & { periods?: BriefPeriod[] }`。`netAvailableMinutes()`（`:490`）与 `freeSlots()`（`:505`）签名不变、自动继承新行为。

- [ ] **Step 1: 追加失败的测试**

在 `document.test.ts` 顶部 import 补上 `freeSlots, netAvailableMinutes`：

```ts
import {
  EMPTY_BRIEF, freeSlots, mergeBrief, netAvailableMinutes, normalizeBrief, weekParityForDate,
} from './document'
```

在文件末尾追加：

```ts
/** 周三全天 08:00-18:00 有空,一条 08:00-09:40 的课;按 parity 决定是否扣掉 */
function parityBrief(weekParity?: 'odd' | 'even', termWeekOneStart = '2026-09-07') {
  return normalizeBrief({
    availability: [{ weekday: 3, windows: [{ start: '08:00', end: '18:00' }] }],
    fixedCommitments: [weekParity
      ? { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity }
      : { weekday: 3, start: '08:00', end: '09:40', label: '人工智能' }],
    termWeekOneStart,
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  })
}

test('netAvailableMinutes: 双周的课只在偶周扣掉容量', () => {
  const brief = parityBrief('even')
  // 2026-09-16 是第 2 周的周三(偶周) → 扣掉 100 分钟
  assert.equal(netAvailableMinutes(brief, utc('2026-09-16')), 600 - 100)
  // 2026-09-23 是第 3 周的周三(奇周) → 不扣
  assert.equal(netAvailableMinutes(brief, utc('2026-09-23')), 600)
})

test('netAvailableMinutes: 单周的课只在奇周扣掉容量', () => {
  const brief = parityBrief('odd')
  assert.equal(netAvailableMinutes(brief, utc('2026-09-16')), 600)
  assert.equal(netAvailableMinutes(brief, utc('2026-09-23')), 600 - 100)
})

test('netAvailableMinutes: 不标 parity 的课每周都扣(旧行为不破)', () => {
  const brief = parityBrief(undefined)
  assert.equal(netAvailableMinutes(brief, utc('2026-09-16')), 600 - 100)
  assert.equal(netAvailableMinutes(brief, utc('2026-09-23')), 600 - 100)
})

test('netAvailableMinutes: 标了 parity 但没填锚点时保守按每周都占', () => {
  const brief = parityBrief('even', '')
  assert.equal(netAvailableMinutes(brief, utc('2026-09-16')), 600 - 100)
  assert.equal(netAvailableMinutes(brief, utc('2026-09-23')), 600 - 100)
})

test('netAvailableMinutes: 早于开学周的日期退回保守口径', () => {
  const brief = parityBrief('even')
  // 2026-09-02 是开学前的周三,weekParityForDate 返回 null → 照常扣
  assert.equal(netAvailableMinutes(brief, utc('2026-09-02')), 600 - 100)
})

test('freeSlots: 双周有课的那天被切成两段,奇周是一整段', () => {
  const brief = parityBrief('even')
  assert.deepEqual(freeSlots(brief, utc('2026-09-16')), [{ start: '09:40', end: '18:00' }])
  assert.deepEqual(freeSlots(brief, utc('2026-09-23')), [{ start: '08:00', end: '18:00' }])
})

test('daySchedule 的假期口径不变:假期里平时的单双周占用都不生效', () => {
  const brief = parityBrief('even')
  brief.periods = [{
    start: '2026-09-14', end: '2026-09-20', kind: 'vacation', note: '中秋假',
    availability: [{ weekday: 3, windows: [{ start: '08:00', end: '18:00' }] }],
    fixedCommitments: [],
  }]
  // 假期区间内 dropWeeklyCommitments 生效,连偶周的课也不扣
  assert.equal(netAvailableMinutes(brief, utc('2026-09-16')), 600)
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd server && node --import tsx --test src/modules/planning/document.test.ts`
Expected: 新增 7 条中，`双周的课只在偶周扣掉容量`、`单周的课只在奇周扣掉容量`、`freeSlots: 双周有课的那天被切成两段` FAIL（当前实现无视 parity，两天都扣）；`不标 parity`、`没填锚点`、`早于开学周`、`假期口径` 4 条应当已经 PASS（它们锁的是旧行为）。

- [ ] **Step 3: 写最小实现**

`document.ts:453` 替换为：

```ts
type BriefSchedule = Pick<PlanBrief, 'availability' | 'fixedCommitments' | 'termWeekOneStart'> & { periods?: BriefPeriod[] }
```

`document.ts:463-488` 的 `daySchedule` 整段替换为：

```ts
/**
 * 某一天的真实作息:区间内按星期覆盖平时作息,未列出的星期回落到平时。
 * 寒暑假不再叠加学期里的固定占用(课都不上了,再扣一遍就排不出任何任务);
 * 单双周的课只在它生效的那一周扣掉 —— parity 为 null(没填校历第 1 周、或日期早于
 * 开学周)时保守按「每周都占」,宁可少算可用时间,也不能把任务排进上课时间。
 */
export function daySchedule(brief: BriefSchedule, date: Date): {
  windows: BriefWindow[]
  commitments: BriefCommitment[]
  period: BriefPeriod | null
} {
  const weekday = date.getUTCDay() || 7
  const period = periodForDate(brief, date)
  const periodDays = period ? period.availability.filter(day => day.weekday === weekday) : []
  const windows = periodDays.flatMap(day => day.windows)
  const fallbackWindows = brief.availability.filter(day => day.weekday === weekday).flatMap(day => day.windows)
  const dropWeeklyCommitments = period?.kind === 'vacation' || period?.kind === 'summer'
  const parity = weekParityForDate(brief.termWeekOneStart ?? '', date)
  const applies = (event: BriefCommitment) => event.weekday === weekday
    && (!event.weekParity || parity === null || event.weekParity === parity)
  const weekly = dropWeeklyCommitments ? [] : brief.fixedCommitments.filter(applies)
  const inPeriod = period ? period.fixedCommitments.filter(applies) : []
  return {
    windows: windows.length > 0 ? windows : fallbackWindows,
    commitments: [...weekly, ...inPeriod],
    period,
  }
}
```

- [ ] **Step 4: 跑测试确认它通过**

Run: `cd server && node --import tsx --test src/modules/planning/document.test.ts`
Expected: 17 个测试全部 PASS。

- [ ] **Step 5: 跑全量服务端验证**

Run: `cd server && npm run verify`
Expected: typecheck + 全量测试 + build 全部通过。`planSpec.test.ts` 里那几条 `netAvailableMinutes` 断言（`:135`、`:236`、`:289`）用的简报都没有 parity，行为不变，应当继续 PASS。

- [ ] **Step 6: 提交**

```bash
git add server/src/modules/planning/document.ts server/src/modules/planning/document.test.ts
git commit -m "$(cat <<'EOF'
feat(planning): count odd/even week classes only on their own week

daySchedule 按校历锚点过滤当天生效的占用,netAvailableMinutes 与 freeSlots
自动继承。无锚点或无 parity 时保持「每周都占」的保守口径,旧简报行为不变。
EOF
)"
```

---

### Task 4: `canonicalBrief()` 的哈希稳定性

**Files:**
- Modify: `server/src/modules/planning/planBudget.ts:170-193`（`canonicalBrief`）
- Test: `server/src/modules/planning/planBudget.test.ts`（追加）

**Interfaces:**
- Consumes: Task 2 的 `PlanBrief.termWeekOneStart`、`BriefCommitment.weekParity`。
- Produces: `canonicalBrief(brief: PlanBrief, examDate: string): string` 签名不变。新增不变式：旧简报（无 parity、无锚点）的输出与改动前逐字节一致；`termWeekOneStart` 仅在非空时出现；commitment 排序键末尾追加 `weekParity ?? ''`。

- [ ] **Step 1: 追加失败的测试**

在 `server/src/modules/planning/planBudget.test.ts` 末尾追加（先确认文件顶部已 import `canonicalBrief`；若没有，把它加进既有的 `from './planBudget'` import 列表）：

```ts
test('canonicalBrief: 旧简报不含任何单双周痕迹(哈希与改动前一致)', () => {
  // 调整单指纹读的是 briefHash → canonicalBrief。老账号的简报里没有 weekParity,
  // 也没有 termWeekOneStart;这两个键一旦出现,线上所有已有计划的调整单会集体失效。
  const brief = normalizeBrief({
    availability: [{ weekday: 1, windows: [{ start: '08:00', end: '12:00' }] }],
    fixedCommitments: [{ weekday: 3, start: '08:00', end: '09:40', label: '人工智能' }],
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  })
  const canonical = canonicalBrief(brief, '2026-12-19')
  assert.ok(!canonical.includes('termWeekOneStart'), canonical)
  assert.ok(!canonical.includes('weekParity'), canonical)
})

test('canonicalBrief: 填了校历第 1 周才把锚点写进去', () => {
  const brief = normalizeBrief({
    availability: [{ weekday: 1, windows: [{ start: '08:00', end: '12:00' }] }],
    termWeekOneStart: '2026-09-07',
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  })
  const canonical = canonicalBrief(brief, '2026-12-19')
  assert.ok(canonical.includes('"termWeekOneStart":"2026-09-07"'), canonical)
})

test('canonicalBrief: 同一时段不同单双周的两条占用排序稳定', () => {
  const brief = normalizeBrief({
    availability: [{ weekday: 1, windows: [{ start: '08:00', end: '12:00' }] }],
    fixedCommitments: [
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'even' },
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' },
    ],
    termWeekOneStart: '2026-09-07',
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  })
  const canonical = canonicalBrief(brief, '2026-12-19')
  // 排序键末尾是 parity:'even' 必须排在 'odd' 前,两次序列化结果一致
  assert.ok(canonical.indexOf('"weekParity":"even"') < canonical.indexOf('"weekParity":"odd"'), canonical)
  assert.equal(canonical, canonicalBrief(JSON.parse(JSON.stringify(brief)), '2026-12-19'))
})

test('canonicalBrief: 无 parity 的占用排在有 parity 的同键占用之前', () => {
  // 旧行为里所有占用都没有 parity,追加排序键不能改变它们之间的相对顺序
  const brief = normalizeBrief({
    availability: [{ weekday: 1, windows: [{ start: '08:00', end: '12:00' }] }],
    fixedCommitments: [
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' },
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能' },
    ],
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  })
  const canonical = canonicalBrief(brief, '2026-12-19')
  const list = JSON.parse(canonical).fixedCommitments as Array<{ weekParity?: string }>
  assert.equal(list[0].weekParity, undefined)
  assert.equal(list[1].weekParity, 'odd')
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd server && node --import tsx --test src/modules/planning/planBudget.test.ts`
Expected: 第 2、3、4 条 FAIL（当前 `canonicalBrief` 既不写锚点，也不按 parity 排序）。第 1 条应当已经 PASS —— 它是本次改动的护栏，若 FAIL 说明 Task 2 把 `termWeekOneStart: ''` 泄漏进了序列化结果，必须回到 Task 2 修 `normalizeBrief`/`EMPTY_BRIEF` 的写入方式。

- [ ] **Step 3: 写最小实现**

`planBudget.ts:187-191` 替换为：

```ts
    fixedCommitments: [...brief.fixedCommitments]
      .sort((a, b) => a.weekday - b.weekday || a.start.localeCompare(b.start) || a.end.localeCompare(b.end)
        || a.label.localeCompare(b.label) || (a.weekParity ?? '').localeCompare(b.weekParity ?? '')),
    periods: [...(brief.periods ?? [])].sort((a, b) => a.start.localeCompare(b.start) || a.end.localeCompare(b.end)),
    availabilityConfirmed: brief.availabilityConfirmed,
    commitmentsConfirmed: brief.commitmentsConfirmed,
    // 只有真填了校历第 1 周才写进哈希:空串一旦出现,所有旧简报的 briefHash 都会变,
    // 线上已有计划的调整单(briefContextHash)会集体失效
    ...(brief.termWeekOneStart ? { termWeekOneStart: brief.termWeekOneStart } : {}),
```

注意：`JSON.stringify` 会自动省略值为 `undefined` 的属性，所以 `fixedCommitments` 里没标 parity 的旧占用序列化结果与改动前一致，不需要额外处理。

- [ ] **Step 4: 跑测试确认它通过**

Run: `cd server && node --import tsx --test src/modules/planning/planBudget.test.ts`
Expected: 全部 PASS。

- [ ] **Step 5: 跑全量服务端验证**

Run: `cd server && npm run verify`
Expected: typecheck + 全量测试 + build 全部通过。

- [ ] **Step 6: 提交**

```bash
git add server/src/modules/planning/planBudget.ts server/src/modules/planning/planBudget.test.ts
git commit -m "$(cat <<'EOF'
fix(planning): keep briefHash stable for briefs without week parity

校历锚点只在非空时进入 canonical JSON,占用排序键末尾追加 parity。
旧简报的哈希逐字节不变,线上已有计划的调整单不会集体失效。
EOF
)"
```

---

### Task 5: 入参校验与档案写入

**Files:**
- Modify: `server/src/modules/planning/schemas.ts:131-145`（`fixedCommitmentsSchema`）
- Modify: `server/src/modules/planning/schemas.ts:157-175`（`profileInputSchema`）
- Modify: `server/src/modules/planning/service.ts:175-178`（`BriefPatchInput`）
- Modify: `server/src/modules/planning/service.ts:1014-1031`（`applyProfileFacts`）
- Modify: `server/src/modules/planning/service.ts:1050-1063`（`withCommitments`）
- Test: `server/src/modules/planning/schemas.test.ts`（追加）

**Interfaces:**
- Consumes: Task 2 的类型。`ProfileInput` 新增 `termWeekOneStart?: string`（缺键 = 保留旧值，`''` = 显式清除）。
- Produces: `fixedCommitmentsSchema` 接受可选 `weekParity: 'odd' | 'even'`，其它值报 Zod 错；`profileInputSchema` 接受可选 `termWeekOneStart`（`YYYY-MM-DD`；缺键保留旧值、`''` 显式清除、合法日期设置，执行时按三值语义落地，见 Step 3）。`applyProfileFacts` 与 `withCommitments` 透传/去重含 parity。

- [ ] **Step 1: 追加失败的测试**

在 `server/src/modules/planning/schemas.test.ts` 末尾追加（该文件已 import `profileInputSchema`，见 `:3`；`validPayload()` 是文件内既有的辅助函数，见 `:81` 用法）：

```ts
test('profileInputSchema: 接受校历第 1 周与占用的单双周标记', () => {
  const parsed = profileInputSchema.parse({
    ...validPayload(),
    termWeekOneStart: '2026-09-07',
    fixedCommitments: [
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' },
      { weekday: 5, start: '10:00', end: '11:40', label: '编译原理' },
    ],
    commitmentsConfirmed: true,
  })
  assert.equal(parsed.termWeekOneStart, '2026-09-07')
  assert.equal(parsed.fixedCommitments[0].weekParity, 'odd')
  assert.equal(parsed.fixedCommitments[1].weekParity, undefined)
})

test('profileInputSchema: 老客户端不带新字段时锚点是 undefined(由写入层保留旧值)', () => {
  // 不能默认成空串:空串现在表示「用户显式清除」,默认成空串会让老客户端的每次提交都清掉开学周
  const parsed = profileInputSchema.parse(validPayload())
  assert.equal(parsed.termWeekOneStart, undefined)
})

test('profileInputSchema: 非法校历第 1 周被挡下', () => {
  for (const bad of ['2026-9-7', '下周一', '2026/09/07', '20260907']) {
    const parsed = profileInputSchema.safeParse({ ...validPayload(), termWeekOneStart: bad })
    assert.equal(parsed.success, false, `${bad} 本该被拒绝`)
  }
})

test('profileInputSchema: 非法 weekParity 被挡下', () => {
  const parsed = profileInputSchema.safeParse({
    ...validPayload(),
    fixedCommitments: [{ weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: '单周' }],
    commitmentsConfirmed: true,
  })
  assert.equal(parsed.success, false)
})
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd server && node --import tsx --test src/modules/planning/schemas.test.ts`
Expected: 新增 4 条 FAIL（`termWeekOneStart` 未定义、`weekParity` 被 Zod 剥离或报错方式不符）。

- [ ] **Step 3: 改 Zod schema**

`schemas.ts:131-145` 的 `fixedCommitmentsSchema` 整段替换为：

```ts
/** 固定占用:上课、上班、通勤等每周固定被占掉的时间段 */
const fixedCommitmentsSchema = z.preprocess(
  normalizeJsonArray,
  z
    .array(
      z
        .object({
          weekday: weekdaySchema,
          start: timeSchema,
          end: timeSchema,
          label: z.string().trim().min(1, '固定占用需要说明是什么事').max(16),
          // 省略 = 每周都占。单双周只在填了 termWeekOneStart 时才真正参与容量计算
          weekParity: z.enum(['odd', 'even']).optional(),
        })
        .refine(c => clockMinutes(c.start) < clockMinutes(c.end), '结束时间需要晚于开始时间'),
    )
    .max(20, '固定占用最多 20 条')
    .default([]),
)
```

`schemas.ts:157-175` 的 `profileInputSchema` 里，在 `fixedCommitments: fixedCommitmentsSchema,` 之后插入：

```ts
  // 校历第 1 周的周一。三值:缺键 = 客户端没提供、保留旧值;空串 = 显式清除;合法日期 = 设置。
  // 空串表示不启用单双周,此时带 weekParity 的占用保守按每周都占计算。
  // 执行时的两处修正:去掉 .default(''),并补一条「真实存在日期」的 refine
  // (2026-02-30 这类值 V8 会静默进位,不挡会存进读回时被归一成空串的脏值)。
  termWeekOneStart: z
    .string()
    .trim()
    .regex(/^\d{4}-\d{2}-\d{2}$/, '校历第 1 周要写成 2026-09-07 这样(年月日全都要)')
    .refine(value => {
      if (value === '') return true
      const parsed = new Date(`${value}T00:00:00.000Z`)
      return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
    }, '校历第 1 周不是一个真实存在的日期')
    .optional()
    .or(z.literal('')),
```

- [ ] **Step 4: 跑 schema 测试确认它通过**

Run: `cd server && node --import tsx --test src/modules/planning/schemas.test.ts`
Expected: 全部 PASS。若 `非法校历第 1 周被挡下` 仍 FAIL，检查 `.or(z.literal(''))` 的位置 —— 它必须只放行空串，不能放行任意字符串。

- [ ] **Step 5: 改 service 的三处**

`service.ts:175-178` 的 `BriefPatchInput` 替换为：

```ts
export interface BriefPatchInput {
  examSubjects?: BriefPatchSubjectInput[]
  fixedCommitments?: Array<{ weekday: number; start: string; end: string; label: string; weekParity?: 'odd' | 'even' }>
}
```

`service.ts:1023-1030` 的 `applyProfileFacts` 返回对象里，在 `fixedCommitments:` 那一行之后插入：

```ts
    // 缺键 = 老客户端没提供,保留旧锚点;空串 = 用户在问卷里清空,真的清掉。
    // 执行时修正:不能用 `input.termWeekOneStart || brief.termWeekOneStart`(那会把「显式清除」吃掉)。
    termWeekOneStart: input.termWeekOneStart === undefined
      ? (brief.termWeekOneStart ?? '')
      : input.termWeekOneStart,
```

`service.ts:1053-1059` 的 `withCommitments` 去重判断替换为：

```ts
  for (const item of incoming) {
    if (!merged.some(existing => existing.weekday === item.weekday
      && existing.start === item.start && existing.end === item.end && existing.label === item.label
      && (existing.weekParity ?? '') === ((item.weekParity as string | undefined) ?? ''))) {
      merged.push(item)
    }
  }
```

- [ ] **Step 6: 跑全量服务端验证**

Run: `cd server && npm run verify`
Expected: typecheck + 全量测试 + build 全部通过。`service.test.ts:410-412` 那条用 `profileInputSchema.parse(validProfile())` 的回归测试应当继续 PASS（新增字段可选且缺键保留旧值，不会破坏既有载荷）。

- [ ] **Step 7: 提交**

```bash
git add server/src/modules/planning/schemas.ts server/src/modules/planning/schemas.test.ts server/src/modules/planning/service.ts
git commit -m "$(cat <<'EOF'
feat(planning): accept week parity and term anchor from the questionnaire

fixedCommitmentsSchema 加可选 weekParity,profileInputSchema 加 termWeekOneStart
(缺键保留旧值、空串清除,老客户端不受影响);applyProfileFacts 与 withCommitments 透传并按 parity 去重。
EOF
)"
```

---

### Task 6: 课表 OCR 输出结构化 `weekParity`

**Files:**
- Modify: `server/src/modules/planning/timetable.ts:5-13`（文件头设计注释）
- Modify: `server/src/modules/planning/timetable.ts:32-48`（`SYSTEM_PROMPT`）
- Modify: `server/src/modules/planning/timetable.ts:96-101`（`normalizeBrief` 调用处，需让 parity 存活）

**Interfaces:**
- Consumes: Task 2 的 `normalizeCommitments`（经 `normalizeBrief` 间接生效）；`PlanBrief['fixedCommitments']` 已带 parity。
- Produces: `TimetableResult.fixedCommitments` 的元素可带 `weekParity: 'odd' | 'even'`。`TimetableResult` 的形状不变（`{summary, warnings, fixedCommitments}`）—— **不新增 `termWeekOneStart`**：课表图上通常没有校历日期，让模型去推违反 `:40` 既有的「严禁编造」，锚点由 App 端单独收集（Task 9/10）。

- [ ] **Step 1: 改文件头注释**

`timetable.ts:5-13` 替换为：

```ts
/**
 * 课表图片识别:把用户拍/截的课程表交给视觉模型,转成「每周固定占用」。
 *
 * 为什么落到 fixedCommitments 而不是直接算出空闲:
 * - netAvailableMinutes 已经按「窗口 - 占用」算净空闲,课表本质上就是「被占掉的时间」;
 * - 空闲窗口由问卷第 3 步按星期给出(App 端会在进入该步时用时段预设铺满七天),两者叠加即可,
 *   不需要给 free-time 再造一套结构。
 *
 * 单双周:模型能判断出来就输出结构化 weekParity(odd/even),由 daySchedule 按校历锚点
 * 决定这门课在哪一周生效。锚点(termWeekOneStart)不从图片里猜 —— 课表上通常没有校历日期,
 * 猜错会让整张课表错位一周,宁可让用户在问卷里自己填。
 * 没填锚点、或模型判断不出单双周时,统一按「每周都占」计入:宁可少算可用时间,
 * 也不能因为漏算占用把任务排进上课时间。
 */
```

- [ ] **Step 2: 改 SYSTEM_PROMPT 的规则 2 与 JSON 示例**

`timetable.ts:37-38` 的规则 2 替换为：

```
2. 同一门课一周上两次就输出两条。课表上标了「单周」「双周」「周次 1-16 单」这类字样的,
   输出 weekParity:单周写 "odd",双周写 "even";标了「1-16 周」全周都上、或根本没标周次的,
   省略 weekParity 这个字段(不要写 null,也不要写空串)。判断不出来的把疑点写进 warnings,不要猜。
```

`timetable.ts:43-48` 的 JSON 示例替换为：

```
严格只输出一个 JSON 对象,不要解释文字、不要 Markdown 代码块:
{
  "summary": "一句话说明识别结果,如「识别到 12 条课程占用,其中 2 条是单双周课」",
  "warnings": ["周三 5-6 节课程名模糊,请确认", "周五那门课没标周次,已按每周都上处理"],
  "commitments": [
    {"weekday":1,"start":"08:00","end":"09:40","label":"高等数学"},
    {"weekday":3,"start":"08:00","end":"09:40","label":"人工智能","weekParity":"odd"}
  ]
}
```

同时把 `timetable.ts:39` 的规则 3 保持不变（label 写课程名），并删掉原先「在 label 末尾标注 (单周)/(双周)」的要求 —— 该措辞位于被替换的规则 2 内，替换后自然消失。

- [ ] **Step 3: 确认 parity 能穿过 `normalizeBrief`**

`timetable.ts:96-101` 当前是：

```ts
  const brief = normalizeBrief({
    fixedCommitments: parsed?.commitments,
    commitmentsConfirmed: parsed?.commitments !== undefined,
  })
```

无需修改：Task 2 已让 `normalizeCommitments` 解析 `weekParity`，非法值只丢属性不丢整条。为把这条链路钉死，在 `server/src/modules/planning/document.test.ts` 末尾追加：

```ts
test('normalizeBrief: 课表识别的原始 JSON 能带着单双周穿过收敛层', () => {
  // timetable.ts 用 normalizeBrief 收敛模型输出,parity 必须活下来
  const brief = normalizeBrief({
    fixedCommitments: [
      { weekday: 1, start: '08:00', end: '09:40', label: '高等数学' },
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' },
      { weekday: 3, start: '10:00', end: '11:40', label: '课程(待确认)', weekParity: 'EVEN' },
    ],
    commitmentsConfirmed: true,
  })
  assert.equal(brief.fixedCommitments.length, 3)
  assert.equal(brief.fixedCommitments[0].weekParity, undefined)
  assert.equal(brief.fixedCommitments[1].weekParity, 'odd')
  assert.equal(brief.fixedCommitments[2].weekParity, undefined, '大写 EVEN 属非法值,只丢属性')
})
```

- [ ] **Step 4: 跑测试与类型检查**

Run: `cd server && npm run verify`
Expected: typecheck + 全量测试 + build 全部通过，新增的那条 PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/modules/planning/timetable.ts server/src/modules/planning/document.test.ts
git commit -m "$(cat <<'EOF'
feat(planning): timetable OCR emits structured weekParity

不再靠 label 后缀表达单双周,改为输出 weekParity 字段;判断不出来就省略并写进
warnings。校历锚点不从图片里猜,由问卷单独收集。
EOF
)"
```

---

### Task 7: 输出侧统一到同一个锚点

**Files:**
- Modify: `server/src/modules/planning/aiGenerator.ts:337`（`expandStage` 的 `brief` 参数类型）
- Modify: `server/src/modules/planning/aiGenerator.ts:344-349`（奇偶计算）
- Modify: `server/src/modules/planning/aiGenerator.ts:394-397`（`expandStageFromVariants` 的 `brief` 参数类型）
- Modify: `server/src/modules/planning/aiGenerator.ts:7`（import）
- Modify: `server/src/modules/planning/aiGenerator.ts:135`（提示词里对奇偶锚点的说明）
- Test: `server/src/modules/planning/generator.test.ts`（追加）

**Interfaces:**
- Consumes: Task 1 `weekParityForDate`；Task 2 `PlanBrief.termWeekOneStart`。
- Produces: `expandStage(stage, range, brief: Pick<PlanBrief, 'availability' | 'fixedCommitments' | 'termWeekOneStart'>)`。有锚点时奇偶按校历算，无锚点时保持 `Math.floor(offset / 7) % 2` 的旧行为。`expandStageFromVariants` 同步 widen。

- [ ] **Step 1: 追加失败的测试**

`generator.test.ts` 的既有约定（已核对）：文件顶部 import 了 `expandStage`（`:4`）与 `EMPTY_BRIEF`（`:6`）；`:19` 有辅助函数 `weekAvailability(windows)`；`:29` 有 `const FULL_BRIEF = briefWith([1..7], '09:00', '12:00')`；`:233-252` 那条 `AI stage alternates odd and even week queues...` 就是现成的 parity 用例，可直接照它的写法。`AiStage` 是 `aiGenerator.ts:71` 的**非导出**类型，`GeneratedStage`（`generator.ts:40-51`）的必填字段是 `{name, startDate: Date, endDate: Date, sortOrder}`，没有 `weeklyMinutes`。因此 stage 与 range 必须**以对象字面量内联传入**（内联才有上下文类型，`weekParity: 'even'` 才能收窄到联合类型；抽成 const 会因推断为 `string` 而编译失败）。

在 `generator.test.ts` 文件末尾追加：

```ts
// ---------- 单双周锚点统一(校历第 1 周) ----------

/** 校历第 1 周 = 2026-09-07(周一),于是 09-14 起是第 2 周 = 偶周、09-21 起是第 3 周 = 奇周 */
const TERM_ANCHOR_BRIEF = {
  availability: weekAvailability([{ start: '08:00', end: '18:00' }]),
  fixedCommitments: [],
  termWeekOneStart: '2026-09-07',
}

/** 与 TERM_ANCHOR_BRIEF 同样的窗口,只是不填锚点 —— 用来锁旧口径不被改坏 */
const NO_ANCHOR_BRIEF = {
  availability: weekAvailability([{ start: '08:00', end: '18:00' }]),
  fixedCommitments: [],
}

const titlesOn = (items: Array<{ planDate: Date; title: string }>, day: string) => items
  .filter(item => item.planDate.toISOString().slice(0, 10) === day).map(item => item.title)

test('expandStage: 有校历锚点时按校历算奇偶,不按阶段起点', () => {
  // 阶段起点 2026-09-14 是校历第 2 周(偶周)。旧口径把 offset 0 当奇周,
  // 当周排的是「奇周阅读」;统一锚点后当周应该排「偶周阅读」。
  const start = new Date('2026-09-14T00:00:00.000Z')
  const items = expandStage({
    name: '基础',
    weeklySlots: [
      { weekdays: [1], subject: '英语一', title: '偶周阅读', minutes: 60, weekParity: 'even' },
      { weekdays: [1], subject: '英语一', title: '奇周阅读', minutes: 60, weekParity: 'odd' },
    ],
  }, { name: '基础', startDate: start, endDate: new Date('2026-09-27T00:00:00.000Z'), sortOrder: 0 }, TERM_ANCHOR_BRIEF)

  assert.ok(titlesOn(items, '2026-09-14').includes('偶周阅读'), titlesOn(items, '2026-09-14').join(','))
  assert.ok(!titlesOn(items, '2026-09-14').includes('奇周阅读'))
  // 第 3 周(09-21)是奇周,轮到「奇周阅读」
  assert.ok(titlesOn(items, '2026-09-21').includes('奇周阅读'), titlesOn(items, '2026-09-21').join(','))
})

test('expandStage: 没填校历锚点时保持阶段起点的旧口径', () => {
  // 同一个阶段起点、同一套窗口,只因缺锚点而结果相反 —— 这条锁的是向后兼容
  const start = new Date('2026-09-14T00:00:00.000Z')
  const items = expandStage({
    name: '基础',
    weeklySlots: [
      { weekdays: [1], subject: '英语一', title: '偶周阅读', minutes: 60, weekParity: 'even' },
      { weekdays: [1], subject: '英语一', title: '奇周阅读', minutes: 60, weekParity: 'odd' },
    ],
  }, { name: '基础', startDate: start, endDate: new Date('2026-09-27T00:00:00.000Z'), sortOrder: 0 }, NO_ANCHOR_BRIEF)

  assert.ok(titlesOn(items, '2026-09-14').includes('奇周阅读'), titlesOn(items, '2026-09-14').join(','))
  assert.ok(titlesOn(items, '2026-09-21').includes('偶周阅读'), titlesOn(items, '2026-09-21').join(','))
})
```

若 `titlesOn` 的形参类型与 `GeneratedItem` 不兼容（`GeneratedItem` 定义在 `generator.ts:57` 起），把形参类型换成 `ReturnType<typeof expandStage>` 即可，不要改生产类型。

- [ ] **Step 2: 跑测试确认它失败**

Run: `cd server && node --import tsx --test src/modules/planning/generator.test.ts`
Expected: 第 1 条 FAIL（当前无视锚点，2026-09-14 被当成奇周，排出来的是「奇周阅读」）；第 2 条 PASS（锁旧行为）；`:233` 那条既有 parity 测试继续 PASS（它不填锚点）。

- [ ] **Step 3: 写最小实现**

`aiGenerator.ts:7` 的 import 里加上 `weekParityForDate`：

```ts
import { briefIsEmpty, briefToPrompt, documentMatchesSubjects, netAvailableMinutes, weekParityForDate, type PlanBrief, type PlanDocument } from './document'
```

`aiGenerator.ts:337` 的签名替换为：

```ts
export function expandStage(stage: AiStage, range: GeneratedStage, brief: Pick<PlanBrief, 'availability' | 'fixedCommitments' | 'termWeekOneStart'>): GeneratedItem[] {
```

`aiGenerator.ts:344-349` 的循环头替换为：

```ts
  for (let offset = 0; offset < stageLength; offset++) {
    const planDate = addDays(range.startDate, offset)
    let dayBudget = netAvailableMinutes(brief, planDate)
    if (dayBudget < MIN_SLOT_MINUTES) continue
    const dow = isoWeekday(planDate)
    // 有校历锚点就按校历算奇偶,和课表上的单双周对齐;没锚点时退回「从阶段起点算」的旧口径
    const weekParity = weekParityForDate(brief.termWeekOneStart ?? '', planDate)
      ?? (Math.floor(offset / 7) % 2 === 0 ? 'odd' : 'even')
```

`aiGenerator.ts:394-397` 的 `expandStageFromVariants` 签名里，`brief` 参数类型同步替换为：

```ts
  brief: Pick<PlanBrief, 'availability' | 'fixedCommitments' | 'termWeekOneStart'>,
```

该函数体内**不涉及奇偶**（已核对：`weekParity` 在 `aiGenerator.ts` 只出现在 `:66-68`、`:122-135`、`:211-218`、`:349-366`，全在 `expandStage` 的模板展开路径与提示词里），所以只改类型、不动逻辑。周变体路径（L2 细化轮产物）本身就是一周一份任务序列，不需要奇偶筛选。

- [ ] **Step 4: 改提示词里对奇偶的说明**

`aiGenerator.ts:135` 替换为：

```
   - weekdays 用 1-7 表示周一到周日;weekParity 用 odd/even 区分奇周/偶周,省略表示不限定;任务完成后不循环重播;
     奇偶按考生的校历第 1 周算(见下文的考生简报),简报里没有校历信息时按本阶段起点算;
```

同时在 `briefToPrompt`（`document.ts:584-591`）输出的简报文本里让模型看得到锚点 —— 在 `document.ts` 的 `briefToPrompt` 中，`if (brief.availability.length) ...` 那一行之前插入：

```ts
  if (brief.termWeekOneStart) lines.push(`校历第 1 周(周一):${brief.termWeekOneStart},单双周按此推算`)
```

- [ ] **Step 5: 跑测试确认它通过**

Run: `cd server && node --import tsx --test src/modules/planning/generator.test.ts`
Expected: 两条新增测试 PASS，`generator.test.ts:236-238` 既有的 parity 测试继续 PASS（它们不填锚点，走旧口径）。

- [ ] **Step 6: 跑全量服务端验证**

Run: `cd server && npm run verify`
Expected: typecheck + 全量测试 + build 全部通过。若 `planSpec.test.ts` 因 `expandStage` 的 `Pick` widen 报类型错，说明调用处传的是精确对象字面量 —— 在该字面量里补 `termWeekOneStart: ''` 即可，不要回退类型。

- [ ] **Step 7: 提交**

```bash
git add server/src/modules/planning/aiGenerator.ts server/src/modules/planning/document.ts server/src/modules/planning/generator.test.ts
git commit -m "$(cat <<'EOF'
fix(planning): align generated week parity with the term calendar

有校历锚点时按校历算奇偶,与课表上的单双周对齐;无锚点保持阶段起点的旧口径。
简报文本里也把校历第 1 周告知模型,避免它自行按阶段起点理解奇偶。
EOF
)"
```

---

### Task 8: App 端 DTO

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt:645-651`（`BriefWindowDto` / `BriefAvailabilityDto` / `BriefCommitmentDto`）
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt:175-189`（`ProfileReq`）
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt:621-633`（`PlanBriefDto`）
- Test: `YanZhong/app/src/test/java/com/yanzhong/app/data/remote/CloudContractTest.kt`（追加）

**Interfaces:**
- Consumes: 无（纯数据类）。
- Produces:
  ```kotlin
  data class BriefCommitmentDto(
      val weekday: Int, val start: String, val end: String, val label: String,
      val weekParity: String? = null
  )
  ```
  `ProfileReq.termWeekOneStart: String = ""`；`PlanBriefDto.termWeekOneStart: String = ""`。全部带默认值，老服务端响应缺字段时仍能反序列化。

- [ ] **Step 1: 追加失败的测试**

`CloudContractTest.kt` 的既有约定（已核对）：`private val json = Json { ignoreUnknownKeys = true }` 用于解码，`private val encodeJson = Json { encodeDefaults = true }` 用于编码；已 import `assertEquals`、`assertTrue`、`org.junit.Test`。在类体末尾追加：

```kotlin
    @Test
    fun `占用带单双周标记时能反序列化`() {
        val dto = json.decodeFromString<BriefCommitmentDto>(
            """{"weekday":3,"start":"08:00","end":"09:40","label":"人工智能","weekParity":"odd"}"""
        )
        assertEquals("odd", dto.weekParity)
    }

    @Test
    fun `老服务端不带 weekParity 时回落到 null`() {
        val dto = json.decodeFromString<BriefCommitmentDto>(
            """{"weekday":3,"start":"08:00","end":"09:40","label":"人工智能"}"""
        )
        assertEquals(null, dto.weekParity)
    }

    @Test
    fun `档案请求体带上校历第 1 周与占用的单双周`() {
        val req = ProfileReq(
            targetType = "考研", examDate = "2026-12-19", studyWindows = listOf("上午"),
            foundation = "一般", weakSubjects = listOf("数学"),
            fixedCommitments = listOf(
                BriefCommitmentDto(weekday = 3, start = "08:00", end = "09:40", label = "人工智能", weekParity = "even")
            ),
            termWeekOneStart = "2026-09-07",
            commitmentsConfirmed = true
        )
        val encoded = encodeJson.encodeToString(ProfileReq.serializer(), req)
        assertTrue(encoded.contains("\"termWeekOneStart\":\"2026-09-07\""), encoded)
        assertTrue(encoded.contains("\"weekParity\":\"even\""), encoded)
    }

    @Test
    fun `老档案响应缺 termWeekOneStart 时回落到空串`() {
        val dto = json.decodeFromString<PlanBriefDto>(
            """{"summary":"","goals":[],"constraints":[],"focus":[],"materials":[],"notes":[],"examSubjects":[],"availability":[],"fixedCommitments":[],"availabilityConfirmed":true,"commitmentsConfirmed":true}"""
        )
        assertEquals("", dto.termWeekOneStart)
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest --tests 'com.yanzhong.app.data.remote.CloudContractTest' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```
Expected: 编译失败，`Unresolved reference: weekParity` / `No value passed for parameter 'termWeekOneStart'` 之类。

- [ ] **Step 3: 改三个数据类**

`Api.kt:650-651` 替换为：

```kotlin
@Serializable
data class BriefCommitmentDto(
    val weekday: Int,
    val start: String,
    val end: String,
    val label: String,
    /** 单双周:"odd"=单周 / "even"=双周 / null=每周都占。只在填了 termWeekOneStart 时生效 */
    val weekParity: String? = null
)
```

`Api.kt:175-189` 的 `ProfileReq` 里，在 `fixedCommitments` 之后、`availabilityConfirmed` 之前插入：

```kotlin
    /** 校历第 1 周的周一(YYYY-MM-DD);空串表示不启用单双周 */
    val termWeekOneStart: String = "",
```

`Api.kt:621-633` 的 `PlanBriefDto` 里，在 `fixedCommitments` 之后插入：

```kotlin
    val termWeekOneStart: String = "",
```

- [ ] **Step 4: 跑测试确认它通过**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest --tests 'com.yanzhong.app.data.remote.CloudContractTest' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt YanZhong/app/src/test/java/com/yanzhong/app/data/remote/CloudContractTest.kt
git commit -m "$(cat <<'EOF'
feat(api): carry weekParity and termWeekOneStart in cloud DTOs

全部带默认值,老服务端响应缺字段时仍能反序列化;weekParity 为 null 表示每周都占。
EOF
)"
```

---

### Task 9: App 端 ViewModel 状态与载荷

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt:36-74`（`OnboardingUiState`）
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt:171-191`（`beginSession`）
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt:219-240`（`loadInternal` 回填）
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt:300-312`（`saveProfileOnly` 的 `ProfileReq`）
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt:495-526`（`addCommitment`）
- Test: `YanZhong/app/src/test/java/com/yanzhong/app/ui/onboarding/OnboardingCommitmentParityTest.kt`（新建）

**Interfaces:**
- Consumes: Task 8 的 `BriefCommitmentDto(weekday, start, end, label, weekParity)`、`ProfileReq.termWeekOneStart`；既有 `normalizeClock(String): String?`（同文件内私有函数，`addCommitment:497-498` 已在用）、`MAX_COMMITMENTS = 20`（`:133`）。
- Produces:
  - `OnboardingUiState.termWeekOneStart: String = ""`
  - `fun addCommitment(weekday: Int, start: String, end: String, label: String, weekParity: String? = null): Boolean`
  - `fun setTermWeekOneStart(raw: String)`
  - `internal fun normalizeWeekParity(raw: String?): String?` —— 纯函数，`"odd"`/`"even"` 原样返回，其它（含 `null`、`""`、`"单周"`、`"ODD"` 之外的任何值）返回 `null`。
  - `internal fun parityLabel(weekParity: String?): String` —— 纯函数，`"odd"`→`"单周"`，`"even"`→`"双周"`，其它→`"每周"`。

- [ ] **Step 1: 写失败的测试**

新建 `YanZhong/app/src/test/java/com/yanzhong/app/ui/onboarding/OnboardingCommitmentParityTest.kt`：

```kotlin
package com.yanzhong.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingCommitmentParityTest {

    @Test
    fun `单双周标记只认 odd 与 even`() {
        assertEquals("odd", normalizeWeekParity("odd"))
        assertEquals("even", normalizeWeekParity("even"))
    }

    @Test
    fun `服务端识别出的中文或大写标记一律退化成每周都占`() {
        // OCR 提示词要求输出 odd/even,但模型偶尔会写「单周」;
        // 非法值退化成 null(每周都占)比整条占用消失安全
        assertEquals(null, normalizeWeekParity("单周"))
        assertEquals(null, normalizeWeekParity("ODD"))
        assertEquals(null, normalizeWeekParity(""))
        assertEquals(null, normalizeWeekParity(null))
    }

    @Test
    fun `界面上显示人话而不是 odd_even`() {
        assertEquals("单周", parityLabel("odd"))
        assertEquals("双周", parityLabel("even"))
        assertEquals("每周", parityLabel(null))
        assertEquals("每周", parityLabel("单周"))
    }

    @Test
    fun `校历第 1 周只接受完整年月日`() {
        assertEquals("2026-09-07", normalizeTermAnchor("2026-09-07"))
        assertEquals("2026-09-07", normalizeTermAnchor("  2026-09-07  "))
        assertEquals("", normalizeTermAnchor("2026-9-7"))
        assertEquals("", normalizeTermAnchor("下周一"))
        assertEquals("", normalizeTermAnchor(""))
        assertEquals("", normalizeTermAnchor("2026-02-30"))
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest --tests 'com.yanzhong.app.ui.onboarding.OnboardingCommitmentParityTest' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```
Expected: 编译失败，`Unresolved reference: normalizeWeekParity`。

- [ ] **Step 3: 写纯函数**

在 `OnboardingViewModel.kt` 的 `MAX_EXAM_SUBJECTS`（`:136`）之后插入：

```kotlin
/** 服务端与 OCR 约定的单双周取值;其它一律退化成「每周都占」 */
private val WEEK_PARITY_VALUES = setOf("odd", "even")

/**
 * 收敛单双周标记:只认 odd/even,其它(含模型写成「单周」、大写、空串)都退化成 null。
 *
 * 为什么退化而不是丢弃整条:一门课标错单双周,最坏结果是那一周少算/多算 100 分钟;
 * 整条占用消失则会把它当成空闲,任务会被直接排进上课时间。
 */
internal fun normalizeWeekParity(raw: String?): String? =
    raw?.trim()?.takeIf { it in WEEK_PARITY_VALUES }

/** 界面上说人话:odd/even 是给服务端的,备考者看的是单周/双周/每周 */
internal fun parityLabel(weekParity: String?): String = when (normalizeWeekParity(weekParity)) {
    "odd" -> "单周"
    "even" -> "双周"
    else -> "每周"
}

/**
 * 校历第 1 周的周一。只接受完整的 YYYY-MM-DD 且是真实存在的日子,否则归一为空串
 * (空串 = 不启用单双周,服务端会保守按每周都占计算)。
 */
internal fun normalizeTermAnchor(raw: String): String {
    val text = raw.trim()
    if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(text)) return ""
    return try {
        LocalDate.parse(text).toString()
    } catch (e: DateTimeParseException) {
        ""
    }
}
```

`LocalDate` 与 `DateTimeParseException` 的 import 该文件已有（`:614`、`:615` 的 `validateExamDate` 在用），无需新增。

- [ ] **Step 4: 跑纯函数测试确认它通过**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest --tests 'com.yanzhong.app.ui.onboarding.OnboardingCommitmentParityTest' '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```
Expected: 4 个测试 PASS。

- [ ] **Step 5: 接进状态、载荷与增删逻辑**

`OnboardingUiState`（`:36-74`）里，在 `commitments` 之后插入：

```kotlin
    /** 校历第 1 周的周一(YYYY-MM-DD);空串表示不启用单双周,服务端按每周都占算 */
    val termWeekOneStart: String = "",
```

`beginSession()`（`:171-191`）的 `commitments = emptyList(),` 之后插入：

```kotlin
                termWeekOneStart = "",
```

`loadInternal()` 的 EDITING 分支（`:219-240`）里，`commitments = ...` 那一行之后插入：

```kotlin
                            termWeekOneStart = p?.brief?.termWeekOneStart?.takeIf { it.isNotBlank() }
                                ?: s.termWeekOneStart
```

`saveProfileOnly()` 的 `ProfileReq(...)`（`:300-312`）里，`fixedCommitments = s.commitments,` 之后插入：

```kotlin
                termWeekOneStart = s.termWeekOneStart,
```

`addCommitment`（`:495-526`）整段替换为：

```kotlin
    /**
     * 手动补一条固定占用(没传课表或课表上没印全的时候用)。
     * [start]/[end] 是用户敲的钟点,[weekParity] 是单双周标记(null = 每周都占)。
     * 这里只做格式与先后校验,不猜。
     * 返回是否真的记下,好让输入框只在成功时清空 —— 校验没过就留着让用户改。
     */
    fun addCommitment(weekday: Int, start: String, end: String, label: String, weekParity: String? = null): Boolean {
        val s = _ui.value
        val from = normalizeClock(start)
        val to = normalizeClock(end)
        val name = label.trim().take(16)
        val parity = normalizeWeekParity(weekParity)
        val error = when {
            name.isEmpty() -> "这条占用是什么事,写两个字就行(比如 上课)"
            from == null || to == null -> "时间按 8:00 或 08:00 这样填"
            from >= to -> "结束时间要晚于开始时间"
            s.commitments.size >= MAX_COMMITMENTS -> "固定占用最多 $MAX_COMMITMENTS 条"
            s.commitments.any {
                it.weekday == weekday && it.start == from && it.end == to && it.label == name
                    && normalizeWeekParity(it.weekParity) == parity
            } -> "这条已经记下了"
            else -> null
        }
        if (error != null) {
            _ui.update { it.copy(message = error, messageIsError = true) }
            return false
        }
        _ui.update {
            it.copy(
                commitments = it.commitments + BriefCommitmentDto(
                    weekday = weekday,
                    start = from!!,
                    end = to!!,
                    label = name,
                    weekParity = parity
                ),
                message = "",
                messageIsError = false
            )
        }
        return true
    }

    /** 单双周要靠校历第 1 周才能落到具体日期;非法输入归一为空串(= 不启用) */
    fun setTermWeekOneStart(raw: String) {
        val normalized = normalizeTermAnchor(raw)
        _ui.update {
            if (raw.isNotBlank() && normalized.isEmpty()) {
                it.copy(message = "校历第 1 周要写成 2026-09-07 这样(年月日全都要)", messageIsError = true)
            } else {
                it.copy(termWeekOneStart = normalized, message = "", messageIsError = false)
            }
        }
    }

    /** 占用列表上点单双周标记时轮换:每周 → 单周 → 双周 → 每周 */
    fun cycleCommitmentParity(index: Int) {
        _ui.update { s ->
            if (index !in s.commitments.indices) return@update s
            val current = normalizeWeekParity(s.commitments[index].weekParity)
            val next = when (current) {
                null -> "odd"
                "odd" -> "even"
                else -> null
            }
            val updated = s.commitments.toMutableList().also {
                it[index] = it[index].copy(weekParity = next)
            }
            s.copy(commitments = updated)
        }
    }
```

`parseTimetable()`（`:535-580`）无需改动：它把 `resp.fixedCommitments` 直接并进 `commitments`，parity 已在 DTO 里。只需确认去重键（`:556`）改成含 parity：

```kotlin
                        val merged = (s.commitments + resp.fixedCommitments)
                            .distinctBy { "${it.weekday}-${it.start}-${it.end}-${it.label}-${normalizeWeekParity(it.weekParity)}" }
                            .take(MAX_COMMITMENTS)
```

- [ ] **Step 6: 编译并跑全量 App 单测**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```
Expected: `BUILD SUCCESSFUL`。若别处调用了 `addCommitment` 的旧四参形式，因为新参数有默认值，不会报错。

- [ ] **Step 7: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt YanZhong/app/src/test/java/com/yanzhong/app/ui/onboarding/OnboardingCommitmentParityTest.kt
git commit -m "$(cat <<'EOF'
feat(onboarding): track week parity and term anchor in questionnaire state

非法 parity 退化成「每周都占」而不是丢弃整条占用;校历第 1 周只接受完整年月日。
占用列表可轮换单双周,识别结果的去重键含 parity。
EOF
)"
```

---

### Task 10: App 端界面

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingScreen.kt:497-526`（课表区标题与说明）
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingScreen.kt:528-544`（占用列表行）
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingScreen.kt:546-549`（手动补占用区）
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingScreen.kt:682-739`（`ManualCommitmentRow`）

**Interfaces:**
- Consumes: Task 9 的 `vm.setTermWeekOneStart(String)`、`vm.cycleCommitmentParity(Int)`、`addCommitment(weekday, start, end, label, weekParity)`、`state.termWeekOneStart`；Task 9 的 `parityLabel(String?)`；既有 `SegmentedChips(options, selectedIndex, onSelect)`（`OnboardingScreen.kt:689-693` 用法）、`FieldLabel(title, hint)`（`:779`）、`ListRow(title, trailing)`（`:533`）、`TextAction(text, onClick, danger)`（`:536`）、`SecondaryButton(text, onClick)`（`:518`）。
- Produces: 无对外接口。

- [ ] **Step 1: 改课表区的标题与说明，并在下方加校历第 1 周输入**

`OnboardingScreen.kt:497-498` 替换为：

```kotlin
    Spacer(Modifier.height(20.dp))
    FieldLabel(
        "有课表就传一张,我替你认",
        "认出来的上课时间会列在下面,和实际不符的直接删掉。单双周的课会标出来,但要生效得填下面那个开学周"
    )
```

在 `OnboardingScreen.kt:526`（`timetableNotice` 那个 `if` 块）之后、`if (state.commitments.isNotEmpty())`（`:528`）之前插入：

```kotlin
    Spacer(Modifier.height(16.dp))
    FieldLabel(
        "校历第 1 周是哪天(填那周的周一)",
        "单双周要靠它才能落到具体日期。不填也行,那样单双周的课会保守按每周都上算"
    )
    Spacer(Modifier.height(8.dp))
    var termAnchor by remember(state.termWeekOneStart) { mutableStateOf(state.termWeekOneStart) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = termAnchor,
            onValueChange = { termAnchor = it.take(10) },
            label = { Text("比如 2026-09-07") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.setTermWeekOneStart(termAnchor) }),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        SecondaryButton(
            text = "记下",
            onClick = { vm.setTermWeekOneStart(termAnchor) },
            modifier = Modifier.width(88.dp)
        )
    }
```

- [ ] **Step 2: 让占用列表显示单双周并可轮换**

`OnboardingScreen.kt:528-544` 的整个 `if (state.commitments.isNotEmpty()) { ... }` 块替换为：

```kotlin
    if (state.commitments.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        FieldLabel("已经记下的固定占用", "每周固定被占掉的时间,排课时不会往里塞任务。点「单周/双周/每周」可以切换")
        if (state.commitments.any { parityLabel(it.weekParity) != "每周" } && state.termWeekOneStart.isBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "有课标了单双周,但还没填校历第 1 周 —— 现在会保守按每周都上算,填了才分得开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        state.commitments.forEachIndexed { index, item ->
            ListRow(
                title = "周${WEEKDAY_LABELS[item.weekday - 1]} ${item.start}-${item.end}  ${item.label}",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextAction(
                            text = parityLabel(item.weekParity),
                            onClick = { vm.cycleCommitmentParity(index) }
                        )
                        TextAction(
                            text = "删掉",
                            onClick = { vm.removeCommitment(index) },
                            danger = true
                        )
                    }
                }
            )
        }
    }
```

- [ ] **Step 3: 给手动补占用加单双周选择**

`OnboardingScreen.kt:682-739` 的 `ManualCommitmentRow` 整段替换为（签名多一个 `weekParity` 回调参数）：

```kotlin
private fun ManualCommitmentRow(onAdd: (Int, String, String, String, String?) -> Boolean) {
    var weekday by remember { mutableStateOf(1) }
    var label by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    // 0 = 每周都占,1 = 单周,2 = 双周
    var parityIndex by remember { mutableStateOf(0) }
    val parityOptions = listOf("每周", "单周", "双周")
    val parityValue = listOf<String?>(null, "odd", "even")[parityIndex]

    Column {
        SegmentedChips(
            options = (1..7).map { "周${WEEKDAY_LABELS[it - 1]}" },
            selectedIndex = weekday - 1,
            onSelect = { weekday = it + 1 }
        )
        Spacer(Modifier.height(8.dp))
        SegmentedChips(
            options = parityOptions,
            selectedIndex = parityIndex,
            onSelect = { parityIndex = it }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(16) },
            label = { Text("是什么事") },
            placeholder = { Text("比如 上课") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = start,
                onValueChange = { start = it.take(5) },
                label = { Text("开始") },
                placeholder = { Text("08:00") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = end,
                onValueChange = { end = it.take(5) },
                label = { Text("结束") },
                placeholder = { Text("11:30") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            text = "记下这条",
            onClick = {
                // 只有真记下了才清空:校验没过(时间格式/先后顺序)留着让用户改,不白打一遍
                if (onAdd(weekday, start, end, label, parityValue)) {
                    label = ""
                    start = ""
                    end = ""
                }
            }
        )
    }
}
```

同步修改调用处 `OnboardingScreen.kt:549`：

```kotlin
    ManualCommitmentRow { weekday, start, end, label, parity -> vm.addCommitment(weekday, start, end, label, parity) }
```

并把 `:547` 的 `FieldLabel` 说明改为：

```kotlin
    FieldLabel("没有课表?手动补一条", "上课、上班、通勤这类每周固定占掉的时间,补几条就够;只在单周或双周上的课,上面选一下")
```

- [ ] **Step 4: 编译并跑全量 App 单测**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```
Expected: `BUILD SUCCESSFUL`，无测试失败。若报 `Unresolved reference: parityLabel`，检查 `OnboardingScreen.kt` 是否已 import 同包符号（同包无需 import，若报错说明函数被误放进 class 内）。

- [ ] **Step 5: 装到设备上按验收清单走一遍**

Run:
```bash
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:installDebug '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain -q"
```

验收清单（模拟器操作参照既有约定：`am start` 拉起、`uiautomator dump` 取 bounds）：

1. 上传一张含单双周课程的真实课表截图 → 识别出的占用列表里，单双周那几条的标记显示「单周」或「双周」，其余显示「每周」。
2. 不填校历第 1 周 → 列表上方出现提示「有课标了单双周,但还没填校历第 1 周…」。
3. 填 `2026-09-07` 点「记下」→ 提示消失；填 `2026-9-7` → 出现「校历第 1 周要写成 2026-09-07 这样」。
4. 点某条占用的「每周」标记 → 依次轮换为「单周」→「双周」→「每周」。
5. 手动补一条占用，选「双周」→ 列表出现该条且标记为「双周」。
6. 走完问卷提交 → 用测试账号查 `GET /profile`，确认返回的 `brief.termWeekOneStart` 与 `fixedCommitments[].weekParity` 与界面一致。
7. 重进问卷（`startManage`）→ 校历第 1 周与单双周标记被正确回填。
8. 生成一份计划，确认双周周三的净空闲比单周周三多出一节课的时长（对照首页/计划页当天的容量数字）。

- [ ] **Step 6: 提交**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingScreen.kt
git commit -m "$(cat <<'EOF'
feat(onboarding): let 备考者 mark odd/even week classes and set term anchor

占用列表可轮换单周/双周/每周,手动补占用带 parity 选择,课表区新增校历第 1 周输入。
标了单双周却没填锚点时就地提示,避免用户以为已经生效。
EOF
)"
```

---

## Self-Review

**Spec coverage（对照 spec 4.2 与第 6 节 B 部分验收）：**

| Spec 要求 | 对应任务 |
|---|---|
| `BriefCommitment.weekParity` / `PlanBrief.termWeekOneStart` 类型 | Task 2 Step 3 |
| `weekParityForDate()` 唯一奇偶判定，锚点归一到周一，非法/早于开学周返回 `null` | Task 1 |
| `daySchedule()` 按 parity 过滤，`netAvailableMinutes`/`freeSlots` 自动继承 | Task 3 |
| 假期区间行为不变 | Task 3 Step 1 测试 `假期口径不变` |
| `normalizeCommitments` 非法 parity 只丢属性不丢整条 | Task 2 Step 4 + Task 6 Step 3 测试 |
| `normalizeBrief` 非法锚点归一为空串 | Task 2 Step 4 |
| `mergeBrief` 去重键含 parity、锚点补丁非空优先 | Task 2 Step 5 |
| `withCommitments` 去重键含 parity | Task 5 Step 5 |
| `canonicalBrief` 哈希稳定（旧简报逐字节不变） | Task 4 |
| OCR 输出结构化 `weekParity`、不得编造 | Task 6 |
| 输出侧统一锚点、提示词与简报文本告知模型 | Task 7 |
| `schemas.ts` 入参校验（`weekParity`、`termWeekOneStart`） | Task 5 Step 3 |
| `BriefPatchInput` 类型加 parity | Task 5 Step 5 |
| `applyProfileFacts` 透传锚点 | Task 5 Step 5 |
| App DTO（`BriefCommitmentDto`/`ProfileReq`/`PlanBriefDto`） | Task 8 |
| App ViewModel 状态、载荷、回填、增删 | Task 9 |
| App UI（parity 三态、校历第 1 周输入、文案改成实话） | Task 10 |
| 验收 1（无锚点时旧行为不破） | Task 3 测试 `没填锚点时保守按每周都占`、Task 7 测试 `没填校历锚点时保持阶段起点的旧口径` |
| 验收 2（2026-09-14 扣、2026-09-21 不扣） | Task 3 测试 `双周的课只在偶周扣掉容量`（用 09-16 / 09-23 两个周三，与 spec 的 09-14 / 09-21 同为第 2、3 周） |
| 验收 3（旧简报 briefHash 不变） | Task 4 测试 `旧简报不含任何单双周痕迹` |
| 验收 4（OCR 返回 parity 且 App 能看到标记） | Task 6 + Task 10 Step 5 清单第 1 项 |

**与 spec 的一处偏离（已在 Task 6 Interfaces 说明）：** spec 4.2 提到 `TimetableResult` 可增加 `termWeekOneStart`，本计划**不实现**该字段 —— 课表图上通常没有校历日期，让模型去推违反 `timetable.ts:40` 既有的「严禁编造」，猜错会让整张课表错位一周。锚点改由 App 端单独收集（Task 10 Step 1）。spec 需同步修订该句。

**Placeholder scan：** 无 TBD/TODO，所有代码步骤均给出完整可粘贴代码，所有 Run 步骤均给出完整命令与预期结果。两处对外部符号的说明已实地核对：`GeneratedStage` 的真实字段（`generator.ts:40-51`，无 `weeklyMinutes`）与 `AiStage` 非导出（`aiGenerator.ts:71`）决定了 Task 7 的测试必须内联传参；`CloudContractTest` 的 `json` / `encodeJson` 两个实例名决定了 Task 8 用哪个做编解码。`expandStageFromVariants` 不涉及奇偶也已核对（`weekParity` 在 `aiGenerator.ts` 的出现位置全在模板展开路径）。

**Type consistency：**
- `weekParityForDate(termWeekOneStart: string, date: Date): 'odd' | 'even' | null` —— Task 1 定义，Task 3（`daySchedule`）、Task 7（`expandStage`）调用，签名一致。
- `BriefCommitment.weekParity?: 'odd' | 'even'` —— Task 2 定义，Task 3/4/5/6 消费。
- `PlanBrief.termWeekOneStart?: string` —— Task 2 定义；Task 3 的 `BriefSchedule` widen、Task 4 的 `canonicalBrief`、Task 5 的 `applyProfileFacts`、Task 7 的 `Pick` widen 均引用同名。
- `normalizeWeekParity(String?): String?` / `parityLabel(String?): String` / `normalizeTermAnchor(String): String` —— Task 9 Step 3 定义，Task 9 Step 5 与 Task 10 Step 2 调用，签名一致。
- `addCommitment(weekday: Int, start: String, end: String, label: String, weekParity: String? = null): Boolean` —— Task 9 Step 5 定义，Task 10 Step 3 的 `ManualCommitmentRow` 回调类型 `(Int, String, String, String, String?) -> Boolean` 与之匹配。
- `cycleCommitmentParity(index: Int)` / `setTermWeekOneStart(raw: String)` —— Task 9 定义，Task 10 调用。
- `seedAvailability` 属另一份计划（`2026-11-08-onboarding-availability-seeding.md`），本计划不引用；两份计划都改 `OnboardingViewModel.kt` 与 `OnboardingScreen.kt`，**必须串行执行，先 A 后 B**，否则 Task 9/10 的行号会漂。

**已知风险（执行时注意）：**
- Task 3 widen `BriefSchedule` 后，`adjust.ts`、`eval.ts`、`generator.ts`、`planBudget.ts` 等所有 `netAvailableMinutes` 调用点的实参类型都要能兼容（`termWeekOneStart` 是可选属性，结构化赋值不会失败，但精确对象字面量会触发 excess/missing property 检查）。Task 3 Step 5 的 `npm run verify` 是这道关。
- Task 4 的第 1 条测试是本次改动的护栏：它必须**先** PASS 才算 Task 2 没把空串泄漏进序列化。若 FAIL，回 Task 2 修，不要在 Task 4 里放宽断言。
- Task 7 改了生成器的奇偶口径。对**已填锚点**的用户，重新生成的计划会与旧版本不同（这是修复，不是回归）；对未填锚点的用户行为完全不变。发布前应在测试账号上对比一次生成结果。
- 行号以 2026-11-08 的 `main`（`199f4ee`）为基准。执行前若 `main` 已前进，先 Read 目标文件确认锚点代码，再按本计划的「替换为」内容落地。
