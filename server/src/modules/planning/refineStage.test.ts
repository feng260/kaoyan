import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeWeekVariants } from './refineStage'

const SUBJECTS = ['申论', '行测']

test('keeps valid tasks and clamps minutes into the sane range', () => {
  const variants = normalizeWeekVariants({
    weeks: [[
      { weekday: 1, subject: '申论', title: '归纳概括:审题与要点定位方法笔记', minutes: 45 },
      { weekday: 2, subject: '行测', title: '资料分析限时训练:20 题对 15 题', minutes: 200 },
      { weekday: 3, subject: '申论', title: '大作文成篇写作练习', minutes: 5 },
      { weekday: 4, subject: '申论', title: '错题回顾', minutes: Number.NaN },
    ]],
  }, SUBJECTS)
  assert.equal(variants.length, 1)
  const week = variants[0]
  assert.deepEqual(week.map(task => task.minutes), [45, 120, 15, 45])
  assert.equal(week[1].title, '资料分析限时训练:20 题对 15 题')
})

test('drops tasks with bad weekday, unconfirmed subject or empty title', () => {
  const variants = normalizeWeekVariants({
    weeks: [[
      { weekday: 0, subject: '申论', title: '周日不存在' },
      { weekday: 8, subject: '申论', title: '周九也不存在' },
      { weekday: 1, subject: '面试培训', title: '未确认科目' },
      { weekday: 1, subject: '申论', title: '   ' },
      { weekday: 1, subject: '申论', title: '合法任务' },
      { weekday: 1, subject: '行测', title: '另一个合法任务' },
    ]],
  }, SUBJECTS)
  assert.equal(variants.length, 1)
  assert.deepEqual(variants[0].map(task => task.title), ['合法任务', '另一个合法任务'])
})

test('truncates long titles to 64 chars and keeps at most 8 non-empty variants', () => {
  const longTitle = '标'.repeat(70)
  const weeks = Array.from({ length: 11 }, (_, index) => [
    { weekday: 1, subject: '申论', title: `#${index}-${longTitle}`, minutes: 45 },
  ])
  const variants = normalizeWeekVariants({ weeks }, SUBJECTS)
  assert.equal(variants.length, 8)
  assert.equal(variants[0][0].title.length, 64)
  assert.ok(variants[0][0].title.startsWith('#0-'))
  assert.ok(variants[7][0].title.startsWith('#7-'))
})

test('returns an empty array when nothing survives so the caller degrades to templates', () => {
  assert.deepEqual(normalizeWeekVariants({ weeks: [[{ weekday: 1, subject: '申论', title: '' }]] }, SUBJECTS), [])
  assert.deepEqual(normalizeWeekVariants(null, SUBJECTS), [])
  assert.deepEqual(normalizeWeekVariants({ weeks: '不是数组' }, SUBJECTS), [])
})
