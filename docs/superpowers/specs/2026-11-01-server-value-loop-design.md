# 研钟 · 服务端价值闭环设计

日期：2026-11-01
状态：待评审
范围：Android 客户端（YanZhong）+ 服务端（server）
关联：`2026-09-29-dynamic-plan-adjustment-design.md`、`2026-10-30-ui-refresh-design.md`

## 1. 背景与目标

研钟目前已经具备：本地专注/任务/复盘、服务端账号与多设备同步、服务端 AI 备考计划、计划调整（L1/L2）、通知推送。但服务端的价值仍偏"后台"：用户看不到同步是否健康，也拿不到或搬不走自己的云端数据；换手机、忘密码、想踢掉旧设备都没有闭环；AI 计划除了生成之外不会主动提醒"你跟不上了"。

本轮把服务端能力变成用户可感知的闭环，目标按优先级：

1. **数据自主**：看得见同步状态；能把云端数据完整导出/下载/恢复；换手机有明确路径。
2. **账号安全**：改密码、全端登出、忘记密码重置，全部在 App 内可完成。
3. **服务端主动服务**：主页展示计划体检（落后/超载/趋势），统计页展示本周执行，计划调整可一键带入上下文。
4. **顺带修 3 个高频交互问题**：计划页错误不可见、主页信息顺序、弹窗内容溢出。

### 非目标

- 不做跨账号迁移、不做团队/分享、不做多计划并行。
- 不新增数据库表、不做数据库迁移（复用现有 `PlanItem`、`PomodoroSession`、`UserProfile.briefJson`、`RefreshToken`、`PlanAdjustment`）。
- 不做离线可用性改造；本轮所有新能力都要求已登录且联网。
- 不替换本地周报（`WeeklyReport`）；服务端只补充"计划执行"视角，避免重复统计。
- 不实现邮件服务商接入（SMTP 未配置时如实提示，仅服务端日志给链接）。

## 2. 总体架构与数据流

```
┌────────────── Android ──────────────┐        ┌──────────── server ────────────┐
│ Compose UI                           │        │ Koa routes                     │
│  ├ AccountScreen                     │        │  ├ /account/export             │
│  │   ├ SyncStatusCard                │  HTTP  │  ├ /backup, /backup/restore    │
│  │   ├ AccountDataSection  ──────────┼───────▶│  ├ /auth/forgot|reset|change   │
│  │   └ AccountSecuritySection        │        │  ├ /devices (DELETE)           │
│  ├ PlanScreen / HomeScreen           │        │  └ /plans/checkup (enriched)   │
│  └ StatsScreen                       │        │ Prisma / MySQL                 │
│ DataSyncer (Room ⇄ server)           │◀──────▶│ WS hub (planReminder, kicked)  │
└──────────────────────────────────────┘        └────────────────────────────────┘
```

关键数据流：

- **同步状态**：`DataSyncer.state`（`running/lastSyncAt/lastError`）→ `CloudSyncViewModel` → `SyncStatusCard`；手动同步复用 `syncOnce()`。
- **云端数据**：导出走 `/account/export`（只读，含账号+档案+计划+数据）；下载/恢复走 `/backup`、`/backup/restore`；恢复前先自动拉一份当前云端快照存本地。
- **体检**：`/plans/checkup?tzOffset=&notify=` 纯计算，客户端主页/统计页共用；主页使用 `notify=0` 避免与每日本地通知重复。
- **改密/全端登出**：服务端吊销 refresh token（全端登出还会 `hub.kickUser`）→ 客户端清空 `TokenStore` → 回登录页。
- **找回密码**：`forgot` 生成 30 分钟一次性 token → 邮件链接 `yanzhong://password-reset?token=...` → App deep link → 重置后清空本地 token 回登录页。

## 3. A. 账号与数据安全中心

### 3.1 账号页信息架构

已登录状态下，`AccountScreen` 的顺序调整为：

