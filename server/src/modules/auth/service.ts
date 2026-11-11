import bcrypt from 'bcryptjs'
import { Prisma } from '@prisma/client'
import { prisma, num } from '../../shared/prisma'
import { signAccessToken, sha256, randomToken, uuid } from '../../shared/jwt'
import { env } from '../../config/env'
import { ApiError } from '../../middlewares/error'
import { assertLegalConsent, POLICY_VERSION } from './consent'
import { createEmailCodeStore, normalizeEmail } from './emailCode'
import { assertInvite } from './invite'
import { assertSmtpConfigured, sendMail, verificationMail } from '../../shared/mailer'

const now = () => Date.now()

/** 协议与同意门禁的实现放在 consent.ts(不依赖 Prisma,可直接单测),这里只做转发 */
export { POLICY_VERSION, POLICY_EFFECTIVE_DATE, assertLegalConsent } from './consent'
export type { LegalConsentInput } from './consent'

export interface TokenPair {
  access: string
  refresh: string
  expiresIn: number // refresh 有效期毫秒
}

export interface PublicUser {
  guid: string
  username: string
  email: string | null
  createdAt: number
  nickname: string | null
  avatarPreset: string | null
}

const publicUser = (u: { guid: string; username: string; email: string | null; createdAt: BigInt; nickname?: string | null; avatarPreset?: string | null }): PublicUser => ({
  guid: u.guid,
  username: u.username,
  email: u.email,
  createdAt: num(u.createdAt),
  nickname: u.nickname ?? null,
  avatarPreset: u.avatarPreset ?? null,
})

/** 为设备签发 access + refresh(refresh 落库存哈希) */
async function issueTokens(userGuid: string, deviceId: number, rememberMe: boolean): Promise<TokenPair> {
  const access = signAccessToken({ sub: userGuid, did: deviceId, jti: uuid() })
  const refresh = randomToken()
  const days = rememberMe ? env.refreshRememberDays : env.refreshDefaultDays
  const expiresAt = now() + days * 24 * 3600_000
  await prisma.refreshToken.create({
    data: { userGuid, deviceId, tokenHash: sha256(refresh), expiresAt, createdAt: now() },
  })
  return { access, refresh, expiresIn: days * 24 * 3600_000 }
}

const emailCodes = createEmailCodeStore(prisma)

/** 注册第一步:校验邀请码与邮箱占用后发验证码 */
export async function requestRegisterCode(input: {
  email: string
  inviteCode?: string | null
}): Promise<void> {
  assertSmtpConfigured()
  assertInvite(input.inviteCode)
  const email = normalizeEmail(input.email)
  const exists = await prisma.user.findUnique({ where: { email } })
  if (exists) throw new ApiError(409, 'CONFLICT', '该邮箱已被注册')
  const code = await emailCodes.issue(email, 'register')
  await sendMail({ to: email, ...verificationMail(code, 'register') })
}

/** email 必填:找回密码依赖它,注册即验证 */
export async function register(input: {
  username: string
  password: string
  email: string
  code: string
  inviteCode?: string | null
  termsAccepted?: boolean | null
  privacyAccepted?: boolean | null
}): Promise<PublicUser> {
  // 同意门禁放在服务层而非路由层:这是唯一的注册入口,谁调都绕不过去。
  assertLegalConsent(input)
  assertInvite(input.inviteCode)
  const email = normalizeEmail(input.email)
  const exists = await prisma.user.findFirst({
    where: { OR: [{ username: input.username }, { email }] },
  })
  if (exists) throw new ApiError(409, 'CONFLICT', '用户名或邮箱已被注册')
  const verified = await emailCodes.verify(email, 'register', input.code)
  if (!verified) throw new ApiError(400, 'INVALID_EMAIL_CODE', '邮箱验证码错误或已过期')
  try {
    const acceptedAt = new Date(now())
    const user = await prisma.user.create({
      data: {
        guid: uuid(),
        username: input.username,
        email,
        emailVerifiedAt: acceptedAt,
        acceptedPolicyVersion: POLICY_VERSION,
        passwordHash: await bcrypt.hash(input.password, 12),
        // 同意时间落库:日后要证明"用户当时同意了哪一版协议"时,这是唯一的凭据
        termsAcceptedAt: acceptedAt,
        privacyAcceptedAt: acceptedAt,
        createdAt: now(),
      },
    })
    return publicUser(user)
  } catch (e) {
    // 并发下先查后插仍可能撞唯一约束;P2002 视为重复注册而非 500
    if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002') {
      throw new ApiError(409, 'CONFLICT', '用户名或邮箱已被注册')
    }
    throw e
  }
}

