import { Context, Next } from 'koa'

/**
 * 内存令牌桶限流(单实例部署足够;上云多实例时换 Redis 实现,接口不变)。
 * 按客户端 IP + 桶名计数,超限返回 429。
 */
const buckets = new Map<string, { count: number; resetAt: number }>()

// 周期性清理避免内存增长
setInterval(() => {
  const now = Date.now()
  for (const [k, b] of buckets) if (b.resetAt < now) buckets.delete(k)
}, 60_000).unref()

export function rateLimit(opts: { name: string; max: number; windowMs: number }) {
  return async (ctx: Context, next: Next) => {
    // 用 socket 实际对端地址计数,可伪造的 X-Forwarded-For 无法绕过限流
    const peer = ctx.req.socket.remoteAddress ?? ctx.ip ?? 'unknown'
    const key = `${opts.name}:${peer}`
    const now = Date.now()
    const b = buckets.get(key)
    if (!b || b.resetAt < now) {
      buckets.set(key, { count: 1, resetAt: now + opts.windowMs })
    } else if (b.count >= opts.max) {
      ctx.status = 429
      ctx.set('Retry-After', String(Math.ceil((b.resetAt - now) / 1000)))
      ctx.body = { error: 'RATE_LIMITED', message: '请求过于频繁,请稍后再试' }
      return
    } else {
      b.count++
    }
    await next()
  }
}

/** 预设:登录/注册/找回等敏感接口 5 次/15 分钟 */
export const strictLimit = () => rateLimit({ name: 'strict', max: 5, windowMs: 15 * 60_000 })
/** 预设:普通接口 60 次/分钟 */
export const apiLimit = () => rateLimit({ name: 'api', max: 60, windowMs: 60_000 })
