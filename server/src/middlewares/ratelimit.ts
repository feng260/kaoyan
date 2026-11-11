import crypto from 'crypto'
import { Context, Next } from 'koa'
import { env } from '../config/env'

/**
 * 内存令牌桶限流(单实例部署足够;上云多实例时换 Redis 实现,接口不变)。
 * 桶键 = 限流器名 + 作用域;作用域由调用方决定(IP、账号哈希或二者组合)。
 */
const buckets = new Map<string, { count: number; resetAt: number }>()

setInterval(() => {
  const now = Date.now()
  for (const [k, b] of buckets) if (b.resetAt < now) buckets.delete(k)
}, 60_000).unref()

/** 可信代理后用 ctx.ip(已含 XFF 解析结果);直连时只认 socket,防伪造 XFF 绕过 */
export function clientAddress(ctx: Context, trustProxy: boolean): string {
  return trustProxy ? ctx.ip : (ctx.req.socket.remoteAddress ?? ctx.ip ?? 'unknown')
}

/** 账号/邮箱只以哈希片段进入桶键,日志与内存里不出现明文 */
export function accountKey(raw: unknown): string {
  const value = typeof raw === 'string' ? raw.trim().toLowerCase() : ''
  return value ? crypto.createHash('sha256').update(value).digest('hex').slice(0, 16) : 'anonymous'
}

export function bucketKey(name: string, scope: string): string {
  return `${name}:${scope}`
}

/** 路由里取 body 字段做限流键;bodyparser 在路由之前完成解析 */
export function bodyField(ctx: Context, field: string): unknown {
  const body = ctx.request.body as Record<string, unknown> | undefined
  return body?.[field]
}

export type RateLimitOptions = {
  name: string
  max: number
  windowMs: number
  /** 不传则按真实 IP;传了则完全由调用方决定作用域 */
  key?: (ctx: Context) => string
}

export function rateLimit(opts: RateLimitOptions) {
  return async (ctx: Context, next: Next) => {
    const scope = opts.key ? opts.key(ctx) : clientAddress(ctx, env.trustProxy)
    const key = bucketKey(opts.name, scope)
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

/** 预设:敏感接口 5 次/15 分钟(仍按 IP;需要更细的桶在路由上显式声明) */
export const strictLimit = () => rateLimit({ name: 'strict', max: 5, windowMs: 15 * 60_000 })
/** 预设:普通接口 60 次/分钟 */
export const apiLimit = () => rateLimit({ name: 'api', max: 60, windowMs: 60_000 })
