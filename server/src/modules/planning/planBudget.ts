import { createHash } from 'node:crypto'
import { assessPlanningFacts, netAvailableMinutes, type PlanBrief } from './document'
import { dayStart, diffDays } from './schemas'

/**
 * 计划预算:把「考生确认的剩余任务」换算成一份排得下、可验收的目标。
 *
 * 旧生成器只看每天容量,能排多少排多少,400 天的计划能排出 15,000 分钟,
 * 而考生真正剩下的任务只有 3,000 分钟。这里把总目标钉死,生成器、校验器、
 * 草案预览和 overview 都读同一份数字,避免三处各算一套。
 */

export const PLAN_LOAD_FACTOR = Math.min(1, Math.max(0.5,
  Number(process.env.PLAN_LOAD_FACTOR ?? 0.85) || 0.85))
export const REVIEW_SUBJECT = '复盘'
const DAY_MS = 86_400_000

export interface PlanBudgetSubject {
  name: string
  estimated: boolean
  remainingMinutes: number
  /** 本科计划总量(估算科目按容量缩放) */
  budgetMinutes: number
  /** 允许的上浮上限:确认科目 +30 分钟取整余量 */
  upperMinutes: number
  milestone: string
  milestoneDate: string
  milestoneMinutes: number
  /** 里程碑前至少要完成的量(尽力版按比例缩放) */
  milestoneBudgetMinutes: number
}

export interface PlanBudget {
  capacityMinutes: number
  effectiveCapacityMinutes: number
  confirmedMinutes: number
  estimatedMinutes: number
  estimatedBudgetMinutes: number
  targetMinutes: number
  shortfallMinutes: number
  deficits: Array<{ subject: string; milestone: string; missingMinutes: number }>
  /** 每天生成目标上限(净空闲 × 85%);手工编辑的硬上限仍是净空闲 */
  dayCap: Map<string, number>
  subjects: PlanBudgetSubject[]
  warnings: string[]
}

export interface PlanBudgetView {
  capacityMinutes: number
  effectiveCapacityMinutes: number
  confirmedMinutes: number
  estimatedMinutes: number
  estimatedBudgetMinutes: number
  targetMinutes: number
  shortfallMinutes: number
  deficits: Array<{ subject: string; milestone: string; missingMinutes: number }>
  subjects: Array<Omit<PlanBudgetSubject, 'upperMinutes'>>
  warnings: string[]
}

