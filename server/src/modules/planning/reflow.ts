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

export function reflowDay(input: { date: string; items: ReflowItem[]; brief: PlanBrief | null }): ReflowPlacement[] {
  if (!input.brief || input.items.length === 0) return []
  const slots = freeSlots(input.brief, new Date(`${input.date}T00:00:00.000Z`))
    .map(slot => ({ start: toMinutes(slot.start), end: toMinutes(slot.end), used: 0 }))
  if (slots.length === 0) return []

  const placements = new Map<number, ReflowPlacement>()
  // done 项与带开始时刻的锁定项视为固定占用:用户已经在那个时间做了/锁了,不能挪
  const fixed = input.items
    .filter(item => item.status === 'done' || (item.locked && item.startTime))
    .sort((a, b) => (a.startTime ?? '').localeCompare(b.startTime ?? '') || a.id - b.id)
  for (const item of fixed) {
    if (!item.startTime) continue
    const start = toMinutes(item.startTime)
    const end = start + item.minutes
    for (const slot of slots) {
      if (start >= slot.start && end <= slot.end) slot.used += item.minutes
    }
    placements.set(item.id, { id: item.id, slot: slotOf(start), startTime: item.startTime, sortOrder: 0 })
  }

  const rest = input.items
    .filter(item => !placements.has(item.id))
    .sort((a, b) => a.priority - b.priority || (a.startTime ?? '').localeCompare(b.startTime ?? '')
      || a.sortOrder - b.sortOrder || a.id - b.id)
  for (const item of rest) {
    const fitting = slots.find(slot => slot.end - slot.start - slot.used >= item.minutes)
    if (fitting) {
      const start = fitting.start + fitting.used
      fitting.used += item.minutes
      placements.set(item.id, { id: item.id, slot: slotOf(start), startTime: toClock(start), sortOrder: 0 })
    } else {
      const earliest = slots.reduce((best, slot) => slot.start < best.start ? slot : best, slots[0])
      placements.set(item.id, { id: item.id, slot: slotOf(earliest.start), startTime: null, sortOrder: 0 })
    }
  }

  return [...placements.values()]
    .sort((a, b) => (a.startTime ?? '99:99').localeCompare(b.startTime ?? '99:99') || a.id - b.id)
    .map((placement, index) => ({ ...placement, sortOrder: index }))
}
