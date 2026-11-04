import test from 'node:test'
import assert from 'node:assert/strict'
import { buildLedger, compressPendingToFit, expandL2, normalizeL2Tasks, validateL2, type L2Task } from './adjustL2'
import type { AdjustableItem, WindowSnapshotItem } from './adjust'

const DAYS = ['2026-09-29', '2026-09-30', '2026-10-01']
const CAPACITY = new Map([['2026-09-29', 180], ['2026-09-30', 0], ['2026-10-01', 180]])
const PENDING: AdjustableItem[] = [
  { id: 1, subject: '数学', title: '线性代数', planDate: '2026-09-29', minutes: 120, priority: 0, sortOrder: 0 },
  { id: 2, subject: '英语', title: '阅读真题', planDate: '2026-09-30', minutes: 60, priority: 0, sortOrder: 1 },
]
const SUBJECTS = ['数学', '英语', '政治']

test('compressPendingToFit:小缺口按比例收缩,总量恰好塞进容量', () => {
  // 待排 180,容量 168(缺口 12,6.7%)→ 每项缩到 112/120*原值
  const result = compressPendingToFit([
    { id: 1, subject: '数学', title: '线性代数', planDate: '2026-09-29', minutes: 120, priority: 0, sortOrder: 0 },
    { id: 2, subject: '英语', title: '阅读真题', planDate: '2026-09-30', minutes: 60, priority: 0, sortOrder: 1 },
  ], 168)
  assert.ok(result)
  assert.equal(result.total, 168)
  // 120*0.9333≈112、60*0.9333≈56
  assert.deepEqual(result.items.map(item => item.minutes), [112, 56])
  // 用户截图场景:缺口 112/18515≈0.6%,压缩后必然塞得下,不再报"排不下"
})

test('compressPendingToFit:15 分钟下限托不住缺口时返回 null', () => {
  const result = compressPendingToFit([
    { id: 1, subject: '数学', title: '线性代数', planDate: '2026-09-29', minutes: 15, priority: 0, sortOrder: 0 },
    { id: 2, subject: '英语', title: '阅读真题', planDate: '2026-09-30', minutes: 15, priority: 0, sortOrder: 1 },
  ], 10)
  assert.equal(result, null)
})

test('compressPendingToFit:没有缺口时返回 null(调用方原样通过)', () => {
  const items: AdjustableItem[] = [
    { id: 1, subject: '数学', title: '线性代数', planDate: '2026-09-29', minutes: 45, priority: 0, sortOrder: 0 },
  ]
  assert.equal(compressPendingToFit(items, 180), null)
  assert.equal(compressPendingToFit([], 180), null)
})

test('buildLedger:总量/每日容量/白名单/里程碑裁剪', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS,
    [{ subject: '英语', date: '2026-10-01', name: '阅读一轮完成' },
     { subject: '英语', date: '2026-09-01', name: '窗口外会被裁掉' }])
  assert.equal(ledger.totalMinutes, 180)
  assert.deepEqual(ledger.dayCapacity, [180, 0, 180])
  assert.deepEqual(ledger.subjects, ['数学', '英语', '政治'])
  assert.deepEqual(ledger.milestones, [{ subject: '英语', date: '2026-10-01', name: '阅读一轮完成' }])
})

test('normalizeL2Tasks:越界/白名单外/非法分钟一律丢弃', () => {
  const raw = [
    { dayOffset: 0, subject: '数学', title: '线性代数', minutes: 120 },
    { dayOffset: 3, subject: '数学', title: '越界', minutes: 30 },
    { dayOffset: 0, subject: '体育', title: '白名单外', minutes: 30 },
    { dayOffset: 1, subject: '英语', title: '负数', minutes: -10 },
  ]
  const tasks = normalizeL2Tasks(raw, buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS, []))
  assert.deepEqual(tasks, [{ dayOffset: 0, subject: '数学', title: '线性代数', minutes: 120 }])
})

test('expandL2:同科同题复用原 id,日期按 dayOffset 展开', () => {
  const tasks: L2Task[] = [
    { dayOffset: 2, subject: '数学', title: '线性代数', minutes: 120 },
    { dayOffset: 2, subject: '英语', title: '阅读真题', minutes: 60 },
  ]
  const target = expandL2(tasks, '2026-09-29', PENDING)
  assert.deepEqual(target.map(item => item.id), [1, 2])
  assert.deepEqual(target.map(item => item.planDate), ['2026-10-01', '2026-10-01'])
})

test('expandL2:同科不同题也复用;无匹配新增 id=0', () => {
  const tasks: L2Task[] = [
    { dayOffset: 0, subject: '数学', title: '换一批题', minutes: 120 },
    { dayOffset: 2, subject: '政治', title: '马原', minutes: 60 },
  ]
  const target = expandL2(tasks, '2026-09-29', PENDING)
  assert.deepEqual(target.map(item => item.id), [1, 0])
  const added = target.find(item => item.id === 0)!
  assert.equal(added.subject, '政治')
  assert.equal(added.planDate, '2026-10-01')
})

