# 研钟 动态计划调整（AI 行程小助手）设计

## 1. 目标

在已有“问卷 + 面谈 → 生成计划草稿 → 预览更正 → 确认生效”闭环的基础上，把 AI 从一次性计划生成器升级为常驻的行程调整小助手：

1. 用户随时用自然语言告诉 AI 突发情况（临时有事、生病、加课、某天没空等）。
2. 服务端据此调整**近期**计划，调整结果先落为**待确认的调整单**，不立即生效。
3. 用户确认后，计划**就地更新**并自动同步到 App，无需重新制定整份计划。
4. 服务端可主动体检并在合适时机提醒用户落后或积压。

本阶段沿用既有权威分工：模型负责意图解析与说明文，服务端负责日期展开、容量校验与持久化。

## 2. 设计原则

- **就地调整，不建新版本**：调整直接作用于当前 active plan，不增加 `Plan.version`。“明天有事”不应触发重排到考前一天。
- **确认前零副作用**：调整单为 `draft` 时，App 展示与 active plan 完全不变；只有 `applied` 才广播变更。
- **服务端硬规则定档**：L1/L2 档位、日期、容量由服务端纯函数判定，模型不输出绝对日期，避免日期算歪。
- **历史与打卡不可侵犯**：历史项、`done` 项、阶段末之后的未来项一律不动。
- **总量守恒**：重排只在窗口内重新分配，不凭空增减计划分钟数。
- **复用既有链路**：确认后的传播复用 `planChanged` + `applyPlanProjection`，App 端不新造同步机制。
- **可撤销**：最近一次调整在有新打卡或新调整前可撤销。

## 3. 范围

### 3.1 调整入口与端点

新增调整能力，独立于既有制定流程：

- 新增 `POST /plans/adjust`：接收用户自然语言与已确认事实，产出调整单。
- 新增 `GET /plans/adjustments/latest`：取回未确认（`draft`）调整单，供 App 冷启动或切换页面时恢复。
- 不复用 `POST /plans/interview`：后者以“补齐备考事实”为门槛，与本接口语义不同，混用会把“请个假”也套上事实校验。
- 对话 UI 复用现有面谈对话框。客户端按是否存在 active plan 分流：无 → 制定模式（现状）；有 → 调整模式。
- 调整结果先写 `draft` 调整单；用户确认后置 `applied` 并广播。确认前 App 显示不变。

### 3.2 两级调整档位

档位由服务端硬规则判定，模型不参与定档：

- **L1 顺延**：影响范围 ≤ 2 天、窗口内可容纳、不改变每周作息模板。只修改 `planDate`，科目、标题、分钟数不变。
- **L2 重排**：不满足 L1 条件时启用。窗口为 `[today, 当前阶段末]`，允许合并、拆分、降强度、换科目，但窗口内总分钟数守恒。

L1 天数阈值可配置，默认 2 天。判定不满足 L1 即升级为 L2。

### 3.3 数据模型

新增 `PlanAdjustment` 表，不修改 `Plan` / `PlanStage` / `PlanItem` 现有结构：

- `id`、`userGuid`、`planId`。
- `tier`：`L1` | `L2`。
- `status`：`draft` | `applied` | `undone` | `expired`。
- `reason`：用户原始诉求摘要。
- `summary`：面向用户的调整说明。
- `windowFrom`、`windowTo`：调整窗口。
- `beforeJson`、`afterJson`：调整前后窗口内计划项快照，用于差异展示与撤销。
- `fingerprint`：确认时校验窗口内打卡未变化，防止跨设备覆盖。
- `createdAt`、`appliedAt`、`undoneAt`。

约束：同一用户同一时刻最多存在一个 `draft` 调整单。调整不动 `Plan.version`。

### 3.4 调整窗口与保护集

调整只作用于“窗口内 `pending` 项”。以下三类绝不修改：

1. `planDate < today` 的历史项。
2. 所有 `status = done` 的打卡项。
3. `planDate > 当前阶段末` 的未来项。

