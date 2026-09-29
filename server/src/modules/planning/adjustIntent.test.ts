import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeAdjustIntent, type AdjustIntentInput } from './adjustIntent'

const input: AdjustIntentInput = {
  message: '明天有事',
  today: '2026-09-29',
  windowTo: '2026-10-06',
  subjects: ['数学', '英语'],
  brief: null,
}

test('unavailable:窗口外日期被丢弃、去重,正常字段保留', () => {
  const intent = normalizeAdjustIntent({
    kind: 'unavailable',
    days: ['2026-09-30', '2026-10-10', 'bad', '2026-09-30'],
    note: '家里有事',
    summary: '9月30日没空',
  }, input)
  assert.equal(intent.kind, 'unavailable')
  assert.deepEqual(intent.days, ['2026-09-30'])
  assert.equal(intent.note, '家里有事')
  assert.equal(intent.summary, '9月30日没空')
  assert.equal(intent.needClarify, false)
})

test('数据撑不起 kind → 降级为 chat', () => {
  assert.equal(normalizeAdjustIntent({ kind: 'unavailable', days: [] }, input).kind, 'chat')
  assert.equal(normalizeAdjustIntent({ kind: 'reduce_capacity', windows: [] }, input).kind, 'chat')
  assert.equal(normalizeAdjustIntent({ kind: 'change_commitment', commitments: [] }, input).kind, 'chat')
  assert.equal(normalizeAdjustIntent({ kind: 'whatever' }, input).kind, 'chat')
})

test('needClarify 优先于降级,追问原样透传', () => {
  const intent = normalizeAdjustIntent(
    { kind: 'unavailable', days: [], needClarify: true, clarifyQuestion: '哪天没空?' }, input)
  assert.equal(intent.needClarify, true)
  assert.equal(intent.clarifyQuestion, '哪天没空?')
  assert.equal(intent.kind, 'unavailable')
})

test('windows:越界/负数/重复日被过滤,分钟取整', () => {
  const intent = normalizeAdjustIntent({
    kind: 'reduce_capacity',
    windows: [
      { day: '2026-09-30', minutes: 90.6 },
      { day: '2026-10-10', minutes: 60 },
      { day: '2026-09-30', minutes: 30 },
      { day: '2026-10-01', minutes: -5 },
    ],
  }, input)
  assert.deepEqual(intent.windows, [{ day: '2026-09-30', minutes: 91 }])
})

test('commitments:非法星期/时间段被过滤,label 缺省兜底', () => {
  const intent = normalizeAdjustIntent({
    kind: 'change_commitment',
    commitments: [
      { weekday: 1, start: '19:00', end: '21:00', label: '晚自习' },
      { weekday: 9, start: '19:00', end: '21:00', label: 'x' },
      { weekday: 2, start: '21:00', end: '19:00', label: 'x' },
      { weekday: 3, start: '08:00', end: '10:00' },
    ],
  }, input)
  assert.deepEqual(intent.commitments, [
    { weekday: 1, start: '19:00', end: '21:00', label: '晚自习' },
    { weekday: 3, start: '08:00', end: '10:00', label: '固定占用' },
  ])
})

test('summary 缺省时按 kind 生成兜底文案', () => {
  const intent = normalizeAdjustIntent({ kind: 'unavailable', days: ['2026-09-30'] }, input)
  assert.equal(intent.summary, '09月30日没空,把任务顺延')
})
