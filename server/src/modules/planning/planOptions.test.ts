import test from 'node:test'
import assert from 'node:assert/strict'
import { buildAdjustmentOptions, type AdjustmentOption, type BuildOptionsInput } from './planOptions'
import type { PlanBrief } from './document'
import type { WindowSnapshotItem } from './adjust'

function brief(over: Partial<PlanBrief> = {}): PlanBrief {
  return {
    summary: '', goals: [], constraints: [], focus: [], materials: [], notes: [],
    examSubjects: [
      { name: '数学', progress: '一轮', scope: '高数', remainingMinutes: 400, milestone: '强化', milestoneDate: '2026-11-05', milestoneMinutes: 200, estimated: false },
      { name: '英语', progress: '单词', scope: '阅读', remainingMinutes: 200, milestone: '', milestoneDate: '', milestoneMinutes: 0, estimated: false },
    ],
    availability: [1, 2, 3, 4, 5, 6, 7].map(weekday => ({ weekday, windows: [{ start: '08:00', end: '12:00' }] })),
    fixedCommitments: [], periods: [], availabilityConfirmed: true, commitmentsConfirmed: true,
    ...over,
  }
}

function item(id: number, planDate: string, minutes: number, subject: string, over: Partial<WindowSnapshotItem> = {}): WindowSnapshotItem {
  return {
    id, subject, title: `${subject}任务${id}`, planDate, minutes, status: 'pending',
    locked: false, priority: 1, sortOrder: id, ...over,
  }
}

function input(items: WindowSnapshotItem[], capacity: Record<string, number>, over: Partial<BuildOptionsInput> = {}): BuildOptionsInput {
  return {
    before: items,
    windowFrom: '2026-11-03',
    windowTo: '2026-11-04',
    examDate: '2026-12-01',
    capacity: new Map(Object.entries(capacity)),
    dailyCapacity: () => 0,
    brief: brief(),
    intentKind: 'unavailable',
    weakSubjects: ['数学'],
    ...over,
  }
}

function lockedOf(option: AdjustmentOption, id: number): WindowSnapshotItem {
  const found = option.after.find(entry => entry.id === id)
  assert.ok(found, `方案 ${option.id} 丢掉了锁定项 ${id}`)
  return found!
}

test('compress:一天不可用时缩短/顺延,锁定项不动,不删除任何任务', () => {
  const done = item(1, '2026-11-03', 30, '数学', { status: 'done' })
  const locked = item(2, '2026-11-03', 30, '英语', { locked: true })
  const open = [
    item(3, '2026-11-03', 60, '数学'),
    item(4, '2026-11-04', 60, '数学'),
    item(5, '2026-11-04', 60, '英语'),
  ]
  const options = buildAdjustmentOptions(input([done, locked, ...open], { '2026-11-03': 120, '2026-11-04': 0, '2026-11-05': 240 }, {
    windowTo: '2026-11-05',
  }))
  const compress = options.find(option => option.id === 'compress')
  assert.ok(compress, '应给出压缩方案')
  assert.equal(compress!.impact.removedCount, 0)
  assert.ok(compress!.impact.movedCount > 0)
  const kept = lockedOf(compress!, 2)
  assert.equal(kept.planDate, '2026-11-03')
  assert.equal(kept.minutes, 30)
  const keptDone = compress!.after.find(entry => entry.id === 1)
  assert.ok(keptDone, '方案必须包含 done 项')
})

test('spill:溢出项挪到窗口后;越过里程碑则不可行', () => {
  const open = [item(3, '2026-11-04', 60, '数学')]
  // 窗口内 03 号放得下 60,但 04 号容量为 0;顺延窗口 05..18 有容量
  const base = input(open, { '2026-11-03': 60, '2026-11-04': 0 }, {
    brief: brief({
      examSubjects: [
        { name: '数学', progress: '', scope: '', remainingMinutes: 400, milestone: '强化', milestoneDate: '2026-11-04', milestoneMinutes: 200, estimated: false },
      ],
    }),
  })
  const blocked = buildAdjustmentOptions({ ...base, dailyCapacity: () => 120 })
  assert.equal(blocked.find(option => option.id === 'spill'), undefined, '越过里程碑不应给出顺延方案')

  const allowed = buildAdjustmentOptions({
    ...base,
    brief: brief({ examSubjects: [
      { name: '数学', progress: '', scope: '', remainingMinutes: 400, milestone: '强化', milestoneDate: '2026-11-20', milestoneMinutes: 200, estimated: false },
    ] }),
    dailyCapacity: () => 120,
  })
  const spill = allowed.find(option => option.id === 'spill')
  assert.ok(spill, '应给出顺延方案')
  assert.equal(spill!.impact.removedCount, 0)
  const movedItem = spill!.after.find(entry => entry.id === 3)
  assert.ok(movedItem && movedItem.planDate > '2026-11-04')
})

