import test from 'node:test'
import assert from 'node:assert/strict'
import { planElasticAdjustment, rankImportance, type Tier } from './elasticAdjust'
import type { WindowSnapshotItem } from './adjust'

const item = (id: number, patch: Partial<WindowSnapshotItem> = {}): WindowSnapshotItem => ({
  id, subject: '数学', title: `任务${id}`, planDate: '2026-11-10', minutes: 60,
  status: 'pending', locked: false, ...patch,
})

test('rankImportance: done/locked 固定,弱科与窗口内里程碑默认保护', () => {
  const tiers = rankImportance([
    item(1, { status: 'done' }),
    item(2, { locked: true }),
    item(3, { subject: '英语' }),
    item(4, { subject: '政治' }),
    item(5, { subject: '专业课' }),
  ], {
    hints: [], weakSubjects: ['英语'],
    milestones: new Map([['政治', '2026-11-12'], ['专业课', '2026-12-01']]),
    windowTo: '2026-11-14',
  })
  assert.equal(tiers.get(1), 'fixed')
  assert.equal(tiers.get(2), 'fixed')
  assert.equal(tiers.get(3), 'protect')
  assert.equal(tiers.get(4), 'protect')
  assert.equal(tiers.get(5), 'normal')
})

test('rankImportance: 用户明确可让覆盖默认保护,明确保护优先于明确可让', () => {
  const tiers = rankImportance([item(1, { subject: '英语' }), item(2, { subject: '数学' })], {
    hints: [
      { subject: '英语', level: 'elastic' },
      { subject: '数学', level: 'elastic' },
      { subject: '数学', level: 'protect' },
    ],
    weakSubjects: ['英语'], milestones: new Map(), windowTo: '2026-11-14',
  })
  assert.equal(tiers.get(1), 'elastic')
  assert.equal(tiers.get(2), 'protect')
})

test('rankImportance: 标题与日期限定只命中对应任务', () => {
  const tiers = rankImportance([
    item(1, { subject: '数学', title: '真题套卷', planDate: '2026-11-10' }),
    item(2, { subject: '数学', title: '错题整理', planDate: '2026-11-10' }),
  ], {
    hints: [{ subject: '数学', title: '错题', date: '2026-11-10', level: 'elastic' }],
    weakSubjects: [], milestones: new Map(), windowTo: '2026-11-14',
  })
  assert.equal(tiers.get(1), 'normal')
  assert.equal(tiers.get(2), 'elastic')
})
const mk = (id: number, day: string, minutes = 60, patch: Partial<WindowSnapshotItem> = {}) =>
  item(id, { planDate: day, minutes, ...patch })
const tiersOf = (...entries: Array<[number, Tier]>) => new Map<number, Tier>(entries)
const capacityOf = (map: Record<string, number>) => (day: string) => map[day] ?? 0

test('弹性:没空日的任务后移,未受影响日期与任务完全不变', () => {
  const before = [mk(1, '2026-11-10'), mk(2, '2026-11-11'), mk(3, '2026-11-12')]
  const tiers = tiersOf([1, 'normal'], [2, 'normal'], [3, 'normal'])
  const result = planElasticAdjustment({
    before, tiers, demands: [{ id: 1, earliest: '2026-11-10', reason: 'unavailable' }],
    capacity: capacityOf({ '2026-11-10': 0, '2026-11-11': 120, '2026-11-12': 120, '2026-11-13': 120, '2026-11-14': 120 }),
    today: '2026-11-10', windowTo: '2026-11-14', hardTo: '2026-11-14', autoExtend: true, milestones: new Map(),
  })
  assert.equal(result.after.find(i => i.id === 1)!.planDate, '2026-11-11')
  assert.deepEqual(result.after.find(i => i.id === 2), before[1])
  assert.deepEqual(result.after.find(i => i.id === 3), before[2])
  // 原日期和新日期都算被影响:确认窗口必须同时覆盖两头
  assert.deepEqual(result.touchedDays, ['2026-11-10', '2026-11-11'])
})

