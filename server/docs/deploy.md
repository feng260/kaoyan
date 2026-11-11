# 研钟后端部署文档

面向自建服务器（当前目标：`81.71.14.219`）。技术栈 Koa + TypeScript + Prisma + MySQL 8 + WebSocket，
用 Docker Compose 一键起 MySQL 与 API。

> **仓库基线为 Caddy + HTTPS**：`docker-compose.yml` 已把 API 绑定到 `127.0.0.1:3000`，并设置
> `TRUST_PROXY: "true"`，对外只经 Caddy 的 80/443 提供服务（切换步骤见第九节）。
> 下文出现的 IP + HTTP 地址示例均属**旧内测形态，仅作回滚参考**，标准路径以第九节为准。

## 一、环境要求

- 服务器：2 核 2G 起，Ubuntu 22.04 / Debian 12 最佳（其他发行版见 2.3）
- Docker Engine 24+ 与 Docker Compose v2
- 放行入站 TCP **80/443**（云厂商安全组 + 系统防火墙两层都要放）；3000 不对公网开放
- 本地：Node.js ≥ 18 仅用于本地开发，部署到服务器不需要

## 二、全新服务器初始化

### 2.1 安装 Docker（Ubuntu / Debian）

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
docker compose version    # 应输出 v2.x
```

### 2.2 放行 80/443（标准路径）

标准路径只放行 TCP **80/443**，TLS 由 Caddy 终结，3000 保持仅本机监听：

```bash
# 系统防火墙（若启用了 ufw）
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
```

**云厂商安全组同样要放行**，否则外面依然连不上：登录云控制台 → 安全组 → 入站规则 →
新增 `TCP:80`、`TCP:443`，来源 `0.0.0.0/0`。这一步最容易被漏掉，表现为本机 `curl` 通、外部超时。

> **旧路径（仅回滚用）**：退回 IP + HTTP 内测时，才需要 `sudo ufw allow 3000/tcp` 并在安全组
> 新增 `TCP:3000`（来源 `0.0.0.0/0`），同时把 compose 的 `ports` 恢复为 `"3000:3000"`、
> `TRUST_PROXY` 改回 `"false"` 再重建（见第九节末尾的回滚说明），并告知用户明文传输风险。

### 2.3 其他发行版

CentOS / TencentOS / openEuler 用 `sudo yum install -y docker-ce docker-compose-plugin`，
或直接 `sudo dnf install -y docker docker-compose-plugin`；防火墙换成
`sudo firewall-cmd --permanent --add-port=80/tcp --add-port=443/tcp && sudo firewall-cmd --reload`
（回滚到 IP + HTTP 时才改为放行 3000）。

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
| `SMTP_*` | 注册与找回密码的验证码邮件依赖它。未配置时发送验证码的接口返回 503（`EMAIL_NOT_CONFIGURED`），没有日志降级 |
| `TRUST_PROXY` | compose 已固定为 `true`：Caddy 是唯一入口且 3000 只绑 `127.0.0.1`，代理头可信。**开着它时绝不能把 3000 暴露到公网**（见第八、九节） |

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

### 升级已有部署：交还 `releases-data` 卷属主

镜像已改为以非 root 的 `node` 用户运行，而旧版本容器以 root 写入 `releases-data` 卷，
升级后卷内文件属主仍是 `root:root`，首个 APK 上传会因 EACCES 失败。**升级已有部署时**，
在首次上传 APK 前执行：

```bash
# 新镜像以 node 用户运行;旧 releases 卷由 root 容器创建,上线前必须交还属主
sudo docker compose exec -u 0 api chown -R node:node /app/releases
# 验证可写(应无输出、退出码 0)
sudo docker compose exec api sh -c 'touch /app/releases/.write-test && rm /app/releases/.write-test'
```

全新部署不需要这一步（Dockerfile 已创建目录并 chown）。如果已经跳过、首次上传报 5xx
且 `docker compose logs api` 中出现 `EACCES`，在 API 容器运行期间补执行上面两条命令即可恢复，
不需要重建容器或数据卷。

## 六、验证

在**服务器上**：

```bash
curl http://127.0.0.1:3000/healthz
# 期望: {"ok":true,"serverTime":...}
```

在**本地电脑上**（验证公网链路；需先完成第九节的 Caddy / HTTPS 切换）：

```bash
curl -fsS https://<域名>/healthz
```

> **旧路径（仅回滚用）**：退回 IP + HTTP 内测时才执行 `curl http://81.71.14.219:3000/healthz`
> 验证 3000 是否放行（见 2.2 与第九节末尾）。

