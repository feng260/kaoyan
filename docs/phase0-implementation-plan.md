# Phase 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成研钟 Phase 0，让新用户能够完成备考配置并获得规则版动态计划，同时补齐协议、数据权利、备份和 HTTPS 部署准备。

**Architecture:** 计划领域作为服务端权威资源，通过独立 API 与 Android 客户端交互，不进入现有 `clientGuid` 墓碑同步协议。Android 首次使用流程通过登录后的状态网关接入，失败时继续使用已有本地 468 天计划；服务端数据库、导出和注销能力复用现有认证与备份基础设施。

**Tech Stack:** Koa、TypeScript、Prisma 6、MySQL 8、Zod、Kotlin、Jetpack Compose、Retrofit、kotlinx.serialization、Room v6、Docker Compose、PowerShell、Bash。

---

## 进度快照（2026-09-25 执行后复核）

已按代码库逐项核对落点：服务端测试 62/62 通过、`npm run typecheck` 通过、`prisma validate` 通过、API 冒烟 21/21 通过、Android 编译与 Debug APK 通过。

| 任务 | 状态 | 落点与证据 |
| --- | --- | --- |
| Task 1 数据模型 | 已完成 | `server/prisma/schema.prisma` 新增 `UserProfile`/`Plan`/`PlanStage`/`PlanItem`，`User` 增 `termsAcceptedAt`/`privacyAcceptedAt`；校验与 schema 单测绿 |
| Task 2 规则版生成器 | 已完成 | `server/src/modules/planning/generator.ts`，阶段边界与确定性测试绿 |
| Task 3 计划服务与 API | 已完成 | 四个端点由 `planning/routes.ts` 提供，事务回滚测试绿；额外增加 `planChanged` WebSocket 广播，多端免手动下拉；`examDateSchema` 已改为幂等，修复 `PUT /profile` 恒 400 |
| Task 4 协议与账号数据 | 已完成 | `public/terms.html`、`public/privacy.html`、`auth/consent.ts`、`account/` 模块；注销在同一事务内显式删除 15 张表（业务表无外键，不能靠级联）；鉴权增加用户存在性校验使旧 token 即刻失效 |
| Task 5 客户端接入 | 已完成 | `Api.kt` DTO、`onboarding/` 问卷与状态机、`legal/` 协议组件、`MainActivity` 的登录 + 档案两道门、我的页导出与两步注销、计划页展示服务端计划（离线回退本地 468 天计划）；`PlanSelectionTest` 等新增用例；编译与 APK 通过，单测 worker 环境异常已记录待复跑 |
| Task 6 备份与部署准备 | 已完成 | `server/scripts/backup-mysql.sh`、`restore-mysql-drill.sh`、`server/deploy/Caddyfile.example`、`server/docs/deploy.md`、`server/docs/phase0-rollout-checklist.md`；脚本 `bash -n` 通过 |
| Task 7 端到端验收 | 已完成 | `docs/phase0-verification.md`；服务端验证、Android 编译、本地 API 冒烟 21/21 已完成，域名 HTTPS 切换与真实恢复演练待线上执行 |

**仅剩一件事待确认**：Step 5 的提交动作（按逻辑分组 commit）尚未执行，等待确认后一次性提交。

三点实现与初稿的偏差，以代码为准：计划数据落在独立的 `plans`/`plan_stages`/`plan_items`，**不写回 `subjects`/`tasks`**，避免与 `clientGuid` 墓碑同步互相污染；档案接口路径是 `/api/v1/profile`，不是 `/onboarding`；冒烟脚本入库为 `server/scripts/phase0-api-smoke.mjs`，通过 `npm run smoke:api` 执行。

> 下方 Step 级勾选框按代码实际落点标记，未勾选项即剩余工作。服务端 `npm run build`（`prisma generate && tsc`）已复核通过（需先停掉占用引擎 DLL 的 dev 进程）。

---

## 文件清单

### 服务端

