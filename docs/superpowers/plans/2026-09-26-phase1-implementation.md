# Phase 1 实施计划

> **执行方式：**按以下任务顺序实施。每个任务先补测试，再修改实现；完成一个任务后运行该任务列出的验证命令。除非用户明确要求，不自动创建 Git commit。
>
> **设计依据：**[2026-09-26-phase1-design.md](../specs/2026-09-26-phase1-design.md)

## 目标与边界

本计划实现四项能力：

1. 保持现有规则计划 API，在服务端补齐模板回归保护。
2. 将服务端 active plan 幂等投影为 Android 本地任务，并与通用 `clientGuid` 墓碑同步协议隔离。
3. 首页优先展示当天服务端计划任务，离线或无计划时保留现有本地计划兜底。
4. 在现有统计入口生成基于自然周、有效 session 的离线规则周报。

不实现 AI、动态重排、计划分享、付费、Web 仪表盘，也不重写计时器和通用云同步协议。

## 工作约定

- 服务端计划是权威；本地任务只是可执行投影。
- 计划任务必须同时具备 `planId + planItemId + accountGuid` 来源边界。
- 手工任务不能被计划同步覆盖、删除或改写。
- 计划投影不能进入 `StudyRepository.collectPush()` 的普通任务推送批次。
- active plan 请求失败、空响应或单项异常都不能清空已有本地任务。
- 账号切换、注销、备份恢复后不得显示前一账号的计划投影。
- 所有日期边界使用系统时区；周报自然周为周一至周日。

---

## 阶段一：服务端规则模板回归保护

### 1. 锁定生成器行为

**修改文件：**

- `server/src/modules/planning/generator.test.ts`
- 必要时才修改 `server/src/modules/planning/generator.ts`

**先写测试：**

- 同一固定输入的阶段名称、日期范围、排序和计划项输出稳定。
- 三阶段连续覆盖开始日到考试日前一天，比例保持约 `45/35/20`。
- 每个备考日不超过 3 项；每日分钟数等于输入预算。
- 每日预算达到 120 分钟才生成 30 分钟复盘项。
- 多个薄弱科目都被轮转覆盖，单科输入不超过项目上限。
- 非法日期、过短区间、空科目、非法分钟数继续返回既有错误码。

**实现要求：**

- 优先只整理命名、常量或纯函数边界，不改变现有 API、Prisma 模型和响应结构。
- 如果需要保证跨自然日的稳定输出，为生成器增加显式的基准日期/时钟注入；不要用当前系统时间隐式承诺跨日稳定。
- 不扩展 AI 字段或新计划来源。

**验证：**

```powershell
Set-Location f:\kaoyan-app-prd\server
npm test -- --test-name-pattern="planning|stage|budget|review|stable"
npm run typecheck
```

预期：相关测试通过，类型检查通过；然后运行完整 `npm run verify`。

### 2. 服务端计划接口回归

**检查/修改文件：**

- `server/src/modules/planning/schemas.ts`
- `server/src/modules/planning/routes.ts`
- `server/src/modules/planning/service.ts`
- `server/src/modules/planning/service.test.ts`
- `server/src/modules/planning/schemas.test.ts`

**先写测试：**

- active plan 响应包含 `plan.id`、`version`、阶段和计划项的 `id`、`subject`、`planDate`、`minutes`、`status`。
- 计划生成后旧版本归档，新版本成为 active；用户之间不能读取彼此计划。
- 计划项日期、分钟数、状态和排序校验继续生效。

**实现要求：**

- 保持 `GET /plans/active` 和 `POST /plans/generate` 的鉴权、限流、错误码与 DTO 兼容。
- 只补足 Phase 1 所需的测试或最小修正，不重写服务层事务。

**验证：**

```powershell
Set-Location f:\kaoyan-app-prd\server
npm run verify
```

---

## 阶段二：Android 计划投影数据基础

### 3. 扩展 Room 计划来源字段并迁移

**先写测试：**

- 新建手工任务时来源字段为空，旧任务从 v6 迁移后来源字段为空且可正常读取。
- 计划来源键可唯一定位 `accountGuid + planId + planItemId`。
- 同一来源键不能插入第二条任务。
- 手工任务与计划任务可以拥有相同标题、同一科目和同一天日期。

**修改文件：**

