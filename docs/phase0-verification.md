# 研钟 Phase 0 验收记录

- 验收日期：2026-09-25
- 验收范围：服务端、Android 客户端、运维脚本、方案文档
- 当前结论：代码闭环已完成，线上发布前仍需域名 HTTPS 切换、真实恢复演练和 Android 单测环境复跑

## 已通过

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| 服务端构建 | 通过 | `npm run build --prefix server`（`prisma generate && tsc`）退出码 0 |
| 服务端测试 | 通过 | `npm test --prefix server`，62/62 通过（含本轮新增 2 条回归用例） |
| 服务端类型检查 | 通过 | `npm run typecheck --prefix server` 退出码 0 |
| Prisma schema | 通过 | `npx prisma validate` 通过 |
| API 冒烟（本地实例） | 通过 | `npm run smoke:api --prefix server`，21/21 通过，覆盖注册同意、登录、档案读写、计划生成、active plan、导出脱敏、注销 |
| Android Kotlin 编译 | 通过 | `:app:compileDebugKotlin` 通过 |
| Android Debug APK | 通过 | `:app:assembleDebug` 成功 |
| 运维脚本语法 | 通过 | 使用 Git Bash 执行两个脚本的 `bash -n`，退出码 0 |
| Git 差异检查 | 通过 | `git diff --check` 通过 |
| 服务端计划回退 | 已实现 | active plan 缺失、请求失败或离线时保留本地 468 天计划 |
| 数据权利闭环 | 已实现 | 协议确认、数据导出、二次确认注销及服务端事务删除已落地 |

## 冒烟暴露并修复的缺陷

本地实例的端到端冒烟（`server/scripts/phase0-api-smoke.mjs`）第一次运行仅 12/21 通过，定位并修复了三个真实缺陷。这三处单元测试均未覆盖，因为测试直接以已归一化对象调用 service 层。

| 缺陷 | 根因 | 修复 | 回归验证 |
| --- | --- | --- | --- |
| `PUT /profile` 恒返回 400 `INVALID_PARAMS`（`examDate: Expected string, received date`） | 路由层已把 `examDate` 解析为 `Date`，service 层为防脏数据再次 `profileInputSchema.parse`，第二道防线把已合法数据判为格式错误 | `schemas.ts` 中 `examDateSchema` 改为幂等的 `z.preprocess`（`Date` 先转回 ISO 字符串再走同一管线） | 新增 2 条回归用例（`schemas.test.ts` 幂等性、`service.test.ts` 路由层归一化入参），服务端 62/62 通过 |
| 注销后旧 access token 仍可用（返回 200） | `requireAuth` 只校验 JWT 签名，不校验用户是否仍存在 | `middlewares/auth.ts` 增加 `prisma.user.findUnique` 存在性检查，用户不存在返回 401 | 冒烟 `注销后旧 token 失效` 由 200 变为 401 |
| 注销后重新登录被限流（返回 429） | `/account/delete` 与登录/注册共用 `strictLimit` 桶，注销耗尽配额 | `modules/account/routes.ts` 为注销单独配置 `deleteLimit`（5 次 / 15 分钟）桶 | 冒烟 `注销后无法登录` 由 429 变为 401 |

## 未完成

### Android 单测

` :app:testDebugUnitTest` 已完成 Kotlin 与测试源码编译，但 Gradle Test Executor 启动阶段失败，错误为测试 worker 的标准输入管道被关闭，随后报告 `ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain`。这不是业务编译错误；需要在本机关闭占用 Gradle worker 的进程或清理 Gradle worker 缓存后重新执行：

```powershell
cd YanZhong
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

### 线上切换

以下动作未在本轮执行，不能宣称已完成：

- 域名 DNS 指向服务器
- 防火墙开放 80/443 并保持 API 3000 仅本机监听
- Caddy 反向代理与 WebSocket 实际验证
- `TRUST_PROXY=true` 切换
- 客户端默认地址切换到 HTTPS 域名
- 移除公网 IP 的明文 HTTP 访问
- 真实 MySQL 备份恢复演练
- 注册、登录、计划生成、导出和注销的线上鉴权冒烟

## 发布前顺序

1. 在服务器执行一次备份并保留日志。
2. 使用 `server/scripts/restore-mysql-drill.sh` 导入备份到隔离演练容器，完成健康查询和行数核对。
3. 配置 DNS、Caddy 和 80/443 防火墙，确认 API 3000 未暴露公网。
4. 使用 HTTPS 域名完成注册、登录、档案保存、计划生成、active plan、数据导出和注销冒烟。
5. 更新 Android 默认服务地址，重新生成并安装 Debug APK 做最小流程验证。
6. 复跑 Android 单测，确认 Gradle worker 环境恢复正常后再关闭 Phase 0。

## 风险边界

当前 IP + HTTP 只允许内测，不应公开分发 APK。未完成线上 HTTPS 与恢复演练前，Phase 0 的正确状态是“代码与内测准备完成，生产发布待验收”，而不是“已正式上线”。
