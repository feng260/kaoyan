# 个人资料自定义 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让备考者自定义头像（预设+相册上传）、昵称、签名、性别、生日，并在「我的」头部卡与账号卡一致展示。

**Architecture:** 身份字段挂服务端 `User` 表（全可空），account 模块新增 `GET/PATCH /api/v1/account/me`；头像 base64 存 DB，预设与自定义互斥。客户端复用 onboarding 的选图压缩链路，新增编辑资料页 + UserAvatar 组件，TokenStore 缓存身份。

**Tech Stack:** 服务端 Node/Koa/TypeScript/Prisma/zod/node:test；客户端 Kotlin/Jetpack Compose Material3/kotlinx.serialization/Retrofit/DataStore/JUnit4。

**Spec:** `docs/superpowers/specs/2026-11-09-profile-customization-design.md`

## Global Constraints

- 始终在 main 工作，不新建分支；未经负责人明确要求不执行 git commit/push/服务端部署（本计划含 commit 步骤，执行时若未获授权则跳过 commit 仅留改动）。
- 不把 SSH 密码/凭证写进仓库或日志；测试只用一次性测试账号。
- 文案用「备考者」，硬编码中文（strings.xml 仅 app_name+widget）；不引入 Coil 等新依赖。
- 身份字段全部可空；昵称空回落 username，头像空回落学士帽图标，老账号无感。
- 预设 key 白名单（服务端=客户端）：`fox,panda,owl,rocket,book,moon,star,clover,cat,target`（10 个）。
- 头像：客户端压到最长边 512/JPEG 85；服务端解码后 ≤1MB（1_048_576 字节），魔数 JPEG/PNG/WebP；预设与自定义互斥。
- 字段上限：昵称 ≤32 码点、签名 ≤100 码点（`Array.from(s).length`）；性别 ∈ male/female/other/secret；生日 YYYY-MM-DD 真实日期、年份≥1900、不晚于今天。
- Windows Gradle：用 PowerShell `gradlew.bat` + `-Xmx1536m` + GBK（见 windows-msys-gradle 记忆），不用 Git Bash `./gradlew`。
- 服务端验证命令：`cd server && npm run verify`（typecheck+test+build）；单测 `npm test`。

## File Structure

服务端：
- `server/prisma/schema.prisma`（改：User 加 6 列）
- `server/src/modules/account/avatarPresets.ts`（新：key 白名单 + 大小上限）
- `server/src/modules/account/identity.ts`（新：纯校验/归一 normalizeIdentityPatch + normalizeAvatar）
- `server/src/modules/account/identity.test.ts`（新：纯函数测试）
- `server/src/modules/account/service.ts`（改：getIdentity/updateIdentity + export 含身份 + AccountDb.user.updateMany）
- `server/src/modules/account/service.test.ts`（改：身份读写/互斥/导出测试）
- `server/src/modules/account/routes.ts`（改：GET/PATCH me）
- `server/src/modules/auth/service.ts`（改：PublicUser + publicUser 加 nickname/avatarPreset）

客户端（前缀 `YanZhong/app/src/main/java/com/yanzhong/app/`）：
- `data/remote/Api.kt`（改：UserDto 字段 + IdentityDto/AccountMeResp/UpdateIdentityReq + 2 方法）
- `data/remote/TokenStore.kt`（改：IdentityCache + 键 + observe/save/clear）
- `data/remote/Identity.kt`（新：refreshIdentity）
- `data/util/ImageCompression.kt`（新：共享 compressImageToBase64）
- `ui/onboarding/OnboardingViewModel.kt`（改：委托共享工具）
- `ui/profile/PresetAvatars.kt`（新）
- `ui/profile/UserAvatar.kt`（新）
- `ui/profile/ProfileValidation.kt`（新：纯校验）
- `ui/profile/EditProfileViewModel.kt`（新）
- `ui/profile/EditProfileScreen.kt`（新）
- `ui/nav/AppNav.kt`（改：Routes.EDIT_PROFILE + 目的地 + deepPage）
- `ui/mine/MineScreen.kt`（改：HeaderCard 头像昵称 + 入口）
- `ui/mine/MineViewModel.kt`（改：identity 流 + refresh）
- `ui/account/AccountScreen.kt`（改：账号卡头像昵称 + 编辑资料入口）
- 测试（前缀 `YanZhong/app/src/test/java/com/yanzhong/app/`）：`ui/profile/ProfileValidationTest.kt`、`ui/profile/PresetAvatarsTest.kt`

---

### Task 1: 服务端身份字段 + 预设白名单 + 纯校验（TDD）

**Files:**
- Modify: `server/prisma/schema.prisma:17-35`（User model）
- Create: `server/src/modules/account/avatarPresets.ts`
- Create: `server/src/modules/account/identity.ts`
- Test: `server/src/modules/account/identity.test.ts`

**Interfaces:**
- Produces: `AVATAR_PRESET_KEYS: readonly string[]`、`AVATAR_MAX_BYTES = 1048576`；`IdentityInput`、`IdentityFields`；`normalizeIdentityPatch(input: IdentityInput, today: string): Partial<IdentityFields>`（校验失败 throw `ApiError(400,…)`）；`normalizeAvatar(value: string): string`。

- [ ] **Step 1: schema 加列**

在 `User` model `createdAt BigInt` 行后、`profile UserProfile?` 行前插入：

```prisma
  nickname     String? @db.VarChar(32)
  signature    String? @db.VarChar(100)
  gender       String? @db.VarChar(12)
  birthday     String? @db.VarChar(10)
  avatarPreset String? @db.VarChar(32)
  avatarData   String? @db.Text
```

- [ ] **Step 2: 写失败测试 identity.test.ts**

