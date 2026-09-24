# 研钟后端部署文档

面向自建服务器（当前目标：`81.71.14.219`）。技术栈 Koa + TypeScript + Prisma + MySQL 8 + WebSocket，
用 Docker Compose 一键起 MySQL 与 API。

> 当前阶段为 **IP + HTTP 内测**：App 通过 `http://81.71.14.219:3000` 访问。
> 明文 HTTP 仅供验证，正式对外前必须换成域名 + HTTPS（见第八节）。

## 一、环境要求

- 服务器：2 核 2G 起，Ubuntu 22.04 / Debian 12 最佳（其他发行版见 2.3）
- Docker Engine 24+ 与 Docker Compose v2
- 放行入站 TCP **3000**（云厂商安全组 + 系统防火墙两层都要放）
- 本地：Node.js ≥ 18 仅用于本地开发，部署到服务器不需要

## 二、全新服务器初始化

### 2.1 安装 Docker（Ubuntu / Debian）

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
docker compose version    # 应输出 v2.x
```

### 2.2 放行 3000 端口

```bash
# 系统防火墙（若启用了 ufw）
sudo ufw allow 3000/tcp
```

**云厂商安全组同样要放行**，否则外面依然连不上：登录云控制台 → 安全组 → 入站规则 →
新增 `TCP:3000`，来源 `0.0.0.0/0`。这一步最容易被漏掉，表现为本机 `curl` 通、外部超时。

### 2.3 其他发行版

CentOS / TencentOS / openEuler 用 `sudo yum install -y docker-ce docker-compose-plugin`，
或直接 `sudo dnf install -y docker docker-compose-plugin`；防火墙换成
`sudo firewall-cmd --permanent --add-port=3000/tcp && sudo firewall-cmd --reload`。

## 三、拉取代码

```bash
sudo mkdir -p /opt && cd /opt
sudo git clone https://github.com/feng260/kaoyan.git
cd /opt/kaoyan/server
```

私有仓库需先在服务器配置访问凭证（`gh auth login` 或部署密钥）。

## 四、配置环境变量

`server/.env` 被 `.gitignore` 排除，**不会随代码下发，必须在服务器上单独创建**。

```bash
cd /opt/kaoyan/server
cp .env.example .env
```

编辑 `.env`，至少填这三项：

```ini
# MySQL 容器 root 密码。compose 会用它拼出容器内网的 DATABASE_URL
# 只允许字母数字:含 @ / : 等字符会破坏连接串解析,导致 API 启动即失败
MYSQL_ROOT_PASSWORD=换成一段随机字母数字

# 必填,缺失时 compose 直接拒绝启动
# 生成:openssl rand -hex 64
JWT_SECRET=换成上面命令生成的 64 字节随机串

# 管理后台与 APK 上传令牌,不填则 /admin 与上传接口全部 403
ADMIN_TOKEN=换成一段随机串
```

其余项说明：

| 变量 | 说明 |
|---|---|
| `DATABASE_URL` | **容器部署时被 compose 覆盖，填了也不生效**，保持默认即可 |
| `PORT` | 由 compose 固定为 3000，无需修改 |
| `CORS_ORIGINS` | 留空即可。App 是原生客户端不受同源策略限制；将来做 Web 管理端再填 |
| `SMTP_*` | 找回密码用。留空时重置链接会降级输出到服务端日志，功能仍可用 |
| `TRUST_PROXY` | compose 已固定为 `false`，不要改（见第七节） |

## 五、启动服务

```bash
cd /opt/kaoyan/server
sudo docker compose up -d --build
```

首次构建约 2-5 分钟，流程为：装依赖 → `prisma generate` → 编译 TS →
等 MySQL 健康检查通过 → `prisma db push` 建表 → 启动 API。

查看状态与日志：

```bash
sudo docker compose ps
sudo docker compose logs -f api
```

日志出现 `[yanzhong-server] listening on :3000 (production)` 即为成功。

## 六、验证

在**服务器上**：

```bash
curl http://127.0.0.1:3000/healthz
# 期望: {"ok":true,"serverTime":...}
```

在**本地电脑上**（这一步才能真正验证安全组是否放行）：

```bash
curl http://81.71.14.219:3000/healthz
```

浏览器打开 `http://81.71.14.219:3000/docs` 可看 Swagger 文档，
`http://81.71.14.219:3000/admin` 用 `ADMIN_TOKEN` 登录管理后台。

App 端：默认地址已内置为 `http://81.71.14.219:3000`，装包后直接登录即可；
也可在「我的 → 云同步 → 服务器地址」手动覆盖。

