import test from 'node:test'
import assert from 'node:assert/strict'
import { addDays, diffDays } from './schemas'
import { EMPTY_BRIEF, netAvailableMinutes, type PlanBrief } from './document'
import { stageArchetypes, topicsForSubject, actionsForSubject, type PlaybookPhase } from './playbooks'
import type { AiWeekVariant, GeneratedStage } from './generator'
import {
  buildPlanSpec,
  compilePlanSpec,
  repairPlanSpec,
  verifyPlanSpec,
  type PlanSpec,
} from './planSpec'
import { buildPlanBudget } from './planBudget'

const SUBJECTS = ['数学二', '英语二', '政治', '数据结构']

function iso(d: Date): string {
  return d.toISOString().slice(0, 10)
}

function sampleVariant(phase: PlaybookPhase): AiWeekVariant {
  const tasks: AiWeekVariant = []
  for (let weekday = 1; weekday <= 7; weekday++) {
    const subject = SUBJECTS[(weekday - 1) % SUBJECTS.length]
    const topics = topicsForSubject(subject, phase)
    const actions = actionsForSubject(subject, phase)
    const topic = topics[(weekday * 3) % Math.max(1, topics.length)] ?? '本周主线'
    const template = actions[weekday % Math.max(1, actions.length)] ?? '{topic}'
    tasks.push({
      weekday,
      subject,
      title: template.replace('{topic}', topic),
      minutes: weekday >= 6 ? 90 : 60,
    })
  }
  return tasks
}

function sampleBrief(start: Date): PlanBrief {
  const subjects = [
    { name: '数学二', progress: '一轮过完', scope: '高数与线代', progressMinutes: 12000 },
    { name: '英语二', progress: '单词二刷', scope: '阅读+作文', progressMinutes: 8000 },
    { name: '政治', progress: '未开始', scope: '马原+史纲', progressMinutes: 5000 },
    { name: '数据结构', progress: '过完一半', scope: '王道全书', progressMinutes: 9000 },
  ]
  return {
    ...EMPTY_BRIEF,
    summary: '在校生,白天有课,晚上与周末整块复习',
    examSubjects: subjects.map(item => ({
      name: item.name,
      progress: item.progress,
      scope: item.scope,
      remainingMinutes: item.progressMinutes,
      milestone: '完成一轮强化',
      milestoneDate: iso(addDays(start, 80)),
      milestoneMinutes: item.progressMinutes,
      estimated: false,
    })),
    availability: [1, 2, 3, 4, 5, 6, 7].map(weekday => ({
      weekday,
      windows: weekday >= 6
        ? [{ start: '08:00', end: '12:00' }, { start: '14:00', end: '17:00' }]
        : [{ start: '19:00', end: '22:00' }],
    })),
    fixedCommitments: [],
    availabilityConfirmed: true,
    commitmentsConfirmed: true,
  }
}

function stagesFor(start: Date, exam: Date, variantFor: (phase: PlaybookPhase, index: number) => AiWeekVariant[]): GeneratedStage[] {
  const totalDays = diffDays(exam, start)
  const archetypes = stageArchetypes('考研', totalDays)
  const stages: GeneratedStage[] = []
  let cursor = start
  for (let index = 0; index < archetypes.length; index++) {
    const remain = diffDays(exam, cursor)
    const days = index === archetypes.length - 1
      ? remain
      : Math.max(1, Math.round(totalDays * archetypes[index].ratio))
    const end = addDays(cursor, Math.max(1, days) - 1)
    const phase: PlaybookPhase = index === 0 ? 'foundation' : index === archetypes.length - 1 ? 'sprint' : 'strengthen'
    stages.push({
      name: archetypes[index].name,
      startDate: cursor,
      endDate: end,
      sortOrder: index,
      strategy: archetypes[index].strategy,
      milestones: archetypes[index].milestones,
      weekVariants: variantFor(phase, index),
    })
    cursor = addDays(end, 1)
  }
  return stages
}

function baseSpec(over: { days?: number; variantFor?: (phase: PlaybookPhase, index: number) => AiWeekVariant[] } = {}): PlanSpec {
  const start = new Date(Date.UTC(2026, 0, 1))
  const exam = addDays(start, over.days ?? 120)
  const variantFor = over.variantFor ?? ((phase: PlaybookPhase) => [sampleVariant(phase)])
  return buildPlanSpec({
    title: '2027 考研全程计划',
    targetType: '考研',
    startDate: start,
    examDate: exam,
    brief: sampleBrief(start),
    weakSubjects: ['数学二'],
    stages: stagesFor(start, exam, variantFor),
  })
}

