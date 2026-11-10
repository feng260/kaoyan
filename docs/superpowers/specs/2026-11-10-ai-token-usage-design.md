# AI Token 用量计量与展示 设计文档

日期：2026-11-10
状态：已与产品负责人确认，待实现

## 1. 背景与目标

研钟的 AI 能力（备考面谈、行程调整、计划生成、阶段细化、文档生成、课表 OCR）全部走同一个
OpenAI 兼容客户端 `server/src/shared/llm/client.ts`，但当前：

- 厂商返回的 `usage`（prompt/completion/total tokens）被直接丢弃，服务端没有任何 token 计量；
- 仅有的防护是按 IP 的 60 次/分钟限流，不分用户、不看单次成本；
- 一次计划生成会串行/并行调用模型多次（骨架→细化→校验→评审→文档），真实成本不可见。

产品负责人的诉求：用户在 App 里使用 AI 智能体时能看到 token 消耗；运营方能在管理后台看到每个
用户、每个功能花了多少 token，因为长期免费不可承受。

本期目标：

1. 服务端对**所有** AI 调用做 token 计量并落库，重试与失败前的计费调用不漏计；
2. 用户在 AI 对话页看到本轮与今日累计 token，在计划生成完成后看到本次生成总消耗；
3. 管理后台看到全站/按用户/按功能的用量与可选的人民币成本估算。

## 2. 非目标（本期明确不做）

- 不做额度检查、拦截、封禁；不做充值、兑换码、支付。计量是后续收费的前置基础设施。
- 不存储对话内容、prompt、回复文本、IP；只存计数。
- 不做历史会话存储、导出、按天汇总表。
- 不做全站预算告警（后续可基于同一边界添加）。

## 3. 已确认的产品决策

| 决策 | 选择 |
| --- | --- |
| 免费额度用完后 | 先只计量不拦截，拿到真实数据再定收费方式 |
| 展示范围 | 用户端 + 管理端 |
| 计量范围 | 全量计量；对话页展示每轮与今日/本月累计；生成类功能完成后展示本次消耗 |
| 计量架构 | 请求级收集器（AsyncLocalStorage） |

## 4. 架构

### 4.1 采集点在 LLM 客户端内部

`chatComplete` 是唯一出站调用点。厂商返回的 `usage` 只在 HTTP 200 的响应体里，而现有实现含
自适应重试（400/422 降预算、空内容换 JSON/翻倍预算，最多 5 次）。因此：

- 每次拿到 200 响应（无论内容是否为空、是否触发重试）都读取 `payload.usage` 并记一条计数；
- 一次 `chatComplete` 调用可能对应多条计数（重试次数），这与厂商计费一致；
- 若响应没有 `usage`，按字符估算并标记 `estimated=true`；
- 采集函数在无计量会话时为空操作（脚本、单测不受影响）。

### 4.2 请求级计量会话

新增 `server/src/shared/llm/usage.ts`：

```ts
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

export function runWithAiUsage<T>(
  userGuid: string,
  fn: () => Promise<T>,
  options?: { onFinish?: (summary: UsageSummary) => void; flush?: (userGuid: string, events: LlmUsageEvent[]) => Promise<void> },
): Promise<T>
```

- 用 `AsyncLocalStorage` 保存当前请求的事件数组；`recordLlmUsage(feature, usage)` 由
  `chatComplete` 调用；
- `runWithAiUsage` 在 `finally` 中统一落库（一次 `createMany`），并调用 `onFinish(summary)`；
- 落库失败只 `console.warn`，绝不改变 AI 请求的结果；
- `currentUsageSummary()` 供路由把本轮/本次汇总写进响应；无会话时返回 `null`。

### 4.3 覆盖的入口

