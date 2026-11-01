import type { PlanBrief } from './document'
import { dailyCapacity } from './adjust'

/** 用户本地时区相对 UTC 的偏移(分钟);东八区 = 480 */
export const DEFAULT_TZ_OFFSET = 480
const MIN_TZ_OFFSET = -840
const MAX_TZ_OFFSET = 840
const DAY_MS = 24 * 60 * 60 * 1000

export interface CheckupItem {
  planDate: string | Date
  minutes: number | null
  status: string
}

export interface CheckupSession {
  startedAt: number | bigint
  durationMin: number | null
  valid: boolean
  isDeleted: boolean
}

export interface CheckupOverloadDay {
  date: string
  planned: number
  capacity: number
}

export interface CheckupSummary {
  behindMinutes: number
  overdueCount: number
  suggestion: string
  weekPlanDone: number
  weekPlanTotal: number
  weekFocusMinutes: number
  lastWeekFocusMinutes: number
  weekActiveDays: number
  next7dPlannedMinutes: number
  next7dCapacityMinutes: number
  overloadedDays: CheckupOverloadDay[]
}

export function normalizeTzOffset(raw: unknown): number {
  const value = typeof raw === 'number' ? raw
    : typeof raw === 'string' && raw.trim() !== '' ? Number(raw)
      : NaN
  if (!Number.isFinite(value) || value < MIN_TZ_OFFSET || value > MAX_TZ_OFFSET) {
    return DEFAULT_TZ_OFFSET
  }
  return Math.trunc(value)
}

export function shouldNotifyPlanReminder(raw: unknown): boolean {
  if (raw === undefined || raw === null || raw === '') return true
  const value = String(raw).trim().toLowerCase()
  return value !== '0' && value !== 'false'
}

export function emptyCheckup(): CheckupSummary {
  return {
    behindMinutes: 0,
    overdueCount: 0,
    suggestion: '',
    weekPlanDone: 0,
    weekPlanTotal: 0,
    weekFocusMinutes: 0,
    lastWeekFocusMinutes: 0,
    weekActiveDays: 0,
    next7dPlannedMinutes: 0,
    next7dCapacityMinutes: 0,
    overloadedDays: [],
  }
}

function shiftDay(day: string, days: number): string {
  return new Date(Date.parse(`${day}T00:00:00.000Z`) + days * DAY_MS).toISOString().slice(0, 10)
}

/** 周一=0 … 周日=6 */
function weekdayIndex(day: string): number {
  return (new Date(`${day}T00:00:00.000Z`).getUTCDay() + 6) % 7
}

function mondayOf(day: string): string {
  return shiftDay(day, -weekdayIndex(day))
}

function localDay(epochMs: number, tzOffset: number): string {
  return new Date(epochMs + tzOffset * 60_000).toISOString().slice(0, 10)
}

function dayOf(value: string | Date): string {
  return value instanceof Date ? value.toISOString().slice(0, 10) : String(value).slice(0, 10)
}

function inRange(day: string, from: string, to: string): boolean {
  return day >= from && day <= to
}

function trendClause(summary: CheckupSummary): string {
  const last = summary.lastWeekFocusMinutes
  const week = summary.weekFocusMinutes
  if (last <= 0) return week > 0 ? '本周已开始积累专注记录' : ''
  if (week === last) return '本周专注与上周持平'
  const pct = Math.round(Math.abs(week - last) * 100 / last)
  return `本周专注比上周${week > last ? '多' : '少'} ${pct}%`
}

function issueSuggestion(summary: CheckupSummary): string {
  const clauses: string[] = []
  if (summary.overdueCount > 0) {
    clauses.push(`有 ${summary.overdueCount} 项、约 ${summary.behindMinutes} 分钟的任务落在过去还没完成`)
  }
  if (summary.overloadedDays.length > 0) {
    clauses.push(`接下来 ${summary.overloadedDays.length} 天安排超过了可用时间`)
  }
  if (clauses.length === 0) return ''
  if (clauses.length === 1) {
    const trend = trendClause(summary)
    if (trend) clauses.push(trend)
    else if (summary.weekPlanTotal >= 3) {
      const rate = Math.round(summary.weekPlanDone / summary.weekPlanTotal * 100)
      clauses.push(`本周已按计划完成 ${rate}%`)
    }
  }
  return `${clauses.slice(0, 2).join('；')}。去调整一下。`
}

export function buildCheckup(input: {
  now: Date
  tzOffset: number
  items: CheckupItem[]
  sessions: CheckupSession[]
  brief: PlanBrief
}): CheckupSummary {
  const { now, items, sessions, brief } = input
  const tzOffset = normalizeTzOffset(input.tzOffset)
  const today = localDay(now.getTime(), tzOffset)
  const weekStart = mondayOf(today)
  const weekEnd = shiftDay(weekStart, 6)
  const lastWeekStart = shiftDay(weekStart, -7)
  const lastWeekEnd = shiftDay(weekStart, -1)
  const nextStart = shiftDay(today, 1)
  const nextEnd = shiftDay(today, 7)

  const summary = emptyCheckup()
  const overdue = items.filter(item => item.status !== 'done' && dayOf(item.planDate) < today)
  summary.overdueCount = overdue.length
  summary.behindMinutes = overdue.reduce((sum, item) => sum + Number(item.minutes ?? 0), 0)

  const plannedByDay = new Map<string, number>()
  for (const item of items) {
    const day = dayOf(item.planDate)
    if (inRange(day, weekStart, weekEnd)) {
      summary.weekPlanTotal++
      if (item.status === 'done') summary.weekPlanDone++
    }
    if (inRange(day, nextStart, nextEnd)) {
      const minutes = Number(item.minutes ?? 0)
      summary.next7dPlannedMinutes += minutes
      plannedByDay.set(day, (plannedByDay.get(day) ?? 0) + minutes)
    }
  }

  const activeDays = new Set<string>()
  for (const session of sessions) {
    if (!session.valid || session.isDeleted) continue
    const day = localDay(Number(session.startedAt), tzOffset)
    const minutes = Number(session.durationMin ?? 0)
    if (inRange(day, weekStart, weekEnd)) {
      summary.weekFocusMinutes += minutes
      if (minutes > 0) activeDays.add(day)
    } else if (inRange(day, lastWeekStart, lastWeekEnd)) {
      summary.lastWeekFocusMinutes += minutes
    }
  }
  summary.weekActiveDays = activeDays.size

  // 没确认空闲时间时容量是"未知",按 0 返回,让客户端展示"未确认空闲时段"而不是假分母
  if (brief.availabilityConfirmed) {
    for (let offset = 1; offset <= 7; offset++) {
      const day = shiftDay(today, offset)
      summary.next7dCapacityMinutes += dailyCapacity(brief, day)
    }
    for (let offset = 1; offset <= 7; offset++) {
      const day = shiftDay(today, offset)
      const capacity = dailyCapacity(brief, day)
      const planned = plannedByDay.get(day) ?? 0
      if (capacity > 0 && planned > capacity) {
        summary.overloadedDays.push({ date: day, planned, capacity })
      }
    }
  }

  summary.suggestion = issueSuggestion(summary)
  return summary
}