test('planSpec: 同一份 spec 编译结果完全一致,且每条任务都有时段', () => {
  const spec = baseSpec()
  const first = compilePlanSpec(spec)
  const second = compilePlanSpec(spec)
  assert.deepEqual(first.items, second.items)
  assert.ok(first.items.length > 100, `items=${first.items.length}`)

  for (const item of first.items) {
    assert.match(item.startTime ?? '', /^\d{2}:\d{2}$/, `缺少开始时刻:${item.title}`)
    assert.ok(['morning', 'afternoon', 'evening'].includes(item.slot ?? ''), `时段非法:${item.slot}`)
  }
})

test('planSpec: 每天排入的分钟数不超过当天真实空闲', () => {
  const spec = baseSpec()
  const plan = compilePlanSpec(spec)
  const byDay = new Map<string, number>()
  for (const item of plan.items) {
    const day = iso(item.planDate)
    byDay.set(day, (byDay.get(day) ?? 0) + item.minutes)
  }
  for (const [day, minutes] of byDay) {
    const capacity = netAvailableMinutes(spec.brief, new Date(`${day}T00:00:00.000Z`))
    assert.ok(minutes <= capacity + 1, `${day} 排了 ${minutes},容量只有 ${capacity}`)
  }
})

/** 每天上午/下午/晚上各一个窗口:用来验证编译器有没有把任务摊到全天 */
function multiWindowSpec(tasks: AiWeekVariant): PlanSpec {
  const start = new Date(Date.UTC(2026, 0, 5)) // 周一
  const exam = addDays(start, 21)
  const brief = sampleBrief(start)
  brief.availability = [1, 2, 3, 4, 5, 6, 7].map(weekday => ({
    weekday,
    windows: [
      { start: '08:30', end: '11:30' },
      { start: '14:00', end: '17:00' },
      { start: '19:00', end: '21:30' },
    ],
  }))
  return buildPlanSpec({
    title: '多窗口测试计划',
    targetType: '考研',
    startDate: start,
    examDate: exam,
    brief,
    weakSubjects: [],
    stages: stagesFor(start, exam, () => [tasks]),
  })
}

function firstDayItems(spec: PlanSpec) {
  const day = spec.stages[0].startDate
  return compilePlanSpec(spec).items.filter(item => iso(item.planDate) === day)
}

test('planSpec: 多空闲窗口时任务分摊到上午/下午/晚上,不再全部堆在第一个窗口', () => {
  const items = firstDayItems(multiWindowSpec([
    { weekday: 1, subject: '数学二', title: '高数极限专项', minutes: 60 },
    { weekday: 1, subject: '英语二', title: '阅读精读一篇', minutes: 45 },
    { weekday: 1, subject: '政治', title: '马原选择题 30 题', minutes: 45 },
  ]))

  assert.equal(items.length, 3)
  assert.deepEqual(items.map(item => item.slot), ['morning', 'afternoon', 'evening'])
  assert.deepEqual(items.map(item => item.startTime), ['08:30', '14:00', '19:00'])
})

test('planSpec: 任务数多于窗口数时按容量比例摊销,每个窗口都有安排', () => {
  const items = firstDayItems(multiWindowSpec(
    ['高数极限专项', '线代矩阵专项', '阅读精读一篇', '翻译长难句', '马原选择题 30 题', '史纲时间线梳理']
      .map((title, index) => ({
        weekday: 1,
        subject: SUBJECTS[index % SUBJECTS.length],
        title,
        minutes: 45,
      }))
  ))

  assert.equal(items.length, 6)
  const perSlot = new Map<string, number>()
  for (const item of items) perSlot.set(item.slot!, (perSlot.get(item.slot!) ?? 0) + 1)
  assert.deepEqual([...perSlot.entries()].sort(), [['afternoon', 2], ['evening', 2], ['morning', 2]])
  for (const item of items) {
    const [hour, minute] = item.startTime!.split(':').map(Number)
    assert.ok(hour * 60 + minute + item.minutes <= 21 * 60 + 30, `${item.startTime} + ${item.minutes} 超出窗口`)
  }
})

test('planSpec: 单周变体铺满长阶段时不原样重放,第二周起换轮次与知识点', () => {
  const spec = baseSpec({ days: 150 })
  const stage = spec.stages[0]
  assert.ok(stage.weeks.length >= 4, `weeks=${stage.weeks.length}`)
  const first = stage.weeks[0].tasks.map(task => task.title)
  const second = stage.weeks[1].tasks.map(task => task.title)
  assert.notDeepEqual(first, second)
  assert.ok(second.some(title => title.startsWith('【二刷】')), second.join('|'))
  const verification = verifyPlanSpec(spec, compilePlanSpec(spec))
  assert.equal(verification.issues.filter(issue => issue.code === 'WEEK_PATTERN').length, 0)
})

