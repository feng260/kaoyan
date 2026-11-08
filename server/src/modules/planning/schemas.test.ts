import test from 'node:test'
import assert from 'node:assert/strict'
import { briefPatchSchema, profileInputSchema, SUPPORTED_TARGET_TYPES } from './schemas'
import { normalizeBrief, briefIsEmpty, assessPlanningFacts, documentMatchesSubjects, mergeBrief } from './document'
import { KAOYAN_KNOWN_SUBJECT_NAMES } from './planExemplar'

test('rejects a generated document that mentions unconfirmed exam subjects', () => {
  const document = {
    title: '考研计划',
    hero: { badge: '', titleLead: '', titleAccent: '', titleTail: '', subtitle: '', subjects: ['英语一', '数学二'], stats: [] },
    chapters: [{ no: '01', title: '科目规划', blocks: [{ type: 'text' as const, text: '按照 408 题型训练' }] }],
  }
  assert.equal(documentMatchesSubjects(document, ['英语一'], KAOYAN_KNOWN_SUBJECT_NAMES), false)
})

test('a civil-service document mentioning 政治 passes because the guard list is target-specific', () => {
  const document = {
    title: '考公计划',
    hero: { badge: '', titleLead: '', titleAccent: '', titleTail: '', subtitle: '', subjects: ['申论', '行政职业能力测验'], stats: [] },
    chapters: [{ no: '01', title: '科目规划', blocks: [{ type: 'text' as const, text: '政治理论学习与申论范文精读交替安排' }] }],
  }
  assert.equal(documentMatchesSubjects(document, ['申论', '行政职业能力测验'], []), true)
})

test('a confirmed timetable is locked: interview patches cannot shrink it', () => {
  // 面谈模型某轮把某天窗口写少,曾按 weekday 整日替换,静默收窄确认过的净空闲
  // (计划从每天 3 小时缩到 50 分钟) —— 课表一经确认必须锁定,改作息走备考档案
  const base = normalizeBrief({
    availability: [
      { weekday: 1, windows: [{ start: '08:30', end: '11:30' }] },
      { weekday: 2, windows: [{ start: '08:30', end: '11:30' }, { start: '19:00', end: '21:00' }] },
    ],
    availabilityConfirmed: true,
  })
  const patch = normalizeBrief({
    availability: [{ weekday: 1, windows: [{ start: '08:00', end: '08:50' }] }],
    availabilityConfirmed: true,
  })
  const merged = mergeBrief(base, patch)
  assert.deepEqual(merged.availability, base.availability)
  assert.equal(merged.availabilityConfirmed, true)
})