- `YanZhong/app/src/main/java/com/yanzhong/app/data/db/Entities.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/data/db/Daos.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/data/db/YanZhongDatabase.kt`
- 新增数据库迁移/DAO 测试文件，放在现有 `app/src/test` 目录下；若项目已有 Room instrumented test 约定，沿用该目录和命名。

**实现要求：**

- 给 `TaskEntity` 增加最小来源字段：账号标识、计划 ID、计划项 ID；手工任务默认空值。
- 使用数据库唯一索引或等价查询约束保证同一账号下同一计划项只有一条投影任务。
- 版本从 6 升到 7，注册 `MIGRATION_6_7`；迁移不得删除或重写存量任务。
- 来源字段不能改变现有 `clientGuid`、`dirty`、墓碑字段的语义。
- 明确计划任务完成、顺延和专注计数是否作为本地执行状态保存；同步刷新时只更新服务端拥有的计划元数据，不覆盖本地执行状态，除非设计明确要求状态重置。

**验证：**

```powershell
Set-Location f:\kaoyan-app-prd\YanZhong
./gradlew.bat :app:testDebugUnitTest --tests "*Database*" --tests "*Task*"
```

预期：迁移、唯一性、旧数据兼容测试通过。

### 4. 将计划投影排除出通用云同步边界

**先写测试：**

- `collectPush()` 不返回计划投影任务。
- 手工任务仍会进入原有 `tasks` 推送批次。
- `applyPull()` 的普通任务变更不会误识别成计划投影，也不会覆盖计划来源键。
- 删除计划投影不会为通用任务同步创建错误的普通任务墓碑；手工任务删除行为保持不变。
- `restoreFromBackup()` 后计划投影清理/重建策略符合账号和 active plan 规则。

**修改文件：**

- `YanZhong/app/src/main/java/com/yanzhong/app/data/repo/StudyRepository.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/data/db/Daos.kt`
- 相关 Repository 测试

**实现要求：**

- `TaskDao.getDirty()` 或 `collectPush()` 按来源字段过滤，仅收集手工任务。
- 计划投影的本地编辑入口必须保留为本地执行状态更新，不将计划来源任务变成普通云同步任务。
- 计划投影删除、账号切换和重建使用独立事务；不能破坏已有手工任务墓碑。
- `applyPull()` 维持现有 LWW 行为，只处理通用同步资源。

**验证：**

```powershell
Set-Location f:\kaoyan-app-prd\YanZhong
./gradlew.bat :app:testDebugUnitTest --tests "*StudyRepository*" --tests "*Sync*"
```

---

## 阶段三：账号隔离与 active plan 投影同步

### 5. 持久化登录账号标识

**先写测试：**

- 登录成功同时保存 `resp.user.guid`；刷新 token 不改变账号标识。
- 注销或 token 失效后清除当前账号标识，但保留设备 GUID 和服务器配置。
- 同一设备切换账号时，旧账号的计划投影不会被新账号复用。

**修改文件：**

- `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/TokenStore.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/ui/auth/LoginViewModel.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/ui/cloud/CloudSyncViewModel.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/StatusSyncClient.kt`

**实现要求：**

- 增加 current account GUID 的读写 API，并在登录成功路径保存 `UserDto.guid`。
- 所有清 token 路径统一清理 account GUID，并触发计划投影清理或失效处理。
- 明确数据库是单账号本地库还是多账号分区库；Phase 1 采用最小可行方案：本地只展示 current account 的计划投影，切换账号时删除旧账号计划投影，手工任务按现有产品行为保留。
- 不清理设备 GUID、服务器地址、通用手工任务，除非现有注销流程已经明确要求全量清库。

**验证：**

```powershell
Set-Location f:\kaoyan-app-prd\YanZhong
./gradlew.bat :app:testDebugUnitTest --tests "*TokenStore*" --tests "*Login*" --tests "*CloudSync*"
```

### 6. 实现独立的计划同步器

**先写测试：**

- 同一个 `accountGuid + planId + planItemId` 重复同步只产生一条任务。
- 同一计划的计划项内容变化会更新对应投影，不会新增重复任务。
- 新计划会新增投影，旧计划投影保留但不覆盖手工任务。
- 同名科目复用现有科目；缺失科目只创建一次，并标记为计划来源或可安全复用。
- 非法日期、空标题、非正分钟数等单项异常被跳过，其他项目继续同步。
- 空计划和网络失败不删除旧投影；成功加载新 active plan 后只按明确的旧计划保留策略更新。
- Android 进程重启后根据 Room 来源字段继续幂等。

