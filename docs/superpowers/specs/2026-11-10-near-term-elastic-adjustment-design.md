# 近期弹性计划调整设计

**日期：** 2026-11-10
**状态：** 待实现
**目标：** AI 行程调整只动未来几天里「被事件波及」的安排，按重要性腾挪，不再整案重排。

## 1. 背景与问题

当前调整链路（`POST /plans/adjust` → 意图解析 → L1/L2/方案引擎 → 确认快照落库）已经具备
「确认前零副作用、确认后就地改写、可撤销」的基础，但排期口径有两个硬伤：

1. **窗口太大**：`adjustWindow()` 固定为今天～未来 30 天（`server/src/modules/planning/adjust.ts:182`）。
   `reschedule` 会把窗口内**所有**未完成任务从今天起重新放置（`server/src/modules/planning/service.ts:2193`），
   用户感知就是「整个计划都被重排」。
2. **容量与重要性脱节**：L1 只挪日期不改时长（`adjust.ts:94`）；塞不下时的 `compress` 方案按比例
   缩短窗口内**全部**任务（`server/src/modules/planning/planOptions.ts:218`），不区分重要与否；
   一条不重要的任务占住某天，就会被当成「这天不能用了」。

用户要求：调整聚焦近几天；顺延有取舍，重要任务保留、可压缩后面不重要任务；确认后只改相关安排，
不重跑整案。

## 2. 目标与非目标

**目标**
- 默认只调整今天～第 5 天（今天+4），用户明确说范围时最多 14 天；自动延窗最多 +3 天（总长 ≤8 天）。
- 只有被事件波及的任务参与移动/缩短；其余任务与日期保持不变。
- 重要任务不被算法缩短或挪动；容量不足时优先牺牲可让任务，再牺牲普通任务。
- 用户确认后只改写调整单覆盖的日期范围；不升计划版本、不重新生成计划/文档。
- 典型调整仍只调用 1 次大模型（意图解析）；模型增量重排只在确定性引擎溢出/冲突时兜底。

**非目标**
- 不改计划生成、科目目标（`adjust_scope` 保持现有逻辑与 30 天窗口）。
- 不改锁定、打卡、里程碑、调整单指纹与撤销协议。
- 不做长期「重要度」字段与用户手动标记（本期只用自动信号 + 本次话术）。

## 3. 行为规则

### 3.1 调整窗口

「窗口天数」一律按含今天的总天数计：5 天 = 今天 + 未来 4 天。

| 场景 | 窗口 |
| --- | --- |
| 默认 | 5 天（`today .. today+4`） |
| 用户明确说「这周 / 到周末 / 未来 N 天」 | `today .. min(today+N-1, examDate-1)`，总天数夹到 `[5, 14]`；用户给的范围是硬上限 |
| 默认 5 天窗口塞不下 | 自动向后延，每天 +1，最多 +3 天（总长 ≤8 天） |
| 用户指定范围后仍塞不下 | 不越过用户给的上限，直接进入模型兜底/可选方案 |
| 历史落后任务 | 仍从过去 30 天内拉入（`from` = 最早未完成日或今天） |
| `adjust_scope`（改科目目标） | 维持现状：今天～未来 30 天 |

未来本来就排得下的任务**不重新洗牌**；默认的 `重排/落后了` 只把「今天之前未完成」和「今天已超载」
的任务拉进窗口处理。用户明确说「重新安排这周/未来 N 天」时，才把该范围内的**非保护项**列为待动项，
固定项与保护项仍钉住。

### 3.2 重要性分级

不改数据库，按现有信号 + 本次话术推断：

| 级别 | 判定 |
| --- | --- |
| 固定 | 已打卡、已锁定；任何算法都不得改动 |
| 保护 | 用户本次点名「重要/别动/必须完成」的科目或任务；否则弱科；否则窗口内有该科里程碑 |
| 普通 | 其余未完成任务 |
| 可让 | 用户本次点名「可以少点/缓一缓/不重要」的科目或任务 |

