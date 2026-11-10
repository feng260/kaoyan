# AI Token 用量计量与展示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 对研钟全部 AI 调用做 token 计量并落库，让用户在对话页看到本轮/今日消耗，让管理后台看到全站、按用户、按功能的用量。

**Architecture:** 在唯一的出站调用点 `chatComplete` 内读取厂商 `usage`（缺失时估算），写入基于 `AsyncLocalStorage` 的请求级会话；`runWithAiUsage` 在请求/后台任务结束时一次 `createMany` 落 `llm_usage` 表，落库失败只告警。路由中间件把 6 个 AI 入口包进会话，响应附 `usage` 汇总；新增用户端与管理端两个汇总接口。

**Tech Stack:** Node 22+/TypeScript/Koa/Prisma/MySQL、node:test + tsx；Android Kotlin/Compose/Retrofit/kotlinx.serialization；管理后台单文件原生 JS。

**Spec:** `docs/superpowers/specs/2026-11-10-ai-token-usage-design.md`

## Global Constraints

- 始终在 `main` 工作，不新建分支。
- **不执行 git commit / git push / 服务端部署 / OTA**：所有任务结束时改动保留在工作区，提交与部署需用户单独批准。本计划中任何“完成”步骤都不含 commit。
- 不把服务器 SSH 密码写进仓库文件或日志；只使用 SSH key。
- 只检查/修改一次性测试账号的数据，不查看其他真实用户数据。
- 本期只计量，不拦截、不限额、不封禁。
- 只存 token 计数、功能标签、模型名与时间；绝不存 prompt、回复文本、IP。
- 所有新增响应字段必须可空/有默认值，旧 App 忽略新字段，新 App 对缺失字段隐藏用量行。
- 服务端单测不得依赖数据库或真实大模型：计量会话测试注入 fake `flush`，`chatComplete` 测试替换 `globalThis.fetch`。
- 用户端 token 数字用千分位（`1,234`），不缩写。
- 文案面向“备考者”，不使用“考研专属”表述；本计划的可见文案只有“AI 用量”等中性词。
- 服务端命令在 `server/` 下执行；Android 构建在 Windows PowerShell 下用 `.\gradlew.bat`，并带 `-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK`。

### 文件结构

**服务端**
- Modify: `server/prisma/schema.prisma` — 新增 `LlmUsage` 模型与 `User.llmUsage` 反向关系
- Modify: `server/src/config/env.ts` — 新增两个可选成本单价
- Modify: `server/.env.example` — 记录成本单价变量
- Create: `server/src/shared/llm/usage.ts` — 估算、归一化、ALS 会话、落库
- Create: `server/src/shared/llm/usage.test.ts`
- Modify: `server/src/shared/llm/client.ts` — 每次 200 记录 usage
- Create: `server/src/shared/llm/client.test.ts`
- Create: `server/src/middlewares/aiUsage.ts` — 请求级计量中间件
- Create: `server/src/middlewares/aiUsage.test.ts`
- Create: `server/src/modules/usage/service.ts` — 时区边界、聚合、用户/管理端查询
- Create: `server/src/modules/usage/service.test.ts`
- Create: `server/src/modules/usage/routes.ts` — `GET /api/v1/usage/ai`
- Modify: `server/src/modules/admin/routes.ts` — `GET /api/v1/admin/usage`
- Modify: `server/src/app.ts` — 注册 usage 路由
- Modify: `server/src/modules/planning/routes.ts` — 5 个同步 AI 路由接入中间件并回传 usage
- Modify: `server/src/modules/planning/service.ts` — 异步生成任务包计量会话并回填 job.usage
- Modify: `server/src/modules/planning/generationJobs.ts` — job 增加 `usage`
- Modify: `server/src/modules/planning/aiCoach.ts` / `adjustIntent.ts` / `adjustL2.ts` / `aiDocument.ts` / `aiGenerator.ts` / `planCritic.ts` / `refineStage.ts` / `timetable.ts` — 传 feature 标签
- Modify: `server/public/admin.html` — “AI 用量”标签页

**客户端**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt` — 用量 DTO、各 AI 响应的可空 usage、`getAiUsage`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AiUsageFormat.kt` — 千分位与状态行文案
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AiUsageSheet.kt` — 用量弹层
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewViewModel.kt` — 本轮/今日/生成用量状态与刷新
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewScreen.kt` — 输入框上方状态行、弹层入口、草稿页本次消耗
- Create: `YanZhong/app/src/test/java/com/yanzhong/app/ui/plan/AiUsageFormatTest.kt`
- Create: `YanZhong/app/src/test/java/com/yanzhong/app/data/remote/AiUsageContractTest.kt`

---

### Task 1: Prisma 数据模型与成本单价配置

**Files:**
- Modify: `server/prisma/schema.prisma`（`User` 模型与文件末尾）
- Modify: `server/src/config/env.ts:44-62`
- Modify: `server/.env.example:46-58`

**Interfaces:**
- Produces: Prisma `llmUsage` 委托（`prisma.llmUsage.createMany/findMany`）、`env.llm.priceInPer1M`、`env.llm.priceOutPer1M`。
- 后续任务依赖：Task 2 的默认 flush 写 `prisma.llmUsage`；Task 4/6 的成本估算读两个单价。

- [ ] **Step 1: 在 `User` 模型加反向关系**

在 `model User { ... }` 的 `plans   Plan[]` 下方加一行：

```prisma
  llmUsage LlmUsage[]
```

- [ ] **Step 2: 在 schema 末尾新增 `LlmUsage` 模型**

```prisma
/// AI token 计量明细:只存计数、功能与模型名,不存任何对话内容
model LlmUsage {
  id               Int      @id @default(autoincrement())
  userGuid         String   @db.VarChar(36)
  feature          String   @db.VarChar(32)
  model            String   @db.VarChar(64)
  promptTokens     Int
  completionTokens Int
  totalTokens      Int
  estimated        Boolean  @default(false)
  createdAt        DateTime @default(now())

  user User @relation(fields: [userGuid], references: [guid], onDelete: Cascade)

  @@index([userGuid, createdAt])
  @@index([createdAt])
  @@map("llm_usage")
}
```

- [ ] **Step 3: 验证并生成 Prisma Client**

Run:
```bash
cd server
npx prisma validate
npx prisma generate
```

Expected: `The schema at prisma/schema.prisma is valid`，generate 输出包含 `LlmUsage`（客户端生成成功即可，不连数据库）。

- [ ] **Step 4: 在 `env.ts` 增加成本单价**

在 `env.ts` 顶部 `pick` 函数下方新增：

```ts
/** 成本单价只接受正的有限数;没配或配错一律按 0(管理端不展示成本) */
function price(value: string | undefined): number {
  const n = Number(value ?? 0)
  return Number.isFinite(n) && n > 0 ? n : 0
}
```

在 `env.llm` 的 `maxTokens` 后增加：

```ts
    /** 可选:按单价估算人民币成本(元/百万 token);0 表示管理端不展示成本 */
    priceInPer1M: price(process.env.LLM_PRICE_IN_PER_1M),
    priceOutPer1M: price(process.env.LLM_PRICE_OUT_PER_1M),
```

- [ ] **Step 5: 更新 `.env.example`**

在 `LLM_MAX_TOKENS=8192` 下方加：

```dotenv
# 可选:管理后台按单价估算 AI 成本(人民币/百万 token);0 或留空表示不展示
LLM_PRICE_IN_PER_1M=0
LLM_PRICE_OUT_PER_1M=0
```

- [ ] **Step 6: 类型检查**

Run: `cd server && npm run typecheck`
Expected: PASS（此时还没有代码引用新字段，应先保持全绿）。

---

### Task 2: 计量核心 `shared/llm/usage.ts`（TDD）

**Files:**
- Create: `server/src/shared/llm/usage.test.ts`
- Create: `server/src/shared/llm/usage.ts`

**Interfaces:**
- Consumes: Prisma `llmUsage.createMany`（Task 1）、`env` 无直接依赖。
- Produces:
  - `type LlmUsageEvent = { feature: string; model: string; promptTokens: number; completionTokens: number; totalTokens: number; estimated: boolean }`
  - `type UsageSummary = { promptTokens: number; completionTokens: number; totalTokens: number; calls: number; estimatedCalls: number }`
  - `type RunWithAiUsageOptions = { onFinish?: (summary: UsageSummary) => void; flush?: (userGuid: string, events: LlmUsageEvent[]) => Promise<void> }`
  - `estimateTokens(text: string): number`
  - `estimatePromptTokens(messages: Array<{ content: string | Array<{ type: string; text?: string }> }>): number`
  - `normalizeUsage(raw: unknown): { promptTokens: number; completionTokens: number; totalTokens: number } | null`
  - `summarizeUsage(events: LlmUsageEvent[]): UsageSummary`
  - `recordLlmUsage(event: LlmUsageEvent): void`
  - `currentUsageSummary(): UsageSummary | null`
  - `runWithAiUsage<T>(userGuid: string, fn: () => Promise<T>, options?: RunWithAiUsageOptions): Promise<T>`

- [ ] **Step 1: 写失败测试**

Create `server/src/shared/llm/usage.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  currentUsageSummary,
  estimatePromptTokens,
  estimateTokens,
  normalizeUsage,
  recordLlmUsage,
  runWithAiUsage,
  summarizeUsage,
  type LlmUsageEvent,
} from './usage'

const event = (patch: Partial<LlmUsageEvent> = {}): LlmUsageEvent => ({
  feature: 'interview',
  model: 'fake-model',
  promptTokens: 10,
  completionTokens: 5,
  totalTokens: 15,
  estimated: false,
  ...patch,
})

test('estimateTokens:纯中文按 0.6/字向上取整', () => {
  assert.equal(estimateTokens('考研计划'), 3) // 4 × 0.6 = 2.4 → 3
})

test('estimateTokens:纯英文按 0.3/字符向上取整', () => {
  assert.equal(estimateTokens('hello world'), 4) // 11 × 0.3 = 3.3 → 4
})

test('estimateTokens:中英混合分别计权', () => {
  assert.equal(estimateTokens('AI 计划'), 3) // 3 × 0.3 + 2 × 0.6 = 2.1 → 3
})