```typescript
import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../middlewares/error'
import { normalizeIdentityPatch, normalizeAvatar } from './identity'
import { AVATAR_PRESET_KEYS } from './avatarPresets'

const TODAY = '2026-11-09'
// 1x1 JPEG 的裸 base64(魔数 FF D8 FF 开头),足够过魔数与大小校验
const JPEG = '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AVN//2Q=='

test('nickname/signature trim 且空串归 null', () => {
  const patch = normalizeIdentityPatch({ nickname: '  小明  ', signature: '   ' }, TODAY)
  assert.equal(patch.nickname, '小明')
  assert.equal(patch.signature, null)
})

test('未传的字段不出现在 patch 里', () => {
  const patch = normalizeIdentityPatch({ nickname: 'A' }, TODAY)
  assert.deepEqual(Object.keys(patch), ['nickname'])
})

test('昵称超过 32 码点报 400', () => {
  assert.throws(() => normalizeIdentityPatch({ nickname: '字'.repeat(33) }, TODAY),
    (e: unknown) => e instanceof ApiError && e.status === 400)
})

test('性别枚举外的值报 400', () => {
  assert.throws(() => normalizeIdentityPatch({ gender: 'unknown' }, TODAY),
    (e: unknown) => e instanceof ApiError && e.status === 400)
  assert.equal(normalizeIdentityPatch({ gender: 'secret' }, TODAY).gender, 'secret')
})

test('生日:假日期/未来/早于1900 报 400,合法落库', () => {
  for (const bad of ['2026-02-30', '2027-01-01', '1899-12-31', '2026-13-01', 'abc']) {
    assert.throws(() => normalizeIdentityPatch({ birthday: bad }, TODAY),
      (e: unknown) => e instanceof ApiError && e.status === 400, bad)
  }
  assert.equal(normalizeIdentityPatch({ birthday: '2003-05-01' }, TODAY).birthday, '2003-05-01')
  assert.equal(normalizeIdentityPatch({ birthday: '' }, TODAY).birthday, null)
})

test('预设 key 白名单:未知报 400,合法且清自定义', () => {
  assert.throws(() => normalizeIdentityPatch({ avatarPreset: 'dragon' }, TODAY),
    (e: unknown) => e instanceof ApiError && e.status === 400)
  const patch = normalizeIdentityPatch({ avatarPreset: 'fox' }, TODAY)
  assert.equal(patch.avatarPreset, 'fox')
  assert.equal(patch.avatarData, null)
  assert.equal(AVATAR_PRESET_KEYS.length, 10)
})

test('自定义头像合法 base64 归一为裸串且清预设', () => {
  const patch = normalizeIdentityPatch({ avatar: `data:image/jpeg;base64,${JPEG}` }, TODAY)
  assert.equal(patch.avatarData, JPEG)
  assert.equal(patch.avatarPreset, null)
})

test('头像非法魔数报 400', () => {
  const notImage = Buffer.from('hello world not an image').toString('base64')
  assert.throws(() => normalizeIdentityPatch({ avatar: notImage }, TODAY),
    (e: unknown) => e instanceof ApiError && e.status === 400)
})

test('预设与自定义同请求非空报 AVATAR_CONFLICT', () => {
  assert.throws(() => normalizeIdentityPatch({ avatarPreset: 'fox', avatar: JPEG }, TODAY),
    (e: unknown) => e instanceof ApiError && e.code === 'AVATAR_CONFLICT')
})

test('avatar=null 清空自定义', () => {
  assert.equal(normalizeIdentityPatch({ avatar: null }, TODAY).avatarData, null)
})
```

- [ ] **Step 3: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/account/identity.test.ts`
Expected: FAIL（Cannot find module './identity'）

- [ ] **Step 4: 实现 avatarPresets.ts**

```typescript
export const AVATAR_PRESET_KEYS = [
  'fox', 'panda', 'owl', 'rocket', 'book', 'moon', 'star', 'clover', 'cat', 'target',
] as const

export type AvatarPresetKey = typeof AVATAR_PRESET_KEYS[number]

/** 自定义头像解码后的字节上限(1MB);客户端已压到最长边 512,这里兜底防滥用 */
export const AVATAR_MAX_BYTES = 1_048_576
```

- [ ] **Step 5: 实现 identity.ts**

```typescript
import { ApiError } from '../../middlewares/error'
import { AVATAR_PRESET_KEYS, AVATAR_MAX_BYTES } from './avatarPresets'

export interface IdentityInput {
  nickname?: string | null
  signature?: string | null
  gender?: string | null
  birthday?: string | null
  avatarPreset?: string | null
  avatar?: string | null
}

export interface IdentityFields {
  nickname: string | null
  signature: string | null
  gender: string | null
  birthday: string | null
  avatarPreset: string | null
  avatarData: string | null
}

const GENDERS = ['male', 'female', 'other', 'secret']
const invalid = (msg: string) => new ApiError(400, 'INVALID_PARAMS', msg)
const codePoints = (s: string) => Array.from(s).length

/** 剥 data URL 前缀 → 解码校验魔数与大小 → 返回裸 base64 */
export function normalizeAvatar(value: string): string {
  const raw = value.includes(',') && value.trim().startsWith('data:')
    ? value.slice(value.indexOf(',') + 1) : value
  const clean = raw.trim()
  let bytes: Buffer
  try {
    bytes = Buffer.from(clean, 'base64')
  } catch {
    throw invalid('头像不是合法的 base64')
  }
  if (bytes.length === 0 || bytes.length > AVATAR_MAX_BYTES) throw invalid('头像图片过大或为空')
  const isJpeg = bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff
  const isPng = bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47
  const isWebp = bytes.slice(0, 4).toString('ascii') === 'RIFF' && bytes.slice(8, 12).toString('ascii') === 'WEBP'
  if (!isJpeg && !isPng && !isWebp) throw invalid('头像仅支持 JPEG/PNG/WebP')
  return bytes.toString('base64')
}

function validBirthday(value: string, today: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const [y, m, d] = value.split('-').map(Number)
  if (y < 1900 || value > today) return false
  const dt = new Date(Date.UTC(y, m - 1, d))
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d
}

/**
 * 局部更新归一:只处理 input 里出现的键(undefined=未传,跳过)。
 * 预设与自定义互斥:同请求两者非空 → AVATAR_CONFLICT;设其一清其二。
 */
