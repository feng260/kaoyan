import { addDays, dayStart, diffDays } from './schemas'
import type { AiWeekVariant, GeneratedItem, GeneratedPlan, GeneratedStage } from './generator'
import {
  dayTemplateFor,
  DAY_TEMPLATES,
  PLAN_RULES,
  actionsForSubject,
  phaseOfStage,
  playbookForSubject,
  topicsForSubject,
  type DayTemplate,
  type PlaybookPhase,
} from './playbooks'
import {
  daySchedule,
  freeSlots,
  netAvailableMinutes,
  type PlanBrief,
} from './document'
import { REVIEW_SUBJECT, budgetView, type PlanBudget, type PlanBudgetView } from './planBudget'

/**
 * PlanSpec —— 计划的可检查、可复算中间产物。
 *
 * 为什么需要它:此前的链路是「骨架 → 并行细化 → 直接展开成 items」,每一步的输出
 * 都是一次性的,没有任何一处能在生成后回答「这份计划到底合不合理」。真正像 coding agent
 * 那样工作,必须先产出一份**可以跑校验的工件**:
 *
 *   1. 模型只负责内容(阶段主线、每周任务),日期、容量、时段、科目配额由服务端计算;
 *   2. 编译是纯函数:同一份 spec 永远展开出同一份 items,可测试、可复现;
 *   3. 校验器能指出「哪一周哪个科目缺了、哪天超了容量、哪两周在重复」;
 *   4. 修复只改失败的那一小块(某一阶段的某一周),不用整份重掷骰子。
 *
 * spec 同时是文档的单一事实来源:文档里的阶段日期、科目时间线、每日作息表
 * 都从它派生,不再由模型另写一遍,避免「文档说 3 小时、清单排 5 小时」。
 */

export type SpecPhase = PlaybookPhase

export type SpecSubject = {
  name: string
  playbookId: string
  /** 相对权重:数学/专业课更高,薄弱科目上浮 */
  weight: number
  weak: boolean
  progress: string
  scope: string
  remainingMinutes: number
  /** 本计划给该科定的目标分钟(预算版 = budgetMinutes,旧行为 = 剩余量) */
  budgetMinutes: number
  /** 校验用上限:超过即视为排超预算 */
  upperMinutes: number
  milestone: string
  milestoneDate: string
  milestoneMinutes: number
  /** 里程碑前至少完成的量(预算版按比例缩放) */
  milestoneBudgetMinutes: number
  estimated: boolean
}

export type SpecTask = {
  /** 1=周一 … 7=周日 */
  weekday: number
  subject: string
  title: string
  minutes: number
}

export type SpecWeek = {
  index: number
  startDate: string
  endDate: string
  focus: string
  /** 本周目标投入(按真实容量 × 填充率),校验科目配额用 */
  targetMinutes: number
  tasks: SpecTask[]
}

export type SpecSubjectShare = { subject: string; share: number }

export type SpecStage = {
  name: string
  strategy: string
  milestones: string[]
  startDate: string
  endDate: string
  sortOrder: number
  phase: SpecPhase
  /** 本阶段每周各科的时间占比(权重归一化) */
  subjectMix: SpecSubjectShare[]
  weeks: SpecWeek[]
}

export type PlanSpec = {
  version: 1
  title: string
  targetType: string
  startDate: string
  examDate: string
  /** 确认事实快照:容量、日历区间、逐科里程碑都从这里读 */
  brief: PlanBrief
  /** 预算版计划的硬约束;旧行为(无剩余任务)为 null */
  budget: PlanBudgetView | null
  /** 每天允许生成的上限(净空闲 × 85%);无预算时为空对象 */
  dayCaps: Record<string, number>
  subjects: SpecSubject[]
  stages: SpecStage[]
  dayTemplates: DayTemplate[]
  rules: string[]
  generatedAt: string
}

export type BuildPlanSpecInput = {
  title: string
  targetType: string
  startDate: Date
  examDate: Date
  brief: PlanBrief
  weakSubjects: string[]
  /** 已排好连续日期、并挂上 L2 周变体的阶段 */
  stages: GeneratedStage[]
  /** 有确认剩余任务时按预算生成,不再把空闲填满 */
  budget?: PlanBudget | null
}

const DAY_MS = 24 * 3600_000
const MIN_TASK_MINUTES = 15
const MAX_TASK_MINUTES = 90
const MAX_TITLE = 64
const MAX_TASKS_PER_DAY = 8

function iso(d: Date): string {
  return dayStart(d).toISOString().slice(0, 10)
}

function fromIso(value: string): Date {
  return new Date(`${value}T00:00:00.000Z`)
}

function addIsoDays(value: string, days: number): string {
  return new Date(fromIso(value).getTime() + days * DAY_MS).toISOString().slice(0, 10)
}

function clipTitle(value: string): string {
  const text = value.replace(/\s+/g, ' ').trim()
  return text.length > MAX_TITLE ? text.slice(0, MAX_TITLE) : text
}

function isoWeekday(d: Date): number {
  const js = d.getUTCDay()
  return js === 0 ? 7 : js
}

function roundName(cycle: number): string {
  if (cycle <= 0) return ''
  return cycle === 1 ? '二刷' : `${cycle + 1}刷`
}

function fillRatio(phase: SpecPhase): number {
  return phase === 'foundation' ? 0.8 : phase === 'strengthen' ? 0.85 : 0.9
}

function normalizeSubjectList(input: BuildPlanSpecInput): SpecSubject[] {
  const names = input.brief.examSubjects.map(subject => subject.name)
  const budgetByName = new Map((input.budget?.subjects ?? []).map(subject => [subject.name, subject]))
  const subjects: SpecSubject[] = []
  for (const subject of input.brief.examSubjects) {
    const playbook = playbookForSubject(subject.name)
    let weight = playbook.weight
    if (input.weakSubjects.includes(subject.name)) weight *= 1.35
    const spec = budgetByName.get(subject.name)
    subjects.push({
      name: subject.name,
      playbookId: playbook.id,
      weight,
      weak: input.weakSubjects.includes(subject.name),
      progress: subject.progress,
      scope: subject.scope,
      remainingMinutes: subject.remainingMinutes,
      budgetMinutes: spec ? spec.budgetMinutes : subject.remainingMinutes,
      upperMinutes: spec ? spec.upperMinutes : subject.remainingMinutes + 30,
      milestone: subject.milestone,
      milestoneDate: subject.milestoneDate,
      milestoneMinutes: subject.milestoneMinutes,
      milestoneBudgetMinutes: spec ? spec.milestoneBudgetMinutes : subject.milestoneMinutes,
      estimated: subject.estimated,
    })
  }
  if (subjects.length > 0) return subjects
  // 面谈没给逐科事实时(理论上被生成闸门拦住)退化为「从阶段任务里见过的科目」
  const seen = new Set<string>()
  for (const stage of input.stages) {
    for (const task of stage.weekVariants?.flat() ?? []) {
      if (!seen.has(task.subject)) {
        seen.add(task.subject)
        subjects.push({
          name: task.subject, playbookId: playbookForSubject(task.subject).id,
          weight: playbookForSubject(task.subject).weight, weak: false,
          progress: '', scope: '',
          remainingMinutes: 0, budgetMinutes: 0, upperMinutes: 0,
          milestone: '', milestoneDate: '', milestoneMinutes: 0, milestoneBudgetMinutes: 0, estimated: true,
        })
      }
    }
  }
  return subjects
}

