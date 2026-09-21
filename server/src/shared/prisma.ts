import { PrismaClient } from '@prisma/client'

export const prisma = new PrismaClient()

/** BigInt 列(毫秒时间戳等)→ number(JSON 序列化安全,2^53 内) */
export const num = (v: unknown): number => Number(v ?? 0)
