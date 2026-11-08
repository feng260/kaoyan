import test from 'node:test'
import assert from 'node:assert/strict'
import { addDays } from './schemas'
import { weekParityForDate } from './document'

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