1. 账号头部卡（用户名、服务器）
2. **同步状态**（新）
3. **云端数据**（新：导出账号数据 / 下载云端备份 / 从备份恢复）
4. 登录的设备（现有）
5. **账号安全**（新：修改密码 / 全端登出）
6. 退出登录 / 注销账号（现有，保持确认弹窗）
7. 隐私与协议（现有，保持在底部）

为避免 `AccountScreen.kt`、`CloudSyncViewModel.kt` 继续膨胀：

- 新增 `CloudDataViewModel`：只负责导出、下载备份、读取待恢复文件、执行恢复、恢复前快照。
- `CloudSyncViewModel` 扩展：同步状态代理、手动同步、修改密码、全端登出。
- UI 拆出 `SyncStatusCard.kt`、`AccountDataSection.kt`、`AccountSecuritySection.kt`，`AccountScreen` 只做编排。

### 3.2 同步状态卡

三种状态，全部显示"上次同步时间"（`TokenStore.lastSync`，本地格式化，失败回落"尚未同步"）：

| 状态 | 触发 | 文案与操作 |
| --- | --- | --- |
| 正常 | 未在同步、无错误、有过成功同步 | "已同步 · 刚刚 / 3 分钟前 / 昨天 21:40" + "立即同步" |
| 同步中 | `DataSyncer.state.running` | 进度圈 + "正在同步…"，按钮禁用 |
| 异常 | `lastError != null` | "同步失败 · 上次成功 21:40" + 错误摘要（复用 `apiErrorMessage` 映射）+ "重试" |

规则：

- 未登录不显示该卡（账号页整页切到未登录态）。
- 手动同步按钮调用 `syncOnce()`，返回后 Toast "同步完成" / "同步失败：…"。
- 同步状态卡只读 `DataSyncer`，不新增同步逻辑。

### 3.3 云端数据

#### 导出账号数据（`GET /api/v1/account/export`）

- 点击 → 调用接口（失败 Toast）→ SAF `CreateDocument("application/json")` 保存，默认文件名 `yanzhong-export-yyyyMMdd-HHmm.json`。
- 文件内容即服务端返回原文，不二次加工；导出前提示"包含你的备考档案、计划与学习记录，请妥善保管"。
- 服务端已有实现：排除 `passwordHash`、重置 token 等敏感字段。

#### 下载云端备份（`GET /api/v1/backup`）

- 与导出同一套 SAF 保存流程，文件名 `yanzhong-backup-yyyyMMdd-HHmm.json`。
- 用途说明："可在新手机上用恢复功能导入，或留作离线存档"。

#### 从备份恢复（`POST /api/v1/backup/restore`）

这一步是**破坏性**操作，必须按固定五步执行：

1. **选文件**：SAF `OpenDocument("application/json")` 读取文本；超过 **7.5MB** 直接拒绝（服务端 body 限制 8MB），提示文件过大。
2. **解析与预览**：SAF 读取文本后统一解析为 `BackupUpload`（服务端与本地导出的字段一致）；校验必填结构；显示摘要——主体/任务/专注记录/日周月复盘的数量，以及"将覆盖云端的学习数据与设置（备考档案与 AI 计划不受影响）"。
3. **两步确认**：第一次确认展示影响范围；第二次要求输入"恢复"两个字才可继续；取消任何一步都不发请求。
4. **自动快照**：恢复前先 `GET /backup` 拉当前云端快照，写入 `filesDir/pre_restore_backup.json`，并提示"已保存恢复前快照，可在本页下载"。**快照失败则中止恢复**，不冒险覆盖。
5. **执行恢复**：`DataSyncer.stop()` → POST `/backup/restore` → 成功后本地镜像：
   - `repo.restoreFromBackup(download)` 清空本地表/墓碑并以 `dirty=false` 重建；
   - `SettingsStore.applySyncMap(settings)` 应用备份中的设置；
   - `DataSyncer.start()`；
   - Toast "已恢复并同步到其他设备"；若服务端返回的 `resources.*.skipped > 0`，额外提示"N 条记录格式不兼容已跳过"。
   失败时同样 `DataSyncer.start()`，本地数据保持不变，并提示可下载恢复前快照。

