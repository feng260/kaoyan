import test from 'node:test'
import assert from 'node:assert/strict'
import { bearerToken } from './jwt'

test('bearerToken 提取标准 Authorization 头', () => {
  assert.equal(bearerToken('Bearer abc.def.ghi'), 'abc.def.ghi')
  assert.equal(bearerToken('bearer abc'), 'abc')
})

test('bearerToken 拒绝空头与非 Bearer 方案', () => {
  assert.equal(bearerToken(undefined), '')
  assert.equal(bearerToken(''), '')
  assert.equal(bearerToken('Basic abc'), '')
})