export function normalizeIdentityPatch(input: IdentityInput, today: string): Partial<IdentityFields> {
  if (input.avatar != null && input.avatarPreset != null) {
    throw new ApiError(400, 'AVATAR_CONFLICT', '预设头像和自定义头像只能选一个')
  }
  const patch: Partial<IdentityFields> = {}
  if (input.nickname !== undefined) {
    const v = input.nickname === null ? '' : input.nickname.trim()
    if (codePoints(v) > 32) throw invalid('昵称最多 32 个字符')
    patch.nickname = v === '' ? null : v
  }
  if (input.signature !== undefined) {
    const v = input.signature === null ? '' : input.signature.trim()
    if (codePoints(v) > 100) throw invalid('签名最多 100 个字符')
    patch.signature = v === '' ? null : v
  }
  if (input.gender !== undefined) {
    if (input.gender !== null && !GENDERS.includes(input.gender)) throw invalid('性别取值非法')
    patch.gender = input.gender ?? null
  }
  if (input.birthday !== undefined) {
    const v = input.birthday === null ? '' : input.birthday.trim()
    if (v !== '' && !validBirthday(v, today)) throw invalid('生日格式或范围非法')
    patch.birthday = v === '' ? null : v
  }
  if (input.avatar !== undefined) {
    if (input.avatar === null) {
      patch.avatarData = null
    } else {
      patch.avatarData = normalizeAvatar(input.avatar)
      patch.avatarPreset = null
    }
  }
  if (input.avatarPreset !== undefined) {
    if (input.avatarPreset === null || input.avatarPreset === '') {
      patch.avatarPreset = null
    } else {
      if (!(AVATAR_PRESET_KEYS as readonly string[]).includes(input.avatarPreset)) throw invalid('未知的预设头像')
      patch.avatarPreset = input.avatarPreset
      patch.avatarData = null
    }
  }
  return patch
}
```

- [ ] **Step 6: 运行确认通过**

Run: `cd server && node --import tsx --test src/modules/account/identity.test.ts`
Expected: PASS（10 测试）

- [ ] **Step 7: 生成 Prisma Client + typecheck**

Run: `cd server && npx prisma generate && npx tsc --noEmit`
Expected: 无错误

- [ ] **Step 8: Commit（若获授权）**

```bash
git add server/prisma/schema.prisma server/src/modules/account/avatarPresets.ts server/src/modules/account/identity.ts server/src/modules/account/identity.test.ts
git commit -m "feat(account): add identity fields and validation for profile customization"
```

---

### Task 2: account 服务身份读写 + 导出含身份（TDD）

**Files:**
- Modify: `server/src/modules/account/service.ts`
- Test: `server/src/modules/account/service.test.ts`

**Interfaces:**
- Consumes: `normalizeIdentityPatch`、`IdentityInput`（Task 1）。
- Produces: `Identity` 类型；`service.getIdentity(userGuid): Promise<Identity>`；`service.updateIdentity(userGuid, input: IdentityInput): Promise<Identity>`。`AccountDb.user` 加 `updateMany`。

- [ ] **Step 1: 写失败测试（追加到 service.test.ts）**

```typescript
import { normalizeIdentityPatch } from './identity'

test('getIdentity returns identity fields and avatar base64', async () => {
  const fake = createFakeAccountDb()
  seed(fake.state)
  Object.assign(fake.state.user[0], { nickname: '小明', signature: '一战成硕', gender: 'male', birthday: '2003-05-01', avatarPreset: 'fox', avatarData: null })
  const { service } = serviceWith(fake)
  const id = await service.getIdentity(USER)
  assert.equal(id.username, 'chensi')
  assert.equal(id.nickname, '小明')
  assert.equal(id.avatarPreset, 'fox')
  assert.equal(id.avatar, null)
  assert.equal('passwordHash' in id, false)
})

test('updateIdentity changes only provided fields and clears avatar exclusivity', async () => {
  const fake = createFakeAccountDb()
  seed(fake.state)
  Object.assign(fake.state.user[0], { nickname: '旧', avatarPreset: 'fox', avatarData: null })
  const { service } = serviceWith(fake)
  const id = await service.updateIdentity(USER, { nickname: '新昵称', avatar: JPEG_BASE64 })
  assert.equal(id.nickname, '新昵称')
  assert.equal(id.avatarPreset, null)
  assert.equal(id.avatar, JPEG_BASE64)
  assert.equal(fake.state.user[0].nickname, '新昵称')
})

test('updateIdentity rejects unknown preset and avatar conflict', async () => {
  const fake = createFakeAccountDb()
  seed(fake.state)
  const { service } = serviceWith(fake)
  await assert.rejects(() => service.updateIdentity(USER, { avatarPreset: 'dragon' }),
    (e: unknown) => e instanceof ApiError && e.status === 400)
  await assert.rejects(() => service.updateIdentity(USER, { avatarPreset: 'fox', avatar: JPEG_BASE64 }),
    (e: unknown) => e instanceof ApiError && e.code === 'AVATAR_CONFLICT')
})

test('exportAccount includes identity fields', async () => {
  const fake = createFakeAccountDb()
  seed(fake.state)
  Object.assign(fake.state.user[0], { nickname: '小明', signature: '签名', gender: 'other', birthday: '2003-05-01', avatarPreset: 'book', avatarData: null })
  const { service } = serviceWith(fake)
  const dump = await service.exportAccount(USER)
  assert.equal(dump.account.nickname, '小明')
  assert.equal(dump.account.signature, '签名')
  assert.equal(dump.account.avatarPreset, 'book')
})
```

在测试文件顶部常量区加：`const JPEG_BASE64 = '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AVN//2Q=='`

- [ ] **Step 2: 运行确认失败**

Run: `cd server && node --import tsx --test src/modules/account/service.test.ts`
Expected: FAIL（service.getIdentity is not a function）

- [ ] **Step 3: 改 AccountDb.user 接口 + AccountExport.account + 实现**

`AccountDb.user` 改为：
```typescript
  user: { findUnique(args: AnyArgs): Promise<any>; updateMany(args: AnyArgs): Promise<any>; deleteMany(args: AnyArgs): Promise<any> }
```

`AccountExport.account` 追加字段：
```typescript
    nickname: string | null
    signature: string | null
    gender: string | null
    birthday: string | null
    avatarPreset: string | null
    avatar: string | null
```

import 区加：
```typescript
import { normalizeIdentityPatch, type IdentityInput } from './identity'
```

新增 Identity 类型（放 AccountExport 附近）：
```typescript
export interface Identity {
  guid: string
  username: string
  email: string | null
  nickname: string | null
  signature: string | null
  gender: string | null
  birthday: string | null
  avatarPreset: string | null
  avatar: string | null
}

const toIdentity = (u: any): Identity => ({
  guid: u.guid,
  username: u.username,
  email: u.email ?? null,
  nickname: u.nickname ?? null,
  signature: u.signature ?? null,
  gender: u.gender ?? null,
  birthday: u.birthday ?? null,
  avatarPreset: u.avatarPreset ?? null,
  avatar: u.avatarData ?? null,
})
```

`exportAccount` 的 account 段追加：
```typescript
        nickname: user.nickname ?? null,
        signature: user.signature ?? null,
        gender: user.gender ?? null,
        birthday: user.birthday ?? null,
        avatarPreset: user.avatarPreset ?? null,
        avatar: user.avatarData ?? null,
```

在 `createAccountService` 内（requireUser 之后）加：
```typescript
  async function getIdentity(userGuid: string): Promise<Identity> {
    return toIdentity(await requireUser(userGuid))
  }

  async function updateIdentity(userGuid: string, input: IdentityInput): Promise<Identity> {
    await requireUser(userGuid)
    const today = new Date().toISOString().slice(0, 10)
    const patch = normalizeIdentityPatch(input, today)
    if (Object.keys(patch).length > 0) {
      await db.user.updateMany({ where: { guid: userGuid }, data: patch })
    }
    return toIdentity(await requireUser(userGuid))
  }