计算窗口可用容量时，窗口内 `done` 项的分钟数按“已消耗容量”扣除。窗口无法容纳时报告缺口，**不自动跨阶段**（当前阶段末为上限）。

### 3.5 L1 顺延算法

纯函数，可独立单测：

- 输入：窗口内 `pending` 项集合；每日容量 `cap(d) − doneMinutes(d)`，容量口径复用 `netAvailableMinutes`。
- 处理：按 `planDate` 升序摊平为队列，逐日填充；当天放不下则顺延至次日。
- 终止：到 `windowTo` 仍有剩余则判定容量不足，复用 `deficits` 结构报告缺口，不落 `applied`。

### 3.6 L2 重排算法

沿用“模型给骨架、服务端权威展开”的既有分工：

1. 服务端先计算窗口内每科剩余量、里程碑与每日容量的硬账本。
2. 模型仅输出窗口内任务序列 `{ dayOffset, subject, title, minutes }`，**不输出绝对日期**。
3. 服务端按账本铺开并校验：窗口内总量守恒、每日不超容量、科目在已确认范围内、里程碑不推迟。
4. 校验不通过则回喂重试一次；仍不通过则返回 `PLAN_GENERATION_FAILED`（502），不落库。

展开逻辑复用 `expandStage` / `scheduleBacklog` 的能力。

### 3.7 模型职责边界

模型只做两类工作：

- **意图解析**：输出 `{ kind, days, windows, note, needClarify }`，其中 `kind ∈ { unavailable, reduce_capacity, change_commitment, chat }`。
  - `change_commitment`：先更新 `briefJson.fixedCommitments`（复用 `parseTimetable` 的并集去重），再触发 L2。
  - `needClarify`：信息不足时向用户追问，不产出调整单。
- **写说明文**：生成 `summary` 与差异解释。

### 3.8 确认界面

按改动大小分流：

- **L1**：对话内“变动清单”卡片，展示 `summary` 与 `beforeJson → afterJson` 逐条差异，提供“确认调整”与“重说一次”。
- **L2**：跳转 HTML 预览（复用 `PlanDraftPreview`），模板新增“本次调整”章节，标注新增、挪动、删除的条目。

### 3.9 App 接入

- 确认后服务端广播 `planChanged`，现有 `applyPlanProjection` 自动更新本地任务：`stalePlanTaskIds` 清理被挪走的旧项，`done` 项保留。
- 新增 `PlanInterviewViewModel.mode: BUILD | ADJUST`，复用同一对话框。
- 新增 `AdjustmentCard` 展示 L1 变动清单。
- 计划页提供“撤销上次调整”入口。
- ADJUST 模式下隐藏三枚事实进度 chip（正式科目 / 空闲时段 / 固定占用）。

### 3.10 主动能力

- 服务端新增 `checkup(userGuid)` 纯计算，返回 `{ behindMinutes, overdueCount, suggestion }`。
- 新增 `PlanNotifier` 可插拔适配器：
  - 先实现在线通道：WebSocket 在线推送。
  - 本地通道：WorkManager 每日与冷启动轮询 `GET /plans/checkup`，命中则发本地通知，复用 `CHANNEL_EVENT`。
  - 厂商推送仅预留适配器接口，本阶段不实现。
- 防打扰：每位用户每天最多 1 条主动提醒。

## 4. 数据流

```text
用户自然语言
    ↓ POST /plans/adjust
意图解析（模型）
    ↓ 档位判定（服务端硬规则）
L1 顺延纯函数 ／ L2 账本 + 模型序列 + 服务端展开校验
    ↓ 写入 draft 调整单
确认界面（L1 对话卡片 ／ L2 HTML 预览）
    ↓ 用户确认
PlanAdjustment = applied（就地更新 active plan 项）
    ↓ planChanged 广播
Android applyPlanProjection
    ↓
本地任务幂等更新（清理旧项、保留 done）
```

调整是 active plan 的原地变更，不产生新计划版本，也不进入本地手工任务的同步协议。

## 5. 模块边界

### 服务端

