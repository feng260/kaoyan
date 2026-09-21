import helmet from 'koa-helmet'
import { Context, Next } from 'koa'
import { env } from '../config/env'

/**
 * 安全头(koa-helmet)+ 轻量 CORS。
 * 纯 JSON API 无 HTML 渲染,XSS 面主要在存储型内容 → 同步数据原样进出、
 * 由客户端渲染层负责转义(Compose Text 天然转义),服务端不返回任何 HTML。
 */
export function securityMiddleware() {
  const helmetKoa = helmet()
  return async (ctx: Context, next: Next) => {
    const origin = ctx.get('Origin')
    if (env.corsOrigins.length === 0) {
      // 开发模式:放开跨域
      ctx.set('Access-Control-Allow-Origin', '*')
    } else if (origin && env.corsOrigins.includes(origin)) {
      // 白名单模式:仅放行命中来源,并声明 Vary 便于缓存区分
      ctx.set('Access-Control-Allow-Origin', origin)
      ctx.set('Vary', 'Origin')
    }
    ctx.set('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS')
    ctx.set('Access-Control-Allow-Headers', 'Content-Type,Authorization')
    if (ctx.method === 'OPTIONS') {
      ctx.status = 204
      return
    }
    await helmetKoa(ctx, next)
  }
}
