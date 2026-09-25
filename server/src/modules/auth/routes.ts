import Router from '@koa/router'
import { z } from 'zod'
import * as authService from './service'
import { strictLimit } from '../../middlewares/ratelimit'
import { requireAuth } from '../../middlewares/auth'

const router = new Router({ prefix: '/api/v1/auth' })

const registerSchema = z.object({
  username: z.string().min(3).max(32).regex(/^[a-zA-Z0-9_\u4e00-\u9fa5]+$/, '仅限中英文数字下划线'),
  password: z.string().min(8).max(64),
  // 邮箱可选。客户端用 kotlinx.serialization 且默认 explicitNulls=true,
  // "没填邮箱"时序列化出来的是 {"email": null} 而不是省略该键;
  // 而 zod 的 .optional() 只放行 undefined、遇到 null 会报 "Expected string",
  // 于是注册被直接打回 400。故用 .nullish() 同时放行 null/undefined,由 service 归一成 null。
  email: z.string().email().nullish(),
})

const loginSchema = z.object({
  username: z.string().min(1),
  password: z.string().min(1),
  deviceGuid: z.string().length(36),
  deviceName: z.string().min(1).max(64),
  platform: z.string().max(16).default('android'),
  rememberMe: z.boolean().optional(),
})

router.post('/register', strictLimit(), async ctx => {
  const body = registerSchema.parse(ctx.request.body)
  const user = await authService.register(body)
  ctx.status = 201
  ctx.body = { user }
})

router.post('/login', strictLimit(), async ctx => {
  const body = loginSchema.parse(ctx.request.body)
  const result = await authService.login({ ...body, ip: ctx.ip })
  ctx.body = result
})

router.post('/refresh', strictLimit(), async ctx => {
  const body = z.object({ refresh: z.string().min(20) }).parse(ctx.request.body)
  ctx.body = await authService.refresh(body)
})

router.post('/logout', requireAuth, async ctx => {
  await authService.logout(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
  ctx.status = 204
})

router.post('/password/forgot', strictLimit(), async ctx => {
  const body = z.object({ account: z.string().min(1) }).parse(ctx.request.body)
  ctx.body = await authService.forgotPassword(body.account)
})

router.post('/password/reset', strictLimit(), async ctx => {
  const body = z.object({ token: z.string().min(20), newPassword: z.string().min(8).max(64) })
    .parse(ctx.request.body)
  await authService.resetPassword(body.token, body.newPassword)
  ctx.status = 204
})

router.post('/password/change', requireAuth, async ctx => {
  const body = z.object({ oldPassword: z.string().min(1), newPassword: z.string().min(8).max(64) })
    .parse(ctx.request.body)
  await authService.changePassword(ctx.state.auth!.userGuid, body.oldPassword, body.newPassword)
  ctx.status = 204
})

export default router
