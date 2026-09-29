import test from 'node:test'
import assert from 'node:assert/strict'
import { profileInputSchema, SUPPORTED_TARGET_TYPES } from './schemas'
import { normalizeBrief, briefIsEmpty, assessPlanningFacts, documentMatchesSubjects } from './document'

test('rejects a generated document that mentions unconfirmed exam subjects', () => {
  const document = {
    title: '考研计划',
    hero: { badge: '', titleLead: '', titleAccent: '', titleTail: '', subtitle: '', subjects: ['英语一', '数学二'], stats: [] },
    chapters: [{ no: '01', title: '科目规划', blocks: [{ type: 'text' as const, text: '按照 408 题型训练' }] }],
  }
  assert.equal(documentMatchesSubjects(document, ['英语一']), false)
})

/** 用相对今天的日期构造,避免写死日期导致测试随时间失效 */
function dayIso(offsetDays: number): string {
  const d = new Date()
  d.setUTCHours(0, 0, 0, 0)
  d.setUTCDate(d.getUTCDate() + offsetDays)
  return d.toISOString().slice(0, 10)
}

const validPayload = () => ({
  targetType: '考研',
  examDate: dayIso(60),
  dailyMinutes: 180,
  studyWindows: ['上午', '晚上'],
  foundation: '一般',
  weakSubjects: ['数学', '英语'],
})

/** 校验必须失败,且错误落在指定字段上(数组元素错误也算该字段) */
function hasErrorOn(payload: unknown, field: string): boolean {
  const parsed = profileInputSchema.safeParse(payload)
  assert.equal(parsed.success, false, '期望校验失败,实际通过')
  if (parsed.success) return false
  return parsed.error.issues.some(i => i.path[0] === field)
}

test('accepts a complete valid profile and normalizes the exam date to a day', () => {
  const parsed = profileInputSchema.parse(validPayload())
  assert.equal(parsed.targetType, '考研')
  assert.equal(parsed.dailyMinutes, 180)
  assert.deepEqual(parsed.weakSubjects, ['数学', '英语'])
  assert.deepEqual(parsed.studyWindows, ['上午', '晚上'])
  assert.ok(parsed.examDate instanceof Date)
  assert.equal(parsed.examDate.getUTCHours(), 0)
  assert.equal(parsed.examDate.toISOString().slice(0, 10), dayIso(60))
})

test('is idempotent so a second validation pass keeps the already parsed profile valid', () => {
  // 线上事故回归:路由层 parse 一次 → service 层再兜一次。
  // 第二次拿到的 examDate 已经是 Date,不能被「格式错误」拦下。
  const once = profileInputSchema.parse(validPayload())
  const twice = profileInputSchema.parse(once)

  assert.equal(twice.examDate.toISOString(), once.examDate.toISOString())
  assert.equal(twice.examDate.getUTCHours(), 0)
  assert.deepEqual(twice.studyWindows, ['上午', '晚上'])
  assert.deepEqual(twice.weakSubjects, ['数学', '英语'])
  assert.equal(twice.dailyMinutes, 180)
})

test('accepts every supported target type', () => {
  for (const targetType of SUPPORTED_TARGET_TYPES) {
    const parsed = profileInputSchema.parse({ ...validPayload(), targetType })
    assert.equal(parsed.targetType, targetType)
  }
})

test('rejects an unsupported target type with a field-level error', () => {
  assert.ok(hasErrorOn({ ...validPayload(), targetType: '随便编的目标' }, 'targetType'))
})

test('defaults target type and foundation when omitted', () => {
  const payload: Record<string, unknown> = validPayload()
  delete payload.targetType
  delete payload.foundation
  const parsed = profileInputSchema.parse(payload)
  assert.equal(parsed.targetType, '考研')
  assert.equal(parsed.foundation, '一般')
})

test('accepts JSON string arrays for study windows and weak subjects', () => {
  const parsed = profileInputSchema.parse({
    ...validPayload(),
    studyWindows: '["上午","晚上"]',
    weakSubjects: '["数学"]',
  })
  assert.deepEqual(parsed.studyWindows, ['上午', '晚上'])
  assert.deepEqual(parsed.weakSubjects, ['数学'])
})

test('rejects today as the exam date because preparation needs at least a full day', () => {
  assert.ok(hasErrorOn({ ...validPayload(), examDate: dayIso(0) }, 'examDate'))
})

test('rejects a past exam date', () => {
  assert.ok(hasErrorOn({ ...validPayload(), examDate: dayIso(-30) }, 'examDate'))
})

test('rejects an exam date beyond the supported horizon', () => {
  assert.ok(hasErrorOn({ ...validPayload(), examDate: dayIso(365 * 4) }, 'examDate'))
})

test('rejects a non-date exam date', () => {
  assert.ok(hasErrorOn({ ...validPayload(), examDate: '明年三月' }, 'examDate'))
})

test('rejects zero daily minutes', () => {
  assert.ok(hasErrorOn({ ...validPayload(), dailyMinutes: 0 }, 'dailyMinutes'))
})

test('rejects daily minutes beyond a realistic day', () => {
  assert.ok(hasErrorOn({ ...validPayload(), dailyMinutes: 1440 }, 'dailyMinutes'))
})

