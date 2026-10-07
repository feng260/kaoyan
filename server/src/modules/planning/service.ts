import { prisma } from '../../shared/prisma'
import { ApiError } from '../../middlewares/error'
import { llmConfigured, visionConfigured } from '../../config/env'
import { dayStart, diffDays, profileInputSchema, MIN_DAILY_MINUTES, MAX_DAILY_MINUTES, type ProfileInput } from './schemas'
import type { GeneratedPlan } from './generator'
import { AiUnavailable, generateAiPlan, type AiPlanningInput } from './aiGenerator'
import { runPlanInterview, type InterviewInput, type InterviewResult } from './aiCoach'
import { parseTimetableImage, type TimetableImage, type TimetableResult } from './timetable'
import { briefIsEmpty, normalizeBrief, assessPlanningFacts, netAvailableMinutes, type PlanBrief, type PlanDocument } from './document'
import {
  affectedDays, dailyCapacity, datesBetween, diffSnapshots, planL1Shuffle, shiftDate,
  windowFingerprint, L1_MAX_AFFECTED_DAYS,
  type AdjustmentTier, type AdjustableItem, type WindowSnapshotItem,
} from './adjust'
import { buildLedger, expandL2, normalizeL2Tasks, runAdjustL2, validateL2, type L2Input, type L2Task } from './adjustL2'
import { runAdjustIntent, type AdjustIntent, type AdjustIntentInput } from './adjustIntent'

/**
 * 备考档案与计划的持久化(Phase 0)。
 *
 * 两点约定值得先说清楚:
 * 1. 所有方法都必须带上已鉴权的 userGuid,没有任何一个接口可以跨用户读计划;
 * 2. 计划是「服务端权威」资源:整份结果先在事务外算完并校验,再在一个事务里落库,
 *    失败必须整体回滚 —— 半份计划比没有计划更糟,客户端会照着它排番茄钟。
 */

type AnyArgs = any

export interface PlanningDb {
  userProfile: {
    findUnique(args: AnyArgs): Promise<any>
    upsert(args: AnyArgs): Promise<any>
    update(args: AnyArgs): Promise<any>
  }
  plan: {
    findFirst(args?: AnyArgs): Promise<any>
    findMany(args?: AnyArgs): Promise<any[]>
    create(args: AnyArgs): Promise<any>
    updateMany(args: AnyArgs): Promise<any>
  }
  planStage: {
    create(args: AnyArgs): Promise<any>
    findMany(args?: AnyArgs): Promise<any[]>
  }
  planItem: {
    createMany(args: AnyArgs): Promise<any>
    findMany(args?: AnyArgs): Promise<any[]>
    updateMany(args: AnyArgs): Promise<any>
    deleteMany(args: AnyArgs): Promise<any>
    groupBy(args: AnyArgs): Promise<any[]>
  }
  planAdjustment: {
    findFirst(args?: AnyArgs): Promise<any>
    findMany(args?: AnyArgs): Promise<any[]>
    create(args: AnyArgs): Promise<any>
    updateMany(args: AnyArgs): Promise<any>
  }
  $transaction<T>(fn: (tx: PlanningDb) => Promise<T>): Promise<T>
}

/** 已落库的计划行 → 客户端可读结构 */
export interface PublicProfile {
  targetType: string
  examDate: string
  dailyMinutes: number
  studyWindows: string[]
  foundation: string | null
  weakSubjects: string[]
  /** 备考面谈得到的考生画像简报;没聊过就是 null */
  brief: PlanBrief | null
  onboardingDoneAt: number | null
  updatedAt: number | null
}

export interface PublicPlanItem {
  id: number
  stageId: number
  subject: string
  title: string
  planDate: string
  minutes: number
  priority: number
  status: string
  sortOrder: number
  completedAt: number | null
}

export interface PublicPlan {
  id: number
  title: string
  targetType: string
  source: string
  status: string
  startDate: string
  examDate: string
  version: number
  /** 档案改过但计划还没重建:客户端应提示「重新生成」而不是直接换掉用户今天的安排 */
  stale: boolean
  /** 长文档(对标 468 天全程作战计划);生成失败时为 null,客户端回退到只显示每日清单 */
  document: PlanDocument | null
  stages: Array<{ id: number; name: string; strategy?: string; milestones?: string[]; startDate: string; endDate: string; sortOrder: number }>
  items: PublicPlanItem[]
  progress: { totalItems: number; pendingItems: number; doneItems: number; totalMinutes: number; totalDays: number }
  createdAt: number | null
  updatedAt: number | null
}

/** 调整单的对外结构;changes 由 before/after 快照重算,客户端拿去渲染变动清单 */
export interface PublicAdjustment {
  id: number
  planId: number
  tier: string
  status: string
  reason: string | null
  summary: string | null
  windowFrom: string
  windowTo: string
  changes: ReturnType<typeof diffSnapshots>
  createdAt: number | null
  appliedAt: number | null
}

/**
 * 历史列表里的计划摘要:只带计数,不带 items/stages 明细。
 * 一份 468 天的计划有上千条计划项,历史列表若逐份把明细拉回来,列表接口会重得离谱。
 */
export interface PublicPlanSummary {
  id: number
  title: string
  targetType: string
  source: string
  status: string
  startDate: string
  examDate: string
  version: number
  totalItems: number
  doneItems: number
  totalDays: number
  createdAt: number | null
  updatedAt: number | null
}

const DAY_MS = 24 * 3600_000

/** 计划项状态:与客户端与 prisma 默认值对齐 */
export const PLAN_ITEM_STATUSES = ['pending', 'done'] as const
export type PlanItemStatus = (typeof PLAN_ITEM_STATUSES)[number]

/** 按天输出,和 App 端「今天/明天」的判定口径保持一致 */
function dateOnly(value: unknown): string {
  const d = value instanceof Date ? value : new Date(Number(value))
  return Number.isNaN(d.getTime()) ? '' : dayStart(d).toISOString().slice(0, 10)
}

/** milestonesJson → 字符串数组;坏数据静默为空数组,不让一条脏 JSON 拖垮整个计划响应 */
function parseMilestones(raw: unknown): string[] {
  if (typeof raw !== 'string' || !raw.trim()) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.map(item => String(item)).filter(Boolean) : []
  } catch {
    return []
  }
}