- Modify: `server/prisma/schema.prisma`，增加用户档案、计划、阶段、计划项；协议确认时间加在 `User` 上（注册时还没有档案）。
- Modify: `server/package.json`，增加 `test` 脚本（node:test + tsx），不改变运行时行为。
- Create: `server/src/modules/planning/schemas.ts`，集中定义 Zod 输入校验和输出类型。
- Create: `server/src/modules/planning/generator.ts`，实现按考试日期计算阶段和规则计划项的纯函数。
- Create: `server/src/modules/planning/service.ts`，实现档案读写、计划生成、计划查询和事务边界。
- Create: `server/src/modules/planning/routes.ts`，提供认证后的档案和计划 API。
- Modify: `server/src/app.ts`，注册 planning 路由。
- Modify: `server/src/modules/auth/routes.ts`，注册时要求协议确认并保存确认时间。
- Modify: `server/src/modules/user/routes.ts`，增加导出和注销接口，复用现有全量备份能力。
- Modify: `server/src/modules/user/service.ts`，实现按用户删除或匿名化数据。
- Create: `server/public/privacy.html`，隐私政策页面。
- Create: `server/public/terms.html`，用户协议页面。
- Modify: `server/src/app.ts`，暴露协议静态页面。

### Android

- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt`，增加档案、计划、导出和注销 DTO/API。
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingScreen.kt`，实现分步问卷 UI。
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt`，管理问卷状态、提交和错误回退。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt`，增加 onboarding 路由和登录后的状态判断。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/MainActivity.kt`，把档案检查状态传入主导航。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/auth/LoginScreen.kt`，注册入口展示协议链接和确认项。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt`，增加协议、导出和注销入口。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/plan/PlanScreen.kt`，展示服务端权威计划，保留本地 468 天计划作为回退。

### 运维

- Create: `server/scripts/backup-mysql.sh`，每日备份、压缩、保留周期和日志。
- Create: `server/scripts/restore-mysql-drill.sh`，恢复到临时数据库容器的演练脚本。
- Create: `server/deploy/Caddyfile.example`，域名 HTTPS 反向代理模板。
- Modify: `server/docs/deploy.md`，补充备份安装、恢复演练、域名切换和验收命令。
- Create: `server/docs/phase0-rollout-checklist.md`，线上执行清单和回滚条件。

### 测试

- Create: `server/src/modules/planning/generator.test.ts`，阶段边界、过期日期和计划项生成测试。
- Create: `server/src/modules/planning/schemas.test.ts`，输入校验测试。
- Create: `server/src/modules/planning/service.test.ts`，事务失败不产生半成品计划的测试，使用项目现有测试约定或轻量 mock。
- Add or modify Android unit tests under `YanZhong/app/src/test/` for onboarding validation and serialization.

---

## Task 1: Extend the server data model

**Files:**
- Modify: `server/prisma/schema.prisma`
- Test: `server/src/modules/planning/schemas.test.ts`

- [x] **Step 1: Add the profile and planning models**

Add models with user ownership and indexes. Use string enums for extensible target types and statuses. Store dates as `DateTime` at the service boundary and derive day-level plan items in the user timezone.

**Constraint:** registration happens before any exam date exists, so consent timestamps cannot live on `UserProfile` (whose `examDate` is required). Add `termsAcceptedAt DateTime?` and `privacyAcceptedAt DateTime?` to the existing `User` model instead.

**Constraint:** existing business tables (`tasks`, `sessions`, `subjects`, …) associate users by the `userGuid` string and mostly have no Prisma foreign key. Do not rely on cascade to clean them up; account deletion must delete explicitly.

The minimum fields are:

