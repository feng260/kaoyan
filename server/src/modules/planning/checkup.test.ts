import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeBrief } from './document'
import {
  buildCheckup, emptyCheckup, normalizeTzOffset, shouldNotifyPlanReminder,
  DEFAULT_TZ_OFFSET,
} from './checkup'

const confirmedBrief = () => normalizeBrief({
  examSubjects: [],
  availability: [1, 2, 3, 4, 5, 6, 7].map(weekday => ({
    weekday, windows: [{ start: '19:00', end: '22:00' }],
  })),
  fixedCommitments: [],
  availabilityConfirmed: true,
  commitmentsConfirmed: true,
})

test('normalizeTzOffset 非法值回退 480', () => {
  assert.equal(normalizeTzOffset('480'), 480)
  assert.equal(normalizeTzOffset('-300'), -300)
  assert.equal(normalizeTzOffset('abc'), DEFAULT_TZ_OFFSET)
  assert.equal(normalizeTzOffset(900), DEFAULT_TZ_OFFSET)
  assert.equal(normalizeTzOffset(undefined), DEFAULT_TZ_OFFSET)
})

test('notify=0/false 时不触发提醒', () => {
  assert.equal(shouldNotifyPlanReminder(undefined), true)
  assert.equal(shouldNotifyPlanReminder('1'), true)
  assert.equal(shouldNotifyPlanReminder('0'), false)
  assert.equal(shouldNotifyPlanReminder(0), false)
  assert.equal(shouldNotifyPlanReminder('false'), false)
})

test('buildCheckup 汇总本周完成、上周专注与未来负荷', () => {
  const summary = buildCheckup({
    now: new Date('2026-03-11T12:00:00.000Z'), // 周三
    tzOffset: 0,
    brief: confirmedBrief(),
    items: [
      { planDate: '2026-03-09', minutes: 60, status: 'done' },
      { planDate: '2026-03-10', minutes: 90, status: 'pending' },
      { planDate: '2026-03-11', minutes: 30, status: 'pending' },
      { planDate: '2026-03-12', minutes: 300, status: 'pending' },
    ],
    sessions: [
      { startedAt: Date.parse('2026-03-09T20:00:00.000Z'), durationMin: 50, valid: true, isDeleted: false },
      { startedAt: Date.parse('2026-03-10T20:00:00.000Z'), durationMin: 25, valid: true, isDeleted: false },
      { startedAt: Date.parse('2026-03-04T20:00:00.000Z'), durationMin: 40, valid: true, isDeleted: false },
      { startedAt: Date.parse('2026-03-09T21:00:00.000Z'), durationMin: 99, valid: false, isDeleted: false },
    ],
  })
  assert.equal(summary.weekPlanTotal, 4)
  assert.equal(summary.weekPlanDone, 1)
  assert.equal(summary.weekFocusMinutes, 75)
  assert.equal(summary.lastWeekFocusMinutes, 40)
  assert.equal(summary.weekActiveDays, 2)
  assert.equal(summary.overdueCount, 1)
  assert.equal(summary.behindMinutes, 90)
  assert.equal(summary.next7dPlannedMinutes, 300)
  assert.equal(summary.next7dCapacityMinutes, 7 * 180)
  assert.equal(summary.overloadedDays.length, 1)
  assert.equal(summary.overloadedDays[0].date, '2026-03-12')
  assert.equal(summary.overloadedDays[0].planned, 300)
  assert.equal(summary.overloadedDays[0].capacity, 180)
  assert.ok(summary.suggestion.includes('过去'))
  assert.ok(summary.suggestion.includes('超过'))
  assert.ok(summary.suggestion.endsWith('去调整一下。'))
})

