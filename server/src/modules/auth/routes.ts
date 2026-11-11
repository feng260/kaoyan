import Router from '@koa/router'
import { z } from 'zod'
import * as authService from './service'
import { legalConsentSchema } from './consent'
import { clientAddress, accountKey, bodyField, rateLimit, strictLimit } from '../../middlewares/ratelimit'
import { requireAuth } from '../../middlewares/auth'
import { env } from '../../config/env'

const router = new Router({ prefix: '/api/v1/auth' })

const loginIpLimit = rateLimit({ name: 'login-ip', max: 30, windowMs: 15 * 60_000 })
const loginUserLimit = rateLimit({
  name: 'login-user',
  max: 10,
  windowMs: 15 * 60_000,
  key: ctx => accountKey(bodyField(ctx, 'username')),
})
const loginUserIpLimit = rateLimit({
  name: 'login-user-ip',
  max: 5,
  windowMs: 15 * 60_000,
  key: ctx => `${clientAddress(ctx, env.trustProxy)}:${accountKey(bodyField(ctx, 'username'))}`,
})

const registerIpLimit = rateLimit({ name: 'register-ip', max: 10, windowMs: 60 * 60_000 })
const registerEmailLimit = rateLimit({
  name: 'register-email',
  max: 5,
  windowMs: 60 * 60_000,
  key: ctx => accountKey(bodyField(ctx, 'email')),
})

const registerSchema = z.object({
  username: z.string().min(3).max(32).regex(/^[a-zA-Z0-9_\u4e00-\u9fa5]+$/, '仅限中英文数字下划线'),
  password: z.string().min(8).max(64),
  email: z.string().email(),
  code: z.string().regex(/^\d{6}$/, '验证码为 6 位数字'),
  // 客户端 explicitNulls=true,未填时下发 null,必须用 nullish 放行
  inviteCode: z.string().max(64).nullish(),
  // 协议同意:这里只校验类型,「必须为 true」的判定交给 assertLegalConsent,
  // 这样未勾选时客户端拿到的是 LEGAL_CONSENT_REQUIRED 而不是泛泛的 INVALID_PARAMS
  ...legalConsentSchema.shape,
})

const emailCodeSchema = z.object({
  email: z.string().email(),
  purpose: z.literal('register'),
  inviteCode: z.string().max(64).nullish(),
})

const forgotSchema = z.object({ email: z.string().email() })

const resetSchema = z.object({
  email: z.string().email(),
  code: z.string().regex(/^\d{6}$/),
  newPassword: z.string().min(8).max(64),
})

const codeIpLimit = rateLimit({ name: 'email-code-ip', max: 10, windowMs: 60 * 60_000 })
const codeEmailLimit = rateLimit({
  name: 'email-code-email',
  max: 3,
  windowMs: 60 * 60_000,
  key: ctx => accountKey(bodyField(ctx, 'email')),
})
const codeCooldown = rateLimit({
  name: 'email-code-cooldown',
  max: 1,
  windowMs: 60_000,
  key: ctx => accountKey(bodyField(ctx, 'email')),
})
const forgotIpLimit = rateLimit({ name: 'forgot-ip', max: 10, windowMs: 60 * 60_000 })
const forgotEmailLimit = rateLimit({
  name: 'forgot-email',
  max: 5,
  windowMs: 60 * 60_000,
  key: ctx => accountKey(bodyField(ctx, 'email')),
})
const resetIpLimit = rateLimit({ name: 'reset-ip', max: 10, windowMs: 60 * 60_000 })
const resetEmailLimit = rateLimit({
  name: 'reset-email',
  max: 5,
  windowMs: 60 * 60_000,
  key: ctx => accountKey(bodyField(ctx, 'email')),
})

const loginSchema = z.object({
  username: z.string().min(1),
  password: z.string().min(1),
  deviceGuid: z.string().length(36),
  deviceName: z.string().min(1).max(64),
  platform: z.string().max(16).default('android'),
  rememberMe: z.boolean().optional(),
})

router.post('/email/code', codeIpLimit, codeEmailLimit, codeCooldown, async ctx => {
  const body = emailCodeSchema.parse(ctx.request.body)
  await authService.requestRegisterCode(body)
  ctx.body = { sent: true }
})

router.post('/register', registerIpLimit, registerEmailLimit, async ctx => {
  const body = registerSchema.parse(ctx.request.body)
  const user = await authService.register(body)
  ctx.status = 201
  ctx.body = { user }
})

router.post('/login', loginIpLimit, loginUserLimit, loginUserIpLimit, async ctx => {
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

router.post('/password/forgot', forgotIpLimit, forgotEmailLimit, async ctx => {
  const body = forgotSchema.parse(ctx.request.body)
  ctx.body = await authService.forgotPassword(body.email)
})

router.post('/password/reset', resetIpLimit, resetEmailLimit, async ctx => {
  const body = resetSchema.parse(ctx.request.body)
  await authService.resetPasswordWithCode(body)
  ctx.status = 204
})

router.post('/password/change', requireAuth, async ctx => {
  const body = z.object({ oldPassword: z.string().min(1), newPassword: z.string().min(8).max(64) })
    .parse(ctx.request.body)
  await authService.changePassword(ctx.state.auth!.userGuid, body.oldPassword, body.newPassword)
  ctx.status = 204
})

export default router
