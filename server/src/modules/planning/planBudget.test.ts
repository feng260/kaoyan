import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildPlanBudget, buildPlanOverview, briefHash, canonicalBrief, capacityByWeekday, PLAN_LOAD_FACTOR,
  type PlanBudget,
} from './planBudget'
import { normalizeBrief } from './document'
import type { PlanBrief } from './document'

function subject(over: Partial<PlanBrief['examSubjects'][number]> = {}): PlanBrief['examSubjects'][number] {
  return {
    name: '数学', progress: '一轮', scope: '高数', remainingMinutes: 1000,
    milestone: '强化', milestoneDate: '2026-11-20', milestoneMinutes: 400, estimated: false, ...over,
  }
}

function brief(over: Partial<PlanBrief> = {}): PlanBrief {
  return {
    summary: '', goals: [], constraints: [], focus: [], materials: [], notes: [],
    examSubjects: [subject()],
    availability: [1, 2, 3, 4, 5, 6, 7].map(weekday => ({ weekday, windows: [{ start: '08:00', end: '12:00' }] })),
    fixedCommitments: [], periods: [], availabilityConfirmed: true, commitmentsConfirmed: true,
    ...over,
  }
}

const start = new Date('2026-11-03T00:00:00.000Z')
const exam = new Date('2026-11-13T00:00:00.000Z')

test('预算按 85% 留白并保留剩余任务', () => {
  const budget = buildPlanBudget(brief(), start, exam)
  assert.equal(PLAN_LOAD_FACTOR, 0.85)
  assert.equal(budget.capacityMinutes, 2400)
  // 240×85%=204,按 5 分钟粒度下取整为 200/天
  assert.equal(budget.effectiveCapacityMinutes, 2000)
  assert.equal(budget.targetMinutes, 1000)
  assert.equal(budget.shortfallMinutes, 0)
  assert.equal(budget.subjects[0].budgetMinutes, 1000)
  assert.equal(budget.dayCap.get('2026-11-03'), 200)
})

test('估算科目按剩余容量缩放,确认科目优先', () => {
  const budget = buildPlanBudget(brief({
    examSubjects: [
      subject({ name: '数学', remainingMinutes: 3000, milestoneMinutes: 0, milestoneDate: '', milestone: '' }),
      subject({ name: '专业课', remainingMinutes: 5000, milestoneMinutes: 0, milestoneDate: '', milestone: '', estimated: true }),
    ],
  }), start, new Date('2026-11-23T00:00:00.000Z'))
  // 20 天 × 240 分钟 = 4800,按 5 分钟粒度留白后有效容量 4000:确认科目占 3000,估算科目只剩 1000
  assert.equal(budget.capacityMinutes, 4800)
  assert.equal(budget.confirmedMinutes, 3000)
  assert.equal(budget.estimatedMinutes, 5000)
  assert.equal(budget.estimatedBudgetMinutes, 1000)
  assert.equal(budget.targetMinutes, 4000)
  assert.equal(budget.subjects.find(item => item.name === '专业课')!.budgetMinutes, 1000)
  assert.ok(budget.warnings.some(warning => warning.includes('估算科目')))
})

test('确认任务超容量:默认报缺口,allowShortfall 后按容量缩放', () => {
  const over = brief({ examSubjects: [subject({ remainingMinutes: 5000, milestoneMinutes: 0, milestoneDate: '', milestone: '' })] })
  // 10 天 × 240 分钟 = 2400,按 5 分钟粒度留白后有效容量 2000,确认任务 5000 → 缺口 3000
  const strict = buildPlanBudget(over, start, exam)
  assert.equal(strict.shortfallMinutes, 3000)
  assert.equal(strict.targetMinutes, 5000)
  assert.ok(strict.warnings.some(warning => warning.includes('取舍')))

  const bestEffort = buildPlanBudget(over, start, exam, { allowShortfall: true })
  assert.equal(bestEffort.shortfallMinutes, 3000)
  assert.equal(bestEffort.targetMinutes, 2000)
})

test('briefHash 对展示字段不敏感,对排课事实敏感', () => {
  const base = briefHash(brief(), '2026-11-13')
  assert.equal(base, briefHash(brief({ summary: '换了一句画像', goals: ['新目标'], notes: ['备注'] }), '2026-11-13'))
  assert.notEqual(base, briefHash(brief({ examSubjects: [subject({ remainingMinutes: 1200 })] }), '2026-11-13'))
  assert.notEqual(base, briefHash(brief({ examSubjects: [subject({ milestoneDate: '2026-11-21' })] }), '2026-11-13'))
  assert.notEqual(base, briefHash(brief(), '2026-11-14'))
})

test('capacityByWeekday 返回 7 项平均净空闲', () => {
  const capacity = capacityByWeekday(brief(), start, exam)
  assert.equal(capacity.length, 7)
  assert.deepEqual(capacity.map(item => item.weekday), [1, 2, 3, 4, 5, 6, 7])
  assert.ok(capacity.every(item => item.minutes === 240))
})

