import http from 'http'
import { createApp } from './app'
import { env } from './config/env'
import { prisma } from './shared/prisma'
import { setupStatusWs } from './modules/status/ws'

async function main() {
  await prisma.$queryRaw`SELECT 1` // 连接自检,失败即快速退出
  const app = createApp()
  const server = http.createServer(app.callback())
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
