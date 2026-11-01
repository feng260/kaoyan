# 服务端价值闭环实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把服务端已有但客户端没露出的能力接成一条闭环：账号与数据安全中心（同步状态、云端导出/备份/恢复、改密、忘记密码、全设备登出）+ 服务端主动服务（计划体检、本周执行、未来负荷、一键去调整）+ 三处高频交互修正。

**Architecture:** 服务端新增纯计算模块 `planning/checkup.ts`，由现有 planning service 取数后调用，路由只负责参数归一与通知开关；客户端新增 `CloudDataViewModel` 管理导出/恢复的安全流程，`CloudSyncViewModel` 扩展同步状态与账号安全动作，首页/统计页共享同一份体检 DTO。恢复流程严格串行：解析预览 → 二次确认 → 自动快照 → 停同步 → 服务端恢复 → 本地镜像 → 恢复同步。

**Tech Stack:** 服务端 Koa + Prisma + MySQL + node:test；客户端 Kotlin + Jetpack Compose Material3 + Room + DataStore + Retrofit/Kotlinx Serialization；构建 Android Gradle（PowerShell 调 `gradlew.bat`）。

**Spec:** `docs/superpowers/specs/2026-11-01-server-value-loop-design.md`

**工作目录:** `F:\kaoyan-app-prd`

## Global Constraints

- 当前分支 `main`；不新建分支、不合并。
- **本计划执行期间不要 `git commit`、`git push`、不要部署服务端**，除非用户明确要求。每个任务末尾只做本地验证。
- 服务端改动必须向后兼容：旧客户端不传 `tzOffset` 时按 480 处理；响应只增字段，不改旧字段含义。
- 恢复备份只覆盖学习数据与设置；**备考档案与 AI 计划不得被恢复覆盖**。
- 服务端恢复是破坏性的，必须二次确认、必须先做恢复前快照，快照失败就中止。
- 不记录、不提交服务器 SSH 密码；测试账号 `yztest_1028a7` / `Throw2026test` 不得写进仓库或日志。
- 只检查/修改一次性测试账号的数据，不查看其他真实用户数据。
- 不提交 `YanZhong/README.md` 的既有改写。
- 客户端构建使用 PowerShell：`.\gradlew.bat` + `-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK`；MSYS 的 `./gradlew` 在本机不可用。
- 现有历史缺陷（退出确认、严格模式逃逸、429 文案）已经修复，不要重做。

## 对 spec 的实现修正

以下 8 处已在写计划时按真实代码修正，实施时以本计划为准：

1. 恢复流程用 `dataSyncer.stop()` / `start()`，不是 `pause()` / `resume()`。
2. 服务端 `/backup/restore` 只清学习数据与设置，不清 `profile` / `plan`；恢复预览文案必须说明“档案与 AI 计划不变”。
3. 密码接口真实路径是 `/api/v1/auth/password/change|forgot|reset`。
4. 健康体检在“无问题时”返回 `suggestion: ''`，否则会破坏 `pullCheckupOnce()` 的“空串=不提醒”判断；客户端自行显示“计划节奏正常”。
5. 首页体检卡位置：倒计时 → 阶段 → 体检 → 进度 → 待办 → 节奏 → 复盘（平板右栏固定节奏，左栏为倒计时 → 阶段 → 体检 → 进度 → 待办 → 复盘）。
6. `WeeklyReviewSheet` 在 `ui/home/ReviewCard.kt`，不在统计页；服务端执行提示加在该 Sheet 的“本周自动盘点”下方。
7. 恢复接口的 `resources.*.rejected` 是 `[{ index, reasons }]` 数组，不是数字；客户端 DTO 必须按数组建模。
8. 首页体检卡的“当天关闭”用独立 `SharedPreferences`（`plan_checkup`），不往 `SettingsStore` / DataStore 里塞一次性 UI 状态，避免污染同步设置。

---

## 文件地图

### 服务端新增

| 文件 | 责任 |
|---|---|
| `server/src/modules/planning/checkup.ts` | 体检纯计算：本地周边界、本周完成、专注分钟、未来 7 天负荷、建议文案、参数归一 |
| `server/src/modules/planning/checkup.test.ts` | 纯函数单测 |
| `server/src/shared/health.ts` | `/healthz` 负载与功能开关 |
| `server/src/shared/health.test.ts` | 健康检查单测 |

### 服务端修改

| 文件 | 改动 |
|---|---|
| `server/src/modules/planning/service.ts` | `PlanningDb` 增加 `pomodoroSession`；`checkup()` 接收 `tzOffset/now` 并调用纯计算 |
| `server/src/modules/planning/routes.ts` | 体检路由归一 `tzOffset`、`notify`，按开关触发提醒 |
| `server/src/modules/planning/service.test.ts` | 假库支持 sessions/gte；更新旧断言；新增体检汇总用例 |
| `server/src/app.ts` | `/healthz` 使用 `healthPayload()` |

### 客户端新增

| 文件 | 责任 |
|---|---|
| `ui/cloud/CloudFormat.kt` | 同步时间文案 |
| `ui/cloud/CloudDataRules.kt` | 备份大小/结构预检、摘要、确认词 |
| `ui/cloud/CloudDataViewModel.kt` | 导出、下载、恢复前快照、二次确认、停/启同步、恢复 |
| `ui/account/SyncStatusCard.kt` | 三态同步卡 + 立即同步 |
| `ui/account/AccountDataSection.kt` | 云端数据分组、恢复预览/确认弹窗 |
| `ui/account/AccountSecuritySection.kt` | 修改密码、登出全部设备 |
| `ui/auth/PasswordRules.kt` | 改密/重置密码校验 |
| `ui/auth/PasswordResetBus.kt` | 深链 token 单次分发 |
| `ui/auth/PasswordResetViewModel.kt` | 重置密码提交 |
| `ui/auth/PasswordResetHost.kt` | 全局重置密码弹窗 |
| `ui/home/CheckupViewModel.kt` | 拉取体检（`notify=0`）、按本地日关闭 |
| `ui/home/PlanCheckupCard.kt` | 首页体检卡 |
| `ui/home/CheckupFormat.kt` | 体检卡文案（客户端侧） |
| `ui/stats/ServerExecutionCard.kt` | 统计页“本周执行”卡 |

### 客户端修改

| 文件 | 改动 |
|---|---|
| `data/remote/Api.kt` | 体检 DTO/查询参数、`HealthResp`、`RestoreResp` |
| `data/remote/SyncModels.kt` | 备份上下行增加 `settings` |
| `data/remote/DataSyncer.kt` | 体检带时区；同步错误用 `apiErrorMessage()` |
| `data/repo/StudyRepository.kt` | 导出备份带设置（可选参数） |
| `util/TimeUtils.kt` | 本地时区偏移 |
| `ui/cloud/CloudSyncViewModel.kt` | 同步状态、手动同步、改密、全设备登出、统一清会话 |
| `ui/account/AccountScreen.kt` | 新分组与 SAF 入口 |
| `ui/auth/LoginViewModel.kt` / `LoginScreen.kt` | 忘记密码 + 邮件能力探测 |
| `MainActivity.kt` / `AndroidManifest.xml` | `yanzhong://password-reset` 深链 |
| `ui/nav/AppNav.kt` | 面谈路由增加 `prefill` |
| `ui/plan/PlanInterviewScreen.kt` | 预填输入框 |
| `ui/home/HomeScreen.kt` | 体检卡、顺序调整、周复盘服务端提示 |
| `ui/home/ReviewCard.kt` | `WeeklyReviewSheet` 增加 `serverHint` |
| `ui/stats/StatsViewModel.kt` / `StatsScreen.kt` | 服务端执行卡与表头文案 |
| `ui/plan/PlanViewModel.kt` / `PlanScreen.kt` | READY 态错误横幅 |
| `ui/mine/MineScreen.kt` | 三个编辑弹窗滚动 + 70% 高度上限 |

### 客户端测试新增

`app/src/test/java/com/yanzhong/app/` 下：
- `data/remote/CloudContractTest.kt`
- `ui/cloud/CloudFormatTest.kt`
- `ui/cloud/CloudDataRulesTest.kt`
- `ui/home/CheckupFormatTest.kt`
- `ui/auth/PasswordRulesTest.kt`
- `util/TimeUtilsTest.kt`（追加用例）

---

## Task 1: 服务端体检纯计算模块

**Files:**
- Create: `server/src/modules/planning/checkup.ts`
- Test: `server/src/modules/planning/checkup.test.ts`

**Interfaces:**
- Consumes: `PlanBrief`（`./document`）、`dailyCapacity`（`./adjust`）。
- Produces: `CheckupSummary`、`CheckupItem`、`CheckupSession`、`CheckupOverloadDay`、`DEFAULT_TZ_OFFSET`、`normalizeTzOffset(raw: unknown): number`、`shouldNotifyPlanReminder(raw: unknown): boolean`、`emptyCheckup(): CheckupSummary`、`buildCheckup(input): CheckupSummary`。

- [ ] **Step 1: 写失败测试**

新建 `server/src/modules/planning/checkup.test.ts`：

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeBrief } from './document'
import {
  buildCheckup, emptyCheckup, normalizeTzOffset, shouldNotifyPlanReminder,
  DEFAULT_TZ_OFFSET,
} from './checkup'

const confirmedBrief = () => normalizeBrief({
  examSubjects: [],
  availability: [1, 2, 3, 4, 5, 6, 7].map(weekday => ({
    weekday, windows: [{ start: '19:00', end: '22:00' }],
  })),
  fixedCommitments: [],
  availabilityConfirmed: true,
  commitmentsConfirmed: true,
})

test('normalizeTzOffset 非法值回退 480', () => {
  assert.equal(normalizeTzOffset('480'), 480)
  assert.equal(normalizeTzOffset('-300'), -300)
  assert.equal(normalizeTzOffset('abc'), DEFAULT_TZ_OFFSET)
  assert.equal(normalizeTzOffset(900), DEFAULT_TZ_OFFSET)
  assert.equal(normalizeTzOffset(undefined), DEFAULT_TZ_OFFSET)
})

test('notify=0/false 时不触发提醒', () => {
  assert.equal(shouldNotifyPlanReminder(undefined), true)
  assert.equal(shouldNotifyPlanReminder('1'), true)
  assert.equal(shouldNotifyPlanReminder('0'), false)
  assert.equal(shouldNotifyPlanReminder(0), false)
  assert.equal(shouldNotifyPlanReminder('false'), false)
})

test('buildCheckup 汇总本周完成、上周专注与未来负荷', () => {
  const summary = buildCheckup({
    now: new Date('2026-03-11T12:00:00.000Z'), // 周三
    tzOffset: 0,
    brief: confirmedBrief(),
    items: [
      { planDate: '2026-03-09', minutes: 60, status: 'done' },
      { planDate: '2026-03-10', minutes: 90, status: 'pending' },
      { planDate: '2026-03-11', minutes: 30, status: 'pending' },
      { planDate: '2026-03-12', minutes: 300, status: 'pending' },
    ],
    sessions: [
      { startedAt: Date.parse('2026-03-09T20:00:00.000Z'), durationMin: 50, valid: true, isDeleted: false },
      { startedAt: Date.parse('2026-03-10T20:00:00.000Z'), durationMin: 25, valid: true, isDeleted: false },
      { startedAt: Date.parse('2026-03-04T20:00:00.000Z'), durationMin: 40, valid: true, isDeleted: false },
      { startedAt: Date.parse('2026-03-09T21:00:00.000Z'), durationMin: 99, valid: false, isDeleted: false },
    ],
  })
  assert.equal(summary.weekPlanTotal, 4)
  assert.equal(summary.weekPlanDone, 1)
  assert.equal(summary.weekFocusMinutes, 75)
  assert.equal(summary.lastWeekFocusMinutes, 40)
  assert.equal(summary.weekActiveDays, 2)
  assert.equal(summary.overdueCount, 1)
  assert.equal(summary.behindMinutes, 90)
  assert.equal(summary.next7dPlannedMinutes, 300)
  assert.equal(summary.next7dCapacityMinutes, 7 * 180)
  assert.equal(summary.overloadedDays.length, 1)
  assert.equal(summary.overloadedDays[0].date, '2026-03-12')
  assert.equal(summary.overloadedDays[0].planned, 300)
  assert.equal(summary.overloadedDays[0].capacity, 180)
  assert.ok(summary.suggestion.includes('过去'))
  assert.ok(summary.suggestion.includes('超过'))
  assert.ok(summary.suggestion.endsWith('去调整一下。'))
})

