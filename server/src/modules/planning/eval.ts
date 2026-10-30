import { addDays, dayStart, diffDays } from './schemas'
import { EMPTY_BRIEF, netAvailableMinutes, type PlanBrief } from './document'
import { stageArchetypes, topicsForSubject, actionsForSubject, type PlaybookPhase } from './playbooks'
import type { AiWeekVariant, GeneratedPlan, GeneratedStage } from './generator'
import { buildPlanSpec, compilePlanSpec, verifyPlanSpec, type PlanSpec } from './planSpec'

/**
 * 计划质量评测:把 PlanSpec 编译后算一组可比较的指标。
 *
 * 生成链路里已经有确定性校验器(错误会阻断/修复),这里不重复做闸门,
 * 而是给开发与回归用:同样的输入,计划变「薄」、科目失衡、周重复、时段丢失
 * 都应该在指标上立刻看得见,而不是等考生用了几周才发现。
 */

export type PlanMetrics = {
  horizonDays: number
  stageCount: number
  weekCount: number
  items: number
  scheduledDays: number
  totalMinutes: number
  capacityMinutes: number
  /** 排入分钟 / 真实空闲,0–1 */
  utilization: number
  avgMinutesPerScheduledDay: number
  distinctTitles: number
  /** 带开始时刻的任务占比 */
  slotCoverage: number
  /** 各科实际分钟与按配额应排分钟 */
  subjects: Array<{ subject: string; planned: number; expected: number; deviation: number }>
  /** 完全相同的周(按任务集签名)有多少对 */
  repeatedWeeks: number
  issuesByCode: Record<string, number>
  errorCount: number
  warningCount: number
}

function iso(d: Date): string {
  return d.toISOString().slice(0, 10)
}

function capacityBetween(brief: PlanBrief, start: string, exam: string): number {
  let total = 0
  for (let day = start; day < exam; day = iso(addDays(new Date(`${day}T00:00:00.000Z`), 1))) {
    total += netAvailableMinutes(brief, new Date(`${day}T00:00:00.000Z`))
  }
  return total
}

export function evaluatePlan(spec: PlanSpec, plan: GeneratedPlan): PlanMetrics {
  const verification = verifyPlanSpec(spec, plan)
  const days = new Set(plan.items.map(item => iso(item.planDate)))
  const totalMinutes = plan.items.reduce((sum, item) => sum + item.minutes, 0)
  const capacityMinutes = capacityBetween(spec.brief, spec.startDate, spec.examDate)
  const plannedBySubject = new Map<string, number>()
  const titles = new Set<string>()
  let withSlot = 0
  for (const item of plan.items) {
    plannedBySubject.set(item.subject, (plannedBySubject.get(item.subject) ?? 0) + item.minutes)
    titles.add(`${item.subject}|${item.title}`)
    if (item.startTime) withSlot++
  }
  const expectedBySubject = new Map<string, number>()
  for (const stage of spec.stages) {
    const target = stage.weeks.reduce((sum, week) => sum + week.targetMinutes, 0)
    for (const mix of stage.subjectMix) {
      expectedBySubject.set(mix.subject, (expectedBySubject.get(mix.subject) ?? 0) + target * mix.share)
    }
  }
  const subjects = spec.subjects.map(subject => {
    const planned = plannedBySubject.get(subject.name) ?? 0
    const expected = Math.round(expectedBySubject.get(subject.name) ?? 0)
    return {
      subject: subject.name,
      planned,
      expected,
      deviation: expected === 0 ? 0 : Math.round(((planned - expected) / expected) * 100) / 100,
    }
  })

  let repeatedWeeks = 0
  for (const stage of spec.stages) {
    const seen = new Map<string, number>()
    for (const week of stage.weeks) {
      const signature = week.tasks.map(task => `${task.weekday}|${task.subject}|${task.title}`).sort().join(';')
      if (!signature) continue
      const count = seen.get(signature) ?? 0
      repeatedWeeks += count
      seen.set(signature, count + 1)
    }
  }

  const issuesByCode: Record<string, number> = {}
  for (const issue of verification.issues) {
    issuesByCode[issue.code] = (issuesByCode[issue.code] ?? 0) + 1
  }

  return {
    horizonDays: diffDays(new Date(`${spec.examDate}T00:00:00.000Z`), new Date(`${spec.startDate}T00:00:00.000Z`)),
    stageCount: spec.stages.length,
    weekCount: spec.stages.reduce((sum, stage) => sum + stage.weeks.length, 0),
    items: plan.items.length,
    scheduledDays: days.size,
    totalMinutes,
    capacityMinutes,
    utilization: capacityMinutes === 0 ? 0 : Math.round((totalMinutes / capacityMinutes) * 100) / 100,
    avgMinutesPerScheduledDay: days.size === 0 ? 0 : Math.round(totalMinutes / days.size),
    distinctTitles: titles.size,
    slotCoverage: plan.items.length === 0 ? 1 : Math.round((withSlot / plan.items.length) * 100) / 100,
    subjects,
    repeatedWeeks,
    issuesByCode,
    errorCount: verification.issues.filter(issue => issue.severity === 'error').length,
    warningCount: verification.issues.filter(issue => issue.severity === 'warn').length,
  }
}

