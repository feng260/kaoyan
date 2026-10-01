import test from 'node:test'
import assert from 'node:assert/strict'
import { generateRulePlan, PlanGenerationError, type PlanningInput } from './generator'
import { AiUnavailable, expandStage, scheduleBacklog } from './aiGenerator'

const DAY = 24 * 3600_000

function dayStart(offsetDays: number): Date {
  const d = new Date()
  d.setUTCHours(0, 0, 0, 0)
  d.setUTCDate(d.getUTCDate() + offsetDays)
  return d
}

/** 默认课表:每天 09:00–12:00 共 180 分钟空闲,即规则版每天可排 180 分钟 */
const DEFAULT_WINDOWS = [{ start: '09:00', end: '12:00' }]

function weekAvailability(windows = DEFAULT_WINDOWS) {
  return [1, 2, 3, 4, 5, 6, 7].map(weekday => ({ weekday, windows }))
}

/** 构造只给指定星期空闲窗口的简报,窗口长度即当天净空闲 */
function briefWith(weekdays: number[], start: string, end: string) {
  return { availability: weekdays.map(weekday => ({ weekday, windows: [{ start, end }] })), fixedCommitments: [] }
}

const FULL_BRIEF = briefWith([1, 2, 3, 4, 5, 6, 7], '09:00', '12:00')

function input(over: Partial<PlanningInput> = {}): PlanningInput {
  return {
    startDate: dayStart(0),
    examDate: dayStart(30),
    availability: weekAvailability(),
    fixedCommitments: [],
    weakSubjects: ['数学', '英语'],
    studyWindows: ['上午', '晚上'],
    ...over,
  }
}

/** 断言所有计划项的日期都落在所属阶段区间内,且早于考试日期 */
function assertItemsInsideStages(plan: ReturnType<typeof generateRulePlan>, examDate: Date) {
  for (const item of plan.items) {
    const stage = plan.stages[item.stageOrder]
    assert.ok(stage, `计划项引用了不存在的阶段 ${item.stageOrder}`)
    assert.ok(item.planDate >= stage.startDate, `${item.title} 早于阶段开始`)
    assert.ok(item.planDate <= stage.endDate, `${item.title} 晚于阶段结束`)
    assert.ok(item.planDate < examDate, `${item.title} 不应安排在考试当天或之后`)
  }
}

test('rejects an exam date in the past', () => {
  assert.throws(
    () => generateRulePlan(input({ startDate: dayStart(-20), examDate: dayStart(-5) })),
    (e: unknown) => e instanceof PlanGenerationError && e.code === 'INVALID_EXAM_DATE',
  )
})

test('rejects an exam date that is not after the start date', () => {
  assert.throws(
    () => generateRulePlan(input({ startDate: dayStart(10), examDate: dayStart(10) })),
    (e: unknown) => e instanceof PlanGenerationError && e.code === 'INVALID_EXAM_DATE',
  )
})

test('rejects a range shorter than 14 days', () => {
  assert.throws(
    () => generateRulePlan(input({ startDate: dayStart(0), examDate: dayStart(13) })),
    (e: unknown) => e instanceof PlanGenerationError && e.code === 'RANGE_TOO_SHORT',
  )
})

test('rejects an empty weak subject list', () => {
  assert.throws(
    () => generateRulePlan(input({ weakSubjects: [] })),
    (e: unknown) => e instanceof PlanGenerationError && e.code === 'INVALID_WEAK_SUBJECTS',
  )
})

test('produces three contiguous stages on the 14-day minimum range', () => {
  const startDate = dayStart(0)
  const examDate = dayStart(14)
  const plan = generateRulePlan(input({ startDate, examDate }))

  assert.equal(plan.stages.length, 3)
  assert.deepEqual(plan.stages.map(s => s.sortOrder), [0, 1, 2])
  assert.deepEqual(plan.stages.map(s => s.name), ['基础阶段', '强化阶段', '冲刺阶段'])
  assert.equal(plan.stages[0].startDate.getTime(), startDate.getTime())

  const totalDays = (examDate.getTime() - startDate.getTime()) / DAY
  let covered = 0
  for (const [i, stage] of plan.stages.entries()) {
    const days = (stage.endDate.getTime() - stage.startDate.getTime()) / DAY + 1
    assert.ok(days >= 1, `${stage.name} 至少应有一天`)
    covered += days
    if (i > 0) {
      assert.equal(
        stage.startDate.getTime() - plan.stages[i - 1].endDate.getTime(),
        DAY,
        `${stage.name} 与上一阶段之间不应有空隙或重叠`,
      )
    }
  }
  assert.equal(covered, totalDays)
  assert.equal(plan.stages[2].endDate.getTime(), examDate.getTime() - DAY)
})