**新增/修改文件：**

- `YanZhong/app/src/main/java/com/yanzhong/app/data/plan/PlanProjector.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/data/plan/PlanProjectorTest.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/data/repo/StudyRepository.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/data/db/Daos.kt`
- 必要时在 `data/remote/Api.kt` 增加日期转换或 DTO 映射辅助，但不改变网络协议。

**实现要求：**

- 输入使用现有 `PlanDto`，输出只写 `SubjectEntity` 和带来源字段的 `TaskEntity`。
- 在一个 Room transaction 内完成来源查找、科目匹配/创建和任务 upsert。
- 计划日期按系统时区转换为当天任务的 `dueAt`，计划分钟转换为现有番茄估算规则，并保留服务端原始分钟可供首页展示；如现有字段无法无损表达，增加最小字段而不是用标题编码。
- 计划任务默认不产生 `dirty` 普通同步标记；完成、顺延等本地执行状态与计划元数据分离。
- 通过返回值提供新增、更新、跳过、失败数量，供 ViewModel 展示非阻塞状态。

**验证：**

```powershell
Set-Location f:\kaoyan-app-prd\YanZhong
./gradlew.bat :app:testDebugUnitTest --tests "*PlanProjector*" --tests "*StudyRepository*"
```

### 7. 接入 PlanViewModel 的刷新链路

**先写测试：**

- active plan 拉取成功后先保存内存 DTO，再执行投影；投影失败不抹掉上次可用状态。
- 请求失败时 `planUnavailable = true`，本地今日任务仍可用。
- 重复刷新不会重复任务；切换账号会使用新的 account GUID。
- 计划投影结果的错误只作为状态提示，不阻塞首页和专注入口。

**修改文件：**

- `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanViewModel.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/data/repo/StudyRepository.kt`
- 计划状态测试

**实现要求：**

- 保持现有 `GET /plans/active` 调用和失败降级语义。
- 刷新成功才更新 active plan 来源；失败不删除本地数据。
- 让首页 ViewModel 可以消费已经筛选好的今日计划状态，不在 Composable 中直接访问数据库或重复生成规则计划。

**验证：**

```powershell
Set-Location f:\kaoyan-app-prd\YanZhong
./gradlew.bat :app:testDebugUnitTest --tests "*Plan*"
```

---

## 阶段四：首页今日节奏

### 8. 抽取今日计划状态

**先写测试：**

- 当前日期有服务端投影时返回阶段、总分钟、已完成分钟/项数、剩余任务和任务详情。
- 当前日期无服务端投影时返回现有本地任务与 468 天计划兜底。
- 同时存在手工任务和计划任务时，计划区域优先展示计划任务，手工任务仍保留在本地任务区域。
- 计划任务可以通过本地 ID 进入现有 `startFocusFor()`。
- 完成任务后今日已完成和剩余状态更新。

**修改文件：**

- `YanZhong/app/src/main/java/com/yanzhong/app/ui/home/HomeViewModel.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/data/repo/StudyRepository.kt`
- 必要时新增 `data/plan/TodayPlanState.kt` 及对应纯 Kotlin 测试

**实现要求：**

- 在 Repository/ViewModel 层完成日期筛选、阶段归并和分钟统计。
- `HomeScreen.kt` 只渲染不可变状态，不直接调用 `PersonalPlan` 生成服务端内容。
- 保留现有本地 468 天逻辑作为无服务端计划 fallback。

### 9. 修改首页布局与非阻塞状态

**修改文件：**

- `YanZhong/app/src/main/java/com/yanzhong/app/ui/home/HomeScreen.kt`
- 如有共享组件，沿用现有主题和图标组件，不新增独立导航。

**实现要求：**

- 今日区域展示阶段、总分钟、完成进度、剩余任务、科目、计划时长和状态。
- 计划任务点击进入现有专注流程；不重写计时器。
- 计划不可用时显示简短非阻塞提示，不能遮挡现有任务操作。
- 同时验证手机和平板布局，避免今日区域在现有响应式布局中溢出或覆盖。

**验证：**

```powershell
Set-Location f:\kaoyan-app-prd\YanZhong
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:assembleDebug
```

---

## 阶段五：离线规则周报

### 10. 实现自然周纯函数聚合

**先写测试：**