浏览器打开 `https://<域名>/docs` 可看 Swagger 文档，
`https://<域名>/admin` 用 `ADMIN_TOKEN` 登录管理后台。

App 端：标准包默认地址为 `https://<域名>`，装包后直接登录即可（旧内测包仍内置
`http://81.71.14.219:3000`）；也可在「我的 → 云同步 → 服务器地址」手动覆盖。

## 七、发布 App 更新（管理后台）

发布只用 `/admin` 管理后台，不需要把 APK 拷到服务器，也不需要旧的一键发布脚本。

1. **改版本号**：`YanZhong/app/build.gradle.kts` 中 `versionCode` 加 1，`versionName` 同步修改。
2. **打 release 包**（签名与上次发布保持一致）：

   ```powershell
   cd F:\kaoyan-app-prd\YanZhong
   .\gradlew.bat :app:assembleRelease '-Dorg.gradle.jvmargs=-Xmx1536m'
   ```

   产物：`app/build/outputs/apk/release/app-release.apk`。

3. **上传**：浏览器打开 `https://<域名>/admin`，用 `ADMIN_TOKEN` 登录，
   在「版本发布」选 APK、按需勾选「强制更新」、填写更新说明后上传。
   服务端会直接从 APK 的 AndroidManifest 解析包名与版本，不读表单：
   - 包名必须是 `com.yanzhong.app`，否则拒绝；
   - `versionCode` 必须大于当前最新版本，否则拒绝；
   - 上传带进度，成功后列表第一行标「最新」。

4. **验证**：

   ```bash
   curl -s "https://<域名>/api/v1/app/latest?current=1"
   # 期望 updateAvailable 为 true,并返回 latest.versionCode / downloadUrl / sha256
   ```

5. **客户端行为**：启动时自动检查；回到前台且距上次检查 ≥ 6 小时再检查；
   「我的 → 检查更新」可手动触发。发现新版本弹更新框，下载完成后校验 SHA-256 再调起安装器。
   非强制版本可「跳过此版本」，强制版本不能跳过。

注意事项：

- **签名必须与上一次发布一致**，否则已安装用户无法覆盖安装，只能卸载重装。
- 从 debug 签名包（开发机直装）切到 release 签名包需要**一次性卸载重装**；
  迁移完成后，后续版本都在应用内更新。
- 上传的 APK 存放在 `releases-data` 卷，`docker compose down -v` 会连同数据库一起清空。
- 安装未知来源应用需要用户授权，首次更新会在系统设置里引导开启。

## 八、安全说明

- `TRUST_PROXY` 只在「API 仅绑定 `127.0.0.1`、前面是唯一入口 Caddy」时保持 `true`：Caddy 模板会
  覆写 `X-Forwarded-For` / `X-Real-IP`，客户端无法伪造来源 IP。一旦把 3000 重新暴露到公网，
  必须同时改回 `false`，否则攻击者可伪造代理头绕过登录限流并伪造来源 IP。
- 明文 HTTP 的流量（含登录密码与 JWT）在链路上可被嗅探，**仅限内测**。
- `JWT_SECRET` 泄露等于任何人都能签发合法 token，务必用随机串且不要提交到仓库。
- 密码存储为 bcrypt(12)，refresh token 仅存 SHA-256 并支持轮换吊销。

## 九、切换到域名 + HTTPS

上线前按 [Phase 0 执行清单](phase0-rollout-checklist.md) 记录旧镜像、备份与回滚负责人。
以下命令在服务器上执行，`<域名>` 必须替换为实际 DNS 名称；需要服务器与域名管理权限，
仅准备模板不代表已完成线上切换。

1. DNS A/AAAA 指向服务器；云安全组及主机防火墙只开放 TCP 80/443（若启用 IPv6 也要检查），
   3000 保持不对公网开放。
2. **HTTPS APK 必须先构建并就绪**：现有测试用户仍指向 IP/HTTP 旧包，服务端一旦只提供 HTTPS
   且 3000 不再公网可达，旧包会立即断线。先将 Android `data/remote/Api.kt` 的
   `DEFAULT_SERVER_URL` 改为 `https://<域名>`，从 `res/xml/network_security_config.xml`
   移除旧公网 IP 的明文许可，打出 release 包并确认能在切换同一窗口装机（真机冒烟见第 7 步）。