test('planSpec: 校验器抓出未确认科目/重复任务/里程碑缺口', () => {
  const spec = JSON.parse(JSON.stringify(baseSpec())) as PlanSpec
  const week = spec.stages[0].weeks[0]
  week.tasks[0] = { ...week.tasks[0], subject: '物理' }
  week.tasks.push({ ...week.tasks[1] })
  const milestoneSubject = spec.subjects.find(subject => subject.name === '政治')!
  milestoneSubject.milestoneMinutes = 9_999_999

  const plan = compilePlanSpec(spec)
  const verification = verifyPlanSpec(spec, plan)
  const codes = new Set(verification.issues.map(issue => issue.code))
  assert.ok(codes.has('UNKNOWN_SUBJECT'), [...codes].join(','))
  assert.ok(codes.has('DUPLICATE_TITLE'), [...codes].join(','))
  assert.ok(codes.has('MILESTONE_GAP'), [...codes].join(','))
  assert.equal(verification.ok, false)
})

test('planSpec: 校验器抓出超长任务与单日溢出(手工构造的脏计划)', () => {
  const spec = baseSpec()
  const plan = compilePlanSpec(spec)
  const item = plan.items[0]
  const day = iso(item.planDate)
  const capacity = netAvailableMinutes(spec.brief, new Date(`${day}T00:00:00.000Z`))
  plan.items.push({ ...item, title: `${item.title}·超长`, minutes: capacity + 120 })
  const verification = verifyPlanSpec(spec, plan)
  const codes = new Set(verification.issues.map(issue => issue.code))
  assert.ok(codes.has('TASK_TOO_LONG'), [...codes].join(','))
  assert.ok(codes.has('DAILY_OVERFLOW'), [...codes].join(','))
})

test('planSpec: 缺科目能被校验发现,定向修复后该阶段补齐', () => {
  const spec = JSON.parse(JSON.stringify(baseSpec())) as PlanSpec
  const stage = spec.stages[0]
  for (const week of stage.weeks) {
    week.tasks = week.tasks.filter(task => task.subject !== '政治')
  }
  const before = verifyPlanSpec(spec, compilePlanSpec(spec))
  assert.ok(before.issues.some(issue => issue.code === 'MISSING_SUBJECT'
    && issue.stageIndex === 0 && issue.subject === '政治'), before.issues.map(issue => issue.code).join(','))

  const repaired = repairPlanSpec(spec, before)
  assert.ok(repaired.actions.length > 0)
  const after = verifyPlanSpec(repaired.spec, compilePlanSpec(repaired.spec))
  assert.equal(after.issues.filter(issue => issue.code === 'MISSING_SUBJECT'
    && issue.stageIndex === 0 && issue.subject === '政治').length, 0)
})

test('planSpec: 假期区间覆盖平日作息,计划只排在假期整天里的空档', () => {
  const start = new Date(Date.UTC(2026, 6, 1))
  const exam = addDays(start, 30)
  const brief = sampleBrief(start)
  brief.availability = [1, 2, 3, 4, 5, 6, 7].map(weekday => ({
    weekday,
    windows: [{ start: '19:00', end: '20:00' }],
  }))
  brief.periods = [{
    start: iso(start),
    end: iso(addDays(exam, -1)),
    kind: 'summer',
    note: '暑假全天在家',
    availability: [1, 2, 3, 4, 5, 6, 7].map(weekday => ({
      weekday,
      windows: [{ start: '08:00', end: '18:00' }],
    })),
    fixedCommitments: [],
  }]
  const spec = buildPlanSpec({
    title: '暑假冲刺',
    targetType: '考研',
    startDate: start,
    examDate: exam,
    brief,
    weakSubjects: [],
    stages: stagesFor(start, exam, phase => [sampleVariant(phase)]),
  })
  assert.equal(netAvailableMinutes(brief, addDays(start, 3)), 600)
  const plan = compilePlanSpec(spec)
  assert.ok(plan.items.length > 0)
  for (const item of plan.items) {
    assert.ok(item.startTime! >= '08:00' && item.startTime! < '18:00', `${item.startTime} ${item.title}`)
  }
})

function budgetBrief(start: Date, remaining: number[]): PlanBrief {
  const brief = sampleBrief(start)
  brief.examSubjects = brief.examSubjects.map((subject, index) => ({
    ...subject,
    remainingMinutes: remaining[index] ?? 0,
    milestoneMinutes: 0,
    milestoneDate: '',
    milestone: '',
  }))
  return brief
}