test('splits a long range roughly into 45/35/20 percent', () => {
  const startDate = dayStart(0)
  const examDate = dayStart(300)
  const plan = generateRulePlan(input({ startDate, examDate }))
  const total = (examDate.getTime() - startDate.getTime()) / DAY

  const share = (i: number) => {
    const s = plan.stages[i]
    return ((s.endDate.getTime() - s.startDate.getTime()) / DAY + 1) / total
  }
  assert.ok(Math.abs(share(0) - 0.45) <= 0.02, `基础阶段占比 ${share(0)}`)
  assert.ok(Math.abs(share(1) - 0.35) <= 0.02, `强化阶段占比 ${share(1)}`)
  assert.ok(Math.abs(share(2) - 0.20) <= 0.02, `冲刺阶段占比 ${share(2)}`)
})

test('never schedules more than three items per day and always fills the daily budget', () => {
  const plan = generateRulePlan(input({ examDate: dayStart(60) }))

  const perDay = new Map<string, { count: number; minutes: number }>()
  for (const item of plan.items) {
    const key = item.planDate.toISOString().slice(0, 10)
    const cur = perDay.get(key) ?? { count: 0, minutes: 0 }
    cur.count += 1
    cur.minutes += item.minutes
    perDay.set(key, cur)
  }
  assert.ok(perDay.size >= 14, '应覆盖全部备考日')
  for (const [day, v] of perDay) {
    assert.ok(v.count <= 3, `${day} 安排了 ${v.count} 项,超过每日上限`)
    assert.equal(v.minutes, 180, `${day} 分配的时长不等于当天课表净空闲`)
  }
})

test('adds a review item only when at least two hours are available', () => {
  const plenty = generateRulePlan(input({ availability: weekAvailability([{ start: '09:00', end: '13:00' }]) }))
  const tight = generateRulePlan(input({ availability: weekAvailability([{ start: '09:00', end: '10:00' }]) }))

  assert.ok(plenty.items.some(i => i.subject === '复盘'), '每天净空闲 240 分钟应包含复盘项')
  assert.ok(!tight.items.some(i => i.subject === '复盘'), '每天净空闲 60 分钟不应包含复盘项')
})

test('covers every weak subject across the plan', () => {
  const weakSubjects = ['数学', '英语', '专业课']
  const plan = generateRulePlan(input({ weakSubjects }))
  const studySubjects = new Set(plan.items.filter(i => i.subject !== '复盘').map(i => i.subject))
  assert.deepEqual([...studySubjects].sort(), [...weakSubjects].sort())
})

test('handles a single weak subject without exceeding the daily item cap', () => {
  const plan = generateRulePlan(input({ availability: weekAvailability([{ start: '09:00', end: '13:00' }]), weakSubjects: ['数学'] }))
  assert.ok(plan.items.every(i => i.subject === '数学' || i.subject === '复盘'))
  const perDay = new Set(plan.items.map(i => i.planDate.toISOString()))
  for (const day of perDay) {
    const count = plan.items.filter(i => i.planDate.toISOString() === day).length
    assert.ok(count <= 3, `${day} 安排了 ${count} 项`)
  }
})

test('keeps every item inside its stage and before the exam date', () => {
  const examDate = dayStart(120)
  const plan = generateRulePlan(input({ examDate, availability: weekAvailability([{ start: '09:00', end: '14:00' }]) }))
  assertItemsInsideStages(plan, examDate)
})

test('produces identical output for repeated calls with the same input', () => {
  const first = generateRulePlan(input({ examDate: dayStart(90) }))
  const second = generateRulePlan(input({ examDate: dayStart(90) }))
  assert.deepEqual(second, first)
})

