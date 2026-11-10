import { freeSlots, type PlanBrief } from './document'

/**
 * 单日时段重排:日期/时长/任务集合变化后,重新给每条任务分配时段与开始时刻。
 * 纯函数、幂等:同一组输入重复调用结果一致,确认调整、撤销、手工编辑共用。
 */

export interface ReflowItem {
  id: number
  subject: string
  title: string
  minutes: number
  status: string
  locked?: boolean
  startTime?: string | null
  slot?: string | null
  sortOrder: number
  priority: number
}

export interface ReflowPlacement {
  id: number
  slot: string
  startTime: string | null
  sortOrder: number
}

const toMinutes = (time: string) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3))
const toClock = (minutes: number) =>
  `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`
const slotOf = (minutes: number) => Math.floor(minutes / 60) < 12 ? 'morning' : Math.floor(minutes / 60) < 18 ? 'afternoon' : 'evening'

/** 从空闲段里挖掉用户明确没空的时段(分钟,从 0 点起) */
function subtractBlocks(
  slots: Array<{ start: number; end: number; used: number }>,
  blocks: Array<{ start: number; end: number }>,
): Array<{ start: number; end: number; used: number }> {
  let current = slots
  for (const block of blocks) {
    const next: typeof current = []
    for (const slot of current) {
      if (block.end <= slot.start || block.start >= slot.end) { next.push(slot); continue }
      if (block.start > slot.start) next.push({ ...slot, end: block.start })
      if (block.end < slot.end) next.push({ start: block.end, end: slot.end, used: 0 })
    }
    current = next
  }
  return current
}

/** 在容量 capacity 内挑一组任务,使占用分钟数尽量大(0/1 子集和) */
function subsetWithin(items: ReflowItem[], capacity: number): ReflowItem[] {
  const reach = new Uint8Array(capacity + 1)
  const choice = new Int32Array(capacity + 1).fill(-1)
  reach[0] = 1
  for (let index = 0; index < items.length; index++) {
    const minutes = items[index].minutes
    if (minutes <= 0 || minutes > capacity) continue
    for (let sum = capacity; sum >= minutes; sum--) {
      if (!reach[sum] && reach[sum - minutes]) {
        reach[sum] = 1
        choice[sum] = index
      }
    }
  }
  let best = capacity
  while (best > 0 && !reach[best]) best--
  const picked: ReflowItem[] = []
  while (best > 0) {
    const index = choice[best]
    if (index < 0) break
    picked.push(items[index])
    best -= items[index].minutes
  }
  return picked
}

/** 按「每段空闲尽量填满」重新划分任务;只有全部排下才返回,否则交给原 first-fit 结果 */
function packAll(
  slots: Array<{ start: number; end: number; used: number }>,
  items: ReflowItem[],
): Map<number, { slot: string; startTime: string }> | null {
  const remaining = [...items]
  const placements = new Map<number, { slot: string; startTime: string }>()
  for (const slot of slots) {
    let cursor = slot.start + slot.used
    const free = slot.end - cursor
    if (free <= 0) continue
    const picked = subsetWithin(remaining, free)
    for (const item of picked) {
      placements.set(item.id, { slot: slotOf(cursor), startTime: toClock(cursor) })
      cursor += item.minutes
      remaining.splice(remaining.indexOf(item), 1)
    }
  }
  return remaining.length === 0 ? placements : null
}

export function reflowDay(input: {
  date: string
  items: ReflowItem[]
  brief: PlanBrief | null
  /** 当天明确没空的时段(分钟,从 0 点起);确认调整时由意图带上 */
  blocked?: Array<{ start: number; end: number }>
}): ReflowPlacement[] {
  if (!input.brief || input.items.length === 0) return []
  const slots = freeSlots(input.brief, new Date(`${input.date}T00:00:00.000Z`))
    .map(slot => ({ start: toMinutes(slot.start), end: toMinutes(slot.end), used: 0 }))
  if (slots.length === 0) return []
  const usable = subtractBlocks(slots, input.blocked ?? [])

  const placements = new Map<number, ReflowPlacement>()
  // done 项与带开始时刻的锁定项视为固定占用:用户已经在那个时间做了/锁了,不能挪
  const fixed = input.items
    .filter(item => item.status === 'done' || (item.locked && item.startTime))
    .sort((a, b) => (a.startTime ?? '').localeCompare(b.startTime ?? '') || a.id - b.id)
  for (const item of fixed) {
    if (!item.startTime) continue
    const start = toMinutes(item.startTime)
    const end = start + item.minutes
    for (const slot of usable) {
      if (start >= slot.start && end <= slot.end) slot.used += item.minutes
    }
    placements.set(item.id, { id: item.id, slot: slotOf(start), startTime: item.startTime, sortOrder: 0 })
  }
  const fixedUsage = usable.map(slot => ({ ...slot }))
  const working = usable.map(slot => ({ ...slot }))

  const rest = input.items
    .filter(item => !placements.has(item.id))
    .sort((a, b) => a.priority - b.priority || (a.startTime ?? '').localeCompare(b.startTime ?? '')
      || a.sortOrder - b.sortOrder || a.id - b.id)
  for (const item of rest) {
    const fitting = working.find(slot => slot.end - slot.start - slot.used >= item.minutes)
    if (fitting) {
      const start = fitting.start + fitting.used
      fitting.used += item.minutes
      placements.set(item.id, { id: item.id, slot: slotOf(start), startTime: toClock(start), sortOrder: 0 })
    } else {
      const earliest = (usable[0] ?? slots[0])
      placements.set(item.id, { id: item.id, slot: slotOf(earliest.start), startTime: null, sortOrder: 0 })
    }
  }

  // first-fit 没排下≠排不下:按每段空闲的容量重新划分一次,全部排下才采纳
  const leftovers = rest.filter(item => placements.get(item.id)?.startTime == null).length
  if (leftovers > 0 && usable.length > 0) {
    const packed = packAll(fixedUsage, rest)
    if (packed) {
      for (const item of rest) {
        const placement = packed.get(item.id)
        if (placement) placements.set(item.id, { id: item.id, ...placement, sortOrder: 0 })
      }
    }
  }

  return [...placements.values()]
    .sort((a, b) => (a.startTime ?? '99:99').localeCompare(b.startTime ?? '99:99') || a.id - b.id)
    .map((placement, index) => ({ ...placement, sortOrder: index }))
}