test('预算版:编译总量落在 105% 上限内,逐科不超上限', () => {
  const start = new Date(Date.UTC(2026, 0, 1))
  const exam = addDays(start, 30)
  const brief = budgetBrief(start, [400, 300, 200, 300])
  const budget = buildPlanBudget(brief, start, exam)
  const spec = buildPlanSpec({
    title: '预算版计划',
    targetType: '考研',
    startDate: start,
    examDate: exam,
    brief,
    weakSubjects: ['数学二'],
    stages: stagesFor(start, exam, phase => [sampleVariant(phase)]),
    budget,
  })
  assert.ok(spec.budget)
  assert.equal(Object.keys(spec.dayCaps).length, 30)
  const plan = compilePlanSpec(spec)
  const total = plan.items.reduce((sum, item) => sum + item.minutes, 0)
  assert.ok(total <= Math.floor(budget.targetMinutes * 1.05), `总排量 ${total} 超过上限`)
  for (const subject of budget.subjects) {
    const scheduled = plan.items.filter(item => item.subject === subject.name).reduce((sum, item) => sum + item.minutes, 0)
    assert.ok(scheduled <= subject.upperMinutes, `${subject.name} ${scheduled} > ${subject.upperMinutes}`)
  }
  const codes = verifyPlanSpec(spec, plan).issues.map(issue => issue.code)
  assert.equal(codes.includes('SUBJECT_OVER_BUDGET'), false)
  assert.equal(codes.includes('TOTAL_OVER_BUDGET'), false)
  assert.equal(codes.includes('DAILY_OVER_BUDGET'), false)
})

test('预算版:某科被细化轮漏排时,定向修复会把缺口补到预算目标', () => {
  const start = new Date(Date.UTC(2026, 0, 1))
  const exam = addDays(start, 30)
  const brief = budgetBrief(start, [400, 300, 200, 300])
  const budget = buildPlanBudget(brief, start, exam)
  // 变体里完全没有政治:模拟细化轮只盯着语数外,漏掉确认过的科目
  const politicsFree = (phase: PlaybookPhase): AiWeekVariant[] => {
    const base = sampleVariant(phase)
    return [base.filter(task => task.subject !== '政治')]
  }
  const spec = buildPlanSpec({
    title: '漏排政治的预算版',
    targetType: '考研',
    startDate: start,
    examDate: exam,
    brief,
    weakSubjects: [],
    stages: stagesFor(start, exam, politicsFree),
    budget,
  })
  const plan = compilePlanSpec(spec)
  const verification = verifyPlanSpec(spec, plan)
  assert.ok(verification.issues.some(issue => issue.code === 'SUBJECT_UNDER_BUDGET' && issue.subject === '政治'))

  const repaired = repairPlanSpec(spec, verification)
  assert.ok(repaired.actions.some(action => action.includes('政治')), repaired.actions.join('；'))
  const nextPlan = compilePlanSpec(repaired.spec)
  const politics = nextPlan.items
    .filter(item => item.subject === '政治')
    .reduce((sum, item) => sum + item.minutes, 0)
  const target = budget.subjects.find(subject => subject.name === '政治')!.budgetMinutes
  assert.ok(politics + 15 >= target, `政治只补到 ${politics}/${target} 分钟`)
  const codes = verifyPlanSpec(repaired.spec, nextPlan).issues
    .filter(issue => issue.severity === 'error')
    .map(issue => issue.code)
  assert.equal(codes.includes('SUBJECT_UNDER_BUDGET'), false)
  assert.equal(codes.includes('SUBJECT_OVER_BUDGET'), false)
  assert.equal(codes.includes('TOTAL_OVER_BUDGET'), false)
  assert.equal(codes.includes('DAILY_OVER_BUDGET'), false)
})

test('verifyPlanSpec:预算超发、总排量超限与单日超预算各自报错', () => {
  const spec = baseSpec()
  spec.dayCaps = { [spec.stages[0].startDate]: 100 }
  spec.budget = {
    ...(spec.budget ?? {
      capacityMinutes: 0, effectiveCapacityMinutes: 0, confirmedMinutes: 0, estimatedMinutes: 0,
      estimatedBudgetMinutes: 0, targetMinutes: 0, shortfallMinutes: 0, deficits: [], subjects: [], warnings: [],
    }),
    targetMinutes: 100,
  }
  spec.subjects[0].upperMinutes = 100
  const item = {
    stageOrder: 0,
    subject: spec.subjects[0].name,
    title: '超发任务',
    planDate: new Date(`${spec.stages[0].startDate}T00:00:00.000Z`),
    minutes: 2000,
    priority: 0,
    sortOrder: 0,
  }
  const codes = verifyPlanSpec(spec, { stages: [], items: [item] }).issues.map(issue => issue.code)
  assert.ok(codes.includes('SUBJECT_OVER_BUDGET'))
  assert.ok(codes.includes('TOTAL_OVER_BUDGET'))
  assert.ok(codes.includes('DAILY_OVER_BUDGET'))
})