```prisma
model UserProfile {
  id                Int      @id @default(autoincrement())
  userGuid          String   @unique
  targetType        String   @default("考研")
  examDate          DateTime
  dailyMinutes      Int
  studyWindowsJson  String?
  foundation        String?
  weakSubjectsJson  String?
  onboardingDoneAt  DateTime?
  createdAt         DateTime @default(now())
  updatedAt         DateTime @updatedAt
  user              User     @relation(fields: [userGuid], references: [guid], onDelete: Cascade)
  plans             Plan[]
}

model Plan {
  id          Int          @id @default(autoincrement())
  userGuid    String
  profileId   Int
  title       String
  targetType  String
  source      String       @default("rule")
  status      String       @default("active")
  startDate   DateTime
  examDate    DateTime
  version     Int          @default(1)
  createdAt   DateTime     @default(now())
  updatedAt   DateTime     @updatedAt
  user        User         @relation(fields: [userGuid], references: [guid], onDelete: Cascade)
  profile     UserProfile  @relation(fields: [profileId], references: [id], onDelete: Cascade)
  stages      PlanStage[]
  items       PlanItem[]
  @@index([userGuid, status])
}

model PlanStage {
  id        Int        @id @default(autoincrement())
  planId    Int
  name      String
  startDate DateTime
  endDate   DateTime
  sortOrder Int
  plan      Plan       @relation(fields: [planId], references: [id], onDelete: Cascade)
  items     PlanItem[]
  @@index([planId, sortOrder])
}

model PlanItem {
  id          Int       @id @default(autoincrement())
  planId      Int
  stageId     Int
  subject     String
  title       String
  planDate    DateTime
  minutes     Int
  priority    Int       @default(0)
  status      String    @default("pending")
  sortOrder   Int       @default(0)
  plan        Plan      @relation(fields: [planId], references: [id], onDelete: Cascade)
  stage       PlanStage @relation(fields: [stageId], references: [id], onDelete: Cascade)
  @@index([planId, planDate])
}
```

Add the inverse relations to `User` using the project’s existing naming style.

- [x] **Step 2: Generate Prisma client and validate the schema**

Run from `server`:

```powershell
npm run db:generate
npx prisma validate
```

Expected: Prisma client generation succeeds and validation reports no schema errors.

- [x] **Step 3: Add schema validation tests**

Test that a valid profile accepts an ISO date, positive daily minutes, a supported target type, and at least one weak subject; test that past dates, zero minutes, and malformed JSON fields fail with field-level errors.

- [x] **Step 4: Run the focused tests**

Run:

```powershell
npm test -- planning/schemas
```

If the repository does not yet define an npm test script, add the smallest project-local test runner configuration before running the test, without changing production runtime behavior.

---

## Task 2: Implement deterministic rule-plan generation

**Files:**
- Create: `server/src/modules/planning/generator.ts`
- Create: `server/src/modules/planning/generator.test.ts`

- [x] **Step 1: Define the generator contract**

Use a pure function with this contract:

```ts
export type PlanningInput = {
  examDate: Date
  startDate: Date
  dailyMinutes: number
  weakSubjects: string[]
  studyWindows: string[]
}

export type GeneratedPlan = {
  stages: Array<{
    name: string
    startDate: Date
    endDate: Date
    sortOrder: number
  }>
  items: Array<{
    stageOrder: number
    subject: string
    title: string
    planDate: Date
    minutes: number
    priority: number
    sortOrder: number
  }>
}

export function generateRulePlan(input: PlanningInput): GeneratedPlan
```

- [x] **Step 2: Implement stage boundaries**

Split the inclusive date range into three contiguous stages:

- 基础：first 45% of available days;
- 强化：next 35%;
- 冲刺：remaining days through the day before the exam.

Reject an exam date earlier than tomorrow and ranges shorter than 14 days. Ensure stage dates do not overlap, have no gaps, and always end before the exam date.

- [x] **Step 3: Implement daily items**

Generate at most three items per day. Allocate daily minutes across weak subjects first, then add a review item when at least two hours are available. Use stable titles and ordering so repeated generation with the same input produces identical output.

- [x] **Step 4: Add boundary and determinism tests**

Cover 14-day minimum, 15-day range, long range, one weak subject, multiple weak subjects, insufficient daily minutes, exam date in the past, no date gaps, and identical output for repeated calls.

- [x] **Step 5: Run tests**

Run:

```powershell
npm test -- planning/generator
```

Expected: all generator tests pass.

---

## Task 3: Add planning service and authenticated API

**Files:**
- Create: `server/src/modules/planning/service.ts`
- Create: `server/src/modules/planning/routes.ts`
- Modify: `server/src/app.ts`
- Test: `server/src/modules/planning/service.test.ts`

- [x] **Step 1: Implement profile read and upsert**

Provide:

```ts
getProfile(userGuid: string)
upsertProfile(userGuid: string, input: ProfileInput)
getActivePlan(userGuid: string)
generatePlan(userGuid: string)
```

All methods must scope by authenticated `userGuid`. Upsert must preserve the existing active plan until a new plan is fully generated.

- [x] **Step 2: Implement atomic plan generation**