/** DateTime / BigInt / number 三种存放形态统一转毫秒时间戳 */
function ms(value: unknown): number | null {
  if (value === null || value === undefined) return null
  if (value instanceof Date) return value.getTime()
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

/** 数据库列是 Text:能是数组就直接用,是字符串就尝试解析,坏的当空数组 */
function jsonArray(raw: unknown): string[] {
  if (Array.isArray(raw)) return raw.map(String)
  if (typeof raw !== 'string' || !raw.trim()) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

/** 数据库里存的结构化对象(briefJson / documentJson):解析失败一律当 null,不让一行坏数据把接口打挂 */
function jsonObject(raw: unknown): Record<string, any> | null {
  if (raw && typeof raw === 'object') return raw as Record<string, any>
  if (typeof raw !== 'string' || !raw.trim()) return null
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}

function toDay(value: unknown): Date {
  const d = value instanceof Date ? value : new Date(Number(value))
  return dayStart(d)
}

function profileSnapshot(row: any): string {
  return JSON.stringify({
    targetType: row.targetType, examDate: dateOnly(row.examDate), dailyMinutes: row.dailyMinutes,
    studyWindows: jsonArray(row.studyWindowsJson), foundation: row.foundation,
    weakSubjects: jsonArray(row.weakSubjectsJson), brief: jsonObject(row.briefJson),
  })
}

async function progressSnapshot(db: PlanningDb, userGuid: string): Promise<string> {
  const active = await db.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
  if (!active) return JSON.stringify({ planId: null, completed: [] })
  const items = await db.planItem.findMany({ where: { planId: active.id }, orderBy: { id: 'asc' } })
  return JSON.stringify({ planId: active.id, completed: items.filter(item => item.status === 'done').map(item => item.id) })
}

function requirePlanningFacts(brief: PlanBrief | null, start: Date, exam: Date, allowCompletedMilestones = false): void {
  const result = assessPlanningFacts(brief ?? normalizeBrief(null), start, exam, allowCompletedMilestones)
  if (result.missing.length) throw new ApiError(400, 'PLANNING_FACTS_INCOMPLETE', `生成计划前请确认:${result.missing.join('、')}`)
  if (result.deficits.length) {
    const details = result.deficits.map(d => `${d.subject}「${d.milestone}」缺口 ${d.missingMinutes} 分钟`).join('；')
    throw new ApiError(400, 'PLAN_CAPACITY_INSUFFICIENT', `考前可用 ${result.availableMinutes} 分钟,无法完成:${details}`)
  }
}

function serializeProfile(row: any): PublicProfile {
  const briefRaw = jsonObject(row.briefJson)
  return {
    targetType: row.targetType,
    examDate: dateOnly(row.examDate),
    dailyMinutes: Number(row.dailyMinutes ?? 0),
    studyWindows: jsonArray(row.studyWindowsJson),
    foundation: row.foundation ?? null,
    weakSubjects: jsonArray(row.weakSubjectsJson),
    brief: briefRaw ? normalizeBrief(briefRaw) : null,
    onboardingDoneAt: ms(row.onboardingDoneAt),
    updatedAt: ms(row.updatedAt),
  }
}

function serializePlan(row: any, stages: any[], items: any[], stale: boolean): PublicPlan {
  const done = items.filter(i => i.status === 'done').length
  return {
    id: row.id,
    title: row.title,
    targetType: row.targetType,
    source: row.source,
    status: row.status,
    startDate: dateOnly(row.startDate),
    examDate: dateOnly(row.examDate),
    version: Number(row.version ?? 1),
    stale,
    document: jsonObject(row.documentJson) as PlanDocument | null,
    stages: stages.map(s => ({
      id: s.id,
      name: s.name,
      strategy: s.strategy ?? undefined,
      milestones: parseMilestones(s.milestonesJson),
      startDate: dateOnly(s.startDate),
      endDate: dateOnly(s.endDate),
      sortOrder: Number(s.sortOrder ?? 0),
    })),
    items: items.map(i => ({
      id: i.id,
      stageId: i.stageId,
      subject: i.subject,
      title: i.title,
      planDate: dateOnly(i.planDate),
      minutes: Number(i.minutes ?? 0),
      priority: Number(i.priority ?? 0),
      status: i.status,
      sortOrder: Number(i.sortOrder ?? 0),
      completedAt: ms(i.completedAt),
    })),
    progress: {
      totalItems: items.length,
      pendingItems: items.length - done,
      doneItems: done,
      totalMinutes: items.reduce((sum, i) => sum + Number(i.minutes ?? 0), 0),
      totalDays: diffDays(toDay(row.examDate), toDay(row.startDate)),
    },
    createdAt: ms(row.createdAt),
    updatedAt: ms(row.updatedAt),
  }
}

/** 计划行 + 按状态聚合出的计数 → 历史列表用的摘要 */
function serializePlanSummary(row: any, totalItems: number, doneItems: number): PublicPlanSummary {
  return {
    id: row.id,
    title: row.title,
    targetType: row.targetType,
    source: row.source,
    status: row.status,
    startDate: dateOnly(row.startDate),
    examDate: dateOnly(row.examDate),
    version: Number(row.version ?? 1),
    totalItems,
    doneItems,
    totalDays: diffDays(toDay(row.examDate), toDay(row.startDate)),
    createdAt: ms(row.createdAt),
    updatedAt: ms(row.updatedAt),
  }
}

// ---------- 行程调整(D1):窗口快照、指纹校验与窗口同步 ----------

function toAdjustable(row: any): AdjustableItem {
  return {
    id: Number(row.id), subject: String(row.subject ?? ''), title: String(row.title ?? ''),
    planDate: dateOnly(row.planDate), minutes: Number(row.minutes ?? 0),
    priority: Number(row.priority ?? 0), sortOrder: Number(row.sortOrder ?? 0),
  }
}

function toSnapshot(items: any[]): WindowSnapshotItem[] {
  return items.map(row => ({
    id: Number(row.id), subject: String(row.subject ?? ''), title: String(row.title ?? ''),
    planDate: dateOnly(row.planDate), minutes: Number(row.minutes ?? 0), status: String(row.status ?? 'pending'),
  }))
}

function jsonList<T>(raw: unknown): T[] {
  try {
    const parsed = JSON.parse(typeof raw === 'string' ? raw : 'null')
    return Array.isArray(parsed) ? parsed as T[] : []
  } catch {
    return []
  }
}

/** 调整窗口 = [今天, 当前阶段末]:优先覆盖今天的阶段,没有则取第一个未来阶段,再没有(全部过完)报 404 */
function stageWindow(stages: any[], today: string): { from: string; to: string } {
  const sorted = [...stages].sort((a, b) => Number(a.sortOrder ?? 0) - Number(b.sortOrder ?? 0))
  const stage = sorted.find(item => dateOnly(item.startDate) <= today && today <= dateOnly(item.endDate))
    ?? sorted.find(item => dateOnly(item.startDate) > today)
  if (!stage) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划的阶段已全部结束,没有可调整的内容')
  const to = dateOnly(stage.endDate)
  if (to < today) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划的阶段已全部结束,没有可调整的内容')
  return { from: today, to }
}

function stageIdFor(stages: any[], day: string): number {
  const hit = stages.find(stage => dateOnly(stage.startDate) <= day && day <= dateOnly(stage.endDate))
  return Number(hit?.id ?? stages[0]?.id ?? 0)
}

/**
 * 把窗口内的计划项改写成目标快照:
 * - 窗口内、非 done、目标里没有的 → 删除
 * - 窗口内、非 done、字段变了 → 原地更新(注意 plan_items 没有 updatedAt 列,不写它)
 * - 目标里新增(id<=0 或库里不存在) → 插入
 * done 项与窗口外的项一律不碰。existingById 必须基于全表:
 * 目标快照里的 done 项可能落在窗口外,只查窗口内会把它们误判成「新增」。
 */
async function syncWindowItems(tx: PlanningDb, planId: number, windowFrom: string, windowTo: string,
  target: WindowSnapshotItem[], stages: any[]): Promise<void> {
  const allRows = await tx.planItem.findMany({ where: { planId } })
  const existingById = new Map<number, any>(allRows.map(row => [Number(row.id), row]))
  const targetIds = new Set(target.filter(item => item.id > 0 && existingById.has(item.id)).map(item => item.id))

  const removeIds = allRows
    .filter(row => {
      const day = dateOnly(row.planDate)
      return day >= windowFrom && day <= windowTo && row.status !== 'done' && !targetIds.has(Number(row.id))
    })
    .map(row => Number(row.id))
  if (removeIds.length > 0) {
    await tx.planItem.deleteMany({ where: { id: { in: removeIds } } })
  }

  for (const item of target) {
    const row = item.id > 0 ? existingById.get(item.id) : undefined
    if (!row || row.status === 'done') continue // 新增走 createMany;done 永不动
    const day = dateOnly(row.planDate)
    if (day < windowFrom || day > windowTo) continue
    if (day === item.planDate && Number(row.minutes) === item.minutes
      && String(row.subject) === item.subject && String(row.title) === item.title) continue
    await tx.planItem.updateMany({
      where: { id: item.id, planId, status: 'pending' },
      data: {
        planDate: new Date(`${item.planDate}T00:00:00.000Z`),
        minutes: item.minutes, subject: item.subject, title: item.title,
      },
    })
  }

  const additions = target.filter(item => item.id <= 0 || !existingById.has(item.id))
  if (additions.length > 0) {
    await tx.planItem.createMany({
      data: additions.map(item => ({
        planId,
        stageId: stageIdFor(stages, item.planDate),
        subject: item.subject,
        title: item.title,
        planDate: new Date(`${item.planDate}T00:00:00.000Z`),
        minutes: item.minutes,
        priority: 0,
        sortOrder: 0,
      })),
    })
  }
}

function serializeAdjustment(row: any): PublicAdjustment {
  const before = jsonList<WindowSnapshotItem>(row.beforeJson)
  const after = jsonList<WindowSnapshotItem>(row.afterJson)
  return {
    id: Number(row.id),
    planId: Number(row.planId),
    tier: String(row.tier ?? ''),
    status: String(row.status ?? ''),
    reason: row.reason ?? null,
    summary: row.summary ?? null,
    windowFrom: dateOnly(row.windowFrom),
    windowTo: dateOnly(row.windowTo),
    changes: diffSnapshots(before, after),
    createdAt: ms(row.createdAt),
    appliedAt: ms(row.appliedAt),
  }
}

/**
 * L2 重排:模型给相对序列 → 服务端展开校验。校验失败把原因回喂重试一次,仍失败抛
 * PLAN_GENERATION_FAILED(502)。整个过程在事务外、落库前,失败天然不产生任何数据。
 */
async function runAdjustL2Plan(ai: PlanningAi, args: {
  message: string
  pending: AdjustableItem[]
  capacity: Map<string, number>
  from: string
  to: string
  brief: PlanBrief
}): Promise<WindowSnapshotItem[]> {
  if (!ai.adjustL2) throw new ApiError(503, 'AI_NOT_CONFIGURED', '服务端还没有配置大模型,暂时无法重排计划')
  const days = datesBetween(args.from, args.to)
  if (days.length === 0) throw new ApiError(400, 'PLAN_CAPACITY_INSUFFICIENT', '调整窗口为空,没有可重排的日期')
  const milestones: Array<{ subject: string; date: string; name: string }> = []
  for (const subject of args.brief.examSubjects) {
    if (subject.milestone && subject.milestoneDate) {
      milestones.push({ subject: subject.name, date: subject.milestoneDate, name: subject.milestone })
    }
  }
  const ledger = buildLedger(
    args.pending, args.capacity, days,
    args.brief.examSubjects.map(subject => subject.name),
    milestones,
  )
  const run = (message: string) => ai.adjustL2!({
    message, windowFrom: args.from, windowTo: args.to, ledger, pending: args.pending,
  }).then(tasks => normalizeL2Tasks(tasks, ledger))

  let tasks = await run(args.message)
  let target = expandL2(tasks, args.from, args.pending)
  let verdict = validateL2(target, ledger)
  if (!verdict.ok) {
    // 回喂一次:把失败原因告诉模型,多数情况一次就能收敛
    tasks = await run(`${args.message}\n(上一次重排没通过:${verdict.reason};请修正后重新输出完整方案)`)
    target = expandL2(tasks, args.from, args.pending)
    verdict = validateL2(target, ledger)
  }
  if (!verdict.ok) {
    throw new ApiError(502, 'PLAN_GENERATION_FAILED', `重排方案没有通过校验:${verdict.reason}`)
  }
  return target
}

/** 从库里读出的档案行还原成 ProfileInput;档案不可用时返回 null —— 面谈没有档案也能进行 */
function profileInputFromRow(row: any): ProfileInput | null {
  // 每日时长早已不是排计划依据,问卷也不再收它;老行/新行里常常是 0。
  // 直接塞 0 会被 schema 的 min 卡掉,整个档案被判成「不可用」——面谈于是永远
  // 回「先去填档案」,生成计划也过不了闸门(死循环)。所以只有落在合法区间才带上。
  const dailyMinutes = Number(row.dailyMinutes ?? 0)
  const parsed = profileInputSchema.safeParse({
    targetType: row.targetType,
    examDate: toDay(row.examDate),
    ...(dailyMinutes >= MIN_DAILY_MINUTES && dailyMinutes <= MAX_DAILY_MINUTES ? { dailyMinutes } : {}),
    studyWindows: jsonArray(row.studyWindowsJson),
    foundation: row.foundation ?? '一般',
    weakSubjects: jsonArray(row.weakSubjectsJson),
  })
  return parsed.success ? parsed.data : null
}

/**
 * 问卷里的结构化事实并入已有简报。
 *
 * 问卷只收「有哪些科」,逐科进度/范围/里程碑由面谈补,所以科目名按问卷名单重建、
 * 同名保留旧进度 —— 用户重填问卷时不会把已经聊出来的进度清空。
 * 空闲时段非空才覆盖;固定占用只有用户明确确认过才覆盖(这样才能表达「确认没有固定占用」)。
 */
function applyProfileFacts(brief: PlanBrief, input: ProfileInput): PlanBrief {
  // 问卷只确认「有哪些科」,逐科进度/范围/里程碑一概没问 —— 所以只按名单建新科目时,
  // 必须标成 estimated,表示「细节待 AI 按经验估」。若误标为 confirmed(false),
  // 逐科细节会被当成「用户已确认但没写」而卡住面谈收尾与生成闸门(死循环)。
  const examSubjects = input.examSubjects.length
    ? input.examSubjects.map(name => brief.examSubjects.find(subject => subject.name === name) ?? {
      name, progress: '', scope: '', remainingMinutes: 0, milestone: '', milestoneDate: '', milestoneMinutes: 0, estimated: true,
    })
    : brief.examSubjects
  return {
    ...brief,
    examSubjects,
    availability: input.availability.length ? input.availability : brief.availability,
    availabilityConfirmed: brief.availabilityConfirmed || input.availabilityConfirmed,
    fixedCommitments: input.commitmentsConfirmed ? input.fixedCommitments : brief.fixedCommitments,
    commitmentsConfirmed: brief.commitmentsConfirmed || input.commitmentsConfirmed,
  }
}

/** 从档案行读出简报(坏数据当空简报),供问卷合并 / 面谈上下文 / 课表识别复用 */
function briefFromRow(row: any): PlanBrief {
  const raw = jsonObject(row?.briefJson)
  return raw ? normalizeBrief(raw) : normalizeBrief(null)
}

/**
 * 落库前的最后一道闸:宁可在这里抛错让事务不开始,也不要写进一份自相矛盾的计划。
 * 检查项对应生成器的核心承诺 —— 阶段连续、计划项落在所属阶段区间内、每天不超预算。
 */
function assertGeneratedPlanValid(generated: GeneratedPlan, examDate: Date, brief: PlanBrief | null): void {
  const fail = (reason: string): never => {
    throw new ApiError(502, 'PLAN_GENERATION_FAILED', `计划生成结果不完整:${reason}`)
  }
  if (generated.stages.length === 0) fail('没有生成任何阶段')
  if (generated.items.length === 0) fail('没有生成任何计划项')

  const firstStage = generated.stages[0]
  const lastStage = generated.stages[generated.stages.length - 1]
  if (diffDays(firstStage.startDate, dayStart(new Date())) !== 0) fail('计划起始日期不是今天')
  if (diffDays(examDate, lastStage.endDate) !== 1) fail('计划未在考前一天结束')

  for (const [index, stage] of generated.stages.entries()) {
    if (stage.sortOrder !== index) fail(`阶段顺序异常:${stage.name}`)
    if (diffDays(stage.endDate, stage.startDate) < 0) fail(`阶段区间为负:${stage.name}`)
    const next = generated.stages[index + 1]
    if (next && diffDays(next.startDate, stage.endDate) !== 1) fail(`阶段之间有空隙或重叠:${stage.name}`)
  }

  const perDay = new Map<string, number>()
  for (const item of generated.items) {
    const stage = generated.stages[item.stageOrder]
    if (!stage) fail(`计划项指向不存在的阶段:${item.subject}`)
    if (diffDays(item.planDate, stage.startDate) < 0 || diffDays(stage.endDate, item.planDate) < 0) {
      fail(`计划项落在阶段区间之外:${item.subject} ${dateOnly(item.planDate)}`)
    }
    if (!(item.minutes > 0)) fail(`计划项时长非正数:${item.subject}`)
    const key = dateOnly(item.planDate)
    perDay.set(key, (perDay.get(key) ?? 0) + item.minutes)
  }
  for (const [day, minutes] of perDay) {
    const available = brief ? netAvailableMinutes(brief, new Date(`${day}T00:00:00.000Z`)) : 0
    if (minutes > available) fail(`${day} 安排 ${minutes} 分钟,超出当天净空闲 ${available} 分钟`)
  }
}

/**
 * 服务端依赖的 AI 能力。`interview` / `parseTimetable` 刻意是可选的:
 * 单测注入的假 AI 只需要 generate,不必为了跑通测试去 mock 一整套面谈与视觉识别。
 */
export interface PlanningAi {
  configured: () => boolean
  generate: (input: AiPlanningInput) => Promise<{ title: string; plan: GeneratedPlan; document?: PlanDocument | null }>
  interview?: (input: InterviewInput) => Promise<InterviewResult>
  parseTimetable?: (input: TimetableImage) => Promise<TimetableResult>
  adjustIntent?: (input: AdjustIntentInput) => Promise<AdjustIntent>
  adjustL2?: (input: L2Input) => Promise<L2Task[]>
}

export function createPlanningService(
  db: PlanningDb,
  ai: PlanningAi = {
    configured: llmConfigured,
    generate: generateAiPlan,
    interview: runPlanInterview,
    parseTimetable: parseTimetableImage,
    adjustIntent: runAdjustIntent,
    adjustL2: runAdjustL2,
  },
) {
  async function requireProfileRow(userGuid: string) {
    const row = await db.userProfile.findUnique({ where: { userGuid } })
    if (!row) {
      throw new ApiError(400, 'PROFILE_INCOMPLETE', '请先完成备考问卷,再生成计划')
    }
    return row
  }

  async function loadPlan(userGuid: string, planRow: any): Promise<PublicPlan> {
    const [stages, items, profileRow] = await Promise.all([
      db.planStage.findMany({ where: { planId: planRow.id }, orderBy: { sortOrder: 'asc' } }),
      db.planItem.findMany({ where: { planId: planRow.id }, orderBy: [{ planDate: 'asc' }, { sortOrder: 'asc' }] }),
      db.userProfile.findUnique({ where: { userGuid } }),
    ])
    // 只有当前的计划才谈得上「档案变了需要重新生成」;历史计划一律不标 stale。
    // 计划生成时会记下当时的档案快照,当前档案与快照不一致(考期、课表空闲、简报等发生变化)即视为 stale。
    const stale = planRow.status !== 'active'
      ? false
      : !profileRow
        ? true
        : !planRow.profileSnapshotJson
          ? diffDays(toDay(planRow.examDate), toDay(profileRow.examDate)) !== 0
          : profileSnapshot(profileRow) !== planRow.profileSnapshotJson
    return serializePlan(planRow, stages, items, stale)
  }

  return {
    async getProfile(userGuid: string): Promise<PublicProfile | null> {
      const row = await db.userProfile.findUnique({ where: { userGuid } })
      return row ? serializeProfile(row) : null
    },

    async upsertProfile(userGuid: string, input: ProfileInput): Promise<PublicProfile> {
      // 路由层已经 parse 过一次;这里再走一遍 schema,保证任何调用方都进不了脏数据
      const parsed = profileInputSchema.parse(input)
      // 问卷里的正式科目/空闲时段/固定占用是「已确认事实」,直接并进简报,
      // 面谈就不必再一项项复问,只补逐科的进度与里程碑。
      const existing = await db.userProfile.findUnique({ where: { userGuid } })
      const mergedBrief = applyProfileFacts(briefFromRow(existing), parsed)
      const data: Record<string, unknown> = {
        targetType: parsed.targetType,
        examDate: parsed.examDate,
        studyWindowsJson: JSON.stringify(parsed.studyWindows),
        foundation: parsed.foundation,
        weakSubjectsJson: JSON.stringify(parsed.weakSubjects),
      }
      // 每日时长已不再作为排计划依据,问卷也不再收;传了才写,避免把历史值清零
      if (parsed.dailyMinutes !== undefined) data.dailyMinutes = parsed.dailyMinutes
      // 空简报既不写也不覆盖:老客户端不带新字段时不会凭空多出一份空简报
      if (!briefIsEmpty(mergedBrief)) data.briefJson = JSON.stringify(mergedBrief)
      const row = await db.userProfile.upsert({
        where: { userGuid },
        create: { userGuid, ...data, dailyMinutes: parsed.dailyMinutes ?? 0, onboardingDoneAt: new Date(), createdAt: new Date(), updatedAt: new Date() },
        // 改档案不影响现有计划:旧计划要留到新计划真的生成出来为止
        update: { ...data, onboardingDoneAt: new Date(), updatedAt: new Date() },
      })
      return serializeProfile(row)
    },

    getActivePlan: async (userGuid: string): Promise<PublicPlan | null> => {
      const row = await db.plan.findFirst({
        where: { userGuid, status: 'active' },
        orderBy: { version: 'desc' },
      })
      return row ? loadPlan(userGuid, row) : null
    },

    /**
     * 历史计划列表:当前计划 + 所有已归档计划,按版本倒序(最新的在前)。
     *
     * 确认新版草稿后旧计划归档,已完成任务和打卡保留,用户仍可回看
     * 「上一版计划长什么样」。这里只返回摘要,进度用一条 groupBy 聚合出来,
     * 不把每份上千条的 items 拉回内存。
     */
    async listPlanHistory(userGuid: string): Promise<PublicPlanSummary[]> {
      const rows = await db.plan.findMany({
        where: { userGuid, status: { in: ['active', 'archived'] } },
        orderBy: { version: 'desc' },
      })
      if (rows.length === 0) return []

      const grouped = await db.planItem.groupBy({
        by: ['planId', 'status'],
        where: { planId: { in: rows.map(r => r.id) } },
        _count: { _all: true },
      })
      const total = new Map<number, number>()
      const done = new Map<number, number>()
      for (const g of grouped) {
        const count = Number(g._count?._all ?? 0)
        total.set(g.planId, (total.get(g.planId) ?? 0) + count)
        if (g.status === 'done') done.set(g.planId, (done.get(g.planId) ?? 0) + count)
      }
      return rows.map(row => serializePlanSummary(row, total.get(row.id) ?? 0, done.get(row.id) ?? 0))
    },

    /** 只读查看某一份计划(含阶段与计划项);严格按 userGuid 归属过滤,读不到他人的计划 */
    async getPlanById(userGuid: string, planId: number): Promise<PublicPlan | null> {
      const row = await db.plan.findFirst({ where: { id: planId, userGuid } })
      return row ? loadPlan(userGuid, row) : null
    },

    async generatePlanDraft(userGuid: string): Promise<PublicPlan> {
      return buildPlan(userGuid)
    },

    async confirmPlan(userGuid: string, planId: number): Promise<PublicPlan> {
      await db.$transaction(async tx => {
        const draft = await tx.plan.findFirst({ where: { id: planId, userGuid, status: 'draft' } })
        if (!draft) throw new ApiError(404, 'PLAN_NOT_FOUND', '待确认计划不存在')
        const profile = await tx.userProfile.findUnique({ where: { userGuid } })
        if (!profile || !draft.profileSnapshotJson || profileSnapshot(profile) !== draft.profileSnapshotJson) {
          throw new ApiError(409, 'PLAN_PROFILE_CHANGED', '备考档案已变化,请重新生成草稿')
        }
        const raw = jsonObject(profile.briefJson)
        requirePlanningFacts(raw ? normalizeBrief(raw) : null, dayStart(new Date()), toDay(profile.examDate))
        if (!draft.progressSnapshotJson || await progressSnapshot(tx, userGuid) !== draft.progressSnapshotJson) {
          throw new ApiError(409, 'PLAN_PROGRESS_CHANGED', '计划进度已变化,请重新生成草稿')
        }
        await tx.plan.updateMany({ where: { userGuid, status: 'active' }, data: { status: 'archived', updatedAt: new Date() } })
        await tx.plan.updateMany({ where: { id: planId, userGuid, status: 'draft' }, data: { status: 'active', updatedAt: new Date() } })
      })
      const row = await db.plan.findFirst({ where: { id: planId, userGuid, status: 'active' } })
      return loadPlan(userGuid, row)
    },

    /**
     * 备考面谈:把「填问卷 → 直接出计划」换成「和 AI 规划师聊几轮 → 出一份真正贴身的计划」。
     *
     * 无状态:客户端把整段对话历史带上来,这里只推进一轮。轮次上限在 aiCoach 里兜底,
     * 到点强制收尾,避免用户一直聊下去而永远生成不出计划。
     * 收尾拿到有效简报时落进档案,下次生成计划直接带上,不必重新面谈。
     */
    async interview(userGuid: string, input: { messages?: unknown; force?: boolean }): Promise<InterviewResult> {
      if (!ai.interview || !ai.configured()) {
        throw new ApiError(503, 'AI_NOT_CONFIGURED', 'AI 面谈服务尚未配置,请稍后再试')
      }

      const profileRow = await db.userProfile.findUnique({ where: { userGuid } })
      const profile = profileRow ? profileInputFromRow(profileRow) : null
      // 问卷/课表确认过的事实作为面谈底稿传下去:AI 不会再问一遍已经确认的科目与作息
      const knownBrief = profileRow ? briefFromRow(profileRow) : null
      const messages = Array.isArray(input.messages) ? (input.messages as InterviewInput['messages']) : []

      let result: InterviewResult
      try {
        result = await ai.interview({ messages, profile, force: input.force === true, brief: knownBrief })
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error)
        console.warn(`[planning] 备考面谈失败:${reason}`)
        throw new ApiError(502, 'PLAN_INTERVIEW_FAILED', 'AI 面谈失败,请稍后重试')
      }

      const brief = normalizeBrief(result.brief)
      const facts = assessPlanningFacts(brief, dayStart(new Date()), profile?.examDate ?? dayStart(new Date()))
      if (!profile) {
        // 纯文本提示是死路:用户被要求去填档案,却不知道档案在哪(入口在「我的」Tab 深处)。
        // 带上 needProfile 信号,客户端据此切回「填写备考档案」阶段,渲染可点击的跳转按钮(F4)。
        return {
          reply: '得先在「备考档案」里填好考期,并确认课表空闲时间,我才好把计划排准。点下面的按钮去填,填完回来我们接着聊。',
          options: [], done: false, brief: null, needProfile: true,
        }
      }
      if (!facts.ready) {
        // 保留模型自己的追问 —— 覆盖成模板句会让面谈每轮都在复读,这是最伤体验的地方。
        // 只有模型自认为可以收尾、实际上事实还没齐时,才附一句差项提示。
        const hint = facts.missing.length ? `还差这几项:${facts.missing.join('、')}`
          : facts.deficits.length ? `按现在确认的空闲时间还盖不住:${facts.deficits.map(d => `${d.subject}「${d.milestone}」缺${d.missingMinutes}分钟`).join('、')}`
            : ''
        const reply = result.done && hint ? `${result.reply}\n(${hint})` : result.reply
        return { reply, options: result.done ? [] : result.options, done: false, brief: null }
      }
      if (result.done && !briefIsEmpty(brief) && profileRow) {
        await db.userProfile.update({
          where: { userGuid },
          data: { briefJson: JSON.stringify(brief), updatedAt: new Date() },
        })
      }

      return { ...result, brief: result.done ? brief : null }
    },

    /**
     * 课表图片识别:交给视觉模型转成「每周固定占用」,并入简报后回给客户端确认。
     *
     * 为什么允许没有档案行:上传课表发生在问卷流程里,那时用户可能还没保存过档案。
     * 识别结果无论如何都返回给客户端,客户端把它带进 PUT /profile 即可落库;
     * 已存在档案时顺手并进 briefJson,这样在面谈阶段补传课表也能立刻生效。
     */
    async parseTimetable(userGuid: string, input: TimetableImage): Promise<TimetableResult> {
      if (!ai.parseTimetable || !visionConfigured()) {
        throw new ApiError(503, 'AI_VISION_NOT_CONFIGURED', '尚未配置视觉模型,暂时无法识别课表图片,请手动填写固定占用')
      }

      let result: TimetableResult
      try {
        result = await ai.parseTimetable(input)
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error)
        console.warn(`[planning] 课表识别失败:${reason}`)
        throw new ApiError(502, 'TIMETABLE_PARSE_FAILED', `课表识别失败:${reason}`)
      }

      const profileRow = await db.userProfile.findUnique({ where: { userGuid } })
      if (profileRow) {
        const base = briefFromRow(profileRow)
        // 识别出的课程是「用户上传并确认过的事实」,因此把确认位置 true;
        // 与已有占用合并去重,重复上传同一张课表不会翻倍。
        const known = base.fixedCommitments
        const added = result.fixedCommitments.filter(item => !known.some(existing => existing.weekday === item.weekday
          && existing.start === item.start && existing.end === item.end && existing.label === item.label))
        await db.userProfile.update({
          where: { userGuid },
          data: {
            briefJson: JSON.stringify({ ...base, fixedCommitments: [...known, ...added], commitmentsConfirmed: true }),
            updatedAt: new Date(),
          },
        })
      }
      return result
    },

    async setItemStatus(userGuid: string, itemId: number, status: PlanItemStatus): Promise<PublicPlan> {
      const planRow = await db.plan.findFirst({
        where: { userGuid, status: 'active' },
        orderBy: { version: 'desc' },
      })
      if (!planRow) throw new ApiError(404, 'PLAN_NOT_FOUND', '当前没有生效中的计划')

      if (status === 'done') {
        await db.planItem.updateMany({
          where: { id: itemId, planId: planRow.id, status: 'pending' },
          data: { status: 'done', completedAt: new Date(), updatedAt: new Date() },
        })
      } else {
        await db.planItem.updateMany({
          where: { id: itemId, planId: planRow.id },
          data: { status: 'pending', completedAt: null, updatedAt: new Date() },
        })
      }
      const current = (await db.planItem.findMany({ where: { id: itemId, planId: planRow.id } }))[0]
      if (!current) throw new ApiError(404, 'PLAN_ITEM_NOT_FOUND', '计划项不存在')

      await db.plan.updateMany({
        where: { id: planRow.id },
        data: { updatedAt: new Date() },
      })
      return loadPlan(userGuid, planRow)
    },

    /**
     * 行程调整入口:一句话描述突发情况 → 模型解析意图 → 服务端硬规则定档(L1 顺延/L2 重排)→ 产出待确认调整单。
     * 确认前零副作用(唯一例外:change_commitment 把新固定占用并进档案,那是用户陈述的事实本身)。
     */
    async adjust(userGuid: string, input: { message: string }):
      Promise<{ adjustment: PublicAdjustment | null; reply: string; plan: PublicPlan | null }> {
      const message = String(input?.message ?? '').trim()
      if (!message) throw new ApiError(400, 'INVALID_PARAMS', '请说一说发生了什么')
      const planRow = await db.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
      if (!planRow) throw new ApiError(404, 'PLAN_NOT_FOUND', '当前没有生效中的计划')
      if (!ai.adjustIntent || !ai.configured()) {
        throw new ApiError(503, 'AI_NOT_CONFIGURED', '服务端还没有配置大模型,暂时无法理解你的描述')
      }

      const profileRow = await db.userProfile.findUnique({ where: { userGuid } })
      const brief = profileRow ? briefFromRow(profileRow) : normalizeBrief(null)
      const stages = await db.planStage.findMany({ where: { planId: planRow.id }, orderBy: { sortOrder: 'asc' } })
      const today = dayStart(new Date()).toISOString().slice(0, 10)
      const { from, to } = stageWindow(stages, today)

      let intent: AdjustIntent
      try {
        const subjects = [...new Set((await db.planItem.findMany({ where: { planId: planRow.id } }))
          .map(row => String(row.subject)).filter(Boolean))]
        intent = await ai.adjustIntent({ message, today, windowTo: to, subjects, brief })
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error)
        console.warn(`[planning] 调整意图解析失败:${reason}`)
        throw new ApiError(502, 'PLAN_INTERVIEW_FAILED', `这句没听懂:${reason}`)
      }

      // 追问 / 闲聊:只回话,不产调整单
      if (intent.needClarify || intent.kind === 'chat') {
        const reply = intent.needClarify ? (intent.clarifyQuestion || intent.summary) : intent.summary
        return { adjustment: null, reply, plan: null }
      }

      // change_commitment:每周固定占用变了。先把新占用并进档案(并集去重,与课表识别同一口径),
      // 之后的容量计算自然按新占用扣减。
      if (intent.kind === 'change_commitment' && intent.commitments.length > 0 && profileRow) {
        const known = brief.fixedCommitments
        const added = intent.commitments.filter(item => !known.some(existing => existing.weekday === item.weekday
          && existing.start === item.start && existing.end === item.end && existing.label === item.label))
        brief.fixedCommitments = [...known, ...added]
        brief.commitmentsConfirmed = true
        await db.userProfile.update({
          where: { userGuid },
          data: { briefJson: JSON.stringify(brief), updatedAt: new Date() },
        })
      }

      const items = await db.planItem.findMany({ where: { planId: planRow.id } })
      const inWindow = items.filter(row => {
        const day = dateOnly(row.planDate)
        return day >= from && day <= to
      })

      // 每日容量 = 当天课表净空闲,再逐项扣掉窗口内已完成的分钟
      const capacity = new Map<string, number>()
      for (const day of datesBetween(from, to)) capacity.set(day, dailyCapacity(brief, day))
      for (const row of inWindow) {
        if (row.status === 'done') {
          const day = dateOnly(row.planDate)
          capacity.set(day, Math.max(0, (capacity.get(day) ?? 0) - Number(row.minutes ?? 0)))
        }
      }

      const pending = inWindow.filter(row => row.status !== 'done').map(row => toAdjustable(row))
      const before = toSnapshot(inWindow)

      if (intent.kind === 'unavailable') {
        for (const day of intent.days) {
          if (capacity.has(day)) capacity.set(day, 0)
        }
      } else if (intent.kind === 'reduce_capacity') {
        if (intent.windows.length === 0) {
          // 用户只说「时间变少」没说哪天:按今明后三天各降 30% 估
          for (const day of datesBetween(from, shiftDate(today, 2))) {
            const base = capacity.get(day) ?? 0
            if (base > 0) capacity.set(day, Math.round(base * 0.7))
          }
        } else {
          for (const window of intent.windows) {
            if (!capacity.has(window.day)) continue
            capacity.set(window.day, window.minutes <= 0 ? 0 : Math.min(capacity.get(window.day) ?? 0, window.minutes))
          }
        }
      }

      const l1 = planL1Shuffle({ pending, capacity, windowFrom: from, windowTo: to })
      // 档位判定(服务端硬规则,spec §3.2):塞得下且影响天数 ≤ 阈值 → L1 顺延;否则 L2 重排
      const tier: AdjustmentTier = l1.overflow.length === 0 && affectedDays(l1.moves) <= L1_MAX_AFFECTED_DAYS
        ? 'L1'
        : 'L2'

      let after: WindowSnapshotItem[]
      if (tier === 'L1') {
        const movedById = new Map(l1.moves.map(move => [move.item.id, move.to]))
        after = before.map(item => {
          const nextDay = movedById.get(item.id)
          return nextDay && item.status !== 'done' ? { ...item, planDate: nextDay } : item
        })
      } else {
        // L2 预检:窗口总量塞不下直接报缺口,不浪费一次模型调用
        const totalPending = pending.reduce((sum, item) => sum + item.minutes, 0)
        const totalCapacity = [...capacity.values()].reduce((sum, value) => sum + value, 0)
        if (totalPending > totalCapacity) {
          throw new ApiError(400, 'PLAN_CAPACITY_INSUFFICIENT',
            `这个阶段到 ${to} 之前只剩 ${totalCapacity} 分钟,排不下 ${totalPending} 分钟的任务。要么把休息日让出来,要么等下一阶段再补`)
        }
        const target = await runAdjustL2Plan(ai, { message, pending, capacity, from, to, brief })
        // L2 的 after 必须带上窗口内的 done 项,否则 diff 会把打卡项误判成 removed
        after = [...before.filter(item => item.status === 'done'), ...target]
      }
      const changes = diffSnapshots(before, after)
      if (changes.length === 0) {
        return { adjustment: null, reply: '这个阶段里本来就排得下,计划不用改。', plan: null }
      }

      // 同一用户同一时刻最多一张 draft:已有就原地覆盖,不产生第二张(spec §6)
      const payload = {
        userGuid,
        planId: planRow.id,
        tier,
        status: 'draft',
        reason: intent.note || message,
        summary: intent.summary,
        windowFrom: new Date(`${from}T00:00:00.000Z`),
        windowTo: new Date(`${to}T00:00:00.000Z`),
        beforeJson: JSON.stringify(before),
        afterJson: JSON.stringify(after),
        fingerprint: windowFingerprint(before),
      }
      const existingDraft = await db.planAdjustment.findFirst({ where: { userGuid, status: 'draft' } })
      let row: any
      if (existingDraft) {
        await db.planAdjustment.updateMany({ where: { id: existingDraft.id }, data: payload })
        row = await db.planAdjustment.findFirst({ where: { id: existingDraft.id } })
      } else {
        row = await db.planAdjustment.create({ data: payload })
      }
      return { adjustment: serializeAdjustment(row), reply: `已按「${intent.summary}」算好新排法,确认后生效。`, plan: null }
    },

    /** 最近一张待确认/已生效的调整单:App 冷启动/切页恢复卡片用。按事件时间倒序(draft 看 createdAt,applied 看 appliedAt) */
    async latestAdjustment(userGuid: string): Promise<PublicAdjustment | null> {
      const rows = await db.planAdjustment.findMany({ where: { userGuid, status: { in: ['draft', 'applied'] } } })
      const eventTime = (row: any) => ms(row.status === 'applied' ? row.appliedAt : row.createdAt) ?? 0
      const sorted = [...rows].sort((a, b) => eventTime(b) - eventTime(a) || Number(b.id) - Number(a.id))
      return sorted.length > 0 ? serializeAdjustment(sorted[0]) : null
    },

    /** 确认调整单:指纹校验(窗口没被第三方改过)→ 事务内把目标快照写进计划项 → 标记 applied */
    async confirmAdjustment(userGuid: string, adjustmentId: number): Promise<PublicPlan> {
      const appliedPlanId = await db.$transaction(async tx => {
        const row = await tx.planAdjustment.findFirst({ where: { id: adjustmentId, userGuid, status: 'draft' } })
        if (!row) throw new ApiError(404, 'ADJUSTMENT_NOT_FOUND', '调整单不存在,或已经确认/撤销过了')
        const planRow = await tx.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
        if (!planRow || Number(planRow.id) !== Number(row.planId)) {
          throw new ApiError(404, 'PLAN_NOT_FOUND', '这份调整单对应的计划已经不在了')
        }
        const stages = await tx.planStage.findMany({ where: { planId: planRow.id }, orderBy: { sortOrder: 'asc' } })
        const items = await tx.planItem.findMany({ where: { planId: planRow.id } })
        const from = dateOnly(row.windowFrom)
        const to = dateOnly(row.windowTo)
        const inWindow = items.filter(item => {
          const day = dateOnly(item.planDate)
          return day >= from && day <= to
        })
        if (windowFingerprint(toSnapshot(inWindow)) !== String(row.fingerprint)) {
          throw new ApiError(409, 'ADJUSTMENT_STALE', '计划在生成调整单之后又被改过,这张调整单已失效,请重新说一遍')
        }
        await syncWindowItems(tx, planRow.id, from, to, jsonList<WindowSnapshotItem>(row.afterJson), stages)
        await tx.planAdjustment.updateMany({
          where: { id: row.id },
          data: { status: 'applied', appliedAt: new Date() },
        })
        await tx.plan.updateMany({ where: { id: planRow.id }, data: { updatedAt: new Date() } })
        return Number(planRow.id)
      })
      const planRow = await db.plan.findFirst({ where: { id: appliedPlanId } })
      if (!planRow) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划不存在')
      return loadPlan(userGuid, planRow)
    },

    /** 撤销:把 beforeJson 写回去。只允许撤销最近一次 applied,且其后没有新打卡(spec §2 可撤销原则) */
    async undoAdjustment(userGuid: string, adjustmentId: number): Promise<PublicPlan> {
      const rows = await db.planAdjustment.findMany({ where: { userGuid, status: 'applied' } })
      const latestApplied = [...rows].sort((a, b) => Number(b.id) - Number(a.id))[0]
      if (!latestApplied || Number(latestApplied.id) !== Number(adjustmentId)) {
        throw new ApiError(409, 'ADJUSTMENT_STALE', '只能撤销最近一次调整')
      }
      const appliedPlanId = await db.$transaction(async tx => {
        const planRow = await tx.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
        if (!planRow || Number(planRow.id) !== Number(latestApplied.planId)) {
          throw new ApiError(404, 'PLAN_NOT_FOUND', '这份调整单对应的计划已经不在了')
        }
        const appliedAt = ms(latestApplied.appliedAt)
        const items = await tx.planItem.findMany({ where: { planId: planRow.id } })
        // >= 而非 >:Windows 时钟粒度可能让「确认」与「打卡」同毫秒,严格比较才能保证不把新打卡搞乱
        const touched = items.some(item => item.status === 'done' && appliedAt != null
          && ms(item.completedAt) != null && ms(item.completedAt)! >= appliedAt)
        if (touched) {
          throw new ApiError(409, 'ADJUSTMENT_STALE', '调整生效后你已经打了新的卡,撤销会把记录搞乱;再用一句话描述新的调整即可')
        }
        const stages = await tx.planStage.findMany({ where: { planId: planRow.id }, orderBy: { sortOrder: 'asc' } })
        await syncWindowItems(tx, planRow.id, dateOnly(latestApplied.windowFrom), dateOnly(latestApplied.windowTo),
          jsonList<WindowSnapshotItem>(latestApplied.beforeJson), stages)
        await tx.planAdjustment.updateMany({
          where: { id: latestApplied.id },
          data: { status: 'undone', undoneAt: new Date() },
        })
        await tx.plan.updateMany({ where: { id: planRow.id }, data: { updatedAt: new Date() } })
        return Number(planRow.id)
      })
      const planRow = await db.plan.findFirst({ where: { id: appliedPlanId } })
      if (!planRow) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划不存在')
      return loadPlan(userGuid, planRow)
    },

    /** 主动体检:数一数落在过去还没完成的任务,给出一句话建议(纯计算,无副作用) */
    async checkup(userGuid: string): Promise<{ behindMinutes: number; overdueCount: number; suggestion: string }> {
      const planRow = await db.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
      if (!planRow) return { behindMinutes: 0, overdueCount: 0, suggestion: '' }
      const items = await db.planItem.findMany({ where: { planId: planRow.id } })
      const today = dayStart(new Date()).toISOString().slice(0, 10)
      const overdue = items.filter(item => item.status !== 'done' && dateOnly(item.planDate) < today)
      const behindMinutes = overdue.reduce((sum, item) => sum + Number(item.minutes ?? 0), 0)
      return { behindMinutes, overdueCount: overdue.length, suggestion: overdueSuggestion(overdue.length, behindMinutes) }
    },
  }

  async function buildPlan(userGuid: string): Promise<PublicPlan> {
    const profileRow = await requireProfileRow(userGuid)
      const initialSnapshot = profileSnapshot(profileRow)
      const profile = serializeProfile(profileRow)
      const examDate = toDay(profileRow.examDate)

      // 库里读出来的是宽松字符串,这里再过一遍 schema 收成 ProfileInput 的字面量联合类型。
      // 校验失败说明存进库的档案本身就不可用(日期过期等),交给路由层转成业务错误码。
      // dailyMinutes 已不再参与排计划,不再回传校验,避免历史/默认值把校验带崩。
      const parsedProfile = profileInputSchema.safeParse({
        targetType: profile.targetType,
        examDate,
        studyWindows: profile.studyWindows,
        foundation: profile.foundation ?? '一般',
        weakSubjects: profile.weakSubjects,
      })
      if (!parsedProfile.success) {
        const issue = parsedProfile.error.issues[0]
        const field = String(issue?.path[0] ?? '')
        if (field === 'examDate') throw new ApiError(400, 'INVALID_EXAM_DATE', issue.message)
        throw new ApiError(400, 'INVALID_PARAMS', issue?.message ?? '备考档案不完整,请重新填写')
      }

      if (!ai.configured()) {
        throw new ApiError(503, 'AI_NOT_CONFIGURED', 'AI 计划生成服务尚未配置,请稍后再试')
      }

      // 面谈得到的画像简报:有就一起喂给生成器,让阶段与每日安排贴合考生真实情况
      const briefRaw = jsonObject(profileRow.briefJson)
      const brief = briefRaw ? normalizeBrief(briefRaw) : null
      const initialProgress = await progressSnapshot(db, userGuid)
      const active = await db.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
      const completed = new Map<string, number>()
      const history = active && active.profileSnapshotJson === initialSnapshot
        ? await db.plan.findMany({ where: { userGuid, status: { in: ['active', 'archived'] } } })
        : []
      const matchingIds = history.filter(row => row.profileSnapshotJson === initialSnapshot).map(row => row.id)
      if (matchingIds.length) {
        const doneItems = await db.planItem.findMany({ where: { planId: { in: matchingIds }, status: 'done' } })
        for (const item of doneItems) {
          completed.set(item.subject, (completed.get(item.subject) ?? 0) + Number(item.minutes))
        }
      }
      const remainingBrief = brief && active ? {
        ...brief,
        examSubjects: brief.examSubjects.map(subject => ({
          ...subject,
          remainingMinutes: Math.max(0, subject.remainingMinutes - (completed.get(subject.name) ?? 0)),
          milestoneMinutes: Math.max(0, subject.milestoneMinutes - (completed.get(subject.name) ?? 0)),
        })),
      } : brief
      // 刻意不再把旧计划的未完成任务作为 backlog 带进新计划:用户反馈"每生成一次,
      // 每日任务就累积一轮旧任务"——重新生成就是全新计划,进度语义由 remainingBrief
      // 的逐科剩余量承载(已完成分钟数已从 brief 扣除),不需要任务级搬运。
      requirePlanningFacts(brief, dayStart(new Date()), examDate)
      if (active && brief) requirePlanningFacts(remainingBrief, dayStart(new Date()), examDate, true)

      let generated: GeneratedPlan
      let title: string
      let document: PlanDocument | null = null
      // 校验不过不直接放弃:模型输出有随机性,同一份 prompt 盲掷骰子大概率还是失败。
      // 把上一轮被拦的原因作为 repairHint 喂回去,模型知道「哪里错了」再重出骨架,
      // 两三次内基本能自愈。事实性缺口(容量真不够)重试无意义,直接上报。
      const maxAttempts = 3
      let lastFailure: ApiError | null = null
      for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
          const result = await ai.generate({
            ...parsedProfile.data,
            startDate: dayStart(new Date()),
            brief: remainingBrief,
            ...(lastFailure ? { repairHint: lastFailure.message } : {}),
          })
          assertGeneratedPlanValid(result.plan, examDate, brief)
          if (brief) {
            const allowed = new Set(brief.examSubjects.map(subject => subject.name))
            const invalid = result.plan.items.find(item => !allowed.has(item.subject))
            if (invalid) throw new ApiError(502, 'PLAN_GENERATION_FAILED', `计划包含未确认的考试科目:${invalid.subject}`)
            for (const subject of brief.examSubjects) {
              // AI 估计的科目允许范围留空(草稿里已标注可改),不按「编造」拦截
              if (subject.estimated) continue
              if (!subject.scope && result.plan.items.some(item => item.subject === subject.name && item.title !== subject.milestone)) {
                throw new ApiError(502, 'PLAN_GENERATION_FAILED', `${subject.name}考试范围不明,不得编造具体任务`)
              }
            }
            const deficits = remainingBrief!.examSubjects.flatMap(subject => {
              // AI 估计的剩余量不参与覆盖度校验,由考生在草稿里逐项更正
              if (subject.estimated) return []
              const tasks = result.plan.items.filter(item => item.subject === subject.name)
              const scheduled = tasks.reduce((sum, item) => sum + item.minutes, 0)
              const beforeDeadline = tasks.filter(item => dateOnly(item.planDate) <= subject.milestoneDate)
                .reduce((sum, item) => sum + item.minutes, 0)
              const missingMinutes = Math.max(subject.remainingMinutes - scheduled, subject.milestoneMinutes - beforeDeadline)
              return missingMinutes > 0 ? [`${subject.name}「${subject.milestone}」缺口 ${missingMinutes} 分钟`] : []
            })
            if (deficits.length) throw new ApiError(400, 'PLAN_CAPACITY_INSUFFICIENT', `草稿安排未覆盖已确认的剩余任务:${deficits.join('；')}`)
          }
          generated = result.plan
          title = result.title
          document = result.document ?? null
          lastFailure = null
          break
        } catch (error) {
          if (error instanceof ApiError) {
            if (error.code === 'PLAN_CAPACITY_INSUFFICIENT') throw error
            lastFailure = error
            console.warn(`[planning] AI 计划生成第 ${attempt}/${maxAttempts} 次校验未过:${error.message}`)
            continue
          }
          const reason = error instanceof Error ? error.message : String(error)
          // 模型侧异常(网络/JSON 解析/阶段数不足)也有随机成分,同样给一轮机会;
          // 但「缺少已确认的空闲时段」这类确定性错误重试无意义,原样上报。
          if (error instanceof AiUnavailable && reason.includes('缺少已确认的空闲时段')) {
            throw new ApiError(502, 'PLAN_GENERATION_FAILED', `AI 计划生成失败:${reason}`)
          }
          lastFailure = new ApiError(502, 'PLAN_GENERATION_FAILED', `AI 计划生成失败:${reason}`)
          console.warn(`[planning] AI 计划生成第 ${attempt}/${maxAttempts} 次失败:${reason}`)
          continue
        }
      }
      if (lastFailure) throw lastFailure

      const lastPlan = await db.plan.findFirst({ where: { userGuid }, orderBy: { version: 'desc' } })
      const version = Number(lastPlan?.version ?? 0) + 1

      const created = await db.$transaction(async tx => {
        const latestProfile = await tx.userProfile.findUnique({ where: { userGuid } })
        if (!latestProfile || profileSnapshot(latestProfile) !== initialSnapshot) {
          throw new ApiError(409, 'PLAN_PROFILE_CHANGED', '备考档案已变化,请重新生成草稿')
        }
        if (await progressSnapshot(tx, userGuid) !== initialProgress) {
          throw new ApiError(409, 'PLAN_PROGRESS_CHANGED', '计划进度已变化,请重新生成草稿')
        }
        await tx.plan.updateMany({
          where: { userGuid, status: 'draft' },
          data: { status: 'archived', updatedAt: new Date() },
        })
        const planRow = await tx.plan.create({
          data: {
            userGuid,
            profileId: profileRow.id,
            title,
            targetType: profileRow.targetType ?? '考研',
            source: 'ai',
            status: 'draft',
            startDate: generated.stages[0].startDate,
            examDate,
            version,
            documentJson: document ? JSON.stringify(document) : null,
            profileSnapshotJson: initialSnapshot,
            progressSnapshotJson: initialProgress,
            createdAt: new Date(),
            updatedAt: new Date(),
          },
        })
        // 阶段逐个 create 是为了拿到自增 id,计划项要按 stageOrder 挂到对应阶段
        const stageIds: number[] = []
        for (const stage of generated.stages) {
          const stageRow = await tx.planStage.create({
            data: {
              planId: planRow.id,
              name: stage.name,
              strategy: stage.strategy ?? null,
              milestonesJson: stage.milestones?.length ? JSON.stringify(stage.milestones) : null,
              startDate: stage.startDate,
              endDate: stage.endDate,
              sortOrder: stage.sortOrder,
            },
          })
          stageIds.push(stageRow.id)
        }
        await tx.planItem.createMany({
          data: generated.items.map(item => ({
            planId: planRow.id,
            stageId: stageIds[item.stageOrder],
            subject: item.subject,
            title: item.title,
            planDate: item.planDate,
            minutes: item.minutes,
            priority: item.priority,
            status: 'pending',
            sortOrder: item.sortOrder,
          })),
        })
        return planRow
      })

      return loadPlan(userGuid, created)
    }
}

/** 主动提醒文案:没有落后任务返回空串(App 以空串判断「不用提醒」) */
export function overdueSuggestion(count: number, minutes: number): string {
  if (count <= 0) return ''
  const part = minutes > 0 ? `、约 ${minutes} 分钟` : ''
  return `有 ${count} 项${part}的任务落在过去还没完成,打开 AI 行程小助手说一句,马上帮你重新排。`
}

export type PlanningService = ReturnType<typeof createPlanningService>

/** 生产实例:注入真实的 Prisma 客户端 */
export const planningService = createPlanningService(prisma as unknown as PlanningDb)