test('estimateTokens:空串为 0', () => {
  assert.equal(estimateTokens(''), 0)
})

test('normalizeUsage:完整 usage 原样归一化', () => {
  assert.deepEqual(
    normalizeUsage({ prompt_tokens: 100, completion_tokens: 50, total_tokens: 150 }),
    { promptTokens: 100, completionTokens: 50, totalTokens: 150 },
  )
})

test('normalizeUsage:缺 total 时用 prompt+completion 补齐', () => {
  assert.deepEqual(
    normalizeUsage({ prompt_tokens: 8, completion_tokens: 2 }),
    { promptTokens: 8, completionTokens: 2, totalTokens: 10 },
  )
})

test('normalizeUsage:没有 usage 返回 null', () => {
  assert.equal(normalizeUsage(undefined), null)
  assert.equal(normalizeUsage(null), null)
  assert.equal(normalizeUsage({}), null)
  assert.equal(normalizeUsage({ prompt_tokens: 'x' }), null)
})

test('estimatePromptTokens:文本按字符、图片每张 1000', () => {
  const tokens = estimatePromptTokens([
    { content: '你好' }, // 2 × 0.6 = 1.2 → 2
    { content: [{ type: 'text', text: 'ok' }] }, // 2 × 0.3 = 0.6 → 1
    { content: [{ type: 'image_url' }] }, // 1000
  ])
  assert.equal(tokens, 1003)
})

test('summarizeUsage:累加计数并统计估算次数', () => {
  assert.deepEqual(summarizeUsage([event(), event({ estimated: true, totalTokens: 20 })]), {
    promptTokens: 20,
    completionTokens: 10,
    totalTokens: 35,
    calls: 2,
    estimatedCalls: 1,
  })
})

test('runWithAiUsage:成功时汇总落库并回调 onFinish', async () => {
  let flushed: LlmUsageEvent[] = []
  let finished = 0
  const result = await runWithAiUsage('u1', async () => {
    recordLlmUsage(event())
    recordLlmUsage(event({ totalTokens: 5 }))
    return 42
  }, {
    onFinish: summary => { finished = summary.calls },
    flush: async (_guid, events) => { flushed = events },
  })
  assert.equal(result, 42)
  assert.equal(flushed.length, 2)
  assert.equal(finished, 2)
})

test('runWithAiUsage:抛错时已发生的用量仍然落库', async () => {
  let flushed = false
  await assert.rejects(
    runWithAiUsage('u1', async () => {
      recordLlmUsage(event())
      throw new Error('boom')
    }, { flush: async () => { flushed = true } }),
    /boom/,
  )
  assert.equal(flushed, true)
})

test('runWithAiUsage:落库失败不影响业务结果', async () => {
  const originalWarn = console.warn
  let warned = ''
  console.warn = (message: string) => { warned = message }
  try {
    const result = await runWithAiUsage('u1', async () => {
      recordLlmUsage(event())
      return 'ok'
    }, { flush: async () => { throw new Error('db down') } })
    assert.equal(result, 'ok')
    assert.match(warned, /用量落库失败/)
  } finally {
    console.warn = originalWarn
  }
})

test('runWithAiUsage:没有用量时不调用 flush', async () => {
  let flushed = false
  const result = await runWithAiUsage('u1', async () => 'ok', {
    flush: async () => { flushed = true },
  })
  assert.equal(result, 'ok')
  assert.equal(flushed, false)
})