test('buildPlanOverview:逐日负荷、逐科差额与预算警告', () => {
  const plan = { title: '测试计划', version: 1, startDate: '2026-11-03', examDate: '2026-11-13', status: 'draft' }
  const stages = [
    { name: '基础期', startDate: '2026-11-03', endDate: '2026-11-07' },
    { name: '冲刺期', startDate: '2026-11-08', endDate: '2026-11-12' },
  ]
  const items = [
    { subject: '数学', planDate: '2026-11-03', minutes: 120, status: 'pending' },
    { subject: '数学', planDate: '2026-11-04', minutes: 120, status: 'pending' },
    { subject: '英语', planDate: '2026-11-04', minutes: 60, status: 'pending' },
  ]
  const overview = buildPlanOverview(plan, brief(), stages, items)
  assert.equal(overview.days.length, 10)
  assert.equal(overview.days.find(day => day.date === '2026-11-04')!.scheduledMinutes, 180)
  const math = overview.subjects.find(item => item.name === '数学')!
  assert.equal(math.scheduledMinutes, 240)
  assert.equal(math.targetMinutes, 1000)
  assert.equal(math.deltaMinutes, -760)
  assert.ok(overview.warnings.some(warning => warning.code === 'MILESTONE_RISK'))
  assert.equal(overview.stages.length, 2)
  assert.ok(overview.stages.every(stage => stage.targetMinutes >= 0))
})

test('buildPlanOverview:超预算与总排量超限产生警告', () => {
  const plan = { title: '测试计划', version: 1, startDate: '2026-11-03', examDate: '2026-11-13', status: 'draft' }
  const items = [
    { subject: '数学', planDate: '2026-11-03', minutes: 1000, status: 'pending' },
    { subject: '数学', planDate: '2026-11-04', minutes: 1000, status: 'pending' },
  ]
  const budget: PlanBudget = buildPlanBudget(brief({ examSubjects: [subject({ remainingMinutes: 1000, milestoneMinutes: 0, milestoneDate: '', milestone: '' })] }), start, exam)
  const overview = buildPlanOverview(plan, brief({ examSubjects: [subject({ remainingMinutes: 1000, milestoneMinutes: 0, milestoneDate: '', milestone: '' })] }), [], items, budget)
  assert.ok(overview.warnings.some(warning => warning.code === 'SUBJECT_OVER_BUDGET'))
  assert.ok(overview.warnings.some(warning => warning.code === 'TOTAL_OVER_BUDGET'))
  assert.ok(overview.warnings.some(warning => warning.code === 'SHORTFALL') === false)
})

test('canonicalBrief: 旧简报不含任何单双周痕迹(哈希与改动前一致)', () => {
  // 调整单指纹读的是 briefHash → canonicalBrief。老账号的简报里没有 weekParity,
  // 也没有 termWeekOneStart;这两个键一旦出现,线上所有已有计划的调整单会集体失效。
  const brief = normalizeBrief({
    availability: [{ weekday: 1, windows: [{ start: '08:00', end: '12:00' }] }],
    fixedCommitments: [{ weekday: 3, start: '08:00', end: '09:40', label: '人工智能' }],
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  })
  const canonical = canonicalBrief(brief, '2026-12-19')
  assert.ok(!canonical.includes('termWeekOneStart'), canonical)
  assert.ok(!canonical.includes('weekParity'), canonical)
})

test('canonicalBrief: 填了校历第 1 周才把锚点写进去', () => {
  const brief = normalizeBrief({
    availability: [{ weekday: 1, windows: [{ start: '08:00', end: '12:00' }] }],
    termWeekOneStart: '2026-09-07',
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  })
  const canonical = canonicalBrief(brief, '2026-12-19')
  assert.ok(canonical.includes('"termWeekOneStart":"2026-09-07"'), canonical)
})

test('canonicalBrief: 同一时段不同单双周的两条占用排序稳定', () => {
  const brief = normalizeBrief({
    availability: [{ weekday: 1, windows: [{ start: '08:00', end: '12:00' }] }],
    fixedCommitments: [
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'even' },
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' },
    ],
    termWeekOneStart: '2026-09-07',
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  })
  const canonical = canonicalBrief(brief, '2026-12-19')
  // 排序键末尾是 parity:'even' 必须排在 'odd' 前,两次序列化结果一致
  assert.ok(canonical.indexOf('"weekParity":"even"') < canonical.indexOf('"weekParity":"odd"'), canonical)
  assert.equal(canonical, canonicalBrief(JSON.parse(JSON.stringify(brief)), '2026-12-19'))
})

test('canonicalBrief: 无 parity 的占用排在有 parity 的同键占用之前', () => {
  // 旧行为里所有占用都没有 parity,追加排序键不能改变它们之间的相对顺序
  const brief = normalizeBrief({
    availability: [{ weekday: 1, windows: [{ start: '08:00', end: '12:00' }] }],
    fixedCommitments: [
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能', weekParity: 'odd' },
      { weekday: 3, start: '08:00', end: '09:40', label: '人工智能' },
    ],
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  })
  const canonical = canonicalBrief(brief, '2026-12-19')
  const list = JSON.parse(canonical).fixedCommitments as Array<{ weekParity?: string }>
  assert.equal(list[0].weekParity, undefined)
  assert.equal(list[1].weekParity, 'odd')
})