3. 部署仓库现有 compose（已内置 `127.0.0.1:3000:3000` 与 `TRUST_PROXY: "true"`，**无需手改端口或变量**）：
   `sudo docker compose up -d --build`，再用 `curl -fsS http://127.0.0.1:3000/healthz` 确认本机健康。
4. 安装 Caddy 后先创建日志目录并交给 caddy 用户——用 root 直接 `mkdir` 会让 Caddy 无法写日志：

   ```bash
   sudo install -d -o caddy -g caddy -m 0755 /var/log/caddy
   ```

   复制 `deploy/Caddyfile.example` 到 `/etc/caddy/Caddyfile`，把 `api.yanzhong.app` 替换为实际 `<域名>`。
5. 校验并重载：`sudo caddy validate --config /etc/caddy/Caddyfile`、`sudo systemctl reload caddy`
   （首次部署用 `systemctl restart caddy`）。
6. 验证 `curl -fsS https://<域名>/healthz`、证书、登录及 WebSocket（Caddy 自动转发 WebSocket Upgrade）；
   并在外部确认 `:3000` 已不可达（如 `curl --connect-timeout 5 http://81.71.14.219:3000/healthz` 应超时或拒绝）。
7. 验证 `https://<域名>/docs`、`/admin`、`/api/v1/app/latest` 和已发布 APK 的
   `/api/v1/app/download/<versionCode>`（带 `Range` 请求），并在同一窗口用第 2 步的新包
   真机冒烟（登录、云同步、计划、下载、WebSocket）。

切换失败时以清单中记录的旧镜像标签/ID 回滚 API，保留两个数据卷；数据库 schema 不兼容时
先停止写入并评估恢复，不能将演练脚本指向生产库。确需退回 IP + HTTP 内测时，在知情的前提下把
compose 的 `ports` 恢复为 `"3000:3000"`、`TRUST_PROXY` 改回 `"false"` 再重建，并恢复 3000 的
安全组/防火墙放行、告知用户明文传输风险。

## 十、运维

| 事项 | 命令 |
|---|---|
| 查看日志 | `sudo docker compose logs -f api` |
| 重启 API | `sudo docker compose restart api` |
| 更新代码后重新部署 | `sudo git pull && sudo docker compose up -d --build` |
| 停止全部服务 | `sudo docker compose down`（**不要加 `-v`，会连数据卷一起删**） |
| 立即备份数据库 | `sudo systemctl start yanzhong-backup.service`（手动触发备份服务） |
| 备份定时器状态 | `systemctl list-timers yanzhong-backup.timer` |
| 隔离恢复演练 | `sudo /opt/kaoyan/server/scripts/restore-mysql-drill.sh /var/backups/yanzhong/mysql/<备份名>.sql.gz` |
| 进数据库命令行 | `sudo docker compose exec mysql mysql -uroot -p yanzhong` |

### 自动备份与恢复演练

`backup-mysql.sh` 默认写入 `/var/backups/yanzhong/mysql`，文件名含 UTC 时间与进程号；
创建压缩 dump 后先检验 gzip，再原子改名，并在同目录生成仅含文件名的 `.sha256`
（整对拷走后仍可校验）。它使用 Compose 内的 MySQL 密码，失败时返回非零，在同目录
`backup.log` 记录 UTC 时间；本地默认保留 14 天，到期时 `yanzhong-*.sql.gz` 与配对的
`.sha256` 一并清理。备份目录应在受控存储上并限制访问。脚本的可选环境变量：
`BACKUP_DIR`、`RETENTION_DAYS`（正整数）、`COMPOSE_PROJECT_DIR`（默认脚本上级目录）、
`BACKUP_SKIP_UPLOAD=1`（仅本地演练时跳过 COS 上传）；运行用户须有 Docker 权限和备份目录写权限。

每日 04:00（Asia/Shanghai，`Persistent=true`，随机延迟最多 5 分钟）由 systemd timer 触发，
日志进 journald。启用前先在主机安装 `rclone` 并用 `sudo rclone config` 配置名为 `cos`
的 remote（S3 兼容，provider TencentCOS，子账号只授权该私有桶），再创建 systemd
服务必读的环境文件：

```bash
sudo install -d -m 700 /etc/yanzhong
sudo install -m 600 /dev/null /etc/yanzhong/backup.env
sudoedit /etc/yanzhong/backup.env
```

```ini
COS_BUCKET=<桶名>
HEALTHCHECK_BACKUP_URL=<Healthchecks ping URL>
```

