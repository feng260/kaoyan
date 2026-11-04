import { datesBetween, diffSnapshots, shiftDate, type AdjustmentChange, type AdjustmentTier, type WindowSnapshotItem } from './adjust'
import type { PlanBrief } from './document'

/**
 * 多方案引擎:容量不够时不再只给一句「排不下」,而是给出几条可比较、可量化的处理方式。
 *
 * 四类方案按推荐顺序产出:
 *   compress       能全做完,只是每条短一点、部分顺延几天
 *   spill          任务一条不少,但溢出的部分挪到窗口之后(最多 14 天)
 *   drop           删掉最不重要的几条,其余原样
 *   lower_target   把本期目标整体调低,档案里的剩余量与里程碑一起下调
 *
 * 所有方案都是纯函数产物:after 是完整快照,确认后由 syncWindowItems 写库;
 * 锁定项与已完成项在每条方案里都必须原样保留。
 */

export const OPTION_MIN_MINUTES = 15
const OPTION_STEP_MINUTES = 5
const SPILL_MAX_DAYS = 14
const MAX_OPTIONS = 4

export interface AdjustmentImpact {
  affectedDays: number
  movedCount: number
  addedCount: number
  removedCount: number
  totalMinutesBefore: number
  totalMinutesAfter: number
  maxDayLoad: number
  maxDayCapacity: number
  shortfallMinutes: number
  milestoneRiskCount: number
  lockedKeptCount: number
}

export interface AdjustmentOption {
  id: 'compress' | 'spill' | 'drop' | 'lower_target' | 'manual'
  label: string
  summary: string
  tier: AdjustmentTier
  /** 完整目标窗口快照(含 done 与 locked) */
  after: WindowSnapshotItem[]
  changes: AdjustmentChange[]
  impact: AdjustmentImpact
  /** lower_target 专用:确认后要写回档案的 brief JSON */
  profileAfter?: string
}

export interface BuildOptionsInput {
  /** 窗口内全部计划项(含 done) */
  before: WindowSnapshotItem[]
  windowFrom: string
  windowTo: string
  examDate: string
  /** 窗口内每日净空闲(未扣 done/locked),key 为 YYYY-MM-DD */
  capacity: Map<string, number>
  /** 窗口之外某天的净空闲;用于 spill 的额外 14 天,调用方应扣掉当天已有任务 */
  dailyCapacity: (day: string) => number
  brief: PlanBrief
  intentKind: string
  weakSubjects?: string[]
}

interface Parts {
  done: WindowSnapshotItem[]
  locked: WindowSnapshotItem[]
  open: WindowSnapshotItem[]
}

function partsOf(before: WindowSnapshotItem[]): Parts {
  return {
    done: before.filter(item => item.status === 'done'),
    locked: before.filter(item => item.status !== 'done' && item.locked === true),
    open: before.filter(item => item.status !== 'done' && item.locked !== true),
  }
}

function totalMinutes(items: WindowSnapshotItem[]): number {
  return items.reduce((sum, item) => sum + Math.max(0, item.minutes), 0)
}

function roundStep(minutes: number): number {
  return Math.max(OPTION_MIN_MINUTES, Math.round(minutes / OPTION_STEP_MINUTES) * OPTION_STEP_MINUTES)
}

function sortByDateId(items: WindowSnapshotItem[]): WindowSnapshotItem[] {
  return [...items].sort((a, b) => a.planDate.localeCompare(b.planDate) || a.id - b.id)
}

/** 窗口内每天的剩余容量:净空闲先扣掉 done 与 locked(两者都不参与改动) */
function freeCapacity(input: BuildOptionsInput, fixed: WindowSnapshotItem[]): Map<string, number> {
  const free = new Map<string, number>()
  for (const day of datesBetween(input.windowFrom, input.windowTo)) {
    free.set(day, Math.max(0, Math.round(input.capacity.get(day) ?? 0)))
  }
  for (const item of fixed) {
    if (!free.has(item.planDate)) continue
    free.set(item.planDate, Math.max(0, (free.get(item.planDate) ?? 0) - item.minutes))
  }
  return free
}

function sumMap(map: Map<string, number>): number {
  let total = 0
  for (const value of map.values()) total += value
  return total
}

/** 顺延放置:优先原日期,放不下就找不早于原日期的最早可容纳日;放不下返回 null */
function placeGreedy(
  items: WindowSnapshotItem[],
  free: Map<string, number>,
  days: string[],
  sameMinutes: Map<number, number> = new Map(),
): WindowSnapshotItem[] | null {
  const ordered = sortByDateId(items)
  const placed: WindowSnapshotItem[] = []
  for (const item of ordered) {
    const minutes = sameMinutes.get(item.id) ?? item.minutes
    let target: string | null = null
    for (const day of days) {
      if (day < item.planDate) continue
      if ((free.get(day) ?? 0) >= minutes) { target = day; break }
    }
    if (!target) return null
    free.set(target, (free.get(target) ?? 0) - minutes)
    placed.push(target === item.planDate && minutes === item.minutes ? item : { ...item, planDate: target, minutes })
  }
  return placed
}

