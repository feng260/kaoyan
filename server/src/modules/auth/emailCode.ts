import crypto from 'crypto'
import { env } from '../../config/env'
import { sha256 } from '../../shared/jwt'

export type EmailCodePurpose = 'register' | 'reset'

const CODE_TTL_MS = 10 * 60_000
export const MAX_CODE_ATTEMPTS = 5

export function normalizeEmail(raw: string): string {
  return raw.trim().toLowerCase()
}

export function generateCode(): string {
  return crypto.randomInt(0, 1_000_000).toString().padStart(6, '0')
}

/** 绑定邮箱与用途的哈希:库里没有可重放的明文验证码 */
export function hashCode(code: string, email: string, purpose: EmailCodePurpose): string {
  return sha256(`${code.trim()}:${normalizeEmail(email)}:${purpose}:${env.jwtSecret}`)
}

type Row = {
  id: number
  email: string
  purpose: string
  codeHash: string
  expiresAt: bigint | number
  attempts: number
  consumedAt: bigint | number | null
  createdAt: bigint | number
}

export interface EmailCodeDb {
  emailCode: {
    deleteMany(args: any): Promise<unknown>
    create(args: any): Promise<unknown>
    findFirst(args: any): Promise<Row | null>
    update(args: any): Promise<unknown>
  }
}

export function createEmailCodeStore(db: EmailCodeDb, clock: () => number = Date.now) {
  async function issue(email: string, purpose: EmailCodePurpose): Promise<string> {
    const normalized = normalizeEmail(email)
    await db.emailCode.deleteMany({ where: { email: normalized, purpose, consumedAt: null } })
    const code = generateCode()
    await db.emailCode.create({
      data: {
        email: normalized,
        purpose,
        codeHash: hashCode(code, normalized, purpose),
        expiresAt: clock() + CODE_TTL_MS,
        attempts: 0,
        consumedAt: null,
        createdAt: clock(),
      },
    })
    return code
  }

  async function verify(email: string, purpose: EmailCodePurpose, code: string): Promise<boolean> {
    const normalized = normalizeEmail(email)
    const row = await db.emailCode.findFirst({
      where: { email: normalized, purpose, consumedAt: null },
      orderBy: { id: 'desc' },
    })
    if (!row) return false
    if (Number(row.expiresAt) < clock() || row.attempts >= MAX_CODE_ATTEMPTS) return false
    if (row.codeHash !== hashCode(code, normalized, purpose)) {
      await db.emailCode.update({ where: { id: row.id }, data: { attempts: { increment: 1 } } })
      return false
    }
    await db.emailCode.update({ where: { id: row.id }, data: { consumedAt: clock() } })
    return true
  }

  return { issue, verify }
}
