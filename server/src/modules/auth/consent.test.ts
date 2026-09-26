import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../middlewares/error'
import {
  POLICY_VERSION,
  POLICY_EFFECTIVE_DATE,
  assertLegalConsent,
  legalConsentSchema,
} from './consent'

/**
 * 注册同意门禁。
 * 单独抽成不依赖 Prisma 的模块,是为了在没有数据库的机器上也能验证「不勾选就不给注册」。
 */

test('assertLegalConsent rejects a missing consent payload', () => {
  assert.throws(
    () => assertLegalConsent(undefined),
    (err: unknown) => {
      assert.ok(err instanceof ApiError)
      assert.equal(err.status, 400)
      assert.equal(err.code, 'LEGAL_CONSENT_REQUIRED')
      return true
    },
  )
})

test('assertLegalConsent rejects when only one box is ticked', () => {
  assert.throws(() => assertLegalConsent({ termsAccepted: true }), /用户协议/)
  assert.throws(() => assertLegalConsent({ privacyAccepted: true }), /用户协议/)
})

test('assertLegalConsent rejects an explicit false and a null', () => {
  assert.throws(() => assertLegalConsent({ termsAccepted: false, privacyAccepted: true }))
  assert.throws(() => assertLegalConsent({ termsAccepted: null, privacyAccepted: null }))
})

test('assertLegalConsent accepts only when both boxes are explicitly ticked', () => {
  assert.doesNotThrow(() => assertLegalConsent({ termsAccepted: true, privacyAccepted: true }))
})

test('legalConsentSchema parses a full consent payload', () => {
  const parsed = legalConsentSchema.parse({ termsAccepted: true, privacyAccepted: true })
  assert.equal(parsed.termsAccepted, true)
  assert.equal(parsed.privacyAccepted, true)
})

test('legalConsentSchema allows the null that kotlinx.serialization sends', () => {
  // 客户端 explicitNulls=true,未勾选时下发 {"termsAccepted": null} 而不是省略键;
  // 这里必须放行 null 走到业务层,由 assertLegalConsent 统一回 LEGAL_CONSENT_REQUIRED,
  // 否则用户看到的会是泛泛的「参数校验失败」。
  const parsed = legalConsentSchema.safeParse({ termsAccepted: null, privacyAccepted: null })
  assert.equal(parsed.success, true)
})

test('legalConsentSchema rejects a non-boolean', () => {
  assert.equal(legalConsentSchema.safeParse({ termsAccepted: 'yes', privacyAccepted: true }).success, false)
})

test('policy version and effective date are published for the client to display', () => {
  assert.match(POLICY_VERSION, /^\d{4}-\d{2}$/)
  assert.match(POLICY_EFFECTIVE_DATE, /^\d{4}-\d{2}-\d{2}$/)
  assert.ok(!Number.isNaN(Date.parse(POLICY_EFFECTIVE_DATE)))
})