function milestoneOf(brief: PlanBrief): Map<string, { date: string; minutes: number }> {
  const map = new Map<string, { date: string; minutes: number }>()
  for (const subject of brief.examSubjects) {
    if (subject.milestoneDate && subject.milestoneMinutes > 0) {
      map.set(subject.name, { date: subject.milestoneDate, minutes: subject.milestoneMinutes })
    }
  }
  return map
}

function capacityOfDay(input: BuildOptionsInput, day: string): number {
  if (day >= input.windowFrom && day <= input.windowTo) return Math.max(0, input.capacity.get(day) ?? 0)
  return Math.max(0, Math.round(input.dailyCapacity(day)))
}

function buildImpact(
  input: BuildOptionsInput,
  before: WindowSnapshotItem[],
  after: WindowSnapshotItem[],
  changes: AdjustmentChange[],
): AdjustmentImpact {
  const affected = new Set<string>()
  for (const change of changes) {
    if (change.from) affected.add(change.from.planDate)
    if (change.to) affected.add(change.to.planDate)
  }
  const loads = new Map<string, number>()
  for (const item of after) loads.set(item.planDate, (loads.get(item.planDate) ?? 0) + item.minutes)
  let maxDayLoad = 0
  let maxDayCapacity = 0
  for (const [day, load] of loads) {
    if (load > maxDayLoad) {
      maxDayLoad = load
      maxDayCapacity = capacityOfDay(input, day)
    }
  }
  // 容量只统计「after 实际用到的日期」:spill 会把任务挪到窗口外,那些天的容量也要算进去
  let capacityTotal = 0
  for (const day of new Set(after.map(item => item.planDate))) capacityTotal += capacityOfDay(input, day)
  const totalAfter = totalMinutes(after)
  const milestones = milestoneOf(input.brief)
  let milestoneRiskCount = 0
  for (const [subject, milestone] of milestones) {
    const doneBefore = after
      .filter(item => item.subject === subject && item.planDate <= milestone.date)
      .reduce((sum, item) => sum + item.minutes, 0)
    if (doneBefore < milestone.minutes) milestoneRiskCount++
  }
  return {
    affectedDays: affected.size,
    movedCount: changes.filter(change => change.kind === 'moved').length,
    addedCount: changes.filter(change => change.kind === 'added').length,
    removedCount: changes.filter(change => change.kind === 'removed').length,
    totalMinutesBefore: totalMinutes(before),
    totalMinutesAfter: totalAfter,
    maxDayLoad,
    maxDayCapacity,
    shortfallMinutes: Math.max(0, totalAfter - capacityTotal),
    milestoneRiskCount,
    lockedKeptCount: partsOf(after).locked.length,
  }
}

function option(
  id: AdjustmentOption['id'],
  label: string,
  summary: string,
  tier: AdjustmentTier,
  input: BuildOptionsInput,
  before: WindowSnapshotItem[],
  after: WindowSnapshotItem[],
  profileAfter?: string,
): AdjustmentOption | null {
  const sorted = sortByDateId(after)
  const changes = diffSnapshots(before, sorted)
  if (changes.length === 0) return null
  return {
    id, label, summary, tier,
    after: sorted,
    changes,
    impact: buildImpact(input, before, sorted, changes),
    ...(profileAfter ? { profileAfter } : {}),
  }
}

/** 压缩:总量够就只缩短，日期不够就顺延；锁定项与完成项原样保留 */
function buildCompress(input: BuildOptionsInput, parts: Parts, free: Map<string, number>): AdjustmentOption | null {
  if (parts.open.length === 0) return null
  const totalFree = sumMap(free)
  const totalOpen = totalMinutes(parts.open)
  if (totalFree <= 0 || totalOpen <= 0) return null
  let scale = totalFree >= totalOpen ? 1 : totalFree / totalOpen
  let minutesById = new Map<number, number>()
  for (let guard = 0; guard < 20; guard++) {
    minutesById = new Map(parts.open.map(item => [item.id, scale >= 1 ? item.minutes : roundStep(item.minutes * scale)]))
    const total = [...minutesById.values()].reduce((sum, value) => sum + value, 0)
    if (total <= totalFree) break
    scale *= 0.9
  }
  const planned = [...minutesById.values()].reduce((sum, value) => sum + value, 0)
  if (planned > totalFree) return null
  const days = datesBetween(input.windowFrom, input.windowTo)
  const placed = placeGreedy(parts.open, new Map(free), days, minutesById)
  if (!placed) return null
  const after = [...parts.done, ...parts.locked, ...placed]
  return option('compress', '压缩任务时长',
    '把任务适度缩短并顺延，全部任务仍然保留',
    'L2', input, input.before, after)
}