- 只有用户话术能产生「可让」；系统不自行把任务判成低重要。
- 优先级：用户明确「保护」> 用户明确「可让」> 弱科/里程碑默认保护 > 普通。用户明确指令高于分级：
  说「把数学挪到周五」就挪；说「删掉这项」就删；说「这项缩短到 20 分钟」就缩。
- 保护只防「被算法主动牺牲」：当保护任务自己所在的日期没空/容量下降时，它仍会被移到别的日期，
  但**不缩短分钟数**；它不会为了给别的任务腾地方而挪动。

### 3.3 容量模型

对窗口内每一天：

```
netFree(day)      = 课表净空闲（briefAfter，已扣固定占用）
fixedLoad(day)    = done 项 + locked 项分钟数
protectedLoad(day)= 保护级未完成项分钟数
elasticLoad(day)  = 普通级 + 可让级未完成项分钟数
```

- 固定项与保护项是硬占用；普通/可让项是**弹性容量**——某天不够时可以被缩短或挤到后一天。
- 「这天不能用了」只允许出现在：净空闲为 0、固定/保护项已占满、且没有可让/普通项可腾挪时。

### 3.4 腾挪顺序

1. 先缩**可让**任务：最多缩到原时长的 50%，单条不低于 15 分钟，按分钟数从大到小、同分钟按 id 稳定排序。
2. 还不够，缩**普通**任务：最多缩 25%，单条不低于 15 分钟。
3. 还不够，把**可让 → 普通**任务向后挤一天；最多连续 3 跳，visited 去重防循环。
4. 保护任务不缩；不会为了让别的任务有地方而挪动。只有它自己所在日期没空/降容（被事件波及）时
   才作为待动项后移，且分钟数不变。
5. 若待动任务自己在所有日子都放不下、且它不是保护项：最后按同级别下限缩它自己
   （例如普通 240 → 180），仍放不下才继续下一步。
6. 仍放不下 → 自动延窗 +1 天（最多 +3）；仍放不下 → 进入模型增量重排（见 4.2）；模型也失败 → 出可选方案卡。

缩短按 5 分钟取整；所有缩短/挪动都写入变动清单并附 `note`。

### 3.5 各意图的待动项

| 意图 | 待动项（demand） |
| --- | --- |
| `unavailable` | 没空那几天上的全部非固定项 |
| `reduce_capacity` | 受影响日超出新容量的部分：可让→普通→保护（保护最后才动，且不缩） |
| `change_commitment` | 窗口内受影响星期几，口径同上 |
| `move_task` | 用户点名的任务；目标日固定为用户指定日期 |
| `add_task` | 新增任务；目标日为用户指定日期 |
| `reschedule`（未说范围） | 今天之前未完成项 + 今天超载项；未来可行项原地不动 |
| `reschedule`（明确「重新安排这周/未来 N 天」） | 该范围内全部非保护项列为待动项，固定/保护项钉住 |
| `drop_task` / `lock_task` | 保持现有直接行为，不进弹性引擎 |
| `adjust_scope` | 保持现有 L2/确定性删减逻辑 |

## 4. 实现结构

### 4.1 确定性弹性引擎（主路径）

新增纯函数模块 `server/src/modules/planning/elasticAdjust.ts`：

```ts
type Tier = 'fixed' | 'protect' | 'normal' | 'elastic'

interface ElasticDemand {
  id: number
  /** 最早可落日期；通常等于原日期或今天 */
  earliest: string
  /** 用户指定目标日（move/add）；undefined 表示由引擎挑选 */
  targetDate?: string
  reason: 'unavailable' | 'capacity' | 'overdue' | 'explicit' | 'added'
}

interface ElasticInput {
  before: WindowSnapshotItem[]
  tiers: Map<number, Tier>
  demands: ElasticDemand[]
  /** 某天净空闲分钟数（briefAfter，已扣固定占用） */
  capacity: (day: string) => number
  today: string
  /** 默认 today+4；用户指定范围时为 today+(N-1) */
  windowTo: string
  /** 可落日期硬上限：min(examDate-1, today+13)，用户指定范围时也取用户上限 */
  hardTo: string
  /** 是否允许在默认 5 天窗口上自动后延（用户指定范围时为 false） */
  autoExtend: boolean
  milestones: Map<string, string>
}

interface ElasticResult {
  after: WindowSnapshotItem[]
  touchedDays: string[]
  overflow: WindowSnapshotItem[]
  shrinkMinutes: number
  extendedTo: string
  notes: Map<number, string>
}
```

