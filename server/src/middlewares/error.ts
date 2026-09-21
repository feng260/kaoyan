import { Context, Next } from 'koa'
import { ZodError } from 'zod'

/** 统一错误响应与日志:业务错误抛 ApiError,未知错误 500 且不泄漏内部细节 */
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message)
  }
}

export function errorMiddleware() {
  return async (ctx: Context, next: Next) => {
    try {
      await next()
    } catch (err) {
      if (err instanceof ApiError) {
        ctx.status = err.status
        ctx.body = { error: err.code, message: err.message }
      } else if (err instanceof ZodError) {
        ctx.status = 400
        ctx.body = { error: 'INVALID_PARAMS', message: '参数校验失败', details: err.errors.map(e => `${e.path.join('.')}: ${e.message}`) }
      } else {
        ctx.status = 500
        ctx.body = { error: 'INTERNAL', message: '服务器内部错误' }
        // 服务端日志保留完整堆栈;响应不含内部信息
        console.error(`[error] ${ctx.method} ${ctx.path}`, err)
      }
    }
  }
}