test('currentUsageSummary:会话外为 null,会话内为本轮汇总', async () => {
  assert.equal(currentUsageSummary(), null)
  const inside = await runWithAiUsage('u1', async () => {
    recordLlmUsage(event())
    return currentUsageSummary()
  }, { flush: async () => {} })
  assert.equal(inside?.totalTokens, 15)
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && node --import tsx --test src/shared/llm/usage.test.ts`
Expected: FAIL，报 `Cannot find module './usage'`（模块尚不存在）。

- [ ] **Step 3: 实现 `usage.ts`**

Create `server/src/shared/llm/usage.ts`:

```ts
import { AsyncLocalStorage } from 'async_hooks'
import { prisma } from '../prisma'

export type LlmUsageEvent = {
  feature: string
  model: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  estimated: boolean
}

export type UsageSummary = {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  calls: number
  estimatedCalls: number
}

export type RunWithAiUsageOptions = {
  onFinish?: (summary: UsageSummary) => void
  flush?: (userGuid: string, events: LlmUsageEvent[]) => Promise<void>
}

type Store = { events: LlmUsageEvent[] }
const storage = new AsyncLocalStorage<Store>()

/** CJK 字符按 0.6、其余按 0.3 粗略估算;厂商不返回 usage 时兜底 */
export function estimateTokens(text: string): number {
  if (!text) return 0
  const cjk = text.match(/[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/g)?.length ?? 0
  const other = text.length - cjk
  return Math.ceil(cjk * 0.6 + other * 0.3)
}

function toCount(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return null
  return Math.round(value)
}

/** 把厂商返回的 usage 归一化;缺字段或类型不对返回 null,交给估算 */
export function normalizeUsage(raw: unknown): { promptTokens: number; completionTokens: number; totalTokens: number } | null {
  if (!raw || typeof raw !== 'object') return null
  const value = raw as { prompt_tokens?: unknown; completion_tokens?: unknown; total_tokens?: unknown }
  const promptTokens = toCount(value.prompt_tokens)
  const completionTokens = toCount(value.completion_tokens)
  const reportedTotal = toCount(value.total_tokens)
  if (promptTokens === null && completionTokens === null && reportedTotal === null) return null
  const prompt = promptTokens ?? 0
  const completion = completionTokens ?? 0
  return { promptTokens: prompt, completionTokens: completion, totalTokens: reportedTotal ?? prompt + completion }
}

/** 输入消息的估算 prompt:文本按字符估算,图片每张按 1000 tokens */
export function estimatePromptTokens(
  messages: Array<{ content: string | Array<{ type: string; text?: string }> }>,
): number {
  let total = 0
  for (const message of messages) {
    if (typeof message.content === 'string') {
      total += estimateTokens(message.content)
      continue
    }
    for (const part of message.content) {
      if (part.type === 'text') total += estimateTokens(part.text ?? '')
      else if (part.type === 'image_url') total += 1000
    }
  }
  return total
}

export function summarizeUsage(events: LlmUsageEvent[]): UsageSummary {
  return {
    promptTokens: events.reduce((sum, item) => sum + item.promptTokens, 0),
    completionTokens: events.reduce((sum, item) => sum + item.completionTokens, 0),
    totalTokens: events.reduce((sum, item) => sum + item.totalTokens, 0),
    calls: events.length,
    estimatedCalls: events.filter(item => item.estimated).length,
  }
}

/** 无计量会话时为空操作:脚本、单测与未接入路由的调用不受影响 */
export function recordLlmUsage(event: LlmUsageEvent): void {
  storage.getStore()?.events.push(event)
}

export function currentUsageSummary(): UsageSummary | null {
  const store = storage.getStore()
  return store && store.events.length > 0 ? summarizeUsage(store.events) : null
}

async function flushToDb(userGuid: string, events: LlmUsageEvent[]): Promise<void> {
  await prisma.llmUsage.createMany({
    data: events.map(event => ({ userGuid, ...event })),
  })
}

/**
 * 包装一次 AI 请求/后台任务:期间所有 chatComplete 调用都记入本轮会话,
 * 结束时一次落库。计量是旁路,落库失败只告警,绝不改变业务结果。
 */
export async function runWithAiUsage<T>(
  userGuid: string,
  fn: () => Promise<T>,
  options: RunWithAiUsageOptions = {},
): Promise<T> {
  const store: Store = { events: [] }
  try {
    return await storage.run(store, fn)
  } finally {
    const summary = summarizeUsage(store.events)
    try {
      options.onFinish?.(summary)
    } catch (error) {
      console.warn(`[llm] 用量回填失败:${(error as Error)?.message ?? error}`)
    }
    if (store.events.length > 0) {
      try {
        await (options.flush ?? flushToDb)(userGuid, store.events)
      } catch (error) {
        console.warn(`[llm] 用量落库失败:${(error as Error)?.message ?? error}`)
      }
    }
  }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server && node --import tsx --test src/shared/llm/usage.test.ts`
Expected: 全部 PASS，输出无多余告警（落库失败用例已临时静音 `console.warn`）。

- [ ] **Step 5: 类型检查**

Run: `cd server && npm run typecheck`
Expected: PASS。

---

### Task 3: `chatComplete` 采集 usage（TDD）

**Files:**
- Create: `server/src/shared/llm/client.test.ts`
- Modify: `server/src/shared/llm/client.ts:1-60,90-150`
- Modify: `server/src/modules/planning/aiCoach.ts:249`
- Modify: `server/src/modules/planning/adjustIntent.ts:115`
- Modify: `server/src/modules/planning/adjustL2.ts:80`
- Modify: `server/src/modules/planning/aiDocument.ts:223`
- Modify: `server/src/modules/planning/aiGenerator.ts:467`
- Modify: `server/src/modules/planning/planCritic.ts:151`
- Modify: `server/src/modules/planning/refineStage.ts:161`
- Modify: `server/src/modules/planning/timetable.ts:78`

**Interfaces:**
- Consumes: `estimatePromptTokens`、`estimateTokens`、`normalizeUsage`、`recordLlmUsage`（Task 2）。
- Produces: `ChatOptions.feature: string`（必填）；`chatComplete` 每次 HTTP 200 记一条 `LlmUsageEvent`，返回类型仍为 `Promise<string>`。

- [ ] **Step 1: 写失败测试**

Create `server/src/shared/llm/client.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { runWithAiUsage, type LlmUsageEvent } from './usage'

process.env.LLM_API_KEY = 'test-key'
process.env.LLM_BASE_URL = 'https://fake.local'
process.env.LLM_MODEL = 'fake-model'
process.env.DATABASE_URL ??= 'mysql://test:test@127.0.0.1:3306/test'
process.env.JWT_SECRET ??= 'test-secret'

async function loadClient() {
  return import('./client')
}

function stubFetch(responses: Array<{ status?: number; body: unknown }>): () => void {
  const original = globalThis.fetch
  let index = 0
  globalThis.fetch = (async () => {
    const next = responses[Math.min(index++, responses.length - 1)]
    return new Response(JSON.stringify(next.body), {
      status: next.status ?? 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }) as typeof fetch
  return () => { globalThis.fetch = original }
}

const reply = (content: string, usage?: Record<string, number>) => ({
  choices: [{ message: { content }, finish_reason: 'stop' }],
  ...(usage ? { usage } : {}),
})

test('json 空内容重试:每次 200 都记一条,按调用次数累加', async () => {
  const { chatComplete } = await loadClient()
  const restore = stubFetch([
    { body: reply('', { prompt_tokens: 10, completion_tokens: 0, total_tokens: 10 }) },
    { body: reply('好', { prompt_tokens: 20, completion_tokens: 10, total_tokens: 30 }) },
  ])
  let captured: LlmUsageEvent[] = []
  try {
    const text = await runWithAiUsage('u1', () => chatComplete({
      feature: 'interview',
      json: true,
      messages: [{ role: 'user', content: '继续' }],
    }), { flush: async (_guid, events) => { captured = events } })
    assert.equal(text, '好')
    assert.equal(captured.length, 2)
    assert.equal(captured[0].feature, 'interview')
    assert.equal(captured[0].totalTokens, 10)
    assert.equal(captured[1].totalTokens, 30)
  } finally {
    restore()
  }
})

test('400 降预算重试:失败响应不计,成功响应记一条', async () => {
  const { chatComplete } = await loadClient()
  const restore = stubFetch([
    { status: 400, body: { error: { message: 'max_tokens too large' } } },
    { body: reply('ok', { prompt_tokens: 3, completion_tokens: 2, total_tokens: 5 }) },
  ])
  let captured: LlmUsageEvent[] = []
  try {
    const text = await runWithAiUsage('u1', () => chatComplete({
      feature: 'plan_generate',
      messages: [{ role: 'user', content: 'hi' }],
    }), { flush: async (_guid, events) => { captured = events } })
    assert.equal(text, 'ok')
    assert.equal(captured.length, 1)
    assert.equal(captured[0].totalTokens, 5)
  } finally {
    restore()
  }
})

test('厂商缺 usage:按字符估算并标记 estimated', async () => {
  const { chatComplete } = await loadClient()
  const restore = stubFetch([{ body: reply('好') }])
  let captured: LlmUsageEvent[] = []
  try {
    await runWithAiUsage('u1', () => chatComplete({
      feature: 'timetable_ocr',
      messages: [{ role: 'user', content: '你好' }],
    }), { flush: async (_guid, events) => { captured = events } })
    assert.equal(captured.length, 1)
    assert.equal(captured[0].estimated, true)
    assert.equal(captured[0].promptTokens, 2) // 2 × 0.6 = 1.2 → 2
    assert.equal(captured[0].completionTokens, 1) // 1 × 0.6 = 0.6 → 1
    assert.equal(captured[0].totalTokens, 3)
  } finally {
    restore()
  }
})

test('图片消息估算:每张按 1000 tokens', async () => {
  const { chatComplete } = await loadClient()
  const restore = stubFetch([{ body: reply('ok') }])
  let captured: LlmUsageEvent[] = []
  try {
    await runWithAiUsage('u1', () => chatComplete({
      feature: 'timetable_ocr',
      vision: true,
      messages: [{
        role: 'user',
        content: [
          { type: 'text', text: 'ok' },
          { type: 'image_url', image_url: { url: 'data:image/png;base64,xx' } },
        ],
      }],
    }), { flush: async (_guid, events) => { captured = events } })
    assert.equal(captured[0].promptTokens, 1001)
    assert.equal(captured[0].estimated, true)
  } finally {
    restore()
  }
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && node --import tsx --test src/shared/llm/client.test.ts`
Expected: FAIL，前两个用例 `captured.length` 为 0（尚未记录 usage）。

- [ ] **Step 3: 修改 `client.ts`**

在文件顶部加导入：

```ts
import {
  estimatePromptTokens, estimateTokens, normalizeUsage, recordLlmUsage,
  type LlmUsageEvent,
} from './usage'
```

在 `ChatOptions` 中新增必填字段（放在 `messages` 之前）：

```ts
  /** 功能标签(interview / plan_generate / adjust_plan ...),计量与管理端分项统计用 */
  feature: string
```

在 `chatComplete` 函数之前新增：

```ts
/** 200 响应 → 一条计量事件;厂商没给 usage 时按字符估算并打标 */
function toUsageEvent(
  options: ChatOptions,
  model: string,
  rawUsage: unknown,
  content: string | null,
): LlmUsageEvent {
  const normalized = normalizeUsage(rawUsage)
  if (normalized) {
    return { feature: options.feature, model, ...normalized, estimated: false }
  }
  const promptTokens = estimatePromptTokens(options.messages)
  const completionTokens = estimateTokens(content ?? '')
  return {
    feature: options.feature,
    model,
    promptTokens,
    completionTokens,
    totalTokens: promptTokens + completionTokens,
    estimated: true,
  }
}
```

在 `chatComplete` 的循环里，把：

```ts
      payload = await res.json().catch(() => null)
      const message = payload?.choices?.[0]?.message
      const text = message?.content
      content = typeof text === 'string' && text.trim() ? text : null
      if (content !== null) break
```

改为：

```ts
      payload = await res.json().catch(() => null)
      const message = payload?.choices?.[0]?.message
      const text = message?.content
      content = typeof text === 'string' && text.trim() ? text : null
      // 每次拿到 200 都记一条:空内容触发的重试同样被厂商计费,不能漏
      recordLlmUsage(toUsageEvent(options, config.model, payload?.usage, content))
      if (content !== null) break
```

- [ ] **Step 4: 给 8 个调用点加 feature 标签**

在下列每个 `chatComplete({` 的对象字面量第一行加 `feature`（行号可能因 Task 2/3 的改动略有偏移，以 `chatComplete({` 为准）：

| 文件 | feature |
| --- | --- |
| `server/src/modules/planning/aiCoach.ts`（约 249 行） | `'interview'` |
| `server/src/modules/planning/adjustIntent.ts`（约 115 行） | `'adjust_intent'` |
| `server/src/modules/planning/adjustL2.ts`（约 80 行） | `'adjust_plan'` |
| `server/src/modules/planning/aiDocument.ts`（约 223 行） | `'plan_document'` |
| `server/src/modules/planning/aiGenerator.ts`（约 467 行） | `'plan_generate'` |
| `server/src/modules/planning/planCritic.ts`（约 151 行） | `'plan_critic'` |
| `server/src/modules/planning/refineStage.ts`（约 161 行） | `'plan_refine'` |
| `server/src/modules/planning/timetable.ts`（约 78 行） | `'timetable_ocr'` |

例如 `aiCoach.ts`：

```ts
    content = await chatComplete({
      feature: 'interview',
      messages: [
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd server && node --import tsx --test src/shared/llm/client.test.ts`
Expected: 4 个用例全部 PASS。

- [ ] **Step 6: 类型检查与全量单测**

Run:
```bash
cd server
npm run typecheck
npm test
```
Expected: 全部 PASS（`feature` 必填会让漏改的调用点在这里暴露）。

---

### Task 4: 用量聚合与时区服务（TDD）

**Files:**
- Create: `server/src/modules/usage/service.test.ts`
- Create: `server/src/modules/usage/service.ts`

**Interfaces:**
- Consumes: `prisma.llmUsage`（Task 1）、`env.llm.priceInPer1M/priceOutPer1M`（Task 1）、`UsageSummary`（Task 2）。
- Produces:
  - `clampAdminDays(raw: unknown): number`
  - `normalizeUtcOffset(raw: unknown): number`
  - `dayStartForOffset(now: Date, offsetMinutes: number): Date`
  - `monthStartForOffset(now: Date, offsetMinutes: number): Date`
  - `summarizeRows(rows: UsageCountRow[]): UsageSummary`
  - `summarizeByFeature(rows: UsageCountRow[]): UsageFeatureSummary[]`
  - `buildAdminUsage(input): AdminUsage`
  - `getUserUsage(userGuid: string, utcOffsetMinutes: number, now?: Date)`
  - `getAdminUsage(days: number, now?: Date): Promise<AdminUsage>`

- [ ] **Step 1: 写失败测试**

Create `server/src/modules/usage/service.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildAdminUsage,
  clampAdminDays,
  dayStartForOffset,
  estimateCostCny,
  monthStartForOffset,
  normalizeUtcOffset,
  summarizeByFeature,
  summarizeRows,
  type UsageCountRow,
} from './service'

const row = (patch: Partial<UsageCountRow> = {}): UsageCountRow => ({
  userGuid: 'u1',
  feature: 'interview',
  promptTokens: 10,
  completionTokens: 5,
  totalTokens: 15,
  estimated: false,
  createdAt: new Date('2026-11-09T00:00:00.000Z'),
  ...patch,
})

test('时区边界:UTC+8 的凌晨属于当天', () => {
  const now = new Date('2026-11-09T16:30:00.000Z') // 2026-11-10 00:30 +08
  assert.equal(dayStartForOffset(now, 480).toISOString(), '2026-11-09T16:00:00.000Z')
  assert.equal(monthStartForOffset(now, 480).toISOString(), '2026-10-31T16:00:00.000Z')
})

test('时区边界:UTC-5 的深夜仍是前一天', () => {
  const now = new Date('2026-11-10T04:30:00.000Z') // 2026-11-09 23:30 -05
  assert.equal(dayStartForOffset(now, -300).toISOString(), '2026-11-09T05:00:00.000Z')
  assert.equal(monthStartForOffset(now, -300).toISOString(), '2026-11-01T05:00:00.000Z')
})

test('时区偏移越界回落到东八区', () => {
  assert.equal(normalizeUtcOffset(480), 480)
  assert.equal(normalizeUtcOffset(-720), -720)
  assert.equal(normalizeUtcOffset(840), 840)
  assert.equal(normalizeUtcOffset(900), 480)
  assert.equal(normalizeUtcOffset(-999), 480)
  assert.equal(normalizeUtcOffset('abc'), 480)
  assert.equal(normalizeUtcOffset(undefined), 480)
})

test('管理端 days 夹紧到 1..90', () => {
  assert.equal(clampAdminDays(30), 30)
  assert.equal(clampAdminDays('7'), 7)
  assert.equal(clampAdminDays(200), 90)
  assert.equal(clampAdminDays(0), 1)
  assert.equal(clampAdminDays(-5), 1)
  assert.equal(clampAdminDays(7.9), 7)
  assert.equal(clampAdminDays(undefined), 30)
  assert.equal(clampAdminDays('abc'), 30)
})

test('按功能分组并按 tokens 倒序', () => {
  const groups = summarizeByFeature([
    row({ feature: 'interview', totalTokens: 15 }),
    row({ feature: 'interview', totalTokens: 20, estimated: true }),
    row({ feature: 'plan_generate', totalTokens: 100 }),
  ])
  assert.deepEqual(groups.map(group => [group.feature, group.totalTokens, group.calls]), [
    ['plan_generate', 100, 1],
    ['interview', 35, 2],
  ])
  assert.equal(groups[1].estimatedCalls, 1)
})

test('管理端聚合:区间过滤、用户 Top、功能分项与成本', () => {
  const now = new Date('2026-11-10T12:00:00.000Z')
  const rows = [
    row({ userGuid: 'u1', totalTokens: 150, promptTokens: 100, completionTokens: 50 }),
    row({
      userGuid: 'u1', totalTokens: 15, promptTokens: 10, completionTokens: 5,
      estimated: true, createdAt: new Date('2026-11-10T01:00:00.000Z'),
    }),
    row({
      userGuid: 'u2', feature: 'plan_generate', totalTokens: 300, promptTokens: 200, completionTokens: 100,
      createdAt: new Date('2026-11-05T00:00:00.000Z'),
    }),
    row({
      userGuid: 'u2', feature: 'plan_generate', totalTokens: 19998, promptTokens: 9999, completionTokens: 9999,
      createdAt: new Date('2026-10-01T00:00:00.000Z'),
    }),
  ]
  const usage = buildAdminUsage({
    rows,
    users: [{ guid: 'u1', username: 'alice' }, { guid: 'u2', username: 'bob' }],
    days: 7,
    now,
    priceInPer1M: 2000,
    priceOutPer1M: 8000,
  })
  assert.equal(usage.totals.promptTokens, 310)
  assert.equal(usage.totals.completionTokens, 155)
  assert.equal(usage.totals.totalTokens, 465)
  assert.equal(usage.totals.calls, 3)
  assert.equal(usage.totals.estimatedCalls, 1)
  assert.equal(usage.totals.activeUsers, 2)
  assert.deepEqual(usage.topUsers.map(user => [user.username, user.totalTokens]), [
    ['bob', 300],
    ['alice', 165],
  ])
  assert.equal(usage.topUsers[1].lastCallAt, new Date('2026-11-10T01:00:00.000Z').getTime())
  assert.deepEqual(usage.byFeature.map(item => item.feature), ['plan_generate', 'interview'])
  assert.equal(usage.estimatedCostCny, 1.86) // 310/1e6×2000 + 155/1e6×8000
})

test('成本单价为 0 时不展示成本', () => {
  assert.equal(estimateCostCny([row()], 0, 0), null)
})

test('汇总行:计数与估算次数', () => {
  assert.deepEqual(summarizeRows([row(), row({ estimated: true })]), {
    promptTokens: 20,
    completionTokens: 10,
    totalTokens: 30,
    calls: 2,
    estimatedCalls: 1,
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && node --import tsx --test src/modules/usage/service.test.ts`
Expected: FAIL，报 `Cannot find module './service'`。

- [ ] **Step 3: 实现 `service.ts`**

Create `server/src/modules/usage/service.ts`:

```ts
import { prisma } from '../../shared/prisma'
import { env } from '../../config/env'
import type { UsageSummary } from '../../shared/llm/usage'

export type UsageCountRow = {
  userGuid?: string
  feature: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  estimated: boolean
  createdAt?: Date
}

export type UsageFeatureSummary = {
  feature: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  calls: number
  estimatedCalls: number
}

export type AdminUsageUser = {
  userGuid: string
  username: string
  nickname: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  calls: number
  lastCallAt: number
}

export type AdminUsage = {
  days: number
  totals: UsageSummary & { activeUsers: number }
  topUsers: AdminUsageUser[]
  byFeature: UsageFeatureSummary[]
  estimatedCostCny: number | null
}

const DAY_MS = 24 * 60 * 60_000

export function clampAdminDays(raw: unknown): number {
  const value = typeof raw === 'number' ? raw : Number(raw)
  if (!Number.isFinite(value)) return 30
  return Math.min(90, Math.max(1, Math.trunc(value)))
}

export function normalizeUtcOffset(raw: unknown): number {
  const value = typeof raw === 'number' ? raw : Number(raw)
  if (!Number.isInteger(value) || value < -720 || value > 840) return 480
  return value
}

/** 客户端时区偏移(分钟,东八区=480)下的「今天 0 点」对应的 UTC 时刻 */
export function dayStartForOffset(now: Date, offsetMinutes: number): Date {
  const shifted = new Date(now.getTime() + offsetMinutes * 60_000)
  return new Date(Date.UTC(shifted.getUTCFullYear(), shifted.getUTCMonth(), shifted.getUTCDate()) - offsetMinutes * 60_000)
}

/** 客户端时区偏移下的「本月 1 号 0 点」对应的 UTC 时刻 */
export function monthStartForOffset(now: Date, offsetMinutes: number): Date {
  const shifted = new Date(now.getTime() + offsetMinutes * 60_000)
  return new Date(Date.UTC(shifted.getUTCFullYear(), shifted.getUTCMonth(), 1) - offsetMinutes * 60_000)
}

export function summarizeRows(rows: UsageCountRow[]): UsageSummary {
  return {
    promptTokens: rows.reduce((sum, item) => sum + item.promptTokens, 0),
    completionTokens: rows.reduce((sum, item) => sum + item.completionTokens, 0),
    totalTokens: rows.reduce((sum, item) => sum + item.totalTokens, 0),
    calls: rows.length,
    estimatedCalls: rows.filter(item => item.estimated).length,
  }
}

export function summarizeByFeature(rows: UsageCountRow[]): UsageFeatureSummary[] {
  const grouped = new Map<string, UsageCountRow[]>()
  for (const item of rows) {
    const list = grouped.get(item.feature) ?? []
    list.push(item)
    grouped.set(item.feature, list)
  }
  return [...grouped.entries()]
    .map(([feature, list]) => ({ feature, ...summarizeRows(list) }))
    .sort((a, b) => b.totalTokens - a.totalTokens)
}

export function estimateCostCny(rows: UsageCountRow[], priceInPer1M: number, priceOutPer1M: number): number | null {
  if (priceInPer1M <= 0 && priceOutPer1M <= 0) return null
  const prompt = rows.reduce((sum, item) => sum + item.promptTokens, 0)
  const completion = rows.reduce((sum, item) => sum + item.completionTokens, 0)
  return Number((prompt / 1_000_000 * priceInPer1M + completion / 1_000_000 * priceOutPer1M).toFixed(2))
}

/** 纯聚合:管理用量页的全部数字从这里出,便于单测覆盖区间/分组/排序 */
export function buildAdminUsage(input: {
  rows: UsageCountRow[]
  users: Array<{ guid: string; username: string; nickname?: string | null }>
  days: number
  now: Date
  priceInPer1M: number
  priceOutPer1M: number
}): AdminUsage {
  const from = new Date(input.now.getTime() - input.days * DAY_MS)
  const rows = input.rows.filter(item => !item.createdAt || item.createdAt >= from)
  const users = new Map(input.users.map(user => [user.guid, user]))
  const perUser = new Map<string, UsageCountRow[]>()
  for (const item of rows) {
    const guid = item.userGuid ?? ''
    const list = perUser.get(guid) ?? []
    list.push(item)
    perUser.set(guid, list)
  }
  const topUsers = [...perUser.entries()]
    .map(([guid, list]) => {
      const summary = summarizeRows(list)
      return {
        userGuid: guid,
        username: users.get(guid)?.username ?? '(已注销)',
        nickname: users.get(guid)?.nickname ?? '',
        promptTokens: summary.promptTokens,
        completionTokens: summary.completionTokens,
        totalTokens: summary.totalTokens,
        calls: summary.calls,
        lastCallAt: Math.max(...list.map(item => item.createdAt?.getTime() ?? 0)),
      }
    })
    .sort((a, b) => b.totalTokens - a.totalTokens)
    .slice(0, 100)
  return {
    days: input.days,
    totals: { ...summarizeRows(rows), activeUsers: perUser.size },
    topUsers,
    byFeature: summarizeByFeature(rows),
    estimatedCostCny: estimateCostCny(rows, input.priceInPer1M, input.priceOutPer1M),
  }
}

/** 用户本人用量:今日/本月按客户端时区计算,分项统计本月 */
export async function getUserUsage(userGuid: string, utcOffsetMinutes: number, now = new Date()) {
  const todayStart = dayStartForOffset(now, utcOffsetMinutes)
  const monthStart = monthStartForOffset(now, utcOffsetMinutes)
  const rows = await prisma.llmUsage.findMany({
    where: { userGuid, createdAt: { gte: monthStart } },
    select: {
      feature: true, promptTokens: true, completionTokens: true,
      totalTokens: true, estimated: true, createdAt: true,
    },
    orderBy: { createdAt: 'asc' },
  })
  const todayRows = rows.filter(item => item.createdAt >= todayStart)
  return {
    today: summarizeRows(todayRows),
    month: summarizeRows(rows),
    byFeature: summarizeByFeature(rows),
  }
}

export async function getAdminUsage(days: number, now = new Date()): Promise<AdminUsage> {
  const clamped = clampAdminDays(days)
  const from = new Date(now.getTime() - clamped * DAY_MS)
  const rows = await prisma.llmUsage.findMany({
    where: { createdAt: { gte: from } },
    select: {
      userGuid: true, feature: true, promptTokens: true, completionTokens: true,
      totalTokens: true, estimated: true, createdAt: true,
    },
  })
  const guids = [...new Set(rows.map(item => item.userGuid))]
  const users = guids.length
    ? await prisma.user.findMany({
        where: { guid: { in: guids } },
        select: { guid: true, username: true, nickname: true },
      })
    : []
  return buildAdminUsage({
    rows,
    users,
    days: clamped,
    now,
    priceInPer1M: env.llm.priceInPer1M,
    priceOutPer1M: env.llm.priceOutPer1M,
  })
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server && node --import tsx --test src/modules/usage/service.test.ts`
Expected: 全部 PASS。

- [ ] **Step 5: 类型检查**

Run: `cd server && npm run typecheck`
Expected: PASS。

---

### Task 5: 请求级计量中间件（TDD）

**Files:**
- Create: `server/src/middlewares/aiUsage.test.ts`
- Create: `server/src/middlewares/aiUsage.ts`

**Interfaces:**
- Consumes: `runWithAiUsage`、`recordLlmUsage`、`currentUsageSummary`（Task 2）。
- Produces: `aiUsageMeter(options?: RunWithAiUsageOptions)`，返回 Koa 中间件；必须在 `requireAuth` 之后使用。

- [ ] **Step 1: 写失败测试**

Create `server/src/middlewares/aiUsage.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { aiUsageMeter } from './aiUsage'
import { currentUsageSummary, recordLlmUsage, type LlmUsageEvent } from '../shared/llm/usage'

const event: LlmUsageEvent = {
  feature: 'interview',
  model: 'fake-model',
  promptTokens: 1,
  completionTokens: 2,
  totalTokens: 3,
  estimated: false,
}

test('计量中间件:把本轮汇总写进响应并落库', async () => {
  let flushed: LlmUsageEvent[] = []
  const ctx: any = { state: { auth: { userGuid: 'u1', deviceId: 7 } }, body: null }
  await aiUsageMeter({ flush: async (_guid, events) => { flushed = events } })(ctx, async () => {
    recordLlmUsage(event)
    ctx.body = { usage: currentUsageSummary() }
  })
  assert.equal(ctx.body.usage.totalTokens, 3)
  assert.equal(flushed.length, 1)
})

test('未登录时直接放行,不开启计量会话', async () => {
  let flushed = false
  const ctx: any = { state: {} }
  await aiUsageMeter({ flush: async () => { flushed = true } })(ctx, async () => {
    assert.equal(currentUsageSummary(), null)
  })
  assert.equal(flushed, false)
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && node --import tsx --test src/middlewares/aiUsage.test.ts`
Expected: FAIL，报 `Cannot find module './aiUsage'`。

- [ ] **Step 3: 实现中间件**

Create `server/src/middlewares/aiUsage.ts`:

```ts
import { Context, Next } from 'koa'
import { runWithAiUsage, type RunWithAiUsageOptions } from '../shared/llm/usage'

/**
 * AI 请求级计量:处理期间所有 chatComplete 调用记入本轮会话,请求结束后一次落库。
 * 必须注册在 requireAuth 之后;落库失败只告警,不改变请求结果。
 */
export function aiUsageMeter(options: RunWithAiUsageOptions = {}) {
  return async (ctx: Context, next: Next) => {
    const userGuid = ctx.state.auth?.userGuid
    if (!userGuid) {
      await next()
      return
    }
    await runWithAiUsage(userGuid, next, options)
  }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server && node --import tsx --test src/middlewares/aiUsage.test.ts`
Expected: 2 个用例 PASS。

- [ ] **Step 5: 类型检查**

Run: `cd server && npm run typecheck`
Expected: PASS。

---

### Task 6: 用户用量接口与管理端接口

**Files:**
- Create: `server/src/modules/usage/routes.ts`
- Modify: `server/src/modules/admin/routes.ts:1-30,92-116`
- Modify: `server/src/app.ts:9-38`

**Interfaces:**
- Consumes: `getUserUsage`、`normalizeUtcOffset`、`getAdminUsage`、`clampAdminDays`（Task 4）。
- Produces:
  - `GET /api/v1/usage/ai?utcOffsetMinutes=480` → `{ today, month, byFeature, serverTime }`
  - `GET /api/v1/admin/usage?days=30` → `{ days, totals, topUsers, byFeature, estimatedCostCny, serverTime }`

- [ ] **Step 1: 创建用户用量路由**

Create `server/src/modules/usage/routes.ts`:

```ts
import Router from '@koa/router'
import { requireAuth } from '../../middlewares/auth'
import { getUserUsage, normalizeUtcOffset } from './service'

const router = new Router({ prefix: '/api/v1' })

/** 本人 AI token 用量:今日/本月按客户端时区计算,分项统计本月 */
router.get('/usage/ai', requireAuth, async ctx => {
  const offset = normalizeUtcOffset(ctx.query.utcOffsetMinutes)
  ctx.body = { ...await getUserUsage(ctx.state.auth!.userGuid, offset), serverTime: Date.now() }
})

export default router
```

- [ ] **Step 2: 在管理端路由加用量接口**

在 `server/src/modules/admin/routes.ts` 顶部导入：

```ts
import { clampAdminDays, getAdminUsage } from '../usage/service'
```

在 `router.get('/releases', ...)` 之前新增：

```ts
router.get('/usage', async ctx => {
  ctx.body = { ...await getAdminUsage(clampAdminDays(ctx.query.days)), serverTime: Date.now() }
})
```

- [ ] **Step 3: 注册用户路由**

在 `server/src/app.ts` 的 import 区加：

```ts
import usageRoutes from './modules/usage/routes'
```

在 `app.use(planningRoutes.routes()).use(planningRoutes.allowedMethods())` 之后加：

```ts
  app.use(usageRoutes.routes()).use(usageRoutes.allowedMethods())
```

- [ ] **Step 4: 类型检查与全量单测**

Run:
```bash
cd server
npm run typecheck
npm test
```
Expected: 全部 PASS。

- [ ] **Step 5: 路由冒烟（不连真实大模型）**

Run（另开终端启动本地服务；若本地 MySQL 不可用，跳到 Task 11 统一验证）：
```bash
cd server
npm run dev
```
然后：
```bash
curl -s http://127.0.0.1:3000/healthz
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:3000/api/v1/usage/ai
```
Expected: `/healthz` 返回 `ok:true`；`/usage/ai` 未带 token 返回 401（说明路由已注册）。

---

### Task 7: 接入 6 个 AI 入口与异步生成任务

**Files:**
- Modify: `server/src/modules/planning/generationJobs.ts:1-31`
- Modify: `server/src/modules/planning/service.ts:1-31,1546-1571`
- Modify: `server/src/modules/planning/routes.ts:1-12,151-175,198-221,239-249,324-327`

**Interfaces:**
- Consumes: `aiUsageMeter`（Task 5）、`runWithAiUsage`、`currentUsageSummary`（Task 2）。
- Produces: 6 个 AI 入口的响应/任务对象带 `usage`；`PlanGenerationJob.usage?: UsageSummary`。

- [ ] **Step 1: 给 job 类型加 usage**

在 `server/src/modules/planning/generationJobs.ts` 顶部加：

```ts
import type { UsageSummary } from '../../shared/llm/usage'
```

在 `PlanGenerationJob` 的 `horizonDays` 之前加：

```ts
  /** 本次生成消耗的 token 汇总;成功、失败都会回填 */
  usage?: UsageSummary
```

- [ ] **Step 2: 异步生成任务包计量会话**

在 `server/src/modules/planning/service.ts` 顶部加：

```ts
import { runWithAiUsage } from '../../shared/llm/usage'
```

把 `startGeneration` 里的：

```ts
      void (async () => {
        try {
          const plan = await buildPlan(userGuid, progress => push({ progress }), options)
          push({ status: 'done', planId: plan.id, progress: { percent: 100, phase: 'done', message: '计划草稿已生成' } })
        } catch (error) {
          const code = error instanceof ApiError ? error.code : 'PLAN_GENERATION_FAILED'
          const message = error instanceof Error ? error.message : '计划生成失败'
          console.warn(`[planning] 异步生成失败(${code}):${message}`)
          push({ status: 'failed', error: { code, message }, progress: { percent: 100, phase: 'done', message: '生成失败' } })
        }
      })()
```

替换为：

```ts
      let finalPatch: Partial<PlanGenerationJob> | null = null
      void runWithAiUsage(userGuid, async () => {
        try {
          const plan = await buildPlan(userGuid, progress => push({ progress }), options)
          finalPatch = {
            status: 'done',
            planId: plan.id,
            progress: { percent: 100, phase: 'done', message: '计划草稿已生成' },
          }
        } catch (error) {
          const code = error instanceof ApiError ? error.code : 'PLAN_GENERATION_FAILED'
          const message = error instanceof Error ? error.message : '计划生成失败'
          console.warn(`[planning] 异步生成失败(${code}):${message}`)
          finalPatch = {
            status: 'failed',
            error: { code, message },
            progress: { percent: 100, phase: 'done', message: '生成失败' },
          }
        }
      }, {
        // 最终状态与 usage 同一次推送:客户端收到 done/failed 时已经能展示消耗
        onFinish: summary => {
          if (finalPatch) push({ ...finalPatch, usage: summary })
        },
      })
```

- [ ] **Step 3: 给 5 个同步 AI 路由接中间件并回传 usage**

在 `server/src/modules/planning/routes.ts` 顶部导入：

```ts
import { aiUsageMeter } from '../../middlewares/aiUsage'
import { currentUsageSummary } from '../../shared/llm/usage'
```

对以下路由，在 `apiLimit()` 与处理函数之间插入 `aiUsageMeter()`，并在响应体加 `usage: currentUsageSummary()`：

1) `POST /plans/generate`（约 151 行）：

```ts
router.post('/plans/generate', requireAuth, apiLimit(), aiUsageMeter(), async ctx => {
  const parsed = generateSchema.safeParse(ctx.request.body ?? {})
  if (!parsed.success) throw new ApiError(400, 'INVALID_PARAMS', '生成参数不合法')
  const plan = await planningService.generatePlanDraft(ctx.state.auth!.userGuid, dropNulls(parsed.data ?? {}))
  ctx.status = 201
  ctx.body = { plan, usage: currentUsageSummary(), serverTime: Date.now() }
})
```

2) `POST /plans/interview`（约 198 行）：

```ts
router.post('/plans/interview', requireAuth, apiLimit(), aiUsageMeter(), async ctx => {
  const parsed = interviewSchema.safeParse(ctx.request.body ?? {})
  if (!parsed.success) {
    throw new ApiError(400, 'INVALID_PARAMS', '面谈参数不合法')
  }
  const result = await planningService.interview(ctx.state.auth!.userGuid, {
    messages: parsed.data.messages ?? [],
    force: parsed.data.force === true,
  })
  ctx.body = { ...result, usage: currentUsageSummary(), serverTime: Date.now() }
})
```

3) `POST /plans/timetable`（约 214 行）：

```ts
router.post('/plans/timetable', requireAuth, apiLimit(), aiUsageMeter(), async ctx => {
  const parsed = timetableSchema.safeParse(ctx.request.body ?? {})
  if (!parsed.success) {
    throw new ApiError(400, 'INVALID_PARAMS', '课表图片不合法(需要 base64 或链接,且不超过 8mb)')
  }
  const result = await planningService.parseTimetable(ctx.state.auth!.userGuid, parsed.data)
  ctx.body = { ...result, usage: currentUsageSummary(), serverTime: Date.now() }
})
```

4) `POST /plans/adjust`（约 239 行）：只改签名与响应体，函数体保持原样：

```ts
router.post('/plans/adjust', requireAuth, apiLimit(), aiUsageMeter(), async ctx => {
  // ...原有校验与 service 调用保持不动...
  ctx.body = { ...result, usage: currentUsageSummary(), serverTime: Date.now() }
})
```

5) `POST /plans/active/document`（约 324 行）：

```ts
router.post('/plans/active/document', requireAuth, apiLimit(), aiUsageMeter(), async ctx => {
  const document = await planningService.regenerateActiveDocument(ctx.state.auth!.userGuid)
  ctx.body = { document, usage: currentUsageSummary(), serverTime: Date.now() }
})
```

**不要**给 `POST /plans/generate/jobs` 加 `aiUsageMeter()`：该路由立即返回，真正的模型调用在 Task 7 Step 2 的后台会话里。

- [ ] **Step 4: 类型检查与全量单测**

Run:
```bash
cd server
npm run typecheck
npm test
```
Expected: 全部 PASS。

- [ ] **Step 5: 静态确认覆盖的入口**

Run:
```bash
cd server
grep -n "aiUsageMeter()" src/modules/planning/routes.ts
grep -n "currentUsageSummary()" src/modules/planning/routes.ts
```
Expected: `aiUsageMeter()` 恰好 5 处（generate、interview、timetable、adjust、active/document）；`currentUsageSummary()` 恰好 5 处。异步任务由 `runWithAiUsage` 单独覆盖。

---

### Task 8: 客户端 DTO、API 与格式化（TDD）

**Files:**
- Create: `YanZhong/app/src/test/java/com/yanzhong/app/ui/plan/AiUsageFormatTest.kt`
- Create: `YanZhong/app/src/test/java/com/yanzhong/app/data/remote/AiUsageContractTest.kt`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AiUsageFormat.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt:285-333,700-793,840-1065`

**Interfaces:**
- Consumes: 服务端 Task 7 的响应字段。
- Produces:
  - `UsageSummaryDto`、`AiUsageFeatureDto`、`AiUsageResp`
  - `YanzhongApi.getAiUsage(utcOffsetMinutes: Int): AiUsageResp`
  - `AiUsageFormat.tokens(Long): String`、`statusLine(turn, today): String?`、`draftLine(job): String?`、`featureLabel(String): String`
  - `deviceUtcOffsetMinutes(now: Long = System.currentTimeMillis()): Int`

- [ ] **Step 1: 写失败测试**

Create `YanZhong/app/src/test/java/com/yanzhong/app/ui/plan/AiUsageFormatTest.kt`:

```kotlin
package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.UsageSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiUsageFormatTest {
    @Test
    fun `token 数字用千分位不缩写`() {
        assertEquals("0", AiUsageFormat.tokens(0))
        assertEquals("1,234", AiUsageFormat.tokens(1234))
        assertEquals("12,345,678", AiUsageFormat.tokens(12_345_678))
    }

    @Test
    fun `本轮与今日拼成一行`() {
        val line = AiUsageFormat.statusLine(
            UsageSummaryDto(totalTokens = 1234),
            UsageSummaryDto(totalTokens = 12345)
        )
        assertEquals("本轮消耗 1,234 tokens · 今日 12,345", line)
    }

    @Test
    fun `没有数据时整行隐藏`() {
        assertNull(AiUsageFormat.statusLine(null, null))
    }

    @Test
    fun `只有今日数据时不显示本轮`() {
        assertEquals("今日 12,345", AiUsageFormat.statusLine(null, UsageSummaryDto(totalTokens = 12345)))
    }

    @Test
    fun `草稿消耗显示本次总计`() {
        assertEquals("本次生成共消耗 12,345 tokens", AiUsageFormat.draftLine(UsageSummaryDto(totalTokens = 12345)))
        assertNull(AiUsageFormat.draftLine(null))
        assertNull(AiUsageFormat.draftLine(UsageSummaryDto(totalTokens = 0)))
    }

    @Test
    fun `功能标签翻成用户能看懂的模块名`() {
        assertEquals("备考面谈", AiUsageFormat.featureLabel("interview"))
        assertEquals("行程重排", AiUsageFormat.featureLabel("adjust_plan"))
        assertEquals("课表识别", AiUsageFormat.featureLabel("timetable_ocr"))
        assertEquals("custom_feature", AiUsageFormat.featureLabel("custom_feature"))
    }
}
```

Create `YanZhong/app/src/test/java/com/yanzhong/app/data/remote/AiUsageContractTest.kt`:

```kotlin
package com.yanzhong.app.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiUsageContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `旧服务端面谈响应没有 usage 仍可解析`() {
        val resp = json.decodeFromString<InterviewResp>(
            """{"reply":"你好","options":[],"done":false,"serverTime":1}"""
        )
        assertNull(resp.usage)
    }

    @Test
    fun `新服务端面谈 usage 解析`() {
        val resp = json.decodeFromString<InterviewResp>(
            """{"reply":"好","usage":{"promptTokens":1234,"completionTokens":567,"totalTokens":1801,"calls":2,"estimatedCalls":0}}"""
        )
        assertEquals(1801L, resp.usage?.totalTokens)
        assertEquals(2, resp.usage?.calls)
    }

    @Test
    fun `生成任务带 usage 解析`() {
        val job = json.decodeFromString<PlanGenerationJobDto>(
            """{"id":"j1","status":"done","usage":{"promptTokens":10,"completionTokens":5,"totalTokens":15,"calls":1,"estimatedCalls":0}}"""
        )
        assertEquals(15L, job.usage?.totalTokens)
    }

    @Test
    fun `用量响应缺省字段为零`() {
        val resp = json.decodeFromString<AiUsageResp>("""{}""")
        assertEquals(0L, resp.today.totalTokens)
        assertEquals(0, resp.month.calls)
        assertEquals(0, resp.byFeature.size)
    }

    @Test
    fun `用量响应分项解析`() {
        val resp = json.decodeFromString<AiUsageResp>(
            """{"today":{"totalTokens":3},"month":{"totalTokens":30},"byFeature":[{"feature":"interview","totalTokens":20,"calls":2}]}"""
        )
        assertEquals(3L, resp.today.totalTokens)
        assertEquals(30L, resp.month.totalTokens)
        assertEquals("interview", resp.byFeature[0].feature)
        assertEquals(20L, resp.byFeature[0].totalTokens)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run（PowerShell）:
```powershell
cd F:\kaoyan-app-prd\YanZhong
.\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK" :app:testDebugUnitTest --tests "com.yanzhong.app.ui.plan.AiUsageFormatTest" --tests "com.yanzhong.app.data.remote.AiUsageContractTest"
```
Expected: FAIL，编译错误：`AiUsageFormat` / `UsageSummaryDto` / `AiUsageResp` 未定义。

- [ ] **Step 3: 在 `Api.kt` 新增 DTO 与字段**

在 `PlanResp`（约 286 行）附近新增并把 `usage` 加到各响应：

```kotlin
/**
 * 一次 AI 请求/一次生成消耗的 token 汇总。旧服务端不返回 usage:
 * 字段全部有默认值,解析失败/缺省时界面整体隐藏用量行。
 */
@Serializable
data class UsageSummaryDto(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val calls: Int = 0,
    val estimatedCalls: Int = 0
)

@Serializable
data class AiUsageFeatureDto(
    val feature: String = "",
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val calls: Int = 0,
    val estimatedCalls: Int = 0
)

@Serializable
data class AiUsageResp(
    val today: UsageSummaryDto = UsageSummaryDto(),
    val month: UsageSummaryDto = UsageSummaryDto(),
    val byFeature: List<AiUsageFeatureDto> = emptyList(),
    val serverTime: Long = 0
)
```

给以下 DTO 各加一个可空字段：

- `PlanResp`（约 286 行）：`val usage: UsageSummaryDto? = null`
- `PlanGenerationJobDto`（约 301 行）：`val usage: UsageSummaryDto? = null`
- `PlanDocumentResp`（约 333 行）：`val usage: UsageSummaryDto? = null`
- `InterviewResp`（约 702 行）：`val usage: UsageSummaryDto? = null`
- `TimetableResp`（约 725 行）：`val usage: UsageSummaryDto? = null`
- `AdjustResp`（约 784 行）：`val usage: UsageSummaryDto? = null`

在 `YanzhongApi` 的 `getLatestAdjustment()` 附近新增：

```kotlin
    /** 本人 AI token 用量:今日/本月(按设备时区)与本月按功能分项 */
    @GET("api/v1/usage/ai")
    suspend fun getAiUsage(@Query("utcOffsetMinutes") utcOffsetMinutes: Int): AiUsageResp
```

- [ ] **Step 4: 创建 `AiUsageFormat.kt`**

Create `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AiUsageFormat.kt`:

```kotlin
package com.yanzhong.app.ui.plan

import com.yanzhong.app.data.remote.UsageSummaryDto
import java.util.Locale
import java.util.TimeZone

/** AI 用量展示:千分位、不缩写;没有数据时返回 null,让界面整行隐藏 */
object AiUsageFormat {
    fun tokens(value: Long): String = String.format(Locale.US, "%,d", value)

    fun statusLine(turn: UsageSummaryDto?, today: UsageSummaryDto?): String? {
        val parts = buildList {
            turn?.let { add("本轮消耗 ${tokens(it.totalTokens)} tokens") }
            today?.let { add("今日 ${tokens(it.totalTokens)}") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun draftLine(job: UsageSummaryDto?): String? =
        job?.takeIf { it.totalTokens > 0 }?.let { "本次生成共消耗 ${tokens(it.totalTokens)} tokens" }

    /** 服务端功能标签 → 用户能看懂的模块名 */
    fun featureLabel(feature: String): String = when (feature) {
        "interview" -> "备考面谈"
        "adjust_intent" -> "行程理解"
        "adjust_plan" -> "行程重排"
        "plan_generate" -> "计划骨架"
        "plan_refine" -> "阶段细化"
        "plan_critic" -> "计划评审"
        "plan_document" -> "长文档生成"
        "timetable_ocr" -> "课表识别"
        else -> feature
    }
}

/** 设备当前时区偏移(分钟,东八区=480):今日/本月边界按设备时间算 */
internal fun deviceUtcOffsetMinutes(now: Long = System.currentTimeMillis()): Int =
    TimeZone.getDefault().getOffset(now) / 60_000
```

- [ ] **Step 5: 运行测试确认通过**

Run（PowerShell）:
```powershell
cd F:\kaoyan-app-prd\YanZhong
.\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK" :app:testDebugUnitTest --tests "com.yanzhong.app.ui.plan.AiUsageFormatTest" --tests "com.yanzhong.app.data.remote.AiUsageContractTest"
```
Expected: 2 个测试类全部 PASS。

---

### Task 9: 对话页 ViewModel 与 UI

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewViewModel.kt`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AiUsageSheet.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanInterviewScreen.kt`

**Interfaces:**
- Consumes: `UsageSummaryDto`、`AiUsageFeatureDto`、`getAiUsage`（Task 8）、`AiUsageFormat`、`deviceUtcOffsetMinutes`（Task 8）。
- Produces: 对话页“本轮消耗 · 今日”、可点击用量弹层、草稿页“本次生成共消耗”。

- [ ] **Step 1: 扩展 ViewModel 状态**

在 `PlanInterviewViewModel.kt` 的 import 区加：

```kotlin
import com.yanzhong.app.data.remote.AiUsageFeatureDto
import com.yanzhong.app.data.remote.UsageSummaryDto
```

在 `InterviewUiState` 的 `draftOverview` 之后新增：

```kotlin
    /** 最近一轮 AI 对话的 token 消耗;null 表示旧服务端或还没有结果 */
    val turnUsage: UsageSummaryDto? = null,
    /** 今日累计 AI 用量(进入会话与每轮后刷新) */
    val todayUsage: UsageSummaryDto? = null,
    /** 本月累计与按功能分项:用量弹层用 */
    val monthUsage: UsageSummaryDto? = null,
    val usageByFeature: List<AiUsageFeatureDto> = emptyList(),
    /** 用量详情弹层是否可见 */
    val showUsageSheet: Boolean = false,
    /** 最近一次生成(异步或同步回退)的 token 消耗:草稿预览展示 */
    val jobUsage: UsageSummaryDto? = null
```

- [ ] **Step 2: 刷新与弹层方法**

在 `dismissError()` 附近新增：

```kotlin
    fun openUsageSheet() {
        _ui.update { it.copy(showUsageSheet = true) }
        viewModelScope.launch { refreshUsage() }
    }

    fun dismissUsageSheet() { _ui.update { it.copy(showUsageSheet = false) } }

    /** 今日/本月用量是辅助信息:失败静默,不影响对话 */
    private suspend fun refreshUsage() {
        val usage = runCatching { ApiClient.api().getAiUsage(deviceUtcOffsetMinutes()) }.getOrNull() ?: return
        _ui.update {
            it.copy(todayUsage = usage.today, monthUsage = usage.month, usageByFeature = usage.byFeature)
        }
    }
```

在 `beginSession` 的 `accountGuid = ...` 下一行加：

```kotlin
            refreshUsage()
```

- [ ] **Step 3: 在对话结果上写入本轮用量**

`advance()` 的成功分支：

```kotlin
            _ui.update {
                it.copy(messages = snapshot + InterviewMessageDto("assistant", resp.reply),
                    options = if (resp.done) emptyList() else resp.options,
                    // 中途也保留 brief：顶部副标题要显示「已确认了什么」
                    done = resp.done, brief = resp.brief ?: it.brief, sending = false,
                    turnUsage = resp.usage)
            }
            persist()
            refreshUsage()
```

`adjust()` 的成功分支：

```kotlin
            _ui.update {
                it.copy(messages = it.messages + InterviewMessageDto("assistant", resp.reply.orEmpty()),
                    adjustment = resp.adjustment ?: it.adjustment, sending = false,
                    clarifyOptions = resp.clarifyOptions.orEmpty(), lockedConflict = null,
                    turnUsage = resp.usage)
            }
            persist()
            refreshUsage()
```

- [ ] **Step 4: 生成任务的消耗回填**

`startGeneration` 的起始状态加 `jobUsage = null`：

```kotlin
        _ui.update {
            it.copy(generating = true, error = null, activePlan = null, activeCompared = false,
                draftOverview = null, jobUsage = null, progress = 0, progressMessage = "正在创建生成任务…",
                showCapacityDialog = false, capacityIssue = null, elapsedSeconds = 0)
        }
```

`applyJob` 的第一处 `_ui.update` 加 `jobUsage`：

```kotlin
        _ui.update {
            it.copy(progress = job.progress.percent,
                progressMessage = job.progress.message.takeIf { msg -> msg.isNotBlank() },
                jobUsage = job.usage ?: it.jobUsage)
        }
```

`legacyGenerate` 改为先保留完整响应再取 plan：

```kotlin
    /** 旧服务端(无 /plans/generate/jobs):保持升级前的同步生成行为 */
    private suspend fun legacyGenerate(briefHash: String?, allowShortfall: Boolean) {
        val body = GeneratePlanReq(
            briefHash = briefHash,
            allowShortfall = allowShortfall.takeIf { it }
        )
        val resp = runCatching { ApiClient.aiApi().generatePlan(body) }.getOrElse { e ->
            failGeneration("草稿生成失败：${e.userMessage()}。可继续纠正后重试")
            return
        }
        val draft = resp.plan?.takeIf { p -> p.status == "draft" }
        if (draft == null) {
            failGeneration("草稿生成失败：服务端没有返回草稿。可继续纠正后重试")
            return
        }
        stopElapsedTicker()
        _ui.update { it.copy(phase = InterviewPhase.DRAFT, plan = draft, generating = false,
            progress = null, progressMessage = null, etaMinutes = null,
            generationStartedAt = null, elapsedSeconds = 0, error = null, jobUsage = resp.usage) }
        // 草稿仍留在服务端；本地只记「面谈已完成」，退出后再进来还能一键重新拉草稿
        persist()
        loadActiveComparison()
        loadDraftOverview(draft.id)
    }
```

- [ ] **Step 5: 创建用量弹层**

Create `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AiUsageSheet.kt`:

```kotlin
package com.yanzhong.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanzhong.app.data.remote.AiUsageFeatureDto
import com.yanzhong.app.data.remote.UsageSummaryDto
import com.yanzhong.app.ui.theme.AppCard
import com.yanzhong.app.ui.theme.AppSheet

/** AI 用量详情:今日/本月汇总 + 本月按功能分项 */
@Composable
fun AiUsageSheet(
    today: UsageSummaryDto?,
    month: UsageSummaryDto?,
    byFeature: List<AiUsageFeatureDto>,
    onDismiss: () -> Unit,
) {
    AppSheet(title = "AI 用量", onDismiss = onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UsageCard("今日", today, Modifier.weight(1f))
            UsageCard("本月", month, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        if (byFeature.isEmpty()) {
            Text("本月还没有 AI 调用记录", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("本月按功能", style = MaterialTheme.typography.labelLarge)
            byFeature.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(AiUsageFormat.featureLabel(item.feature),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Text("${AiUsageFormat.tokens(item.totalTokens)} tokens · ${item.calls} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("只统计 token 数量,不记录对话内容", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UsageCard(title: String, summary: UsageSummaryDto?, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${AiUsageFormat.tokens(summary?.totalTokens ?: 0)} tokens",
            style = MaterialTheme.typography.titleMedium)
        Text(
            "${summary?.calls ?: 0} 次调用" +
                if ((summary?.estimatedCalls ?: 0) > 0) " · 估算 ${summary!!.estimatedCalls} 次" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

- [ ] **Step 6: 对话页加状态行与弹层**

在 `PlanInterviewScreen.kt` 的 import 区加：

```kotlin
import androidx.compose.foundation.clickable
```

在底部输入条 `Row(verticalAlignment = Alignment.CenterVertically) {`（约 262 行）之前插入：

```kotlin
                            AiUsageFormat.statusLine(state.turnUsage, state.todayUsage)?.let { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = vm::openUsageSheet)
                                        .padding(vertical = 4.dp)
                                )
                            }
```

在 `state.showCapacityDialog` 弹窗块之后、`state.lockedConflict` 之前插入：

```kotlin
        if (state.showUsageSheet) {
            AiUsageSheet(
                today = state.todayUsage,
                month = state.monthUsage,
                byFeature = state.usageByFeature,
                onDismiss = vm::dismissUsageSheet
            )
        }
```

- [ ] **Step 7: 草稿页显示本次生成消耗**

在 `PlanInterviewScreen.kt` 的 `DraftContent` 调用（约 285 行）加参数：

```kotlin
                        estimatedSubjects = state.brief?.examSubjects?.filter { it.estimated }?.map { it.name }.orEmpty(),
                        usage = state.jobUsage,
```

`DraftContent` 签名加：

```kotlin
    estimatedSubjects: List<String>,
    usage: com.yanzhong.app.data.remote.UsageSummaryDto?,
```

在 `if (!fullscreen) { Column(...) { ... } }` 头部区域（`Text(draft.title, ...)` 之后）加：

```kotlin
            AiUsageFormat.draftLine(usage)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
```

- [ ] **Step 8: 构建验证**

Run（PowerShell）:
```powershell
cd F:\kaoyan-app-prd\YanZhong
.\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK" :app:testDebugUnitTest
.\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK" :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`，单测全绿。

---

### Task 10: 管理后台“AI 用量”标签页

**Files:**
- Modify: `server/public/admin.html:56-91,124-173,226`

**Interfaces:**
- Consumes: `GET /api/v1/admin/usage?days=7|30`（Task 6）。
- Produces: 7/30 天切换、汇总卡、按用户表、按功能表。

- [ ] **Step 1: 加导航标签与区块**

`nav` 中 `users` 与 `releases` 之间加：

```html
    <a data-tab="usage">AI 用量</a>
```

`tab-users` 区块之后加：

```html
    <section id="tab-usage" style="display:none">
      <div class="row">
        <button id="usage7" class="ghost" onclick="loadUsage(7)">近 7 天</button>
        <button id="usage30" class="ghost" onclick="loadUsage(30)">近 30 天</button>
      </div>
      <div class="cards" id="usageCards"></div>
      <h3 style="font-size:14px;margin:0 0 10px">按用户</h3>
      <table style="margin-bottom:24px">
        <thead><tr><th>用户名</th><th>总 tokens</th><th>prompt / completion</th><th>调用次数</th><th>最后调用</th></tr></thead>
        <tbody id="usageUserRows"></tbody>
      </table>
      <h3 style="font-size:14px;margin:0 0 10px">按功能</h3>
      <table>
        <thead><tr><th>功能</th><th>总 tokens</th><th>prompt / completion</th><th>调用次数</th></tr></thead>
        <tbody id="usageFeatureRows"></tbody>
      </table>
    </section>
```

- [ ] **Step 2: 加 JS 加载逻辑**

在 `const fmtMB = ...` 下方加：

```js
const fmtNum = n => Number(n || 0).toLocaleString('zh-CN')
let usageDays = 30
const featureLabel = f => ({
  interview:'备考面谈', adjust_intent:'行程理解', adjust_plan:'行程重排',
  plan_generate:'计划骨架', plan_refine:'阶段细化', plan_critic:'计划评审',
  plan_document:'长文档生成', timetable_ocr:'课表识别'
}[f] || f)
```

`enter()` 的 `Promise.all` 改为：

```js
  await Promise.all([loadStats(), loadUsers(), loadReleases(), loadUsage()])
```

`switchTab` 的数组改为：

```js
  ;['overview','users','releases','usage'].forEach(t => $('#tab-' + t).style.display = t === name ? '' : 'none')
```

在 `// ---- 版本 ----` 之前加：

```js
// ---- AI 用量 ----
async function loadUsage(days = usageDays) {
  usageDays = days
  $('#usage7').className = days === 7 ? '' : 'ghost'
  $('#usage30').className = days === 30 ? '' : 'ghost'
  const u = await api('/admin/usage?days=' + days)
  const cards = [
    ['总 tokens', fmtNum(u.totals.totalTokens)],
    ['调用次数', fmtNum(u.totals.calls)],
    ['活跃用户', fmtNum(u.totals.activeUsers)],
    ['估算调用占比', u.totals.calls ? Math.round(u.totals.estimatedCalls * 100 / u.totals.calls) + '%' : '0%'],
  ]
  if (u.estimatedCostCny != null) cards.push(['预估成本', '¥' + u.estimatedCostCny.toFixed(2)])
  $('#usageCards').innerHTML = cards.map(([l, v]) =>
    `<div class="card"><div class="v">${v}</div><div class="l">${l}</div></div>`).join('')
  $('#usageUserRows').innerHTML = u.topUsers.map(r => `<tr>
    <td><b>${esc(r.username)}</b>${r.nickname ? ' <span class="muted">' + esc(r.nickname) + '</span>' : ''}</td>
    <td>${fmtNum(r.totalTokens)}</td>
    <td class="muted">${fmtNum(r.promptTokens)} / ${fmtNum(r.completionTokens)}</td>
    <td>${fmtNum(r.calls)}</td>
    <td class="muted">${r.lastCallAt ? fmtT(r.lastCallAt) : '—'}</td>
  </tr>`).join('') || '<tr><td colspan="5" class="muted">暂无用量</td></tr>'
  $('#usageFeatureRows').innerHTML = u.byFeature.map(r => `<tr>
    <td><b>${esc(featureLabel(r.feature))}</b> <span class="muted">${esc(r.feature)}</span></td>
    <td>${fmtNum(r.totalTokens)}</td>
    <td class="muted">${fmtNum(r.promptTokens)} / ${fmtNum(r.completionTokens)}</td>
    <td>${fmtNum(r.calls)}</td>
  </tr>`).join('') || '<tr><td colspan="4" class="muted">暂无用量</td></tr>'
}
```

- [ ] **Step 3: 静态检查**

Run:
```bash
cd server
node -e "const fs=require('fs');const html=fs.readFileSync('public/admin.html','utf8');const js=html.split('<script>')[1].split('</script>')[0];new Function(js);console.log('admin.html inline JS parses')"
```
Expected: `admin.html inline JS parses`。

- [ ] **Step 4: 管理页手动验收（需本地或已部署服务端 + 管理员令牌）**

打开 `http://<服务器>/admin`，登录后切到“AI 用量”：
- 7/30 天按钮能切换，汇总卡出现；
- 生成过 AI 用量的测试账号出现在“按用户”表；
- “按功能”表能看到 `interview` 等分项且中文名正确；
- 浏览器控制台无报错。
若服务端尚未部署本期改动，此步记录了待部署后复验，不阻塞工作区交付。

---

### Task 11: 全量验证与端到端清单

**Files:**
- 无新增；验证 Task 1–10 的产物。

- [ ] **Step 1: 服务端全量验证**

Run:
```bash
cd server
npm run verify
```
Expected:
- `tsc -p tsconfig.test.json` 与 `tsc --noEmit` 均无错误；
- `node --import tsx --test "src/**/*.test.ts"` 全部 PASS；
- `prisma generate && tsc` 构建成功。

- [ ] **Step 2: 客户端全量验证**

Run（PowerShell）:
```powershell
cd F:\kaoyan-app-prd\YanZhong
.\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK" :app:testDebugUnitTest
.\gradlew.bat "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK" :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 端到端走查（需用户先批准部署；未批准则记录为待办）**

部署后用测试账号在模拟器验证：

1. 进入“AI 备考面谈”，发一轮消息：
   - 输入框上方出现 `本轮消耗 X tokens · 今日 Y`；
   - 点击该行弹出“AI 用量”，今日/本月/按功能有数字；
   - 再发一轮，本轮数字变化、今日累计增加。
2. 进入“AI 行程小助手”，发一句“明天要出门”：
   - 回复后同样出现本轮消耗；
   - `adjust_intent`（必要时 `adjust_plan`）出现在用量弹层的分项里。
3. 生成计划草稿（异步任务）：
   - 完成后草稿页标题下出现 `本次生成共消耗 X tokens`；
   - `SELECT feature, SUM(total_tokens) FROM yanzhong.llm_usage WHERE user_guid='<测试账号guid>' GROUP BY feature;` 的行与页面数字一致（只查测试账号）。
4. 管理后台“AI 用量”：
   - 能看到该测试用户与功能分项；
   - 7/30 天切换后总数变化符合预期。
5. 计量故障降级（单测已覆盖，端到端可选）：
   - 不修改生产库；以 `client.test.ts` 的“落库失败不影响业务结果”用例为证据。

- [ ] **Step 4: 汇总变更、等待批准**

Run:
```bash
cd F:\kaoyan-app-prd
git status --short
git diff --stat
```
Expected: 只包含本计划列出的文件；**不执行 commit / push / 部署**，向用户报告并等待单独批准。

---

## 自审记录

**1. Spec 覆盖**

| Spec 要求 | 对应任务 |
| --- | --- |
| 所有 AI 调用采集、重试不漏计 | Task 3（每次 200 记录）、Task 7（6 入口） |
| 请求级收集器与落库 | Task 2、Task 5 |
| `llm_usage` 模型 | Task 1 |
| 用户今日/本月与分项接口 | Task 4、Task 6 |
| 管理端聚合、Top 用户、成本 | Task 4、Task 6、Task 10 |
| 客户端 DTO/兼容旧服务端 | Task 8 |
| 对话页本轮/今日、弹层、草稿消耗 | Task 9 |
| 生成任务失败也有 usage | Task 7 Step 2（onFinish 无条件回填） |
| 隐私（不存内容/IP） | Global Constraints + Task 1 表结构 |
| 计量故障不影响 AI | Task 2（flush 异常吞掉并告警）+ 单测 |
| 不拦截 | 未实现任何额度/拦截逻辑 |
| 时区边界 | Task 4 纯函数 + 测试 |
| 成本单价配置 | Task 1、Task 4 |

**2. Placeholder 扫描**：无 TBD/TODO；每个代码步骤都给出完整代码或精确替换位置。

**3. 类型一致性**：
- 服务端事件/汇总字段 `promptTokens|completionTokens|totalTokens|calls|estimatedCalls` 在 Task 2、4、5、6、7 一致。
- 客户端 `UsageSummaryDto` 字段名与 JSON 完全对齐（camelCase，kotlinx.serialization 默认同名）。
- `feature` 标签在 Task 3 调用点与 Task 8 `featureLabel`、Task 10 管理页映射一一对应。
- `aiUsageMeter()` 在 Task 5 定义，Task 7 使用；`currentUsageSummary()` 在 Task 2 定义，Task 7 使用。
- `RunWithAiUsageOptions` 在 Task 2 导出，Task 5 复用。