| 入口 | 包装方式 | feature 标签 |
| --- | --- | --- |
| `POST /plans/generate` | 路由中间件 | `plan_generate` / `plan_refine` / `plan_critic` / `plan_document` |
| `POST /plans/generate/jobs` | 后台任务 IIFE 外层显式包装 | 同上 |
| `POST /plans/interview` | 路由中间件 | `interview` |
| `POST /plans/timetable` | 路由中间件 | `timetable_ocr` |
| `POST /plans/adjust` | 路由中间件 | `adjust_intent` / `adjust_plan` |
| `POST /plans/active/document` | 路由中间件 | `plan_document` |

`adjustments/:id/select|confirm|undo` 不调用模型，不包装。

路由中间件 `aiUsageMeter()` 从 `ctx.state.auth.userGuid` 取用户，包裹 `await next()`；
路由在处理完后用 `currentUsageSummary()` 把汇总附到响应体。

异步生成任务在 `generationJobs` 的 job 对象上新增 `usage?: UsageSummary`；`runWithAiUsage`
的 `onFinish` 无论成功失败都回填，客户端轮询/WS 均可拿到。

### 4.4 估算规则（厂商未返回 usage 时）

- 文本 token ≈ CJK 字符数 × 0.6 + 其他字符数 × 0.3，向上取整；
- 图片消息每张按 1000 tokens 计；
- prompt 由输入消息文本累加，completion 由返回文本估算；
- `estimated=true`，管理端展示估算调用占比。

## 5. 数据模型