## 七、安全说明

- `TRUST_PROXY` 必须保持 `false`。当前 API 直接暴露 3000 端口、前面没有反向代理，
  开启后攻击者可伪造 `X-Forwarded-For` 绕过登录限流并伪造来源 IP。
  将来接入 Nginx/Caddy 后，再改为 `true`。
- 明文 HTTP 的流量（含登录密码与 JWT）在链路上可被嗅探，**仅限内测**。
- `JWT_SECRET` 泄露等于任何人都能签发合法 token，务必用随机串且不要提交到仓库。
- 密码存储为 bcrypt(12)，refresh token 仅存 SHA-256 并支持轮换吊销。

## 八、切换到域名 + HTTPS

1. 域名解析到服务器 IP，装 Caddy 或 Nginx 反代 `127.0.0.1:3000`，签好证书。
2. 改 `docker-compose.yml`：`TRUST_PROXY` 改为 `"true"`；`ports` 改为 `"127.0.0.1:3000:3000"`，
   只允许本机反代访问，不再对公网直接暴露 3000。
3. 云安全组关闭 3000 入站，只保留 80/443。
4. App 端两处同步改：`data/remote/Api.kt` 的 `DEFAULT_SERVER_URL` 改为 `https://<域名>`，
   并从 `res/xml/network_security_config.xml` 删掉该 IP 的明文许可。

## 九、运维

| 事项 | 命令 |
|---|---|
| 查看日志 | `sudo docker compose logs -f api` |
| 重启 API | `sudo docker compose restart api` |
| 更新代码后重新部署 | `sudo git pull && sudo docker compose up -d --build` |
| 停止全部服务 | `sudo docker compose down`（**不要加 `-v`，会连数据卷一起删**） |
| 备份数据库 | `sudo docker compose exec mysql mysqldump -uroot -p yanzhong > backup_$(date +%F).sql` |
| 恢复数据库 | `sudo docker compose exec -T mysql mysql -uroot -p yanzhong < backup.sql` |
| 进数据库命令行 | `sudo docker compose exec mysql mysql -uroot -p yanzhong` |

数据落在两个命名卷里：`mysql-data`（数据库）、`releases-data`（上传的 APK）。
容器重建不影响数据，但 `docker compose down -v` 会清空。

### 表结构变更

`prisma/schema.prisma` 改动后重新 `up -d --build` 即可，启动命令会自动同步。
启动命令**没有**加 `--accept-data-loss`：如果新结构需要删列，服务会启动失败并报错，
而不是静默清掉线上数据。确认可以丢数据时，手动执行：

```bash
sudo docker compose exec api npx prisma db push --accept-data-loss
```

## 十、常见问题

**外部访问超时，服务器本机 curl 正常**
安全组没放行 3000。见 2.2。

**`error while interpolating services.mysql.environment.MYSQL_ROOT_PASSWORD`**
`.env` 里没填 `MYSQL_ROOT_PASSWORD`，或文件不在 `server/` 目录下（compose 只读同目录的 `.env`）。

**API 反复重启，日志报数据库连接失败**
`MYSQL_ROOT_PASSWORD` 含 `@`、`/`、`:` 等字符破坏了连接串。改成纯字母数字后
`sudo docker compose down && sudo docker compose up -d`（密码已写进 MySQL 数据卷，
改密码需先 `down -v` 清空重来，即会丢数据）。

**`/admin` 打开是空白或报错**
镜像里缺 `public/admin.html`。确认用的是当前 Dockerfile（已包含 `COPY --from=build /app/public ./public`），
然后 `up -d --build` 重建。

**App 提示无法连接 / 登录失败**
1. 先按第六节确认 `curl http://81.71.14.219:3000/healthz` 通。
2. 若服务正常但 App 报 `CLEARTEXT communication not permitted`，
   说明 `res/xml/network_security_config.xml` 里没放行该地址。
3. 真机连电脑局域网调试时，需把电脑局域网 IP 也加进该文件并重新打包。

**上传的 APK 重启后消失**
`releases-data` 卷被 `down -v` 删掉了，或部署时用的旧 compose（没有挂该卷）。

## 十一、本地开发

```powershell
cd f:\kaoyan-app-prd\server
Copy-Item .env.example .env
# 填 DATABASE_URL 指向本机 MySQL,JWT_SECRET 随意
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS yanzhong CHARACTER SET utf8mb4"
npm install
npm run db:push
npm run dev              # http://localhost:3000
```

本地不需要 `MYSQL_ROOT_PASSWORD`（仅 Docker Compose 使用）。