function subjectMixFor(subjects: SpecSubject[], phase: SpecPhase): SpecSubjectShare[] {
  // 有逐科预算时,配比必须服从预算:考生确认的剩余分钟才是各科目标,
  // 用知识库权重算占比会让政治这类「低权重但确有剩余量」的科目系统性欠排。
  const budgetTotal = subjects.reduce((sum, subject) => sum + Math.max(0, subject.budgetMinutes), 0)
  const values = subjects.map(subject => {
    if (budgetTotal > 0) {
      return { subject: subject.name, weight: Math.max(1, subject.budgetMinutes) }
    }
    let weight = subject.weight
    // 政治在基础期不需要和数学同权:跟课+选择题即可,把整块时间让给硬科目
    if (playbookForSubject(subject.name).category === 'politics' && phase === 'foundation') weight *= 0.6
    if (subject.estimated) weight *= 0.8
    return { subject: subject.name, weight }
  })
  const total = values.reduce((sum, item) => sum + item.weight, 0) || 1
  return values.map(item => ({ subject: item.subject, share: item.weight / total }))
}

function weekTarget(brief: PlanBrief, startDate: string, endDate: string, phase: SpecPhase): number {
  let capacity = 0
  for (let day = startDate; day <= endDate; day = addIsoDays(day, 1)) {
    capacity += netAvailableMinutes(brief, fromIso(day))
  }
  return Math.round(capacity * fillRatio(phase))
}

/**
 * 把一个周变体转成本阶段可用的周任务:
 * - 首轮沿用模型写的标题;
 * - 变体循环到下一轮时加「二刷/三刷」前缀,并换一个知识点 —— 旧实现按
 *   `weekIdx % variants.length` 原样重放,长阶段会出现几十周完全相同的计划;
 * - 阶段内同一「星期+科目+标题」重复时用知识点兜底改写,保证每天内容可区分。
 */
function transformVariant(
  variant: AiWeekVariant,
  cycle: number,
  stage: SpecStage,
  subjects: SpecSubject[],
  used: Set<string>,
): SpecTask[] {
  const allowed = new Set(subjects.map(subject => subject.name))
  const tasks: SpecTask[] = []
  for (const raw of variant) {
    if (!allowed.has(raw.subject)) continue
    const minutes = Math.max(MIN_TASK_MINUTES, Math.min(MAX_TASK_MINUTES, Math.round(raw.minutes)))
    let title = clipTitle(raw.title)
    if (!title) continue
    const round = roundName(cycle)
    if (round && !title.startsWith('【')) title = clipTitle(`【${round}】${title}`)
    const key = `${raw.weekday}|${raw.subject}|${title}`
    if (used.has(key)) {
      const topics = topicsForSubject(raw.subject, stage.phase)
      const fallback = topics.length ? topics[(used.size + raw.weekday) % topics.length] : `${stage.name}第${cycle + 1}轮`
      title = clipTitle(`${fallback} · ${raw.title}`)
      if (used.has(`${raw.weekday}|${raw.subject}|${title}`)) {
        title = clipTitle(`${title} · ${tasks.length + 1}`)
      }
    }
    used.add(`${raw.weekday}|${raw.subject}|${title}`)
    tasks.push({ weekday: raw.weekday, subject: raw.subject, title, minutes })
  }
  return tasks
}

/** 没有细化轮产物时的确定性兜底:按科目配额与知识点顺序铺一周,不依赖模型 */
function synthesizeWeek(
  stage: SpecStage,
  subjects: SpecSubject[],
  brief: PlanBrief,
  week: { index: number; startDate: string; endDate: string },
): SpecTask[] {
  const mix = stage.subjectMix
  const pool: string[] = []
  for (const item of mix) {
    const count = Math.max(1, Math.round(item.share * 10))
    for (let i = 0; i < count; i++) pool.push(item.subject)
  }
  if (pool.length === 0) return []
  const tasks: SpecTask[] = []
  let seed = week.index * 3
  const used = new Set<string>()
  for (let day = week.startDate; day <= week.endDate; day = addIsoDays(day, 1)) {
    const date = fromIso(day)
    const capacity = netAvailableMinutes(brief, date)
    if (capacity < 20) continue
    const weekday = isoWeekday(date)
    const target = Math.round(capacity * fillRatio(stage.phase))
    const count = Math.max(1, Math.min(5, Math.round(target / 50)))
    const minutes = Math.max(20, Math.min(60, Math.floor(target / count / 5) * 5))
    for (let i = 0; i < count; i++) {
      const subject = pool[(seed + i) % pool.length]
      const topics = topicsForSubject(subject, stage.phase)
      const actions = actionsForSubject(subject, stage.phase)
      const topic = topics.length ? topics[(week.index * 4 + seed + i) % topics.length] : '本周主线'
      const action = actions.length ? actions[(week.index + i) % actions.length].replace('{topic}', topic) : topic
      let title = clipTitle(action)
      const key = `${weekday}|${subject}|${title}`
      if (used.has(key)) title = clipTitle(`${title} · ${i + 1}`)
      used.add(`${weekday}|${subject}|${title}`)
      tasks.push({ weekday, subject, title, minutes })
    }
    seed += count
  }
  return tasks
}