test('时区偏移决定本地日:UTC 晚间在东八区算次日', () => {
  const summary = buildCheckup({
    now: new Date('2026-03-11T18:00:00.000Z'), // 东八区 3/12 02:00
    tzOffset: 480,
    brief: confirmedBrief(),
    items: [
      { planDate: '2026-03-11', minutes: 60, status: 'pending' },
      { planDate: '2026-03-12', minutes: 30, status: 'pending' },
    ],
    sessions: [
      // UTC 3/11 16:30 = 本地 3/12 00:30,必须归到本周四
      { startedAt: Date.parse('2026-03-11T16:30:00.000Z'), durationMin: 25, valid: true, isDeleted: false },
    ],
  })
  assert.equal(summary.overdueCount, 1)
  assert.equal(summary.behindMinutes, 60)
  assert.equal(summary.weekPlanTotal, 2)
  assert.equal(summary.weekFocusMinutes, 25)
  assert.equal(summary.weekActiveDays, 1)
})

test('健康时 suggestion 必须为空串（保通知闸门）', () => {
  const summary = buildCheckup({
    now: new Date('2026-03-11T12:00:00.000Z'),
    tzOffset: 0,
    brief: confirmedBrief(),
    items: [{ planDate: '2026-03-11', minutes: 60, status: 'pending' }],
    sessions: [],
  })
  assert.equal(summary.overdueCount, 0)
  assert.equal(summary.overloadedDays.length, 0)
  assert.equal(summary.suggestion, '')
})

test('未确认空闲时不判超载', () => {
  const brief = confirmedBrief()
  brief.availabilityConfirmed = false
  const summary = buildCheckup({
    now: new Date('2026-03-11T12:00:00.000Z'),
    tzOffset: 0,
    brief,
    items: [{ planDate: '2026-03-12', minutes: 600, status: 'pending' }],
    sessions: [],
  })
  assert.equal(summary.overloadedDays.length, 0)
  assert.equal(summary.next7dCapacityMinutes, 0)
  assert.equal(summary.suggestion, '')
})

test('单项问题用趋势做第二句:比上周多', () => {
  const summary = buildCheckup({
    now: new Date('2026-03-11T12:00:00.000Z'),
    tzOffset: 0,
    brief: confirmedBrief(),
    items: [{ planDate: '2026-03-10', minutes: 60, status: 'pending' }],
    sessions: [
      { startedAt: Date.parse('2026-03-09T20:00:00.000Z'), durationMin: 75, valid: true, isDeleted: false },
      { startedAt: Date.parse('2026-03-04T20:00:00.000Z'), durationMin: 40, valid: true, isDeleted: false },
    ],
  })
  assert.equal(summary.overdueCount, 1)
  assert.ok(summary.suggestion.includes('本周专注比上周多 88%'))
})

test('单项问题用趋势做第二句:比上周少', () => {
  const summary = buildCheckup({
    now: new Date('2026-03-11T12:00:00.000Z'),
    tzOffset: 0,
    brief: confirmedBrief(),
    items: [{ planDate: '2026-03-10', minutes: 60, status: 'pending' }],
    sessions: [
      { startedAt: Date.parse('2026-03-04T20:00:00.000Z'), durationMin: 40, valid: true, isDeleted: false },
    ],
  })
  assert.ok(summary.suggestion.includes('本周专注比上周少 100%'))
})

test('上周没有专注时用开始积累的文案', () => {
  const summary = buildCheckup({
    now: new Date('2026-03-11T12:00:00.000Z'),
    tzOffset: 0,
    brief: confirmedBrief(),
    items: [{ planDate: '2026-03-10', minutes: 60, status: 'pending' }],
    sessions: [
      { startedAt: Date.parse('2026-03-09T20:00:00.000Z'), durationMin: 30, valid: true, isDeleted: false },
    ],
  })
  assert.ok(summary.suggestion.includes('本周已开始积累专注记录'))
})

test('emptyCheckup 返回完整零值', () => {
  assert.deepEqual(emptyCheckup(), {
    behindMinutes: 0, overdueCount: 0, suggestion: '', suggestedMessage: null,
    weekPlanDone: 0, weekPlanTotal: 0, weekFocusMinutes: 0,
    lastWeekFocusMinutes: 0, weekActiveDays: 0,
    next7dPlannedMinutes: 0, next7dCapacityMinutes: 0, overloadedDays: [],
  })
})
