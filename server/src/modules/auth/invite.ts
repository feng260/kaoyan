import crypto from 'crypto'
import { ApiError } from '../../middlewares/error'

export function parseInviteCodes(raw: string | undefined): string[] {
  return (raw ?? '').split(',').map(s => s.trim()).filter(Boolean)
}

function configuredCodes(): string[] {
  return parseInviteCodes(process.env.INVITE_CODES)
}

function sha256(value: string): Buffer {
  return crypto.createHash('sha256').update(value).digest()
}

/** 恒时比较:长度不同也走同一条路径,不因提前返回泄露前缀信息 */
function sameSecret(a: string, b: string): boolean {
  return crypto.timingSafeEqual(sha256(a), sha256(b))
}

export function assertInvite(raw: unknown): void {
  const codes = configuredCodes()
  const nodeEnv = process.env.NODE_ENV ?? 'development'
  if (codes.length === 0) {
    // 本地/测试环境允许不配邀请码;生产必须显式配置,否则等同于公开注册
    if (nodeEnv !== 'production') return
    throw new ApiError(400, 'INVITE_REQUIRED', '当前需要邀请码才能注册')
  }
  const candidate = typeof raw === 'string' ? raw.trim() : ''
  if (!candidate) throw new ApiError(400, 'INVITE_REQUIRED', '请输入邀请码')
  const matched = codes.some(code => sameSecret(candidate, code))
  if (!matched) throw new ApiError(400, 'INVITE_INVALID', '邀请码无效')
}
