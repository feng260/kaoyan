import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../middlewares/error'
import { assertInvite, parseInviteCodes } from './invite'

/**
 * 保存并还原环境变量:原值不存在时必须 delete,不能把字符串 "undefined" 写回去。
 * 否则本文件的用例会污染同一进程里的其它测试,测试结果取决于机器上恰好设了什么。
 */
function captureEnv(keys: readonly ('NODE_ENV' | 'INVITE_CODES')[]): () => void {
  const saved = keys.map(key => [key, process.env[key]] as const)
  return () => {
    for (const [key, value] of saved) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  }
}

test('parseInviteCodes 去空白与空项', () => {
  assert.deepEqual(parseInviteCodes(' a, b ,,c '), ['a', 'b', 'c'])
})

test('生产环境未配置邀请码时拒绝注册', () => {
  const restore = captureEnv(['NODE_ENV', 'INVITE_CODES'])
  process.env.NODE_ENV = 'production'
  delete process.env.INVITE_CODES
  try {
    assert.throws(() => assertInvite('anything'), (err: unknown) => {
      assert.ok(err instanceof ApiError)
      assert.equal(err.code, 'INVITE_REQUIRED')
      return true
    })
  } finally {
    restore()
  }
})

test('开发环境未配置邀请码时放行', () => {
  const restore = captureEnv(['NODE_ENV', 'INVITE_CODES'])
  process.env.NODE_ENV = 'development'
  delete process.env.INVITE_CODES
  try {
    assert.doesNotThrow(() => assertInvite(undefined))
  } finally {
    restore()
  }
})

test('配置后错误或缺失邀请码被拒绝', () => {
  const restore = captureEnv(['NODE_ENV', 'INVITE_CODES'])
  process.env.NODE_ENV = 'production'
  process.env.INVITE_CODES = 'alpha,beta'
  try {
    assert.throws(() => assertInvite('wrong'), /邀请码/)
    assert.throws(() => assertInvite(undefined), /邀请码/)
    assert.doesNotThrow(() => assertInvite(' beta '))
  } finally {
    restore()
  }
})
