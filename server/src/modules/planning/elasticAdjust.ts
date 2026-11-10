import { AUTO_EXTEND_DAYS, datesBetween, shiftDate, type WindowSnapshotItem } from './adjust'
import type { AdjustImportanceHint, AdjustIntent } from './adjustIntent'

/**
 * 行程调整的弹性层:按重要性给任务定级,再只在被事件波及的范围内腾挪。
 * 这里全部是纯函数:不碰数据库、不调模型,方便把取舍规则单测到边界。
 */

export type Tier = 'fixed' | 'protect' | 'normal' | 'elastic'

export interface ElasticDemand {
  id: number
  /** 最早可落日期;通常等于原日期或今天 */
  earliest: string
  /** 用户指定目标日(move/add);undefined 表示由引擎挑选 */
  targetDate?: string
  reason: 'unavailable' | 'capacity' | 'overdue' | 'explicit' | 'added'
}

export interface ElasticInput {
  before: WindowSnapshotItem[]
  tiers: Map<number, Tier>
  demands: ElasticDemand[]
  /** 某天净空闲分钟数(netAvailable,briefAfter 口径) */
  capacity: (day: string) => number
  today: string
  windowTo: string
  hardTo: string
  /** 用户指定范围时为 false:不越过用户给的上限 */
  autoExtend: boolean
  milestones: Map<string, string>
}

export interface ElasticResult {
  after: WindowSnapshotItem[]
  touchedDays: string[]
  overflow: WindowSnapshotItem[]
  shrinkMinutes: number
  extendedTo: string
  notes: Map<number, string>
}

const round5 = (n: number) => Math.round(n / 5) * 5
const round5Down = (n: number) => Math.max(0, Math.floor(n / 5) * 5)
const ceil5 = (n: number) => Math.max(0, Math.ceil(n / 5) * 5)

/** 可缩下限:可让最多缩一半,普通最多缩 25%,固定/保护不缩 */
function floorFor(tier: Tier, baseMinutes: number): number {
  if (tier === 'elastic') return Math.max(15, round5(baseMinutes * 0.5))
  if (tier === 'normal') return Math.max(15, round5(baseMinutes * 0.75))
  return baseMinutes
}

const tierRank: Record<Tier, number> = { protect: 0, normal: 1, elastic: 2, fixed: 3 }

type Placed = { item: WindowSnapshotItem; day: string; minutes: number; base: number }
type State = { byId: Map<number, Placed>; load: Map<string, number> }

function cloneState(state: State): State {
  return {
    byId: new Map([...state.byId].map(([id, placed]) => [id, { ...placed }])),
    load: new Map(state.load),
  }
}

function loadOf(state: State, day: string): number {
  return state.load.get(day) ?? 0
}

function addPlacement(state: State, item: WindowSnapshotItem, day: string, minutes: number, base: number): void {
  state.byId.set(item.id, { item, day, minutes, base })
  state.load.set(day, loadOf(state, day) + minutes)
}

function removePlacement(state: State, id: number): void {
  const placed = state.byId.get(id)
  if (!placed) return
  state.load.set(placed.day, Math.max(0, loadOf(state, placed.day) - placed.minutes))
  state.byId.delete(id)
}

/** 把某天可让/普通项按级缩到还掉 need 分钟;做不到就原样返回 false */
function shrinkOthers(state: State, day: string, need: number, excludeIds: Set<number>, tiers: Map<number, Tier>): boolean {
  if (need <= 0) return true
  const candidates = [...state.byId.values()]
    .filter(placed => placed.day === day && !excludeIds.has(placed.item.id))
    .filter(placed => ['elastic', 'normal'].includes(tiers.get(placed.item.id) ?? 'normal'))
    .map(placed => ({ placed, tier: tiers.get(placed.item.id) ?? 'normal' as Tier }))
  const allowanceOf = (entry: { placed: Placed; tier: Tier }) =>
    Math.max(0, entry.placed.minutes - floorFor(entry.tier, entry.placed.base))
  const total = candidates.reduce((sum, entry) => sum + allowanceOf(entry), 0)
  if (total < need) return false
  candidates.sort((a, b) =>
    tierRank[b.tier] - tierRank[a.tier] || b.placed.minutes - a.placed.minutes || a.placed.item.id - b.placed.item.id)
  let remaining = ceil5(need)
  for (const entry of candidates) {
    if (remaining <= 0) break
    const cut = Math.min(allowanceOf(entry), remaining)
    if (cut <= 0) continue
    const next = Math.max(floorFor(entry.tier, entry.placed.base), entry.placed.minutes - cut)
    const actual = entry.placed.minutes - next
    if (actual <= 0) continue
    entry.placed.minutes = next
    state.load.set(day, Math.max(0, loadOf(state, day) - actual))
    remaining -= actual
  }
  return remaining <= 0
}