```prisma
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

`User` 增加反向关系 `llmUsage LlmUsage[]`。表只存数字、功能名与模型名，不含任何内容。

部署沿用现有 `npx prisma db push`（Dockerfile CMD 自动执行），不写迁移文件。
本期不设自动清理（数据量小），后续需要时再加按天汇总与明细过期策略。

## 6. 服务端接口

### 6.1 对话/生成响应新增字段

`InterviewResp`、`AdjustResp`、`TimetableResp`、同步生成与文档生成的响应新增：

```json
"usage": {
  "promptTokens": 1234,
  "completionTokens": 567,
  "totalTokens": 1801,
  "calls": 2,
  "estimatedCalls": 0
}
```

异步生成任务的 `job` 对象新增同样的 `usage` 字段（失败任务也有）。

### 6.2 用户用量汇总

`GET /api/v1/usage/ai?utcOffsetMinutes=480`（需登录）

- `utcOffsetMinutes`：整数，范围 -720..840，默认 480；服务端据此计算「今日/本月」边界；
- 返回：

```json
{
  "today": { "promptTokens": 0, "completionTokens": 0, "totalTokens": 0, "calls": 0, "estimatedCalls": 0 },
  "month": { "...": 0 },
  "byFeature": [{ "feature": "interview", "totalTokens": 0, "calls": 0 }],
  "serverTime": 0
}
```

`byFeature` 统计本月。用户只能读自己的数据。

### 6.3 管理端用量

`GET /api/v1/admin/usage?days=30`（`X-Admin-Token`）

- `days` 限制 1..90；
- 返回全站汇总、按用户 Top 100（用户名、总 tokens、prompt/completion、调用次数、最后调用时间）、
  按功能分项；
- 若配置 `LLM_PRICE_IN_PER_1M` 与 `LLM_PRICE_OUT_PER_1M`（人民币/百万 token，默认 0 表示不展示），
  额外返回 `estimatedCostCny`。

## 7. 客户端

### 7.1 数据层

- `data/remote/Api.kt`：新增 `UsageSummaryDto`（字段全部有默认值）与 `AiUsageResp`；
  给 `InterviewResp`、`AdjustResp`、`TimetableResp`、`PlanGenerationJobDto`、文档响应加可空 `usage`；
  新增 `getAiUsage(utcOffsetMinutes)`。
- 旧服务端不返回 `usage` 时，字段为 null，界面整体隐藏该行，不影响任何现有功能。

### 7.2 对话页

`PlanInterviewViewModel`：

- 新增 `turnUsage: UsageSummaryDto?`、`todayUsage: UsageSummaryDto?`；
- `advance()` 与 `adjust()` 收到响应后写入本轮用量，并异步刷新今日汇总（失败静默）；
- 进入会话时刷新一次今日汇总；
- 计划生成任务完成/失败时记录 `jobUsage`。

`PlanInterviewScreen`：

- 输入框上方加一行弱化的状态文字：`本轮消耗 1,234 tokens · 今日 12,345`；
- 点击该行弹出「AI 用量」底部弹层：今日、本月汇总与按功能分项（数据来自 `GET /usage/ai`）；
- 无数据时整行不渲染；
- 草稿预览阶段显示：`本次生成共消耗 12,345 tokens`（来自 job.usage）。

数字格式：千分位（`1,234`），不缩写。

### 7.3 其他 AI 功能

课表 OCR 与文档生成的计量在服务端完成，本期不在结果页展示（避免干扰确认表单），管理端可查。

## 8. 管理后台

`server/public/admin.html` 新增「AI 用量」标签页：

- 7 天 / 30 天切换；
- 汇总卡：总 tokens、预估成本（配置了价格才显示）、活跃用户数、估算调用占比；
- 按用户表：用户名、总 tokens、prompt/completion、调用次数、最后调用时间；
- 按功能表：功能、tokens、调用次数。

沿用现有单文件、原生 JS、`api()` 封装与样式体系。

## 9. 错误处理与边界

- 计量落库失败：`console.warn` 后继续，AI 请求照常返回；
- 厂商不返回 usage：估算并标记，绝不抛错；
- 后台生成任务失败/崩溃：`finally` 仍落已发生的用量，job 上回填；
- 时区：日/月边界按客户端传入偏移计算，避免服务器 UTC 造成「跨天」误判；
- 隐私：不记录 prompt/回复/IP；用户只能读自己的；管理端只读聚合与用户名；
- 兼容：旧 App 忽略新字段；新 App 对缺失字段降级隐藏。

## 10. 测试计划（TDD）

服务端单测（node:test）：

1. `estimateTokens`：纯中文/纯英文/混合/空串；
2. usage 归一化：完整 usage、缺 total、缺 usage、视觉得到 usage；
3. 重试累加：fake fetch 连续返回带 usage 的空内容与正常内容，事件数 = 实际 200 次数；
4. `runWithAiUsage`：成功与抛错都落库；flush 抛错不影响业务结果；
5. 汇总时区：UTC+8 的 00:30 属于当天、UTC-5 的边界正确；
6. 管理端聚合：按用户/功能/区间正确、`days` 越界被夹紧；
7. 路由层：面谈响应含 `usage.turn`（fake LLM）。

客户端单测：

1. 千分位格式化；
2. `UsageSummaryDto` 缺省字段解析。

端到端（本地服务端 + 模拟器）：

1. 面谈/调整一轮 → 页面出现本轮与今日数字；
2. `llm_usage` 表出现对应行，数字与本地假厂商返回一致；
3. 计划生成完成 → 草稿页显示本次消耗；
4. 管理页能看到该测试用户与功能分项；
5. 计量落库失败（临时断开 DB）时 AI 仍能正常回复。

## 11. 上线与回滚

- 服务端：部署时 `prisma db push` 自动建表；无数据回填；
- 客户端：随下一次 OTA 发布；旧版本不受影响；
- 回滚：服务端回滚后新字段消失，客户端自动隐藏用量行；`llm_usage` 表保留不删。
- commit / push / 部署 / OTA 均需单独批准（遵循既有工作约束）。

## 12. 验收标准

1. 所有 6 个 AI 入口的调用都产生 `llm_usage` 行，重试不漏计；
2. 厂商返回 usage 时数字与厂商一致；缺失时有估算标记；
3. 对话页每轮显示本轮消耗，今日累计准确（按设备时区）；
4. 计划生成完成后显示本次总消耗；
5. 管理后台可按 7/30 天查看全站、按用户、按功能用量；
6. 计量故障不改变任何 AI 功能的可用性；
7. 本期没有任何拦截行为。
