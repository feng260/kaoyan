import Router from '@koa/router'
import { requireAuth } from '../../middlewares/auth'
import { getUserUsage, normalizeUtcOffset } from './service'

const router = new Router({ prefix: '/api/v1' })

/** 本人 AI token 用量:今日/本月按客户端时区计算,分项统计本月 */
router.get('/usage/ai', requireAuth, async ctx => {
  const offset = normalizeUtcOffset(ctx.query.utcOffsetMinutes)
  ctx.body = { ...await getUserUsage(ctx.state.auth!.userGuid, offset), serverTime: Date.now() }
})

export default router