function ceilingFor(item: WindowSnapshotItem, milestones: Map<string, string>): string | undefined {
  const milestone = milestones.get(item.subject)
  return milestone && item.planDate <= milestone ? milestone : undefined
}

/**
 * 把一条任务放进候选日期:先找整块放得下的;再缩当天的可让/普通任务;再缩自己;
 * 最后把当天较不重要的任务后挤一天(最多 3 跳)。成功返回新状态,失败返回 null。
 */
function placeItem(
  state: State,
  item: WindowSnapshotItem,
  days: string[],
  depth: number,
  input: ElasticInput,
  horizonTo: string,
): State | null {
  if (days.length === 0) return null
  const ceiling = ceilingFor(item, input.milestones)
  const candidates = ceiling ? days.filter(day => day <= ceiling) : days
  if (candidates.length === 0) return null
  const tier = input.tiers.get(item.id) ?? 'normal'

  // 按日期顺序逐天试:能整块放下就放;放不下先缩当天的可让/普通任务。
  // 不先跳到后面的空白天——否则「重要任务」会被平白推后,而不是用低重要任务腾位置。
  for (const day of candidates) {
    if (loadOf(state, day) + item.minutes <= input.capacity(day)) {
      const next = cloneState(state)
      addPlacement(next, item, day, item.minutes, item.minutes)
      return next
    }
    const need = loadOf(state, day) + item.minutes - input.capacity(day)
    if (need <= 0) continue
    const next = cloneState(state)
    if (shrinkOthers(next, day, need, new Set([item.id]), input.tiers)) {
      addPlacement(next, item, day, item.minutes, item.minutes)
      return next
    }
  }

  // 所有日期都腾不出整块空间时,才考虑缩短需求任务自己(保护任务永不缩)
  if (tier !== 'protect' && tier !== 'fixed') {
    const floor = floorFor(tier, item.minutes)
    for (const day of candidates) {
      const free = input.capacity(day) - loadOf(state, day)
      if (free < floor) continue
      const minutes = Math.max(floor, Math.min(item.minutes, round5Down(free)))
      const next = cloneState(state)
      addPlacement(next, item, day, minutes, item.minutes)
      return next
    }
  }

  if (depth <= 0) return null
  for (const day of candidates) {
    const later = datesBetween(shiftDate(day, 1), horizonTo)
    if (later.length === 0) continue
    const movables = [...state.byId.values()]
      .filter(placed => placed.day === day && placed.item.id !== item.id)
      .filter(placed => {
        const placedTier = input.tiers.get(placed.item.id) ?? 'normal'
        return placedTier === 'elastic' || placedTier === 'normal'
      })
      .sort((a, b) => {
        const tierA = input.tiers.get(a.item.id) ?? 'normal'
        const tierB = input.tiers.get(b.item.id) ?? 'normal'
        return tierRank[tierB] - tierRank[tierA] || b.minutes - a.minutes || a.item.id - b.item.id
      })
      .slice(0, 3)
    for (const movable of movables) {
      const freed = cloneState(state)
      removePlacement(freed, movable.item.id)
      const moved = placeItem(freed, movable.item, later, depth - 1, input, horizonTo)
      if (!moved) continue
      if (loadOf(moved, day) + item.minutes > input.capacity(day)) continue
      addPlacement(moved, item, day, item.minutes, item.minutes)
      return moved
    }
  }
  return null
}