export function buildPlanBudget(
  brief: PlanBrief,
  startDate: Date,
  examDate: Date,
  options: { allowShortfall?: boolean } = {},
): PlanBudget {
  // 0.85 是浮点数,5000 × 0.408 会得到 2040.0000000000002 / 2039.9999999999998;
  // 直接 floor 会凭空少 1 分钟,加极小 epsilon 让整数边界稳定落在整数上
  const scaleMinutes = (minutes: number, scale: number) => Math.floor(minutes * scale + 1e-6)
  const start = dayStart(startDate)
  const exam = dayStart(examDate)
  const dayCap = new Map<string, number>()
  let capacityMinutes = 0
  for (let cursor = start.getTime(); cursor < exam.getTime(); cursor += DAY_MS) {
    const date = new Date(cursor)
    const day = date.toISOString().slice(0, 10)
    const capacity = Math.max(0, netAvailableMinutes(brief, date))
    capacityMinutes += capacity
    // 任务时长按 5 分钟粒度生成,日上限也钉到 5 分钟,否则 180×85%=153
    // 这种上限会让每天最多只能排 150 分钟,计划永远差一截而无法覆盖预算
    dayCap.set(day, Math.floor(capacity * PLAN_LOAD_FACTOR / 5) * 5)
  }
  const effectiveCapacityMinutes = [...dayCap.values()].reduce((sum, value) => sum + value, 0)
  const confirmed = brief.examSubjects.filter(subject => !subject.estimated)
  const estimated = brief.examSubjects.filter(subject => subject.estimated)
  const sum = (list: typeof confirmed) => list.reduce((total, subject) => total + Math.max(0, subject.remainingMinutes), 0)
  const confirmedMinutes = sum(confirmed)
  const estimatedMinutes = sum(estimated)
  const shortfallMinutes = Math.max(0, confirmedMinutes - effectiveCapacityMinutes)
  const confirmedScale = options.allowShortfall && shortfallMinutes > 0 && confirmedMinutes > 0
    ? effectiveCapacityMinutes / confirmedMinutes
    : 1
  const confirmedBudget = scaleMinutes(confirmedMinutes, confirmedScale)
  const estimatedBudgetMinutes = Math.max(0, Math.min(estimatedMinutes,
    Math.max(0, effectiveCapacityMinutes - confirmedBudget)))
  const estimatedScale = estimatedMinutes > 0 ? estimatedBudgetMinutes / estimatedMinutes : 1

  const subjects: PlanBudgetSubject[] = brief.examSubjects.map(subject => {
    const scale = subject.estimated ? estimatedScale : confirmedScale
    const budgetMinutes = scaleMinutes(Math.max(0, subject.remainingMinutes), scale)
    const milestoneBudgetMinutes = subject.estimated
      ? Math.max(0, subject.milestoneMinutes)
      : scaleMinutes(Math.max(0, subject.milestoneMinutes), confirmedScale)
    return {
      name: subject.name,
      estimated: subject.estimated,
      remainingMinutes: subject.remainingMinutes,
      budgetMinutes,
      upperMinutes: subject.estimated ? Math.floor(budgetMinutes * 1.05) : Math.floor(budgetMinutes * 1.05) + 30,
      milestone: subject.milestone,
      milestoneDate: subject.milestoneDate,
      milestoneMinutes: subject.milestoneMinutes,
      milestoneBudgetMinutes: Math.min(milestoneBudgetMinutes, budgetMinutes),
    }
  })

  const warnings: string[] = []
  if (shortfallMinutes > 0 && !options.allowShortfall) {
    warnings.push(`已确认任务比有效容量多 ${shortfallMinutes} 分钟,需要先做取舍`)
  }
  if (options.allowShortfall && shortfallMinutes > 0) {
    warnings.push(`尽力版按有效容量缩放,部分科目可能无法覆盖全部剩余任务`)
  }
  if (estimatedMinutes > estimatedBudgetMinutes) {
    warnings.push(`估算科目已按剩余容量缩放:${estimatedMinutes} → ${estimatedBudgetMinutes} 分钟`)
  }

  const facts = assessPlanningFacts(brief, start, exam, true)
  const deficits = options.allowShortfall ? [] : facts.deficits
  return {
    capacityMinutes,
    effectiveCapacityMinutes,
    confirmedMinutes,
    estimatedMinutes,
    estimatedBudgetMinutes,
    targetMinutes: subjects.reduce((total, subject) => total + subject.budgetMinutes, 0),
    shortfallMinutes,
    deficits,
    dayCap,
    subjects,
    warnings,
  }
}

export function budgetView(budget: PlanBudget): PlanBudgetView {
  return {
    capacityMinutes: budget.capacityMinutes,
    effectiveCapacityMinutes: budget.effectiveCapacityMinutes,
    confirmedMinutes: budget.confirmedMinutes,
    estimatedMinutes: budget.estimatedMinutes,
    estimatedBudgetMinutes: budget.estimatedBudgetMinutes,
    targetMinutes: budget.targetMinutes,
    shortfallMinutes: budget.shortfallMinutes,
    deficits: budget.deficits,
    subjects: budget.subjects.map(subject => ({
      name: subject.name,
      estimated: subject.estimated,
      remainingMinutes: subject.remainingMinutes,
      budgetMinutes: subject.budgetMinutes,
      milestone: subject.milestone,
      milestoneDate: subject.milestoneDate,
      milestoneMinutes: subject.milestoneMinutes,
      milestoneBudgetMinutes: subject.milestoneBudgetMinutes,
    })),
    warnings: budget.warnings,
  }
}