test('时区偏移决定本地日:UTC 晚间在东八区算次日', () => {
  const summary = buildCheckup({
    now: new Date('2026-03-11T18:00:00.000Z'), // 东八区 3/12 02:00
    tzOffset: 480,
    brief: confirmedBrief(),
    items: [
      { planDate: '2026-03-11', minutes: 60, status: 'pending' },
      { planDate: '2026-03-12', minutes: 30, status: 'pending' },
    ],
    sessions: [
      // UTC 3/11 16:30 = 本地 3/12 00:30,必须归到本周四
      { startedAt: Date.parse('2026-03-11T16:30:00.000Z'), durationMin: 25, valid: true, isDeleted: false },
    ],
  })
  assert.equal(summary.overdueCount, 1)
  assert.equal(summary.behindMinutes, 60)
  assert.equal(summary.weekPlanTotal, 2)
  assert.equal(summary.weekFocusMinutes, 25)
  assert.equal(summary.weekActiveDays, 1)
})

test('健康时 suggestion 必须为空串（保通知闸门）', () => {
  const summary = buildCheckup({
    now: new Date('2026-03-11T12:00:00.000Z'),
    tzOffset: 0,
    brief: confirmedBrief(),
    items: [{ planDate: '2026-03-11', minutes: 60, status: 'pending' }],
    sessions: [],
  })
  assert.equal(summary.overdueCount, 0)
  assert.equal(summary.overloadedDays.length, 0)
  assert.equal(summary.suggestion, '')
})

test('未确认空闲时不判超载', () => {
  const brief = confirmedBrief()
  brief.availabilityConfirmed = false
  const summary = buildCheckup({
    now: new Date('2026-03-11T12:00:00.000Z'),
    tzOffset: 0,
    brief,
    items: [{ planDate: '2026-03-12', minutes: 600, status: 'pending' }],
    sessions: [],
  })
  assert.equal(summary.overloadedDays.length, 0)
  assert.equal(summary.suggestion, '')
})

