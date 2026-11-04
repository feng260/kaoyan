import { addDays, dayStart, diffDays } from './schemas'
import { netAvailableMinutes, type PlanBrief } from './document'
import { PLAN_LOAD_FACTOR, REVIEW_SUBJECT, type PlanBudget } from './planBudget'

/**
 * 规则版计划生成器(Phase 0,纯函数,不依赖数据库与时间随机源)。
 * 同一份输入必须产出完全一致的结果:便于测试、便于多设备拿到同一份计划。
 */

const DAY_MS = 24 * 3600_000
/** 备考周期下限:再短就分不出基础/强化/冲刺 */
export const MIN_RANGE_DAYS = 14
/** 每个自然日最多安排的计划项 */
const MAX_ITEMS_PER_DAY = 3
/** 单日可用时长达到该值时,额外安排一项复盘 */
const REVIEW_THRESHOLD_MINUTES = 120
/** 复盘项时长 */
const REVIEW_MINUTES = 30
/** 单日净空闲低于该值就不排计划项:排不出有意义的内容 */
const MIN_SCHEDULABLE_MINUTES = 15
const REVIEW_TITLE = '错题回顾与复盘'

type StageDef = { name: string; ratio: number; priority: number; titleSuffix: string }

const STAGE_DEFS: StageDef[] = [
  { name: '基础阶段', ratio: 0.45, priority: 0, titleSuffix: '基础梳理' },
  { name: '强化阶段', ratio: 0.35, priority: 1, titleSuffix: '专项强化' },
  { name: '冲刺阶段', ratio: 0.2, priority: 2, titleSuffix: '冲刺训练' },
]

export type PlanningInput = Pick<PlanBrief, 'availability' | 'fixedCommitments'> & {
  examDate: Date
  startDate: Date
  weakSubjects: string[]
  studyWindows: string[]
  /** 确认过剩余任务时按预算排课:总量封顶、逐日留白,不再把空闲填满 */
  budget?: PlanBudget | null
}

export type GeneratedStage = {
  name: string
  startDate: Date
  endDate: Date
  sortOrder: number
  /** L1 策略轮产物:本阶段主线一句话(细化轮与计划书共用) */
  strategy?: string
  /** L1 策略轮产物:本阶段可验收里程碑 */
  milestones?: string[]
  /** L2 细化轮产物:本阶段的周变体任务序列;缺省时回退 weeklySlots 模板展开 */
  weekVariants?: AiWeekVariant[]
}

/** 一个周变体 = 一周的任务序列(weekday 1-7),由细化轮产出,展开层按周轮换铺满阶段 */
export type AiWeekTask = { weekday: number; subject: string; title: string; minutes: number }
export type AiWeekVariant = AiWeekTask[]

export type GeneratedItem = {
  stageOrder: number
  subject: string
  title: string
  planDate: Date
  minutes: number
  priority: number
  sortOrder: number
  /** 时段:morning/afternoon/evening;由编译器按当天真实空闲窗口给出 */
  slot?: string
  /** 建议开始时刻 HH:mm;客户端按它排序并在任务卡上展示 */
  startTime?: string
}

export type GeneratedPlan = {
  stages: GeneratedStage[]
  items: GeneratedItem[]
}

export type PlanErrorCode =
  | 'INVALID_EXAM_DATE'
  | 'RANGE_TOO_SHORT'
  | 'INVALID_WEAK_SUBJECTS'

export class PlanGenerationError extends Error {
  constructor(public code: PlanErrorCode, message: string) {
    super(message)
    this.name = 'PlanGenerationError'
  }
}

/** 按比例切分可用天数,保证每阶段至少一天且总和不变 */
function splitStageDays(totalDays: number): number[] {
  const counts = [
    Math.round(totalDays * STAGE_DEFS[0].ratio),
    Math.round(totalDays * STAGE_DEFS[1].ratio),
    0,
  ]
  counts[2] = totalDays - counts[0] - counts[1]
  for (let i = 0; i < 2; i++) {
    if (counts[i] < 1) {
      counts[2] -= 1 - counts[i]
      counts[i] = 1
    }
  }
  if (counts[2] < 1) {
    counts[0] -= 1 - counts[2]
    counts[2] = 1
  }
  return counts
}

/** 把可用时长摊到各项:除不尽的余数给第一项,保证总额等于可用时长 */
function allocateMinutes(total: number, count: number): number[] {
  const base = Math.floor(total / count)
  const out = new Array<number>(count).fill(base)
  out[0] += total - base * count
  return out
}