test('AI stage fills every available day up to its net availability', () => {
  const monday = new Date('2026-09-28T00:00:00.000Z')
  const brief = {
    availability: [1, 2, 3].map(weekday => ({ weekday, windows: [{ start: '19:00', end: '21:00' }] })),
    fixedCommitments: [{ weekday: 1, start: '19:00', end: '21:00', label: '值班' }, { weekday: 2, start: '20:00', end: '21:00', label: '会议' }],
  }
  const items = expandStage({ name: '基础', weeklySlots: [
    { weekdays: [1, 2, 3], subject: '英语一', title: '2015 阅读', minutes: 90 },
    { weekdays: [1, 2, 3], subject: '英语一', title: '2016 阅读', minutes: 90 },
  ] }, { name: '基础', startDate: monday, endDate: new Date('2026-09-30T00:00:00.000Z'), sortOrder: 0 }, brief)
  // 周一值班净空闲 0 → 无任务;周二会议后净 60;周三净 120 排满,
  // 队列耗尽后循环重放补足剩余预算,不再空天
  assert.deepEqual(items.map(item => [item.planDate.getUTCDay(), item.title, item.minutes]),
    [[2, '2015 阅读', 60], [3, '2016 阅读', 90], [3, '2015 阅读', 30]])
})

test('AI stage cycles the queue so later days stay covered after it runs dry', () => {
  const monday = new Date('2026-09-28T00:00:00.000Z')
  const items = expandStage({ name: '基础', weeklySlots: [
    { weekdays: [1], subject: '英语一', title: '先读 2015', minutes: 60 },
    { weekdays: [2], subject: '英语一', title: '再读 2016', minutes: 60 },
  ] }, { name: '基础', startDate: monday, endDate: new Date('2026-10-06T00:00:00.000Z'), sortOrder: 0 }, FULL_BRIEF)
  // 队列两条各 60 分钟,每天净空闲 180:耗尽后循环重放,9 天每天 3 条,不再 7 天全空
  assert.equal(items.length, 27)
  const byDay = new Map<string, number>()
  items.forEach(item => {
    const key = item.planDate.toISOString().slice(0, 10)
    byDay.set(key, (byDay.get(key) ?? 0) + item.minutes)
  })
  assert.equal(byDay.size, 9)
  assert.ok([...byDay.values()].every(total => total === 180))
})

test('AI stage consumes the task queue in order within a day, then cycles on replay', () => {
  const monday = new Date('2026-09-28T00:00:00.000Z')
  const items = expandStage({ name: '基础', weeklySlots: [
    { weekdays: [1], subject: '英语一', title: '2015 阅读', minutes: 60 },
    { weekdays: [1], subject: '英语一', title: '2016 阅读', minutes: 60 },
  ] }, { name: '基础', startDate: monday, endDate: new Date('2026-10-12T00:00:00.000Z'), sortOrder: 0 }, FULL_BRIEF)
  // 第一周周一按队列顺序消费 2015 → 2016,随后进入复习轮
  const mondayItems = items.filter(item => item.planDate.getUTCDay() === 1)
  assert.deepEqual(mondayItems.slice(0, 2).map(item => item.title), ['2015 阅读', '2016 阅读'])
  // 15 天每天 3 条 60 分钟,无空天
  assert.equal(items.length, 45)
  const byDay = new Map<string, number>()
  items.forEach(item => {
    const key = item.planDate.toISOString().slice(0, 10)
    byDay.set(key, (byDay.get(key) ?? 0) + item.minutes)
  })
  assert.equal(byDay.size, 15)
  assert.ok([...byDay.values()].every(total => total === 180))
})

test('AI stage alternates odd and even week queues and keeps the other days covered by replay', () => {
  const monday = new Date('2026-09-28T00:00:00.000Z')
  const items = expandStage({ name: '基础', weeklySlots: [
    { weekdays: [1], subject: '英语一', title: '偶周阅读', minutes: 60, weekParity: 'even' },
    { weekdays: [1], subject: '英语一', title: '奇周阅读', minutes: 60, weekParity: 'odd' },
    { weekdays: [1], subject: '英语一', title: '下一奇周阅读', minutes: 60, weekParity: 'odd' },
  ] }, { name: '基础', startDate: monday, endDate: new Date('2026-10-26T00:00:00.000Z'), sortOrder: 0 }, FULL_BRIEF)
  // 奇偶周节奏保留:奇周不出现偶周条目,偶周排上偶周条目
  const titlesOf = (iso: string) => items
    .filter(item => item.planDate.toISOString().slice(0, 10) === iso).map(item => item.title)
  assert.ok(titlesOf('2026-09-28').includes('奇周阅读') && !titlesOf('2026-09-28').includes('偶周阅读'))
  assert.ok(titlesOf('2026-10-05').includes('偶周阅读'))
  // 29 天全部有安排(队列耗尽后循环重放),不再 26 天空天
  const byDay = new Map<string, number>()
  items.forEach(item => {
    const key = item.planDate.toISOString().slice(0, 10)
    byDay.set(key, (byDay.get(key) ?? 0) + item.minutes)
  })
  assert.equal(byDay.size, 29)
})

