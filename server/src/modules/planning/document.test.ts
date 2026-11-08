import test from 'node:test'
import assert from 'node:assert/strict'
import { addDays } from './schemas'
import { EMPTY_BRIEF, mergeBrief, normalizeBrief, weekParityForDate } from './document'

const utc = (day: string) => new Date(`${day}T00:00:00.000Z`)

test('weekParityForDate: 锚点当周为奇周,下一周为偶周', () => {
  // 2026-09-07 是周一(产品负责人那份 468 天计划就是按这个日子当校历第 1 周)
  const anchor = '2026-09-07'
  assert.equal(weekParityForDate(anchor, utc('2026-09-07')), 'odd')
  assert.equal(weekParityForDate(anchor, utc('2026-09-13')), 'odd')
  assert.equal(weekParityForDate(anchor, utc('2026-09-14')), 'even')
  assert.equal(weekParityForDate(anchor, utc('2026-09-20')), 'even')
  assert.equal(weekParityForDate(anchor, utc('2026-09-21')), 'odd')
})

test('weekParityForDate: 锚点填的不是周一时自动归一到所在周的周一', () => {
  // 用户填了周三 2026-09-09,不能让整张课表错位两天
  const anchorWednesday = '2026-09-09'
  assert.equal(weekParityForDate(anchorWednesday, utc('2026-09-07')), 'odd')
  assert.equal(weekParityForDate(anchorWednesday, utc('2026-09-14')), 'even')
  // 与直接填周一 2026-09-07 的结果逐日一致
  for (let offset = 0; offset < 60; offset++) {
    const day = addDays(utc('2026-09-07'), offset)
    assert.equal(
      weekParityForDate(anchorWednesday, day),
      weekParityForDate('2026-09-07', day),
      `第 ${offset} 天不一致`,
    )
  }
})

test('weekParityForDate: 锚点为空或非法时返回 null(调用方退回每周都占)', () => {
  assert.equal(weekParityForDate('', utc('2026-09-14')), null)
  assert.equal(weekParityForDate('2026-9-7', utc('2026-09-14')), null)
  assert.equal(weekParityForDate('下周一', utc('2026-09-14')), null)
  assert.equal(weekParityForDate('2026-02-30', utc('2026-09-14')), null)
})

test('weekParityForDate: 早于锚点所在周的日期返回 null', () => {
  // 计划起点早于开学日是常事(暑期就开始备考),那段日子没有单双周可言
  assert.equal(weekParityForDate('2026-09-07', utc('2026-08-31')), null)
  assert.equal(weekParityForDate('2026-09-07', utc('2026-09-06')), null)
})

test('weekParityForDate: 跨月与跨年连续交替不断档', () => {
  const anchor = '2026-12-28' // 周一
  const expected = (offset: number) => (Math.floor(offset / 7) % 2 === 0 ? 'odd' : 'even')
  for (let offset = 0; offset < 400; offset++) {
    assert.equal(
      weekParityForDate(anchor, addDays(utc(anchor), offset)),
      expected(offset),
      `偏移 ${offset} 天出错`,
    )
  }
})

test('normalizeBrief: 保留占用的单双周标记与校历锚点', () => {
  const brief = normalizeBrief({
    availability: [{ weekday: 1, windows: [{ start: '08:00', end: '12:00' }] }],
    fixedCommitments: [
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' },
      { weekday: 5, start: '10:00', end: '11:40', label: '编译原理' },
    ],
    termWeekOneStart: '2026-09-07',
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  })
  assert.equal(brief.fixedCommitments[0].weekParity, 'odd')
  assert.equal(brief.fixedCommitments[1].weekParity, undefined)
  assert.equal(brief.termWeekOneStart, '2026-09-07')
})

test('normalizeBrief: 非法 parity 只丢属性不丢整条占用', () => {
  const brief = normalizeBrief({
    fixedCommitments: [
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: '单周' },
      { weekday: 3, start: '10:00', end: '11:40', label: '数据结构', weekParity: 'even' },
    ],
    commitmentsConfirmed: true,
  })
  assert.equal(brief.fixedCommitments.length, 2, '两条占用都该留下')
  assert.equal(brief.fixedCommitments[0].weekParity, undefined)
  assert.equal(brief.fixedCommitments[1].weekParity, 'even')
})

test('normalizeBrief: 非法锚点归一为空串', () => {
  assert.equal(normalizeBrief({ termWeekOneStart: '2026-9-7' }).termWeekOneStart, '')
  assert.equal(normalizeBrief({ termWeekOneStart: '下周一' }).termWeekOneStart, '')
  assert.equal(normalizeBrief({}).termWeekOneStart, '')
  assert.equal(EMPTY_BRIEF.termWeekOneStart, '')
})

test('mergeBrief: 同一时段不同单双周算两条,不去重掉', () => {
  const base = normalizeBrief({
    fixedCommitments: [{ weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' }],
    commitmentsConfirmed: true,
  })
  const patch = normalizeBrief({
    fixedCommitments: [{ weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'even' }],
    commitmentsConfirmed: true,
  })
  const merged = mergeBrief(base, patch)
  assert.equal(merged.fixedCommitments.length, 2)
  assert.deepEqual(merged.fixedCommitments.map(item => item.weekParity), ['odd', 'even'])
})

test('mergeBrief: 补丁的锚点非空时覆盖,为空时保留旧值', () => {
  const base = normalizeBrief({ termWeekOneStart: '2026-09-07' })
  assert.equal(mergeBrief(base, normalizeBrief({ termWeekOneStart: '' })).termWeekOneStart, '2026-09-07')
  assert.equal(mergeBrief(base, normalizeBrief({ termWeekOneStart: '2027-02-22' })).termWeekOneStart, '2027-02-22')
})