function isoDay(d: Date): string {
  return dayStart(d).toISOString().slice(0, 10)
}

/**
 * 预算版排课:先扣住逐科剩余任务总量,再按每日预算(净空闲 × 85%)能排多少排多少。
 * 旧逻辑把每天的空闲全部填满,长周期计划会排出远超考生真实剩余量的任务;
 * 这里选科目按「里程碑临近 → 薄弱科目 → 轮转」,单条 15–60 分钟,预算耗尽即停止。
 */
function generateBudgetItems(input: PlanningInput, stages: GeneratedStage[], weakSubjects: string[]): GeneratedItem[] {
  const budget = input.budget!
  const remaining = new Map(
    budget.subjects
      .filter(subject => subject.budgetMinutes >= MIN_SCHEDULABLE_MINUTES)
      .map(subject => [subject.name, subject.budgetMinutes]),
  )
  let remainingTotal = [...remaining.values()].reduce((sum, value) => sum + value, 0)
  // 复盘本身已被确认成一门科目时,它就走普通科目预算,不再额外叠加复盘条目
  const reviewBudgeted = budget.subjects.some(subject => subject.name === REVIEW_SUBJECT)
  const reviewCap = reviewBudgeted ? 0 : Math.floor(budget.targetMinutes * 0.1)
  let reviewTotal = 0
  const items: GeneratedItem[] = []
  const weak = new Set(weakSubjects)
  let rotation = 0

  const daysTo = (date: string, day: Date): number =>
    date ? diffDays(new Date(`${date}T00:00:00.000Z`), day) : Number.MAX_SAFE_INTEGER

  const pickSubject = (day: Date): string | null => {
    const eligible = budget.subjects.filter(subject => (remaining.get(subject.name) ?? 0) >= MIN_SCHEDULABLE_MINUTES)
    if (eligible.length === 0) return null
    const ranked = [...eligible].sort((a, b) => {
      const am = daysTo(a.milestoneDate, day)
      const bm = daysTo(b.milestoneDate, day)
      if (am >= 0 && bm >= 0 && am !== bm) return am - bm
      if ((am >= 0) !== (bm >= 0)) return am >= 0 ? -1 : 1
      const aw = weak.has(a.name) ? 0 : 1
      const bw = weak.has(b.name) ? 0 : 1
      if (aw !== bw) return aw - bw
      return 0
    })
    return ranked[rotation++ % ranked.length].name
  }

  for (const [stageOrder, stage] of stages.entries()) {
    const def = STAGE_DEFS[stageOrder]
    const stageLength = diffDays(stage.endDate, stage.startDate) + 1
    for (let offset = 0; offset < stageLength; offset++) {
      const planDate = addDays(stage.startDate, offset)
      const capacity = netAvailableMinutes(input, planDate)
      if (capacity < MIN_SCHEDULABLE_MINUTES) continue
      const cap = Math.min(capacity, budget.dayCap.get(isoDay(planDate)) ?? Math.floor(capacity * PLAN_LOAD_FACTOR))
      let available = Math.min(cap, remainingTotal)
      if (available < MIN_SCHEDULABLE_MINUTES) continue
      let sortOrder = 0
      let studyToday = 0
      // 与旧排课一致:留出复盘位时最多两门主科,否则三门主科全部排上,
      // 小容量日也能让每门课都露脸;复盘预算不足 15 分钟时不留空位
      const reviewReserved = reviewCap - reviewTotal >= MIN_SCHEDULABLE_MINUTES
      const maxStudyItems = reviewReserved ? MAX_ITEMS_PER_DAY - 1 : MAX_ITEMS_PER_DAY
      while (available >= MIN_SCHEDULABLE_MINUTES && sortOrder < maxStudyItems) {
        const subject = pickSubject(planDate)
        if (!subject) break
        const left = remaining.get(subject) ?? 0
        const minutes = Math.floor(Math.min(left, available, 60) / 5) * 5
        if (minutes < MIN_SCHEDULABLE_MINUTES) break
        items.push({
          stageOrder,
          subject,
          title: `${subject} · ${def.titleSuffix}`,
          planDate,
          minutes,
          priority: def.priority,
          sortOrder: sortOrder++,
        })
        remaining.set(subject, left - minutes)
        remainingTotal -= minutes
        available -= minutes
        studyToday += minutes
      }
      const reviewMinutes = studyToday >= 60 && reviewTotal < reviewCap && available >= 30
        ? Math.min(30, reviewCap - reviewTotal, available)
        : 0
      if (reviewMinutes >= MIN_SCHEDULABLE_MINUTES) {
        items.push({
          stageOrder,
          subject: REVIEW_SUBJECT,
          title: REVIEW_TITLE,
          planDate,
          minutes: reviewMinutes,
          priority: def.priority,
          sortOrder: sortOrder++,
        })
        reviewTotal += reviewMinutes
      }
    }
  }
  return items
}