算法：

1. 把非 demand 项钉在原日期（pinned），只对 demand 排序：保护 → 普通 → 可让；同级按日期、id。
2. 逐条放到 `[max(earliest, today) .. hardTo]` 内最早可容纳日：先试 `targetDate`（如有），否则从 earliest 向后找。
3. 某天不够时计算「可腾分钟」= 可让项可缩空间 + 普通项可缩空间；够则按 3.4 缩，放置成功。
4. 仍不够则尝试把当天的可让/普通项后挤一天（≤3 跳），再重试。
5. 全部 demand 处理完仍有余量不足且 `autoExtend = true`：自动 `windowTo + 1`（最多 +3 天），
   重新执行 2–4；用户指定范围时 `autoExtend = false`，直接判溢出。
6. 输出快照、实际改动日期、溢出项、总缩短分钟、每条说明。

服务层给引擎的快照会预读到 14 天（`hardTo`），这样自动延窗落到第 6–8 天时，引擎能看到那些天
已有的任务，不会把既有占用当空气。

不变量：
- 非 demand 项、且未被缩/挤的项，字段与输入完全一致。
- 固定项不得被算法改动。
- 保护项不得被缩短；不得因其他任务的需求被挪动（只有它自己所在日期被事件波及、
  作为 demand 时才允许改日期）。
- 每天总分钟 ≤ `capacity(day)`。
- 科目任务不得晚于其里程碑（若里程碑落在窗口内）。
- 相同输入重复执行结果一致（幂等）。

### 4.2 模型增量重排（兜底）

仅当引擎溢出（默认场景延满 +3 天仍放不下；用户指定范围则是不越上限仍放不下）或多约束冲突时触发。

输入（默认场景 ≤8 天；用户指定范围 ≤14 天）：
- 每个窗口任务：`id / 日期 / 科目 / 标题 / 分钟 / 级别`（只列模型能落到的日期，更远的任务不放进 prompt）；
- 固定项与未被事件波及的保护项标注「不可动」；被事件波及的保护项允许改日期、禁止改分钟；
- 每天可用分钟、里程碑、demand 清单、用户原话。

输出只允许增量：

```json
{
  "changes": [{"id": 123, "dayOffset": 1, "minutes": 45}],
  "drops": [124],
  "notes": "把英语整理挪到明天并缩短 15 分钟"
}
```

服务端校验（`validateIncrementalProposal`，纯函数）：
- `id` 必须存在于窗口未完成项；`dayOffset` 落在窗口内；
- 固定/保护项不得出现在 `changes`/`drops`（用户明确指令除外）；
- `drops` 只允许可让项；
- 缩短幅度：可让 ≤50%、普通 ≤25%，单条 ≥15 分钟；
- 每天总分钟 ≤ 当天容量；里程碑不得被推后；
- 未出现在增量里的任务保持原日期与原分钟。

任一条不满足 → 丢弃模型结果，回退到可选方案卡；不把半成品交给用户。

模型输出使用 `adjust_plan` 计量，`maxTokens = 2400`、`timeoutMs = 45s`、`json = true`。

### 4.3 意图解析扩展

`AdjustIntent` 新增（内部 JSON，不下发给客户端）：

```ts
importance: Array<{ subject: string; title?: string; date?: string; level: 'protect' | 'elastic' }>
horizonDays?: number
```