/** 规范化口径:只保留影响排课的字段,展示性字段(summary/goals/notes)不参与哈希 */
export function canonicalBrief(brief: PlanBrief, examDate: string): string {
  const windows = (list: Array<{ weekday: number; windows: Array<{ start: string; end: string }> }>) =>
    [...list].sort((a, b) => a.weekday - b.weekday).map(day => ({
      weekday: day.weekday,
      windows: [...day.windows].sort((a, b) => a.start.localeCompare(b.start) || a.end.localeCompare(b.end)),
    }))
  return JSON.stringify({
    examDate,
    subjects: [...brief.examSubjects]
      .sort((a, b) => a.name.localeCompare(b.name))
      .map(subject => ({
        name: subject.name, progress: subject.progress, scope: subject.scope,
        remainingMinutes: subject.remainingMinutes, milestone: subject.milestone,
        milestoneDate: subject.milestoneDate, milestoneMinutes: subject.milestoneMinutes,
        estimated: subject.estimated,
      })),
    availability: windows(brief.availability),
    fixedCommitments: [...brief.fixedCommitments]
      .sort((a, b) => a.weekday - b.weekday || a.start.localeCompare(b.start) || a.end.localeCompare(b.end) || a.label.localeCompare(b.label)),
    periods: [...(brief.periods ?? [])].sort((a, b) => a.start.localeCompare(b.start) || a.end.localeCompare(b.end)),
    availabilityConfirmed: brief.availabilityConfirmed,
    commitmentsConfirmed: brief.commitmentsConfirmed,
  })
}

export function briefHash(brief: PlanBrief, examDate: string): string {
  return createHash('sha256').update(canonicalBrief(brief, examDate)).digest('hex')
}

/** 调整单指纹的上下文:口径/作息/考期任一变化,旧调整单都应失效 */
export function briefContextHash(brief: PlanBrief | null, examDate: string): string {
  return brief ? briefHash(brief, examDate) : createHash('sha256').update(`empty:${examDate}`).digest('hex')
}

export function capacityByWeekday(brief: PlanBrief, startDate: Date, examDate: Date): Array<{ weekday: number; minutes: number }> {
  const totals = new Map<number, { total: number; count: number }>()
  for (let cursor = dayStart(startDate).getTime(); cursor < dayStart(examDate).getTime(); cursor += DAY_MS) {
    const date = new Date(cursor)
    const weekday = date.getUTCDay() || 7
    const entry = totals.get(weekday) ?? { total: 0, count: 0 }
    entry.total += Math.max(0, netAvailableMinutes(brief, date))
    entry.count += 1
    totals.set(weekday, entry)
  }
  return Array.from({ length: 7 }, (_, index) => {
    const entry = totals.get(index + 1)
    return { weekday: index + 1, minutes: entry && entry.count > 0 ? Math.round(entry.total / entry.count) : 0 }
  })
}

export interface OverviewInputPlan {
  title: string
  version: number
  startDate: string
  examDate: string
  status: string
}
export interface OverviewStage {
  name: string
  startDate: string
  endDate: string
}
export interface OverviewItem {
  subject: string
  planDate: string
  minutes: number
  status: string
}

