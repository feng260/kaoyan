import { createHash } from 'node:crypto'
import { netAvailableMinutes, type PlanBrief } from './document'

/**
 * 行程调整的纯函数层:档位判定、L1 顺延、窗口快照差异与指纹。
 * 这里不碰数据库、不调模型 —— service 负责取数,模型负责意图,这里只管算。
 */

/** 调整档位:L1 = 只挪日期的小顺延;L2 = 允许增删改的重排 */
export type AdjustmentTier = 'L1' | 'L2'

/** 可以被算法挪动的窗口内待办项(排进算法前已剔除 done 与窗口外) */
export interface AdjustableItem {
  id: number
  subject: string
  title: string
  planDate: string
  minutes: number
  priority: number
  sortOrder: number
}

/** 调整窗口快照:确认时用它做指纹比对,撤销时用它还原 */
export interface WindowSnapshotItem {
  id: number
  subject: string
  title: string
  planDate: string
  minutes: number
  status: string
}

/** L1 的单条挪动:只改 planDate,其余字段原样保留 */
export interface L1Move {
  item: AdjustableItem
  from: string
  to: string
}

export interface L1Result {
  moves: L1Move[]
  /** 窗口内放到最后一天也放不下的项(按顺序溢出) */
  overflow: AdjustableItem[]
}

export interface L1Input {
  pending: AdjustableItem[]
  /** 每日剩余容量(已扣除当天 done 项),key 为 YYYY-MM-DD */
  capacity: Map<string, number>
  windowFrom: string
  windowTo: string
}

/** 给确认卡片看的单条变动 */
export interface AdjustmentChange {
  kind: 'moved' | 'added' | 'removed' | 'updated'
  id: number
  subject: string
  title: string
  from?: { planDate: string; minutes: number; title: string }
  to?: { planDate: string; minutes: number; title: string }
}

/** L1 的「影响天数」上限:被挪动任务涉及的 from∪to 日期数超过它就升级到 L2(默认 2 天,PLAN_L1_MAX_DAYS 可覆盖) */
export const L1_MAX_AFFECTED_DAYS = Number(process.env.PLAN_L1_MAX_DAYS ?? 2) || 2

/** 'YYYY-MM-DD' 加减天数,返回 'YYYY-MM-DD' */
export function shiftDate(day: string, days: number): string {
  const date = new Date(`${day}T00:00:00.000Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

/** 窗口内全部日期(含首尾);from > to 时返回空数组 */
export function datesBetween(from: string, to: string): string[] {
  const days: string[] = []
  let cursor = from
  while (cursor <= to) {
    days.push(cursor)
    cursor = shiftDate(cursor, 1)
  }
  return days
}

/** 某天容量 = min(每日目标分钟, 当天净空闲);无 brief 时退化为 dailyMinutes。与 assertGeneratedPlanValid 同口径 */
export function dailyCapacity(brief: PlanBrief | null, dailyMinutes: number, date: string): number {
  if (!brief) return Math.max(0, dailyMinutes)
  return Math.max(0, Math.min(dailyMinutes, netAvailableMinutes(brief, new Date(`${date}T00:00:00.000Z`))))
}

/**
 * L1 顺延:按原计划顺序逐项找「不早于原日期」的第一个有容量的日子放下。
 * 只顺延不提前、只改日期不改内容;放不进窗口的进 overflow,由 service 决定报缺口还是升级 L2。
 */
export function planL1Shuffle(input: L1Input): L1Result {
  const days = datesBetween(input.windowFrom, input.windowTo)
  const left = new Map(input.capacity)
  const moves: L1Move[] = []
  const overflow: AdjustableItem[] = []
  const ordered = [...input.pending].sort((a, b) =>
    a.planDate.localeCompare(b.planDate) || a.priority - b.priority
    || a.sortOrder - b.sortOrder || a.id - b.id)
  for (const item of ordered) {
    // 顺延不提前:最早只能落回它原本的日子(原本的日子已在窗口外时,从窗口头开始)
    const floor = item.planDate > input.windowFrom ? item.planDate : input.windowFrom
    let placed: string | null = null
    for (const day of days) {
      if (day < floor) continue
      if ((left.get(day) ?? 0) >= item.minutes) {
        left.set(day, (left.get(day) ?? 0) - item.minutes)
        placed = day
        break
      }
    }
    if (placed == null) overflow.push(item)
    else if (placed !== item.planDate) moves.push({ item, from: item.planDate, to: placed })
  }
  return { moves, overflow }
}

/** L1 影响天数:被挪动任务涉及的 from∪to 去重日期数 */
export function affectedDays(moves: L1Move[]): number {
  const days = new Set<string>()
  for (const move of moves) {
    days.add(move.from)
    days.add(move.to)
  }
  return days.size
}

/** 对比窗口前后快照,产出给人看的变动清单(按 日期 → 类型 → 科目 排序,输出稳定) */
export function diffSnapshots(before: WindowSnapshotItem[], after: WindowSnapshotItem[]): AdjustmentChange[] {
  const beforeById = new Map(before.filter(item => item.id > 0).map(item => [item.id, item]))
  const afterIds = new Set(after.filter(item => item.id > 0).map(item => item.id))
  const changes: AdjustmentChange[] = []
  for (const item of after) {
    const old = item.id > 0 ? beforeById.get(item.id) : undefined
    if (!old) {
      changes.push({ kind: 'added', id: item.id, subject: item.subject, title: item.title,
        to: { planDate: item.planDate, minutes: item.minutes, title: item.title } })
    } else if (old.planDate !== item.planDate) {
      changes.push({ kind: 'moved', id: item.id, subject: item.subject, title: item.title,
        from: { planDate: old.planDate, minutes: old.minutes, title: old.title },
        to: { planDate: item.planDate, minutes: item.minutes, title: item.title } })
    } else if (old.minutes !== item.minutes || old.title !== item.title || old.subject !== item.subject) {
      changes.push({ kind: 'updated', id: item.id, subject: item.subject, title: item.title,
        from: { planDate: old.planDate, minutes: old.minutes, title: old.title },
        to: { planDate: item.planDate, minutes: item.minutes, title: item.title } })
    }
  }
  for (const item of before) {
    if (item.id > 0 && !afterIds.has(item.id)) {
      changes.push({ kind: 'removed', id: item.id, subject: item.subject, title: item.title,
        from: { planDate: item.planDate, minutes: item.minutes, title: item.title } })
    }
  }
  const order = { moved: 0, updated: 1, added: 2, removed: 3 } as const
  return changes.sort((a, b) => {
    const dayA = a.to?.planDate ?? a.from!.planDate
    const dayB = b.to?.planDate ?? b.from!.planDate
    return dayA.localeCompare(dayB) || order[a.kind] - order[b.kind] || a.subject.localeCompare(b.subject)
  })
}

/**
 * 窗口指纹:调整单生成时记一份,确认时重算比对 —— 不一致说明窗口被人动过,调整单作废(409 ADJUSTMENT_STALE)。
 * done 项不参与:打卡随时发生,不该让一张调整单因为正常打卡而全部失效。
 */
export function windowFingerprint(items: WindowSnapshotItem[]): string {
  const payload = items
    .filter(item => item.id > 0 && item.status !== 'done')
    .map(item => `${item.id}:${item.planDate}:${item.minutes}`)
    .sort()
    .join('|')
  return createHash('sha256').update(payload).digest('hex')
}
