import bcrypt from 'bcryptjs'
import nodemailer from 'nodemailer'
import { Prisma } from '@prisma/client'
import { prisma, num } from '../../shared/prisma'
import { signAccessToken, sha256, randomToken, uuid } from '../../shared/jwt'
import { env, smtpConfigured } from '../../config/env'
import { ApiError } from '../../middlewares/error'

const now = () => Date.now()

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
}

const publicUser = (u: { guid: string; username: string; email: string | null; createdAt: BigInt }): PublicUser => ({
  guid: u.guid,
  username: u.username,
  email: u.email,
  createdAt: num(u.createdAt),
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

/** email 允许 null:客户端"没填邮箱"时下发的是 null 而非省略键(见 routes.ts registerSchema) */
export async function register(input: { username: string; password: string; email?: string | null }): Promise<PublicUser> {
  const exists = await prisma.user.findFirst({
    where: { OR: [{ username: input.username }, ...(input.email ? [{ email: input.email }] : [])] },
  })
  if (exists) throw new ApiError(409, 'CONFLICT', '用户名或邮箱已被注册')
  try {
    const user = await prisma.user.create({
      data: {
        guid: uuid(),
        username: input.username,
        email: input.email ?? null,
        passwordHash: await bcrypt.hash(input.password, 12),
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
 * 找回密码:
 * - 生成一次性 token(30 分钟有效,仅存哈希),含 userId 的 reset 链接
 * - SMTP 未配置时降级:链接输出服务端日志(个人自部署场景)
 */
export async function forgotPassword(usernameOrEmail: string): Promise<{ sent: boolean }> {
  const user = await prisma.user.findFirst({
    where: { OR: [{ username: usernameOrEmail }, { email: usernameOrEmail }] },
  })
  // 统一返回成功,不泄漏账号是否存在
  if (!user) return { sent: true }
  const token = randomToken(32)
  await prisma.user.update({
    where: { guid: user.guid },
    data: { resetTokenHash: sha256(token), resetTokenExpiry: now() + 30 * 60_000 },
  })
  const link = `yanzhong://password-reset?token=${token}`
  if (smtpConfigured() && user.email) {
    const transport = nodemailer.createTransport({
      host: env.smtp.host, port: env.smtp.port, secure: env.smtp.secure,
      auth: { user: env.smtp.user, pass: env.smtp.pass },
    })
    await transport.sendMail({
      from: env.smtp.from, to: user.email, subject: '研钟 · 重置密码',
      text: `30 分钟内有效,点击或复制到 App 中打开:\n${link}\n若非本人操作请忽略。`,
    })
  } else {
    // 降级:个人部署无 SMTP,链接输出日志,从服务器日志取用
    console.log(`[password-reset] user=${user.username} link=${link}`)
  }
  return { sent: true }
}

export async function resetPassword(token: string, newPassword: string): Promise<void> {
  const user = await prisma.user.findFirst({ where: { resetTokenHash: sha256(token) } })
  if (!user || !user.resetTokenExpiry || num(user.resetTokenExpiry) < now()) {
    throw new ApiError(400, 'INVALID_TOKEN', '重置链接无效或已过期')
  }
  await prisma.user.update({
    where: { guid: user.guid },
    data: { passwordHash: await bcrypt.hash(newPassword, 12), resetTokenHash: null, resetTokenExpiry: null },
  })
  // 强制所有设备下线
  await prisma.refreshToken.updateMany({ where: { userGuid: user.guid, revokedAt: null }, data: { revokedAt: now() } })
}