/** 草案/生效计划摘要:预算表、逐日负荷、阶段目标与警告都从同一份计划项派生 */
export function buildPlanOverview(
  plan: OverviewInputPlan,
  brief: PlanBrief,
  stages: OverviewStage[],
  items: OverviewItem[],
  budget = buildPlanBudget(brief, new Date(`${plan.startDate}T00:00:00.000Z`), new Date(`${plan.examDate}T00:00:00.000Z`)),
) {
  const scheduled = new Map<string, number>()
  const beforeMilestone = new Map<string, number>()
  const dayMap = new Map<string, { minutes: number; count: number }>()
  for (const item of items) {
    const subject = item.subject
    scheduled.set(subject, (scheduled.get(subject) ?? 0) + item.minutes)
    const day = dayMap.get(item.planDate) ?? { minutes: 0, count: 0 }
    day.minutes += item.minutes
    day.count += 1
    dayMap.set(item.planDate, day)
    const spec = budget.subjects.find(entry => entry.name === subject)
    if (spec && item.planDate <= spec.milestoneDate) {
      beforeMilestone.set(subject, (beforeMilestone.get(subject) ?? 0) + item.minutes)
    }
  }
  const subjects = budget.subjects.map(subject => {
    const scheduledMinutes = scheduled.get(subject.name) ?? 0
    const before = beforeMilestone.get(subject.name) ?? 0
    return {
      name: subject.name,
      estimated: subject.estimated,
      targetMinutes: subject.budgetMinutes,
      scheduledMinutes,
      milestone: subject.milestone,
      milestoneDate: subject.milestoneDate,
      milestoneMinutes: subject.milestoneBudgetMinutes,
      beforeMilestoneMinutes: before,
      deltaMinutes: scheduledMinutes - subject.budgetMinutes,
    }
  })
  const days = Array.from(budget.dayCap.keys()).map(date => {
    const entry = dayMap.get(date) ?? { minutes: 0, count: 0 }
    const capacity = netAvailableMinutes(brief, new Date(`${date}T00:00:00.000Z`))
    return {
      date,
      capacityMinutes: capacity,
      dayCapMinutes: budget.dayCap.get(date) ?? 0,
      scheduledMinutes: entry.minutes,
      itemCount: entry.count,
    }
  })
  const scheduledTotal = items.filter(item => item.subject !== REVIEW_SUBJECT).reduce((sum, item) => sum + item.minutes, 0)
  const warnings: Array<{ code: string; message: string; date?: string; subject?: string }> = []
  if (budget.shortfallMinutes > 0) {
    warnings.push({ code: 'SHORTFALL', message: `已确认任务超出有效容量 ${budget.shortfallMinutes} 分钟` })
  }
  for (const subject of subjects) {
    if (subject.scheduledMinutes > (budget.subjects.find(entry => entry.name === subject.name)?.upperMinutes ?? subject.targetMinutes)) {
      warnings.push({ code: 'SUBJECT_OVER_BUDGET', message: `${subject.name} 实排 ${subject.scheduledMinutes} 分钟,超过目标 ${subject.targetMinutes} 分钟`, subject: subject.name })
    }
    if (!subject.estimated && subject.beforeMilestoneMinutes + 1 < subject.milestoneMinutes) {
      warnings.push({ code: 'MILESTONE_RISK', message: `${subject.name} 里程碑前还差 ${subject.milestoneMinutes - subject.beforeMilestoneMinutes} 分钟`, subject: subject.name })
    }
    if (subject.estimated) {
      warnings.push({ code: 'ESTIMATED_SUBJECT', message: `${subject.name} 的剩余量是估算值,可在草案里更正`, subject: subject.name })
    }
  }
  if (scheduledTotal > Math.floor(budget.targetMinutes * 1.05) + 1) {
    warnings.push({ code: 'TOTAL_OVER_BUDGET', message: `总排量 ${scheduledTotal} 分钟超过目标 ${budget.targetMinutes} 分钟` })
  }
  const stageCapacity = stages.map(stage => {
    let total = 0
    const end = new Date(`${stage.endDate}T00:00:00.000Z`)
    for (let cursor = new Date(`${stage.startDate}T00:00:00.000Z`); cursor <= end; cursor = new Date(cursor.getTime() + DAY_MS)) {
      total += Math.max(0, netAvailableMinutes(brief, cursor))
    }
    return total
  })
  const totalStageCapacity = stageCapacity.reduce((sum, value) => sum + value, 0)
  return {
    title: plan.title,
    version: plan.version,
    startDate: plan.startDate,
    examDate: plan.examDate,
    status: plan.status,
    budget: {
      capacityMinutes: budget.capacityMinutes,
      effectiveCapacityMinutes: budget.effectiveCapacityMinutes,
      targetMinutes: budget.targetMinutes,
      scheduledMinutes: items.reduce((sum, item) => sum + item.minutes, 0),
      confirmedMinutes: budget.confirmedMinutes,
      estimatedMinutes: budget.estimatedMinutes,
      utilization: budget.targetMinutes > 0 ? Math.round((scheduledTotal / budget.targetMinutes) * 1000) / 1000 : 0,
      shortfallMinutes: budget.shortfallMinutes,
    },
    subjects,
    days,
    stages: stages.map((stage, index) => ({
      name: stage.name,
      startDate: stage.startDate,
      endDate: stage.endDate,
      targetMinutes: totalStageCapacity > 0
        ? Math.floor((stageCapacity[index] / totalStageCapacity) * budget.targetMinutes)
        : Math.floor(budget.targetMinutes / Math.max(1, stages.length)),
      scheduledMinutes: items.filter(item => item.planDate >= stage.startDate && item.planDate <= stage.endDate)
        .reduce((sum, item) => sum + item.minutes, 0),
    })),
    warnings,
    horizonDays: diffDays(new Date(`${plan.examDate}T00:00:00.000Z`), new Date(`${plan.startDate}T00:00:00.000Z`)),
  }
}