test('rejects an empty weak subject list', () => {
  assert.ok(hasErrorOn({ ...validPayload(), weakSubjects: [] }, 'weakSubjects'))
})

test('rejects malformed JSON in weak subjects', () => {
  assert.ok(hasErrorOn({ ...validPayload(), weakSubjects: '[数学,' }, 'weakSubjects'))
})

test('rejects malformed JSON in study windows', () => {
  assert.ok(hasErrorOn({ ...validPayload(), studyWindows: '上午,晚上' }, 'studyWindows'))
})

test('rejects an empty study window list', () => {
  assert.ok(hasErrorOn({ ...validPayload(), studyWindows: [] }, 'studyWindows'))
})

test('rejects weak subjects that are only whitespace', () => {
  assert.ok(hasErrorOn({ ...validPayload(), weakSubjects: ['   '] }, 'weakSubjects'))
})

test('allows structured interview facts to be normalized without inventing exam subjects', () => {
  const brief = normalizeBrief({
    examSubjects: [{ name: '英语一', progress: '真题做至 2015 年', scope: '阅读', remainingMinutes: 120, milestone: '完成真题一轮' }],
    availability: [{ weekday: 1, windows: [{ start: '19:00', end: '21:00' }] }],
    fixedCommitments: [], availabilityConfirmed: true, commitmentsConfirmed: true,
  })
  assert.equal(briefIsEmpty(brief), false)
  assert.deepEqual(brief.examSubjects.map(subject => subject.name), ['英语一'])
  assert.equal(brief.availability[0].windows[0].start, '19:00')
  assert.equal(brief.commitmentsConfirmed, true)
})

test('a dated milestone is blocked when its deadline capacity is insufficient even if the exam total fits', () => {
  const brief = normalizeBrief({
    examSubjects: [{ name: '英语一', progress: '已做阅读', scope: '阅读', remainingMinutes: 120,
      milestone: '一轮阅读', milestoneDate: '2026-09-28', milestoneMinutes: 120 }],
    availability: [1, 2].map(weekday => ({ weekday, windows: [{ start: '19:00', end: '20:00' }] })),
    fixedCommitments: [], availabilityConfirmed: true, commitmentsConfirmed: true,
  })
  const result = assessPlanningFacts(brief, new Date('2026-09-28T00:00:00.000Z'), new Date('2026-10-06T00:00:00.000Z'))
  assert.equal(result.availableMinutes, 180)
  assert.deepEqual(result.deficits, [{ subject: '英语一', milestone: '一轮阅读', missingMinutes: 60 }])
})

test('a milestone deadline and target amount must be explicitly confirmed together', () => {
  const brief = normalizeBrief({
    examSubjects: [{ name: '英语一', progress: '已做阅读', scope: '阅读', remainingMinutes: 120,
      milestone: '一轮阅读', milestoneDate: '2026-09-29' }],
    availability: [{ weekday: 1, windows: [{ start: '19:00', end: '22:00' }] }],
    fixedCommitments: [], availabilityConfirmed: true, commitmentsConfirmed: true,
  })
  const result = assessPlanningFacts(brief, new Date('2026-09-28T00:00:00.000Z'), new Date('2026-10-06T00:00:00.000Z'))
  assert.ok(result.missing.includes('英语一的里程碑截止日与目标量'))
})

test('capacity assessment subtracts fixed commitments and names unmet subject milestones', () => {
  const brief = normalizeBrief({
    examSubjects: [
      { name: '英语一', progress: '做过阅读', scope: '阅读', remainingMinutes: 120, milestone: '一轮真题' },
      { name: '自命题 912', progress: '教材第一章', scope: '', remainingMinutes: 240, milestone: '完成大纲章节' },
    ],
    availability: [{ weekday: 1, windows: [{ start: '19:00', end: '21:00' }] }],
    fixedCommitments: [{ weekday: 1, start: '19:00', end: '20:00', label: '固定会议' }],
    availabilityConfirmed: true, commitmentsConfirmed: true,
  })
  const start = new Date('2026-09-28T00:00:00.000Z')
  const result = assessPlanningFacts(brief, start, new Date('2026-10-05T00:00:00.000Z'))
  assert.equal(result.availableMinutes, 60)
  assert.equal(result.ready, false)
  assert.ok(result.deficits.some(d => d.subject === '自命题 912' && d.milestone === '完成大纲章节' && d.missingMinutes > 0))
})

test('confirmed structured facts with adequate capacity pass the quality gate', () => {
  const brief = normalizeBrief({
    examSubjects: [{ name: '英语一', progress: '真题做至 2015 年', scope: '阅读', remainingMinutes: 60, milestone: '真题一轮', milestoneDate: '2026-09-28', milestoneMinutes: 60 }],
    availability: [{ weekday: 1, windows: [{ start: '19:00', end: '21:00' }] }],
    fixedCommitments: [], availabilityConfirmed: true, commitmentsConfirmed: true,
  })
  const result = assessPlanningFacts(brief, new Date('2026-09-28T00:00:00.000Z'), new Date('2026-10-05T00:00:00.000Z'))
  assert.equal(result.ready, true)
  assert.equal(result.availableMinutes, 120)
})
