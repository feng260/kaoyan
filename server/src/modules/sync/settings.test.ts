import test from 'node:test'
import assert from 'node:assert/strict'
import { parseSettingsRows, settingsEntries } from './settings'

/**
 * 备份/导出里的 settings 解码。
 * 曾经的线上事故:plan_reminder_day 这类服务端裸写的行让 JSON.parse 抛异常,
 * 整个 /sync/backup 和 /account/export 直接 500 —— 也就是用户点「导出」只会拿到空文件。
 */

test('decodes JSON values written by the App', () => {
  const settings = parseSettingsRows([
    { key: 'theme', value: '"dark"' },
    { key: 'pomodoro_minutes', value: '25' },
    { key: 'strict_mode', value: 'true' },
    { key: 'goal', value: '{"minutes":180}' },
    { key: 'cleared', value: 'null' },
  ])
  assert.deepEqual(settings, {
    theme: 'dark',
    pomodoro_minutes: 25,
    strict_mode: true,
    goal: { minutes: 180 },
    cleared: null,
  })
})

test('keeps raw non-JSON values instead of throwing', () => {
  const settings = parseSettingsRows([
    { key: 'plan_reminder_day', value: '2026-11-01' },
    { key: 'plan_reminder_day_empty', value: '' },
    { key: 'theme', value: '"dark"' },
  ])
  assert.equal(settings.plan_reminder_day, '2026-11-01')
  assert.equal(settings.plan_reminder_day_empty, '')
  assert.equal(settings.theme, 'dark')
})

test('an empty table decodes to an empty object', () => {
  assert.deepEqual(parseSettingsRows([]), {})
})

test('missing or empty settings are not a replacement', () => {
  assert.deepEqual(settingsEntries(undefined), [])
  assert.deepEqual(settingsEntries(null), [])
  assert.deepEqual(settingsEntries({}), [])
  assert.deepEqual(settingsEntries([]), [])
  assert.deepEqual(settingsEntries('x'), [])
})

test('object settings become key/value entries', () => {
  assert.deepEqual(settingsEntries({ theme: 'dark', pomodoro_minutes: 25 }), [
    ['theme', 'dark'],
    ['pomodoro_minutes', 25],
  ])
})