function snapOf(id: number, day: string, minutes: number, subject = '数学'): WindowSnapshotItem {
  return { id, subject, title: `任务${id}`, planDate: day, minutes, status: 'pending' }
}

test('validateL2:总量守恒被破坏 → 不通过', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS, [])
  const broken = [snapOf(1, '2026-09-29', 60), snapOf(2, '2026-10-01', 60)]
  assert.equal(validateL2(broken, ledger).ok, false)
})

test('validateL2:排进容量为 0 的日子 → 不通过', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS, [])
  const over = [snapOf(1, '2026-09-30', 120), snapOf(2, '2026-10-01', 60)]
  assert.equal(validateL2(over, ledger).ok, false)
})

test('validateL2:conserve 总量差 2 分钟失败,差 1 分钟放行', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS, [])
  const offByTwo = [{ id: 1, subject: '数学', title: '线性代数', planDate: '2026-09-29', minutes: 118, status: 'pending' }]
  assert.equal(validateL2(offByTwo, ledger, { mode: 'conserve' }).ok, false)
  const offByOne = [{ id: 1, subject: '数学', title: '线性代数', planDate: '2026-09-29', minutes: 179, status: 'pending' }]
  assert.equal(validateL2(offByOne, ledger, { mode: 'conserve' }).ok, true)
})

test('validateL2:add 模式总量必须等于原总量加新增', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS, [])
  const exact = [
    { id: 1, subject: '数学', title: '线性代数', planDate: '2026-09-29', minutes: 180, status: 'pending' },
    { id: 0, subject: '数学', title: '新增任务', planDate: '2026-10-01', minutes: 60, status: 'pending' },
  ]
  assert.equal(validateL2(exact, ledger, { mode: 'add', allowedDelta: 60 }).ok, true)
  const over = [
    { id: 1, subject: '数学', title: '线性代数', planDate: '2026-09-29', minutes: 180, status: 'pending' },
    { id: 0, subject: '数学', title: '新增任务', planDate: '2026-10-01', minutes: 62, status: 'pending' },
  ]
  assert.equal(validateL2(over, ledger, { mode: 'add', allowedDelta: 60 }).ok, false)
})

test('validateL2:drop 模式只许减少,点名删除的项必须缺席', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS, [])
  const dropped = [{ id: 1, subject: '数学', title: '线性代数', planDate: '2026-09-29', minutes: 120, status: 'pending' }]
  assert.equal(validateL2(dropped, ledger, { mode: 'drop', droppedIds: [2] }).ok, true)
  const stillThere = [...dropped, { id: 2, subject: '英语', title: '阅读真题', planDate: '2026-10-01', minutes: 60, status: 'pending' }]
  assert.equal(validateL2(stillThere, ledger, { mode: 'drop', droppedIds: [2] }).ok, false)
})

test('validateL2:scope 模式允许低于原总量', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS, [])
  const lowered = [{ id: 1, subject: '数学', title: '线性代数', planDate: '2026-09-29', minutes: 90, status: 'pending' }]
  assert.equal(validateL2(lowered, ledger, { mode: 'scope' }).ok, true)
})

test('validateL2:锁定项出现在重排结果里即失败', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS, [])
  const locked: WindowSnapshotItem = {
    id: 2, subject: '英语', title: '阅读真题', planDate: '2026-09-30', minutes: 60, status: 'pending', locked: true,
  }
  const target = [
    { id: 1, subject: '数学', title: '线性代数', planDate: '2026-09-29', minutes: 120, status: 'pending' },
    { id: 2, subject: '英语', title: '阅读真题', planDate: '2026-10-01', minutes: 60, status: 'pending' },
  ]
  const verdict = validateL2(target, ledger, { mode: 'conserve', locked: [locked] })
  assert.equal(verdict.ok, false)
  assert.match(verdict.reason!, /锁定任务「阅读真题」被改动/)
})

test('validateL2:里程碑不推迟;当日或之前允许', () => {
  const ledger = buildLedger(PENDING, CAPACITY, DAYS, SUBJECTS,
    [{ subject: '英语', date: '2026-09-29', name: '单词一轮' }])
  const late = [snapOf(1, '2026-09-29', 120), snapOf(2, '2026-10-01', 60, '英语')]
  assert.equal(validateL2(late, ledger).ok, false)
  const onTime = [snapOf(1, '2026-09-29', 120), snapOf(2, '2026-09-29', 60, '英语')]
  assert.equal(validateL2(onTime, ledger).ok, true)
})