恢复范围（必须在预览页如实说明）：

- 服务端恢复只重建 `subjects / countdownNodes / tasks / sessions / 日周月复盘 / settings`；**备考档案（含考试日期、空闲时段）与 AI 计划不在备份内，不会被恢复或覆盖**。
- 因此恢复不会改变考试倒计时、阶段和每日计划；如果用户要"换一份计划"，应走计划页重新生成或调整。

其它规则：

- 备份 DTO 补 `settings: Map<String, JsonElement> = emptyMap()`：`BackupUpload` 与 `BackupDownload` 都要加，保证"下载 → 恢复"不丢设置；旧备份没有该字段时按空处理。
- 恢复成功后其它设备通过服务端广播触发全量拉取（现有能力）。
- 页面文案明确："换手机登录同一账号即自动同步，通常不需要恢复；恢复只用于覆盖或抢救数据。"
- 恢复前快照若存在，页面出现"下载恢复前快照"入口（恢复到 `pre_restore_backup.json`，同样走 SAF）。

### 3.4 账号安全

#### 修改密码（`POST /api/v1/auth/password/change`）

- 入口在账号安全区，弹窗包含：原密码、新密码、确认新密码。
- 前端校验：新密码至少 8 位、两次一致、新密码不得与原密码相同；按钮在未满足时禁用。
- 成功后服务端吊销所有 refresh token（含当前）→ 客户端 `TokenStore.clearAll()` → 关闭弹窗 → 回到登录页，Toast "密码已修改，请重新登录"。
- 失败（原密码错误/网络）就地显示错误，不清 token。

#### 全端登出（`DELETE /api/v1/devices`）

- 两步确认：说明"将退出所有设备（含本机），需要重新登录"。
- 成功后清空本地 token → 回登录页 → Toast "已退出全部设备"。
- 与现有单设备登出并存；单设备登出保持现有确认弹窗。

#### 忘记密码

- `LoginScreen` 登录态下、密码输入框下方增加"忘记密码？"文本按钮。
- 弹窗输入账号（预填上次用户名）→ `POST /api/v1/auth/password/forgot`；无论账号是否存在都显示统一成功文案（防枚举）。
- 文案根据服务端能力分支：
  - `/healthz` 返回 `features.emailReset = true`：显示"重置链接已发送到绑定邮箱，30 分钟内有效"。
  - `false`：显示"当前服务器未配置邮件发送。请联系管理员获取重置链接；链接 30 分钟内有效。"（不承诺邮件已发出）
- 服务端 `/healthz` 增加 `features: { emailReset: boolean }`，取 `smtpConfigured()`。

#### 邮件链接回到 App（deep link）

- `AndroidManifest` 给 `MainActivity` 增加 `VIEW` + `BROWSABLE`，`scheme=yanzhong`、`host=password-reset`。
- `MainActivity.onCreate` / `onNewIntent` 解析 `token`，写入 `PasswordResetBus`（`MutableStateFlow<String?>`），不打印日志。
- App 根级监听：弹出"设置新密码"弹窗（新密码 + 确认）；提交 `POST /api/v1/auth/password/reset`；成功后清 token、回登录页；失败提示链接失效/已使用并允许关闭。
- 未登录状态也能处理该链接（重置本身不需要登录）。

## 4. B. 服务端主动服务

### 4.1 体检接口扩展

现有 `GET /api/v1/plans/checkup` 只返回 `behindMinutes/overdueCount/suggestion/serverTime`。扩展为：

