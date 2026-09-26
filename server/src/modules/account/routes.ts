import Router from '@koa/router'
import { z } from 'zod'
import { requireAuth } from '../../middlewares/auth'
import { apiLimit, rateLimit } from '../../middlewares/ratelimit'
import { accountService } from './service'

const router = new Router({ prefix: '/api/v1/account' })

/**
 * 注销单独分桶,不跟登录/注册共用「strict」。
 * 否则登录时密码输错几次,15 分钟内连注销都做不了 —— 注销权不能被登录失败拖住。
 */
const deleteLimit = rateLimit({ name: 'account-delete', max: 5, windowMs: 15 * 60_000 })

const deleteSchema = z.object({
  // 输入类型先放过,「是否与用户名一致」由服务层判定,这样客户端能得到明确的 CONFIRM_MISMATCH
  confirm: z.string().max(64),
  password: z.string().min(1).max(64),
})

/** 导出全部数据:一次性返回 JSON,并带上下载文件名(浏览器直接访问也能存下来) */
router.get('/export', requireAuth, apiLimit(), async ctx => {
  const dump = await accountService.exportAccount(ctx.state.auth!.userGuid)
  const day = new Date(dump.exportedAt).toISOString().slice(0, 10)
  ctx.set('Content-Disposition', `attachment; filename="yanzhong-export-${day}.json"`)
  ctx.body = dump
})

/**
 * 注销账号:不可撤销,所以要求「完整用户名 + 当前密码」双重确认。
 * 限流仍然很严(5 次/15 分钟),但不给暴力尝试留余地这点由独立桶保证。
 */
router.post('/delete', requireAuth, deleteLimit, async ctx => {
  const body = deleteSchema.parse(ctx.request.body)
  const result = await accountService.deleteAccount({
    userGuid: ctx.state.auth!.userGuid,
    confirm: body.confirm,
    password: body.password,
  })
  ctx.body = { deleted: result.deleted, serverTime: Date.now() }
})

export default router