export function generateRulePlan(input: PlanningInput): GeneratedPlan {
  const today = dayStart(new Date())
  const exam = dayStart(input.examDate)
  const requestedStart = dayStart(input.startDate)

  if (Number.isNaN(exam.getTime()) || Number.isNaN(requestedStart.getTime())) {
    throw new PlanGenerationError('INVALID_EXAM_DATE', '考试日期不是有效日期')
  }
  if (exam.getTime() <= today.getTime()) {
    throw new PlanGenerationError('INVALID_EXAM_DATE', '考试日期需要晚于今天,无法生成计划')
  }
  // 开始日期早于今天时从今天起算,避免把计划项排进已经过去的日子
  const start = requestedStart.getTime() < today.getTime() ? today : requestedStart
  if (exam.getTime() <= start.getTime()) {
    throw new PlanGenerationError('INVALID_EXAM_DATE', '考试日期必须晚于开始日期')
  }

  const totalDays = diffDays(exam, start)
  if (totalDays < MIN_RANGE_DAYS) {
    throw new PlanGenerationError('RANGE_TOO_SHORT', `距离考试只剩 ${totalDays} 天,至少需要 ${MIN_RANGE_DAYS} 天`)
  }

  const weakSubjects = (input.weakSubjects ?? []).map(s => s.trim()).filter(Boolean)
  if (weakSubjects.length === 0) {
    throw new PlanGenerationError('INVALID_WEAK_SUBJECTS', '至少需要一门薄弱科目才能排计划')
  }

  // ---- 阶段:连续、无空隙、最后一天截止到考前一天 ----
  const stageDays = splitStageDays(totalDays)
  const stages: GeneratedStage[] = []
  let cursor = start
  for (const [index, def] of STAGE_DEFS.entries()) {
    const days = stageDays[index]
    const endDate = addDays(cursor, days - 1)
    stages.push({ name: def.name, startDate: cursor, endDate, sortOrder: index })
    cursor = addDays(endDate, 1)
  }

  // ---- 每日计划项:容量按当天「课表净空闲」算,与具体星期几无关的日期跳过 ----
  if (input.budget) {
    return { stages, items: generateBudgetItems(input, stages, weakSubjects) }
  }
  const items: GeneratedItem[] = []
  // 轮转游标:保证多门薄弱科目在所有阶段之间也均匀覆盖
  let rotation = 0
  for (const [stageOrder, stage] of stages.entries()) {
    const def = STAGE_DEFS[stageOrder]
    const stageLength = diffDays(stage.endDate, stage.startDate) + 1
    for (let offset = 0; offset < stageLength; offset++) {
      const planDate = addDays(stage.startDate, offset)
      const capacity = netAvailableMinutes(input, planDate)
      if (capacity < MIN_SCHEDULABLE_MINUTES) continue
      const reviewMinutes = capacity >= REVIEW_THRESHOLD_MINUTES ? REVIEW_MINUTES : 0
      const studyBudget = capacity - reviewMinutes
      const maxStudyItems = reviewMinutes > 0 ? MAX_ITEMS_PER_DAY - 1 : MAX_ITEMS_PER_DAY
      const studyCount = Math.min(weakSubjects.length, maxStudyItems)
      const minutesPerItem = allocateMinutes(studyBudget, studyCount)
      let sortOrder = 0
      for (let slot = 0; slot < studyCount; slot++) {
        const subject = weakSubjects[rotation++ % weakSubjects.length]
        items.push({
          stageOrder,
          subject,
          title: `${subject} · ${def.titleSuffix}`,
          planDate,
          minutes: minutesPerItem[slot],
          priority: def.priority,
          sortOrder: sortOrder++,
        })
      }
      if (reviewMinutes > 0) {
        items.push({
          stageOrder,
          subject: REVIEW_SUBJECT,
          title: REVIEW_TITLE,
          planDate,
          minutes: reviewMinutes,
          priority: def.priority,
          sortOrder: sortOrder++,
        })
      }
    }
  }

  return { stages, items }
}