- 周一至周日边界准确，跨周 session 不串周。
- 缺失日期补零，空数据返回零和明确空状态。
- `valid = false` 的 session 完全排除。
- 总专注分钟、活跃天数、完成任务数准确。
- 科目按投入分钟降序，科目为空时返回无科目状态。
- 峰值时段按有效 session 的小时聚合，平局有稳定规则。
- 有周目标时计算完成率；无周目标时返回未设置而非 0%。

**新增/修改文件：**

- `YanZhong/app/src/main/java/com/yanzhong/app/data/stats/WeeklyReport.kt`
- `YanZhong/app/src/test/java/com/yanzhong/app/data/stats/WeeklyReportTest.kt`
- 必要时复用 `LearningProfile.kt` 的数据类型，但不能把“近七天”误当自然周。

**实现要求：**

- 输入为不可变 session、任务、科目、周目标和 `ZoneId`/当前时间；输出为不可变 `WeeklyReport`。
- 聚合使用系统时区，按周一 00:00 到下周一 00:00 计算。
- 仅数据层负责业务计算，Compose 页面不自行聚合。

### 11. 接入 StatsViewModel 与统计页

**先写测试：**

- ViewModel 在本地无网络时仍能从 Room session 生成周报。
- 现有 `StatsRange.WEEK` 和历史统计保持可用，不重复显示冲突指标。
- 周目标未配置时页面显示“未设置”，不是伪造完成率。
- 空数据、无科目和有数据三种状态均有稳定 UI state。

**修改文件：**

- `YanZhong/app/src/main/java/com/yanzhong/app/ui/stats/StatsViewModel.kt`
- `YanZhong/app/src/main/java/com/yanzhong/app/ui/stats/StatsScreen.kt`
- 必要时修改 `StatsRange` 或现有统计 DTO，但保持其他统计范围兼容。

**实现要求：**

- 复用现有统计页入口和主题组件。
- 页面只渲染 `WeeklyReport` 状态，周报计算不放在 Composable。
- 离线时不显示网络错误作为周报失败；网络状态与本地统计状态分开。

**验证：**

```powershell
Set-Location f:\kaoyan-app-prd\YanZhong
./gradlew.bat :app:testDebugUnitTest --tests "*WeeklyReport*" --tests "*Stats*"
```

---

## 阶段六：全量回归与设备验收

### 12. 自动化回归

**验证命令：**

```powershell
Set-Location f:\kaoyan-app-prd\server
npm run verify

Set-Location f:\kaoyan-app-prd\YanZhong
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:assembleDebug
```

**预期：**

- 服务端类型检查、测试、构建全部通过。
- Android 单元测试全部通过。
- Debug APK 构建成功。
- 不覆盖或删除工作区已有的 MineScreen、MineViewModel、PomodoroEngine、学习档案相关改动。

### 13. 真机/模拟器验收矩阵

按以下顺序执行并记录结果：

1. 新账号登录，完成 onboarding，生成规则计划。
2. active plan 刷新成功，首页出现当天计划阶段、任务和分钟数。
3. 重复刷新，任务数量不增加，标题/分钟更新正确。
4. 创建同名手工任务，确认手工任务不被计划同步覆盖。
5. 从计划任务启动专注，完成有效 session，首页完成进度和统计周报更新。
6. 让网络不可用，确认首页、本地任务、专注和周报继续可用。
7. 切换到第二账号，确认第一账号计划投影不可见；切回后按规则重新拉取。
8. 注销并重新登录，确认账号标识和计划投影边界正确。
9. 执行备份恢复，确认恢复后不会错误复用其他账号的计划来源。
10. 在手机和平板窗口验证首页和统计页无重叠、截断和溢出。

## 完成判定

仅当以下条件全部满足时标记 Phase 1 完成：

- 设计文档中的四项目标均有实现任务和测试覆盖。
- Room v6→v7 迁移、来源唯一性、账号隔离和通用同步边界通过测试。
- active plan 能幂等投影，失败不清空本地内容。
- 首页能启动当天服务端计划任务，并保留离线兜底。
- 周报按自然周统计有效 session，覆盖空数据、补零、科目、峰值和目标未设置。
- 服务端 `npm run verify`、Android `testDebugUnitTest`、`assembleDebug` 全部通过。
- Phase 0 登录、云同步、导出、注销、MineScreen 和既有本地计划功能无回归。