test('emptyCheckup 返回完整零值', () => {
  assert.deepEqual(emptyCheckup(), {
    behindMinutes: 0, overdueCount: 0, suggestion: '',
    weekPlanDone: 0, weekPlanTotal: 0, weekFocusMinutes: 0,
    lastWeekFocusMinutes: 0, weekActiveDays: 0,
    next7dPlannedMinutes: 0, next7dCapacityMinutes: 0, overloadedDays: [],
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

PowerShell（工作目录 `F:\kaoyan-app-prd\server`）：

```powershell
npm test -- --test-name-pattern "buildCheckup|时区偏移|normalizeTzOffset|notify=0|健康时|未确认空闲|emptyCheckup"
```

Expected: FAIL，提示找不到模块 `./checkup`。

- [ ] **Step 3: 实现纯计算模块**

新建 `server/src/modules/planning/checkup.ts`：

```ts
import type { PlanBrief } from './document'
import { dailyCapacity } from './adjust'

/** 用户本地时区相对 UTC 的偏移(分钟);东八区 = 480 */
export const DEFAULT_TZ_OFFSET = 480
const MIN_TZ_OFFSET = -840
const MAX_TZ_OFFSET = 840
const DAY_MS = 24 * 60 * 60 * 1000

export interface CheckupItem {
  planDate: string | Date
  minutes: number | null
  status: string
}

export interface CheckupSession {
  startedAt: number | bigint
  durationMin: number | null
  valid: boolean
  isDeleted: boolean
}

export interface CheckupOverloadDay {
  date: string
  planned: number
  capacity: number
}

export interface CheckupSummary {
  behindMinutes: number
  overdueCount: number
  suggestion: string
  weekPlanDone: number
  weekPlanTotal: number
  weekFocusMinutes: number
  lastWeekFocusMinutes: number
  weekActiveDays: number
  next7dPlannedMinutes: number
  next7dCapacityMinutes: number
  overloadedDays: CheckupOverloadDay[]
}

export function normalizeTzOffset(raw: unknown): number {
  const value = typeof raw === 'number' ? raw
    : typeof raw === 'string' && raw.trim() !== '' ? Number(raw)
      : NaN
  if (!Number.isFinite(value) || value < MIN_TZ_OFFSET || value > MAX_TZ_OFFSET) {
    return DEFAULT_TZ_OFFSET
  }
  return Math.trunc(value)
}

export function shouldNotifyPlanReminder(raw: unknown): boolean {
  if (raw === undefined || raw === null || raw === '') return true
  const value = String(raw).trim().toLowerCase()
  return value !== '0' && value !== 'false'
}

export function emptyCheckup(): CheckupSummary {
  return {
    behindMinutes: 0,
    overdueCount: 0,
    suggestion: '',
    weekPlanDone: 0,
    weekPlanTotal: 0,
    weekFocusMinutes: 0,
    lastWeekFocusMinutes: 0,
    weekActiveDays: 0,
    next7dPlannedMinutes: 0,
    next7dCapacityMinutes: 0,
    overloadedDays: [],
  }
}

function shiftDay(day: string, days: number): string {
  return new Date(Date.parse(`${day}T00:00:00.000Z`) + days * DAY_MS).toISOString().slice(0, 10)
}

/** 周一=0 … 周日=6 */
function weekdayIndex(day: string): number {
  return (new Date(`${day}T00:00:00.000Z`).getUTCDay() + 6) % 7
}

function mondayOf(day: string): string {
  return shiftDay(day, -weekdayIndex(day))
}

function localDay(epochMs: number, tzOffset: number): string {
  return new Date(epochMs + tzOffset * 60_000).toISOString().slice(0, 10)
}

function dayOf(value: string | Date): string {
  return value instanceof Date ? value.toISOString().slice(0, 10) : String(value).slice(0, 10)
}

function inRange(day: string, from: string, to: string): boolean {
  return day >= from && day <= to
}

function issueSuggestion(summary: CheckupSummary): string {
  const clauses: string[] = []
  if (summary.overdueCount > 0) {
    clauses.push(`有 ${summary.overdueCount} 项、约 ${summary.behindMinutes} 分钟的任务落在过去还没完成`)
  }
  if (summary.overloadedDays.length > 0) {
    clauses.push(`接下来 ${summary.overloadedDays.length} 天安排超过了可用时间`)
  }
  if (clauses.length === 0) return ''
  if (clauses.length === 1) {
    if (summary.weekPlanTotal >= 3) {
      const rate = Math.round(summary.weekPlanDone / summary.weekPlanTotal * 100)
      clauses.push(`本周已按计划完成 ${rate}%`)
    } else if (summary.weekFocusMinutes > 0) {
      clauses.push(`本周已专注 ${summary.weekFocusMinutes} 分钟`)
    }
  }
  return `${clauses.slice(0, 2).join('；')}。去调整一下。`
}

export function buildCheckup(input: {
  now: Date
  tzOffset: number
  items: CheckupItem[]
  sessions: CheckupSession[]
  brief: PlanBrief
}): CheckupSummary {
  const { now, items, sessions, brief } = input
  const tzOffset = normalizeTzOffset(input.tzOffset)
  const today = localDay(now.getTime(), tzOffset)
  const weekStart = mondayOf(today)
  const weekEnd = shiftDay(weekStart, 6)
  const lastWeekStart = shiftDay(weekStart, -7)
  const lastWeekEnd = shiftDay(weekStart, -1)
  const nextStart = shiftDay(today, 1)
  const nextEnd = shiftDay(today, 7)

  const summary = emptyCheckup()
  const overdue = items.filter(item => item.status !== 'done' && dayOf(item.planDate) < today)
  summary.overdueCount = overdue.length
  summary.behindMinutes = overdue.reduce((sum, item) => sum + Number(item.minutes ?? 0), 0)

  const plannedByDay = new Map<string, number>()
  for (const item of items) {
    const day = dayOf(item.planDate)
    if (inRange(day, weekStart, weekEnd)) {
      summary.weekPlanTotal++
      if (item.status === 'done') summary.weekPlanDone++
    }
    if (inRange(day, nextStart, nextEnd)) {
      const minutes = Number(item.minutes ?? 0)
      summary.next7dPlannedMinutes += minutes
      plannedByDay.set(day, (plannedByDay.get(day) ?? 0) + minutes)
    }
  }

  const activeDays = new Set<string>()
  for (const session of sessions) {
    if (!session.valid || session.isDeleted) continue
    const day = localDay(Number(session.startedAt), tzOffset)
    const minutes = Number(session.durationMin ?? 0)
    if (inRange(day, weekStart, weekEnd)) {
      summary.weekFocusMinutes += minutes
      if (minutes > 0) activeDays.add(day)
    } else if (inRange(day, lastWeekStart, lastWeekEnd)) {
      summary.lastWeekFocusMinutes += minutes
    }
  }
  summary.weekActiveDays = activeDays.size

  for (let offset = 1; offset <= 7; offset++) {
    const day = shiftDay(today, offset)
    summary.next7dCapacityMinutes += dailyCapacity(brief, day)
  }
  if (brief.availabilityConfirmed) {
    for (let offset = 1; offset <= 7; offset++) {
      const day = shiftDay(today, offset)
      const capacity = dailyCapacity(brief, day)
      const planned = plannedByDay.get(day) ?? 0
      if (capacity > 0 && planned > capacity) {
        summary.overloadedDays.push({ date: day, planned, capacity })
      }
    }
  }

  summary.suggestion = issueSuggestion(summary)
  return summary
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
npm test -- --test-name-pattern "buildCheckup|时区偏移|normalizeTzOffset|notify=0|健康时|未确认空闲|emptyCheckup"
```

Expected: 全部 PASS。

---

## Task 2: 服务端体检接线（service + route + 假库）

**Files:**
- Modify: `server/src/modules/planning/service.ts`（`PlanningDb`、`checkup`）
- Modify: `server/src/modules/planning/routes.ts`（体检路由）
- Modify: `server/src/modules/planning/service.test.ts`（假库、旧断言、新用例）

**Interfaces:**
- Consumes: Task 1 的 `buildCheckup/emptyCheckup/normalizeTzOffset/DEFAULT_TZ_OFFSET`。
- Produces: `PlanningService.checkup(userGuid, options?: { tzOffset?: number; now?: Date }): Promise<CheckupSummary>`；`PlanningDb.pomodoroSession.findMany`。

- [ ] **Step 1: 写失败测试（假库先支持 sessions）**

在 `service.test.ts` 顶部 import 改为：

```ts
import { createPlanningService, overdueSuggestion, slimToggleResponse, PlanningDb } from './service'
import { emptyCheckup } from './checkup'
```

把 `createFakeDb` 的 state 增加 sessions：

```ts
  const state = {
    profiles: [] as Row[],
    plans: [] as Row[],
    stages: [] as Row[],
    items: [] as Row[],
    adjustments: [] as Row[],
    sessions: [] as Row[],
  }
```

`snapshot()` / `restore()` 增加：

```ts
    sessions: structuredClone(state.sessions),
```
```ts
    state.sessions = snap.sessions
```

`whereMatch` 增加 `gte` 支持（保留原 `in`）：

```ts
  const whereMatch = (row: Row, where: Row) =>
    Object.entries(where).every(([key, value]) => {
      if (value && typeof value === 'object') {
        if (Array.isArray((value as Row).in)) {
          return (value as Row).in.includes(row[key])
        }
        if ((value as Row).gte !== undefined) {
          return row[key] >= (value as Row).gte
        }
      }
      return row[key] === value
    })
```

在 `planAdjustment` 之后、`$transaction` 之前插入：

```ts
    pomodoroSession: {
      findMany: async ({ where }: any = {}) =>
        state.sessions.filter(r => !where || whereMatch(r, where)),
    },
```

保留原测试 `checkup:数出过去未完成的任务;没有计划返回全零` 的前半段（逾期计数）不动，只把无计划断言的三字段对象改成完整零值：

```ts
  const none = await serviceWith(createFakeDb()).checkup('other-guid')
  assert.deepEqual(none, emptyCheckup())
```

原测试末尾的 `assert.equal(overdueSuggestion(0, 0), '')` 保留。

在文件末尾追加：

```ts
test('checkup 汇总本周完成、上周专注与未来负荷', async () => {
  const fake = createFakeDb()
  fake.state.profiles.push({
    id: 1, userGuid: USER, targetType: '考研', examDate: dayIso(120), dailyMinutes: 180,
    studyWindowsJson: '["上午","晚上"]', foundation: '一般', weakSubjectsJson: '[]',
    briefJson: JSON.stringify(qualityBrief()), updatedAt: new Date(),
  })
  fake.state.plans.push({
    id: 900, userGuid: USER, profileId: 1, title: '测试计划', targetType: '考研',
    source: 'rule', status: 'active', version: 1,
    startDate: new Date('2026-03-01T00:00:00.000Z'),
    examDate: new Date('2026-06-01T00:00:00.000Z'),
    updatedAt: new Date(),
  })
  fake.state.items.push(
    { id: 1, planId: 900, planDate: new Date('2026-03-09T00:00:00.000Z'), minutes: 60, status: 'done' },
    { id: 2, planId: 900, planDate: new Date('2026-03-10T00:00:00.000Z'), minutes: 90, status: 'pending' },
    { id: 3, planId: 900, planDate: new Date('2026-03-11T00:00:00.000Z'), minutes: 30, status: 'pending' },
    { id: 4, planId: 900, planDate: new Date('2026-03-12T00:00:00.000Z'), minutes: 300, status: 'pending' },
  )
  fake.state.sessions.push(
    { userGuid: USER, startedAt: BigInt(Date.parse('2026-03-09T20:00:00.000Z')), durationMin: 50, valid: true, isDeleted: false },
    { userGuid: USER, startedAt: BigInt(Date.parse('2026-03-10T20:00:00.000Z')), durationMin: 25, valid: true, isDeleted: false },
    { userGuid: USER, startedAt: BigInt(Date.parse('2026-03-04T20:00:00.000Z')), durationMin: 40, valid: true, isDeleted: false },
  )

  const summary = await serviceWith(fake).checkup(USER, {
    tzOffset: 0,
    now: new Date('2026-03-11T12:00:00.000Z'),
  })

  assert.equal(summary.weekPlanTotal, 4)
  assert.equal(summary.weekPlanDone, 1)
  assert.equal(summary.weekFocusMinutes, 75)
  assert.equal(summary.lastWeekFocusMinutes, 40)
  assert.equal(summary.weekActiveDays, 2)
  assert.equal(summary.overdueCount, 1)
  assert.equal(summary.behindMinutes, 90)
  assert.equal(summary.next7dPlannedMinutes, 300)
  assert.equal(summary.overloadedDays.length, 1)
  assert.match(summary.suggestion, /去调整一下。$/)
})
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
npm test -- --test-name-pattern "checkup"
```

Expected: FAIL（`this.db.pomodoroSession is undefined` 或 `checkup` 仍缺字段）。

- [ ] **Step 3: 扩展 PlanningDb 并重写 checkup**

在 `service.ts` 顶部加入：

```ts
import {
  buildCheckup, emptyCheckup, normalizeTzOffset, DEFAULT_TZ_OFFSET,
  type CheckupSummary,
} from './checkup'
```

`PlanningDb` 增加：

```ts
  pomodoroSession: {
    findMany(args?: AnyArgs): Promise<any[]>
  }
```

把 `checkup` 方法整体替换为：

```ts
    /** 主动体检:本周执行、落后任务与未来 7 天负荷(纯计算,无副作用) */
    async checkup(
      userGuid: string,
      options: { tzOffset?: number; now?: Date } = {},
    ): Promise<CheckupSummary> {
      const now = options.now ?? new Date()
      const tzOffset = normalizeTzOffset(options.tzOffset ?? DEFAULT_TZ_OFFSET)
      const planRow = await db.plan.findFirst({
        where: { userGuid, status: 'active' },
        orderBy: { version: 'desc' },
      })
      if (!planRow) return emptyCheckup()
      const [items, profileRow, sessions] = await Promise.all([
        db.planItem.findMany({ where: { planId: planRow.id } }),
        db.userProfile.findUnique({ where: { userGuid } }),
        db.pomodoroSession.findMany({
          where: {
            userGuid,
            valid: true,
            isDeleted: false,
            startedAt: { gte: BigInt(now.getTime() - 15 * 24 * 60 * 60 * 1000) },
          },
        }),
      ])
      return buildCheckup({
        now,
        tzOffset,
        items,
        sessions,
        brief: briefFromRow(profileRow),
      })
    },
```

- [ ] **Step 4: 路由接参数和通知开关**

`routes.ts` 增加 import：

```ts
import { normalizeTzOffset, shouldNotifyPlanReminder } from './checkup'
```

把体检路由替换为：

```ts
/** 计划体检:周执行/落后任务/未来负荷。App 周期轮询;服务端按 notify 开关顺手触发提醒 */
router.get('/plans/checkup', requireAuth, async ctx => {
  const tzOffset = normalizeTzOffset(ctx.query.tzOffset)
  const notify = shouldNotifyPlanReminder(ctx.query.notify)
  const checkup = await planningService.checkup(ctx.state.auth!.userGuid, { tzOffset })
  if (notify) void maybeNotifyPlanReminder(ctx.state.auth!.userGuid)
  ctx.body = { ...checkup, serverTime: Date.now() }
})
```

- [ ] **Step 5: 运行服务端测试与类型检查**

```powershell
npm test
npm run typecheck
```

Expected: 全部 PASS；`tsc` 无错误。

---

## Task 3: `/healthz` 暴露邮件重置能力

**Files:**
- Create: `server/src/shared/health.ts`
- Test: `server/src/shared/health.test.ts`
- Modify: `server/src/app.ts:83-90`

**Interfaces:**
- Consumes: `smtpConfigured()`（`../config/env`）。
- Produces: `healthPayload(now?: number): { ok: true; serverTime: number; features: { emailReset: boolean } }`。

- [ ] **Step 1: 写失败测试**

新建 `server/src/shared/health.test.ts`：

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { healthPayload } from './health'

test('healthz 暴露邮件重置能力开关', () => {
  const payload = healthPayload(123)
  assert.equal(payload.ok, true)
  assert.equal(payload.serverTime, 123)
  assert.equal(typeof payload.features.emailReset, 'boolean')
})
```

- [ ] **Step 2: 运行确认失败**

```powershell
npm test -- --test-name-pattern "healthz"
```

Expected: FAIL，找不到模块 `./health`。

- [ ] **Step 3: 实现并接入**

新建 `server/src/shared/health.ts`：

```ts
import { smtpConfigured } from '../config/env'

/** 健康检查负载:客户端据此判断「忘记密码」能否发邮件,避免把用户带进死路 */
export function healthPayload(now: number = Date.now()) {
  return {
    ok: true,
    serverTime: now,
    features: { emailReset: smtpConfigured() },
  }
}
```

`app.ts` 顶部增加：

```ts
import { healthPayload } from './shared/health'
```

替换健康检查块：

```ts
  app.use(async ctx => {
    if (ctx.path === '/healthz') {
      ctx.body = healthPayload()
      return
    }
    throw new ApiError(404, 'NOT_FOUND', '接口不存在')
  })
```

- [ ] **Step 4: 验证**

```powershell
npm test -- --test-name-pattern "healthz"
npm run typecheck
```

Expected: PASS。

---

## Task 4: 客户端 API 契约（体检、恢复、备份设置、健康）

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/SyncModels.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/DataSyncer.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/repo/StudyRepository.kt`
- Test: `YanZhong/app/src/test/java/com/yanzhong/app/data/remote/CloudContractTest.kt`

**Interfaces:**
- Consumes: Task 2/3 的服务端形状。
- Produces: `CheckupResp` 新字段、`CheckupOverloadDayDto`、`HealthResp/HealthFeatures`、`RestoreResp/RestoreResourceStat`、`BackupUpload.settings`、`BackupDownload.settings`、`planCheckup(tzOffset, notify)`、`health()`、`restoreBackup(): RestoreResp`、`StudyRepository.exportBackupJson(settings)`。

- [ ] **Step 1: 写失败测试**

新建 `CloudContractTest.kt`：

```kotlin
package com.yanzhong.app.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `旧服务端体检响应仍可解析`() {
        val resp = json.decodeFromString<CheckupResp>(
            """{"behindMinutes":30,"overdueCount":1,"suggestion":"x","serverTime":1}"""
        )
        assertEquals(30L, resp.behindMinutes)
        assertEquals(0L, resp.weekPlanTotal)
        assertTrue(resp.overloadedDays.isEmpty())
    }

    @Test
    fun `体检超载日解析日期与分钟`() {
        val resp = json.decodeFromString<CheckupResp>(
            """{"overloadedDays":[{"date":"2026-03-12","planned":300,"capacity":180}]}"""
        )
        assertEquals("2026-03-12", resp.overloadedDays[0].date)
        assertEquals(300L, resp.overloadedDays[0].planned)
        assertEquals(180L, resp.overloadedDays[0].capacity)
    }

    @Test
    fun `恢复响应保留被拒绝行明细`() {
        val resp = json.decodeFromString<RestoreResp>(
            """{"restored":true,"resources":{"tasks":{"applied":8,"skipped":1,"rejected":[{"index":0,"reasons":["bad"]}]}}}"""
        )
        assertEquals(8, resp.resources.getValue("tasks").applied)
        assertEquals(1, resp.resources.getValue("tasks").rejected.size)
    }

    @Test
    fun `备份设置缺省为空`() {
        val backup = json.decodeFromString<BackupDownload>("""{"version":2}""")
        assertTrue(backup.settings.isEmpty())
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
cd F:\kaoyan-app-prd\YanZhong
.\gradlew.bat :app:testDebugUnitTest --tests "com.yanzhong.app.data.remote.CloudContractTest" -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: 编译失败（找不到 `weekPlanTotal`、`RestoreResp`、`settings`）。

- [ ] **Step 3: 补 DTO 与接口**

`Api.kt` 在 `CheckupResp` 前增加：

```kotlin
@Serializable
data class CheckupOverloadDayDto(
    val date: String = "",
    val planned: Long = 0,
    val capacity: Long = 0
)
```

把 `CheckupResp` 替换为：

```kotlin
@Serializable
data class CheckupResp(
    val behindMinutes: Long = 0,
    val overdueCount: Long = 0,
    val suggestion: String = "",
    val weekPlanDone: Long = 0,
    val weekPlanTotal: Long = 0,
    val weekFocusMinutes: Long = 0,
    val lastWeekFocusMinutes: Long = 0,
    val weekActiveDays: Long = 0,
    val next7dPlannedMinutes: Long = 0,
    val next7dCapacityMinutes: Long = 0,
    val overloadedDays: List<CheckupOverloadDayDto> = emptyList(),
    val serverTime: Long = 0
)
```

在 `SyncResult` 后增加：

```kotlin
@Serializable
data class RestoreResourceStat(
    val applied: Int = 0,
    val skipped: Int = 0,
    val rejected: List<RejectedSyncRow> = emptyList()
)

@Serializable
data class RestoreResp(
    val restored: Boolean = false,
    val resources: Map<String, RestoreResourceStat> = emptyMap(),
    val serverTime: Long = 0
)
```

在健康相关 DTO 区域增加：

```kotlin
@Serializable
data class HealthFeatures(val emailReset: Boolean = false)

@Serializable
data class HealthResp(
    val ok: Boolean = false,
    val serverTime: Long = 0,
    val features: HealthFeatures = HealthFeatures()
)
```

接口方法修改/增加：

```kotlin
    @GET("healthz")
    suspend fun health(): HealthResp

    @POST("api/v1/backup/restore")
    suspend fun restoreBackup(@Body payload: BackupUpload): RestoreResp

    @GET("api/v1/plans/checkup")
    suspend fun planCheckup(
        @Query("tzOffset") tzOffset: Int = 480,
        @Query("notify") notify: Int = 1
    ): CheckupResp
```

`SyncModels.kt` 两个 DTO 各增加一行（放在 `monthlyReviews` 之后）：

```kotlin
    val settings: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
```

`StudyRepository.exportBackupJson` 改为：

```kotlin
    suspend fun exportBackupJson(
        settings: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
    ): String {
        val upload = buildBackupUpload()
        val download = BackupDownload(
            version = 2,
            exportedAt = System.currentTimeMillis(),
            subjects = upload.subjects,
            countdownNodes = upload.countdownNodes,
            tasks = upload.tasks,
            sessions = upload.sessions,
            dailyReviews = upload.dailyReviews,
            weeklyReviews = upload.weeklyReviews,
            monthlyReviews = upload.monthlyReviews,
            settings = settings
        )
        return Json.encodeToString(download)
    }
```

`DataSyncer.kt`：

- 体检调用改为（并新增 `import com.yanzhong.app.util.TimeUtils`）：

```kotlin
        val checkup = runCatching {
            ApiClient.api().planCheckup(
                tzOffset = TimeUtils.currentTzOffsetMinutes(),
                notify = 1
            )
        }.getOrNull() ?: return
```

- 同步失败文案改为：

```kotlin
                    it.copy(running = false, lastError = e.apiErrorMessage(), pushed = pushed)
```

- [ ] **Step 4: 补时区工具（Task 5 会加测试，这里先实现）**

`util/TimeUtils.kt` 增加：

```kotlin
    /** 指定时区在某一时刻相对 UTC 的偏移(分钟),东八区为 480 */
    fun tzOffsetMinutesAt(zone: ZoneId, instant: Instant): Int =
        zone.rules.getOffset(instant).totalSeconds / 60

    fun currentTzOffsetMinutes(): Int =
        tzOffsetMinutesAt(zone, Instant.now())
```

- [ ] **Step 5: 运行测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.yanzhong.app.data.remote.CloudContractTest" -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: PASS。

---

## Task 5: 客户端纯规则与文案（TDD）

**Files:**
- Create: `ui/cloud/CloudFormat.kt`
- Create: `ui/cloud/CloudDataRules.kt`
- Create: `ui/home/CheckupFormat.kt`
- Create: `ui/auth/PasswordRules.kt`
- Test: `ui/cloud/CloudFormatTest.kt`
- Test: `ui/cloud/CloudDataRulesTest.kt`
- Test: `ui/home/CheckupFormatTest.kt`
- Test: `ui/auth/PasswordRulesTest.kt`
- Modify: `util/TimeUtilsTest.kt`

**Interfaces:**
- Produces:
  - `CloudFormat.syncMoment(lastSyncAt, now, zone): String`
  - `CloudFormat.clockAt(epochMilli, zone): String`
  - `CloudDataRules.MAX_BACKUP_BYTES`、`RESTORE_CONFIRM_WORD`、`validateSize(bytes): String?`、`parseBackup(text): Result<BackupDownload>`、`summary(backup): String`、`canConfirm(input): Boolean`
  - `CheckupFormat.headline/rate/trend/load`
  - `PasswordRules.validateChange/validateReset`

- [ ] **Step 1: 写失败测试**

`CloudFormatTest.kt`：

```kotlin
package com.yanzhong.app.ui.cloud

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudFormatTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()

    @Test
    fun `同步时间按距离分档`() {
        assertEquals("尚未同步", CloudFormat.syncMoment(0, now, zone))
        assertEquals("刚刚", CloudFormat.syncMoment(now - 30_000, now, zone))
        assertEquals("5 分钟前", CloudFormat.syncMoment(now - 5 * 60_000, now, zone))
        assertEquals("2 小时前", CloudFormat.syncMoment(now - 2 * 3_600_000, now, zone))
    }

    @Test
    fun `昨天显示昨天加时刻`() {
        val last = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()
        assertEquals("昨天 20:00", CloudFormat.syncMoment(last, now, zone))
    }

    @Test
    fun `更早显示月日时刻`() {
        val last = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        assertEquals("10-30 20:00", CloudFormat.syncMoment(last, now, zone))
    }
}
```

`CloudDataRulesTest.kt`：

```kotlin
package com.yanzhong.app.ui.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDataRulesTest {
    @Test
    fun `结构完整时解析成功`() {
        val result = CloudDataRules.parseBackup(
            """{"version":2,"subjects":[],"tasks":[],"sessions":[]}"""
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `缺列表时给出可读原因`() {
        val result = CloudDataRules.parseBackup("""{"version":2,"subjects":[]}""")
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("tasks"))
    }

    @Test
    fun `确认词必须完全一致`() {
        assertTrue(CloudDataRules.canConfirm("恢复"))
        assertTrue(CloudDataRules.canConfirm(" 恢复 "))
        assertFalse(CloudDataRules.canConfirm("恢复数据"))
        assertFalse(CloudDataRules.canConfirm(""))
    }

    @Test
    fun `超过上限拒绝`() {
        assertTrue(CloudDataRules.validateSize(CloudDataRules.MAX_BACKUP_BYTES) == null)
        assertNotNull(CloudDataRules.validateSize(CloudDataRules.MAX_BACKUP_BYTES + 1))
    }
}
```

上限是 7.5MB（`7_500_000` 字节），与服务端 8MB body 限制留出包装余量。

`CheckupFormatTest.kt`：

```kotlin
package com.yanzhong.app.ui.home

import com.yanzhong.app.data.remote.CheckupOverloadDayDto
import com.yanzhong.app.data.remote.CheckupResp
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckupFormatTest {
    @Test
    fun `有逾期时标题说落后`() {
        assertEquals("有 2 项任务没跟上", CheckupFormat.headline(CheckupResp(overdueCount = 2)))
    }

    @Test
    fun `无逾期但超载时标题说过满`() {
        val c = CheckupResp(overloadedDays = listOf(CheckupOverloadDayDto("2026-03-12", 300, 180)))
        assertEquals("接下来 1 天排得有点满", CheckupFormat.headline(c))
    }

    @Test
    fun `健康时标题是节奏正常`() {
        assertEquals("计划节奏正常", CheckupFormat.headline(CheckupResp()))
    }

    @Test
    fun `没有计划任务时不显示完成率`() {
        assertEquals("本周还没有计划任务", CheckupFormat.rate(CheckupResp()))
    }

    @Test
    fun `上周没有专注时提示开始积累`() {
        assertEquals("本周已开始积累专注记录", CheckupFormat.trend(CheckupResp()))
    }

    @Test
    fun `未来负荷显示已排与容量`() {
        val c = CheckupResp(next7dPlannedMinutes = 600, next7dCapacityMinutes = 900)
        assertEquals("未来 7 天已排 600 / 900 分钟", CheckupFormat.load(c))
    }

    @Test
    fun `容量未知时标注未确认空闲时段`() {
        val c = CheckupResp(next7dPlannedMinutes = 600)
        assertEquals("未来 7 天已排 600 分钟 · 未确认空闲时段", CheckupFormat.load(c))
    }
}
```

`PasswordRulesTest.kt`：

```kotlin
package com.yanzhong.app.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordRulesTest {
    @Test
    fun `改密拒绝过短与不一致`() {
        assertEquals("新密码至少 8 位", PasswordRules.validateChange("oldpass1", "short", "short"))
        assertEquals("两次输入的新密码不一致", PasswordRules.validateChange("oldpass1", "newpass12", "newpass13"))
        assertEquals("新密码不能和当前密码相同", PasswordRules.validateChange("samepass1", "samepass1", "samepass1"))
        assertNull(PasswordRules.validateChange("oldpass1", "newpass12", "newpass12"))
    }

    @Test
    fun `重置只校验新密码`() {
        assertEquals("新密码至少 8 位", PasswordRules.validateReset("short", "short"))
        assertNull(PasswordRules.validateReset("newpass12", "newpass12"))
    }
}
```

`TimeUtilsTest.kt` 追加：

```kotlin
    @Test
    fun `时区偏移按指定时区计算`() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val instant = java.time.Instant.parse("2026-11-01T00:00:00Z")
        org.junit.Assert.assertEquals(480, TimeUtils.tzOffsetMinutesAt(zone, instant))
    }
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.yanzhong.app.ui.cloud.*" --tests "com.yanzhong.app.ui.home.CheckupFormatTest" --tests "com.yanzhong.app.ui.auth.PasswordRulesTest" --tests "com.yanzhong.app.util.TimeUtilsTest" -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现四个纯模块**

`CloudFormat.kt`：

```kotlin
package com.yanzhong.app.ui.cloud

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object CloudFormat {
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val monthDay = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)

    fun syncMoment(lastSyncAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (lastSyncAt <= 0L) return "尚未同步"
        val delta = (now - lastSyncAt).coerceAtLeast(0L)
        if (delta < 60_000L) return "刚刚"
        if (delta < 3_600_000L) return "${delta / 60_000L} 分钟前"
        if (delta < 86_400_000L) return "${delta / 3_600_000L} 小时前"
        val last = Instant.ofEpochMilli(lastSyncAt).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (last.toLocalDate() == today.minusDays(1)) "昨天 ${last.format(clock)}"
        else last.format(monthDay)
    }

    fun clockAt(epochMilli: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMilli).atZone(zone).format(clock)
}
```

`CloudDataRules.kt`：

```kotlin
package com.yanzhong.app.ui.cloud

import com.yanzhong.app.data.remote.BackupDownload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object CloudDataRules {
    /** 服务端 body 上限 8MB,留出余量给 JSON 包装 */
    const val MAX_BACKUP_BYTES = 7_500_000
    const val RESTORE_CONFIRM_WORD = "恢复"

    fun validateSize(bytes: Int): String? =
        if (bytes > MAX_BACKUP_BYTES) "备份文件超过 7.5MB，请换一个小一点的文件" else null

    fun parseBackup(text: String): Result<BackupDownload> = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        for (key in listOf("subjects", "tasks", "sessions")) {
            val element = root[key] ?: throw IllegalArgumentException("备份包缺少 $key 列表")
            runCatching { element.jsonArray }.getOrElse {
                throw IllegalArgumentException("备份包里的 $key 不是列表")
            }
        }
        Json { ignoreUnknownKeys = true }.decodeFromString<BackupDownload>(text)
    }

    fun summary(backup: BackupDownload): String {
        val reviews = backup.dailyReviews.size + backup.weeklyReviews.size + backup.monthlyReviews.size
        return "科目 ${backup.subjects.size} · 任务 ${backup.tasks.size} · " +
            "专注 ${backup.sessions.size} 条 · 复盘 $reviews 条"
    }

    fun canConfirm(input: String): Boolean = input.trim() == RESTORE_CONFIRM_WORD
}
```

`CheckupFormat.kt`：

```kotlin
package com.yanzhong.app.ui.home

import com.yanzhong.app.data.remote.CheckupResp

object CheckupFormat {
    fun headline(c: CheckupResp): String = when {
        c.overdueCount > 0 -> "有 ${c.overdueCount} 项任务没跟上"
        c.overloadedDays.isNotEmpty() -> "接下来 ${c.overloadedDays.size} 天排得有点满"
        else -> "计划节奏正常"
    }

    fun rate(c: CheckupResp): String =
        if (c.weekPlanTotal <= 0) "本周还没有计划任务"
        else "本周计划完成 ${c.weekPlanDone}/${c.weekPlanTotal}"

    fun trend(c: CheckupResp): String = when {
        c.lastWeekFocusMinutes > 0 && c.weekFocusMinutes == 0L ->
            "上周专注 ${c.lastWeekFocusMinutes} 分钟，本周等你开始"
        c.lastWeekFocusMinutes == 0L && c.weekFocusMinutes == 0L -> "本周已开始积累专注记录"
        c.weekActiveDays <= 0 -> "本周已专注 ${c.weekFocusMinutes} 分钟"
        else -> "本周 ${c.weekActiveDays} 天有专注，共 ${c.weekFocusMinutes} 分钟"
    }

    fun load(c: CheckupResp): String =
        if (c.next7dCapacityMinutes <= 0) "未来 7 天已排 ${c.next7dPlannedMinutes} 分钟 · 未确认空闲时段"
        else "未来 7 天已排 ${c.next7dPlannedMinutes} / ${c.next7dCapacityMinutes} 分钟"
}
```

`PasswordRules.kt`：

```kotlin
package com.yanzhong.app.ui.auth

object PasswordRules {
    fun validateChange(old: String, new: String, confirm: String): String? = when {
        old.isEmpty() -> "请输入当前密码"
        new.length < 8 -> "新密码至少 8 位"
        new != confirm -> "两次输入的新密码不一致"
        new == old -> "新密码不能和当前密码相同"
        else -> null
    }

    fun validateReset(new: String, confirm: String): String? = when {
        new.length < 8 -> "新密码至少 8 位"
        new != confirm -> "两次输入的新密码不一致"
        else -> null
    }
}
```

- [ ] **Step 4: 运行测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.yanzhong.app.ui.cloud.*" --tests "com.yanzhong.app.ui.home.CheckupFormatTest" --tests "com.yanzhong.app.ui.auth.PasswordRulesTest" --tests "com.yanzhong.app.util.TimeUtilsTest" -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: PASS。

---

## Task 6: 账号 VM（同步状态、手动同步、改密、全设备登出）

**Files:**
- Modify: `ui/cloud/CloudSyncViewModel.kt`

**Interfaces:**
- Consumes: Task 4 DTO、Task 5 `PasswordRules`、`YanZhongApp.dataSyncer`、`TokenStore.observeLastSync()`。
- Produces: `CloudUiState.syncing/lastSyncAt/lastSyncError/changingPassword/securityError`；`syncNow()`、`changePassword(old,new,confirm)`、`revokeAllDevices()`、`clearSecurityError()`、`clearLocalSession(message)`。

- [ ] **Step 1: 扩展状态**

把 `CloudUiState` 替换为：

```kotlin
data class CloudUiState(
    val serverUrl: String = "",
    val loggedIn: Boolean = false,
    val username: String = "",
    val devices: List<DeviceFullDto> = emptyList(),
    val busy: Boolean = false,
    val message: String = "",
    val syncing: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastSyncError: String? = null,
    val changingPassword: Boolean = false,
    val securityError: String = ""
)
```

- [ ] **Step 2: 在 init 订阅同步状态**

在现有 init 的第一个 launch 之前加两个收集协程：

```kotlin
    init {
        viewModelScope.launch {
            (getApplication<Application>() as YanZhongApp).dataSyncer.state.collect { s ->
                _ui.update { it.copy(syncing = s.running, lastSyncError = s.lastError) }
            }
        }
        viewModelScope.launch {
            TokenStore.observeLastSync().collect { at ->
                _ui.update { it.copy(lastSyncAt = at) }
            }
        }
        viewModelScope.launch {
            val server = effectiveServerUrl()
            val access = TokenStore.currentAccess()
            _ui.update { it.copy(serverUrl = server, loggedIn = access != null) }
            if (access != null) {
                _ui.update { it.copy(username = TokenStore.currentUsername().orEmpty()) }
                refreshDevices()
            }
        }
    }
```

import 增加：

```kotlin
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.ui.auth.PasswordRules
```

- [ ] **Step 3: 统一清会话并改造 logout**

在 `logout()` 前加：

```kotlin
    /** 密码变更/全设备登出后本机也必须重新登录:清 token 与计划投影,立即停同步 */
    private suspend fun clearLocalSession(message: String) {
        val app = getApplication<Application>() as YanZhongApp
        TokenStore.currentAccountGuid()?.let { repo.clearPlanProjections(it) }
        TokenStore.clearAll()
        app.dataSyncer.stop()
        _ui.update {
            CloudUiState(serverUrl = it.serverUrl, message = message)
        }
    }
```

把 `logout()` 替换为：

```kotlin
    fun logout() {
        viewModelScope.launch {
            runCatching { ApiClient.api().logout() }
            clearLocalSession("")
        }
    }
```

- [ ] **Step 4: 增加同步与安全动作**

在 `revokeDevice` 之后增加：

```kotlin
    fun syncNow() {
        viewModelScope.launch {
            val app = getApplication<Application>() as YanZhongApp
            app.dataSyncer.syncOnce()
            val error = app.dataSyncer.state.value.lastError
            _ui.update {
                it.copy(message = if (error == null) "已同步" else "同步失败，稍后会自动重试")
            }
        }
    }

    fun changePassword(oldPassword: String, newPassword: String, confirm: String) {
        val error = PasswordRules.validateChange(oldPassword, newPassword, confirm)
        if (error != null) {
            _ui.update { it.copy(securityError = error) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(changingPassword = true, securityError = "") }
            runCatching {
                ApiClient.api().changePassword(ChangePasswordReq(oldPassword, newPassword))
            }.fold({
                clearLocalSession("密码已修改，请用新密码重新登录")
            }, { e ->
                _ui.update {
                    it.copy(
                        changingPassword = false,
                        securityError = e.apiErrorMessage(unauthorized = "当前密码不正确")
                    )
                }
            })
        }
    }

    fun revokeAllDevices() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            runCatching { ApiClient.api().revokeAllDevices() }.fold({
                clearLocalSession("已登出全部设备，请重新登录")
            }, { e ->
                _ui.update { it.copy(busy = false, message = "登出失败:${e.apiErrorMessage()}") }
            })
        }
    }

    fun clearSecurityError() {
        _ui.update { it.copy(securityError = "") }
    }
```

- [ ] **Step 5: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: BUILD SUCCESSFUL。

---

## Task 7: 账号页同步状态与安全分组

**Files:**
- Create: `ui/account/SyncStatusCard.kt`
- Create: `ui/account/AccountSecuritySection.kt`
- Modify: `ui/account/AccountScreen.kt`

**Interfaces:**
- Consumes: Task 6 的 `CloudUiState` 与动作。
- Produces: `SyncStatusCard(...)`、`AccountSecuritySection(...)` 可复用组件。

- [ ] **Step 1: 实现同步状态卡**

新建 `ui/account/SyncStatusCard.kt`：

```kotlin
package com.yanzhong.app.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.ui.cloud.CloudFormat
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.PillTone
import com.yanzhong.app.ui.theme.SecondaryButton
import com.yanzhong.app.ui.theme.StatusPill
import kotlinx.coroutines.delay

/** 同步状态三态:正常 / 同步中 / 失败;数字时间每 30 秒刷新一次,不拖整页重组 */
@Composable
fun SyncStatusCard(
    lastSyncAt: Long,
    syncing: Boolean,
    lastError: String?,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val error = lastError
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("同步状态", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        syncing -> "正在同步…"
                        error != null -> "同步失败 · 上次成功 ${
                            if (lastSyncAt > 0) CloudFormat.clockAt(lastSyncAt) else "尚未同步"
                        }"
                        else -> "已同步 · ${CloudFormat.syncMoment(lastSyncAt, now)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(
                text = when {
                    syncing -> "同步中"
                    error != null -> "待重试"
                    else -> "正常"
                },
                tone = when {
                    syncing -> PillTone.NEUTRAL
                    error != null -> PillTone.WARNING
                    else -> PillTone.SOLID
                }
            )
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            text = if (error != null) "重试同步" else "立即同步",
            onClick = onSyncNow,
            enabled = !syncing,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
```

- [ ] **Step 2: 实现安全分组**

新建 `ui/account/AccountSecuritySection.kt`：

```kotlin
package com.yanzhong.app.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yanzhong.app.ui.auth.PasswordRules
import com.yanzhong.app.ui.theme.ListRow
import com.yanzhong.app.ui.theme.RowDivider
import com.yanzhong.app.ui.theme.RowGroup
import com.yanzhong.app.ui.theme.SectionHeader

@Composable
fun AccountSecuritySection(
    changing: Boolean,
    error: String,
    onClearError: () -> Unit,
    onChangePassword: (String, String, String) -> Unit,
    onRevokeAll: () -> Unit
) {
    var showChange by remember { mutableStateOf(false) }
    var showRevokeAll by remember { mutableStateOf(false) }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    SectionHeader(title = "账号安全", meta = "密码与登录设备")
    RowGroup {
        ListRow(
            title = "修改密码",
            subtitle = "修改后其他设备需要重新登录",
            onClick = {
                oldPassword = ""
                newPassword = ""
                confirmPassword = ""
                onClearError()
                showChange = true
            }
        )
        RowDivider()
        ListRow(
            title = "登出全部设备",
            subtitle = "本机也会退出，需要重新登录",
            onClick = { showRevokeAll = true }
        )
    }

    if (showChange) {
        AlertDialog(
            onDismissRequest = {
                showChange = false
                onClearError()
            },
            title = { Text("修改密码") },
            text = {
                Column {
                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it },
                        label = { Text("当前密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("新密码（至少 8 位）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("再输一次新密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onChangePassword(oldPassword, newPassword, confirmPassword) },
                    enabled = !changing &&
                        PasswordRules.validateChange(oldPassword, newPassword, confirmPassword) == null
                ) {
                    Text(if (changing) "提交中…" else "修改密码")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showChange = false
                        onClearError()
                    }
                ) { Text("取消") }
            }
        )
    }

    if (showRevokeAll) {
        AlertDialog(
            onDismissRequest = { showRevokeAll = false },
            title = { Text("登出全部设备？") },
            text = {
                Text("所有设备都会立即停止同步，包括这台设备；需要重新登录才能恢复。学习数据仍保存在服务端。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevokeAll = false
                        onRevokeAll()
                    }
                ) { Text("全部登出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeAll = false }) { Text("取消") }
            }
        )
    }
}
```

- [ ] **Step 3: 接进 AccountScreen**

`AccountScreen.kt` import 增加：

```kotlin
import com.yanzhong.app.ui.account.AccountSecuritySection
import com.yanzhong.app.ui.account.SyncStatusCard
```

在登录态账号头部 `AppCard` 结束之后（当前第 214 行 `}` 之后、`Spacer(Modifier.height(20.dp))` 之前）插入：

```kotlin
                    Spacer(Modifier.height(16.dp))
                    SyncStatusCard(
                        lastSyncAt = state.lastSyncAt,
                        syncing = state.syncing,
                        lastError = state.lastSyncError,
                        onSyncNow = { vm.syncNow() }
                    )
                    Spacer(Modifier.height(20.dp))
```

`AccountDataSection` 整块留到 Task 8 接入，本任务不写空回调占位，避免出现点击无反应的入口。

在“刷新设备列表”之后、`SecondaryButton("退出登录")` 之前插入：

```kotlin
                    Spacer(Modifier.height(20.dp))
                    AccountSecuritySection(
                        changing = state.changingPassword,
                        error = state.securityError,
                        onClearError = { vm.clearSecurityError() },
                        onChangePassword = { old, new, confirm ->
                            vm.changePassword(old, new, confirm)
                        },
                        onRevokeAll = { vm.revokeAllDevices() }
                    )
                    Spacer(Modifier.height(20.dp))
```

- [ ] **Step 4: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: BUILD SUCCESSFUL。

---

## Task 8: 云端数据分组（导出 / 下载 / 恢复五步）

**Files:**
- Create: `ui/cloud/CloudDataViewModel.kt`
- Create: `ui/account/AccountDataSection.kt`
- Modify: `ui/account/AccountScreen.kt`（SAF 启动器与回调）

**Interfaces:**
- Consumes: Task 4 `RestoreResp`、`BackupDownload.settings`、Task 5 `CloudDataRules`、`YanZhongApp.repository/settingsRepo/dataSyncer`。
- Produces: `CloudDataUiState`、`CloudDataViewModel.exportAccountTo/downloadBackupTo/downloadSnapshotTo/prepareRestore/confirmRestore/clearPendingRestore/clearMessage`。

- [ ] **Step 1: 实现 CloudDataViewModel**

新建 `ui/cloud/CloudDataViewModel.kt`：

```kotlin
package com.yanzhong.app.ui.cloud

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.BackupDownload
import com.yanzhong.app.data.remote.BackupUpload
import com.yanzhong.app.data.remote.apiErrorMessage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CloudDataUiState(
    val busy: Boolean = false,
    val pending: BackupDownload? = null,
    val pendingName: String = "",
    val snapshotAvailable: Boolean = false,
    val message: String = ""
)

class CloudDataViewModel(app: Application) : AndroidViewModel(app) {
    private val yanZhong = app as YanZhongApp
    private val repo = yanZhong.repository
    private val settingsRepo = yanZhong.settingsRepo
    private val syncer = yanZhong.dataSyncer
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val snapshotFile get() = yanZhong.filesDir.resolve(SNAPSHOT_NAME)

    private val _ui = MutableStateFlow(
        CloudDataUiState(snapshotAvailable = yanZhong.filesDir.resolve(SNAPSHOT_NAME).exists())
    )
    val ui: StateFlow<CloudDataUiState> = _ui

    fun exportAccountTo(uri: Uri) = write(uri) {
        ApiClient.api().exportAccount().toString()
    }

    fun downloadBackupTo(uri: Uri) = write(uri) {
        val backup = ApiClient.api().backup()
        json.encodeToString(BackupDownload.serializer(), backup)
    }

    fun downloadSnapshotTo(uri: Uri) = write(uri) {
        if (!snapshotFile.exists()) throw IllegalStateException("还没有恢复前快照")
        snapshotFile.readText()
    }

    private fun write(uri: Uri, produce: suspend () -> String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "") }
            runCatching {
                val text = produce()
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法写入这个文件")
                }
            }.fold({
                _ui.update { it.copy(busy = false, message = "已保存到所选位置") }
            }, { e ->
                _ui.update { it.copy(busy = false, message = "保存失败:${e.message ?: "无法写入文件"}") }
            })
        }
    }

    fun prepareRestore(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "", pending = null) }
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) { readLimited(uri) }
                CloudDataRules.validateSize(bytes.size)?.let { throw IllegalArgumentException(it) }
                CloudDataRules.parseBackup(bytes.toString(Charsets.UTF_8)).getOrThrow()
            }
            result.fold({ backup ->
                _ui.update {
                    it.copy(
                        busy = false,
                        pending = backup,
                        pendingName = displayName(uri),
                        message = ""
                    )
                }
            }, { e ->
                _ui.update { it.copy(busy = false, message = "读取失败:${e.message ?: "文件格式不正确"}") }
            })
        }
    }

    fun clearPendingRestore() = _ui.update { it.copy(pending = null, pendingName = "") }

    fun confirmRestore() {
        val pending = _ui.value.pending ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "") }
            val snapshot = runCatching {
                val backup = ApiClient.api().backup()
                withContext(Dispatchers.IO) {
                    snapshotFile.writeText(json.encodeToString(BackupDownload.serializer(), backup))
                }
            }
            if (snapshot.isFailure) {
                _ui.update {
                    it.copy(busy = false, message = "恢复前快照失败，已取消恢复;请检查网络后重试")
                }
                return@launch
            }
            syncer.stop()
            val result = runCatching {
                // 服务端 /backup/restore 收 BackupUpload;文件解析出来的是 BackupDownload,
                // 两者字段一致但没有继承关系,必须显式转换(settings 也要带上,否则恢复后设置丢失)。
                val upload = BackupUpload(
                    subjects = pending.subjects,
                    countdownNodes = pending.countdownNodes,
                    tasks = pending.tasks,
                    sessions = pending.sessions,
                    dailyReviews = pending.dailyReviews,
                    weeklyReviews = pending.weeklyReviews,
                    monthlyReviews = pending.monthlyReviews,
                    settings = pending.settings
                )
                val resp = ApiClient.api().restoreBackup(upload)
                repo.restoreFromBackup(pending)
                settingsRepo.applySyncMap(pending.settings)
                resp
            }
            syncer.start()
            result.fold({ resp ->
                val skipped = resp.resources.values.sumOf { it.skipped }
                _ui.value = CloudDataUiState(
                    snapshotAvailable = true,
                    message = if (skipped > 0) "已恢复，$skipped 条记录未能导入"
                    else "已恢复，其他设备会自动刷新"
                )
            }, { e ->
                _ui.update { it.copy(busy = false, message = "恢复失败:${e.apiErrorMessage()}") }
            })
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = "") }

    private fun readLimited(uri: Uri): ByteArray {
        val input = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取这个文件")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > CloudDataRules.MAX_BACKUP_BYTES) {
                    throw IllegalArgumentException("备份文件超过 7.5MB，请换一个小一点的文件")
                }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    private fun displayName(uri: Uri): String {
        val name = getApplication<Application>().contentResolver
            .query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        return name ?: "备份文件"
    }

    companion object {
        private const val SNAPSHOT_NAME = "pre_restore_backup.json"
    }
}
```

- [ ] **Step 2: 实现 AccountDataSection**

新建 `ui/account/AccountDataSection.kt`：

```kotlin
package com.yanzhong.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.ui.cloud.CloudDataRules
import com.yanzhong.app.ui.cloud.CloudDataUiState
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.ListRow
import com.yanzhong.app.ui.theme.PrimaryButton
import com.yanzhong.app.ui.theme.RowDivider
import com.yanzhong.app.ui.theme.RowGroup
import com.yanzhong.app.ui.theme.SecondaryButton
import com.yanzhong.app.ui.theme.SectionHeader

@Composable
fun AccountDataSection(
    state: CloudDataUiState,
    onExportAccount: () -> Unit,
    onDownloadBackup: () -> Unit,
    onPickRestore: () -> Unit,
    onDownloadSnapshot: () -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }
    var confirmText by remember { mutableStateOf("") }

    SectionHeader(title = "云端数据", meta = "导出 / 下载 / 恢复")
    RowGroup {
        ListRow(
            title = "导出账号数据",
            subtitle = "账号、档案、计划与学习记录，JSON 文件",
            onClick = onExportAccount
        )
        RowDivider()
        ListRow(
            title = "下载云端备份",
            subtitle = "只含学习数据与设置，不含档案和 AI 计划",
            onClick = onDownloadBackup
        )
        RowDivider()
        ListRow(
            title = "从备份恢复",
            subtitle = "覆盖本机与服务端的学习数据和设置",
            onClick = onPickRestore
        )
        if (state.snapshotAvailable) {
            RowDivider()
            ListRow(
                title = "下载恢复前快照",
                subtitle = "上次恢复之前自动保存的一份备份",
                onClick = onDownloadSnapshot
            )
        }
    }

    state.pending?.let { pending ->
        Spacer(Modifier.height(10.dp))
        AppCard {
            Text("准备恢复 ${state.pendingName}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                CloudDataRules.summary(pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "将覆盖服务端与本机的学习数据和设置；备考档案与 AI 计划不会变。恢复前会自动保存一份快照。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    text = "取消",
                    onClick = {
                        showConfirm = false
                        confirmText = ""
                        onCancelRestore()
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = "继续恢复",
                    onClick = { showConfirm = true },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showConfirm && state.pending != null) {
        AlertDialog(
            onDismissRequest = {
                showConfirm = false
                confirmText = ""
            },
            title = { Text("最后确认一次") },
            text = {
                Column {
                    Text(
                        "服务端与本机现有的学习数据、设置会被这份备份覆盖，无法撤销。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "输入「${CloudDataRules.RESTORE_CONFIRM_WORD}」继续。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (CloudDataRules.canConfirm(confirmText)) {
                            showConfirm = false
                            confirmText = ""
                            onConfirmRestore()
                        }
                    },
                    enabled = CloudDataRules.canConfirm(confirmText) && !state.busy
                ) { Text("恢复", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        confirmText = ""
                    }
                ) { Text("取消") }
            }
        )
    }
}
```

- [ ] **Step 3: AccountScreen 接 SAF**

import 增加：

```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.yanzhong.app.ui.cloud.CloudDataViewModel
import com.yanzhong.app.ui.cloud.CloudDataUiState
```

在 `val vm: CloudSyncViewModel = viewModel()` 后加：

```kotlin
    val dataVm: CloudDataViewModel = viewModel()
    val dataState by dataVm.ui.collectAsStateWithLifecycle()
    var pendingWrite by remember { mutableStateOf<WriteAction?>(null) }
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val action = pendingWrite
        pendingWrite = null
        if (uri != null && action != null) {
            when (action) {
                WriteAction.EXPORT_ACCOUNT -> dataVm.exportAccountTo(uri)
                WriteAction.CLOUD_BACKUP -> dataVm.downloadBackupTo(uri)
                WriteAction.SNAPSHOT -> dataVm.downloadSnapshotTo(uri)
            }
        }
    }
    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) dataVm.prepareRestore(uri) }
```

在文件底部（AccountScreen 外）增加私有枚举：

```kotlin
private enum class WriteAction { EXPORT_ACCOUNT, CLOUD_BACKUP, SNAPSHOT }
```

在 `LaunchedEffect(state.message)` 后增加 data 消息：

```kotlin
    LaunchedEffect(dataState.message) {
        if (dataState.message.isNotBlank()) {
            Toast.makeText(context, dataState.message, Toast.LENGTH_SHORT).show()
            dataVm.clearMessage()
        }
    }
```

在 `SyncStatusCard` 之后插入：

```kotlin
                    AccountDataSection(
                        state = dataState,
                        onExportAccount = {
                            pendingWrite = WriteAction.EXPORT_ACCOUNT
                            createDoc.launch(
                                "yanzhong-export-${TimeUtils.formatYmd(System.currentTimeMillis())}.json"
                            )
                        },
                        onDownloadBackup = {
                            pendingWrite = WriteAction.CLOUD_BACKUP
                            createDoc.launch(
                                "yanzhong-backup-${TimeUtils.formatYmd(System.currentTimeMillis())}.json"
                            )
                        },
                        onPickRestore = { openDoc.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        onDownloadSnapshot = {
                            pendingWrite = WriteAction.SNAPSHOT
                            createDoc.launch(
                                "yanzhong-pre-restore-${TimeUtils.formatYmd(System.currentTimeMillis())}.json"
                            )
                        },
                        onConfirmRestore = { dataVm.confirmRestore() },
                        onCancelRestore = { dataVm.clearPendingRestore() }
                    )
                    Spacer(Modifier.height(20.dp))
```

- [ ] **Step 4: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: BUILD SUCCESSFUL。

> 恢复逻辑会先写 `filesDir/pre_restore_backup.json`；快照失败时**不会**停同步、不会调用恢复接口。恢复结束后无论成败都会 `syncer.start()`。

---

## Task 9: 忘记密码 + 深链重置

**Files:**
- Modify: `ui/auth/LoginViewModel.kt`
- Modify: `ui/auth/LoginScreen.kt`
- Create: `ui/auth/PasswordResetBus.kt`
- Create: `ui/auth/PasswordResetViewModel.kt`
- Create: `ui/auth/PasswordResetHost.kt`
- Modify: `MainActivity.kt`
- Modify: `YanZhong/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: Task 3 `health()`、Task 4 `HealthResp`、Task 5 `PasswordRules`。
- Produces: `LoginUiState.forgotMode/forgotAccount/forgotBusy/forgotMessage/emailResetAvailable`；`PasswordResetBus.offer/consume`；`PasswordResetHost()`。

- [ ] **Step 1: LoginViewModel 增加忘记密码状态与动作**

`LoginUiState` 增加字段：

```kotlin
    val forgotMode: Boolean = false,
    val forgotAccount: String = "",
    val forgotBusy: Boolean = false,
    val forgotMessage: String = "",
    val emailResetAvailable: Boolean? = null
```

init 里追加能力探测：

```kotlin
        viewModelScope.launch {
            val available = runCatching { ApiClient.api().health() }.getOrNull()
                ?.features?.emailReset
            if (available != null) _ui.update { it.copy(emailResetAvailable = available) }
        }
```

在 `saveServer` 前增加：

```kotlin
    fun openForgot() {
        _ui.update {
            it.copy(
                forgotMode = true,
                forgotAccount = it.username,
                forgotBusy = false,
                forgotMessage = ""
            )
        }
    }

    fun closeForgot() = _ui.update { it.copy(forgotMode = false, forgotMessage = "") }

    fun updateForgotAccount(value: String) = _ui.update { it.copy(forgotAccount = value) }

    fun submitForgot() {
        val account = _ui.value.forgotAccount.trim()
        if (account.isEmpty()) {
            _ui.update { it.copy(forgotMessage = "请输入用户名或邮箱") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(forgotBusy = true, forgotMessage = "") }
            runCatching { ApiClient.api().forgotPassword(ForgotReq(account)) }.fold({
                // 服务端有意不透露账号是否存在;这里也只给统一结果
                val message = if (_ui.value.emailResetAvailable == true) {
                    "如果这个账号绑定了邮箱，重置链接已经发出，30 分钟内有效。"
                } else {
                    "已提交。当前服务器未开通邮件发送，请联系管理员重置。"
                }
                _ui.update { it.copy(forgotBusy = false, forgotMessage = message) }
            }, { e ->
                _ui.update { it.copy(forgotBusy = false, forgotMessage = "提交失败:${e.apiErrorMessage()}") }
            })
        }
    }
```

import 增加 `ForgotReq` 与 `apiErrorMessage`（后者已有）。

- [ ] **Step 2: LoginScreen 加入口和弹窗**

在登录按钮那一行之后（`PolicyLinksRow` 之前）插入：

```kotlin
                    TextButton(
                        onClick = { vm.openForgot() },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("忘记密码？") }
```

在 `LoginScreen` 根 `Box` 结束前插入：

```kotlin
    if (state.forgotMode) {
        AlertDialog(
            onDismissRequest = { vm.closeForgot() },
            title = { Text(if (state.emailResetAvailable == true) "找回密码" else "找回密码（邮件未开通）") },
            text = {
                Column {
                    Text(
                        if (state.emailResetAvailable == true) {
                            "填写注册时绑定的邮箱，我们会发一封重置链接，30 分钟内有效。"
                        } else {
                            "填写账号提交后，需要联系管理员获取重置链接。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = state.forgotAccount,
                        onValueChange = vm::updateForgotAccount,
                        label = { Text("用户名或邮箱") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.forgotMessage.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(state.forgotMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.submitForgot() },
                    enabled = !state.forgotBusy && state.forgotAccount.isNotBlank()
                ) { Text(if (state.forgotBusy) "提交中…" else "提交") }
            },
            dismissButton = {
                TextButton(onClick = { vm.closeForgot() }) { Text("关闭") }
            }
        )
    }
```

- [ ] **Step 3: 实现重置密码深链三件套**

`PasswordResetBus.kt`：

```kotlin
package com.yanzhong.app.ui.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 深链 token 只分发一次:MainActivity 写入,全局弹窗消费 */
object PasswordResetBus {
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    fun offer(token: String?) {
        if (!token.isNullOrBlank()) _token.value = token
    }

    fun consume() {
        _token.value = null
    }
}
```

`PasswordResetViewModel.kt`：

```kotlin
package com.yanzhong.app.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.ResetReq
import com.yanzhong.app.data.remote.TokenStore
import com.yanzhong.app.data.remote.apiErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PasswordResetUiState(
    val token: String = "",
    val newPassword: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    val error: String = "",
    val success: Boolean = false
)

class PasswordResetViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(PasswordResetUiState())
    val ui: StateFlow<PasswordResetUiState> = _ui

    fun begin(token: String) = _ui.update { PasswordResetUiState(token = token) }

    fun updateNew(value: String) = _ui.update { it.copy(newPassword = value) }

    fun updateConfirm(value: String) = _ui.update { it.copy(confirm = value) }

    fun submit() {
        val state = _ui.value
        PasswordRules.validateReset(state.newPassword, state.confirm)?.let { error ->
            _ui.update { it.copy(error = error) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = "") }
            runCatching {
                ApiClient.api().resetPassword(ResetReq(state.token, state.newPassword))
                // 重置成功后服务端已吊销 refresh token,本机也清干净
                TokenStore.clearAll()
            }.fold({
                _ui.update { it.copy(busy = false, success = true) }
            }, { e ->
                _ui.update { it.copy(busy = false, error = e.apiErrorMessage()) }
            })
        }
    }
}
```

`PasswordResetHost.kt`：

```kotlin
package com.yanzhong.app.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** 全局重置密码弹窗:不受登录门限制,未登录收到深链也能用 */
@Composable
fun PasswordResetHost() {
    val token by PasswordResetBus.token.collectAsStateWithLifecycle()
    val current = token ?: return
    val vm: PasswordResetViewModel = viewModel()
    val state by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(current) { vm.begin(current) }

    AlertDialog(
        onDismissRequest = { PasswordResetBus.consume() },
        title = { Text(if (state.success) "密码已重置" else "设置新密码") },
        text = {
            if (state.success) {
                Text("请用新密码重新登录。")
            } else {
                Column {
                    OutlinedTextField(
                        value = state.newPassword,
                        onValueChange = vm::updateNew,
                        label = { Text("新密码（至少 8 位）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.confirm,
                        onValueChange = vm::updateConfirm,
                        label = { Text("再输一次") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.error.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (state.success) PasswordResetBus.consume() else vm.submit()
                },
                enabled = !state.busy
            ) {
                Text(
                    when {
                        state.success -> "知道了"
                        state.busy -> "提交中…"
                        else -> "确认重置"
                    }
                )
            }
        },
        dismissButton = if (!state.success) {
            { TextButton(onClick = { PasswordResetBus.consume() }) { Text("取消") } }
        } else null
    )
}
```

- [ ] **Step 4: MainActivity 与 Manifest**

`MainActivity.kt` import：

```kotlin
import android.content.Intent
import com.yanzhong.app.ui.auth.PasswordResetBus
import com.yanzhong.app.ui.auth.PasswordResetHost
```

`onCreate` 在 `setContent` 前加：

```kotlin
        handleDeepLink(intent)
```

主题内容包一层 Box：

```kotlin
            YanZhongTheme(themeMode = themeMode) {
                Box(Modifier.fillMaxSize()) {
                    val auth by app.authState.collectAsStateWithLifecycle()
                    when (auth) {
                        null -> AuthChecking()
                        false -> LoginScreen()
                        else -> ProfileGate()
                    }
                    PasswordResetHost()
                }
            }
```

类内增加：

```kotlin
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "yanzhong" && data.host == "password-reset") {
            PasswordResetBus.offer(data.getQueryParameter("token"))
        }
    }
```

Manifest 的 `MainActivity` 增加第二个 intent-filter：

```xml
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="yanzhong" android:host="password-reset" />
            </intent-filter>
```

- [ ] **Step 5: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: BUILD SUCCESSFUL。

深链自测命令（模拟器）：

```powershell
F:/AndroidSDK/platform-tools/adb.exe shell am start -a android.intent.action.VIEW -d "yanzhong://password-reset?token=demo-token"
```

Expected: App 打开并弹出“设置新密码”（token 无效时提交会显示服务端错误，属正常）。

---

## Task 10: 首页体检卡、顺序调整、面谈预填

**Files:**
- Modify: `ui/nav/AppNav.kt`
- Modify: `ui/plan/PlanInterviewScreen.kt`
- Create: `ui/home/CheckupViewModel.kt`
- Create: `ui/home/PlanCheckupCard.kt`
- Modify: `ui/home/HomeScreen.kt`
- Modify: `ui/home/ReviewCard.kt`（先加 `serverHint` 参数，保证本任务可独立编译）

**Interfaces:**
- Consumes: Task 4 `CheckupResp`、Task 5 `CheckupFormat`。
- Produces: `Routes.planInterview(mode, prefill)`、`CheckupViewModel.ui/dismissForToday/refresh`、`PlanCheckupCard(...)`。

- [ ] **Step 1: 路由支持 prefill**

`AppNav.kt`：

- 顶部 import `android.net.Uri`。
- 把 `PLAN_INTERVIEW` 常量改为：

```kotlin
    const val PLAN_INTERVIEW = "plan_interview?mode={mode}&prefill={prefill}"
```

- 函数改为：

```kotlin
    fun planInterview(mode: String = "", prefill: String = "") =
        "plan_interview?mode=$mode&prefill=${Uri.encode(prefill)}"
```

- `composable` 参数列表增加：

```kotlin
                            androidx.navigation.navArgument("prefill") {
                                type = androidx.navigation.NavType.StringType
                                defaultValue = ""
                            }
```

- 调用增加：

```kotlin
                            prefill = entry.arguments?.getString("prefill").orEmpty()
```

- [ ] **Step 2: 面谈页预填**

`PlanInterviewScreen.kt` 签名增加：

```kotlin
    /** 从首页体检卡带过来的调整描述;空串表示不预填 */
    prefill: String = ""
```

在 `var input by rememberSaveable ...` 之后增加：

```kotlin
    LaunchedEffect(prefill) {
        if (prefill.isNotBlank() && input.isBlank()) input = prefill
    }
```

- [ ] **Step 3: 实现 CheckupViewModel**

新建 `ui/home/CheckupViewModel.kt`：

```kotlin
package com.yanzhong.app.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.CheckupResp
import com.yanzhong.app.data.remote.TokenStore
import com.yanzhong.app.data.remote.apiErrorMessage
import com.yanzhong.app.util.TimeUtils
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CheckupUiState(
    val loading: Boolean = true,
    val checkup: CheckupResp? = null,
    val error: String = "",
    val dismissed: Boolean = false
)

class CheckupViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("plan_checkup", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(CheckupUiState())
    val ui: StateFlow<CheckupUiState> = _ui
    private var loadedForDay: String? = null

    /** 首页拉体检时不触发服务端提醒(notify=0),提醒只走后台 15 分钟轮询,避免打开首页就弹通知 */
    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            if (TokenStore.currentAccess() == null) {
                _ui.update { it.copy(loading = false, checkup = null, error = "") }
                return@launch
            }
            val today = LocalDate.now().toString()
            if (!force && loadedForDay == today && _ui.value.checkup != null) return@launch
            _ui.update { it.copy(loading = true, error = "") }
            runCatching {
                ApiClient.api().planCheckup(
                    tzOffset = TimeUtils.currentTzOffsetMinutes(),
                    notify = 0
                )
            }.fold({ checkup ->
                loadedForDay = today
                _ui.update {
                    it.copy(
                        loading = false,
                        checkup = checkup,
                        error = "",
                        dismissed = prefs.getString(KEY_DISMISSED_DAY, null) == today
                    )
                }
            }, { e ->
                _ui.update { it.copy(loading = false, error = e.apiErrorMessage()) }
            })
        }
    }

    fun dismissForToday() {
        val today = LocalDate.now().toString()
        prefs.edit().putString(KEY_DISMISSED_DAY, today).apply()
        _ui.update { it.copy(dismissed = true) }
    }

    companion object {
        private const val KEY_DISMISSED_DAY = "dismissed_day"
    }
}
```

- [ ] **Step 4: 实现体检卡**

新建 `ui/home/PlanCheckupCard.kt`：

```kotlin
package com.yanzhong.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.remote.CheckupResp
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.SecondaryButton

@Composable
fun PlanCheckupCard(
    checkup: CheckupResp,
    onAdjust: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasIssue = checkup.overdueCount > 0 || checkup.overloadedDays.isNotEmpty()
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    "计划体检",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    CheckupFormat.headline(checkup),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    AppIcons.Close,
                    contentDescription = "今天不再显示",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(CheckupFormat.rate(checkup), style = MaterialTheme.typography.bodySmall)
        Text(
            CheckupFormat.trend(checkup),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            CheckupFormat.load(checkup),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (checkup.suggestion.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(checkup.suggestion, style = MaterialTheme.typography.bodyMedium)
        }
        if (hasIssue) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                text = "去调整计划",
                onClick = onAdjust,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
```

- [ ] **Step 5: 给 WeeklyReviewSheet 增加 serverHint 参数**

`ReviewCard.kt` 的 `WeeklyReviewSheet` 在 `weekGoalMin` 之后增加：

```kotlin
    serverHint: String? = null,
```

在“本周自动盘点”的 `Surface` 之后插入：

```kotlin
        if (!serverHint.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "服务端执行：$serverHint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
```

- [ ] **Step 6: HomeScreen 接入并调整顺序**

import 增加：

```kotlin
import androidx.lifecycle.viewmodel.compose.viewModel
```

（`HomeScreen` 第 48 行已导入 `viewModel` 与 `collectAsStateWithLifecycle`，若执行时已在则跳过。）

在 `val state by vm.uiState...` 附近增加：

```kotlin
    val checkupVm: CheckupViewModel = viewModel()
    val checkupState by checkupVm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(state.hasActivePlan) {
        if (state.hasActivePlan) checkupVm.refresh()
    }
```

手机页（非 `expanded`）：

1. 删除现有 `rhythm-strip` 区块（414-424 行），把它插到 `taskListSection(...)` 之后、`review-card` 之前：

```kotlin
            if (state.hasActivePlan) {
                item(key = "rhythm-strip") {
                    TodayRhythmCard(
                        tpl = todayTemplate,
                        rows = rhythmRows,
                        now = state.now,
                        focusMinutes = state.focusMinutes,
                        doneTaskIds = doneTaskIds
                    )
                }
            }
```

2. 在 `phase-strip` 之后、`today-progress` 之前插入：

```kotlin
            if (state.hasActivePlan) {
                val checkup = checkupState.checkup
                if (checkup != null && !checkupState.dismissed) {
                    item(key = "plan-checkup") {
                        PlanCheckupCard(
                            checkup = checkup,
                            onAdjust = {
                                navController.navigate(
                                    Routes.planInterview(
                                        mode = "adjust",
                                        prefill = checkup.suggestion
                                    )
                                ) { launchSingleTop = true }
                            },
                            onDismiss = { checkupVm.dismissForToday() }
                        )
                    }
                }
            }
```

平板左栏：

1. 在 phase-strip 之后插入同样的 `plan-checkup` item。
2. 把 `review-card` item 移到 `taskListSection` 之后（当前在 taskList 之前）。
3. 平板右栏保持节奏卡不变。

3. 周复盘 Sheet 传入 `serverHint`（参数已在 Step 5 加好）：

```kotlin
        WeeklyReviewSheet(
            initial = state.weeklyReview,
            weekDoneTasks = state.weekDoneTasks,
            weekMinutes = state.weekMinutes,
            weekGoalMin = state.weekGoalMin,
            serverHint = checkupState.checkup?.let { c ->
                if (c.weekPlanTotal > 0) {
                    "本周计划完成 ${c.weekPlanDone}/${c.weekPlanTotal} · 专注 ${c.weekFocusMinutes} 分钟"
                } else {
                    "本周还没有云端计划任务"
                }
            },
            onSave = { weak, t1, t2, t3 -> vm.saveWeeklyReview(weak, t1, t2, t3) },
            onDismiss = { showWeeklyReview = false }
        )
```

- [ ] **Step 7: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: BUILD SUCCESSFUL。`WeeklyReviewSheet` 的参数与调用在同一任务内完成，不会出现中间态编译失败。

---

## Task 11: 统计页“本周执行”卡

**Files:**
- Modify: `ui/stats/StatsViewModel.kt`
- Create: `ui/stats/ServerExecutionCard.kt`
- Modify: `ui/stats/StatsScreen.kt`

**Interfaces:**
- Consumes: Task 4 `CheckupResp`、Task 5 `CheckupFormat`、Task 10 的 `WeeklyReviewSheet(serverHint)`。
- Produces: `StatsViewModel.server: StateFlow<ServerExecutionState>`、`refreshServer()`、`ServerExecutionCard(state, onRetry)`。

- [ ] **Step 1: StatsViewModel 增加服务端执行状态**

在 `StatsUiState` 之后增加：

```kotlin
data class ServerExecutionState(
    val loading: Boolean = true,
    val checkup: com.yanzhong.app.data.remote.CheckupResp? = null,
    val error: String = "",
    val loggedOut: Boolean = false
)
```

类内增加：

```kotlin
    private val _server = MutableStateFlow(ServerExecutionState())
    val server: StateFlow<ServerExecutionState> = _server

    fun refreshServer() {
        viewModelScope.launch {
            if (com.yanzhong.app.data.remote.TokenStore.currentAccess() == null) {
                _server.value = ServerExecutionState(loading = false, loggedOut = true)
                return@launch
            }
            _server.update { it.copy(loading = true, error = "") }
            runCatching {
                com.yanzhong.app.data.remote.ApiClient.api().planCheckup(
                    tzOffset = TimeUtils.currentTzOffsetMinutes(),
                    notify = 0
                )
            }.fold({ checkup ->
                _server.value = ServerExecutionState(loading = false, checkup = checkup)
            }, { e ->
                _server.value = ServerExecutionState(
                    loading = false,
                    error = "暂时读不到服务端执行情况"
                )
            })
        }
    }
```

需要 import：

```kotlin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
```

- [ ] **Step 2: 实现统计卡**

新建 `ui/stats/ServerExecutionCard.kt`：

```kotlin
package com.yanzhong.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.ui.home.CheckupFormat
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.MetricTile
import com.yanzhong.app.ui.theme.SkeletonBlock
import com.yanzhong.app.ui.theme.TextAction
import com.yanzhong.app.util.TimeUtils

@Composable
fun ServerExecutionCard(
    state: ServerExecutionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("本周执行", style = MaterialTheme.typography.titleMedium)
                Text(
                    "来自服务端 · 云端计划与专注记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.loggedOut -> Text(
                "登录后可查看云端执行情况",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.error.isNotBlank() -> {
                Text(
                    state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextAction(text = "重试", onClick = onRetry)
            }
            state.checkup == null -> SkeletonBlock(height = 48.dp)
            else -> {
                val checkup = state.checkup
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(
                        value = if (checkup.weekPlanTotal > 0) {
                            "${checkup.weekPlanDone}/${checkup.weekPlanTotal}"
                        } else "—",
                        label = "本周计划",
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        value = "${checkup.weekActiveDays} 天",
                        label = "本周专注",
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        value = TimeUtils.formatHours(checkup.weekFocusMinutes.toInt()),
                        label = "专注时长",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    CheckupFormat.load(checkup),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (checkup.overloadedDays.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "负荷偏高：${checkup.overloadedDays.joinToString("、") { it.date.substring(5) }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: StatsScreen 接入**

import 增加（若没有）：

```kotlin
import androidx.compose.runtime.LaunchedEffect
```

在 `val state by vm.uiState...` 后增加：

```kotlin
    val server by vm.server.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshServer() }
```

表头文案改为：

```kotlin
                    PageHeader(title = "统计", meta = "图表来自本机，执行情况来自服务端")
```

在 `item(key = "kpi")` 之后插入：

```kotlin
            item(key = "server-execution") {
                ServerExecutionCard(state = server, onRetry = vm::refreshServer)
            }
```

- [ ] **Step 4: 检查周复盘接入**

`WeeklyReviewSheet` 的 `serverHint` 参数和 `HomeScreen` 调用已在 Task 10 完成；本任务只需确认 `ReviewCard.kt` 与 `HomeScreen.kt` 的 `serverHint` 没有遗漏，不再重复改代码。

- [ ] **Step 5: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: BUILD SUCCESSFUL。

---

## Task 12: 计划页错误横幅

**Files:**
- Modify: `ui/plan/PlanViewModel.kt`
- Modify: `ui/plan/PlanScreen.kt`

**Interfaces:**
- Consumes: 现有 `_planError`。
- Produces: `PlanViewModel.clearPlanError()`；READY 态错误横幅。

- [ ] **Step 1: 增加清除方法**

在 `PlanViewModel` 的 `refreshPlan` 附近加：

```kotlin
    fun clearPlanError() {
        _planError.value = null
    }
```

- [ ] **Step 2: READY 分支包一层 Column**

把 `PlanDisplayState.READY -> ServerPlanSummary(...)` 替换为：

```kotlin
                    PlanDisplayState.READY -> Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        planError?.let { message ->
                            PlanErrorBanner(
                                message = message,
                                onRetry = vm::refreshPlan,
                                onDismiss = vm::clearPlanError
                            )
                        }
                        ServerPlanSummary(
                            plan = serverPlan!!,
                            today = todayDate,
                            updatingItemId = updatingItemId,
                            latestAdjustment = latestAdjustment,
                            undoing = undoing,
                            onUndo = vm::undoLatestAdjustment,
                            onToggle = vm::toggleItem,
                            onOpenSetup = { navController.navigate(Routes.planSetup()) },
                            onOpenAssistant = {
                                navController.navigate(Routes.planInterview("adjust"))
                            },
                            onOpenDocument = { navController.navigate(Routes.PLAN_DOCUMENT) },
                            onOpenHistory = { navController.navigate(Routes.PLAN_HISTORY) }
                        )
                    }
```

- [ ] **Step 3: 增加横幅组件**

在 `PlanScreen.kt` 文件底部加：

```kotlin
@Composable
private fun PlanErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextAction(text = "重试", onClick = onRetry)
                Spacer(Modifier.width(8.dp))
                TextAction(text = "关闭", onClick = onDismiss)
            }
        }
    }
}
```

需要 import（检查是否已有）：`androidx.compose.material3.Surface`、`androidx.compose.ui.unit.dp`、`TextAction`、`Spacer`、`width`。`PlanScreen.kt` 已有 `Surface`、`Spacer`、`width`、`TextAction`、`Arrangement`。

- [ ] **Step 4: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: BUILD SUCCESSFUL。

---

## Task 13: 我的页编辑弹窗滚动 + 高度上限

**Files:**
- Modify: `ui/mine/MineScreen.kt`（`PomodoroGoalEditorDialog`、`GoalEditorDialog`、`PlanEditorDialog`）

**Interfaces:**
- 三个弹窗的 `text` 内容统一加 `heightIn(max = screenHeight * 0.7)` + `verticalScroll`。

- [ ] **Step 1: 补 import**

```kotlin
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
```

`heightIn` 已在 `MineScreen.kt:15` 导入，无需重复添加。

- [ ] **Step 2: 修改三个弹窗**

`PomodoroGoalEditorDialog` 的 `text` 改为：

```kotlin
        text = {
            Column(
                Modifier
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.7f).dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { if (count > 1) count-- }) { Text("− 1") }
                    Text("$count", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = { if (count < 30) count++ }) { Text("+ 1") }
                }
            }
        },
```

`GoalEditorDialog` 的 `text` 的 `Column {` 改为：

```kotlin
            Column(
                Modifier
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.7f).dp)
                    .verticalScroll(rememberScrollState())
            ) {
```

`PlanEditorDialog` 的 `text` 的 `Column {` 同样改为带 Modifier 的版本：

```kotlin
            Column(
                Modifier
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.7f).dp)
                    .verticalScroll(rememberScrollState())
            ) {
```

- [ ] **Step 3: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: BUILD SUCCESSFUL。

---

## Task 14: 全量验证与模拟器 QA

**Files:**
- 不新增源码；只跑验证与清单。

- [ ] **Step 1: 服务端全量验证**

```powershell
cd F:\kaoyan-app-prd\server
npm test
npm run typecheck
npm run build
```

Expected: 全部通过；`dist` 可构建。

- [ ] **Step 2: 客户端单测与构建**

```powershell
cd F:\kaoyan-app-prd\YanZhong
.\gradlew.bat :app:testDebugUnitTest -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
.\gradlew.bat :app:assembleDebug -Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK
```

Expected: 单测全绿；`app-debug.apk` 生成。

- [ ] **Step 3: 模拟器安装**

```powershell
F:/AndroidSDK/platform-tools/adb.exe install -r F:\kaoyan-app-prd\YanZhong\app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 4: 9 项手工 QA**

用一次性测试账号登录（不要使用真实用户）：

1. 账号页顺序为：账号头 → 同步状态 → 云端数据 → 登录设备 → 账号安全 → 退出/注销 → 隐私；立即同步能显示“已同步”。
2. 导出账号数据：SAF 保存后文件非空，包含 `profile` / `plan` / `data` 字段。
3. 下载云端备份：保存后文件含 `settings`；用文本查看器确认不是空数组。
4. 恢复：选文件 → 预览摘要 → 点继续 → 输入“恢复” → 确认；完成后提示成功；刻意断网重试一次，应提示“恢复前快照失败，已取消恢复”。
5. 修改密码：旧密码错误时提示“当前密码不正确”；成功后回到登录页；用新密码可登录。测试完改回原密码。
6. 登出全部设备：二次确认后回到登录页；重新登录后设备列表只剩当前设备。
7. 忘记密码：登录页入口可打开；邮箱能力未知时文案不承诺一定发信；提交后有统一成功提示。
8. 首页：体检卡出现在阶段卡下方、进度卡上方；有逾期/超载时可“去调整计划”，面谈输入框已预填；点关闭后当天不再出现。
9. 统计页：表头显示“图表来自本机，执行情况来自服务端”；“本周执行”卡有数据；断网时卡内出现重试且不影响本机图表。

- [ ] **Step 5: 回归检查**

- 计划页勾选任务仍正常（错误横幅只在失败时出现）。
- 周复盘 Sheet 能打开、能保存。
- 我的页三个编辑弹窗在长内容下可滚动。
- 未登录打开统计页不崩溃。

---

## 自检结果（写计划时已执行）

**Spec 覆盖：**

| Spec 项 | 对应任务 |
|---|---|
| A1 同步状态三态 + 立即同步 | Task 6 / 7 |
| A2 账号导出 | Task 4 / 8 |
| A3 云端备份下载 | Task 4 / 8 |
| A4 恢复五步 + 自动快照 + 停/启同步 | Task 8 |
| A5 修改密码 | Task 6 / 7 |
| A6 忘记密码 + 深链重置 | Task 3 / 4 / 9 |
| A7 全设备登出 | Task 6 / 7 |
| B1 体检测算扩展 | Task 1 / 2 |
| B2 首页体检卡 + 关闭 + 去调整 | Task 10 |
| B3 统计页本周执行 | Task 11 |
| B4 周复盘服务端提示 | Task 10 |
| C1 READY 态错误横幅 | Task 12 |
| C2 首页顺序 | Task 10 |
| C3 我的页弹窗滚动 | Task 13 |
| 测试与验收 | Task 14 |
| 服务端向后兼容 | Global Constraints + Task 2 默认 480 / Task 4 默认值 |

**占位检查：** 未使用 TBD/TODO/“类似 Task N”；每个代码步骤都有可直接落地的实现。

**类型一致性：** `CheckupSummary` 字段名与 `CheckupResp` 一一对应；`RestoreResp.resources.*.rejected` 为数组；`WeeklyReviewSheet` 的 `serverHint` 参数与调用都在 Task 10；`Routes.planInterview(mode, prefill)` 两处调用一致。

**已知风险：**

1. 服务端上线前旧客户端仍调用 `/plans/checkup`，新字段只是增量，旧字段不变；`tzOffset` 缺省 480，与旧行为一致。
2. 恢复会覆盖测试账号的数据；QA 前先导出一份账号数据做兜底。
3. 修改密码与全设备登出会清本机 token；测试账号密码必须改回，避免后续无法登录。
4. `CloudDataViewModel.confirmRestore()` 在恢复失败时仍会 `syncer.start()`，这是刻意设计：本地数据可能已部分覆盖，必须继续同步而不是永久停摆。