/** 顺延:原日期放不下就挪到窗口后最多 14 天,绝不越过科目里程碑 */
function buildSpill(input: BuildOptionsInput, parts: Parts, free: Map<string, number>): AdjustmentOption | null {
  if (parts.open.length === 0) return null
  const extraFrom = shiftDate(input.windowTo, 1)
  const extraTo = shiftDate(input.windowTo, SPILL_MAX_DAYS) < shiftDate(input.examDate, -1)
    ? shiftDate(input.windowTo, SPILL_MAX_DAYS) : shiftDate(input.examDate, -1)
  if (extraFrom > extraTo) return null
  const extraDays = datesBetween(extraFrom, extraTo)
  const extraFree = new Map<string, number>(extraDays.map(day => [day, Math.max(0, Math.round(input.dailyCapacity(day)))]))
  if (sumMap(extraFree) <= 0) return null
  const milestones = milestoneOf(input.brief)
  const placed: WindowSnapshotItem[] = []
  let moved = 0
  for (const item of sortByDateId(parts.open)) {
    const milestone = milestones.get(item.subject)
    const ceiling = milestone && item.planDate <= milestone.date ? milestone.date : null
    if ((free.get(item.planDate) ?? 0) >= item.minutes) {
      free.set(item.planDate, (free.get(item.planDate) ?? 0) - item.minutes)
      placed.push(item)
      continue
    }
    const target = extraDays.find(day => (extraFree.get(day) ?? 0) >= item.minutes && (!ceiling || day <= ceiling))
    if (!target) return null
    extraFree.set(target, (extraFree.get(target) ?? 0) - item.minutes)
    placed.push({ ...item, planDate: target })
    moved++
  }
  if (moved === 0) return null
  const after = [...parts.done, ...parts.locked, ...placed]
  return option('spill', '顺延到之后',
    `把 ${moved} 项任务挪到之后 ${Math.min(SPILL_MAX_DAYS, extraDays.length)} 天内,一条不删`,
    'L2', input, input.before, after)
}

const DROP_ORDER_LABEL = '删掉最不影响目标的几条任务'

/** 删减:按天删最少的低优先级项;锁定项与弱科尽量保留 */
function buildDrop(input: BuildOptionsInput, parts: Parts, free: Map<string, number>): AdjustmentOption | null {
  if (parts.open.length === 0) return null
  const weak = new Set(input.weakSubjects ?? [])
  const milestones = milestoneOf(input.brief)
  const dropped = new Set<number>()
  for (const day of datesBetween(input.windowFrom, input.windowTo)) {
    const available = free.get(day) ?? 0
    const onDay = parts.open.filter(item => item.planDate === day)
    let load = onDay.reduce((sum, item) => sum + item.minutes, 0)
    if (load <= available) continue
    const ordered = [...onDay].sort((a, b) => {
      const weakA = weak.has(a.subject) ? 1 : 0
      const weakB = weak.has(b.subject) ? 1 : 0
      if (weakA !== weakB) return weakA - weakB
      const priorityA = a.priority ?? 0
      const priorityB = b.priority ?? 0
      if (priorityA !== priorityB) return priorityB - priorityA
      const milestoneA = milestones.has(a.subject) ? 1 : 0
      const milestoneB = milestones.has(b.subject) ? 1 : 0
      if (milestoneA !== milestoneB) return milestoneA - milestoneB
      if (a.planDate !== b.planDate) return b.planDate.localeCompare(a.planDate)
      return (b.sortOrder ?? 0) - (a.sortOrder ?? 0) || b.id - a.id
    })
    for (const item of ordered) {
      if (load <= available) break
      dropped.add(item.id)
      load -= item.minutes
    }
  }
  if (dropped.size === 0) return null
  const kept = parts.open.filter(item => !dropped.has(item.id))
  const after = [...parts.done, ...parts.locked, ...kept]
  // 删光了 open 项仍然超容量,说明是锁定/已完成项与新课表冲突,删任务解决不了
  const loads = new Map<string, number>()
  for (const item of after) loads.set(item.planDate, (loads.get(item.planDate) ?? 0) + item.minutes)
  for (const [day, load] of loads) {
    if (load > capacityOfDay(input, day)) return null
  }
  return option('drop', '删减部分任务',
    `${DROP_ORDER_LABEL}(共 ${dropped.size} 项)`,
    'L2', input, input.before, after)
}