该文件必须为 600 且包含上述两项；`yanzhong-backup.service` 的 `EnvironmentFile` 没有
`-` 前缀，文件缺失或 `COS_BUCKET` 为空时服务直接失败（刻意的 fail-fast）。随后安装单元：

```bash
cd /opt/kaoyan/server
sudo chmod 700 scripts/backup-mysql.sh scripts/restore-mysql-drill.sh
sudo install -d -m 700 /var/backups/yanzhong/mysql
sudo cp deploy/yanzhong-backup.service deploy/yanzhong-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now yanzhong-backup.timer
sudo systemctl start yanzhong-backup.service   # 立即手动跑一次并等待完成
sudo systemctl status yanzhong-backup.service --no-pager
systemctl list-timers yanzhong-backup.timer
```

预期产物：本地 `/var/backups/yanzhong/mysql/yanzhong-*.sql.gz` 与同名 `.sha256`；
COS `cos:${COS_BUCKET}/yanzhong-backup/mysql/YYYY/MM/DD/` 下同一对文件。远端生命周期
30 天与桶的服务端加密在 COS 桶配置中设置（脚本本身不加密）；本地 14 天保留包含校验和。
每次成功会 ping `HEALTHCHECK_BACKUP_URL`；上传失败让服务非零退出（用
`journalctl -u yanzhong-backup.service` 查看），配合 Healthchecks 宽限期内的心跳缺失
构成死信告警。心跳 ping 失败只记 WARN，不改变备份结果。

> **曾按旧文档加过 cron 的服务器必须先删除旧任务**：执行 `sudo crontab -e`，删除或注释
> `15 2 * * * /opt/kaoyan/server/scripts/backup-mysql.sh`，并可用
> `sudo crontab -l | grep backup-mysql` 确认无残留。旧 cron 不读取
> `/etc/yanzhong/backup.env`，会因缺少 `COS_BUCKET` 每次退出 1，还会与 timer 重复备份。

定期检查 `backup.log`、`journalctl` 与磁盘容量。每次重大 schema 变更前再手动备份并记下文件校验值。

```bash
sudo /opt/kaoyan/server/scripts/restore-mysql-drill.sh /var/backups/yanzhong/mysql/<备份名>.sql.gz
```

演练脚本只接受 `.sql.gz`：先做 gzip 完整性检验，若同目录存在同名 `.sha256` 会先执行
`sha256sum -c` 校验；随后用 `mysql:8` 建立无网络、无端口映射、无生产卷的临时容器，
导入后打印 `users`、`plans`、`plan_items`、`tasks`、`pomodoro_sessions` 五个关键表的
行数供与备份时点对比，退出时仅删除该临时容器及匿名卷。脚本不支持生产恢复，即使设置
`ALLOW_PRODUCTION_RESTORE=YES` 也会拒绝；真正生产恢复需单独审批、停止写入、确认目标和
备份，并遵循事故恢复流程。演练需服务器可用且已拉取 `mysql:8` 镜像；打印的行数需人工
核对，脚本跑通不等于数据完整。

数据落在两个命名卷里：`mysql-data`（数据库）、`releases-data`（上传的 APK）。
容器重建不影响数据，但 `docker compose down -v` 会清空。

### 表结构变更

`prisma/schema.prisma` 改动后重新 `up -d --build` 即可，启动命令会自动同步。
启动命令**没有**加 `--accept-data-loss`：如果新结构需要删列，服务会启动失败并报错，
而不是静默清掉线上数据。确认可以丢数据时，手动执行：

```bash
sudo docker compose exec api npx prisma db push --accept-data-loss
```

## 十一、常见问题

**外部访问超时，服务器本机 curl 正常**
标准路径先检查安全组/防火墙是否放行 80/443；仅回滚到 IP + HTTP 时才是 3000 未放行。见 2.2。

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
1. 先确认健康检查通：标准路径 `curl -fsS https://<域名>/healthz`；回滚到 IP + HTTP 时见第六节。
2. 若服务正常但 App 报 `CLEARTEXT communication not permitted`，
   说明 `res/xml/network_security_config.xml` 里没放行该地址。
3. 真机连电脑局域网调试时，需把电脑局域网 IP 也加进该文件并重新打包。

**上传的 APK 重启后消失**
`releases-data` 卷被 `down -v` 删掉了，或部署时用的旧 compose（没有挂该卷）。

## 十二、本地开发

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