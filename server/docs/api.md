# 研钟后端 API 文档（速查）

完整交互式文档：启动服务后访问 **http://localhost:3000/docs**（Swagger UI）。
规范文件：`server/docs/openapi.yaml`。

## 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/auth/email/code | 注册验证码 `{email, purpose:"register", inviteCode?}` → `{sent:true}`；限流 IP 10/时、邮箱 3/时、同邮箱 1/分；SMTP 未配置 503 |
| POST | /api/v1/auth/register | 注册 `{username, password, email, code, termsAccepted, privacyAccepted, inviteCode?}` → 201 `{user}`；限流 IP 10/时、邮箱 5/时 |
| POST | /api/v1/auth/login | 登录 `{username, password, deviceGuid, deviceName, platform?, rememberMe?}` → `{user, device, tokens:{access, refresh, expiresIn}}` |
| POST | /api/v1/auth/refresh | 刷新 `{refresh}`（轮换，旧 token 作废）|
| POST | /api/v1/auth/logout | 登出当前设备 🔒 |
| POST | /api/v1/auth/password/forgot | `{email}` 发 10 分钟有效的 6 位验证码，始终返回 `{sent:true}`（不暴露邮箱是否存在）；限流 IP 10/时、邮箱 5/时；SMTP 未配置 503 |
| POST | /api/v1/auth/password/reset | `{email, code, newPassword}` → 204（吊销全部 refresh token，所有设备下线）|
| POST | /api/v1/auth/password/change | `{oldPassword, newPassword}` 🔒（改密后全设备下线）|

注册必须显式勾选协议（否则 400 `LEGAL_CONSENT_REQUIRED`）；验证码 6 位、10 分钟内有效、最多尝试 5 次，
错误或过期返回 400 `INVALID_EMAIL_CODE`。用户名/邮箱冲突返回 409，未带邀请码返回 400 `INVITE_REQUIRED`
（服务端配置 `INVITE_CODES` 后，发送验证码与注册都必须携带邀请码；邀请码不匹配返回 400 `INVITE_INVALID`；
生产环境未配置邀请码时同样拒绝）。

🔒 = 需要 `Authorization: Bearer <access>`。401 时用 refresh 刷新。

## 设备与会话

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/v1/devices | 全部登录设备（名称/最近活跃/IP/在线状态）🔒 |
| DELETE | /api/v1/devices/{id} | 强制登出指定设备（踢 WS）🔒 |
| DELETE | /api/v1/devices | 全部登出 🔒 |

## 账号与合规

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/account/consent | 重新同意当前协议 `{version}` → 204；版本不符返回 409 `POLICY_VERSION_MISMATCH` 🔒 |

## 同步与备份

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/v1/sync-meta | 可用资源清单 + serverTime 🔒 |
| POST | /api/v1/sync/{resource} | 批量 upsert（LWW），body = 行数组或 `{rows:[...]}` 🔒 |
| GET | /api/v1/sync?since=&resources= | 增量拉取（含软删墓碑），响应带 serverTime 🔒 |
| GET | /api/v1/backup | 全量导出（version 2：7 表 + settings）🔒 |
| POST | /api/v1/backup/restore | 全量恢复（事务清表重插；兼容 App 本地导出 nodes 字段名）🔒 |
| GET / PUT | /api/v1/settings | 设置 KV 读取/合并写入 🔒 |

resource ∈ `subjects | countdownNodes | tasks | sessions | dailyReviews | weeklyReviews | monthlyReviews`

写成功后服务端经 WS 广播 `dataChanged`/`settingsChanged`（见下），他端立即拉取。

## WebSocket 状态同步

连接 `ws://<host>/ws`（`Authorization: Bearer <access>`；access 过期返回 4001，刷新后重连）

**上行**：
```json
{"type":"hello","deviceName":"我的手机"}          // 连接/重连后第一步
{"type":"status","phase":"focus","remainMs":123456,"taskId":1,"taskTitle":"数学刷题","planName":"标准 25"}
{"type":"stop"}
```
phase ∈ `focus | break | idle`；status 另带 `sessionGuid`（同场专注标识）/`following`（跟随中）/`paused`（暂停）

**下行**：
```json
{"type":"presence","serverTime":0,"devices":[{"deviceId":1,"deviceName":"我的平板","online":true,"self":false,"lastStatus":{...}}]}
{"type":"peerStatus","serverTime":0,"deviceId":1,"deviceName":"我的平板","status":{...}}
{"type":"dataChanged","serverTime":0,"deviceId":1,"resource":"tasks","full":false}
{"type":"settingsChanged","serverTime":0,"deviceId":1}
{"type":"kicked","reason":"强制登出"}
```

- `dataChanged`：他端推送数据（resource=资源名）或全量恢复（full=true，需清 since 水位重拉）后广播；deviceId=发送方，客户端据此跳过自身
- `settingsChanged`：他端更新云端设置后广播，接收端 GET /settings 立即拉取应用

## 错误格式

```json
{ "error": "INVALID_CREDENTIALS", "message": "用户名或密码错误" }
```

常见：`UNAUTHORIZED`(401) / `RATE_LIMITED`(429) / `INVALID_PARAMS`(400) / `CONFLICT`(409) / `INVALID_REFRESH`(401)
