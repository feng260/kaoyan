# 研钟后端部署文档

## 一、环境要求

- Node.js ≥ 18（推荐 20/22 LTS）
- MySQL ≥ 8.0（本机已装 9.5 可直接用）
- （可选）Docker + Docker Compose

## 二、本地开发（Windows）

```powershell
cd f:\kaoyan-app-prd\server

# 1. 配置环境
Copy-Item .env.example .env
# 编辑 .env:
#   DATABASE_URL="mysql://root:<你的密码>@localhost:3306/yanzhong"
#   JWT_SECRET=<任意长随机串,如 openssl rand -hex 64 或 PowerShell:
#     -join ((48..57)+(97..122) | Get-Random -Count 64 | % {[char]$_})

# 2. 建库 + 建表
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS yanzhong CHARACTER SET utf8mb4"
npm run db:push          # 按 prisma/schema.prisma 同步表结构

# 3. 安装依赖并启动
npm install
npm run dev              # tsx 热重载,http://localhost:3000
```

验证：`curl http://localhost:3000/healthz` → `{"ok":true,...}`；API 文档 http://localhost:3000/docs

## 三、Docker 部署（云服务器）

```bash
cd server
cp .env.example .env
# 编辑 .env:数据库指向 compose 内的 mysql 服务
#   DATABASE_URL="mysql://root:yanzhong@mysql:3306/yanzhong"
#   JWT_SECRET=<长随机串>

docker compose up -d --build
# 自动:MySQL 初始化 → prisma db push → 服务启动(0.0.0.0:3000)
```

## 四、Android 端连接

App「我的 → 云同步 → 设置服务器」填入：
- 本地局域网：`http://<电脑局域网IP>:3000`（手机平板与电脑同一 WiFi）
- 云服务器：`https://<你的域名>`（建议前置 nginx + HTTPS）

## 五、运维要点

| 事项 | 命令 |
|---|---|
| 查看日志 | `docker compose logs -f api` / 本地直接控制台 |
| 数据库管理 | `npm run db:studio`（Prisma Studio 可视化） |
| 表结构变更 | 改 `prisma/schema.prisma` → `npm run db:push`（开发）或 `npm run db:migrate`（生产留迁移记录） |
| 备份 MySQL | `mysqldump yanzhong > backup.sql`（另有应用层全量备份 API） |

## 六、安全清单

- [x] 密码 bcrypt(12)，永不返回哈希
- [x] refresh token 仅存 SHA-256，轮换 + 吊销
- [x] JWT_SECRET 必改（env 启动校验强制）
- [x] 限流：登录/注册/找回 5 次/15 分钟，普通接口 60 次/分钟
- [x] SQL 全走 Prisma 参数化（防注入）；纯 JSON API 无 HTML 渲染（XSS 面收敛）
- [x] koa-helmet 安全响应头
- [ ] 生产上云：CORS_ORIGINS 收紧为 App/管理端域名；前置 nginx TLS
