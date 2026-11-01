import test from 'node:test'
import assert from 'node:assert/strict'
import { healthPayload } from './health'

test('healthz 暴露邮件重置能力开关', () => {
  const payload = healthPayload(123)
  assert.equal(payload.ok, true)
  assert.equal(payload.serverTime, 123)
  assert.equal(typeof payload.features.emailReset, 'boolean')
})
