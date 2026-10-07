import http from 'http'
import { createApp } from './app'
import { env } from './config/env'
import { prisma } from './shared/prisma'
import { setupStatusWs } from './modules/status/ws'

async function main() {
  await prisma.$queryRaw`SELECT 1` // 连接自检,失败即快速退出
  const app = createApp()
  const server = http.createServer(app.callback())
  // Node 18+ 默认 requestTimeout=300s,会掐断多轮计划生成(L1 重试 + L2 并行细化最坏 ~10 分钟)。
  // 放宽到 15 分钟:仅长轮询/生成受影响,普通接口不受影响;滥用由全局 apiLimit 限流兜底。
  server.requestTimeout = 900_000
  setupStatusWs(server)
  server.listen(env.port, () => {
    console.log(`[yanzhong-server] listening on :${env.port} (${env.nodeEnv})`)
    console.log(`[yanzhong-server] api docs: http://localhost:${env.port}/docs`)
  })
}

main().catch(err => {
  console.error('[yanzhong-server] 启动失败:', err.message)
  process.exit(1)
})