test('弹性:先缩可让任务给保护任务腾位置', () => {
  const before = [mk(1, '2026-11-10'), mk(2, '2026-11-11', 120)]
  const tiers = tiersOf([1, 'protect'], [2, 'elastic'])
  const result = planElasticAdjustment({
    before, tiers, demands: [{ id: 1, earliest: '2026-11-11', reason: 'unavailable' }],
    capacity: capacityOf({ '2026-11-10': 0, '2026-11-11': 120, '2026-11-12': 120, '2026-11-13': 120, '2026-11-14': 120 }),
    today: '2026-11-10', windowTo: '2026-11-14', hardTo: '2026-11-14', autoExtend: true, milestones: new Map(),
  })
  assert.equal(result.after.find(i => i.id === 2)!.minutes, 60)
  assert.equal(result.after.find(i => i.id === 1)!.planDate, '2026-11-11')
  assert.ok(result.shrinkMinutes >= 60)
})

test('弹性:可让任务后挤一天给保护任务让位', () => {
  const before = [mk(1, '2026-11-10', 60), mk(2, '2026-11-11', 60)]
  const tiers = tiersOf([1, 'protect'], [2, 'elastic'])
  const result = planElasticAdjustment({
    before, tiers, demands: [{ id: 1, earliest: '2026-11-11', targetDate: '2026-11-11', reason: 'unavailable' }],
    capacity: capacityOf({ '2026-11-10': 0, '2026-11-11': 60, '2026-11-12': 60, '2026-11-13': 60, '2026-11-14': 60 }),
    today: '2026-11-10', windowTo: '2026-11-14', hardTo: '2026-11-14', autoExtend: true, milestones: new Map(),
  })
  assert.equal(result.after.find(i => i.id === 2)!.planDate, '2026-11-12')
  assert.equal(result.after.find(i => i.id === 1)!.planDate, '2026-11-11')
})

test('弹性:默认窗口放不下自动延窗,用户指定上限时不延', () => {
  const before = [mk(1, '2026-11-10', 60)]
  const tiers = tiersOf([1, 'normal'])
  const capacity = capacityOf({ '2026-11-10': 0, '2026-11-11': 0, '2026-11-12': 0, '2026-11-13': 0, '2026-11-14': 0, '2026-11-15': 60 })
  const extended = planElasticAdjustment({
    before, tiers, demands: [{ id: 1, earliest: '2026-11-10', reason: 'unavailable' }],
    capacity, today: '2026-11-10', windowTo: '2026-11-14', hardTo: '2026-11-15', autoExtend: true, milestones: new Map(),
  })
  assert.equal(extended.overflow.length, 0)
  assert.equal(extended.after.find(i => i.id === 1)!.planDate, '2026-11-15')
  const fixed = planElasticAdjustment({
    before, tiers, demands: [{ id: 1, earliest: '2026-11-10', reason: 'unavailable' }],
    capacity, today: '2026-11-10', windowTo: '2026-11-14', hardTo: '2026-11-15', autoExtend: false, milestones: new Map(),
  })
  assert.equal(fixed.overflow.length, 1)
})

test('弹性:幂等,且未受影响的保护任务不被动', () => {
  const before = [mk(1, '2026-11-10'), mk(2, '2026-11-12')]
  const tiers = tiersOf([1, 'normal'], [2, 'protect'])
  const input = {
    before, tiers, demands: [{ id: 1, earliest: '2026-11-10', reason: 'unavailable' as const }],
    capacity: capacityOf({ '2026-11-10': 0, '2026-11-11': 60, '2026-11-12': 60 }),
    today: '2026-11-10', windowTo: '2026-11-12', hardTo: '2026-11-12', autoExtend: true, milestones: new Map<string, string>(),
  }
  const first = planElasticAdjustment(input)
  const second = planElasticAdjustment({ ...input, before: first.after })
  assert.deepEqual(second.after, first.after)
  assert.deepEqual(first.after.find(i => i.id === 2), before[1])
})

test('弹性:里程碑前的科目任务不会被推到里程碑之后', () => {
  const before = [mk(1, '2026-11-11', 60, { subject: '数学' })]
  const tiers = tiersOf([1, 'normal'])
  const result = planElasticAdjustment({
    before, tiers, demands: [{ id: 1, earliest: '2026-11-11', reason: 'unavailable' }],
    capacity: capacityOf({ '2026-11-11': 0, '2026-11-12': 60, '2026-11-13': 60 }),
    today: '2026-11-11', windowTo: '2026-11-13', hardTo: '2026-11-13', autoExtend: false,
    milestones: new Map([['数学', '2026-11-11']]),
  })
  assert.equal(result.overflow.length, 1)
  assert.equal(result.after.find(i => i.id === 1)!.planDate, '2026-11-11')
})
