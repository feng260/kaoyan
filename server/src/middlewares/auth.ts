import { Context, Next } from 'koa'
import { verifyAccessToken, JwtPayload } from '../shared/jwt'
import { prisma } from '../shared/prisma'

export interface AuthState {
  userGuid: string
  deviceId: number
}

declare module 'koa' {
  interface DefaultState {
    auth?: AuthState
  }
}

/** Bearer JWT 校验:通过后 ctx.state.auth = { userGuid, deviceId } */
export async function requireAuth(ctx: Context, next: Next) {
  const header = ctx.get('Authorization')
  const token = header.startsWith('Bearer ') ? header.slice(7) : ''
  const payload: JwtPayload | null = token ? verifyAccessToken(token) : null
  if (!payload) {
    ctx.status = 401
    ctx.body = { error: 'UNAUTHORIZED', message: '未登录或登录已过期' }
    return
  }
  // 账号一旦注销就不可逆:签发了但还没过期的 access token 必须当场作废。
  // 否则它会带着一个已不存在的 userGuid 继续写库,撞上外键约束变成 500 —— 注销拿 401 才是对的。
  const user = await prisma.user.findUnique({ where: { guid: payload.sub }, select: { guid: true } })
  if (!user) {
    ctx.status = 401
    ctx.body = { error: 'UNAUTHORIZED', message: '账号已注销,请重新登录' }
    return
  }
  ctx.state.auth = { userGuid: payload.sub, deviceId: payload.did }
  await next()
}