```

return 改为：`return { exportAccount, deleteAccount, getIdentity, updateIdentity }`

- [ ] **Step 4: 运行确认通过**

Run: `cd server && node --import tsx --test src/modules/account/service.test.ts`
Expected: PASS（含原有导出/注销测试）

- [ ] **Step 5: Commit（若获授权）**

```bash
git add server/src/modules/account/service.ts server/src/modules/account/service.test.ts
git commit -m "feat(account): read/update identity and include it in account export"
```

---

### Task 3: me 路由 + PublicUser 轻量字段

**Files:**
- Modify: `server/src/modules/account/routes.ts`
- Modify: `server/src/modules/auth/service.ts:22-34`

**Interfaces:**
- Consumes: `accountService.getIdentity/updateIdentity`（Task 2）。
- Produces: `GET/PATCH /api/v1/account/me` → `{ identity, serverTime }`；`PublicUser.nickname/avatarPreset`。

- [ ] **Step 1: routes.ts 加 me 路由**

import 区已有 z/requireAuth/apiLimit/accountService。在 export 路由前加：

```typescript
const identitySchema = z.object({
  nickname: z.string().nullish(),
  signature: z.string().nullish(),
  gender: z.string().nullish(),
  birthday: z.string().nullish(),
  avatarPreset: z.string().nullish(),
  avatar: z.string().nullish(),
})

router.get('/me', requireAuth, apiLimit(), async ctx => {
  const identity = await accountService.getIdentity(ctx.state.auth!.userGuid)
  ctx.body = { identity, serverTime: Date.now() }
})

router.patch('/me', requireAuth, apiLimit(), async ctx => {
  const body = identitySchema.parse(ctx.request.body)
  const identity = await accountService.updateIdentity(ctx.state.auth!.userGuid, body)
  ctx.body = { identity, serverTime: Date.now() }
})
```

- [ ] **Step 2: auth/service.ts PublicUser 加字段**

`PublicUser` 接口加 `nickname: string | null`、`avatarPreset: string | null`。
`publicUser` 函数参数类型加 `nickname: string | null; avatarPreset: string | null`，返回对象加 `nickname: u.nickname ?? null, avatarPreset: u.avatarPreset ?? null`。

- [ ] **Step 3: typecheck + 全量测试 + build**

Run: `cd server && npm run verify`
Expected: typecheck 干净、全部测试 PASS、build 成功

- [ ] **Step 4: Commit（若获授权）**

```bash
git add server/src/modules/account/routes.ts server/src/modules/auth/service.ts
git commit -m "feat(account): expose GET/PATCH /account/me and lightweight identity in auth responses"
```

---

### Task 4: 客户端 DTO + API 方法

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt`

**Interfaces:**
- Produces: `UserDto.nickname/avatarPreset`；`IdentityDto`；`AccountMeResp`；`UpdateIdentityReq`；`YanzhongApi.getAccountMe()/updateAccountMe()`。

- [ ] **Step 1: UserDto 加字段（Api.kt:30）**

```kotlin
@Serializable
data class UserDto(
    val guid: String, val username: String, val email: String? = null, val createdAt: Long = 0,
    val nickname: String? = null, val avatarPreset: String? = null
)
```

- [ ] **Step 2: 新增 DTO（放 ProfileResp 附近）**

```kotlin
@Serializable
data class IdentityDto(
    val guid: String = "", val username: String = "", val email: String? = null,
    val nickname: String? = null, val signature: String? = null, val gender: String? = null,
    val birthday: String? = null, val avatarPreset: String? = null, val avatar: String? = null
)

@Serializable
data class AccountMeResp(val identity: IdentityDto = IdentityDto(), val serverTime: Long = 0)

@Serializable
data class UpdateIdentityReq(
    val nickname: String? = null, val signature: String? = null, val gender: String? = null,
    val birthday: String? = null, val avatarPreset: String? = null, val avatar: String? = null
)
```

- [ ] **Step 3: YanzhongApi 加方法（account/export 附近）**

```kotlin
    @GET("api/v1/account/me")
    suspend fun getAccountMe(): AccountMeResp

    @PATCH("api/v1/account/me")
    suspend fun updateAccountMe(@Body body: UpdateIdentityReq): AccountMeResp
```

确认文件已 import `retrofit2.http.PATCH`（若无则加）。

- [ ] **Step 4: 编译验证（与后续任务合并到 Task 12 构建）**

本任务无独立单测（DTO 序列化由编译保证）；随 Task 12 一起 assembleDebug。

- [ ] **Step 5: Commit（若获授权）**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Api.kt
git commit -m "feat(profile): add identity DTOs and account/me API methods"
```

---

### Task 5: TokenStore 身份缓存 + refreshIdentity

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/TokenStore.kt`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Identity.kt`

**Interfaces:**
- Consumes: `ApiClient.api().getAccountMe()`（Task 4）。
- Produces: `TokenStore.IdentityCache`；`observeIdentity()/currentIdentity()/saveIdentity()`；`suspend fun refreshIdentity(): TokenStore.IdentityCache?`。

- [ ] **Step 1: TokenStore 加键与 IdentityCache**

键区加：
```kotlin
    private val KEY_NICKNAME = stringPreferencesKey("nickname")
    private val KEY_SIGNATURE = stringPreferencesKey("signature")
    private val KEY_GENDER = stringPreferencesKey("gender")
    private val KEY_BIRTHDAY = stringPreferencesKey("birthday")
    private val KEY_AVATAR_PRESET = stringPreferencesKey("avatar_preset")
    private val KEY_AVATAR_DATA = stringPreferencesKey("avatar_data")
```

object 内加：
```kotlin
    data class IdentityCache(
        val nickname: String? = null, val signature: String? = null, val gender: String? = null,
        val birthday: String? = null, val avatarPreset: String? = null, val avatar: String? = null
    )

    fun observeIdentity(): Flow<IdentityCache> = context().cloudStore.data.map {
        IdentityCache(it[KEY_NICKNAME], it[KEY_SIGNATURE], it[KEY_GENDER], it[KEY_BIRTHDAY], it[KEY_AVATAR_PRESET], it[KEY_AVATAR_DATA])
    }

    suspend fun currentIdentity(): IdentityCache = context().cloudStore.data.first().let {
        IdentityCache(it[KEY_NICKNAME], it[KEY_SIGNATURE], it[KEY_GENDER], it[KEY_BIRTHDAY], it[KEY_AVATAR_PRESET], it[KEY_AVATAR_DATA])
    }

    suspend fun saveIdentity(cache: IdentityCache) {
        context().cloudStore.edit { prefs ->
            fun put(key: androidx.datastore.preferences.core.Preferences.Key<String>, v: String?) {
                if (v.isNullOrBlank()) prefs.remove(key) else prefs[key] = v
            }
            put(KEY_NICKNAME, cache.nickname); put(KEY_SIGNATURE, cache.signature)
            put(KEY_GENDER, cache.gender); put(KEY_BIRTHDAY, cache.birthday)
            put(KEY_AVATAR_PRESET, cache.avatarPreset); put(KEY_AVATAR_DATA, cache.avatar)
        }
    }
