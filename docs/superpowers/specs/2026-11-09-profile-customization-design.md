# 个人资料自定义（头像 / 昵称 / 签名 / 性别 / 生日）设计文档

日期：2026-11-09
状态：方案已与产品负责人确认；spec 由实现者自审后直接进入实现（负责人豁免人工审阅）
实现计划：`docs/superpowers/plans/2026-11-09-profile-customization.md`

---

## 1. 问题与目标

「我的」页头部卡与账号卡目前只显示一个写死的学士帽图标 + 登录用户名，没有任何个性化身份。
用户原话：「个人界面可以自定义头像昵称等等，你看着优化，旨在提升用户体验感」。

目标：让备考者拥有可自定义的个人身份——头像（预设 + 相册上传）、昵称、个性签名、性别、生日，
并在「我的」头部卡与账号卡一致地展示。昵称空时回落用户名，头像空时回落学士帽图标，保证老账号无感。

## 2. 现状（附证据）

- 头部卡写死图标 + 用户名：`YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt:519-532`（52dp 圆 + `AppIcons.GraduationCap`），用户名取自 `AccountState.LoggedIn(username)`。
- 账号卡同样写死：`YanZhong/app/src/main/java/com/yanzhong/app/ui/account/AccountScreen.kt:212-241`。
- 身份模型只有登录字段：客户端 `UserDto(guid, username, email, createdAt)`（`data/remote/Api.kt:30`）；服务端 `User` 表无昵称/头像列（`server/prisma/schema.prisma:17-35`）；`PublicUser` 只序列化 guid/username/email/createdAt（`server/src/modules/auth/service.ts:22-34`）。
- `/api/v1/profile` 是**备考档案**（targetType/examDate/课表），不是身份；不能往那里塞昵称头像（`server/src/modules/planning/routes.ts`、`schemas.ts profileInputSchema`）。
- 已有可复用的选图→压缩→base64 链路：`OnboardingViewModel.kt:978 compressToBase64(uri)`（decode→scaleDown→JPEG 82→Base64.NO_WRAP），课表最长边 1600；选择器 `OnboardingScreen.kt:599 GetContent()` + `picker.launch("image/*")`。服务端 JSON body 上限 8mb（`server/src/app.ts:29`）。
- 无 Coil/Glide（`gradle/libs.versions.toml`、`app/build.gradle.kts:82-113`）；Bitmap→Image 现有范式 `ui/supermode/AppPickerScreen.kt:332-349`（`asImageBitmap()` + `Image(bitmap=…)`）。base64→Bitmap 需自行 `BitmapFactory.decodeByteArray(Base64.decode(...))`。
- 共享组件均在 `ui/theme/`：`Components.kt`（AppCard:106 / GradientCard:134 / ListRow:170 / SectionHeader:293 / RowGroup:151 / RowDivider:161 / StatusPill:436 / GradientTag:463）、`Controls.kt`（PrimaryButton:309 / SecondaryButton:341 / TextAction:369）；tokens `Color.kt BrandGradient:29-31`；`AppIcons.kt:85`。
- 导航：`ui/nav/AppNav.kt` `object Routes:64`、`NavHost:146`、`composable(Routes.ACCOUNT):191-193`、底部导航隐藏判定 `deepPage:126-129`。
- 本地凭证缓存：`data/remote/TokenStore.kt` DataStore "cloud_sync"，已存 username（key:28，observeUsername:74，saveUsername:82，clearAll:139 会清 username）。MineViewModel 组合 observeLoggedIn+observeUsername（`ui/mine/MineViewModel.kt:52-57`）；登录存用户名在 `CloudSyncViewModel.kt:111` 与 `LoginViewModel.kt:222`。
- account 模块是「账号级操作」之家，已有假库测试缝：`server/src/modules/account/service.ts`（`AccountDb` 接口:24、`createAccountService(db, deps):83`、exportAccount:91、deleteAccount:118）；路由 `account/routes.ts`（prefix `/api/v1/account`，requireAuth + apiLimit）。注销事务删 User 行（:147），头像/昵称若挂在 User 上随之消失。
- 数据库走 `prisma db push`（无 migrations 目录）；部署 start 脚本 `prisma db push --skip-generate`，新增可空列自动安全应用。
- 客户端测试约定：JUnit4，测 ViewModel 抽出的 `internal` 纯函数（如 `ui/onboarding/OnboardingSubjectsTest.kt`），中文反引号测试名；ApiClient 是单例无注入缝，网络部分不做单测。UI 文案为硬编码中文（strings.xml 仅 app_name + widget）。

## 3. 已确认的决策