test('an unconfirmed timetable still merges interview windows in', () => {
  const base = normalizeBrief({
    availability: [{ weekday: 1, windows: [{ start: '08:30', end: '11:30' }] }],
  })
  const patch = normalizeBrief({
    availability: [{ weekday: 2, windows: [{ start: '19:00', end: '21:00' }] }],
  })
  const merged = mergeBrief(base, patch)
  assert.deepEqual(merged.availability.map(day => day.weekday).sort(), [1, 2])
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

test('AI-estimated subject details do not block wrap-up but must stay marked', () => {
  const brief = normalizeBrief({
    // 逐科细节全缺、只有科目名是确认过的:模型按经验估,estimated=true
    examSubjects: [{ name: '英语一', estimated: true }],
    availability: [{ weekday: 1, windows: [{ start: '19:00', end: '21:00' }] }],
    fixedCommitments: [], availabilityConfirmed: true, commitmentsConfirmed: true,
  })
  assert.equal(brief.examSubjects[0].estimated, true)
  const result = assessPlanningFacts(brief, new Date('2026-09-28T00:00:00.000Z'), new Date('2026-10-05T00:00:00.000Z'))
  assert.equal(result.ready, true)
  assert.deepEqual(result.missing, [])
  assert.deepEqual(result.deficits, [])

  // 同一份内容去掉估计标记后必须重新被门槛拦住
  const confirmed = normalizeBrief({
    examSubjects: [{ name: '英语一' }],
    availability: [{ weekday: 1, windows: [{ start: '19:00', end: '21:00' }] }],
    fixedCommitments: [], availabilityConfirmed: true, commitmentsConfirmed: true,
  })
  const blocked = assessPlanningFacts(confirmed, new Date('2026-09-28T00:00:00.000Z'), new Date('2026-10-05T00:00:00.000Z'))
  assert.equal(blocked.ready, false)
  assert.ok(blocked.missing.includes('英语一的进度与里程碑'))
})

test('an estimated milestone is kept but drops out of the capacity gate', () => {
  const brief = normalizeBrief({
    examSubjects: [{
      name: '英语一', progress: '按经验估计已过一轮', scope: '阅读',
      remainingMinutes: 600, milestone: '完成真题一轮',
      milestoneDate: '2026-09-28', milestoneMinutes: 600, estimated: true,
    }],
    availability: [{ weekday: 1, windows: [{ start: '19:00', end: '20:00' }] }],
    fixedCommitments: [], availabilityConfirmed: true, commitmentsConfirmed: true,
  })
  const result = assessPlanningFacts(brief, new Date('2026-09-28T00:00:00.000Z'), new Date('2026-10-06T00:00:00.000Z'))
  // 区间内只有 09-28、10-05 两个周一各 60 分钟:容量远不够,但估计项不参与缺口计算,只要求考生之后在草稿里更正
  assert.equal(result.availableMinutes, 120)
  assert.equal(result.ready, true)
  assert.deepEqual(result.deficits, [])
})

test('profileInputSchema: 接受校历第 1 周与占用的单双周标记', () => {
  const parsed = profileInputSchema.parse({
    ...validPayload(),
    termWeekOneStart: '2026-09-07',
    fixedCommitments: [
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' },
      { weekday: 5, start: '10:00', end: '11:40', label: '编译原理' },
    ],
    commitmentsConfirmed: true,
  })
  assert.equal(parsed.termWeekOneStart, '2026-09-07')
  assert.equal(parsed.fixedCommitments[0].weekParity, 'odd')
  assert.equal(parsed.fixedCommitments[1].weekParity, undefined)
})

test('profileInputSchema: 老客户端不带新字段时锚点默认空串', () => {
  const parsed = profileInputSchema.parse(validPayload())
  assert.equal(parsed.termWeekOneStart, '')
})

test('profileInputSchema: 非法校历第 1 周被挡下', () => {
  // 后两个样本是「格式对但日期不存在」:V8 会把 02-30 静默进位成 03-02、月份越界给 Invalid Date,
  // 必须在入口就挡掉,不能让 briefJson 存进一个读回时会被归一成空串的脏值
  for (const bad of ['2026-9-7', '下周一', '2026/09/07', '20260907', '2026-02-30', '2026-13-01']) {
    const parsed = profileInputSchema.safeParse({ ...validPayload(), termWeekOneStart: bad })
    assert.equal(parsed.success, false, `${bad} 本该被拒绝`)
  }
})

test('profileInputSchema: 非法 weekParity 被挡下', () => {
  // 必须报错而不是静默剥离:剥掉会让客户端以为「单周」存进去了,容量却按每周都占算
  const parsed = profileInputSchema.safeParse({
    ...validPayload(),
    fixedCommitments: [{ weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: '单周' }],
    commitmentsConfirmed: true,
  })
  assert.equal(parsed.success, false)
})

test('profileInputSchema: 格式不合法的锚点报的是格式提示,不是「日期不存在」', () => {
  // 六个坏样本里只有 2026/09/07 这类能触发 regex;只断言 success=false 的话,
  // 删掉 .regex 仍然全绿(refine 会把它们都拦下),所以必须钉住具体消息
  const parsed = profileInputSchema.safeParse({ ...validPayload(), termWeekOneStart: '2026/09/07' })
  assert.equal(parsed.success, false)
  if (!parsed.success) {
    const messages = parsed.error.issues.map(issue => issue.message)
    assert.ok(messages.includes('校历第 1 周要写成 2026-09-07 这样(年月日全都要)'), messages.join(' / '))
  }
})

test('profileInputSchema: 锚点与 parity 传 null 都被拒(客户端只能省略键或发空串)', () => {
  // App 端 kotlinx.serialization 默认 explicitNulls=true:DTO 写成 String? = null 会发出 "key":null。
  // 这条把契约钉死 —— 客户端用 @EncodeDefault(NEVER) 或不发该键,服务端不放宽成 nullish
  assert.equal(profileInputSchema.safeParse({ ...validPayload(), termWeekOneStart: null }).success, false)
  assert.equal(profileInputSchema.safeParse({
    ...validPayload(),
    fixedCommitments: [{ weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: null }],
    commitmentsConfirmed: true,
  }).success, false)
})

test('briefPatchSchema: 手工改口径时单双周标记不被剥离,非法值被挡下', () => {
  // 路由 schema 曾漏声明 weekParity —— Zod 默认剥掉未声明的键,接口看着 200,
  // 存进 briefJson 的占用却没有 parity,单双周课被当成每周都占
  const parsed = briefPatchSchema.parse({
    fixedCommitments: [
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' },
      { weekday: 5, start: '10:00', end: '11:40', label: '编译原理' },
    ],
  })
  assert.equal(parsed.fixedCommitments?.[0].weekParity, 'odd')
  assert.equal(parsed.fixedCommitments?.[1].weekParity, undefined)
  assert.equal(briefPatchSchema.safeParse({
    fixedCommitments: [{ weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: '单周' }],
  }).success, false)
  assert.equal(briefPatchSchema.safeParse({
    fixedCommitments: [{ weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: null }],
  }).success, false)
})