```

`clearAll()` 追加移除这 6 键。

- [ ] **Step 2: Identity.kt**

```kotlin
package com.yanzhong.app.data.remote

/** 拉服务端身份并写本地缓存;失败静默返回 null,调用方回落已有缓存。 */
suspend fun refreshIdentity(): TokenStore.IdentityCache? = runCatching {
    val resp = ApiClient.api().getAccountMe()
    val id = resp.identity
    val cache = TokenStore.IdentityCache(
        nickname = id.nickname, signature = id.signature, gender = id.gender,
        birthday = id.birthday, avatarPreset = id.avatarPreset, avatar = id.avatar
    )
    TokenStore.saveIdentity(cache)
    cache
}.getOrNull()
```

- [ ] **Step 3: 编译验证（随 Task 12）**

- [ ] **Step 4: Commit（若获授权）**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/data/remote/TokenStore.kt YanZhong/app/src/main/java/com/yanzhong/app/data/remote/Identity.kt
git commit -m "feat(profile): cache identity locally and refresh from account/me"
```

---

### Task 6: 共享图片压缩工具 + onboarding 委托

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/data/util/ImageCompression.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt:978-1001`

**Interfaces:**
- Produces: `internal fun compressImageToBase64(context: Context, uri: Uri, maxSide: Int, quality: Int): String?`。

- [ ] **Step 1: ImageCompression.kt**

```kotlin
package com.yanzhong.app.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** 相册图 → 缩放 → JPEG → 裸 base64;失败返回 null。课表用 1600/82,头像用 512/85。 */
internal fun compressImageToBase64(context: Context, uri: Uri, maxSide: Int, quality: Int): String? {
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
    val longest = maxOf(decoded.width, decoded.height)
    val scaled = if (longest <= maxSide) decoded else {
        val ratio = maxSide.toFloat() / longest
        Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1), (decoded.height * ratio).toInt().coerceAtLeast(1), true)
    }
    val out = ByteArrayOutputStream()
    val ok = scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()
    if (!ok) return null
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}
```

- [ ] **Step 2: OnboardingViewModel 委托**

把 `compressToBase64(uri)` 体替换为：
```kotlin
    private fun compressToBase64(uri: Uri): String? =
        compressImageToBase64(getApplication(), uri, TIMETABLE_MAX_SIDE, 82)
```
删除原 `scaleDown` 私有函数（已并入工具）；import `com.yanzhong.app.data.util.compressImageToBase64`。

- [ ] **Step 3: 编译验证（随 Task 12）+ 现有 onboarding 测试仍绿**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*Onboarding*"`（PowerShell）
Expected: PASS

