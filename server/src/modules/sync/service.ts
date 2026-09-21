import { prisma, num } from '../../shared/prisma'
import { ApiError } from '../../middlewares/error'
import { schemas, table, parseResource, ResourceName } from './resources'
import { Prisma } from '@prisma/client'
import { z } from 'zod'

const now = () => Date.now()

// ---------- 增量同步 ----------

/**
 * 批量 upsert(LWW):UNQ(user_guid, client_guid) 命中且存量 updatedAt >= incoming → 跳过;
 * 否则整行覆盖,写入 updatedAt = max(serverNow, incoming)。
 * 复盘表无 client_guid,按自然键(epochDay / weekStartEpochDay) upsert。
 */
export async function upsertResource(userGuid: string, resource: string, rows: unknown[]) {
  const name = parseResource(resource)
  if (!name) throw new ApiError(404, 'NOT_FOUND', `未知资源 ${resource}`)
  if (!Array.isArray(rows)) throw new ApiError(400, 'INVALID_PARAMS', 'body 应为行数组')
  const schema = schemas[name] as z.ZodTypeAny
  const delegate = (prisma as any)[table[name]]

  let applied = 0
  let skipped = 0
  for (const raw of rows.slice(0, 2000)) { // 单批上限,防滥用
    const parsed = schema.safeParse(raw)
    if (!parsed.success) { skipped++; continue }
    const row: any = { ...parsed.data }
    delete row.userGuid
    // BigInt 列(stringify 前 Number 化)
    for (const key of ['colorArgb', 'targetAt', 'dueAt', 'completedAt', 'startedAt', 'endedAt']) {
      if (row[key] !== undefined && row[key] !== null) row[key] = BigInt(row[key])
    }
    row.updatedAt = BigInt(Math.max(Date.now(), parsed.data.updatedAt))

    // 查询命中键:client_guid 表 vs 自然键表
    const where = name === 'dailyReviews'
      ? { userGuid_epochDay: { userGuid, epochDay: parsed.data.epochDay } }
      : name === 'weeklyReviews'
        ? { userGuid_weekStartEpochDay: { userGuid, weekStartEpochDay: parsed.data.weekStartEpochDay } }
        : name === 'monthlyReviews'
          ? { userGuid_monthStartEpochDay: { userGuid, monthStartEpochDay: parsed.data.monthStartEpochDay } }
          : { userGuid_clientGuid: { userGuid, clientGuid: parsed.data.clientGuid } }

    const existing = await delegate.findUnique({ where })
    if (existing && num(existing.updatedAt) >= parsed.data.updatedAt) {
      skipped++ // LWW:存量更新,忽略旧写
      continue
    }
    const data = name === 'dailyReviews' || name === 'weeklyReviews'
      ? { ...row, userGuid }
      : { ...row, userGuid }
    if (existing) {
      await delegate.update({ where, data })
    } else {
      // 复盘表 upsert 用复合唯一键
      await delegate.upsert({ where, create: data, update: data })
    }
    applied++
  }
  return { applied, skipped, serverTime: now() }
}

/** 增量拉取:updated_at > since 的行(含墓碑);无 since = 全量 */
export async function pullChanges(userGuid: string, since: number, resources?: string[]) {
  const wanted: ResourceName[] = resources
    ? resources.map(parseResource).filter((r): r is ResourceName => r !== null)
    : ['subjects', 'countdownNodes', 'tasks', 'sessions', 'dailyReviews', 'weeklyReviews', 'monthlyReviews']
  const result: Record<string, any[]> = {}
  for (const name of wanted) {
    const delegate = (prisma as any)[table[name]]
    const rows = await delegate.findMany({
      where: { userGuid, updatedAt: { gt: BigInt(since) } },
      orderBy: { updatedAt: 'asc' },
      take: 5000,
    })
    result[name] = rows.map((r: any) => {
      const out: any = {}
      for (const [k, v] of Object.entries(r)) {
        out[k] = typeof v === 'bigint' ? num(v) : v
      }
      return out
    })
  }
  return { changes: result, serverTime: now() }
}

// ---------- 全量备份 / 恢复 ----------