function runAttempt(input: ElasticInput, currentTo: string): {
  state: State
  overflow: WindowSnapshotItem[]
  extendedTo: string
} {
  const state: State = { byId: new Map(), load: new Map() }
  const demandIds = new Set(input.demands.map(demand => demand.id))
  for (const item of input.before) {
    if (demandIds.has(item.id)) continue
    addPlacement(state, item, item.planDate, item.minutes, item.minutes)
  }
  const itemsById = new Map(input.before.map(item => [item.id, item]))
  const overflow: WindowSnapshotItem[] = []
  const ordered = [...input.demands].sort((a, b) => {
    const tierA = input.tiers.get(a.id) ?? 'normal'
    const tierB = input.tiers.get(b.id) ?? 'normal'
    return tierRank[tierA] - tierRank[tierB]
      || a.earliest.localeCompare(b.earliest) || a.id - b.id
  })
  for (const demand of ordered) {
    const item = itemsById.get(demand.id)
    if (!item) continue
    const start = demand.earliest > input.today ? demand.earliest : input.today
    const days = demand.targetDate
      ? (demand.targetDate >= start && demand.targetDate <= input.hardTo ? [demand.targetDate] : [])
      : datesBetween(start, currentTo)
    const placed = placeItem(state, item, days, 3, input, currentTo)
    if (placed) {
      state.byId = placed.byId
      state.load = placed.load
    } else {
      overflow.push(item)
    }
  }
  return { state, overflow, extendedTo: currentTo }
}

function finalize(input: ElasticInput, attempt: ReturnType<typeof runAttempt>): ElasticResult {
  const { state, overflow, extendedTo } = attempt
  const beforeById = new Map(input.before.map(item => [item.id, item]))
  const after: WindowSnapshotItem[] = input.before.map(item => {
    const placed = state.byId.get(item.id)
    return placed ? { ...item, planDate: placed.day, minutes: placed.minutes } : item
  })
  for (const placed of state.byId.values()) {
    if (!beforeById.has(placed.item.id)) {
      after.push({ ...placed.item, planDate: placed.day, minutes: placed.minutes })
    }
  }
  after.sort((a, b) => a.planDate.localeCompare(b.planDate) || a.id - b.id)

  const touched = new Set<string>()
  const notes = new Map<number, string>()
  let shrinkMinutes = 0
  for (const item of after) {
    const old = beforeById.get(item.id)
    if (!old) continue
    if (old.planDate === item.planDate && old.minutes === item.minutes) continue
    touched.add(old.planDate)
    touched.add(item.planDate)
    const tier = input.tiers.get(item.id) ?? 'normal'
    if (old.minutes !== item.minutes) {
      shrinkMinutes += Math.max(0, old.minutes - item.minutes)
      notes.set(item.id, `缩短 ${old.minutes}→${item.minutes} 分钟（${tier === 'elastic' ? '可让' : '普通'}）`)
    } else if (old.planDate !== item.planDate) {
      notes.set(item.id, `顺延到 ${item.planDate}`)
    }
  }
  return {
    after,
    touchedDays: [...touched].sort(),
    overflow,
    shrinkMinutes,
    extendedTo,
    notes,
  }
}

export function planElasticAdjustment(input: ElasticInput): ElasticResult {
  let currentTo = input.windowTo
  const extendLimit = shiftDate(input.windowTo, AUTO_EXTEND_DAYS)
  for (;;) {
    const attempt = runAttempt(input, currentTo)
    if (attempt.overflow.length === 0) return finalize(input, attempt)
    const canExtend = input.autoExtend && currentTo < input.hardTo && currentTo < extendLimit
    if (!canExtend) return finalize(input, attempt)
    currentTo = shiftDate(currentTo, 1)
  }
}

/** 目标日超容量时只按重要性缩当天任务;用于 move/add 这类用户指定了日期的场景 */
export function shrinkDayByTier(
  before: WindowSnapshotItem[],
  day: string,
  capacity: number,
  tiers: Map<number, Tier>,
  excludeIds: Set<number> = new Set(),
): WindowSnapshotItem[] | null {
  const state: State = { byId: new Map(), load: new Map() }
  for (const item of before) addPlacement(state, item, item.planDate, item.minutes, item.minutes)
  const excess = loadOf(state, day) - capacity
  if (excess <= 0) return null
  // 排除用户明确新增的项:这些任务的时长是用户点名的,不能为了塞进去悄悄缩掉
  if (!shrinkOthers(state, day, excess, excludeIds, tiers)) return null
  return before.map(item => {
    const placed = state.byId.get(item.id)
    return placed ? { ...item, minutes: placed.minutes } : item
  })
}

/**
 * 把意图翻译成「待动项」:只有事件波及的任务进这次调整,其余一律钉住。
 * 这是「不整案重排」的关键——未来本来排得下的任务不会出现在这里。
 */
