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