/** 全量导出:格式对齐 App 端 ExportPayload 并补齐 reviews + settings */
export async function exportBackup(userGuid: string) {
  const [subjects, countdownNodes, tasks, sessions, dailyReviews, weeklyReviews, monthlyReviews, settings] = await Promise.all([
    prisma.subject.findMany({ where: { userGuid, isDeleted: false } }),
    prisma.countdownNode.findMany({ where: { userGuid, isDeleted: false } }),
    prisma.task.findMany({ where: { userGuid, isDeleted: false } }),
    prisma.pomodoroSession.findMany({ where: { userGuid, isDeleted: false } }),
    prisma.dailyReview.findMany({ where: { userGuid } }),
    prisma.weeklyReview.findMany({ where: { userGuid } }),
    prisma.monthlyReview.findMany({ where: { userGuid } }),
    prisma.userSetting.findMany({ where: { userGuid } }),
  ])
  const strip = (rows: any[]) => rows.map(r => {
    const out: any = {}
    for (const [k, v] of Object.entries(r)) out[k] = typeof v === 'bigint' ? num(v) : v
    return out
  })
  return {
    version: 2,
    exportedAt: now(),
    subjects: strip(subjects),
    countdownNodes: strip(countdownNodes),
    tasks: strip(tasks),
    sessions: strip(sessions),
    dailyReviews: strip(dailyReviews),
    weeklyReviews: strip(weeklyReviews),
    monthlyReviews: strip(monthlyReviews),
    settings: Object.fromEntries(settings.map((s: { key: string; value: string }) => [s.key, JSON.parse(s.value)])),
  }
}

/** 全量恢复:事务内按用户清表(软删全部)后整包重插 */
export async function restoreBackup(userGuid: string, payload: any) {
  if (!payload || typeof payload !== 'object') {
    throw new ApiError(400, 'INVALID_PARAMS', '备份包格式错误')
  }
  await prisma.$transaction(async (tx: Prisma.TransactionClient) => {
    // 清空该用户现有数据(事务内)
    await Promise.all([
      tx.subject.deleteMany({ where: { userGuid } }),
      tx.countdownNode.deleteMany({ where: { userGuid } }),
      tx.task.deleteMany({ where: { userGuid } }),
      tx.pomodoroSession.deleteMany({ where: { userGuid } }),
      tx.dailyReview.deleteMany({ where: { userGuid } }),
      tx.weeklyReview.deleteMany({ where: { userGuid } }),
      tx.monthlyReview.deleteMany({ where: { userGuid } }),
      tx.userSetting.deleteMany({ where: { userGuid } }),
    ])
    // 重插(经与增量 upsert 相同的校验/映射)
    const res: Array<[string, any[]]> = [
      ['subjects', payload.subjects ?? []],
      ['countdownNodes', payload.countdownNodes ?? payload.nodes ?? []], // 兼容 App v1 本地导出的 nodes 字段
      ['tasks', payload.tasks ?? []],
      ['sessions', payload.sessions ?? []],
      ['dailyReviews', payload.dailyReviews ?? []],
      ['weeklyReviews', payload.weeklyReviews ?? []],
      ['monthlyReviews', payload.monthlyReviews ?? []],
    ]
    for (const [resource, rows] of res) {
      const name = parseResource(resource)!
      const schema = schemas[name] as z.ZodTypeAny
      const delegate = (tx as any)[table[name]]
      for (const raw of rows) {
        const parsed = schema.safeParse(raw)
        if (!parsed.success) continue
        const row: any = { ...parsed.data, userGuid }
        delete row.isDeleted
        for (const key of ['colorArgb', 'targetAt', 'dueAt', 'completedAt', 'startedAt', 'endedAt']) {
          if (row[key] !== undefined && row[key] !== null) row[key] = BigInt(row[key])
        }
        row.updatedAt = BigInt(Math.max(Date.now(), parsed.data.updatedAt || Date.now()))
        await delegate.create({ data: row })
      }
    }
    // 设置 KV
    if (payload.settings && typeof payload.settings === 'object') {
      for (const [key, value] of Object.entries(payload.settings)) {
        await tx.userSetting.create({
          data: { userGuid, key: key.slice(0, 64), value: JSON.stringify(value), updatedAt: now() },
        })
      }
    }
  })
  return { restored: true, serverTime: now() }
}

// ---------- 设置 ----------

export async function getSettings(userGuid: string) {
  const rows = await prisma.userSetting.findMany({ where: { userGuid } })
  const settings: Record<string, unknown> = {}
  for (const r of rows) {
    try { settings[r.key] = JSON.parse(r.value) } catch { settings[r.key] = r.value }
  }
  return { settings, serverTime: now() }
}

export async function putSettings(userGuid: string, body: { settings: Record<string, unknown> }) {
  if (!body?.settings || typeof body.settings !== 'object') {
    throw new ApiError(400, 'INVALID_PARAMS', 'body.settings 应为对象')
  }
  for (const [key, value] of Object.entries(body.settings).slice(0, 100)) {
    await prisma.userSetting.upsert({
      where: { userGuid_key: { userGuid, key: key.slice(0, 64) } },
      create: { userGuid, key: key.slice(0, 64), value: JSON.stringify(value), updatedAt: now() },
      update: { value: JSON.stringify(value), updatedAt: now() },
    })
  }
  return { serverTime: now() }
}