export function buildElasticDemands(input: {
  intent: AdjustIntent
  items: WindowSnapshotItem[]
  capacity: Map<string, number>
  tiers: Map<number, Tier>
  today: string
  windowTo: string
}): ElasticDemand[] {
  const { intent, items, capacity, tiers, today, windowTo } = input
  const demands: ElasticDemand[] = []
  const seen = new Set<number>()
  const tierOf = (item: WindowSnapshotItem): Tier => tiers.get(item.id) ?? 'normal'
  const onDay = (day: string) => items.filter(item => item.planDate === day)
  const push = (item: WindowSnapshotItem, reason: ElasticDemand['reason'], earliest: string) => {
    if (tierOf(item) === 'fixed' || seen.has(item.id)) return
    seen.add(item.id)
    demands.push({ id: item.id, earliest: earliest > today ? earliest : today, reason })
  }

  if (intent.kind === 'unavailable') {
    for (const day of intent.days) {
      if (day < today || day > windowTo) continue
      for (const item of onDay(day)) push(item, 'unavailable', day)
    }
    return demands
  }

  if (intent.kind === 'reduce_capacity' || intent.kind === 'change_commitment') {
    for (const day of datesBetween(today, windowTo)) {
      const dayItems = onDay(day)
      const load = dayItems.reduce((sum, item) => sum + item.minutes, 0)
      const cap = capacity.get(day) ?? 0
      let excess = load - cap
      if (excess <= 0) continue
      const ordered = dayItems
        .filter(item => tierOf(item) !== 'fixed')
        .sort((a, b) =>
          tierRank[tierOf(b)] - tierRank[tierOf(a)] || b.minutes - a.minutes || a.id - b.id)
      for (const item of ordered) {
        if (excess <= 0) break
        push(item, 'capacity', day)
        excess -= item.minutes
      }
    }
    return demands
  }

  if (intent.kind === 'reschedule') {
    if (intent.horizonDays != null) {
      // 用户明确说「重新安排这周/未来 N 天」:范围内的非保护项可以重排
      for (const item of items) {
        if (item.planDate < today || item.planDate > windowTo) continue
        if (tierOf(item) === 'protect') continue
        push(item, 'explicit', item.planDate)
      }
      return demands
    }
    // 默认重排:只动落后项与今天已超载的任务,未来可行项原地不动
    const todayItems = onDay(today)
    const todayLoad = todayItems.reduce((sum, item) => sum + item.minutes, 0)
    const todayCap = capacity.get(today) ?? 0
    for (const item of items) {
      if (tierOf(item) === 'fixed') continue
      if (item.planDate < today) push(item, 'overdue', today)
      else if (item.planDate === today && todayLoad > todayCap) push(item, 'capacity', today)
    }
  }
  return demands
}

/**
 * 定级:
 * - done/locked 永远固定;
 * - 用户本次明确「重要」> 明确「可让」> 弱科/窗口内里程碑默认保护 > 普通。
 * 只有用户话术能产生「可让」,系统不自行把任务判成低重要。
 */
export function rankImportance(
  items: WindowSnapshotItem[],
  input: { hints: AdjustImportanceHint[]; weakSubjects: string[]; milestones: Map<string, string>; windowTo: string },
): Map<number, Tier> {
  const explicit = new Map<number, 'protect' | 'elastic'>()
  for (const hint of input.hints) {
    for (const item of items) {
      if (item.subject !== hint.subject) continue
      if (hint.title && !item.title.includes(hint.title)) continue
      if (hint.date && item.planDate !== hint.date) continue
      const prev = explicit.get(item.id)
      if (hint.level === 'protect' || prev !== 'protect') explicit.set(item.id, hint.level)
    }
  }
  const weak = new Set(input.weakSubjects)
  const tiers = new Map<number, Tier>()
  for (const item of items) {
    if (item.status === 'done' || item.locked === true) {
      tiers.set(item.id, 'fixed')
      continue
    }
    const hint = explicit.get(item.id)
    if (hint) {
      tiers.set(item.id, hint)
      continue
    }
    const milestone = input.milestones.get(item.subject)
    if (weak.has(item.subject) || (milestone != null && milestone <= input.windowTo)) {
      tiers.set(item.id, 'protect')
      continue
    }
    tiers.set(item.id, 'normal')
  }
  return tiers
}