```
GET /api/v1/plans/checkup?tzOffset=480&notify=0
→ {
  behindMinutes, overdueCount, suggestion, serverTime,
  weekPlanDone, weekPlanTotal,
  weekFocusMinutes, lastWeekFocusMinutes, weekActiveDays,
  next7dPlannedMinutes, next7dCapacityMinutes,
  overloadedDays: [{ date: "2026-11-03", planned: 360, capacity: 240 }]
}
```

参数：

- `tzOffset`：分钟，正值表示东区，范围 `-840..840`，非法值回落 480（北京时间）；用于按用户本地周计算。
- `notify`：默认 `1` 保留现有每日提醒行为；客户端主页/统计页请求传 `notify=0`，避免重复通知。

计算规则（全部纯函数，便于单测）：

- **周窗口**：以用户本地时间所在的周一到周日；`weekPlanDone/weekPlanTotal` 统计已生成计划中 `planDate` 落在本周的 `PlanItem`，`status=done` 计完成；`weekFocusMinutes` 取本周 `PomodoroSession`（`valid=true`、未删除）`durationMin` 之和，按 `startedAt` 换算到本地时间后归日；`weekActiveDays` 为本周有有效专注的本地天数。
- **上周对比**：`lastWeekFocusMinutes` 用同一规则取上一周，供"较上周 +x%"。
- **未来 7 天负载**：从明天起 7 个自然日，`next7dPlannedMinutes` 为计划项 `minutes` 之和；`next7dCapacityMinutes` 用 `dailyCapacity(brief, day)` 累加；`overloadedDays` 只列出 `capacity > 0 && planned > capacity` 的天，最多 7 条。当 `brief.availabilityConfirmed === false` 时容量视为"未知"：容量字段照常返回 0，但 `overloadedDays` 强制为空，避免把"没填空闲时间"误报成"排太满"。
- **建议**：最多两句，优先级为 ① 积压（`overdueCount>0`）→ "有 N 项计划已过期，共 X 分钟"；② 超载（`overloadedDays` 非空）→ "未来 7 天有 M 天安排超出可用时间"；③ 趋势（本周与上周对比）→ "本周专注比上周少/多 X%"；无问题时 `suggestion` 返回**空字符串**（沿用现有通知门控：空串不弹提醒），由客户端卡片显示"计划节奏正常"。两句用"；"连接，结尾统一"去调整一下。"。上周专注为 0 时不计算百分比，改为"本周已开始积累专注记录"。该建议同时用于每日本地通知，需保持可作为单条通知正文。
- 无 active plan 时：计划相关字段为 0/空，`suggestion` 为空字符串（保持现有测试语义）。

服务端改动集中在 `server/src/modules/planning/service.ts` 的 `checkup()` 与 `overdueSuggestion()`，路由层透传 `tzOffset/notify`。`PlanningDb` 接口需补 `pomodoroSession.findMany`（当前只有 plan/profile 相关表）。**注意**：现有单测对无计划返回值做了 `deepEqual{none}`，需同步更新为包含新字段的期望值。

### 4.2 主页：计划体检卡

位置：倒计时卡 → 阶段卡 → 本卡 → 今日进度（见 C 的信息顺序）。

- 只在"已登录 + 有 active plan + 体检成功"时显示。
- 内容：一条主结论（`suggestion`）、三个数字胶囊（本周完成 `done/total`、本周专注 `Xh`、未来 7 天 `Xh/Yh`，超载时用警示色）、操作"去调整"。`next7dCapacityMinutes = 0`（未确认空闲时段）时不显示分母，改为"未设置空闲时段"并引导补充档案。
- 可关闭：按本地日期记忆（DataStore），当天不再显示；次日自动恢复。
- "去调整"→ 跳转 `PlanInterviewScreen(mode=adjust)`，并用新增的 `prefill` 路由参数预填一条消息（`Uri.encode` 传入，`LaunchedEffect(prefill)` 只填一次，不覆盖用户已输入内容），例如：
  - 有积压："我最近有 N 项计划没完成（共 X 分钟），帮我重新安排到接下来的空闲时间，别把每天排太满。"
  - 超载："未来 7 天有 M 天超出可用时间，帮我减压并重新分配。"
  - 正常："帮我看看未来一周的计划是否合理，需要的话微调。"
