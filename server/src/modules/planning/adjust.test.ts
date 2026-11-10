import test from 'node:test'
import assert from 'node:assert/strict'
import {
  adjustWindow, affectedDays, datesBetween, diffSnapshots, planL1Shuffle, shiftDate, windowFingerprint,
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

test('diffSnapshots:传入 notes 时每条变动带说明,未传时保持原样', () => {
  const before = [snap({ id: 1, planDate: '2026-11-10', minutes: 60 })]
  const after = [{ ...before[0], planDate: '2026-11-11', minutes: 45 }]
  const changes = diffSnapshots(before, after, new Map([[1, '缩短 60→45 分钟（可让）']]))
  assert.equal(changes[0].note, '缩短 60→45 分钟（可让）')
  assert.equal(diffSnapshots(before, after)[0].note, undefined)
})

test('windowFingerprint:状态切换不改变指纹,内容变了指纹必变,顺序无关', () => {
  const base = [snap({ id: 1, planDate: '2026-09-29', minutes: 60 })]
  // 正常打卡只改 status,不会让调整单失效
  assert.equal(windowFingerprint(base), windowFingerprint([snap({ id: 1, planDate: '2026-09-29', minutes: 60, status: 'done' })]))
  assert.notEqual(windowFingerprint(base), windowFingerprint([snap({ id: 1, planDate: '2026-09-29', minutes: 90 })]))
  assert.notEqual(windowFingerprint(base), windowFingerprint([snap({ id: 1, planDate: '2026-09-30', minutes: 60 })]))
  assert.equal(
    windowFingerprint([snap({ id: 1, planDate: '2026-09-29', minutes: 60 }), snap({ id: 2, planDate: '2026-09-30', minutes: 30 })]),
    windowFingerprint([snap({ id: 2, planDate: '2026-09-30', minutes: 30 }), snap({ id: 1, planDate: '2026-09-29', minutes: 60 })]),
  )
})

test('windowFingerprint:subject/title/locked/startTime/slot 与口径哈希都会让旧调整单失效', () => {
  const base = [snap({ id: 1, planDate: '2026-09-29', minutes: 60 })]
  for (const changed of [
    snap({ id: 1, planDate: '2026-09-29', minutes: 60, subject: '英语' }),
    snap({ id: 1, planDate: '2026-09-29', minutes: 60, title: '换了标题' }),
    snap({ id: 1, planDate: '2026-09-29', minutes: 60, locked: true }),
    snap({ id: 1, planDate: '2026-09-29', minutes: 60, startTime: '09:00' }),
    snap({ id: 1, planDate: '2026-09-29', minutes: 60, slot: 'morning' }),
  ]) {
    assert.notEqual(windowFingerprint(base), windowFingerprint([changed]))
  }
  assert.notEqual(windowFingerprint(base), windowFingerprint(base, 'brief-a'))
  assert.notEqual(windowFingerprint(base, 'brief-a'), windowFingerprint(base, 'brief-b'))
})

test('adjustWindow:默认 5 天;显式 horizonDays 放宽;scope 可放宽到 30 天', () => {
  const base = { planStart: '2026-09-01', examDate: '2027-06-01', today: '2026-11-03', unfinishedDates: [] }
  assert.deepEqual(adjustWindow(base), { from: '2026-11-03', to: '2026-11-07' })
  assert.deepEqual(adjustWindow(base, { horizonDays: 10 }), { from: '2026-11-03', to: '2026-11-12' })
  // 不指定 maxDays 时,再大的 horizon 也夹到 14 天
  assert.deepEqual(adjustWindow(base, { horizonDays: 30 }), { from: '2026-11-03', to: '2026-11-16' })
  assert.deepEqual(adjustWindow(base, { horizonDays: 1 }), { from: '2026-11-03', to: '2026-11-07' })
  // adjust_scope 保留旧的 30 天窗口
  assert.deepEqual(adjustWindow(base, { horizonDays: 30, maxDays: 30 }), { from: '2026-11-03', to: '2026-12-02' })

  const examBound = adjustWindow({ planStart: '2026-09-01', examDate: '2026-11-20', today: '2026-11-03', unfinishedDates: [] })
  assert.deepEqual(examBound, { from: '2026-11-03', to: '2026-11-07' })

  // 落后任务仍把起点拉回去;未来仍只看近几天
  const overdue = adjustWindow({
    planStart: '2026-09-01', examDate: '2027-06-01', today: '2026-11-03',
    unfinishedDates: ['2026-10-04', '2026-10-20'],
  })
  assert.deepEqual(overdue, { from: '2026-10-04', to: '2026-11-07' })

  const planStartsLater = adjustWindow({
    planStart: '2026-10-31', examDate: '2027-06-01', today: '2026-11-03',
    unfinishedDates: ['2026-10-30'],
  })
  assert.deepEqual(planStartsLater, { from: '2026-10-31', to: '2026-11-07' })

  // 落后超过 30 天的不再纳入,避免把整份历史计划拖进调整窗口
  const tooOld = adjustWindow({
    planStart: '2026-09-01', examDate: '2027-06-01', today: '2026-11-03',
    unfinishedDates: ['2026-09-20'],
  })
  assert.deepEqual(tooOld, { from: '2026-11-03', to: '2026-11-07' })

  assert.equal(adjustWindow({ planStart: '2026-09-01', examDate: '2026-11-03', today: '2026-11-03', unfinishedDates: [] }), null)
})

test('L1:原日期在科目里程碑之前的任务,不会被推到里程碑之后', () => {
  const result = planL1Shuffle({
    pending: [item({ id: 1, planDate: '2026-09-29', minutes: 60, subject: '数学' })],
    capacity: new Map([['2026-09-29', 0], ['2026-09-30', 0], ['2026-10-01', 120], ['2026-10-02', 120]]),
    windowFrom: '2026-09-29',
    windowTo: '2026-10-02',
    milestones: [{ subject: '数学', date: '2026-09-30' }],
  })
  assert.equal(result.moves.length, 0)
  assert.equal(result.overflow.length, 1, '越过里程碑不如实报缺口,交给用户选方案')

  const beforeMilestone = planL1Shuffle({
    pending: [item({ id: 1, planDate: '2026-10-02', minutes: 60, subject: '数学' })],
    capacity: new Map([['2026-10-01', 120], ['2026-10-02', 0], ['2026-10-03', 120]]),
    windowFrom: '2026-10-01',
    windowTo: '2026-10-03',
    milestones: [{ subject: '数学', date: '2026-09-30' }],
  })
  assert.deepEqual(beforeMilestone.moves.map(move => move.to), ['2026-10-03'], '原日期已在里程碑之后则不受限')
})
