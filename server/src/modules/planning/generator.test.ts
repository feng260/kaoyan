import test from 'node:test'
import assert from 'node:assert/strict'
import { generateRulePlan, PlanGenerationError, type PlanningInput } from './generator'

const DAY = 24 * 3600_000

function dayStart(offsetDays: number): Date {
  const d = new Date()
  d.setUTCHours(0, 0, 0, 0)
  d.setUTCDate(d.getUTCDate() + offsetDays)
  return d
}

function input(over: Partial<PlanningInput> = {}): PlanningInput {
  return {
    startDate: dayStart(0),
    examDate: dayStart(30),
    dailyMinutes: 180,
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

test('rejects daily minutes below the supported minimum', () => {
  assert.throws(
    () => generateRulePlan(input({ dailyMinutes: 10 })),
    (e: unknown) => e instanceof PlanGenerationError && e.code === 'INVALID_DAILY_MINUTES',
  )
})

test('produces three contiguous stages on the 14-day minimum range', () => {
  const startDate = dayStart(0)
  const examDate = dayStart(14)
  const plan = generateRulePlan(input({ startDate, examDate, dailyMinutes: 90 }))

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
  const plan = generateRulePlan(input({ examDate: dayStart(60), dailyMinutes: 180 }))

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
    assert.equal(v.minutes, 180, `${day} 分配的时长不等于每日可用时长`)
  }
})

test('adds a review item only when at least two hours are available', () => {
  const plenty = generateRulePlan(input({ dailyMinutes: 240 }))
  const tight = generateRulePlan(input({ dailyMinutes: 60 }))

  assert.ok(plenty.items.some(i => i.subject === '复盘'), '每日 240 分钟应包含复盘项')
  assert.ok(!tight.items.some(i => i.subject === '复盘'), '每日 60 分钟不应包含复盘项')
})

test('covers every weak subject across the plan', () => {
  const weakSubjects = ['数学', '英语', '专业课']
  const plan = generateRulePlan(input({ weakSubjects }))
  const studySubjects = new Set(plan.items.filter(i => i.subject !== '复盘').map(i => i.subject))
  assert.deepEqual([...studySubjects].sort(), [...weakSubjects].sort())
})

test('handles a single weak subject without exceeding the daily item cap', () => {
  const plan = generateRulePlan(input({ dailyMinutes: 240, weakSubjects: ['数学'] }))
  assert.ok(plan.items.every(i => i.subject === '数学' || i.subject === '复盘'))
  const perDay = new Set(plan.items.map(i => i.planDate.toISOString()))
  for (const day of perDay) {
    const count = plan.items.filter(i => i.planDate.toISOString() === day).length
    assert.ok(count <= 3, `${day} 安排了 ${count} 项`)
  }
})

test('keeps every item inside its stage and before the exam date', () => {
  const examDate = dayStart(120)
  const plan = generateRulePlan(input({ examDate, dailyMinutes: 300 }))
  assertItemsInsideStages(plan, examDate)
})

test('produces identical output for repeated calls with the same input', () => {
  const first = generateRulePlan(input({ examDate: dayStart(90) }))
  const second = generateRulePlan(input({ examDate: dayStart(90) }))
  assert.deepEqual(second, first)
})