- [ ] **Step 4: Commit（若获授权）**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/data/util/ImageCompression.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/onboarding/OnboardingViewModel.kt
git commit -m "refactor(onboarding): extract shared image compression util"
```

---

### Task 7: 预设头像 + UserAvatar 组件

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/profile/PresetAvatars.kt`
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/profile/UserAvatar.kt`
- Test: `YanZhong/app/src/test/java/com/yanzhong/app/ui/profile/PresetAvatarsTest.kt`

**Interfaces:**
- Produces: `PresetAvatar(key,emoji,label,colors)`；`PRESET_AVATARS`；`presetOf(key): PresetAvatar?`；`@Composable UserAvatar(avatarPreset, avatarBase64, size, modifier)`。

- [ ] **Step 1: 写失败测试 PresetAvatarsTest.kt**

```kotlin
package com.yanzhong.app.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PresetAvatarsTest {
    @Test
    fun `预设 key 与服务端白名单一致且唯一`() {
        assertEquals(
            listOf("fox", "panda", "owl", "rocket", "book", "moon", "star", "clover", "cat", "target"),
            PRESET_AVATARS.map { it.key }
        )
        assertEquals(10, PRESET_AVATARS.map { it.key }.toSet().size)
    }

    @Test
    fun `presetOf 命中与回落`() {
        assertNotNull(presetOf("fox"))
        assertNull(presetOf("dragon"))
        assertNull(presetOf(null))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*PresetAvatarsTest"`（PowerShell）
Expected: FAIL（unresolved reference PRESET_AVATARS）

- [ ] **Step 3: PresetAvatars.kt**

```kotlin
package com.yanzhong.app.ui.profile

import androidx.compose.ui.graphics.Color

data class PresetAvatar(val key: String, val emoji: String, val label: String, val colors: List<Color>)

val PRESET_AVATARS: List<PresetAvatar> = listOf(
    PresetAvatar("fox", "🦊", "灵动", listOf(Color(0xFFF97316), Color(0xFFEA580C))),
    PresetAvatar("panda", "🐼", "沉稳", listOf(Color(0xFF64748B), Color(0xFF334155))),
    PresetAvatar("owl", "🦉", "夜读", listOf(Color(0xFF8B5CF6), Color(0xFF6D28D9))),
    PresetAvatar("rocket", "🚀", "冲刺", listOf(Color(0xFFEF4444), Color(0xFFB91C1C))),
    PresetAvatar("book", "📚", "苦读", listOf(Color(0xFF5865F2), Color(0xFF4338CA))),
    PresetAvatar("moon", "🌙", "晚自习", listOf(Color(0xFF6366F1), Color(0xFF3730A3))),
    PresetAvatar("star", "⭐", "上岸", listOf(Color(0xFFF59E0B), Color(0xFFD97706))),
    PresetAvatar("clover", "🍀", "好运", listOf(Color(0xFF22C55E), Color(0xFF15803D))),
    PresetAvatar("cat", "🐱", "陪伴", listOf(Color(0xFFEC4899), Color(0xFFBE185D))),
    PresetAvatar("target", "🎯", "目标", listOf(Color(0xFF14B8A6), Color(0xFF0F766E))),
)

fun presetOf(key: String?): PresetAvatar? = PRESET_AVATARS.firstOrNull { it.key == key }
```

- [ ] **Step 4: UserAvatar.kt**

```kotlin
package com.yanzhong.app.ui.profile

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanzhong.app.ui.theme.AppIcons

/** 头像:自定义 base64 优先,其次预设 emoji 渐变圆,兜底学士帽。 */
@Composable
fun UserAvatar(avatarPreset: String?, avatarBase64: String?, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(avatarBase64) {
        if (avatarBase64.isNullOrBlank()) null else runCatching {
            val bytes = Base64.decode(avatarBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    val preset = presetOf(avatarPreset)
    Box(
        modifier = modifier.size(size).clip(CircleShape)
            .background(if (preset != null) Brush.linearGradient(preset.colors) else Brush.linearGradient(listOf(Color(0xFF4338CA), Color(0xFF6D28D9)))),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(bitmap = bitmap, contentDescription = "头像", contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
            preset != null -> Text(preset.emoji, fontSize = (size.value * 0.5f).sp)
            else -> Icon(AppIcons.GraduationCap, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*PresetAvatarsTest"`（PowerShell）
Expected: PASS

- [ ] **Step 6: Commit（若获授权）**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/profile/PresetAvatars.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/profile/UserAvatar.kt YanZhong/app/src/test/java/com/yanzhong/app/ui/profile/PresetAvatarsTest.kt
git commit -m "feat(profile): preset avatars and reusable UserAvatar composable"
```

---

### Task 8: 客户端校验纯函数（TDD）

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/profile/ProfileValidation.kt`
- Test: `YanZhong/app/src/test/java/com/yanzhong/app/ui/profile/ProfileValidationTest.kt`

**Interfaces:**
- Produces: `validateNickname/validateSignature/validateGender/validateBirthday/avatarConflict`（返回 String? 错误，null=通过）；`codePointLength(s)`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.yanzhong.app.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileValidationTest {
    @Test fun `昵称空通过超32码点拒绝`() {
        assertNull(validateNickname(null))
        assertNull(validateNickname("小明"))
        assertNotNull(validateNickname("字".repeat(33)))
    }
    @Test fun `签名超100码点拒绝`() {
        assertNull(validateSignature("一战成硕"))
        assertNotNull(validateSignature("字".repeat(101)))
    }
    @Test fun `性别枚举校验`() {
        assertNull(validateGender(null)); assertNull(validateGender("secret"))
        assertNotNull(validateGender("unknown"))
    }
    @Test fun `生日格式真实范围校验`() {
        assertNull(validateBirthday(null, "2026-11-09"))
        assertNull(validateBirthday("2003-05-01", "2026-11-09"))
        assertNotNull(validateBirthday("2026-02-30", "2026-11-09"))
        assertNotNull(validateBirthday("2027-01-01", "2026-11-09"))
        assertNotNull(validateBirthday("1899-12-31", "2026-11-09"))
    }
    @Test fun `预设与自定义互斥`() {
        assertNull(avatarConflict("fox", null))
        assertNull(avatarConflict(null, "base64"))
        assertNotNull(avatarConflict("fox", "base64"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*ProfileValidationTest"`
Expected: FAIL（unresolved reference）

- [ ] **Step 3: ProfileValidation.kt**

```kotlin
package com.yanzhong.app.ui.profile

internal fun codePointLength(s: String): Int = s.codePointCount(0, s.length)

internal fun validateNickname(s: String?): String? =
    if (s != null && codePointLength(s.trim()) > 32) "昵称最多 32 个字符" else null

internal fun validateSignature(s: String?): String? =
    if (s != null && codePointLength(s.trim()) > 100) "签名最多 100 个字符" else null

internal fun validateGender(s: String?): String? =
    if (s != null && s !in listOf("male", "female", "other", "secret")) "性别取值非法" else null

internal fun validateBirthday(s: String?, today: String): String? {
    if (s.isNullOrBlank()) return null
    if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) return "生日格式应为 YYYY-MM-DD"
    val y = s.substring(0, 4).toInt()
    if (y < 1900 || s > today) return "生日年份或范围非法"
    return runCatching {
        val d = java.time.LocalDate.parse(s)
        if (d.toString() != s) "生日不是真实日期" else null
    }.getOrElse { "生日不是真实日期" }
}

internal fun avatarConflict(preset: String?, custom: String?): String? =
    if (!preset.isNullOrBlank() && !custom.isNullOrBlank()) "预设头像和自定义头像只能选一个" else null
```

- [ ] **Step 4: 运行确认通过**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*ProfileValidationTest"`
Expected: PASS

- [ ] **Step 5: Commit（若获授权）**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/profile/ProfileValidation.kt YanZhong/app/src/test/java/com/yanzhong/app/ui/profile/ProfileValidationTest.kt
git commit -m "feat(profile): client-side identity validation mirroring server rules"
```

---

### Task 9: EditProfileViewModel

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/profile/EditProfileViewModel.kt`

**Interfaces:**
- Consumes: `refreshIdentity`、`TokenStore`、`ApiClient.api().updateAccountMe`、`UpdateIdentityReq`、`compressImageToBase64`、校验函数、`presetOf`。
- Produces: `EditProfileUiState`；`EditProfileViewModel`（pickPreset/uploadAvatar/save/各字段 setter）。

- [ ] **Step 1: 实现 ViewModel**

```kotlin
package com.yanzhong.app.ui.profile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.TokenStore
import com.yanzhong.app.data.remote.UpdateIdentityReq
import com.yanzhong.app.data.remote.apiErrorMessage
import com.yanzhong.app.data.remote.refreshIdentity
import com.yanzhong.app.data.util.compressImageToBase64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class EditProfileUiState(
    val nickname: String = "", val signature: String = "", val gender: String? = null,
    val birthday: String = "", val selectedPreset: String? = null, val customAvatar: String? = null,
    val loading: Boolean = true, val saving: Boolean = false, val error: String = "", val saved: Boolean = false
)

class EditProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(EditProfileUiState())
    val ui: StateFlow<EditProfileUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val cached = TokenStore.currentIdentity()
            _ui.update { it.copy(nickname = cached.nickname.orEmpty(), signature = cached.signature.orEmpty(), gender = cached.gender, birthday = cached.birthday.orEmpty(), selectedPreset = cached.avatarPreset, customAvatar = cached.avatar, loading = true) }
            val fresh = refreshIdentity()
            if (fresh != null) {
                _ui.update { it.copy(nickname = fresh.nickname.orEmpty(), signature = fresh.signature.orEmpty(), gender = fresh.gender, birthday = fresh.birthday.orEmpty(), selectedPreset = fresh.avatarPreset, customAvatar = fresh.avatar, loading = false) }
            } else {
                _ui.update { it.copy(loading = false) }
            }
        }
    }

    fun setNickname(v: String) { _ui.update { it.copy(nickname = v, error = "") } }
    fun setSignature(v: String) { _ui.update { it.copy(signature = v, error = "") } }
    fun setGender(v: String?) { _ui.update { it.copy(gender = v, error = "") } }
    fun setBirthday(v: String) { _ui.update { it.copy(birthday = v, error = "") } }
    fun pickPreset(key: String) { _ui.update { it.copy(selectedPreset = key, customAvatar = null, error = "") } }
    fun clearAvatar() { _ui.update { it.copy(selectedPreset = null, customAvatar = null, error = "") } }

    fun uploadAvatar(uri: Uri) {
        val base64 = compressImageToBase64(getApplication(), uri, 512, 85)
        if (base64 == null) { _ui.update { it.copy(error = "这张图读不出来,换一张试试") } }
        else _ui.update { it.copy(customAvatar = base64, selectedPreset = null, error = "") }
    }

    fun save() {
        val s = _ui.value
        val today = LocalDate.now().toString()
        val err = validateNickname(s.nickname) ?: validateSignature(s.signature)
            ?: validateGender(s.gender) ?: validateBirthday(s.birthday, today)
            ?: avatarConflict(s.selectedPreset, s.customAvatar)
        if (err != null) { _ui.update { it.copy(error = err) }; return }
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = "") }
            val result = runCatching {
                ApiClient.api().updateAccountMe(
                    UpdateIdentityReq(
                        nickname = s.nickname.trim().ifBlank { null },
                        signature = s.signature.trim().ifBlank { null },
                        gender = s.gender,
                        birthday = s.birthday.ifBlank { null },
                        avatarPreset = s.selectedPreset,
                        avatar = s.customAvatar
                    )
                )
            }
            result.fold({ resp ->
                val id = resp.identity
                TokenStore.saveIdentity(TokenStore.IdentityCache(id.nickname, id.signature, id.gender, id.birthday, id.avatarPreset, id.avatar))
                _ui.update { it.copy(saving = false, saved = true) }
            }, { e ->
                _ui.update { it.copy(saving = false, error = "保存失败:${e.apiErrorMessage()}") }
            })
        }
    }
}
```

确认 `apiErrorMessage` 是现有扩展（`data/remote/ApiError.kt`）；若签名不符按现有调整。

- [ ] **Step 2: 编译验证（随 Task 12）**

ViewModel 网络部分无单测缝（沿用约定）；校验逻辑已由 Task 8 覆盖。

- [ ] **Step 3: Commit（若获授权）**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/profile/EditProfileViewModel.kt
git commit -m "feat(profile): edit-profile view model with load/upload/save"
```

---

### Task 10: EditProfileScreen + 路由 + 入口

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/profile/EditProfileScreen.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt:145-152`（HeaderCard 入口）
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/account/AccountScreen.kt:212-248`（编辑资料入口）

**Interfaces:**
- Consumes: `EditProfileViewModel`、`UserAvatar`、`PRESET_AVATARS`、共享组件、`Routes.EDIT_PROFILE`。
- Produces: `EditProfileScreen(padding, navController)`；`Routes.EDIT_PROFILE = "edit_profile"`。

- [ ] **Step 1: AppNav 路由**

`Routes` 加 `const val EDIT_PROFILE = "edit_profile"`。
`deepPage` 判定加 `|| currentRoute == Routes.EDIT_PROFILE`。
NavHost 加：
```kotlin
                    composable(Routes.EDIT_PROFILE) {
                        com.yanzhong.app.ui.profile.EditProfileScreen(padding, navController)
                    }
```

- [ ] **Step 2: EditProfileScreen.kt**

```kotlin
package com.yanzhong.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.ui.theme.*
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(padding: PaddingValues, navController: NavHostController) {
    val vm: EditProfileViewModel = viewModel()
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.uploadAvatar(uri)
    }
    LaunchedEffect(state.saved) { if (state.saved) navController.popBackStack() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .padding(bottom = padding.calculateBottomPadding())
            .verticalScroll(rememberScrollState())
            .widthIn(max = CONTENT_MAX_WIDTH).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("编辑资料", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
        UserAvatar(state.selectedPreset, state.customAvatar, 96.dp)
        TextAction(text = "更换头像", onClick = { showAvatarPicker = true })
        OutlinedTextField(value = state.nickname, onValueChange = vm::setNickname, label = { Text("昵称") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("${codePointLength(state.nickname)}/32") })
        OutlinedTextField(value = state.signature, onValueChange = vm::setSignature, label = { Text("个性签名") },
            minLines = 2, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("${codePointLength(state.signature)}/100") })
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("性别", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("secret" to "保密", "male" to "男", "female" to "女", "other" to "其他").forEach { (key, label) ->
                    FilterChip(selected = state.gender == key, onClick = { vm.setGender(key) }, label = { Text(label) })
                }
            }
        }
        OutlinedTextField(value = state.birthday, onValueChange = {}, readOnly = true,
            label = { Text("生日") }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("点击选择") },
            trailingIcon = { TextAction(text = if (state.birthday.isBlank()) "选择" else "清除", onClick = { if (state.birthday.isBlank()) showDatePicker = true else vm.setBirthday("") }) },
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }.also {
                LaunchedEffect(it) { it.interactions.collect { _ -> showDatePicker = true } }
            })
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        PrimaryButton(text = "保存", onClick = vm::save, loading = state.saving, enabled = !state.loading, modifier = Modifier.fillMaxWidth())
    }

    if (showAvatarPicker) {
        AlertDialog(onDismissRequest = { showAvatarPicker = false }, confirmButton = {},
            title = { Text("选择头像") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PRESET_AVATARS.forEach { p ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
                                Box(Modifier.clickable { vm.pickPreset(p.key); showAvatarPicker = false }) {
                                    UserAvatar(p.key, null, 52.dp)
                                }
                                Text(p.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    SecondaryButton(text = "从相册上传", onClick = { picker.launch("image/*"); showAvatarPicker = false }, modifier = Modifier.fillMaxWidth())
                    TextAction(text = "移除头像", onClick = { vm.clearAvatar(); showAvatarPicker = false })
                }
            })
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = {
            TextAction(text = "确定", onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                    vm.setBirthday(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString())
                }
                showDatePicker = false
            })
        }, dismissButton = { TextAction(text = "取消", onClick = { showDatePicker = false }) }) {
            DatePicker(state = pickerState)
        }
    }
}
```

> 实现注：生日只读框的点击触发若 `interactionSource` 写法不稳，改用 `Box(Modifier.clickable{showDatePicker=true})` 包一个显示文本 + 「选择/清除」TextAction 的行（更简单可靠）。FlowRow 需 `androidx.compose.foundation.layout.FlowRow`（ExperimentalLayoutApi，加 `@OptIn`）。`clickable` import `androidx.compose.foundation.clickable`。`CONTENT_MAX_WIDTH` 来自 theme（MineScreen 已用）。编译时按实际 import 补齐。

- [ ] **Step 3: Mine HeaderCard 入口**

MineScreen.kt:146-151 `HeaderCard(state, onOpenAccount = {...})`：把 onOpenAccount 改为按登录态分流——登录态 navigate `Routes.EDIT_PROFILE`，否则 `Routes.ACCOUNT`。需要 state.account 判定（HeaderCard 调用处可访问 state.account）。

- [ ] **Step 4: AccountScreen 编辑资料入口**

账号卡（AccountScreen.kt:212-248 已登录 AppCard）内加 `TextAction(text = "编辑资料", onClick = { navController.navigate(Routes.EDIT_PROFILE) { launchSingleTop = true } })`。

- [ ] **Step 5: 编译验证（随 Task 12）**

- [ ] **Step 6: Commit（若获授权）**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/profile/EditProfileScreen.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/account/AccountScreen.kt
git commit -m "feat(profile): edit-profile screen, route and entry points"
```