| 议题 | 决策 |
|---|---|
| 头像来源 | **预设 + 自定义上传**：内置一组备考主题预设头像可直接选；同时支持相册选图压缩上传。 |
| 资料字段 | **完整资料**：头像、昵称、个性签名、性别、生日。 |
| 头像存储 | **base64 存 DB**（`User.avatarData Text?`），复用 briefJson/课表范式；不建文件存储/静态服务。客户端压到最长边 512，服务端解码后上限 1MB。 |
| 身份字段挂哪 | 挂 `User`（身份表），不挂备考档案 `UserProfile`；填问卷前即可拥有身份。 |
| 预设与自定义关系 | **互斥**：设其一清其二；同请求两者都给非空 → 400。 |
| 编辑入口 | 登录后点「我的」头部卡 → 编辑资料页；账号卡加「编辑资料」动作。未登录点头部卡仍进账号页。 |
| 上线 | schema 全可空新增列，db push 安全；服务端 bundle 部署与客户端 OTA(m7) 各自需负责人单独批准（本 spec 不含部署执行）。 |

## 4. 数据模型（服务端）

`User` 表新增可空列（`server/prisma/schema.prisma`）：

```prisma
nickname     String? @db.VarChar(32)   // 展示昵称,空则客户端回落 username
signature    String? @db.VarChar(100)  // 个性签名 / 备考 motto
gender       String? @db.VarChar(12)   // male / female / other / secret
birthday     String? @db.VarChar(10)   // YYYY-MM-DD,字符串避免时区问题
avatarPreset String? @db.VarChar(32)   // 预设头像 key(白名单)
avatarData   String? @db.Text          // 自定义头像裸 base64(JPEG/PNG/WebP),与 avatarPreset 互斥
```

`PublicUser`（`auth/service.ts`）新增轻量字段 `nickname: string | null`、`avatarPreset: string | null`，
随 login/register/refresh 下发，供头部即时显示昵称与预设头像。**不**把 avatarData base64 塞进认证响应（保持轻量），完整头像由 me 接口给。
`publicUser()` 序列化函数同步加这两列。客户端 `UserDto` 加 `nickname: String? = null`、`avatarPreset: String? = null`（ignoreUnknownKeys 已开，老服务端兼容）。

## 5. 服务端接口（account 模块）

新增两个路由（`account/routes.ts`，requireAuth + apiLimit），服务逻辑放 `account/service.ts`（沿用假库缝）。
`AccountDb.user` 接口补 `update(args)`、`findUnique` 已有。

### 5.1 GET /api/v1/account/me

返回完整身份：

```jsonc
{
  "identity": {
    "guid": "…", "username": "…", "email": null,
    "nickname": null, "signature": null, "gender": null, "birthday": null,
    "avatarPreset": null,
    "avatar": null            // 裸 base64 字符串或 null
  },
  "serverTime": 1794…
}
```

`avatar` 即 `user.avatarData`（裸 base64，无前缀）。账号不存在 → 404 NOT_FOUND（复用 requireUser）。

### 5.2 PATCH /api/v1/account/me

请求体（全部可选；**不传=不动，传 null=清空**）：

```jsonc
{ "nickname": "…", "signature": "…", "gender": "female",
  "birthday": "2003-05-01", "avatarPreset": "fox", "avatar": "<base64>" }
```

响应与 GET me 相同（返回更新后的完整 identity），客户端据此刷新缓存。

**校验规则**（zod schema + 服务层归一）：

| 字段 | 规则 |
|---|---|
| nickname | nullish；字符串 trim，空串→null；非空时按码点 ≤32（`Array.from(s).length`），超限 400 INVALID_PARAMS |
| signature | nullish；trim，空串→null；按码点 ≤100 |
| gender | nullish；非空必须 ∈ {male, female, other, secret}，否则 400 |
| birthday | nullish；空串→null；非空须匹配 `^\d{4}-\d{2}-\d{2}$` 且为真实日期（Date 往返一致）、年份 ≥1900、不晚于今天，否则 400 |
| avatarPreset | nullish；空串→null；非空须 ∈ 预设 key 白名单（§7），否则 400 |
| avatar | nullish；null=清空自定义；非空须为合法图片 base64（可带 `data:image/…;base64,` 前缀，服务端剥前缀），解码后字节数 ≤1MB（1_048_576），且魔数为 JPEG(FF D8 FF)/PNG(89 50 4E 47)/WebP(RIFF…WEBP)，否则 400；归一为裸 base64 存储 |

**互斥与局部更新语义**（服务层，按 key 是否出现判定，`undefined`=未传）：

1. 若请求同时含非空 `avatar` 与非空 `avatarPreset` → 400 `AVATAR_CONFLICT`（「预设头像和自定义头像只能选一个」）。
2. 含 `avatar` 键：非空 → `avatarData=归一base64` 且 `avatarPreset=null`；null → `avatarData=null`。
3. 含 `avatarPreset` 键：非空 → `avatarPreset=key` 且 `avatarData=null`；null → `avatarPreset=null`。
4. nickname/signature/gender/birthday：键出现才写入（null 写 null 清空），未出现保持原值。
5. 一次 `user.update` 落库；返回更新后的完整 identity。