test('pending backlog is not duplicated when AI repeats the same subject and title', () => {
  const monday = new Date('2026-09-28T00:00:00.000Z')
  const stages = [{ name: '基础', startDate: monday, endDate: monday, sortOrder: 0 }]
  const generated = [{ stageOrder: 0, subject: '英语一', title: '旧阅读', planDate: monday, minutes: 60, priority: 0, sortOrder: 0 }]
  const items = scheduleBacklog(stages, generated, [{ subject: '英语一', title: '旧阅读', minutes: 60 }], FULL_BRIEF,
    ['英语一'])
  assert.deepEqual(items.map(item => [item.title, item.minutes]), [['旧阅读', 60]])
})

test('pending backlog for a confirmed subject is not dropped when the model omits that subject', () => {
  const monday = new Date('2026-09-28T00:00:00.000Z')
  const stages = [{ name: '基础', startDate: monday, endDate: monday, sortOrder: 0 }]
  const items = scheduleBacklog(stages, [], [{ subject: '自命题 912', title: '旧大纲', minutes: 60 }], FULL_BRIEF,
    ['自命题 912'])
  assert.deepEqual(items.map(item => [item.subject, item.title, item.minutes]), [['自命题 912', '旧大纲', 60]])
})

test('pending backlog and new work cannot silently exceed available capacity', () => {
  const monday = new Date('2026-09-28T00:00:00.000Z')
  const stages = [{ name: '基础', startDate: monday, endDate: monday, sortOrder: 0 }]
  const generated = [{ stageOrder: 0, subject: '英语一', title: '新阅读', planDate: monday, minutes: 60, priority: 0, sortOrder: 0 }]
  assert.throws(() => scheduleBacklog(stages, generated,
    [{ subject: '英语一', title: '旧阅读', minutes: 60 }], briefWith([1], '09:00', '10:00'), ['英语一']), AiUnavailable)
})

test('pending backlog does not pull an even-week task into an odd week', () => {
  const monday = new Date('2026-09-28T00:00:00.000Z')
  const nextMonday = new Date('2026-10-05T00:00:00.000Z')
  const stages = [{ name: '基础', startDate: monday, endDate: nextMonday, sortOrder: 0 }]
  const generated = [{ stageOrder: 0, subject: '英语一', title: '偶周阅读', planDate: nextMonday, minutes: 60, priority: 0, sortOrder: 0 }]
  const items = scheduleBacklog(stages, generated, [{ subject: '英语一', title: '旧阅读', minutes: 60 }], FULL_BRIEF, ['英语一'])
  assert.deepEqual(items.map(item => [item.planDate.toISOString().slice(0, 10), item.title]), [
    ['2026-09-28', '旧阅读'], ['2026-10-05', '偶周阅读'],
  ])
})

test('pending backlog is scheduled before new work and carries across a stage boundary', () => {
  const monday = new Date('2026-09-28T00:00:00.000Z')
  const stages = [
    { name: '基础', startDate: monday, endDate: monday, sortOrder: 0 },
    { name: '强化', startDate: new Date('2026-09-29T00:00:00.000Z'), endDate: new Date('2026-09-30T00:00:00.000Z'), sortOrder: 1 },
  ]
  const backlog = [{ subject: '英语一', title: '旧阅读', minutes: 90 }]
  const newItems = [{ stageOrder: 0, subject: '英语一', title: '新阅读', planDate: monday, minutes: 60, priority: 0, sortOrder: 0 }]
  const items = scheduleBacklog(stages, newItems, backlog, briefWith([1, 2, 3], '09:00', '10:00'))
  assert.deepEqual(items.map(item => [item.stageOrder, item.title, item.minutes]), [
    [0, '旧阅读', 60], [1, '旧阅读', 30], [1, '新阅读', 30], [1, '新阅读', 30],
  ])
})
