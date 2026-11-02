import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeApkMeta, validateRelease, parseBoolFlag, EXPECTED_APP_PACKAGE } from './release'

test('normalizeApkMeta 接受数字或字符串 versionCode', () => {
  assert.deepEqual(
    normalizeApkMeta({ package: EXPECTED_APP_PACKAGE, versionCode: 12, versionName: '1.0.12' }),
    { packageName: EXPECTED_APP_PACKAGE, versionCode: 12, versionName: '1.0.12' }
  )
  assert.deepEqual(
    normalizeApkMeta({ package: ' com.yanzhong.app ', versionCode: '13', versionName: ' 1.0.13 ' }),
    { packageName: EXPECTED_APP_PACKAGE, versionCode: 13, versionName: '1.0.13' }
  )
})

test('normalizeApkMeta 拒绝缺失包名或非法 versionCode', () => {
  assert.equal(normalizeApkMeta({ versionCode: 1, versionName: 'x' }), null)
  assert.equal(normalizeApkMeta({ package: EXPECTED_APP_PACKAGE, versionCode: 0, versionName: 'x' }), null)
  assert.equal(normalizeApkMeta({ package: EXPECTED_APP_PACKAGE, versionCode: 1.5, versionName: 'x' }), null)
  assert.equal(normalizeApkMeta({ package: EXPECTED_APP_PACKAGE, versionCode: 'abc', versionName: 'x' }), null)
})

test('validateRelease 只接受研钟且版本号严格递增', () => {
  const meta = { packageName: EXPECTED_APP_PACKAGE, versionCode: 12, versionName: '1.0.12' }
  assert.equal(validateRelease(meta, null), null)
  assert.equal(validateRelease(meta, 11), null)
  assert.match(validateRelease(meta, 12) ?? '', /必须大于/)
  assert.match(validateRelease(meta, 20) ?? '', /必须大于/)
  assert.match(
    validateRelease({ ...meta, packageName: 'com.example.other' }, null) ?? '',
    /不是研钟/
  )
})

test('parseBoolFlag 只把明确真值识别为 true', () => {
  for (const raw of [true, 1, 'true', '1']) assert.equal(parseBoolFlag(raw), true)
  for (const raw of [false, 0, 'false', '0', '', undefined, null]) {
    assert.equal(parseBoolFlag(raw), false)
  }
})