> 客户端编辑页保存的是**整份表单**（六个字段都给值或 null），因此 kotlinx.serialization explicitNulls 全量序列化是安全的；服务端局部语义主要保护其它潜在调用方。自定义头像未变时客户端仍回传 base64（几十 KB，可接受）。

### 5.3 导出与注销

- `exportAccount` 的 `account` 段（`account/service.ts:103-110`）追加 nickname/signature/gender/birthday/avatarPreset/avatar（base64）。导出仍不含 passwordHash/resetTokenHash。
- 注销已删 User 行，身份列随之消失，无需额外清理。

## 6. 客户端设计

### 6.1 网络层（data/remote/Api.kt）

- `UserDto` 加 `nickname: String? = null`、`avatarPreset: String? = null`。
- 新增 `IdentityDto(guid, username, email, nickname, signature, gender, birthday, avatarPreset, avatar)`（字段均可空默认 null）。
- 新增 `AccountMeResp(identity: IdentityDto, serverTime: Long = 0)`。
- 新增 `UpdateIdentityReq(nickname, signature, gender, birthday, avatarPreset, avatar)`（均 `String? = null`）。
- `YanzhongApi` 加 `@GET("api/v1/account/me") getAccountMe(): AccountMeResp` 与 `@PATCH("api/v1/account/me") updateAccountMe(@Body body): AccountMeResp`。

### 6.2 本地缓存（TokenStore）

沿用 username 的范式，新增键 nickname/signature/gender/birthday/avatar_preset/avatar_data：
- `data class IdentityCache(nickname, signature, gender, birthday, avatarPreset, avatar)`（全 String?）。
- `observeIdentity(): Flow<IdentityCache>`、`currentIdentity(): IdentityCache`、`saveIdentity(cache)`（null/空白键移除）、`clearAll()` 一并清除这些键。
- 新增 `data/remote/Identity.kt`：`suspend fun refreshIdentity(): IdentityCache?` —— 调 getAccountMe，成功写 TokenStore 并返回；失败返回 null（静默，调用方回落缓存）。MineViewModel 在 account 变为 LoggedIn 时 launch 一次（保证新设备登录后头部能拿到头像昵称）；编辑页 init 与保存后也调用。

### 6.3 上传工具复用

抽 `OnboardingViewModel.compressToBase64` 为共享 `internal fun compressImageToBase64(context, uri, maxSide, quality): String?`（放 `data/util/ImageCompression.kt`），逻辑不变（decode→scaleDown→JPEG→Base64.NO_WRAP，回收 bitmap）。OnboardingViewModel 改为委托（maxSide 1600/quality 82 保持）；头像用 maxSide 512/quality 85。

### 6.4 预设头像与 UserAvatar 组件

- `ui/profile/PresetAvatars.kt`：`data class PresetAvatar(key, emoji, label, colors: List<Color>)` + `val PRESET_AVATARS`（10 个，§7）。key 与服务端白名单一致。
- `ui/profile/UserAvatar.kt`：`@Composable UserAvatar(avatarPreset: String?, avatarBase64: String?, size: Dp, modifier)`。
  - avatarBase64 非空 → `remember(avatarBase64){ BitmapFactory.decodeByteArray(Base64.decode(...),0,…) }` → `Image(bitmap.asImageBitmap(), clip CircleShape)`；解码失败回落预设/图标。
  - 否则 preset 命中 → 渐变圆底（colors）+ `Text(emoji)`。
  - 否则 → 圆底 + `AppIcons.GraduationCap`（兜底）。
- Mine 头部卡（MineScreen.kt:519-532）与账号卡（AccountScreen.kt:213-227）改用 UserAvatar；头部显示昵称（`nickname ?: username`），账号卡同理。

### 6.5 编辑资料页

- 路由 `Routes.EDIT_PROFILE = "edit_profile"`；AppNav 注册 `composable(Routes.EDIT_PROFILE){ EditProfileScreen(padding, navController) }`；加入 `deepPage` 判定隐藏底部导航。
- 入口：Mine `HeaderCard.onClick` 登录态→EDIT_PROFILE，未登录→ACCOUNT；账号卡加 `TextAction("编辑资料")`→EDIT_PROFILE。
- `ui/profile/EditProfileViewModel.kt`：
  - `EditProfileUiState`：nickname/signature/gender/birthday 表单值、`selectedPreset: String?`、`customAvatar: String?`（互斥）、loading/saving/error/saved。
  - init：先读 TokenStore 缓存铺表单，再 `refreshIdentity()` 拉服务端覆盖（离线回落缓存）。
  - `pickPreset(key)`：selectedPreset=key，customAvatar=null。
  - `uploadAvatar(uri)`：compressImageToBase64(512,85) → customAvatar=base64，selectedPreset=null；失败置 error。
  - `save()`：客户端校验（§6.6）→ updateAccountMe(整份表单) → 成功 saveIdentity(resp.identity) + 置 saved（导航返回）；失败置 error（apiErrorMessage）。