/** 科目配比贴近 playbook 权重:数学 4 / 专业课 3 / 英语 2 / 政治 1(政治基础期还会降权) */
const SAMPLE_POOL: Record<PlaybookPhase, string[]> = {
  foundation: ['数学二', '数学二', '数据结构', '英语二', '数学二', '数据结构', '政治', '数学二', '英语二', '数据结构'],
  strengthen: ['数学二', '数学二', '数据结构', '英语二', '数学二', '数据结构', '政治', '数学二', '英语二', '数据结构'],
  sprint: ['数学二', '数学二', '数据结构', '英语二', '数学二', '数据结构', '政治', '数学二', '英语二', '数据结构'],
}

/** 按当天真实容量生成一整周任务:工作日 2 条、周末 4–5 条,接近真实细化轮的密度 */
function sampleVariant(phase: PlaybookPhase, offset: number): AiWeekVariant {
  const tasks: AiWeekVariant = []
  const fill = phase === 'foundation' ? 0.8 : phase === 'strengthen' ? 0.85 : 0.9
  const pool = SAMPLE_POOL[phase]
  for (let weekday = 1; weekday <= 7; weekday++) {
    const target = Math.round((weekday >= 6 ? 420 : 180) * fill)
    const count = Math.max(1, Math.ceil(target / 80))
    const minutes = Math.max(20, Math.min(90, Math.floor(target / count / 5) * 5))
    for (let index = 0; index < count; index++) {
      const subject = pool[(weekday * 3 + offset + index * 4) % pool.length]
      const topics = topicsForSubject(subject, phase)
      const actions = actionsForSubject(subject, phase)
      const topic = topics[(weekday * 3 + offset + index * 2) % Math.max(1, topics.length)] ?? '本周主线'
      const template = actions[(weekday + index + offset) % Math.max(1, actions.length)] ?? '{topic}'
      tasks.push({
        weekday,
        subject,
        title: template.replace('{topic}', topic),
        minutes,
      })
    }
  }
  return tasks
}

/**
 * 评测用样例:一份 468 天考研计划的输入(在校生,工作日晚上 + 周末整块)。
 * 与真实生成走同一条 buildPlanSpec → compilePlanSpec 链路,只是不需要模型。
 */
export function samplePlanSpec(days = 468): PlanSpec {
  const start = dayStart(new Date())
  const exam = addDays(start, days)
  const subjects = [
    { name: '数学二', progress: '一轮过完', scope: '高数+线代', remaining: 14000 },
    { name: '英语二', progress: '单词二刷', scope: '阅读+作文', remaining: 9000 },
    { name: '政治', progress: '未开始', scope: '马原+史纲+时政', remaining: 6000 },
    { name: '数据结构', progress: '过完一半', scope: '王道全书', remaining: 11000 },
  ]
  const brief: PlanBrief = {
    ...EMPTY_BRIEF,
    summary: '在校生,白天上课,晚上与周末整块复习',
    examSubjects: subjects.map(subject => ({
      name: subject.name,
      progress: subject.progress,
      scope: subject.scope,
      remainingMinutes: subject.remaining,
      milestone: '完成一轮强化',
      milestoneDate: iso(addDays(start, Math.round(days * 0.75))),
      milestoneMinutes: Math.round(subject.remaining * 0.4),
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
  const archetypes = stageArchetypes('考研', days)
  const stages: GeneratedStage[] = []
  let cursor = start
  for (let index = 0; index < archetypes.length; index++) {
    const remain = diffDays(exam, cursor)
    const span = index === archetypes.length - 1
      ? remain
      : Math.max(1, Math.round(days * archetypes[index].ratio))
    const end = addDays(cursor, Math.max(1, span) - 1)
    const phase: PlaybookPhase = index === 0 ? 'foundation' : index === archetypes.length - 1 ? 'sprint' : 'strengthen'
    stages.push({
      name: archetypes[index].name,
      startDate: cursor,
      endDate: end,
      sortOrder: index,
      strategy: archetypes[index].strategy,
      milestones: archetypes[index].milestones,
      weekVariants: [sampleVariant(phase, index * 2), sampleVariant(phase, index * 2 + 1)],
    })
    cursor = addDays(end, 1)
  }
  return buildPlanSpec({
    title: `考研 ${days} 天全程计划`,
    targetType: '考研',
    startDate: start,
    examDate: exam,
    brief,
    weakSubjects: ['数学二'],
    stages,
  })
}

/** 命令行入口:编译样例计划并输出指标与校验摘要 */
export function runSampleEval(days = 468): PlanMetrics {
  const spec = samplePlanSpec(days)
  return evaluatePlan(spec, compilePlanSpec(spec))
}
