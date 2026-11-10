import test from 'node:test'
import assert from 'node:assert/strict'
import { reflowDay, type ReflowItem } from './reflow'
import type { PlanBrief } from './document'

const brief: PlanBrief = {
  summary: '', goals: [], constraints: [], focus: [], materials: [], notes: [],
  examSubjects: [], fixedCommitments: [], periods: [],
  availability: [{ weekday: 2, windows: [{ start: '08:00', end: '12:00' }, { start: '14:00', end: '18:00' }] }],
  availabilityConfirmed: true, commitmentsConfirmed: true,
}

function item(id: number, minutes: number, over: Partial<ReflowItem> = {}): ReflowItem {
  return {
    id, subject: '数学', title: `任务${id}`, minutes, status: 'pending',
    locked: false, startTime: null, slot: null, sortOrder: id, priority: id, ...over,
  }
}

const date = '2026-11-03' // 周二

test('reflowDay:done 与带时刻的锁定项先占位,其余按优先级放进最早可用空闲段', () => {
  const placements = reflowDay({
    date, brief,
    items: [
      item(1, 60, { status: 'done', startTime: '08:00', slot: 'morning' }),
      item(2, 60, { locked: true, startTime: '14:00', slot: 'afternoon' }),
      item(3, 90, { priority: 0 }),
      item(4, 120, { priority: 1 }),
    ],
  })
  assert.deepEqual(placements.map(p => [p.id, p.startTime]), [
    [1, '08:00'], [3, '09:00'], [2, '14:00'], [4, '15:00'],
  ])
  assert.deepEqual(placements.map(p => p.sortOrder), [0, 1, 2, 3])
})

test('reflowDay:放不进任何空闲段的任务保留最早时段并清空 startTime', () => {
  const placements = reflowDay({
    date, brief,
    items: [item(1, 300)],
  })
  assert.equal(placements.length, 1)
  assert.equal(placements[0].startTime, null)
  assert.equal(placements[0].slot, 'morning')
})

test('reflowDay:同一输入重复调用结果一致(幂等)', () => {
  const items = [item(1, 60), item(2, 90, { priority: 0 }), item(3, 45, { priority: 2 })]
  const first = reflowDay({ date, brief, items })
  const second = reflowDay({ date, brief, items })
  assert.deepEqual(first, second)
})

test('reflowDay:没有 brief 时返回空数组,由调用方跳过写回', () => {
  assert.deepEqual(reflowDay({ date, brief: null, items: [item(1, 60)] }), [])
})

const workdayBrief: PlanBrief = {
  ...brief,
  availability: [{ weekday: 3, windows: [
    { start: '08:30', end: '11:30' },
    { start: '14:00', end: '17:30' },
    { start: '19:00', end: '22:00' },
  ] }],
}

test('reflowDay:没空时段内不落任何任务(下午没空就只排上午和晚上)', () => {
  const placements = reflowDay({
    date: '2026-11-11', brief: workdayBrief,
    blocked: [{ start: 12 * 60, end: 18 * 60 }],
    items: [item(1, 60), item(2, 60), item(3, 60), item(4, 60)],
  })
  assert.deepEqual(placements.map(p => [p.id, p.startTime]), [
    [1, '08:30'], [2, '09:30'], [3, '10:30'], [4, '19:00'],
  ])
})

test('reflowDay:first-fit 留下无时间任务但存在完整排法时,回退重排把所有任务排进时段', () => {
  // 13 项共 570 分钟,恰好等于三段空闲;旧算法按顺序 first-fit 会在 25/25/40 的碎片里
  // 留下两条 45 分钟任务没有时间,但按容量划分是排得下的。
  const sizes = [40, 30, 45, 40, 50, 45, 45, 45, 50, 45, 45, 45, 45]
  const placements = reflowDay({
    date: '2026-11-11', brief: workdayBrief,
    items: sizes.map((minutes, index) => item(index + 1, minutes, { priority: index })),
  })
  assert.equal(placements.length, sizes.length)
  assert.ok(placements.every(p => p.startTime != null))
  const ranges = [[8 * 60 + 30, 11 * 60 + 30], [14 * 60, 17 * 60 + 30], [19 * 60, 22 * 60]]
  const used = placements
    .map(p => { const [h, m] = p.startTime!.split(':').map(Number); return [h * 60 + m, h * 60 + m + sizes[p.id - 1]] })
    .sort((a, b) => a[0] - b[0])
  for (const [start, end] of used) {
    assert.ok(ranges.some(([from, to]) => start >= from && end <= to), `${start}-${end} 不在空闲时段内`)
  }
  for (let i = 1; i < used.length; i++) assert.ok(used[i][0] >= used[i - 1][1], '任务时段重叠')
})
