import test from 'node:test'
import assert from 'node:assert/strict'
import { createAiAlert, cstDayKey } from './aiAlert'

const DAY = 24 * 60 * 60_000

function harness(initialTokens = 0) {
  let tokens = initialTokens
  const sent: Array<{ subject: string; text: string }> = []
  const logs: string[] = []
  let now = Date.UTC(2026, 10, 11, 0, 0, 0) // 2026-11-11 08:00 CST
  const alert = createAiAlert({
    sumTokensSince: async () => tokens,
    sendAlert: async (subject, text) => { sent.push({ subject, text }) },
    clock: () => now,
    log: message => logs.push(message),
  }, { dailyTokens: 100, jobTokens: 50, concurrencyWarn: 4 })
  return {
    sent, logs,
    setTokens: (v: number) => { tokens = v },
    advance: (ms: number) => { now += ms },
    alert,
  }
}

test('cstDayKey 以 Asia/Shanghai 自然日切分', () => {
  assert.equal(cstDayKey(Date.UTC(2026, 10, 10, 16, 30)), '2026-11-11')
})

test('低于阈值不发送告警', async () => {
  const h = harness(99)
  await h.alert.checkDailyTokenAlert()
  assert.equal(h.sent.length, 0)
})

test('越线当日只发一封,跨日再发', async () => {
  const h = harness(101)
  await h.alert.checkDailyTokenAlert()
  assert.equal(h.sent.length, 1)

  h.advance(6 * 60_000)
  await h.alert.checkDailyTokenAlert()
  assert.equal(h.sent.length, 1)

  h.setTokens(0)
  h.advance(DAY)
  await h.alert.checkDailyTokenAlert()
  h.advance(6 * 60_000)
  h.setTokens(101)
  await h.alert.checkDailyTokenAlert()
  assert.equal(h.sent.length, 2)
})

test('5 分钟内重复调用不重复查询', async () => {
  let queries = 0
  let now = Date.now()
  const alert = createAiAlert({
    sumTokensSince: async () => { queries++; return 0 },
    sendAlert: async () => {},
    clock: () => now,
    log: () => {},
  }, { dailyTokens: 100, jobTokens: 50, concurrencyWarn: 4 })
  await alert.checkDailyTokenAlert()
  now += 60_000
  await alert.checkDailyTokenAlert()
  assert.equal(queries, 1)
})

test('单任务与并发超限只写日志', () => {
  const h = harness()
  h.alert.warnIfJobExceeds(50, 'job-1')
  h.alert.warnIfBusy(4)
  assert.equal(h.logs.length, 2)
  assert.match(h.logs[0], /job-1/)
  assert.match(h.logs[1], /4/)
})
