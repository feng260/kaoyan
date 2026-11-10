import { prisma } from '../../shared/prisma'
import { env } from '../../config/env'
import type { UsageSummary } from '../../shared/llm/usage'

export type UsageCountRow = {
  userGuid?: string
  feature: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  estimated: boolean
  createdAt?: Date
}

export type UsageFeatureSummary = {
  feature: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  calls: number
  estimatedCalls: number
}

export type AdminUsageUser = {
  userGuid: string
  username: string
  nickname: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  calls: number
  lastCallAt: number
}

export type AdminUsage = {
  days: number
  totals: UsageSummary & { activeUsers: number }
  topUsers: AdminUsageUser[]
  byFeature: UsageFeatureSummary[]
  estimatedCostCny: number | null
}

const DAY_MS = 24 * 60 * 60_000

export function clampAdminDays(raw: unknown): number {
  const value = typeof raw === 'number' ? raw : Number(raw)
  if (!Number.isFinite(value)) return 30
  return Math.min(90, Math.max(1, Math.trunc(value)))
}

export function normalizeUtcOffset(raw: unknown): number {
  const value = typeof raw === 'number' ? raw : Number(raw)
  if (!Number.isInteger(value) || value < -720 || value > 840) return 480
  return value
}

/** 客户端时区偏移(分钟,东八区=480)下的「今天 0 点」对应的 UTC 时刻 */
export function dayStartForOffset(now: Date, offsetMinutes: number): Date {
  const shifted = new Date(now.getTime() + offsetMinutes * 60_000)
  return new Date(Date.UTC(shifted.getUTCFullYear(), shifted.getUTCMonth(), shifted.getUTCDate()) - offsetMinutes * 60_000)
}

/** 客户端时区偏移下的「本月 1 号 0 点」对应的 UTC 时刻 */
export function monthStartForOffset(now: Date, offsetMinutes: number): Date {
  const shifted = new Date(now.getTime() + offsetMinutes * 60_000)
  return new Date(Date.UTC(shifted.getUTCFullYear(), shifted.getUTCMonth(), 1) - offsetMinutes * 60_000)
}

export function summarizeRows(rows: UsageCountRow[]): UsageSummary {
  return {
    promptTokens: rows.reduce((sum, item) => sum + item.promptTokens, 0),
    completionTokens: rows.reduce((sum, item) => sum + item.completionTokens, 0),
    totalTokens: rows.reduce((sum, item) => sum + item.totalTokens, 0),
    calls: rows.length,
    estimatedCalls: rows.filter(item => item.estimated).length,
  }
}

export function summarizeByFeature(rows: UsageCountRow[]): UsageFeatureSummary[] {
  const grouped = new Map<string, UsageCountRow[]>()
  for (const item of rows) {
    const list = grouped.get(item.feature) ?? []
    list.push(item)
    grouped.set(item.feature, list)
  }
  return [...grouped.entries()]
    .map(([feature, list]) => ({ feature, ...summarizeRows(list) }))
    .sort((a, b) => b.totalTokens - a.totalTokens)
}

export function estimateCostCny(rows: UsageCountRow[], priceInPer1M: number, priceOutPer1M: number): number | null {
  if (priceInPer1M <= 0 && priceOutPer1M <= 0) return null
  const prompt = rows.reduce((sum, item) => sum + item.promptTokens, 0)
  const completion = rows.reduce((sum, item) => sum + item.completionTokens, 0)
  return Number((prompt / 1_000_000 * priceInPer1M + completion / 1_000_000 * priceOutPer1M).toFixed(2))
}

/** 纯聚合:管理用量页的全部数字从这里出,便于单测覆盖区间/分组/排序 */
export function buildAdminUsage(input: {
  rows: UsageCountRow[]
  users: Array<{ guid: string; username: string; nickname?: string | null }>
  days: number
  now: Date
  priceInPer1M: number
  priceOutPer1M: number
}): AdminUsage {
  const from = new Date(input.now.getTime() - input.days * DAY_MS)
  const rows = input.rows.filter(item => !item.createdAt || item.createdAt >= from)
  const users = new Map(input.users.map(user => [user.guid, user]))
  const perUser = new Map<string, UsageCountRow[]>()
  for (const item of rows) {
    const guid = item.userGuid ?? ''
    const list = perUser.get(guid) ?? []
    list.push(item)
    perUser.set(guid, list)
  }
  const topUsers = [...perUser.entries()]
    .map(([guid, list]) => {
      const summary = summarizeRows(list)
      return {
        userGuid: guid,
        username: users.get(guid)?.username ?? '(已注销)',
        nickname: users.get(guid)?.nickname ?? '',
        promptTokens: summary.promptTokens,
        completionTokens: summary.completionTokens,
        totalTokens: summary.totalTokens,
        calls: summary.calls,
        lastCallAt: Math.max(...list.map(item => item.createdAt?.getTime() ?? 0)),
      }
    })
    .sort((a, b) => b.totalTokens - a.totalTokens)
    .slice(0, 100)
  return {
    days: input.days,
    totals: { ...summarizeRows(rows), activeUsers: perUser.size },
    topUsers,
    byFeature: summarizeByFeature(rows),
    estimatedCostCny: estimateCostCny(rows, input.priceInPer1M, input.priceOutPer1M),
  }
}

/** 用户本人用量:今日/本月按客户端时区计算,分项统计本月 */
export async function getUserUsage(userGuid: string, utcOffsetMinutes: number, now = new Date()) {
  const todayStart = dayStartForOffset(now, utcOffsetMinutes)
  const monthStart = monthStartForOffset(now, utcOffsetMinutes)
  const rows = await prisma.llmUsage.findMany({
    where: { userGuid, createdAt: { gte: monthStart } },
    select: {
      feature: true, promptTokens: true, completionTokens: true,
      totalTokens: true, estimated: true, createdAt: true,
    },
    orderBy: { createdAt: 'asc' },
  })
  const todayRows = rows.filter(item => item.createdAt >= todayStart)
  return {
    today: summarizeRows(todayRows),
    month: summarizeRows(rows),
    byFeature: summarizeByFeature(rows),
  }
}

export async function getAdminUsage(days: number, now = new Date()): Promise<AdminUsage> {
  const clamped = clampAdminDays(days)
  const from = new Date(now.getTime() - clamped * DAY_MS)
  const rows = await prisma.llmUsage.findMany({
    where: { createdAt: { gte: from } },
    select: {
      userGuid: true, feature: true, promptTokens: true, completionTokens: true,
      totalTokens: true, estimated: true, createdAt: true,
    },
  })
  const guids = [...new Set(rows.map(item => item.userGuid))]
  const users = guids.length
    ? await prisma.user.findMany({
        where: { guid: { in: guids } },
        select: { guid: true, username: true, nickname: true },
      })
    : []
  return buildAdminUsage({
    rows,
    users,
    days: clamped,
    now,
    priceInPer1M: env.llm.priceInPer1M,
    priceOutPer1M: env.llm.priceOutPer1M,
  })
}
