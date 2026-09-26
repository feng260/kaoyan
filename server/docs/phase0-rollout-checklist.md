# Phase 0 上线执行清单

执行人：________  时间：________  域名：________  变更单/镜像标识：________

## 前置确认

- [ ] 已获得服务器访问权限、域名 DNS 控制权、80/443 安全组与防火墙配置权限；安排维护窗口与回滚负责人。
- [ ] 记录现有 `docker compose images`、`docker compose ps`、客户端版本及旧镜像 ID/可回退镜像标签；保留旧镜像，禁止 `docker compose down -v`。
- [ ] 确认 `/opt/kaoyan/server/.env` 权限受限，`MYSQL_ROOT_PASSWORD`、`JWT_SECRET`、`ADMIN_TOKEN` 已配置；`TRUST_PROXY` 当前仍为 `false`。
- [ ] 安装定时备份，手动备份一次，检查 `gzip -t`，执行一次隔离恢复演练并记录 `SELECT COUNT(*) FROM yanzhong.users` 的结果；保留备份路径与校验值。生产恢复须另走事故流程。
- [ ] 检查 `releases-data` 和 `mysql-data` 数据卷均存在，旧 APK 可下载；确认磁盘剩余空间足够容纳备份及临时 MySQL 容器。

## 部署与切换

- [ ] 部署候选服务，检查 `docker compose ps`、`docker compose logs --tail=100 api` 和 `curl -fsS http://127.0.0.1:3000/healthz`。
- [ ] DNS A/AAAA 记录指向服务器；确保 DNS 解析和 IPv6（如有）均指向实际服务节点。
- [ ] 先在安全组/防火墙开放 80/443，安装 Caddy，替换 `deploy/Caddyfile.example` 中域名，校验配置并启动；验证证书和 `curl -fsS https://<域名>/healthz`。
- [ ] 将 Compose 的 `ports` 设为 `127.0.0.1:3000:3000` 并重建 API；检查公网 `:3000` 不可达且域名 HTTPS 正常，再关闭 3000 对公网的安全组/防火墙入口。
- [ ] 验证 Caddy 覆写转发 IP 头且 3000 仅本机监听后，才将 Compose `TRUST_PROXY` 设为 `"true"` 并重建 API；验证登录限流、真实 IP 与 WebSocket 连接。
- [ ] 经 HTTPS 检查 `/docs`、`/admin`、`/api/v1/app/latest` 和已发布版本的 `/api/v1/app/download/<versionCode>`（可用 `curl -I` 检查响应/Range）；检查 APK 下载与上传权限。
- [ ] Android `DEFAULT_SERVER_URL` 更新为 `https://<域名>` 并发布新包；移除旧公网 IP 的明文网络许可，真机检查登录、云同步、计划、下载和 WebSocket。

## 观察与回滚

- [ ] 记录部署后健康检查、错误率/限流异常、MySQL/API 日志、磁盘空间、备份作业结果及多端冒烟结果；通知维护负责人结束窗口。
- [ ] 遇到健康检查持续失败、数据库迁移失败或数据不一致、登录/同步/计划关键链路失败、证书失败或回源 IP 不可信，停止放量并启动回滚。
- [ ] 回滚应用到已记录的旧镜像标签/ID 并重建（不要用未经固定的 `latest`），保留 `mysql-data`、`releases-data`；重验旧版健康检查与登录/同步。若新 schema 与旧镜像不兼容，停止写入，先评估数据库恢复，不要自动覆盖生产库。
- [ ] 若仅 HTTPS 路由故障，优先修复 Caddy/DNS；确需退回 IP+HTTP 内测时，先把 `TRUST_PROXY=false`、确认 3000 暴露风险并恢复临时安全组规则，告知用户明文传输风险，安排再次切换。
- [ ] 记录回滚时间、旧镜像标识、受影响用户、数据库状态与后续处置。

线上执行未完成前，以上勾选项保持未勾选；仅有模板和本地语法检查不能视为已上线。
