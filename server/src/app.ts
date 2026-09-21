import Koa from 'koa'
import bodyParser from 'koa-bodyparser'
import fs from 'fs'
import path from 'path'
import { errorMiddleware, ApiError } from './middlewares/error'
import { securityMiddleware } from './middlewares/security'
import { env } from './config/env'
import authRoutes from './modules/auth/routes'
import userRoutes from './modules/user/routes'
import syncRoutes from './modules/sync/routes'
import docsRoutes from './modules/docs/routes'
import appRoutes from './modules/app/routes'
import adminRoutes from './modules/admin/routes'

/**
 * Koa 装配:中间件顺序 = 安全头/CORS → 错误捕获 → JSON 解析 → 路由。
 * 全部为原生 Koa 中间件签名,不引入任何 Express 包装层。
 */
export function createApp(): Koa {
  const app = new Koa()
  // 仅在部署于可信反代(会剥离 X-Forwarded-For)后置 true;直接暴露端口时打开会被伪造 XFF 绕过限流/伪造 IP
  app.proxy = env.trustProxy

  app.use(securityMiddleware())
  app.use(errorMiddleware())
  app.use(bodyParser({ enableTypes: ['json'], jsonLimit: '8mb' })) // 备份整包恢复需要较大 body

  app.use(authRoutes.routes()).use(authRoutes.allowedMethods())
  app.use(userRoutes.routes()).use(userRoutes.allowedMethods())
  app.use(syncRoutes.routes()).use(syncRoutes.allowedMethods())
  app.use(appRoutes.routes()).use(appRoutes.allowedMethods())
  app.use(adminRoutes.routes()).use(adminRoutes.allowedMethods())
  app.use(docsRoutes.routes()).use(docsRoutes.allowedMethods())

  // 管理后台界面(单文件静态页,零依赖)
  app.use(async (ctx, next) => {
    if (ctx.path === '/admin' || ctx.path === '/admin/') {
      const file = path.resolve(process.cwd(), 'public/admin.html')
      // 注意:koa 的 ctx.type 只接受 mime 简称,带 "; charset" 的写法会查找失败退化为 octet-stream
      ctx.type = 'html'
      ctx.set('Cache-Control', 'no-store') // 禁缓存:避免旧的 octet-stream 下载响应被浏览器记住
      // 单文件后台含内嵌 JS:覆盖 helmet 的默认 CSP(其 script-src 'self' 会静默拦截内联脚本,
      // 表现为"页面显示但按钮无反应");仅此页面放宽 inline,API 不受影响
      ctx.set('Content-Security-Policy',
        "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'")
      ctx.body = fs.createReadStream(file)
      return
    }
    await next()
  })

  // 根路径跳转到 API 文档,避免裸访问 3000 端口时页面空白
  app.use(async (ctx, next) => {
    if (ctx.path === '/') {
      ctx.redirect('/docs')
      return
    }
    await next()
  })

  // 健康检查
  app.use(async ctx => {
    if (ctx.path === '/healthz') {
      ctx.body = { ok: true, serverTime: Date.now() }
      return
    }
    throw new ApiError(404, 'NOT_FOUND', '接口不存在')
  })

  return app
}