function cloneBrief(brief: PlanBrief): PlanBrief {
  return JSON.parse(JSON.stringify(brief)) as PlanBrief
}

/** 调低目标:按科目比例削减剩余量,再从后往前删/缩任务,档案一起改 */
function buildLowerTarget(
  input: BuildOptionsInput,
  parts: Parts,
  free: Map<string, number>,
  compress: AdjustmentOption | null,
  spill: AdjustmentOption | null,
): AdjustmentOption | null {
  if (input.intentKind !== 'reduce_capacity' && input.intentKind !== 'change_commitment') return null
  // 只有前面的方案不够用时才建议动目标:压缩/顺延都可行且压缩不压出里程碑风险,就没必要下调目标
  const needed = compress === null || spill === null || compress.impact.milestoneRiskCount > 0
  if (!needed) return null
  const totalFree = sumMap(free)
  const totalOpen = totalMinutes(parts.open)
  if (totalOpen <= totalFree) return null
  const excess = totalOpen - totalFree
  const brief = cloneBrief(input.brief)
  const openBySubject = new Map<string, number>()
  for (const item of parts.open) openBySubject.set(item.subject, (openBySubject.get(item.subject) ?? 0) + item.minutes)
  const reducedBySubject = new Map<string, number>()
  for (const subject of brief.examSubjects) {
    const openMinutes = openBySubject.get(subject.name) ?? 0
    if (openMinutes <= 0) continue
    const cut = Math.min(subject.remainingMinutes, Math.round(excess * openMinutes / Math.max(1, totalOpen)))
    if (cut <= 0) continue
    subject.remainingMinutes = Math.max(0, subject.remainingMinutes - cut)
    subject.milestoneMinutes = Math.min(subject.milestoneMinutes, subject.remainingMinutes)
    reducedBySubject.set(subject.name, cut)
  }

  const removed: number[] = []
  let remaining = totalOpen
  const ordered = [...parts.open].sort((a, b) =>
    b.planDate.localeCompare(a.planDate) || (b.sortOrder ?? 0) - (a.sortOrder ?? 0) || b.id - a.id)
  for (const item of ordered) {
    if (remaining <= totalFree) break
    removed.push(item.id)
    remaining -= item.minutes
  }
  if (removed.length === 0) return null
  const removedSet = new Set(removed)
  const kept = parts.open.filter(item => !removedSet.has(item.id))
  const days = datesBetween(input.windowFrom, input.windowTo)
  const placed = placeGreedy(kept, new Map(free), days)
  if (!placed) return null

  // 按实际删掉的分钟数再校正一次档案,保证「档案剩余量 = 计划里还排着的量」不会越调越乱
  const removedBySubject = new Map<string, number>()
  for (const item of parts.open) {
    if (!removedSet.has(item.id)) continue
    removedBySubject.set(item.subject, (removedBySubject.get(item.subject) ?? 0) + item.minutes)
  }
  for (const subject of brief.examSubjects) {
    const gone = removedBySubject.get(subject.name) ?? 0
    if (gone <= 0) continue
    const already = reducedBySubject.get(subject.name) ?? 0
    const extra = Math.max(0, gone - already)
    subject.remainingMinutes = Math.max(0, subject.remainingMinutes - extra)
    subject.milestoneMinutes = Math.min(subject.milestoneMinutes, subject.remainingMinutes)
  }
  const after = [...parts.done, ...parts.locked, ...placed]
  return option('lower_target', '调低本期目标',
    `本期目标下调 ${excess} 分钟,减少 ${removed.length} 项任务,档案里的剩余量同步调整`,
    'L2', input, input.before, after, JSON.stringify(brief))
}

/**
 * 产出可行方案,顺序固定为 compress → spill → drop → lower_target。
 * 只保留 changes 非空且去重后不重复的方案,最多 4 条。
 */
export function buildAdjustmentOptions(input: BuildOptionsInput): AdjustmentOption[] {
  const parts = partsOf(input.before)
  const free = freeCapacity(input, [...parts.done, ...parts.locked])
  const compress = buildCompress(input, parts, new Map(free))
  const spill = buildSpill(input, parts, new Map(free))
  const drop = buildDrop(input, parts, new Map(free))
  const lower = buildLowerTarget(input, parts, new Map(free), compress, spill)
  const candidates = [compress, spill, drop, lower].filter((item): item is AdjustmentOption => item !== null)
  const seen = new Set<string>()
  const result: AdjustmentOption[] = []
  for (const candidate of candidates) {
    const key = JSON.stringify(candidate.changes)
    if (seen.has(key)) continue
    seen.add(key)
    result.push(candidate)
    if (result.length >= MAX_OPTIONS) break
  }
  return result
}