- 该请求使用 `notify=0`，不触发服务端提醒。

### 4.3 统计页：本周执行（服务端视角）

- 页头说明从"数据全部来自本机"改为"图表来自本机，执行情况来自服务端"。
- 新增"本周执行"卡（数据来自同一体检接口）：本周计划完成率（带进度线）、本周专注时长与上周对比、活跃天数、未来 7 天负载（含超载日提示）。容量未知时的展示规则同主页。
- 失败时卡片显示"暂时取不到服务端数据"并给重试按钮，不影响本地图表。
- 周复盘弹窗（`WeeklyReviewSheet`，位于主页）增加只读的"服务端执行摘要"一行（体检建议或完成率），不写回任何字段。

## 5. C. 交互修复

1. **计划页错误可见**：`READY` 状态下若 `planError != null`，在主卡上方显示错误横幅（"操作失败，请重试" + 具体原因），带"重试"（调用 `refreshPlan()` 重新拉取计划状态）和"关闭"；`PlanViewModel` 增加 `clearPlanError()`。现有 `selectPlanDisplayState` 保持不变（有计划时仍优先 READY）。
2. **主页信息顺序**：倒计时 → 阶段/体检 → 今日进度 → 今日待办 → 节奏条（`rhythm`）。用户最常操作的待办列表上移，避免被进度/节奏信息挤出首屏。
3. **弹窗溢出**：`PomodoroGoalEditorDialog`、`GoalEditorDialog`、`PlanEditorDialog` 内容加 `verticalScroll`，并限制最大高度为屏幕高度的 70%（`heightIn(max = ...)`），底部按钮始终可见。

## 6. 安全与边界情况

- **凭证**：deep link token、密码、SSH 密码一律不入日志、不入仓库；测试账号信息不提交。
- **防枚举**：忘记密码对存在/不存在的账号返回同样文案与状态。
- **破坏性恢复**：必须五步流程；恢复前快照在请求前完成；快照失败时中止恢复并提示（不冒险覆盖）。
- **同步竞争**：恢复期间停止 `DataSyncer`，恢复完成后重新启动；若恢复失败，本地数据不动。
- **多处同时登出**：所有会清空 token 的路径（改密、全端登出、单端踢出、远端 `kicked`）都走同一函数，回到登录页并清理同步状态 UI。
- **无 SMTP**：如实提示"未配置邮件"，不承诺邮件送达。
- **老备份兼容**：缺少 `settings` 的备份正常恢复，只是不覆盖设置。
- **体检容错**：`tzOffset` 越界/非数字回落默认；无计划、无专注记录时返回零值而非报错；单次 DB 查询失败不影响主页其它区块。

## 7. 测试与验收

服务端（`cd server && npm test`）：

- `checkup` 新增字段的纯函数单测：本周窗口（含跨周边界、时区偏移、跨日专注按本地日归集）、上周对比、未来 7 天容量累加、超载日只含 `capacity>0`、`availabilityConfirmed=false` 时不报超载、无计划返回零值；更新原有 `deepEqual` 断言。
- `notify=0` 不触发提醒、`notify=1` 保持原行为。
- `healthz` 返回 `features.emailReset`。

客户端（`.\gradlew.bat testDebugUnitTest`）：

- 备份 DTO 带 `settings` 的序列化/反序列化往返；旧 JSON 缺字段按空处理。
- 恢复前置校验：超 7.5MB 拒绝、结构非法拒绝、确认词不一致不执行。
- 体检字段解析容错（缺字段/空数组）。

模拟器手测清单：