规则：
- `importance` 最多 8 条；科目必须在给定白名单内；日期必须在 `today..contextTo` 内；级别只接受两个值。
- `horizonDays` 仅在用户明确说范围时给出，按含今天的总天数计，服务端夹到 `[5, 14]`；
  不给时默认 5 天。
- 提示词明确：用户没说「重要/可少」时不得臆造 `importance`；`reschedule` 只处理落后与今日超载，不得重排未来可行项。

窗口计算顺序：
1. 意图解析上下文仍保留 30 天：科目目标/里程碑（`adjust_scope`）可以看得更远。
2. 解析后：日程类 `effectiveTo = min(today + (horizonDays ? horizonDays-1 : 4), examDate-1)`；
   若日程类意图出现超过 14 天的日期，直接请用户去计划页手动改，不静默忽略。
3. `adjust_scope` 保持 30 天；引擎只用于日程类，按 3.1 自动延窗。
4. 引擎快照预读到 14 天，覆盖自动延窗可能落到的日期。

### 4.4 服务层接入

`server/src/modules/planning/service.ts`：

- `adjust()` 的 `unavailable` / `reduce_capacity` / `change_commitment` / `reschedule` 分支改走
  `planElasticAdjustment()`；`move_task` / `add_task` 的目标日压缩改为 `shrinkDayByTier()`（按级别缩，
  取代 `compressDaysToCapacity()` 的按比例缩）。
- `PLAN_ADJUST_ENGINE=legacy` 时保留旧分支（回滚开关，默认 `elastic`）。
- 调整单窗口：
  - `windowFrom = min(所有变动项的原日期与新日期)`；
  - `windowTo = max(所有变动项的原日期与新日期)`；
  - 若没有实际改动（仅档案变更），窗口取 `today..today`。
- 保存前把 `beforeJson` / `afterJson` 过滤到 `[windowFrom, windowTo]` 并重算 `changes`/`impact`；
  指纹也按这个收紧后的窗口计算，`confirmAdjustment()` 才能用同一口径比对。
- `confirmAdjustment()` 沿用现有 `syncWindowItems()`（`service.ts:450`），只写窗口内项；
  由于窗口已收紧，窗口外任务不会被删改。
- 缩短任务只改计划项分钟数，不回写档案 `remainingMinutes`（与现有 `compress` 方案一致）。
- 不调用 `generatePlanDraft`、不创建异步生成任务、不改 `Plan.version`、不重算 `documentJson`。

### 4.5 前端

- `AdjustmentChangeDto` 增加可选 `note: String? = null`；服务端 `AdjustmentChange` 同步增加可选 `note`。
- `AdjustmentCard`（`YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AdjustmentCard.kt:91`）：
  - 变动行在 `adjustmentChangeLine(change)` 后追加 `note`（存在时）；
  - 顶部摘要由服务端 `summary` 提供，格式示例：
    「只影响 2 天,共缩短 30 分钟;重要任务未缩短」；
  - 变动超过 8 条时保留现有「…共 N 条」提示；
  - 多方案卡只在引擎/模型都溢出时出现。
- 整页预览（`PlanDraftPreview.buildAdjustmentPreview()`）继续用 `changes` 本地合成，逻辑不变。
- 旧客户端忽略 `note`，行为不受影响。

### 4.6 配置

- `PLAN_ADJUST_ENGINE=elastic|legacy`，默认 `elastic`；仅用于线上回滚，稳定一个版本后移除。
- 窗口与缩短比例先用常量（`DEFAULT_HORIZON_DAYS=4`、`MAX_HORIZON_DAYS=13`、`AUTO_EXTEND_DAYS=3`、
  `SHRINK_MIN_MINUTES=15`、`ELASTIC_SHRINK_MAX=0.5`、`NORMAL_SHRINK_MAX=0.25`），不进环境变量。

## 5. 测试

### 5.1 纯函数（`elasticAdjust.test.ts`）