test('drop:删除最少的非锁定项,锁定项一次都不删', () => {
  const locked = item(2, '2026-11-04', 20, '数学', { locked: true })
  const open = [
    item(3, '2026-11-04', 90, '数学', { priority: 0 }),
    item(4, '2026-11-04', 90, '英语', { priority: 2 }),
  ]
  const options = buildAdjustmentOptions(input([locked, ...open], { '2026-11-03': 0, '2026-11-04': 150 }))
  const drop = options.find(option => option.id === 'drop')
  assert.ok(drop, '应给出删减方案')
  assert.equal(drop!.impact.removedCount, 1, '删一条就够,不应多删')
  const keptLocked = lockedOf(drop!, 2)
  assert.equal(keptLocked.planDate, '2026-11-04')
  assert.equal(drop!.after.some(entry => entry.id === 2), true)
  assert.equal(drop!.after.some(entry => entry.id === 4), false, '先删非薄弱、低优先级的任务')
})

test('lower_target:持续性容量下降时按科目下调剩余量与里程碑', () => {
  const open = [
    item(3, '2026-11-03', 60, '数学'),
    item(4, '2026-11-03', 60, '数学'),
    item(5, '2026-11-03', 60, '英语'),
  ]
  const options = buildAdjustmentOptions(input(open, { '2026-11-03': 0, '2026-11-04': 100 }, {
    intentKind: 'reduce_capacity',
  }))
  const lower = options.find(option => option.id === 'lower_target')
  assert.ok(lower, '压缩/顺延都不可行时应给出调低目标方案')
  const profile = JSON.parse(lower!.profileAfter!) as PlanBrief
  const math = profile.examSubjects.find(subject => subject.name === '数学')!
  const english = profile.examSubjects.find(subject => subject.name === '英语')!
  assert.ok(math.remainingMinutes < 400)
  assert.ok(english.remainingMinutes < 200)
  assert.ok(math.milestoneMinutes <= math.remainingMinutes)
  assert.equal(lower!.after.some(entry => entry.id === 3 || entry.id === 4 || entry.id === 5), true)
})

test('方案去重、推荐顺序固定且最多 4 条', () => {
  const open = [
    item(3, '2026-11-03', 60, '数学'),
    item(4, '2026-11-03', 60, '数学'),
    item(5, '2026-11-03', 60, '英语'),
  ]
  const options = buildAdjustmentOptions(input(open, { '2026-11-03': 0, '2026-11-04': 100 }, {
    intentKind: 'reduce_capacity', dailyCapacity: () => 120,
  }))
  assert.ok(options.length <= 4)
  const order = ['compress', 'spill', 'drop', 'lower_target']
  const indexes = options.map(option => order.indexOf(option.id))
  assert.deepEqual(indexes, [...indexes].sort((a, b) => a - b))
  const keys = options.map(option => JSON.stringify(option.changes))
  assert.equal(new Set(keys).size, keys.length)
})

test('所有方案都保留 done 项并原样保留锁定项', () => {
  const done = item(1, '2026-11-03', 45, '英语', { status: 'done' })
  const locked = item(2, '2026-11-04', 30, '数学', { locked: true })
  const open = [
    item(3, '2026-11-04', 90, '数学', { priority: 0 }),
    item(4, '2026-11-04', 90, '英语', { priority: 2 }),
  ]
  const options = buildAdjustmentOptions(input([done, locked, ...open], { '2026-11-03': 60, '2026-11-04': 150 }, {
    intentKind: 'reduce_capacity',
  }))
  assert.ok(options.length > 0)
  for (const option of options) {
    assert.ok(option.after.some(entry => entry.id === 1), `${option.id} 丢了 done 项`)
    assert.equal(lockedOf(option, 2).planDate, '2026-11-04')
    assert.equal(lockedOf(option, 2).minutes, 30)
    assert.ok(option.changes.length > 0)
  }
})
