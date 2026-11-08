import test from 'node:test'
import assert from 'node:assert/strict'
import { addDays } from './schemas'
import {
  EMPTY_BRIEF, freeSlots, mergeBrief, netAvailableMinutes, normalizeBrief, weekParityForDate,
} from './document'

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
  // 日溢出:V8 会把 2026-02-30 静默进位成 3 月 2 日,只有 isoDay 的往返校验挡得住。
  // 放进档案就会让整张课表的奇偶平移两天,必须归一为空串(退回每周都占)。
  assert.equal(normalizeBrief({ termWeekOneStart: '2026-02-30' }).termWeekOneStart, '')
  assert.equal(normalizeBrief({ termWeekOneStart: '2026-13-01' }).termWeekOneStart, '')
  // 裁定 1 接受的不 trim 口径:带空格的脏值判不出奇偶,失败方向是保守的
  assert.equal(normalizeBrief({ termWeekOneStart: ' 2026-09-07 ' }).termWeekOneStart, '')
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

/** 周三全天 08:00-18:00 有空,一条 08:00-09:40 的课;按 parity 决定是否扣掉 */
function parityBrief(weekParity?: 'odd' | 'even', termWeekOneStart = '2026-09-07') {
  return normalizeBrief({
    availability: [{ weekday: 3, windows: [{ start: '08:00', end: '18:00' }] }],
    fixedCommitments: [weekParity
      ? { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity }
      : { weekday: 3, start: '08:00', end: '09:40', label: '人工智能' }],
    termWeekOneStart,
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  })
}

test('netAvailableMinutes: 双周的课只在偶周扣掉容量', () => {
  const brief = parityBrief('even')
  // 2026-09-16 是第 2 周的周三(偶周) → 扣掉 100 分钟
  assert.equal(netAvailableMinutes(brief, utc('2026-09-16')), 600 - 100)
  // 2026-09-23 是第 3 周的周三(奇周) → 不扣
  assert.equal(netAvailableMinutes(brief, utc('2026-09-23')), 600)
})

test('netAvailableMinutes: 单周的课只在奇周扣掉容量', () => {
  const brief = parityBrief('odd')
  assert.equal(netAvailableMinutes(brief, utc('2026-09-16')), 600)
  assert.equal(netAvailableMinutes(brief, utc('2026-09-23')), 600 - 100)
})

test('netAvailableMinutes: 不标 parity 的课每周都扣(旧行为不破)', () => {
  const brief = parityBrief(undefined)
  assert.equal(netAvailableMinutes(brief, utc('2026-09-16')), 600 - 100)
  assert.equal(netAvailableMinutes(brief, utc('2026-09-23')), 600 - 100)
})

test('netAvailableMinutes: 标了 parity 但没填锚点时保守按每周都占', () => {
  const brief = parityBrief('even', '')
  assert.equal(netAvailableMinutes(brief, utc('2026-09-16')), 600 - 100)
  assert.equal(netAvailableMinutes(brief, utc('2026-09-23')), 600 - 100)
})

test('netAvailableMinutes: 早于开学周的日期退回保守口径', () => {
  const brief = parityBrief('even')
  // 2026-09-02 是开学前的周三,weekParityForDate 返回 null → 照常扣
  assert.equal(netAvailableMinutes(brief, utc('2026-09-02')), 600 - 100)
})

test('freeSlots: 双周有课的那天空闲从课后开始,奇周是一整段', () => {
  const brief = parityBrief('even')
  assert.deepEqual(freeSlots(brief, utc('2026-09-16')), [{ start: '09:40', end: '18:00' }])
  assert.deepEqual(freeSlots(brief, utc('2026-09-23')), [{ start: '08:00', end: '18:00' }])
})

test('daySchedule 的假期口径不变:假期里平时的单双周占用都不生效', () => {
  const brief = parityBrief('even')
  brief.periods = [{
    start: '2026-09-14', end: '2026-09-20', kind: 'vacation', note: '中秋假',
    availability: [{ weekday: 3, windows: [{ start: '08:00', end: '18:00' }] }],
    fixedCommitments: [],
  }]
  // 假期区间内 dropWeeklyCommitments 生效,连偶周的课也不扣
  assert.equal(netAvailableMinutes(brief, utc('2026-09-16')), 600)
})

test('daySchedule: 区间内的占用同样按单双周过滤', () => {
  // normalizePeriods 复用 normalizeCommitments,所以区间里的课也带 parity;
  // 只给平时占用加过滤、漏掉区间这一侧,冲刺周里的双周课照样会被算成每周都占。
  const brief = parityBrief(undefined)
  brief.periods = [{
    start: '2026-09-14', end: '2026-09-27', kind: 'sprint', note: '冲刺闭关',
    availability: [{ weekday: 3, windows: [{ start: '08:00', end: '18:00' }] }],
    fixedCommitments: [
      { weekday: 3, start: '14:00', end: '15:40', label: '模拟考', weekParity: 'even' },
    ],
  }]
  // 2026-09-16 是第 2 周的周三(偶周):平时那条不带 parity 的课扣 100,区间里那条双周占用再扣 100
  assert.equal(netAvailableMinutes(brief, utc('2026-09-16')), 600 - 100 - 100)
  // 2026-09-23 是第 3 周的周三(奇周):区间里那条双周占用不生效,只扣平时那条
  assert.equal(netAvailableMinutes(brief, utc('2026-09-23')), 600 - 100)
})

test('normalizeBrief: 课表识别的原始 JSON 能带着单双周穿过收敛层', () => {
  // timetable.ts 用 normalizeBrief 收敛模型输出,parity 必须活下来
  const brief = normalizeBrief({
    fixedCommitments: [
      { weekday: 1, start: '08:00', end: '09:40', label: '高等数学' },
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' },
      { weekday: 3, start: '10:00', end: '11:40', label: '课程(待确认)', weekParity: 'EVEN' },
    ],
    commitmentsConfirmed: true,
  })
  assert.equal(brief.fixedCommitments.length, 3)
  assert.equal(brief.fixedCommitments[0].weekParity, undefined)
  assert.equal(brief.fixedCommitments[1].weekParity, 'odd')
  assert.equal(brief.fixedCommitments[2].weekParity, undefined, '大写 EVEN 属非法值,只丢属性')
})
