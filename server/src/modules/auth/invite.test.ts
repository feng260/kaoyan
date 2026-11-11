import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../middlewares/error'
import { assertInvite, parseInviteCodes } from './invite'

test('parseInviteCodes 去空白与空项', () => {
  assert.deepEqual(parseInviteCodes(' a, b ,,c '), ['a', 'b', 'c'])
})

test('生产环境未配置邀请码时拒绝注册', () => {
  const prev = process.env.NODE_ENV
  process.env.NODE_ENV = 'production'
  try {
    assert.throws(() => assertInvite('anything'), (err: unknown) => {
      assert.ok(err instanceof ApiError)
      assert.equal(err.code, 'INVITE_REQUIRED')
      return true
    })
  } finally {
    process.env.NODE_ENV = prev
  }
})

test('开发环境未配置邀请码时放行', () => {
  const prev = process.env.NODE_ENV
  process.env.NODE_ENV = 'development'
  try {
    assert.doesNotThrow(() => assertInvite(undefined))
  } finally {
    process.env.NODE_ENV = prev
  }
})

test('配置后错误或缺失邀请码被拒绝', () => {
  const prev = process.env.NODE_ENV
  process.env.NODE_ENV = 'production'
  process.env.INVITE_CODES = 'alpha,beta'
  try {
    assert.throws(() => assertInvite('wrong'), /邀请码/)
    assert.throws(() => assertInvite(undefined), /邀请码/)
    assert.doesNotThrow(() => assertInvite(' beta '))
  } finally {
    process.env.NODE_ENV = prev
    delete process.env.INVITE_CODES
  }
})