function buildStageWeeks(
  stage: SpecStage,
  subjects: SpecSubject[],
  brief: PlanBrief,
  variants: AiWeekVariant[],
  start: Date,
  end: Date,
): SpecWeek[] {
  const totalDays = diffDays(end, start) + 1
  const weekCount = Math.max(1, Math.ceil(totalDays / 7))
  const weeks: SpecWeek[] = []
  const used = new Set<string>()
  for (let index = 0; index < weekCount; index++) {
    const weekStart = iso(addDays(start, index * 7))
    const weekEnd = iso(addDays(start, Math.min(index * 7 + 6, totalDays - 1)))
    const target = weekTarget(brief, weekStart, weekEnd, stage.phase)
    const cycle = variants.length ? Math.floor(index / variants.length) : 0
    const variant = variants.length ? variants[index % variants.length] : []
    let tasks = variant.length ? transformVariant(variant, cycle, stage, subjects, used) : []
    if (tasks.length === 0) {
      tasks = synthesizeWeek(stage, subjects, brief, { index, startDate: weekStart, endDate: weekEnd })
    }
    const phaseFocus = playbookForSubject(stage.subjectMix[0]?.subject ?? '').phases[stage.phase].focus
    weeks.push({
      index,
      startDate: weekStart,
      endDate: weekEnd,
      focus: cycle > 0
        ? `${stage.strategy || phaseFocus} · 第${cycle + 1}轮`
        : (stage.strategy || phaseFocus),
      targetMinutes: target,
      tasks,
    })
  }
  return weeks
}

/**
 * 逐科预算上限:总和钉在 target×105% 内,且每科不低于自己的目标分钟。
 * 直接把每科压到 upperMinutes 会因「+30 分钟余量 × 科目数」冲破全局上限,
 * 所以按科目顺序分配:先保证后面科目的目标,剩余额度才给当前科目做上浮。
 */
function budgetCaps(budget: PlanBudget): Map<string, number> {
  const totalCap = Math.floor(budget.targetMinutes * 1.05)
  let remaining = totalCap
  const caps = new Map<string, number>()
  for (const [index, subject] of budget.subjects.entries()) {
    const rest = budget.subjects.slice(index + 1).reduce((sum, item) => sum + item.budgetMinutes, 0)
    // 逐科只留 15 分钟取整余量:余量再大就会挤占其它科目的目标,
    // 表现为一科排超、另一科永远补不满(政治 551/1382 就是这么来的)
    const cap = Math.max(subject.budgetMinutes, Math.min(subject.upperMinutes, subject.budgetMinutes + 15, remaining - rest))
    caps.set(subject.name, cap)
    remaining -= cap
  }
  return caps
}

function subjectTaskMinutes(week: SpecWeek, subject: string): number {
  return week.tasks.filter(task => task.subject === subject).reduce((sum, task) => sum + task.minutes, 0)
}

/**
 * 预算裁剪:把每科任务总量压进上限。按周等比缩到 5 分钟粒度(最低 15 分钟),
 * 缩完仍超的周从末尾删任务 —— 每周都分到自己的那一份,计划才不会「前紧后空」。
 * 最后把每周目标改成实际任务分钟数,细化轮拿到的就是预算口径而不是满容量口径。
 */
function applyBudgetCaps(stages: SpecStage[], subjects: SpecSubject[], budget: PlanBudget): void {
  const caps = budgetCaps(budget)
  const originalTargets = new Map<SpecWeek, number>()
  for (const stage of stages) {
    for (const week of stage.weeks) originalTargets.set(week, week.targetMinutes)
  }

  for (const subject of subjects) {
    const cap = caps.get(subject.name) ?? 0
    const weeks = stages.flatMap(stage => stage.weeks).filter(week => subjectTaskMinutes(week, subject.name) > 0)
    const total = weeks.reduce((sum, week) => sum + subjectTaskMinutes(week, subject.name), 0)
    if (total <= cap) continue
    const scale = total > 0 ? cap / total : 0
    for (const week of weeks) {
      const tasks = week.tasks.filter(task => task.subject === subject.name)
      const weekTotal = tasks.reduce((sum, task) => sum + task.minutes, 0)
      const weekCap = Math.max(MIN_TASK_MINUTES, Math.floor((weekTotal * scale) / 5) * 5)
      for (const task of tasks) {
        task.minutes = Math.max(MIN_TASK_MINUTES, Math.round(task.minutes * scale / 5) * 5)
      }
      let sum = tasks.reduce((totalMinutes, task) => totalMinutes + task.minutes, 0)
      for (let index = tasks.length - 1; index >= 1 && sum > weekCap; index--) {
        sum -= tasks[index].minutes
        const position = week.tasks.indexOf(tasks[index])
        if (position >= 0) week.tasks.splice(position, 1)
      }
    }
  }

  for (const stage of stages) {
    for (const week of stage.weeks) {
      const actual = week.tasks.reduce((sum, task) => sum + task.minutes, 0)
      week.targetMinutes = Math.min(originalTargets.get(week) ?? 0, actual)
    }
  }
}

export function buildPlanSpec(input: BuildPlanSpecInput): PlanSpec {
  const subjects = normalizeSubjectList(input)
  const stages: SpecStage[] = input.stages.map((stage, index) => {
    const phase = phaseOfStage(index, input.stages.length)
    const specStage: SpecStage = {
      name: stage.name,
      strategy: stage.strategy?.trim() || playbookForSubject(subjects[0]?.name ?? '').phases[phase].focus,
      milestones: stage.milestones?.length ? stage.milestones : [],
      startDate: iso(stage.startDate),
      endDate: iso(stage.endDate),
      sortOrder: stage.sortOrder,
      phase,
      subjectMix: subjectMixFor(subjects, phase),
      weeks: [],
    }
    specStage.weeks = buildStageWeeks(specStage, subjects, input.brief, stage.weekVariants ?? [], stage.startDate, stage.endDate)
    return specStage
  })
  if (input.budget) applyBudgetCaps(stages, subjects, input.budget)
  return {
    version: 1,
    title: input.title,
    targetType: input.targetType,
    startDate: iso(input.startDate),
    examDate: iso(input.examDate),
    brief: input.brief,
    budget: input.budget ? budgetView(input.budget) : null,
    dayCaps: input.budget ? Object.fromEntries(input.budget.dayCap) : {},
    subjects,
    stages,
    dayTemplates: DAY_TEMPLATES.map(template => ({ ...template, blocks: template.blocks.map(block => ({ ...block })) })),
    rules: [...PLAN_RULES],
    generatedAt: new Date().toISOString(),
  }
}