1. 未受影响的日期与任务字段完全不变。
2. 保护项不被缩短/挪动；用户明确指令可覆盖。
3. 可让先缩 50%、普通缩 25%、单条 floor 15。
4. 后挤链最多 3 跳且无循环。
5. 默认窗口 5 天；用户 10 天 → 10 天；自动延窗最多 +3；上限 14 天。
6. `reschedule` 只动落后与今日超载，未来可行项不动。
7. 里程碑约束与锁定约束。
8. 幂等：同输入两次结果一致。
9. 溢出时返回 `overflow`，不产生半成品快照。

### 5.2 模型增量校验（`incrementalProposal.test.ts`）

改固定/保护项、越窗、超容量、破里程碑、超缩短上限、drop 非可让项 → 全部拒绝。

### 5.3 服务层（`service.test.ts`）

- `adjust()` 产出的 `changes` 只落在 `[windowFrom, windowTo]`；
- `confirmAdjustment()` 只改窗口内项，`Plan.version` 不变，`documentJson` 不重算；
- `undoAdjustment()` 完整还原；
- 指纹不一致仍返回 `ADJUSTMENT_STALE`；
- `PLAN_ADJUST_ENGINE=legacy` 走旧路径（回滚开关有效）。

### 5.4 客户端

- `note` 缺省时 DTO 解析不失败；
- 变动行含 `note` 时的展示格式；
- 现有 `AdjustmentChangeDto` 契约测试保持通过。

## 6. 验收标准

1. 30 天计划上做一次「明天没空」调整，变动只涉及 1–3 个日期，不出现整月重排。
2. 目标日有可让/普通任务时，先缩它们；保护任务分钟数与日期不变。
3. 目标日存在可让任务时，不再报「这天排满了」。
4. `重排/落后了` 不改变未来本来排得下的任务。
5. 确认后计划版本不变，只有调整单覆盖日期的计划项变化；撤销可还原。
6. 典型调整只有 1 次模型调用；模型兜底只在溢出/冲突时发生。
7. 线上日志能看到：意图、窗口天数、实际影响天数、缩短分钟、是否用模型、是否溢出。

## 7. 风险与取舍

- **重要性误判**：只有用户话术能产生「可让」，默认不会把任务当低重要；变动行展示说明并可撤销。
- **重复调整导致累计缩短**：当前以「调整时的分钟数」为基数，单次有比例与 15 分钟下限；连续多次仍可能逐步缩短。
  本期用日志记录并按需再收紧。
- **模型兜底不稳定**：强校验 + 失败回退方案卡，模型结果永不直接落库。
- **旧客户端**：只忽略 `note`，调整单结构不变，无兼容问题。
- **缩短与档案口径**：缩短只改计划项，不回写科目剩余量；与现有 `compress` 方案口径一致。
- **回滚**：`PLAN_ADJUST_ENGINE=legacy` 恢复旧行为，数据无需回滚。
- **发布节奏**：服务端与客户端全部完成、本地验收通过后一起发布；不做只发服务端的中间态。

## 8. 涉及文件

服务端：
- `server/src/modules/planning/elasticAdjust.ts`（新建）
- `server/src/modules/planning/elasticAdjust.test.ts`（新建）
- `server/src/modules/planning/adjust.ts`（窗口参数化）
- `server/src/modules/planning/adjustIntent.ts`（重要性/窗口字段与提示词）
- `server/src/modules/planning/adjustL2.ts`（增量重排与校验）
- `server/src/modules/planning/service.ts`（接入、窗口收紧、回滚开关）
- `server/src/modules/planning/planOptions.ts`（目标日按级别缩，复用为溢出方案）
- `server/src/config/env.ts`、`server/.env.example`（`PLAN_ADJUST_ENGINE`）

客户端：
- `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt`（`AdjustmentChangeDto.note`）
- `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/AdjustmentCard.kt`
  （`adjustmentChangeLine` 在 :25，变动行在 :91，追加 `note` 展示）
- 对应单测与契约测试