---

### Task 11: Mine 头部 + 账号卡展示昵称头像

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineViewModel.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt:503-548`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/account/AccountScreen.kt:212-241`

**Interfaces:**
- Consumes: `TokenStore.observeIdentity/currentIdentity`、`refreshIdentity`、`UserAvatar`。
- Produces: `MineUiState.identity: TokenStore.IdentityCache`；头部/账号卡显示昵称+头像。

- [ ] **Step 1: MineViewModel 加 identity**

`MineUiState` 加 `val identity: TokenStore.IdentityCache = TokenStore.IdentityCache()`。
`accountFlow` 之外组合 `TokenStore.observeIdentity()`：在 `combine` 加入 identity 流并写进 MineUiState。
加 init/launch：当 `TokenStore.observeLoggedIn()` 为 true 时 `refreshIdentity()`（用 viewModelScope.launch，收集 loggedIn distinctUntilChanged，true 时拉一次）。import `com.yanzhong.app.data.remote.refreshIdentity`、`TokenStore`。

- [ ] **Step 2: MineScreen HeaderCard 用 UserAvatar + 昵称**

HeaderCard（MineScreen.kt:503-548）：username 显示改为 `state.identity.nickname?.takeIf{it.isNotBlank()} ?: username`；把 519-532 的写死图标 Box 替换为 `UserAvatar(state.identity.avatarPreset, state.identity.avatar, 52.dp)`。