export async function login(input: {
  username: string
  password: string
  deviceGuid: string
  deviceName: string
  platform: string
  rememberMe?: boolean
  ip?: string
}): Promise<{ user: PublicUser; device: { id: number; guid: string; name: string }; tokens: TokenPair }> {
  const user = await prisma.user.findUnique({ where: { username: input.username } })
  if (!user || !(await bcrypt.compare(input.password, user.passwordHash))) {
    throw new ApiError(401, 'INVALID_CREDENTIALS', '用户名或密码错误')
  }
  // 设备按 guid 幂等登记(同一手机重装/多设备各自独立)
  const device = await prisma.device.upsert({
    where: { deviceGuid: input.deviceGuid },
    create: {
      userGuid: user.guid,
      deviceGuid: input.deviceGuid,
      name: input.deviceName,
      platform: input.platform,
      lastIp: input.ip,
      lastActiveAt: now(),
      createdAt: now(),
    },
    update: { userGuid: user.guid, name: input.deviceName, lastIp: input.ip, lastActiveAt: now() },
  })
  const tokens = await issueTokens(user.guid, device.id, input.rememberMe ?? false)
  return {
    user: publicUser(user),
    device: { id: device.id, guid: device.deviceGuid, name: device.name },
    tokens,
  }
}

/** refresh 轮换:旧 token 吊销 + 签发新对;过期/吊销一律拒绝 */
export async function refresh(input: { refresh: string }): Promise<{ user: PublicUser; device: { id: number; name: string }; tokens: TokenPair }> {
  const record = await prisma.refreshToken.findUnique({ where: { tokenHash: sha256(input.refresh) } })
  if (!record || record.revokedAt !== null || num(record.expiresAt) < now()) {
    throw new ApiError(401, 'INVALID_REFRESH', '登录已过期,请重新登录')
  }
  // 原子吊销:并发复用同一 refresh 时仅一个请求能成功,其余视为已被使用而拒绝
  const revoked = await prisma.refreshToken.updateMany({
    where: { id: record.id, revokedAt: null },
    data: { revokedAt: now() },
  })
  if (revoked.count === 0) {
    throw new ApiError(401, 'INVALID_REFRESH', '登录已过期,请重新登录')
  }
  const [user, device] = await Promise.all([
    prisma.user.findUnique({ where: { guid: record.userGuid } }),
    prisma.device.findUnique({ where: { id: record.deviceId } }),
  ])
  if (!user || !device) throw new ApiError(401, 'INVALID_REFRESH', '登录已过期,请重新登录')
  // 滑动续期:通过原 token 的完整有效期判断是否 rememberMe(而非剩余时长,避免越接近过期越误判)
  const remember = num(record.expiresAt) - num(record.createdAt) > env.refreshDefaultDays * 24 * 3600_000
  const tokens = await issueTokens(user.guid, device.id, remember)
  return { user: publicUser(user), device: { id: device.id, name: device.name }, tokens }
}

export async function logout(userGuid: string, deviceId: number): Promise<void> {
  await prisma.refreshToken.updateMany({ where: { userGuid, deviceId, revokedAt: null }, data: { revokedAt: now() } })
}

export async function changePassword(userGuid: string, oldPassword: string, newPassword: string): Promise<void> {
  const user = await prisma.user.findUnique({ where: { guid: userGuid } })
  if (!user || !(await bcrypt.compare(oldPassword, user.passwordHash))) {
    throw new ApiError(400, 'WRONG_PASSWORD', '原密码错误')
  }
  await prisma.user.update({ where: { guid: userGuid }, data: { passwordHash: await bcrypt.hash(newPassword, 12) } })
  // 改密后强制全部设备下线(含本次),只保留当前会话由客户端重新登录
  await prisma.refreshToken.updateMany({ where: { userGuid, revokedAt: null }, data: { revokedAt: now() } })
}

/**
 * 找回密码第一步:统一返回成功,不暴露邮箱是否存在。
 * 未配置 SMTP 时在查询前就返回 503,避免成为账号探测通道。
 */
export async function forgotPassword(emailRaw: string): Promise<{ sent: true }> {
  assertSmtpConfigured()
  const email = normalizeEmail(emailRaw)
  const user = await prisma.user.findUnique({ where: { email } })
  if (!user) return { sent: true }
  const code = await emailCodes.issue(email, 'reset')
  try {
    await sendMail({ to: email, ...verificationMail(code, 'reset') })
  } catch {
    // sendMail 已按脱敏错误码记录日志;这里吞掉失败,保证「邮箱不存在」与「发送失败」响应一致
  }
  return { sent: true }
}

export async function resetPasswordWithCode(input: {
  email: string
  code: string
  newPassword: string
}): Promise<void> {
  const email = normalizeEmail(input.email)
  const verified = await emailCodes.verify(email, 'reset', input.code)
  if (!verified) throw new ApiError(400, 'INVALID_EMAIL_CODE', '验证码错误或已过期')
  const user = await prisma.user.findUnique({ where: { email } })
  if (!user) throw new ApiError(400, 'INVALID_EMAIL_CODE', '验证码错误或已过期')
  await prisma.user.update({
    where: { guid: user.guid },
    data: { passwordHash: await bcrypt.hash(input.newPassword, 12) },
  })
  // 强制所有设备下线
  await prisma.refreshToken.updateMany({ where: { userGuid: user.guid, revokedAt: null }, data: { revokedAt: now() } })
}