- `planning/service.ts`：新增 `adjust`、`latestAdjustment`、`confirmAdjustment`、`undoAdjustment`、`checkup`。
- `planning/routes.ts`：新增路由，维持既有鉴权、限流与响应协议；静态路径须早于 `/plans/:id` 注册。
- 新增 L1/L2 纯函数模块，与既有 `netAvailableMinutes`、`assessPlanningFacts` 口径保持一致。
- `prisma/schema.prisma`：新增 `PlanAdjustment`。

### Android 数据层

- 复用 `PlanProjector` 与 `applyPlanProjection`，不新增同步通路。
- Repository 负责调整单的取回与确认调用，不让 Composable 直接访问网络或数据库。

### Android 界面层

- `PlanInterviewViewModel` 增加 `BUILD | ADJUST` 模式。
- 新增 `AdjustmentCard`；L2 复用 `PlanDraftPreview`。
- 计划页增加“撤销上次调整”。

## 6. 错误处理与降级

| 错误码 | HTTP | 场景 |
| --- | --- | --- |
| `PLAN_CAPACITY_INSUFFICIENT` | 400 | 窗口内塞不下，阻止确认并报告缺口 |
| `PLAN_GENERATION_FAILED` | 502 | L2 校验两次不过，不落库 |
| `ADJUSTMENT_STALE` | 409 | 确认时指纹与打卡状态不符 |
| `ADJUSTMENT_NOT_FOUND` | 404 | 调整单不存在或已过期 |
| `PLAN_NOT_FOUND` | 404 | 无 active plan，回落制定模式 |
| `AI_NOT_CONFIGURED` | 503 | 未配置模型 |

- 并发：同一用户仅允许一个 `draft` 调整单；已有 `draft` 时新请求原地覆盖其内容，不产生第二个 `draft`。
- 撤销：仅限最近一次调整，且其后无新打卡、无新调整。
- 模型未配置或超时：沿用既有 `LlmError` 分类处理，调整失败不改变 active plan。

## 7. 测试策略

### 服务端

- L1 顺延：冲突日顺延、跨日填充、窗口末仍溢出报缺口、`done` 项容量扣除。
- L2：总量守恒、每日不超容量、科目范围合法、里程碑不推迟、两次校验失败不落库。
- 档位判定：L1 条件边界（2 天/3 天）正确升降级。
- 保护集：历史项、`done` 项、阶段末之后项不被修改。
- 调整单：指纹不一致返回 `ADJUSTMENT_STALE`；并发只保留一个 draft；撤销条件严格。
- 沿用 `npm run verify` 与既有测试全部通过。

### Android

- 确认后 `planChanged` 触发本地更新，旧项被清理、`done` 项保留。
- 调整单取回与恢复（冷启动、切页）。
- `BUILD | ADJUST` 模式切换正确，ADJUST 下隐藏事实 chip。
- 网络失败不改变本地任务。

## 8. 分期实施

- **D1**：`PlanAdjustment` 表 + L1 顺延 + `POST /plans/adjust` + `GET /plans/adjustments/latest` + 单测。
- **D2**：App ADJUST 模式 + 变动清单卡片 + 撤销。
- **D3**：L2 重排 + HTML 预览“本次调整”章节 + 指纹校验。
- **D4**：`checkup` 体检 + WebSocket 在线提醒 + WorkManager 本地通知。

## 9. 非目标

- 不实现厂商推送通道（仅预留适配器接口）。
- 不跨越当前阶段末自动排期。
- 不调整历史项与已打卡项。
- 不新增 `Plan.version`，不重写计划生成与确认流程。
- 不改造本地手工任务的同步协议。

## 10. 完成定义

1. 确认前 App 展示与 active plan 完全不变。
2. 用户确认后 2 秒内 App 自动更新，无需手动刷新。
3. 已打卡项在任意调整后永不丢失。
4. 阶段末之后的计划项不被动。
5. 窗口塞不下时明确报告缺口，不静默丢弃任务。
6. L1 调整不改变科目与分钟数，仅移动日期。
7. 最近一次调整在无新打卡前可撤销。
8. 服务端 `npm run verify` 与 Android 构建通过，既有功能不回归。