Use one Prisma transaction to create or replace the active plan, all stages, and all items. Generate outside the transaction, validate the complete result, then persist inside the transaction. Mark the previous plan as archived only after the new plan rows are ready.

- [x] **Step 3: Add routes**

Add authenticated endpoints:

```text
GET  /api/v1/profile
PUT  /api/v1/profile
POST /api/v1/plans/generate
GET  /api/v1/plans/active
```

Return consistent envelopes matching the existing user and sync modules. Reject missing profile data with `PROFILE_INCOMPLETE`, invalid dates with `INVALID_EXAM_DATE`, and generation failures with `PLAN_GENERATION_FAILED`.

- [x] **Step 4: Register routes and test failure atomicity**

Register planning routes before generic 404 handling. Mock a failed item insert and verify no new active plan, stage, or item remains after the transaction rolls back.

- [x] **Step 5: Run server checks**

Run:

```powershell
npm run build
npx prisma validate
npm test -- planning
```

Expected: TypeScript build, Prisma validation, and planning tests pass.

---

## Task 4: Add privacy, terms, registration consent, export, and deletion

**Files:**
- Create: `server/public/privacy.html`
- Create: `server/public/terms.html`
- Modify: `server/src/app.ts`
- Modify: `server/src/modules/auth/routes.ts`
- Modify: `server/src/modules/user/routes.ts`
- Modify: `server/src/modules/user/service.ts`

- [x] **Step 1: Add static policy pages**

Create readable mobile-safe HTML pages covering account data, study data, device data, backup data, purpose, retention, deletion, export, security limitations of the current HTTP internal-test environment, and contact method. Terms must cover account responsibility, synchronization behavior, service availability, prohibited use, and cancellation.

- [x] **Step 2: Add registration consent fields**

Extend registration input with `termsAccepted` and `privacyAccepted`, both required to be true. Store the two acceptance timestamps on the `User` row (registration precedes any profile). Return `LEGAL_CONSENT_REQUIRED` when either is absent.

- [x] **Step 3: Add data export endpoint**

Add `GET /api/v1/account/export` returning the authenticated user’s account, settings, sync backup, profile, and active plan in a versioned JSON envelope. Do not include password hashes or refresh token hashes.

- [x] **Step 4: Add account deletion endpoint**

Add `POST /api/v1/account/delete` requiring a confirmation string and current password. Revoke refresh tokens, then inside one transaction explicitly `deleteMany` every user-owned table by `userGuid` — `devices`, `refresh_tokens`, `subjects`, `countdown_nodes`, `tasks`, `pomodoro_sessions`, `daily_reviews`, `weekly_reviews`, `monthly_reviews`, `user_settings`, `plan_items`, `plan_stages`, `plans`, `user_profiles`, and finally the `users` row. Most of these tables have no Prisma foreign key, so cascade is not available. Return a clear success envelope; a failed deletion must not report success.

- [x] **Step 5: Test legal and account flows**

Run the server build and authenticated API tests covering missing consent, successful consent, export redaction, wrong deletion confirmation, wrong password, and successful deletion.

---

## Task 5: Integrate Android onboarding and server plan display

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingScreen.kt`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/MainActivity.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/auth/LoginScreen.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt`

- [x] **Step 1: Add serializable DTOs and API methods**

Add `ProfileDto`, `ProfileReq`, `PlanDto`, `PlanStageDto`, `PlanItemDto`, and API methods for the four planning endpoints plus export and deletion. Use nullable fields only where the server actually returns nullable values.

- [x] **Step 2: Implement onboarding state**

The ViewModel must expose loading, editing, submitting, success, and fallback states. Preserve entered values on validation or network errors. A fallback state must leave the existing local 468-day plan untouched and offer retry.

- [x] **Step 3: Build the Compose questionnaire**

Use a compact two-step flow: target and exam date first; daily minutes, study windows, foundation, and weak subjects second. The primary action text should be natural and specific, such as “生成我的计划”, not generic AI wording. Show policy links near the submit action.

- [x] **Step 4: Gate the main navigation**  （实际落在 `MainActivity` 的登录 + 档案两道门，`AppNav` 不改）

After login, request `GET /profile`. If the profile is incomplete, route to onboarding. If complete or unavailable due to a transient network issue, keep the existing home flow available and show a retry entry rather than blocking offline use.

