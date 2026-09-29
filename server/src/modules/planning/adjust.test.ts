import test from 'node:test'
import assert from 'node:assert/strict'
import {
  affectedDays, datesBetween, diffSnapshots, planL1Shuffle, shiftDate, windowFingerprint,
  type AdjustableItem, type WindowSnapshotItem,
} from './adjust'

function item(overrides: Partial<AdjustableItem> & { id: number; planDate: string; minutes: number }): AdjustableItem {
  return { subject: '数学', title: `任务${overrides.id}`, priority: 0, sortOrder: 0, ...overrides }
}

function snap(overrides: Partial<WindowSnapshotItem> & { id: number; planDate: string; minutes: number }): WindowSnapshotItem {
  return { subject: '数学', title: `任务${overrides.id}`, status: 'pending', ...overrides }
}

test('shiftDate/datesBetween 处理 UTC 日界与空窗口', () => {
  assert.equal(shiftDate('2026-09-29', 1), '2026-09-30')
  assert.equal(shiftDate('2026-09-30', 1), '2026-10-01')
  assert.deepEqual(datesBetween('2026-09-29', '2026-10-01'), ['2026-09-29', '2026-09-30', '2026-10-01'])
  assert.deepEqual(datesBetween('2026-10-02', '2026-10-01'), [])
})

test('L1:冲突日顺延到次日', () => {
  const result = planL1Shuffle({
    pending: [item({ id: 1, planDate: '2026-09-29', minutes: 60 })],
    capacity: new Map([['2026-09-29', 0], ['2026-09-30', 120]]),
    windowFrom: '2026-09-29',
    windowTo: '2026-09-30',
  })
  assert.equal(result.overflow.length, 0)
  assert.deepEqual(result.moves.map(move => [move.item.id, move.from, move.to]), [[1, '2026-09-29', '2026-09-30']])
})

test('L1:多项跨日填充,先来的先挑日子', () => {
  const result = planL1Shuffle({
    pending: [
      item({ id: 1, planDate: '2026-09-29', minutes: 100 }),
      item({ id: 2, planDate: '2026-09-29', minutes: 100 }),
      item({ id: 3, planDate: '2026-09-29', minutes: 50 }),
    ],
    capacity: new Map([['2026-09-29', 120], ['2026-09-30', 120], ['2026-10-01', 120]]),
    windowFrom: '2026-09-29',
    windowTo: '2026-10-01',
  })
  // id1 留在原日;id2 顺延到 30 日;id3 在 30 日只剩 20 放不下 → 10-01
  assert.deepEqual(result.moves.map(move => [move.item.id, move.to]), [[2, '2026-09-30'], [3, '2026-10-01']])
  assert.equal(result.overflow.length, 0)
})

test('L1:窗口末仍放不下 → overflow 报缺口', () => {
  const result = planL1Shuffle({
    pending: [item({ id: 1, planDate: '2026-09-29', minutes: 200 })],
    capacity: new Map([['2026-09-29', 0], ['2026-09-30', 120]]),
    windowFrom: '2026-09-29',
    windowTo: '2026-09-30',
  })
  assert.equal(result.moves.length, 0)
  assert.equal(result.overflow.length, 1)
  assert.equal(result.overflow[0].id, 1)
})

test('L1:只顺延不提前', () => {
  const result = planL1Shuffle({
    pending: [item({ id: 1, planDate: '2026-09-30', minutes: 60 })],
    capacity: new Map([['2026-09-29', 120], ['2026-09-30', 120]]),
    windowFrom: '2026-09-29',
    windowTo: '2026-09-30',
  })
  assert.equal(result.moves.length, 0)
  assert.equal(result.overflow.length, 0)
})

test('affectedDays:from∪to 去重计数', () => {
  const moves = [
    { item: item({ id: 1, planDate: '2026-09-29', minutes: 10 }), from: '2026-09-29', to: '2026-09-30' },
    { item: item({ id: 2, planDate: '2026-09-30', minutes: 10 }), from: '2026-09-30', to: '2026-10-01' },
  ]
  assert.equal(affectedDays(moves), 3)
})

test('diffSnapshots:moved/added/removed/updated 各归各位且排序稳定', () => {
  const before = [
    snap({ id: 1, planDate: '2026-09-29', minutes: 60 }),
    snap({ id: 2, planDate: '2026-09-29', minutes: 30 }),
    snap({ id: 3, planDate: '2026-09-30', minutes: 45 }),
  ]
  const after = [
    snap({ id: 1, planDate: '2026-09-30', minutes: 60 }),
    snap({ id: 2, planDate: '2026-09-29', minutes: 50, title: '改了名' }),
    snap({ id: 0, planDate: '2026-09-30', minutes: 30, title: '新增' }),
  ]
  const changes = diffSnapshots(before, after)
  // 09-29:updated(id2);09-30:moved(id1) → added(id0) → removed(id3)
  assert.deepEqual(changes.map(change => change.kind), ['updated', 'moved', 'added', 'removed'])
  const removed = changes.find(change => change.kind === 'removed')!
  assert.equal(removed.id, 3)
})

test('windowFingerprint:done 项不参与,内容变了指纹必变,顺序无关', () => {
  const base = [snap({ id: 1, planDate: '2026-09-29', minutes: 60 })]
  const withDone = [...base, snap({ id: 2, planDate: '2026-09-30', minutes: 30, status: 'done' })]
  assert.equal(windowFingerprint(base), windowFingerprint(withDone))
  assert.notEqual(windowFingerprint(base), windowFingerprint([snap({ id: 1, planDate: '2026-09-29', minutes: 90 })]))
  assert.notEqual(windowFingerprint(base), windowFingerprint([snap({ id: 1, planDate: '2026-09-30', minutes: 60 })]))
  assert.equal(
    windowFingerprint([snap({ id: 1, planDate: '2026-09-29', minutes: 60 }), snap({ id: 2, planDate: '2026-09-30', minutes: 30 })]),
    windowFingerprint([snap({ id: 2, planDate: '2026-09-30', minutes: 30 }), snap({ id: 1, planDate: '2026-09-29', minutes: 60 })]),
  )
})