- [ ] **Step 3: AccountScreen 账号卡用 UserAvatar + 昵称**

AccountScreen 已登录卡（212-241）：图标 Box 换 `UserAvatar(...)`，需要 identity——AccountScreen 已持有 mineVm（MineViewModel），读 `mineVm.uiState.collectAsStateWithLifecycle().value.identity`；昵称显示 `identity.nickname ?: state.username`。

- [ ] **Step 4: 编译验证（随 Task 12）**

- [ ] **Step 5: Commit（若获授权）**

```bash
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineViewModel.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/account/AccountScreen.kt
git commit -m "feat(profile): show nickname and avatar on mine header and account card"
```

---

### Task 12: 全量构建 + 单测 + 模拟器走查

**Files:** 无新增（验证）

- [ ] **Step 1: 服务端全量验证**

Run: `cd server && npm run verify`
Expected: typecheck 干净、全部测试 PASS、build 成功

- [ ] **Step 2: 客户端单测 + 构建（PowerShell）**

Run: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug -Dorg.gradle.jvmargs=-Xmx1536m`
Expected: 单测 PASS、assembleDebug 成功

- [ ] **Step 3: 安装到模拟器并走查**

安装 debug APK；强制竖屏；走查：
1. 登录测试账号 → 我的头部卡显示头像+昵称（初次回落用户名+图标）。
2. 点头部卡进编辑资料 → 改昵称/签名/性别/生日 → 保存 → 返回后头部即时更新。
3. 更换头像 → 选预设 → 保存 → 头部显示预设；再相册上传一张 → 保存 → 头部显示照片。
4. 账号卡同样显示昵称头像 + 「编辑资料」入口可达。
5. 退出登录 → 缓存清空，头部回落「本机学习档案」+图标。
每步截图核对（截图为准，dump bounds 辅助）。

- [ ] **Step 4: 修复走查发现的问题（若有），重跑构建**

- [ ] **Step 5: 汇报；部署/OTA 待负责人单独批准**

服务端 bundle 部署与客户端 OTA(m7) 不在本计划自动执行；完成后向负责人汇报并请求单独批准。

---

## Self-Review

**Spec coverage：**
- §4 数据模型 → Task 1（schema）+ Task 3（PublicUser）。✓
- §5.1 GET me → Task 2/3。✓ §5.2 PATCH 校验/互斥 → Task 1/2/3。✓ §5.3 导出/注销 → Task 2（导出含身份；注销已删 User）。✓
- §6.1 DTO → Task 4。✓ §6.2 缓存/refresh → Task 5。✓ §6.3 压缩工具 → Task 6。✓ §6.4 预设/UserAvatar → Task 7。✓ §6.5 编辑页/路由/入口 → Task 9/10。✓ §6.6 校验 → Task 8。✓
- §7 预设白名单 → Task 1（服务端）/Task 7（客户端，测试断言一致）。✓
- §8 测试 → Task 1/2（服务端）/Task 7/8（客户端）/Task 12（构建走查）。✓
- §9 上线 → Task 12 Step 5（待批准）。✓ §10 范围边界 → 全局约束（不引 Coil/不做裁剪/不改 profile）。✓

**Placeholder scan：** 无 TBD/TODO；UI 布线步骤给了具体代码与 import 提示。Task 10 生日框给了可靠回退方案。✓

**Type consistency：** `IdentityInput/IdentityFields/normalizeIdentityPatch`（Task1）→ Task2 消费一致；`Identity/getIdentity/updateIdentity`（Task2）→ Task3 路由消费；客户端 `IdentityDto/AccountMeResp/UpdateIdentityReq`（Task4）→ Task5/9 消费；`IdentityCache`（Task5）→ Task9/11 消费；`compressImageToBase64`（Task6）→ Task9 消费；`PRESET_AVATARS/presetOf/UserAvatar`（Task7）→ Task10/11 消费；校验函数（Task8）→ Task9 消费。✓