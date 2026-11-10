import { Context, Next } from 'koa'
import { runWithAiUsage, type RunWithAiUsageOptions } from '../shared/llm/usage'

/**
 * AI 请求级计量:处理期间所有 chatComplete 调用记入本轮会话,请求结束后一次落库。
 * 必须注册在 requireAuth 之后;落库失败只告警,不改变请求结果。
 */
export function aiUsageMeter(options: RunWithAiUsageOptions = {}) {
  return async (ctx: Context, next: Next) => {
    const userGuid = ctx.state.auth?.userGuid
    if (!userGuid) {
      await next()
      return
    }
    await runWithAiUsage(userGuid, next, options)
  }
}