1. 账号页三张新卡在两台设备上的状态（正常 / 飞行模式同步失败重试 / 同步中）。
2. 导出与下载备份保存到文件；用导出的文件恢复到同一账号，校验任务/专注/设置恢复且另一台设备自动刷新。
3. 密码修改后本机与其他设备均被登出；用新密码可登录。
4. 全端登出后所有设备回到登录页（本机确认文案正确）。
5. 忘记密码弹窗在 SMTP 未配置时的文案；手动 `adb shell am start -a android.intent.action.VIEW -d "yanzhong://password-reset?token=..."` 验证重置弹窗与失效提示。
6. 主页体检卡：正常/落后/超载三种文案、"去调整"带入预填消息、当天关闭后不再出现。
7. 统计页"本周执行"与本地图表数据一致性；断网时卡片可重试。
8. 计划页断网勾选失败后错误横幅可见、可关闭、可重试。
9. 主页待办在首屏可见；三个长弹窗可滚动、按钮不被遮挡。

## 8. 交付与部署

- 代码保持 `main` 分支，不新建分支；按仓库约定提交（用户明确要求时才提交/推送）。
- 服务端新增/改动接口**向后兼容**：老客户端不带 `tzOffset/notify` 仍可用。
- 部署顺序：服务端先发布（兼容旧客户端）→ 客户端安装。服务端部署需用户单独确认，本轮不自动执行。
- 回滚：服务端接口为增量字段，回滚只需回退镜像；客户端恢复为纯客户端流程，不涉及数据库迁移。

## 9. 文件清单（预估）

服务端：

- `server/src/modules/planning/service.ts`（checkup 扩展、建议生成、纯函数、`PlanningDb` 补 `pomodoroSession.findMany`）
- `server/src/modules/planning/routes.ts`（query 参数）
- `server/src/modules/planning/service.test.ts`（新/改单测）
- `server/src/app.ts`（`/healthz` features）

客户端：

- `data/remote/SyncModels.kt`（`settings` 字段）
- `data/remote/Api.kt`（checkup 参数与新字段）
- `data/remote/DataSyncer.kt`（恢复期间复用现有 `stop()`/`start()`）
- `data/repo/StudyRepository.kt`（导出/恢复链路复用）
- `data/prefs/SettingsStore.kt`（恢复时复用现有 `applySyncMap`）
- `ui/account/AccountScreen.kt` + `SyncStatusCard.kt` + `AccountDataSection.kt` + `AccountSecuritySection.kt`
- `ui/cloud/CloudSyncViewModel.kt`（同步状态、改密、全端登出）+ 新增 `CloudDataViewModel.kt`
- `ui/auth/LoginScreen.kt`（忘记密码入口）、新增 `PasswordResetBus.kt` + 重置弹窗
- `MainActivity.kt`、`AndroidManifest.xml`（deep link）
- `ui/home/HomeViewModel.kt`、`HomeScreen.kt`、新增 `PlanCheckupCard.kt`
- `ui/plan/PlanScreen.kt`、`PlanViewModel.kt`、`PlanInterviewScreen.kt`、`ui/nav/AppNav.kt`
- `ui/stats/StatsScreen.kt`、`StatsViewModel.kt`、`ReviewCard.kt`
- `ui/mine/MineScreen.kt`（三个弹窗滚动）
- 字符串/文案集中位置（若项目已有 `strings.xml` 使用则复用，现有代码以硬编码中文为主，保持一致）

## 10. 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| 恢复误操作覆盖云端 | 五步确认 + 自动快照 + 恢复前二次输入 |
| 恢复期间同步竞争 | 暂停 DataSyncer，失败恢复原状 |
| 改密/全端登出后本地 token 残留 | 统一清理函数，所有路径复用 |
| SMTP 未配置导致用户以为邮件已发 | 按 `/healthz.features.emailReset` 分支文案 |
| 体检增加查询拖慢主页 | 纯计算 + 现有索引；主页每次进入只请求一次，失败不阻塞其它区块，计划变化/打卡后刷新 |
| 老备份缺 `settings` | DTO 默认空、恢复时跳过设置写入 |