// ---------- 确定性编译器:spec → 每日计划项 ----------

function clockMinutes(time: string): number {
  return Number(time.slice(0, 2)) * 60 + Number(time.slice(3))
}

function formatMinutes(minutes: number): string {
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`
}

function slotName(startMinutes: number): string {
  const hour = Math.floor(startMinutes / 60)
  return hour < 12 ? 'morning' : hour < 18 ? 'afternoon' : 'evening'
}

type OpenSlot = { start: number; end: number; used: number }

/**
 * 选任务要落进的窗口:优先选已用比例最低的窗口,让任务在上午/下午/晚上之间摊开,
 * 而不是像旧实现那样先把第一个窗口塞满(4 条任务全挤进 08:30-11:30)。
 * 能整条放下的窗口优先;都放不下时才挑剩余最多的窗口截短,避免把任务拆碎。
 */
function pickSlot(slots: OpenSlot[], taskMinutes: number): OpenSlot | null {
  const open = slots.filter(slot => slot.end - slot.start - slot.used >= MIN_TASK_MINUTES)
  if (open.length === 0) return null
  const fitting = open.filter(slot => slot.end - slot.start - slot.used >= taskMinutes)
  const pool = fitting.length > 0 ? fitting : open
  return pool.reduce((best, slot) => {
    if (fitting.length === 0) {
      return slot.end - slot.start - slot.used > best.end - best.start - best.used ? slot : best
    }
    const ratio = slot.used / (slot.end - slot.start)
    const bestRatio = best.used / (best.end - best.start)
    return ratio < bestRatio - 1e-9 ? slot : best
  })
}

/**
 * 把一天的周任务填进真实空闲窗口:
 * - 任务按「先难后易」排序(分钟数降序);
 * - 按窗口容量比例分摊,任务不会全部堆在上午;
 * - 单条任务不跨固定占用拆成两半:窗口放不下就按窗口剩余缩短,短于 15 分钟则换窗口;
 * - 每条计划项带上开始时刻与时段,客户端按真实作息分组展示。
 */
function compileDay(
  spec: PlanSpec,
  stage: SpecStage,
  week: SpecWeek,
  date: Date,
): GeneratedItem[] {
  const day = iso(date)
  const capacity = netAvailableMinutes(spec.brief, date)
  const dayCap = spec.dayCaps[day]
  const budgetMinutes = dayCap === undefined ? capacity : Math.min(capacity, dayCap)
  if (budgetMinutes < MIN_TASK_MINUTES) return []
  const weekday = isoWeekday(date)
  const tasks = week.tasks
    .filter(task => task.weekday === weekday && task.minutes >= MIN_TASK_MINUTES)
    .sort((a, b) => b.minutes - a.minutes)
    .slice(0, MAX_TASKS_PER_DAY)
  if (tasks.length === 0) return []
  const slots: OpenSlot[] = freeSlots(spec.brief, date)
    .map(slot => ({ start: clockMinutes(slot.start), end: clockMinutes(slot.end), used: 0 }))
    .filter(slot => slot.end - slot.start >= MIN_TASK_MINUTES)
  if (slots.length === 0) return []

  const picked: GeneratedItem[] = []
  let budget = budgetMinutes
  for (const task of tasks) {
    if (budget < MIN_TASK_MINUTES) break
    const slot = pickSlot(slots, task.minutes)
    if (!slot) break
    const remaining = slot.end - slot.start - slot.used
    const minutes = Math.min(task.minutes, remaining, budget)
    if (minutes < MIN_TASK_MINUTES) break
    const startMinutes = slot.start + slot.used
    slot.used += minutes
    budget -= minutes
    picked.push({
      stageOrder: stage.sortOrder,
      subject: task.subject,
      title: task.title,
      planDate: fromIso(day),
      minutes,
      priority: stage.phase === 'foundation' ? 0 : stage.phase === 'strengthen' ? 1 : 2,
      sortOrder: 0,
      slot: slotName(startMinutes),
      startTime: formatMinutes(startMinutes),
    })
  }
  // 选窗口的顺序按任务时长,展示顺序按钟表时间
  return picked
    .sort((a, b) => clockMinutes(a.startTime!) - clockMinutes(b.startTime!))
    .map((item, index) => ({ ...item, sortOrder: index }))
}

export function compilePlanSpec(spec: PlanSpec): GeneratedPlan {
  const stages: GeneratedStage[] = spec.stages.map(stage => ({
    name: stage.name,
    strategy: stage.strategy,
    milestones: stage.milestones,
    startDate: fromIso(stage.startDate),
    endDate: fromIso(stage.endDate),
    sortOrder: stage.sortOrder,
  }))
  const items: GeneratedItem[] = []
  for (const stage of spec.stages) {
    for (const week of stage.weeks) {
      for (let day = week.startDate; day <= week.endDate; day = addIsoDays(day, 1)) {
        items.push(...compileDay(spec, stage, week, fromIso(day)))
      }
    }
  }
  return { stages, items }
}

// ---------- 确定性校验器 ----------

export type SpecIssueCode =
  | 'STAGE_RANGE'
  | 'UNKNOWN_SUBJECT'
  | 'DUPLICATE_TITLE'
  | 'DAILY_OVERFLOW'
  | 'DAILY_OVER_BUDGET'
  | 'TASK_TOO_LONG'
  | 'THIN_DAY'
  | 'MISSING_SUBJECT'
  | 'SUBJECT_MIX'
  | 'SUBJECT_OVER_BUDGET'
  | 'SUBJECT_UNDER_BUDGET'
  | 'TOTAL_OVER_BUDGET'
  | 'WEEK_PATTERN'
  | 'SYLLABUS_THIN'
  | 'MILESTONE_GAP'

export type SpecIssue = {
  code: SpecIssueCode
  severity: 'error' | 'warn'
  message: string
  hint: string
  stageIndex?: number
  weekIndex?: number
  weekday?: number
  subject?: string
  date?: string
}

export type SpecVerification = { ok: boolean; issues: SpecIssue[] }

function dateOf(value: Date): string {
  return dayStart(value).toISOString().slice(0, 10)
}

export function verifyPlanSpec(spec: PlanSpec, plan: GeneratedPlan): SpecVerification {
  const issues: SpecIssue[] = []
  const subjectNames = new Set(spec.subjects.map(subject => subject.name))
  const subjectByName = new Map(spec.subjects.map(subject => [subject.name, subject]))
  const budgetRemaining = spec.budget
    ? spec.subjects.reduce((sum, subject) => {
        const scheduled = plan.items
          .filter(item => item.subject === subject.name)
          .reduce((total, item) => total + item.minutes, 0)
        return sum + Math.max(0, subject.budgetMinutes - scheduled)
      }, 0)
    : 0

  // 阶段必须首尾相接,首日=开始日,末日=考前一天
  for (let index = 0; index < spec.stages.length; index++) {
    const stage = spec.stages[index]
    if (index === 0 && stage.startDate !== spec.startDate) {
      issues.push({ code: 'STAGE_RANGE', severity: 'error', stageIndex: index, message: `第一阶段从 ${stage.startDate} 开始,应为 ${spec.startDate}`, hint: '把第一阶段起点改成今天' })
    }
    if (index > 0 && stage.startDate !== addIsoDays(spec.stages[index - 1].endDate, 1)) {
      issues.push({ code: 'STAGE_RANGE', severity: 'error', stageIndex: index, message: `第 ${index + 1} 阶段与上一阶段之间有空隙/重叠`, hint: '阶段日期必须首尾相接' })
    }
    if (index === spec.stages.length - 1) {
      const expected = addIsoDays(spec.examDate, -1)
      if (stage.endDate !== expected) {
        issues.push({ code: 'STAGE_RANGE', severity: 'error', stageIndex: index, message: `最后阶段结束于 ${stage.endDate},应为 ${expected}`, hint: `把最后阶段结束日改成考前一天 ${expected}` })
      }
    }
  }

  // 日期 → 容量与任务
  const dayItems = new Map<string, GeneratedItem[]>()
  for (const item of plan.items) {
    const day = dateOf(item.planDate)
    const list = dayItems.get(day) ?? []
    list.push(item)
    dayItems.set(day, list)
    if (!subjectNames.has(item.subject)) {
      issues.push({ code: 'UNKNOWN_SUBJECT', severity: 'error', stageIndex: item.stageOrder, date: day, message: `${day} 出现未确认的考试科目「${item.subject}」`, hint: '删除或改成已确认科目的任务' })
    }
    if (item.minutes > MAX_TASK_MINUTES) {
      issues.push({ code: 'TASK_TOO_LONG', severity: 'warn', stageIndex: item.stageOrder, date: day, subject: item.subject, message: `${day}「${item.title}」单条 ${item.minutes} 分钟,超过 ${MAX_TASK_MINUTES} 分钟`, hint: '拆成两条不同的子任务' })
    }
  }

  for (const [day, items] of dayItems) {
    const seen = new Set<string>()
    for (const item of items) {
      const key = `${item.subject}|${item.title}`
      if (seen.has(key)) {
        issues.push({ code: 'DUPLICATE_TITLE', severity: 'error', stageIndex: item.stageOrder, date: day, subject: item.subject, message: `${day} 同科目重复任务「${item.title}」`, hint: '给重复任务换一个知识点或合并' })
      }
      seen.add(key)
    }
    const total = items.reduce((sum, item) => sum + item.minutes, 0)
    const capacity = netAvailableMinutes(spec.brief, fromIso(day))
    if (total > capacity + 1) {
      issues.push({ code: 'DAILY_OVERFLOW', severity: 'error', stageIndex: items[0].stageOrder, date: day, message: `${day} 排了 ${total} 分钟,超过净空闲 ${capacity} 分钟`, hint: '删掉或缩短当天最后一条任务' })
    }
    const dayCap = spec.dayCaps[day]
    if (dayCap !== undefined && total > dayCap + 1) {
      issues.push({ code: 'DAILY_OVER_BUDGET', severity: 'error', stageIndex: items[0].stageOrder, date: day, message: `${day} 排了 ${total} 分钟,超过预算上限 ${dayCap} 分钟`, hint: '把当天的任务压缩或顺延到后面的日子' })
    }
    if (capacity >= 90 && total < capacity * 0.45 && (!spec.budget || budgetRemaining >= 60)) {
      const stage = spec.stages[items[0]?.stageOrder ?? 0]
      const week = stage?.weeks.find(week => week.startDate <= day && day <= week.endDate)
      issues.push({
        code: 'THIN_DAY', severity: 'warn', stageIndex: items[0]?.stageOrder, weekIndex: week?.index,
        weekday: isoWeekday(fromIso(day)), date: day,
        message: `${day} 有 ${capacity} 分钟空闲,只排了 ${total} 分钟`,
        hint: '给这天补一个同科目知识点任务,把空闲用起来',
      })
    }
  }

  // 阶段内的科目覆盖与配额
  for (const [stageIndex, stage] of spec.stages.entries()) {
    const stageItems = plan.items.filter(item => item.stageOrder === stage.sortOrder)
    const target = stage.weeks.reduce((sum, week) => sum + week.targetMinutes, 0)
    const actualMinutes = new Map<string, number>()
    for (const item of stageItems) actualMinutes.set(item.subject, (actualMinutes.get(item.subject) ?? 0) + item.minutes)
    for (const mix of stage.subjectMix) {
      if (spec.budget && (subjectByName.get(mix.subject)?.budgetMinutes ?? 0) < 120) continue
      const targetMinutes = target * mix.share
      const actual = actualMinutes.get(mix.subject) ?? 0
      if (targetMinutes >= 120 && actual === 0) {
        issues.push({ code: 'MISSING_SUBJECT', severity: 'warn', stageIndex, subject: mix.subject, message: `阶段「${stage.name}」完全没有安排「${mix.subject}」`, hint: `在本阶段每周补 1–2 条 ${mix.subject} 任务` })
      } else if (targetMinutes >= 180 && (actual < targetMinutes * 0.4 || actual > targetMinutes * 1.7)) {
        issues.push({ code: 'SUBJECT_MIX', severity: 'warn', stageIndex, subject: mix.subject, message: `阶段「${stage.name}」的 ${mix.subject} 投入 ${actual} 分钟,期望约 ${Math.round(targetMinutes)} 分钟`, hint: `调整本阶段 ${mix.subject} 的周任务条数` })
      }
    }
    // 相邻周任务集合完全相同 = 模型在原样重放,长阶段会变成几十周重复
    const signatures = stage.weeks.map(week => week.tasks.map(task => `${task.weekday}|${task.subject}|${task.title}`).sort().join(';'))
    const firstSeen = new Map<string, number>()
    signatures.forEach((signature, weekIndex) => {
      if (!signature) return
      const first = firstSeen.get(signature)
      if (first === undefined) firstSeen.set(signature, weekIndex)
      else {
        issues.push({ code: 'WEEK_PATTERN', severity: 'warn', stageIndex, weekIndex, message: `阶段「${stage.name}」第 ${weekIndex + 1} 周与第 ${first + 1} 周任务完全相同`, hint: '把这一周的任务换成下一轮知识点,标题加「二刷/三刷」并提高难度' })
      }
    })
  }

  // 预算闸:逐科不超上限,总排量不超目标的 105%
  if (spec.budget) {
    for (const subject of spec.subjects) {
      const scheduled = plan.items
        .filter(item => item.subject === subject.name)
        .reduce((sum, item) => sum + item.minutes, 0)
      if (scheduled > subject.upperMinutes + 1) {
        issues.push({ code: 'SUBJECT_OVER_BUDGET', severity: 'error', subject: subject.name, message: `${subject.name} 排了 ${scheduled} 分钟,超过预算上限 ${subject.upperMinutes} 分钟`, hint: `把 ${subject.name} 的任务压缩到 ${subject.upperMinutes} 分钟以内` })
      }
      // 与 service 的落库闸同口径:允许一个 15 分钟取整档位,超过就必须补
      if (scheduled + 15 < subject.budgetMinutes) {
        issues.push({ code: 'SUBJECT_UNDER_BUDGET', severity: 'error', subject: subject.name, message: `${subject.name} 只排了 ${scheduled} 分钟,低于目标 ${subject.budgetMinutes} 分钟`, hint: `在还有空容量的日子给 ${subject.name} 补 ${subject.budgetMinutes - scheduled} 分钟任务` })
      }
    }
    const totalScheduled = plan.items
      .filter(item => item.subject !== REVIEW_SUBJECT)
      .reduce((sum, item) => sum + item.minutes, 0)
    const totalCap = Math.floor(spec.budget.targetMinutes * 1.05)
    if (totalScheduled > totalCap) {
      issues.push({ code: 'TOTAL_OVER_BUDGET', severity: 'error', message: `总排量 ${totalScheduled} 分钟超过预算上限 ${totalCap} 分钟`, hint: '按科目目标等比压缩任务总量' })
    }
  }

  // 科目标题多样性:长期只有两三个标题说明任务没有推进
  const titlesBySubject = new Map<string, Set<string>>()
  for (const item of plan.items) {
    const set = titlesBySubject.get(item.subject) ?? new Set<string>()
    set.add(item.title)
    titlesBySubject.set(item.subject, set)
  }
  const horizonDays = diffDays(fromIso(spec.examDate), fromIso(spec.startDate))
  for (const subject of spec.subjects) {
    if (horizonDays < 30 || subject.remainingMinutes <= 0) continue
    const distinct = titlesBySubject.get(subject.name)?.size ?? 0
    const topics = topicsForSubject(subject.name, 'strengthen')
    const needed = Math.min(6, Math.max(3, Math.floor(topics.length / 2)))
    if (distinct < needed) {
      issues.push({ code: 'SYLLABUS_THIN', severity: 'warn', subject: subject.name, message: `${subject.name} 全程只有 ${distinct} 种不同任务,内容偏薄`, hint: `按 ${subject.name} 的知识点序列补充更多不同标题` })
    }
  }

  // 逐科里程碑:截止前必须排够考生确认的目标分钟数(预算版按缩放后的里程碑目标)
  for (const subject of spec.subjects) {
    const milestoneTarget = spec.budget ? subject.milestoneBudgetMinutes : subject.milestoneMinutes
    if (subject.estimated || milestoneTarget <= 0) continue
    const before = plan.items
      .filter(item => item.subject === subject.name && dateOf(item.planDate) <= subject.milestoneDate)
      .reduce((sum, item) => sum + item.minutes, 0)
    if (before < milestoneTarget) {
      issues.push({
        code: 'MILESTONE_GAP', severity: 'error', subject: subject.name, date: subject.milestoneDate,
        message: `${subject.name}「${subject.milestone}」在 ${subject.milestoneDate} 前只排了 ${before} 分钟,还差 ${milestoneTarget - before} 分钟`,
        hint: `在 ${subject.milestoneDate} 之前给 ${subject.name} 增加任务,优先安排该科目的核心知识点`,
      })
    }
  }

  const ok = !issues.some(issue => issue.severity === 'error')
  return { ok, issues }
}

// ---------- 定向修复:只改失败的那一小块 ----------

function cloneSpec(spec: PlanSpec): PlanSpec {
  return JSON.parse(JSON.stringify(spec)) as PlanSpec
}

function plannerTask(subject: string, phase: SpecPhase, seed: number, weekday: number, minutes = 45): SpecTask | null {
  const topics = topicsForSubject(subject, phase)
  const actions = actionsForSubject(subject, phase)
  if (topics.length === 0 || actions.length === 0) return null
  const topic = topics[seed % topics.length]
  const action = actions[seed % actions.length].replace('{topic}', topic)
  const title = clipTitle(action)
  return { weekday, subject, title, minutes }
}

/** 本周容量最大的星期:补任务时优先塞进真正有空的那天 */
function bestWeekdays(spec: PlanSpec, week: SpecWeek): number[] {
  const days: Array<{ weekday: number; capacity: number }> = []
  for (let day = week.startDate; day <= week.endDate; day = addIsoDays(day, 1)) {
    days.push({ weekday: isoWeekday(fromIso(day)), capacity: netAvailableMinutes(spec.brief, fromIso(day)) })
  }
  return days.sort((a, b) => b.capacity - a.capacity).map(item => item.weekday)
}

function appendTask(spec: PlanSpec, stageIndex: number, weekIndex: number, subject: string, weekday?: number): boolean {
  const stage = spec.stages[stageIndex]
  const week = stage?.weeks[weekIndex]
  if (!stage || !week) return false
  const days = bestWeekdays(spec, week)
  const targetWeekday = weekday && days.includes(weekday) ? weekday : days[0]
  if (!targetWeekday) return false
  const capacities = new Map<number, number>()
  for (let day = week.startDate; day <= week.endDate; day = addIsoDays(day, 1)) {
    capacities.set(isoWeekday(fromIso(day)), netAvailableMinutes(spec.brief, fromIso(day)))
  }
  const existing = week.tasks
    .filter(task => task.weekday === targetWeekday && task.subject === subject)
    .reduce((sum, task) => sum + task.minutes, 0)
  if (existing + MIN_TASK_MINUTES > (capacities.get(targetWeekday) ?? 0)) return false
  const seed = week.tasks.length + weekIndex * 7 + stageIndex
  let minutes = Math.min(45, Math.max(20, Math.floor((capacities.get(targetWeekday) ?? 45) / 3 / 5) * 5))
  const subjectSpec = spec.subjects.find(item => item.name === subject)
  if (spec.budget && subjectSpec) {
    const scheduled = spec.stages
      .flatMap(item => item.weeks)
      .reduce((sum, item) => sum + subjectTaskMinutes(item, subject), 0)
    const remaining = subjectSpec.budgetMinutes - scheduled
    if (remaining < MIN_TASK_MINUTES) return false
    minutes = Math.min(minutes, Math.floor(remaining / 5) * 5)
  }
  const task = plannerTask(subject, stage.phase, seed, targetWeekday, minutes)
  if (!task || week.tasks.some(item => item.weekday === task.weekday && item.subject === task.subject && item.title === task.title)) return false
  week.tasks.push(task)
  return true
}

/**
 * 逐科预算补足:细化轮/骨架轮漏掉的分钟数,在这里按「编译后的真实排量」
 * 把缺口补进仍有余量的日期。修复只增不减,且优先填里程碑截止前的日子。
 */
function fillBudgetDeficits(spec: PlanSpec, actions: string[]): void {
  if (!spec.budget) return
  // 补一轮后编译器可能因窗口碎片少放几条,再跑一轮兜住取整误差;两轮足够收敛
  for (let pass = 0; pass < 2; pass++) {
    const plan = compilePlanSpec(spec)
    const scheduled = new Map<string, number>()
    const dayUsage = new Map<string, { minutes: number; count: number }>()
    for (const item of plan.items) {
      scheduled.set(item.subject, (scheduled.get(item.subject) ?? 0) + item.minutes)
      const day = dateOf(item.planDate)
      const usage = dayUsage.get(day) ?? { minutes: 0, count: 0 }
      usage.minutes += item.minutes
      usage.count += 1
      dayUsage.set(day, usage)
    }
    const deficits = spec.subjects.filter(subject =>
      subject.budgetMinutes - (scheduled.get(subject.name) ?? 0) >= MIN_TASK_MINUTES)
    if (deficits.length === 0) return

    let changed = false
    for (const subject of deficits) {
      let deficit = subject.budgetMinutes - (scheduled.get(subject.name) ?? 0)
      const milestoneDeadline = !subject.estimated && subject.milestoneBudgetMinutes > 0
        ? subject.milestoneDate
        : ''
      let milestoneDeficit = milestoneDeadline
        ? Math.max(0, subject.milestoneBudgetMinutes - plan.items
          .filter(item => item.subject === subject.name && dateOf(item.planDate) <= milestoneDeadline)
          .reduce((sum, item) => sum + item.minutes, 0))
        : 0

      const candidates: Array<{ stageIndex: number; weekIndex: number; weekday: number; date: string; early: boolean }> = []
      for (const [stageIndex, stage] of spec.stages.entries()) {
        for (const week of stage.weeks) {
          for (let day = week.startDate; day <= week.endDate; day = addIsoDays(day, 1)) {
            candidates.push({
              stageIndex, weekIndex: week.index, weekday: isoWeekday(fromIso(day)), date: day,
              early: milestoneDeadline ? day <= milestoneDeadline : true,
            })
          }
        }
      }
      candidates.sort((a, b) => a.date < b.date ? -1 : a.date > b.date ? 1 : 0)
      const ordered = milestoneDeficit > 0
        ? [...candidates.filter(item => item.early), ...candidates.filter(item => !item.early)]
        : candidates

      let added = 0
      for (const candidate of ordered) {
        if (deficit < MIN_TASK_MINUTES) break
        // 截止前的容量用尽后,别为了里程碑卡住总量补足;里程碑缺口继续留在校验里上报
        if (milestoneDeficit > 0 && !candidate.early) milestoneDeficit = 0
        const stage = spec.stages[candidate.stageIndex]
        const week = stage?.weeks[candidate.weekIndex]
        if (!stage || !week) continue
        const dayCap = spec.dayCaps[candidate.date] ?? netAvailableMinutes(spec.brief, fromIso(candidate.date))
        const usage = dayUsage.get(candidate.date) ?? { minutes: 0, count: 0 }
        if (usage.count >= MAX_TASKS_PER_DAY) continue
        const free = dayCap - usage.minutes
        // 差几分钟凑不满一条新任务时,把余量加到当天该科已有任务上,
        // 否则每天 3-4 分钟的零头会白白浪费,总量就永远差最后一截
        if (free > 0 && free < MIN_TASK_MINUTES) {
          const extendable = week.tasks.find(item => item.weekday === candidate.weekday
            && item.subject === subject.name && item.minutes + free <= MAX_TASK_MINUTES)
          if (extendable) {
            extendable.minutes += free
            usage.minutes += free
            dayUsage.set(candidate.date, usage)
            deficit -= free
            if (candidate.early) milestoneDeficit = Math.max(0, milestoneDeficit - free)
            added += free
            changed = true
          }
          continue
        }
        if (free < MIN_TASK_MINUTES) continue
        const minutes = Math.min(45, Math.floor(deficit / 5) * 5, Math.floor(free / 5) * 5)
        if (minutes < MIN_TASK_MINUTES) continue
        const seedBase = week.tasks.length + week.index * 11 + candidate.stageIndex * 7
        let task: SpecTask | null = null
        for (let attempt = 0; attempt < 24; attempt++) {
          const candidateTask = plannerTask(subject.name, stage.phase, seedBase + attempt, candidate.weekday, minutes)
          if (!candidateTask) break
          const clash = week.tasks.some(item => item.weekday === candidateTask.weekday
            && item.subject === candidateTask.subject && item.title === candidateTask.title)
          if (!clash) {
            task = candidateTask
            break
          }
        }
        if (!task) continue
        week.tasks.push(task)
        usage.minutes += task.minutes
        usage.count += 1
        dayUsage.set(candidate.date, usage)
        deficit -= task.minutes
        if (candidate.early) milestoneDeficit = Math.max(0, milestoneDeficit - task.minutes)
        added += task.minutes
        changed = true
      }
      if (added > 0) actions.push(`给 ${subject.name} 补 ${added} 分钟任务(目标 ${subject.budgetMinutes} 分钟)`)
    }
    if (!changed) return
  }
}

/**
 * 按校验结果做确定性修复。返回新的 spec 与修复动作;
 * 修不掉的(容量真不够、里程碑确实盖不住)继续留在 issues 里上报。
 */
export function repairPlanSpec(spec: PlanSpec, verification: SpecVerification): { spec: PlanSpec; actions: string[] } {
  const next = cloneSpec(spec)
  const actions: string[] = []
  const addAction = (text: string) => { if (!actions.includes(text)) actions.push(text) }
  const allowed = new Set(next.subjects.map(subject => subject.name))

  // 未确认科目、重复标题、超长任务:就地清洗
  for (const stage of next.stages) {
    for (const week of stage.weeks) {
      week.tasks = week.tasks.filter(task => allowed.has(task.subject))
      const seen = new Set<string>()
      for (const task of week.tasks) {
        const key = `${task.weekday}|${task.subject}|${task.title}`
        if (seen.has(key)) {
          const topics = topicsForSubject(task.subject, stage.phase)
          const topic = topics[week.index % Math.max(1, topics.length)] ?? '复盘'
          task.title = clipTitle(`${topic} · 第${week.index + 1}周`)
          addAction(`重命名 ${stage.name} 第 ${week.index + 1} 周的重复任务`)
        }
        seen.add(`${task.weekday}|${task.subject}|${task.title}`)
        if (task.minutes > MAX_TASK_MINUTES) {
          const half = Math.round(task.minutes / 2 / 5) * 5
          task.minutes = half
          week.tasks.push({ ...task, title: clipTitle(`${task.title}(续)`), minutes: task.minutes })
          addAction(`拆开 ${stage.name} 的超长任务「${task.title}」`)
        }
      }
    }
  }

  // 周任务原样重放:给重复周加轮次前缀,并换一个知识点
  for (const issue of verification.issues.filter(item => item.code === 'WEEK_PATTERN')) {
    const stage = next.stages[issue.stageIndex ?? -1]
    const week = stage?.weeks[issue.weekIndex ?? -1]
    if (!week) continue
    const round = roundName(issue.weekIndex ?? 1) || `第${(issue.weekIndex ?? 0) + 1}轮`
    for (const task of week.tasks) {
      task.title = clipTitle(task.title.startsWith('【') ? task.title : `【${round}】${task.title}`)
    }
    addAction(`给 ${stage.name} 第 ${(issue.weekIndex ?? 0) + 1} 周换一轮任务`)
  }

  // 缺科目 / 某天太薄:只在该阶段对应周补任务
  const handledStages = new Set<number>()
  for (const issue of verification.issues) {
    if (issue.code === 'THIN_DAY' && next.budget) continue
    if (issue.code !== 'MISSING_SUBJECT' && issue.code !== 'THIN_DAY' && issue.code !== 'SUBJECT_MIX') continue
    if (issue.stageIndex === undefined) continue
    const stage = next.stages[issue.stageIndex]
    if (!stage) continue
    const subject = issue.subject && allowed.has(issue.subject)
      ? issue.subject
      : stage.subjectMix[0]?.subject
    if (!subject) continue
    const indices = issue.code === 'THIN_DAY' && issue.weekIndex !== undefined ? [issue.weekIndex] : stage.weeks.map(week => week.index)
    for (const weekIndex of indices) {
      const count = issue.code === 'MISSING_SUBJECT' ? 2 : 1
      for (let i = 0; i < count; i++) {
        if (appendTask(next, issue.stageIndex, weekIndex, subject, issue.weekday)) {
          addAction(`在 ${stage.name} 第 ${weekIndex + 1} 周补 ${subject} 任务`)
        }
      }
    }
    handledStages.add(issue.stageIndex)
  }
  void handledStages

  // 里程碑盖不住:截止日之前的每一周都补该科任务
  for (const issue of verification.issues.filter(item => item.code === 'MILESTONE_GAP' && item.subject)) {
    const subject = issue.subject!
    for (const [stageIndex, stage] of next.stages.entries()) {
      for (const week of stage.weeks) {
        if (week.startDate > (issue.date ?? '')) continue
        if (appendTask(next, stageIndex, week.index, subject)) {
          addAction(`在 ${week.startDate} 前给 ${subject} 补任务`)
        }
      }
    }
  }

  // 逐科总量兜底:前面只补了"整段消失/某周太薄"的零头,这里按编译后的真实排量补齐
  fillBudgetDeficits(next, actions)

  return { spec: next, actions }
}

/** 校验结果 → 给细化轮/骨架轮的修复指令(只列可执行的,不把整份计划推倒) */
export function repairHintForStage(verification: SpecVerification, stageIndex: number): string {
  const items = verification.issues.filter(issue => issue.stageIndex === stageIndex)
  if (items.length === 0) return ''
  return items.slice(0, 6).map(issue => `- ${issue.message};${issue.hint}`).join('\n')
}

export function issuesSummary(verification: SpecVerification): string {
  return verification.issues.slice(0, 12).map(issue => `${issue.severity === 'error' ? '[必须修]' : '[建议]'} ${issue.message}`).join('\n')
}

/** 文档第 04 章按当天容量挑模板:与编译器使用的是同一份容量口径 */
export function templateForDay(spec: PlanSpec, day: string): DayTemplate {
  return dayTemplateFor(netAvailableMinutes(spec.brief, fromIso(day)))
}

/** 文档渲染用的明细:某阶段某周的日期 → 实际任务与时刻 */
export function weekTimetable(spec: PlanSpec, stageIndex: number, weekIndex: number): Array<{ day: string; weekday: number; capacity: number; items: GeneratedItem[] }> {
  const stage = spec.stages[stageIndex]
  const week = stage?.weeks[weekIndex]
  if (!week) return []
  const out: Array<{ day: string; weekday: number; capacity: number; items: GeneratedItem[] }> = []
  const plan = compilePlanSpec(spec)
  for (let day = week.startDate; day <= week.endDate; day = addIsoDays(day, 1)) {
    out.push({
      day,
      weekday: isoWeekday(fromIso(day)),
      capacity: netAvailableMinutes(spec.brief, fromIso(day)),
      items: plan.items.filter(item => dateOf(item.planDate) === day),
    })
  }
  return out
}

export function dayScheduleFor(spec: PlanSpec, day: string) {
  return daySchedule(spec.brief, fromIso(day))
}