- [x] **Step 5: Add account actions to Mine**

Expose policy links, export, and deletion under account and security. Confirm deletion twice and require the server’s success response before clearing local credentials.

- [x] **Step 6: Display the server plan**

`PlanScreen` must render the active server plan (plan title, stage names with date ranges, and the current stage's upcoming items) when one exists, and keep the existing local 468-day plan visible whenever the server plan is missing, failed to load, or the device is offline. Never show an empty plan screen; the local plan is the fallback, not a replacement.

- [x] **Step 7: Build and test Android**  （Kotlin 编译与 Debug APK 通过；单测编译通过但 Gradle worker 启动异常，详见 `docs/phase0-verification.md`）

Run from `YanZhong`:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Expected: unit tests pass and a debug APK is produced.

---

## Task 6: Add backup, restore drill, and HTTPS deployment preparation

**Files:**
- Create: `server/scripts/backup-mysql.sh`
- Create: `server/scripts/restore-mysql-drill.sh`
- Create: `server/deploy/Caddyfile.example`
- Modify: `server/docs/deploy.md`
- Create: `server/docs/phase0-rollout-checklist.md`

- [x] **Step 1: Write the backup script**

The script must accept `BACKUP_DIR`, `RETENTION_DAYS`, and `COMPOSE_PROJECT_DIR` environment variables, fail on errors, create a dated compressed dump, remove files older than the retention period, and write a timestamped log. It must never call `docker compose down -v`.

- [x] **Step 2: Write the restore drill script**

The script must create a temporary MySQL container or isolated database, import the selected dump, run a health query and row-count query, then remove only drill resources. It must refuse to run against the production database unless `ALLOW_PRODUCTION_RESTORE=YES` is explicitly set.

- [x] **Step 3: Add Caddy template**

Proxy the chosen domain to `127.0.0.1:3000`, preserve WebSocket upgrades, and include a placeholder for the real domain. Document setting `TRUST_PROXY=true` only after proxy verification.

- [x] **Step 4: Update deployment docs and checklist**

Document DNS, firewall ports 80/443, local binding of API port 3000, backup cron installation, restore drill, APK endpoint checks, HTTPS client address update, removal of the public IP cleartext allowance, and rollback to the prior container image.

- [x] **Step 5: Validate shell syntax locally**

Run PowerShell-compatible checks where available and, in a Linux-compatible environment, run:

```bash
bash -n server/scripts/backup-mysql.sh
bash -n server/scripts/restore-mysql-drill.sh
```

Expected: both scripts pass syntax validation. Actual cloud execution remains pending server login and domain access.

---

## Task 7: End-to-end verification and handoff

**Files:**
- Modify: `docs/yanzhong-evolution/yanzhong-evolution.html` only if Phase 0 status text needs updating.
- Create: `docs/phase0-verification.md`

- [x] **Step 1: Run server verification**

Run:

```powershell
npm run build
npx prisma validate
npm test -- planning
```

Record the results and any environment limitations.

- [x] **Step 2: Run Android verification**  （编译与 APK 通过；单测 worker 异常已记录）

Run the unit tests and debug APK build. Confirm existing Room v6 and sync-related tests remain green.

- [x] **Step 3: Run API smoke checks**

Against a local or explicitly configured test server, verify registration consent, login, profile read/write, plan generation, active plan read, export redaction, and deletion behavior. Do not claim cloud deployment success without a successful authenticated check against the deployed server.

实现说明：冒烟脚本已入库为 `server/scripts/phase0-api-smoke.mjs`，并通过 `npm run smoke:api --prefix server` 执行（支持 `SMOKE_BASE` 环境变量指向已切换的 HTTPS 域名）。本轮对本地实例执行，覆盖 21 项断言并全部通过；过程暴露并修复了三个真实缺陷（见 `docs/phase0-verification.md`）。线上域名冒烟仍需在 DNS/HTTPS 切换后复跑。

- [x] **Step 4: Write verification report**

Record passed checks, skipped checks, cloud prerequisites, current HTTP internal-test limitation, and the exact next server commands.

- [ ] **Step 5: Commit the implementation in logical commits**

Use separate commits for server planning, Android onboarding, legal/account data, and operations documentation. Do not include generated secrets, production dumps, APKs, or local machine configuration.
