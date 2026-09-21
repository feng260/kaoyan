import { Context, Next } from 'koa'
import { verifyAccessToken, JwtPayload } from '../shared/jwt'

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
  ctx.state.auth = { userGuid: payload.sub, deviceId: payload.did }
  await next()
}
