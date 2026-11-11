import test from 'node:test'
import assert from 'node:assert/strict'
import { healthPayload } from './health'

test('healthz 暴露邮件能力开关', () => {
  const payload = healthPayload(123)
  assert.equal(payload.ok, true)
  assert.equal(payload.serverTime, 123)
  assert.equal(typeof payload.features.emailReset, 'boolean')
})

test('数据库不可用时 ok=false', () => {
  assert.equal(healthPayload(1, false).ok, false)
})
