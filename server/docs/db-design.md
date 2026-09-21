# 研钟后端数据库设计

MySQL 8+，Prisma ORM（schema 权威来源：`server/prisma/schema.prisma`）。
所有时间戳为 **BigInt 毫秒**（与 App `System.currentTimeMillis()` 对齐）。表名 snake_case。

## 表清单（9 张）

### users — 账号
| 列 | 类型 | 说明 |
|---|---|---|
| id | INT PK AUTO | 内部主键 |
| guid | VARCHAR(36) UNQ | 对外用户标识，业务表 `user_guid` 关联此列 |
| username | VARCHAR(32) UNQ | 登录名 |
| email | VARCHAR(255) UNQ NULL | 找回密码通道 |
| password_hash | VARCHAR(100) | bcrypt(12) |
| reset_token_hash / reset_token_expiry | — | 找回密码一次性 token 的 SHA-256 与 30 分钟有效期 |
| created_at | BIGINT | |

### devices — 登录设备
| 列 | 类型 | 说明 |
|---|---|---|
| id | INT PK AUTO | |
| user_guid | VARCHAR(36) IDX | 归属用户 |
| device_guid | VARCHAR(36) UNQ | 客户端生成并持久化的设备 UUID |
| name / platform | — | 设备名（如“陈喜俊的手机”）/ 平台 |
| last_ip / last_active_at | — | 最近连接 IP（`app.proxy=true` 时为真实 IP） |

### refresh_tokens — 会话
| 列 | 类型 | 说明 |
|---|---|---|
| id | INT PK AUTO | |
| user_guid / device_id | IDX | 会话归属 |
| token_hash | VARCHAR(100) UNQ | SHA-256(refresh 明文)，明文只在客户端 |
| expires_at / revoked_at | BIGINT | 滑动续期；revoked_at 非空即吊销（登出/轮换/强制下线） |

**会话策略**：登录签发 access(2h) + refresh（“记住我”30 天/默认 7 天）；`/auth/refresh` 轮换（旧 token 立即吊销）；改密/重置密码吊销全部 refresh。

### 业务表（6 张）通用列

`subjects / countdown_nodes / tasks / pomodoro_sessions / daily_reviews / weekly_reviews`

| 通用列 | 说明 |
|---|---|
| id INT PK AUTO | 服务端主键 |
| user_guid | 数据隔离：所有查询强制带此条件 |
| client_guid VARCHAR(36) | 客户端稳定标识，UNQ(user_guid, client_guid) 支撑幂等 upsert |
| updated_at BIGINT | idx(user_guid, updated_at) 支撑增量拉取 |
| is_deleted | 软删墓碑：删除也作为变更同步，防止删端复现 |

业务字段与 App Room 实体（`YanZhong/.../Entities.kt`）**同名同型**；跨表业务关联存对端 `client_guid`（`task.subject_client_guid`、`session.task_client_guid/subject_client_guid`），避免多设备本地 id 不一致。

### daily_reviews / weekly_reviews — 复盘（自然键表）
- `UNQ(user_guid, epoch_day)` / `UNQ(user_guid, week_start_epoch_day)`：一天/一周一行，天然幂等
- 无 client_guid/is_deleted（upsert 即覆盖）

### user_settings — 设置 KV
`UNQ(user_guid, key)` + `value`（JSON 文本）+ `updated_at`。

## 关系图（逻辑外键，业务查询走 user_guid）

```
users.guid ──┬── devices.user_guid
             ├── refresh_tokens.user_guid
             ├── subjects / countdown_nodes / tasks / pomodoro_sessions .user_guid
             │        task.subject_client_guid ──> subjects.client_guid
             │        session.task_client_guid ──> tasks.client_guid
             │        session.subject_client_guid ──> subjects.client_guid
             ├── daily_reviews / weekly_reviews .user_guid
             └── user_settings.user_guid
```

## 索引设计

| 表 | 索引 | 用途 |
|---|---|---|
| 全部业务表 | UNQ(user_guid, client_guid) | 幂等 upsert 定位 |
| 全部业务表 | idx(user_guid, updated_at) | 增量同步 `WHERE user_guid=? AND updated_at>?` |
| tasks | idx(user_guid, subject_client_guid) | 科目维度任务查询 |
| pomodoro_sessions | idx(user_guid, started_at) | 时间范围统计 |
| devices / refresh_tokens | idx(user_guid) | 设备列表/会话吊销 |

## 同步协议要点

1. **增量拉取**：`GET /api/v1/sync?since=<上次 serverTime>`，返回 updated_at > since 的行（含墓碑）
2. **LWW upsert**：存量 updated_at ≥ incoming → 跳过；否则整行覆盖
3. **时钟漂移**：服务端写入 `updatedAt = max(serverNow, incoming.updatedAt)`
4. **备份/恢复**：全量导出（version 2，含 reviews+settings）；恢复为事务内清表重插
