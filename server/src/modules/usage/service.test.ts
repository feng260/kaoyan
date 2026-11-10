import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildAdminUsage,
  clampAdminDays,
  dayStartForOffset,
  estimateCostCny,
  monthStartForOffset,
  normalizeUtcOffset,
  summarizeByFeature,
  summarizeRows,
  type UsageCountRow,
} from './service'

const row = (patch: Partial<UsageCountRow> = {}): UsageCountRow => ({
  userGuid: 'u1',
  feature: 'interview',
  promptTokens: 10,
  completionTokens: 5,
  totalTokens: 15,
  estimated: false,
  createdAt: new Date('2026-11-09T00:00:00.000Z'),
  ...patch,
})

test('时区边界:UTC+8 的凌晨属于当天', () => {
  const now = new Date('2026-11-09T16:30:00.000Z') // 2026-11-10 00:30 +08
  assert.equal(dayStartForOffset(now, 480).toISOString(), '2026-11-09T16:00:00.000Z')
  assert.equal(monthStartForOffset(now, 480).toISOString(), '2026-10-31T16:00:00.000Z')
})

test('时区边界:UTC-5 的深夜仍是前一天', () => {
  const now = new Date('2026-11-10T04:30:00.000Z') // 2026-11-09 23:30 -05
  assert.equal(dayStartForOffset(now, -300).toISOString(), '2026-11-09T05:00:00.000Z')
  assert.equal(monthStartForOffset(now, -300).toISOString(), '2026-11-01T05:00:00.000Z')
})

test('时区偏移越界回落到东八区', () => {
  assert.equal(normalizeUtcOffset(480), 480)
  assert.equal(normalizeUtcOffset(-720), -720)
  assert.equal(normalizeUtcOffset(840), 840)
  assert.equal(normalizeUtcOffset(900), 480)
  assert.equal(normalizeUtcOffset(-999), 480)
  assert.equal(normalizeUtcOffset('abc'), 480)
  assert.equal(normalizeUtcOffset(undefined), 480)
})

test('管理端 days 夹紧到 1..90', () => {
  assert.equal(clampAdminDays(30), 30)
  assert.equal(clampAdminDays('7'), 7)
  assert.equal(clampAdminDays(200), 90)
  assert.equal(clampAdminDays(0), 1)
  assert.equal(clampAdminDays(-5), 1)
  assert.equal(clampAdminDays(7.9), 7)
  assert.equal(clampAdminDays(undefined), 30)
  assert.equal(clampAdminDays('abc'), 30)
})

test('按功能分组并按 tokens 倒序', () => {
  const groups = summarizeByFeature([
    row({ feature: 'interview', totalTokens: 15 }),
    row({ feature: 'interview', totalTokens: 20, estimated: true }),
    row({ feature: 'plan_generate', totalTokens: 100 }),
  ])
  assert.deepEqual(groups.map(group => [group.feature, group.totalTokens, group.calls]), [
    ['plan_generate', 100, 1],
    ['interview', 35, 2],
  ])
  assert.equal(groups[1].estimatedCalls, 1)
})

test('管理端聚合:区间过滤、用户 Top、功能分项与成本', () => {
  const now = new Date('2026-11-10T12:00:00.000Z')
  const rows = [
    row({ userGuid: 'u1', totalTokens: 150, promptTokens: 100, completionTokens: 50 }),
    row({
      userGuid: 'u1', totalTokens: 15, promptTokens: 10, completionTokens: 5,
      estimated: true, createdAt: new Date('2026-11-10T01:00:00.000Z'),
    }),
    row({
      userGuid: 'u2', feature: 'plan_generate', totalTokens: 300, promptTokens: 200, completionTokens: 100,
      createdAt: new Date('2026-11-05T00:00:00.000Z'),
    }),
    row({
      userGuid: 'u2', feature: 'plan_generate', totalTokens: 19998, promptTokens: 9999, completionTokens: 9999,
      createdAt: new Date('2026-10-01T00:00:00.000Z'),
    }),
  ]
  const usage = buildAdminUsage({
    rows,
    users: [{ guid: 'u1', username: 'alice' }, { guid: 'u2', username: 'bob' }],
    days: 7,
    now,
    priceInPer1M: 2000,
    priceOutPer1M: 8000,
  })
  assert.equal(usage.totals.promptTokens, 310)
  assert.equal(usage.totals.completionTokens, 155)
  assert.equal(usage.totals.totalTokens, 465)
  assert.equal(usage.totals.calls, 3)
  assert.equal(usage.totals.estimatedCalls, 1)
  assert.equal(usage.totals.activeUsers, 2)
  assert.deepEqual(usage.topUsers.map(user => [user.username, user.totalTokens]), [
    ['bob', 300],
    ['alice', 165],
  ])
  assert.equal(usage.topUsers[1].lastCallAt, new Date('2026-11-10T01:00:00.000Z').getTime())
  assert.deepEqual(usage.byFeature.map(item => item.feature), ['plan_generate', 'interview'])
  assert.equal(usage.estimatedCostCny, 1.86) // 310/1e6×2000 + 155/1e6×8000
})

test('成本单价为 0 时不展示成本', () => {
  assert.equal(estimateCostCny([row()], 0, 0), null)
})

test('汇总行:计数与估算次数', () => {
  assert.deepEqual(summarizeRows([row(), row({ estimated: true })]), {
    promptTokens: 20,
    completionTokens: 10,
    totalTokens: 30,
    calls: 2,
    estimatedCalls: 1,
  })
})