- `ui/profile/EditProfileScreen.kt`（共享组件、硬编码中文、CONTENT_MAX_WIDTH、statusBarsPadding、可滚动 Column）：
  - 顶部居中 UserAvatar(96dp) + `TextAction("更换头像")` → 头像选择弹层（ModalBottomSheet 或 AlertDialog）：预设网格（LazyVerticalGrid/FlowRow）+ 「从相册上传」行（GetContent `image/*`）；选中即关闭。
  - 昵称 OutlinedTextField（单行，字数计数 ≤32）。
  - 签名 OutlinedTextField（minLines 2，计数 ≤100）。
  - 性别 FilterChip 行：保密(secret)/男(male)/女(female)/其他(other)。
  - 生日：只读行显示 YYYY-MM-DD，点击弹 Material3 DatePickerDialog，可清除。
  - error 文本行；PrimaryButton("保存", loading=saving)。

### 6.6 客户端校验（抽纯函数供 JUnit 测）

`ui/profile/ProfileValidation.kt` internal 纯函数，规则与服务端一致：
`validateNickname`/`validateSignature`（码点长度）、`validateGender`（枚举）、`validateBirthday`（格式+真实+范围）、`avatarConflict(preset, custom)`（两者非空冲突）。返回可读中文错误或 null。保存前调用，避免填完才被服务端打回。

## 7. 预设头像（服务端白名单 = 客户端定义）

10 个 key（服务端 `server/src/modules/account/avatarPresets.ts` 导出 `AVATAR_PRESET_KEYS`，客户端 PresetAvatars 镜像）：

| key | emoji | 寓意 |
|---|---|---|
| fox | 🦊 | 灵动 |
| panda | 🐼 | 沉稳 |
| owl | 🦉 | 夜读 |
| rocket | 🚀 | 冲刺 |
| book | 📚 | 苦读 |
| moon | 🌙 | 晚自习 |
| star | ⭐ | 上岸 |
| clover | 🍀 | 好运 |
| cat | 🐱 | 陪伴 |
| target | 🎯 | 目标 |

客户端为每个 key 配 BrandGradient 系渐变色；服务端只认 key，不关心 emoji/颜色。

## 8. 测试

- **服务端**（account 假库缝，沿用 `createAccountService(db, deps)`）：
  - GET me 返回各字段与 avatar base64；账号不存在 404。
  - PATCH 改 nickname/signature/gender/birthday 生效；未传字段保持；null 清空。
  - 预设白名单：未知 key 400；合法 key 落库且清 avatarData。
  - 头像：合法 base64 落库且清 preset；非法魔数/超 1MB 400；null 清空。
  - 互斥：同请求 preset+avatar 非空 → 400 AVATAR_CONFLICT。
  - birthday：非法格式/假日期/未来/早于1900 → 400；合法落库。
  - nickname/signature 超码点上限 → 400。
  - exportAccount 含新身份字段。
- **客户端**（JUnit4 纯函数）：ProfileValidation 各规则边界；PresetAvatars key 唯一且与服务端白名单一致（可硬编码断言 10 key）。
- **构建/走查**：`gradlew.bat :app:assembleDebug`（按 windows-msys-gradle 记忆用 PowerShell + -Xmx1536m + GBK）；服务端 `npm run verify`；模拟器竖屏走查：编辑昵称/签名/性别/生日、选预设、相册上传、保存后 Mine 头部与账号卡展示一致、退出登录缓存清空、老账号回落用户名+图标。截图核对。

## 9. 上线与回滚

- schema 全可空新增列，`prisma db push` 安全；老行新列为 null，客户端回落逻辑保证无感。
- 服务端经 bundle 部署（备份→bundle→scp→git pull→docker compose up -d --build→/healthz+healthy+新代码进镜像三验），客户端 OTA m7。**两者各自需负责人单独批准**，本 spec 不含执行。
- 回滚：客户端旧版本忽略未知字段（ignoreUnknownKeys）照常运行；服务端回滚后新列残留但无读写路径，无副作用。

## 10. 范围边界（本次不做）

- 不做头像裁剪/滤镜（仅缩放压缩）；不做文件存储/CDN/静态头像 URL。
- 不做社交（他人主页/关注/排行榜）；身份仅本人可见，与现有「数据仅自己可见」一致。
- 不改备考档案 `/profile`；不引入 Coil 等新依赖。
- 不做性别/生日的强校验运营逻辑（仅格式与范围校验，均可空）。