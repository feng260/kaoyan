import test from 'node:test'
import assert from 'node:assert/strict'
import { accountKey, bucketKey, clientAddress } from './ratelimit'

const ctxWith = (overrides: Record<string, unknown> = {}) => ({
  ip: '203.0.113.9',
  req: { socket: { remoteAddress: '10.0.0.2' } },
  ...overrides,
} as any)

test('clientAddress 在未信任代理时使用 socket 地址', () => {
  assert.equal(clientAddress(ctxWith(), false), '10.0.0.2')
})

test('clientAddress 在信任代理时使用 ctx.ip', () => {
  assert.equal(clientAddress(ctxWith(), true), '203.0.113.9')
})

test('accountKey 归一化大小写并且不包含明文账号', () => {
  const a = accountKey('  Alice@Example.com ')
  const b = accountKey('alice@example.com')
  assert.equal(a, b)
  assert.match(a, /^[0-9a-f]{16}$/)
  assert.ok(!a.includes('alice'))
})

test('bucketKey 按限流器名字隔离桶', () => {
  assert.notEqual(bucketKey('login-user', 'x'), bucketKey('login-user-ip', 'x'))
})
