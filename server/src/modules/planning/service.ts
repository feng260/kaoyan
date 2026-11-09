import { prisma } from '../../shared/prisma'
import { ApiError } from '../../middlewares/error'
import { llmConfigured, visionConfigured } from '../../config/env'
import { addDays, dayStart, diffDays, profileInputSchema, MIN_DAILY_MINUTES, MAX_DAILY_MINUTES, type ProfileInput } from './schemas'
import type { GeneratedPlan, GeneratedStage } from './generator'
import { AiUnavailable, generateAiPlan, type AiPlanningInput, type PlanProgress } from './aiGenerator'
import type { PlanSpec } from './planSpec'
import { GenerationJobStore, type PlanGenerationJob } from './generationJobs'
import { generatePlanDocument } from './aiDocument'
import { runPlanInterview, type InterviewInput, type InterviewResult } from './aiCoach'
import { parseTimetableImage, type TimetableImage, type TimetableResult } from './timetable'
import { briefIsEmpty, normalizeBrief, assessPlanningFacts, netAvailableMinutes, documentMatchesSubjects, type PlanBrief, type PlanDocument } from './document'
import { KAOYAN_KNOWN_SUBJECT_NAMES } from './planExemplar'
import {
  adjustWindow, affectedDays, dailyCapacity, datesBetween, diffSnapshots, planL1Shuffle, shiftDate,
  windowFingerprint, L1_MAX_AFFECTED_DAYS,
  type AdjustmentTier, type AdjustableItem, type WindowSnapshotItem,
} from './adjust'
import { buildLedger, expandL2, normalizeL2Tasks, runAdjustL2, validateL2, type L2Input, type L2Mode, type L2Task } from './adjustL2'
import { runAdjustIntent, type AdjustIntent, type AdjustIntentInput } from './adjustIntent'
import {
  briefContextHash, briefHash, budgetView, buildPlanBudget, buildPlanOverview, capacityByWeekday,
  PLAN_LOAD_FACTOR, REVIEW_SUBJECT, type PlanBudget,
} from './planBudget'
import { buildAdjustmentOptions, type AdjustmentImpact, type AdjustmentOption } from './planOptions'
import { reflowDay } from './reflow'
import {
  buildCheckup, emptyCheckup, normalizeTzOffset, DEFAULT_TZ_OFFSET,
  type CheckupSummary,
} from './checkup'

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
    create(args: AnyArgs): Promise<any>
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
  pomodoroSession: {
    findMany(args?: AnyArgs): Promise<any[]>
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
  /** 时段 morning/afternoon/evening;旧计划没有这一列时为 null */
  slot: string | null
  /** 建议开始时刻 HH:mm */
  startTime: string | null
  /** 锁定后 AI 调整不得移动/改内容/删除;手工仍可改 */
  locked: boolean
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
  /** 多方案调整:客户端只拿展示字段,完整 after 留在服务端 */
  options: Array<{
    id: string
    label: string
    summary: string
    tier?: string
    impact: Record<string, number>
    changes: ReturnType<typeof diffSnapshots>
  }> | null
  selectedOption: string | null
  createdAt: number | null
  appliedAt: number | null
}

export interface GenerateOptions {
  /** 客户端确认过的计划口径哈希;不一致说明用户改过档案,拒绝用旧口径生成 */
  briefHash?: string
  /** 用户明确选择「尽力而为」:允许按有效容量缩放,不因缺口 409 */
  allowShortfall?: boolean
}

export interface BriefPatchSubjectInput {
  name: string
  remainingMinutes?: number
  milestone?: string
  milestoneDate?: string
  milestoneMinutes?: number
  estimated?: boolean
  scope?: string
  progress?: string
}

export interface BriefPatchInput {
  examSubjects?: BriefPatchSubjectInput[]
  fixedCommitments?: Array<{ weekday: number; start: string; end: string; label: string; weekParity?: 'odd' | 'even' }>
}

export interface PlanItemEditInput {
  status?: PlanItemStatus
  planDate?: string
  minutes?: number
  title?: string
  subject?: string
  locked?: boolean
}

export interface AddPlanItemInput {
  planId?: number
  subject: string
  title: string
  planDate: string
  minutes: number
  locked?: boolean
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

/**
 * slim 勾选响应:只回被改的那一项与进度。全量 plan 单份可达数百 KB,
 * 而勾选是高频操作 —— 客户端用本地缓存的 plan 合并这一项即可得到完整新计划。
 */
export function slimToggleResponse(
  plan: PublicPlan,
  itemId: number,
): { item: PublicPlanItem; progress: PublicPlan['progress'] } | null {
  const item = plan.items.find(i => i.id === itemId)
  if (!item) return null
  return { item, progress: plan.progress }
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

/** ISO 星期:周一=1 至 周日=7(补生成文档时把计划项日期映射成首周示例的 weekday) */
function isoWeekdayOf(d: Date): number {
  const js = d.getUTCDay()
  return js === 0 ? 7 : js
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
      slot: i.slot ?? null,
      startTime: i.startTime ?? null,
      locked: i.locked === true,
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
    locked: row.locked === true,
  }
}

function toSnapshot(items: any[]): WindowSnapshotItem[] {
  return items.map(row => ({
    id: Number(row.id), subject: String(row.subject ?? ''), title: String(row.title ?? ''),
    planDate: dateOnly(row.planDate), minutes: Number(row.minutes ?? 0), status: String(row.status ?? 'pending'),
    locked: row.locked === true,
    startTime: row.startTime ?? null,
    slot: row.slot ?? null,
    priority: Number(row.priority ?? 0),
    sortOrder: Number(row.sortOrder ?? 0),
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
  target: WindowSnapshotItem[], stages: any[], brief: PlanBrief | null = null): Promise<void> {
  const allRows = await tx.planItem.findMany({ where: { planId } })
  const existingById = new Map<number, any>(allRows.map(row => [Number(row.id), row]))
  const targetIds = new Set(target.filter(item => item.id > 0 && existingById.has(item.id)).map(item => item.id))
  const affected = new Set<string>()
  for (const row of allRows) {
    const day = dateOnly(row.planDate)
    if (day >= windowFrom && day <= windowTo) affected.add(day)
  }
  for (const item of target) {
    if (item.planDate >= windowFrom && item.planDate <= windowTo) affected.add(item.planDate)
  }

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
    const locked = item.locked === true
    if (day === item.planDate && Number(row.minutes) === item.minutes
      && String(row.subject) === item.subject && String(row.title) === item.title
      && (row.locked === true) === locked) continue
    await tx.planItem.updateMany({
      where: { id: item.id, planId, status: 'pending' },
      data: {
        planDate: new Date(`${item.planDate}T00:00:00.000Z`),
        minutes: item.minutes, subject: item.subject, title: item.title,
        locked,
        stageId: stageIdFor(stages, item.planDate),
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
        priority: item.priority ?? 0,
        sortOrder: item.sortOrder ?? 0,
        locked: item.locked === true,
        slot: item.slot ?? null,
        startTime: item.startTime ?? null,
      })),
    })
  }

  if (brief) await reflowDates(tx, planId, [...affected], brief)
}

/** 按天重排时段与开始时刻:改期、增删、确认调整后都必须调用,保证 startTime 与日期一致 */
async function reflowDates(tx: PlanningDb, planId: number, days: string[], brief: PlanBrief | null): Promise<void> {
  if (!brief) return
  const unique = [...new Set(days)].filter(Boolean).sort()
  if (unique.length === 0) return
  const rows = await tx.planItem.findMany({ where: { planId } })
  for (const day of unique) {
    const dayRows = rows.filter(row => dateOnly(row.planDate) === day)
    if (dayRows.length === 0) continue
    const placements = reflowDay({
      date: day,
      brief,
      items: dayRows.map(row => ({
        id: Number(row.id), subject: String(row.subject ?? ''), title: String(row.title ?? ''),
        minutes: Number(row.minutes ?? 0), status: String(row.status ?? 'pending'),
        locked: row.locked === true, startTime: row.startTime ?? null, slot: row.slot ?? null,
        sortOrder: Number(row.sortOrder ?? 0), priority: Number(row.priority ?? 0),
      })),
    })
    for (const placement of placements) {
      await tx.planItem.updateMany({
        where: { id: placement.id, planId },
        data: { slot: placement.slot, startTime: placement.startTime, sortOrder: placement.sortOrder },
      })
    }
  }
}

/** 手工编辑/新增只允许动自己名下、非历史计划里的计划项 */
async function loadEditableItem(db: PlanningDb, userGuid: string, itemId: number): Promise<{ item: any; plan: any }> {
  const [item] = await db.planItem.findMany({ where: { id: itemId } })
  if (!item) throw new ApiError(404, 'PLAN_ITEM_NOT_FOUND', '计划项不存在')
  const plan = await db.plan.findFirst({ where: { id: item.planId, userGuid } })
  if (!plan) throw new ApiError(404, 'PLAN_ITEM_NOT_FOUND', '计划项不存在')
  if (plan.status === 'archived') throw new ApiError(409, 'PLAN_ITEM_NOT_EDITABLE', '历史计划只读,不能修改')
  return { item, plan }
}

/** 某天除 excludeId 之外已排的分钟数;容量判断包含 done/locked —— 那天的时间已经被占用 */
function itemDayLoad(items: any[], day: string, excludeId?: number): number {
  return items
    .filter(row => dateOnly(row.planDate) === day && Number(row.id) !== excludeId)
    .reduce((sum, row) => sum + Number(row.minutes ?? 0), 0)
}

/**
 * 超容量时给出一个可落地的替代日期,而不是只回一句「排不下」。
 * 要找的是能放下整项任务的日期(不是只放下超出部分),因此从明天开始找,
 * 当天已经装不下才报错,再建议它只会让用户原地打转。
 */
function findCapacitySuggestion(
  items: any[], brief: PlanBrief | null, fromDay: string, neededMinutes: number, excludeId?: number, horizon = 7,
): string | null {
  if (!brief || neededMinutes <= 0) return null
  for (let offset = 1; offset <= horizon; offset++) {
    const day = shiftDate(fromDay, offset)
    const capacity = dailyCapacity(brief, day)
    if (capacity <= 0) continue
    const free = capacity - itemDayLoad(items, day, excludeId)
    if (free >= neededMinutes) return `${day} 还有 ${free} 分钟空余,可以把这项挪到那天`
  }
  return null
}

async function applyItemStatus(db: PlanningDb, planId: number, itemId: number, status: PlanItemStatus): Promise<void> {
  if (status === 'done') {
    await db.planItem.updateMany({
      where: { id: itemId, planId, status: 'pending' },
      data: { status: 'done', completedAt: new Date() },
    })
  } else {
    await db.planItem.updateMany({
      where: { id: itemId, planId },
      data: { status: 'pending', completedAt: null },
    })
  }
  await db.plan.updateMany({ where: { id: planId }, data: { updatedAt: new Date() } })
}

/**
 * 手工编辑的内容校验:科目必须在已确认名单里,日期必须落在计划与某个阶段内,
 * 时长 5..480;每天硬上限是净空闲(锁定/done 已占用的时间不再放给新任务)。
 */
function validateItemContent(input: {
  subject: string; title: string; planDate: string; minutes: number; locked: boolean
}, ctx: {
  brief: PlanBrief | null; planStart: string; examDate: string; stages: any[]; items: any[]; excludeId?: number
}): { dayCapacity: number; planned: number } {
  const details: Array<{ field: string; message: string }> = []
  const subject = input.subject.trim()
  const title = input.title.trim()
  const allowed = new Set((ctx.brief?.examSubjects ?? []).map(item => item.name))
  allowed.add(REVIEW_SUBJECT)
  if (!subject) details.push({ field: 'subject', message: '科目不能为空' })
  else if (ctx.brief && ctx.brief.examSubjects.length > 0 && !allowed.has(subject)) {
    details.push({ field: 'subject', message: `科目不在已确认的考试科目中:${subject}` })
  }
  if (!title) details.push({ field: 'title', message: '任务标题不能为空' })
  if (title.length > 64) details.push({ field: 'title', message: '任务标题最多 64 字' })
  if (!Number.isInteger(input.minutes) || input.minutes < 5 || input.minutes > 480) {
    details.push({ field: 'minutes', message: '任务时长需在 5..480 分钟' })
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(input.planDate)) {
    details.push({ field: 'planDate', message: '日期格式应为 YYYY-MM-DD' })
  } else if (input.planDate < ctx.planStart || input.planDate >= ctx.examDate) {
    details.push({ field: 'planDate', message: `日期需在 ${ctx.planStart} 与考前一天之间` })
  } else if (!ctx.stages.some(stage => {
    const start = dateOnly(stage.startDate)
    const end = dateOnly(stage.endDate)
    return input.planDate >= start && input.planDate <= end
  })) {
    details.push({ field: 'planDate', message: '日期不在任何阶段内' })
  }
  if (details.length > 0) throw new ApiError(400, 'INVALID_PARAMS', '计划项内容不合法', { fields: details })

  const capacity = dailyCapacity(ctx.brief, input.planDate)
  const planned = itemDayLoad(ctx.items, input.planDate, ctx.excludeId) + input.minutes
  // 口径还没确认空闲时段时容量是未知值,不用 0 去堵住手工编辑
  if (ctx.brief?.availabilityConfirmed && planned > capacity) {
    const overflow = planned - capacity
    throw new ApiError(409, 'PLAN_DAY_OVERLOADED', `${input.planDate} 当天容量 ${capacity} 分钟,已排 ${planned} 分钟,超出 ${overflow} 分钟`, {
      date: input.planDate,
      capacity,
      planned,
      overflow,
      suggestion: findCapacitySuggestion(ctx.items, ctx.brief, input.planDate, input.minutes, ctx.excludeId),
    })
  }
  return { dayCapacity: capacity, planned }
}

/** 手工编辑后若超过建议强度(净空闲 × 负载系数),用一句提醒让用户自己决定,不拦 */
function loadWarnings(brief: PlanBrief | null, day: string, planned: number): string[] {
  if (!brief || !brief.availabilityConfirmed) return []
  const cap = Math.floor(dailyCapacity(brief, day) * PLAN_LOAD_FACTOR)
  return planned > cap && cap > 0 ? [`${day} 已排 ${planned} 分钟,超过建议强度 ${cap} 分钟`] : []
}

// ---------- 调整方案:手工方案、任务消歧与影响计算 ----------

function dayLoads(items: WindowSnapshotItem[]): Map<string, number> {
  const loads = new Map<string, number>()
  for (const item of items) loads.set(item.planDate, (loads.get(item.planDate) ?? 0) + item.minutes)
  return loads
}

function computeImpact(
  before: WindowSnapshotItem[], after: WindowSnapshotItem[],
  changes: ReturnType<typeof diffSnapshots>, capacity?: Map<string, number>,
): AdjustmentImpact {
  const affected = new Set<string>()
  for (const change of changes) {
    if (change.from) affected.add(change.from.planDate)
    if (change.to) affected.add(change.to.planDate)
  }
  const loads = dayLoads(after)
  let maxDayLoad = 0
  let maxDayCapacity = 0
  for (const [day, load] of loads) {
    if (load > maxDayLoad) {
      maxDayLoad = load
      maxDayCapacity = capacity?.get(day) ?? 0
    }
  }
  let capacityTotal = 0
  for (const day of new Set(after.map(item => item.planDate))) capacityTotal += capacity?.get(day) ?? 0
  return {
    affectedDays: affected.size,
    movedCount: changes.filter(change => change.kind === 'moved').length,
    addedCount: changes.filter(change => change.kind === 'added').length,
    removedCount: changes.filter(change => change.kind === 'removed').length,
    totalMinutesBefore: before.reduce((sum, item) => sum + item.minutes, 0),
    totalMinutesAfter: after.reduce((sum, item) => sum + item.minutes, 0),
    maxDayLoad,
    maxDayCapacity,
    shortfallMinutes: Math.max(0, after.reduce((sum, item) => sum + item.minutes, 0) - capacityTotal),
    milestoneRiskCount: 0,
    lockedKeptCount: after.filter(item => item.locked === true && item.status !== 'done').length,
  }
}

function manualOption(
  id: AdjustmentOption['id'], label: string, summary: string, tier: AdjustmentTier,
  before: WindowSnapshotItem[], after: WindowSnapshotItem[], capacity?: Map<string, number>,
  profileAfter?: string,
): AdjustmentOption | null {
  const changes = diffSnapshots(before, after)
  if (changes.length === 0 && !profileAfter) return null
  return {
    id, label, summary, tier,
    after: [...after].sort((a, b) => a.planDate.localeCompare(b.planDate) || a.id - b.id),
    changes,
    impact: computeImpact(before, after, changes, capacity),
    ...(profileAfter ? { profileAfter } : {}),
  }
}

/** 把意图里的任务描述消歧到窗口内 pending 项;subject 唯一时允许按科锁定 */
function resolveTaskRefs(
  items: any[], refs: AdjustIntent['tasks'], options: { unique: boolean },
): { rows: any[]; missing: string[]; ambiguous: string[] } {
  const matched = new Set<number>()
  const missing: string[] = []
  const ambiguous: string[] = []
  for (const ref of refs) {
    const label = `${ref.subject}${ref.title ? `「${ref.title}」` : ''}${ref.date ? ` ${ref.date}` : ''}`
    let candidates = items.filter(row => row.status !== 'done' && String(row.subject ?? '') === ref.subject)
    if (ref.title) {
      candidates = candidates.filter(row => {
        const title = String(row.title ?? '')
        return title === ref.title || title.includes(ref.title!)
      })
    }
    if (ref.date) candidates = candidates.filter(row => dateOnly(row.planDate) === ref.date)
    if (candidates.length === 0) {
      missing.push(label)
      continue
    }
    if (candidates.length > 1 && (options.unique || ref.title || ref.date)) {
      ambiguous.push(label)
      continue
    }
    for (const row of candidates) matched.add(Number(row.id))
  }
  return { rows: items.filter(row => matched.has(Number(row.id))), missing, ambiguous }
}

/** 新增任务塞进方案后重新算影响;目标日放不下就淘汰该方案 */

/** L1 的挪动结果写回快照:只改 pending 项的日期,其余字段原样保留 */
function applyMoves(before: WindowSnapshotItem[], moves: Array<{ item: AdjustableItem; to: string }>): WindowSnapshotItem[] {
  const movedById = new Map(moves.map(move => [move.item.id, move.to]))
  return before.map(item => {
    const nextDay = item.status !== 'done' && item.locked !== true ? movedById.get(item.id) : undefined
    return nextDay ? { ...item, planDate: nextDay } : item
  })
}

/** after 快照里超过当天净空闲的日期(done/locked 也计入占用) */
function overloadedDays(
  after: WindowSnapshotItem[], capacity: Map<string, number>, from: string, to: string,
): Array<{ date: string; load: number; capacity: number }> {
  const loads = dayLoads(after)
  const result: Array<{ date: string; load: number; capacity: number }> = []
  for (const [day, load] of loads) {
    if (day < from || day > to) continue
    const cap = capacity.get(day) ?? 0
    if (load > cap) result.push({ date: day, load, capacity: cap })
  }
  return result.sort((a, b) => a.date.localeCompare(b.date))
}

/**
 * 目标日超容量时的确定性压缩:只压缩给定日期上的未锁定待办,单条最低 15 分钟。
 * 用于 move/add 这类「用户指定了目标日」的场景,不动其它日期;压不到位返回 null。
 */
function compressDaysToCapacity(
  after: WindowSnapshotItem[], capacity: Map<string, number>, days: string[],
): WindowSnapshotItem[] | null {
  if (days.length === 0) return null
  const result = after.map(item => ({ ...item }))
  let changed = false
  for (const day of new Set(days)) {
    const onDay = result.filter(item => item.planDate === day)
    const fixed = onDay
      .filter(item => item.status === 'done' || item.locked === true)
      .reduce((sum, item) => sum + item.minutes, 0)
    const movable = onDay.filter(item => item.status !== 'done' && item.locked !== true)
    if (movable.length === 0) continue
    const available = Math.max(0, (capacity.get(day) ?? 0) - fixed)
    const total = movable.reduce((sum, item) => sum + item.minutes, 0)
    if (total <= available) continue
    changed = true
    let scale = available / total
    let placed = false
    for (let attempt = 0; attempt < 20 && !placed; attempt++) {
      let scaled = 0
      for (const item of movable) {
        item.minutes = Math.max(15, Math.round(item.minutes * scale))
        scaled += item.minutes
      }
      if (scaled <= available) placed = true
      else scale = (available / scaled) * 0.95
    }
    if (!placed) return null
  }
  return changed ? result : null
}

/**
 * 新增任务放不下时,把新增项顺延到窗口内最近放得下的日期:先向后找,再向前找。
 * 只改新增项日期,原有排布保持不动;任意一条找不到空位返回 null。
 */
function shiftAdditionsToFit(
  before: WindowSnapshotItem[], additions: WindowSnapshotItem[],
  capacity: Map<string, number>, days: string[],
): WindowSnapshotItem[] | null {
  if (additions.length === 0) return before
  const loads = dayLoads(before)
  const placed: WindowSnapshotItem[] = []
  for (const addition of additions) {
    const index = days.indexOf(addition.planDate)
    if (index < 0) return null
    const order = [...days.slice(index), ...days.slice(0, index).reverse()]
    const day = order.find(candidate =>
      (loads.get(candidate) ?? 0) + addition.minutes <= (capacity.get(candidate) ?? 0))
    if (!day) return null
    loads.set(day, (loads.get(day) ?? 0) + addition.minutes)
    placed.push({ ...addition, planDate: day })
  }
  return [...before, ...placed]
}

/** adjust_scope 的确定性兜底:按「非弱科 → 低优先级 → 无里程碑 → 日期更晚」删到目标分钟内 */
function trimToTarget(
  before: WindowSnapshotItem[], targetMinutes: number, weakSubjects: string[], milestones: Map<string, string>,
): WindowSnapshotItem[] {
  const open = before.filter(item => item.status !== 'done' && item.locked !== true)
  let total = open.reduce((sum, item) => sum + item.minutes, 0)
  if (total <= targetMinutes) return before
  const weak = new Set(weakSubjects)
  const ordered = [...open].sort((a, b) => {
    const weakA = weak.has(a.subject) ? 1 : 0
    const weakB = weak.has(b.subject) ? 1 : 0
    if (weakA !== weakB) return weakA - weakB
    const priorityA = a.priority ?? 0
    const priorityB = b.priority ?? 0
    if (priorityA !== priorityB) return priorityB - priorityA
    const milestoneA = milestones.has(a.subject) ? 1 : 0
    const milestoneB = milestones.has(b.subject) ? 1 : 0
    if (milestoneA !== milestoneB) return milestoneA - milestoneB
    if (a.planDate !== b.planDate) return b.planDate.localeCompare(a.planDate)
    return (b.sortOrder ?? 0) - (a.sortOrder ?? 0) || b.id - a.id
  })
  const removed = new Set<number>()
  for (const item of ordered) {
    if (total <= targetMinutes) break
    removed.add(item.id)
    total -= item.minutes
  }
  return before.filter(item => !removed.has(item.id))
}

/** 多方案时把延迟档案口径挂到每条方案上;lower_target 自带的档案变更优先 */
function attachProfile(options: AdjustmentOption[], profileAfter?: string): AdjustmentOption[] {
  if (!profileAfter) return options
  return options.map(option => option.profileAfter ? option : { ...option, profileAfter })
}

/** 调整单落库:同一用户同一时刻最多一张 draft,已有就原地覆盖(spec §6) */
async function saveAdjustmentDraft(db: PlanningDb, args: {
  userGuid: string
  planRow: any
  reason: string
  summary: string
  windowFrom: string
  windowTo: string
  before: WindowSnapshotItem[]
  options: AdjustmentOption[]
  briefBefore: PlanBrief
  profileBefore?: string
  profileAfter?: string
}): Promise<any> {
  const recommended = args.options[0]
  if (!recommended) throw new ApiError(409, 'PLAN_CAPACITY_INSUFFICIENT', '没有可用的调整方案')
  const tier = args.options.length >= 2 || args.options.some(option => option.tier === 'L2') ? 'L2' : 'L1'
  const payload: Record<string, unknown> = {
    userGuid: args.userGuid,
    planId: args.planRow.id,
    tier,
    status: 'draft',
    reason: args.reason,
    summary: args.summary,
    windowFrom: new Date(`${args.windowFrom}T00:00:00.000Z`),
    windowTo: new Date(`${args.windowTo}T00:00:00.000Z`),
    beforeJson: JSON.stringify(args.before),
    afterJson: JSON.stringify(recommended.after),
    optionsJson: JSON.stringify(args.options),
    selectedOption: recommended.id,
    fingerprint: windowFingerprint(args.before, briefContextHash(args.briefBefore, dateOnly(args.planRow.examDate))),
    profileBeforeJson: args.profileBefore ?? null,
    profileAfterJson: args.profileAfter ?? null,
    afterFingerprint: null,
    appliedAt: null,
    undoneAt: null,
  }
  const existing = await db.planAdjustment.findFirst({ where: { userGuid: args.userGuid, status: 'draft' } })
  if (existing) {
    await db.planAdjustment.updateMany({ where: { id: existing.id }, data: payload })
    return db.planAdjustment.findFirst({ where: { id: existing.id } })
  }
  return db.planAdjustment.create({ data: payload })
}

function serializeAdjustment(row: any): PublicAdjustment {
  const before = jsonList<WindowSnapshotItem>(row.beforeJson)
  const after = jsonList<WindowSnapshotItem>(row.afterJson)
  const storedOptions = jsonList<any>(row.optionsJson)
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
    // 只有多方案才下发 options;单方案/旧调整单保持 null,旧客户端不受影响
    options: storedOptions.length >= 2
      ? storedOptions.map(option => ({
          id: String(option.id ?? ''),
          label: String(option.label ?? ''),
          summary: String(option.summary ?? ''),
          tier: String(option.tier ?? ''),
          impact: option.impact && typeof option.impact === 'object' ? option.impact : {},
          changes: Array.isArray(option.changes) ? option.changes : [],
        }))
      : null,
    selectedOption: row.selectedOption ?? null,
    createdAt: ms(row.createdAt),
    appliedAt: ms(row.appliedAt),
  }
}

/**
 * L2 重排:模型给相对序列 → 服务端展开校验。校验失败把原因回喂重试一次,仍失败抛
 * PLAN_GENERATION_FAILED(502);调用方会降级到确定性方案引擎,不把错误直接甩给用户。
 * 整个过程在事务外、落库前,失败天然不产生任何数据。
 */
async function runAdjustL2Plan(ai: PlanningAi, args: {
  message: string
  pending: AdjustableItem[]
  capacity: Map<string, number>
  from: string
  to: string
  brief: PlanBrief
  mode?: L2Mode
  allowedDelta?: number
  locked?: WindowSnapshotItem[]
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
  const mode = args.mode ?? 'conserve'
  const run = (message: string) => ai.adjustL2!({
    message, windowFrom: args.from, windowTo: args.to, ledger, pending: args.pending, mode,
  }).then(tasks => normalizeL2Tasks(tasks, ledger))

  let tasks = await run(args.message)
  let target = expandL2(tasks, args.from, args.pending)
  let verdict = validateL2(target, ledger, { mode, allowedDelta: args.allowedDelta, locked: args.locked })
  if (!verdict.ok) {
    // 回喂一次:把失败原因告诉模型,多数情况一次就能收敛
    tasks = await run(`${args.message}\n(上一次重排没通过:${verdict.reason};请修正后重新输出完整方案)`)
    target = expandL2(tasks, args.from, args.pending)
    verdict = validateL2(target, ledger, { mode, allowedDelta: args.allowedDelta, locked: args.locked })
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
    // 缺键 = 老客户端没提供,保留旧锚点;空串 = 用户在问卷里清空,真的清掉
    termWeekOneStart: input.termWeekOneStart === undefined
      ? (brief.termWeekOneStart ?? '')
      : input.termWeekOneStart,
    commitmentsConfirmed: brief.commitmentsConfirmed || input.commitmentsConfirmed,
  }
}

/** 从档案行读出简报(坏数据当空简报),供问卷合并 / 面谈上下文 / 课表识别复用 */
function briefFromRow(row: any): PlanBrief {
  const raw = jsonObject(row?.briefJson)
  return raw ? normalizeBrief(raw) : normalizeBrief(null)
}

/** 调整单里延迟保存的简报 JSON → PlanBrief;坏数据当 null,不因一行脏数据把确认打挂 */
function briefFromJson(raw: unknown): PlanBrief | null {
  const obj = jsonObject(raw)
  return obj ? normalizeBrief(obj) : null
}

function cloneBrief(brief: PlanBrief): PlanBrief {
  return JSON.parse(JSON.stringify(brief)) as PlanBrief
}

/** change_commitment:把新固定占用并进简报(并集去重),确认前只改副本不写库 */
function withCommitments(brief: PlanBrief, commitments: BriefPatchInput['fixedCommitments']): PlanBrief {
  const next = cloneBrief(brief)
  const incoming = commitments ?? []
  const merged = [...next.fixedCommitments]
  for (const item of incoming) {
    // 去重键要含 parity:同一时段可以是「单周」和「双周」两条,只按时段+名称比会把其中一条吞掉。
    // 与 mergeBrief、课表上传合并保持同一套键
    if (!merged.some(existing => existing.weekday === item.weekday
      && existing.start === item.start && existing.end === item.end && existing.label === item.label
      && (existing.weekParity ?? '') === (item.weekParity ?? ''))) {
      merged.push(item)
    }
  }
  next.fixedCommitments = merged
  if (incoming.length > 0) next.commitmentsConfirmed = true
  return next
}

/** adjust_scope:按意图修改逐科剩余量与里程碑;未知科目忽略,由调用方决定是否追问 */
function withScope(brief: PlanBrief, scope: AdjustIntent['scope']): { brief: PlanBrief; applied: string[] } {
  const next = cloneBrief(brief)
  const applied: string[] = []
  for (const patch of scope) {
    const subject = next.examSubjects.find(item => item.name === patch.subject)
    if (!subject) continue
    if (patch.remainingMinutes !== undefined) subject.remainingMinutes = Math.max(0, Math.min(500000, patch.remainingMinutes))
    if (patch.milestone !== undefined) subject.milestone = String(patch.milestone).slice(0, 64)
    if (patch.milestoneDate !== undefined) subject.milestoneDate = patch.milestoneDate
    if (patch.milestoneMinutes !== undefined) {
      subject.milestoneMinutes = Math.max(0, Math.min(subject.remainingMinutes, patch.milestoneMinutes))
    }
    if (patch.estimated !== undefined) subject.estimated = patch.estimated
    applied.push(subject.name)
  }
  return { brief: next, applied }
}

/**
 * 落库前的最后一道闸:宁可在这里抛错让事务不开始,也不要写进一份自相矛盾的计划。
 * 检查项对应生成器的核心承诺 —— 阶段连续、计划项落在所属阶段区间内、每天不超预算。
 */
function assertGeneratedPlanValid(
  generated: GeneratedPlan,
  examDate: Date,
  brief: PlanBrief | null,
  budget?: PlanBudget | null,
  allowShortfall = false,
): void {
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

  if (budget) {
    const bySubject = new Map<string, number>()
    for (const item of generated.items) {
      bySubject.set(item.subject, (bySubject.get(item.subject) ?? 0) + item.minutes)
    }
    for (const subject of budget.subjects) {
      const scheduled = bySubject.get(subject.name) ?? 0
      if (scheduled > subject.upperMinutes + 1) {
        fail(`${subject.name} 排了 ${scheduled} 分钟,超过预算上限 ${subject.upperMinutes} 分钟`)
      }
      // 逐周缩到 15 分钟下限时可能有几档取整误差,留出一个档位再判欠排
      if (scheduled + 15 < subject.budgetMinutes) {
        fail(`${subject.name} 只排了 ${scheduled} 分钟,未覆盖目标 ${subject.budgetMinutes} 分钟`)
      }
      const before = generated.items
        .filter(item => item.subject === subject.name && dateOnly(item.planDate) <= subject.milestoneDate)
        .reduce((sum, item) => sum + item.minutes, 0)
      if (!subject.estimated && before + 1 < subject.milestoneBudgetMinutes) {
        fail(`${subject.name}「${subject.milestone}」在 ${subject.milestoneDate} 前差 ${subject.milestoneBudgetMinutes - before} 分钟`)
      }
    }
    const total = generated.items
      .filter(item => item.subject !== REVIEW_SUBJECT)
      .reduce((sum, item) => sum + item.minutes, 0)
    const review = generated.items
      .filter(item => item.subject === REVIEW_SUBJECT)
      .reduce((sum, item) => sum + item.minutes, 0)
    if (total > Math.floor(budget.targetMinutes * 1.05)) {
      fail(`总排量 ${total} 分钟超过目标 ${budget.targetMinutes} 分钟的 105%`)
    }
    // 复盘若本身被确认成一门科目,它的排量由上面的科目预算约束,不再吃 10% 上限
    const reviewBudgeted = budget.subjects.some(subject => subject.name === REVIEW_SUBJECT)
    if (!reviewBudgeted && review > Math.floor(budget.targetMinutes * 0.1) + 15) {
      fail(`复盘 ${review} 分钟超过目标 10%`)
    }
    void allowShortfall
  }
}

/**
 * 服务端依赖的 AI 能力。`interview` / `parseTimetable` 刻意是可选的:
 * 单测注入的假 AI 只需要 generate,不必为了跑通测试去 mock 一整套面谈与视觉识别。
 */
export interface PlanningAi {
  configured: () => boolean
  generate: (input: AiPlanningInput) => Promise<{ title: string; plan: GeneratedPlan; document?: PlanDocument | null; spec?: PlanSpec }>
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
  const generationJobs = new GenerationJobStore()

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

  /** 口径读取:简报 + 哈希 + 预算视图 + 每周容量,生成前确认页与预算表共用这一份数字 */
  async function readBriefPayload(userGuid: string) {
    const row = await requireProfileRow(userGuid)
    const brief = briefFromRow(row)
    const today = dayStart(new Date())
    const examDate = toDay(row.examDate)
    const budget = buildPlanBudget(brief, today, examDate)
    return {
      brief,
      briefHash: briefHash(brief, dateOnly(row.examDate)),
      profile: {
        targetType: row.targetType,
        examDate: dateOnly(row.examDate),
        foundation: row.foundation ?? null,
        weakSubjects: jsonArray(row.weakSubjectsJson),
      },
      budget: budgetView(budget),
      capacityByWeekday: capacityByWeekday(brief, today, examDate),
      warnings: budget.warnings,
    }
  }

  /** 生成前校验客户端确认的口径哈希;allowShortfall 必须与 briefHash 同时出现,避免旧客户端误触尽力版 */
  function assertGenerateOptions(profileRow: any, options: GenerateOptions): void {
    const hash = briefHash(briefFromRow(profileRow), dateOnly(profileRow.examDate))
    if (options.briefHash !== undefined && options.briefHash !== hash) {
      throw new ApiError(409, 'PLAN_BRIEF_CHANGED', '计划口径已更新,请重新确认', { briefHash: hash })
    }
    if (options.allowShortfall && options.briefHash === undefined) {
      throw new ApiError(400, 'INVALID_PARAMS', '生成尽力版必须同时确认计划口径')
    }
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
     * 最近一份未确认草稿:生成任务存在进程内存里,服务重启或超过 TTL 后 jobId 会失效,
     * 但草稿早已落库。客户端靠这个入口把「白等一次生成」的草稿捡回来。
     * 同一用户最多只有一份 draft(新生成会把旧草稿归档),取版本号最大的即可。
     */
    getLatestDraft: async (userGuid: string): Promise<PublicPlan | null> => {
      const row = await db.plan.findFirst({
        where: { userGuid, status: 'draft' },
        orderBy: { version: 'desc' },
      })
      return row ? loadPlan(userGuid, row) : null
    },

    /** 计划口径:确认页读取简报、预算与每周容量;生成请求带着 briefHash 回来证明用户看过这份口径 */
    async getBrief(userGuid: string) {
      return readBriefPayload(userGuid)
    },

    /** 手工改口径:逐科剩余量/里程碑与每周固定占用;计划生成中禁止改,避免生成用的口径和落库的不一致 */
    async patchBrief(userGuid: string, input: BriefPatchInput) {
      if (generationJobs.runningFor(userGuid)) {
        throw new ApiError(409, 'PLAN_GENERATION_RUNNING', '计划正在生成,完成后再改口径')
      }
      const row = await requireProfileRow(userGuid)
      const brief = briefFromRow(row)
      const examDate = dateOnly(row.examDate)
      const today = dateOnly(dayStart(new Date()))
      const details: Array<{ field: string; message: string }> = []
      const known = new Map(brief.examSubjects.map(subject => [subject.name, subject]))
      for (const patch of input.examSubjects ?? []) {
        const subject = known.get(patch.name)
        if (!subject) {
          details.push({ field: `examSubjects.${patch.name}`, message: '科目不在已确认的考试科目中' })
          continue
        }
        if (patch.remainingMinutes !== undefined) {
          if (!Number.isSafeInteger(patch.remainingMinutes) || patch.remainingMinutes < 0 || patch.remainingMinutes > 500000) {
            details.push({ field: `examSubjects.${patch.name}.remainingMinutes`, message: '剩余量需在 0..500000 分钟' })
          } else {
            subject.remainingMinutes = patch.remainingMinutes
            subject.milestoneMinutes = Math.min(subject.milestoneMinutes, subject.remainingMinutes)
          }
        }
        if (patch.milestone !== undefined) subject.milestone = String(patch.milestone).slice(0, 64)
        if (patch.milestoneDate !== undefined) {
          if (patch.milestoneDate < today || patch.milestoneDate >= examDate) {
            details.push({ field: `examSubjects.${patch.name}.milestoneDate`, message: `截止日需在 ${today} 与考前一天之间` })
          } else {
            subject.milestoneDate = patch.milestoneDate
          }
        }
        if (patch.milestoneMinutes !== undefined) {
          if (!Number.isSafeInteger(patch.milestoneMinutes) || patch.milestoneMinutes < 0 || patch.milestoneMinutes > subject.remainingMinutes) {
            details.push({ field: `examSubjects.${patch.name}.milestoneMinutes`, message: '里程碑量不能超过剩余量' })
          } else {
            subject.milestoneMinutes = patch.milestoneMinutes
          }
        }
        if (patch.estimated !== undefined) subject.estimated = patch.estimated
        if (patch.scope !== undefined) subject.scope = String(patch.scope).slice(0, 200)
        if (patch.progress !== undefined) subject.progress = String(patch.progress).slice(0, 200)
      }
      if (input.fixedCommitments !== undefined) {
        brief.fixedCommitments = input.fixedCommitments
        brief.commitmentsConfirmed = true
      }
      if (details.length > 0) throw new ApiError(400, 'INVALID_PARAMS', '计划口径有字段不合法', { fields: details })
      await db.userProfile.update({
        where: { userGuid },
        data: { briefJson: JSON.stringify(brief), updatedAt: new Date() },
      })
      return readBriefPayload(userGuid)
    },

    /** 草案/生效计划的预算摘要:预算表、日负荷、阶段目标与警告 */
    async getPlanOverview(userGuid: string, planId: number) {
      const row = await db.plan.findFirst({ where: { id: planId, userGuid, status: { in: ['draft', 'active'] } } })
      if (!row) return null
      const profile = await db.userProfile.findUnique({ where: { userGuid } })
      const brief = profile ? briefFromRow(profile) : null
      if (!brief) throw new ApiError(400, 'PLANNING_FACTS_INCOMPLETE', '缺少备考口径,无法生成预算摘要')
      const [stages, items] = await Promise.all([
        db.planStage.findMany({ where: { planId }, orderBy: { sortOrder: 'asc' } }),
        db.planItem.findMany({ where: { planId }, orderBy: [{ planDate: 'asc' }, { sortOrder: 'asc' }] }),
      ])
      return {
        planId,
        ...buildPlanOverview(
          {
            title: row.title, version: Number(row.version ?? 1),
            startDate: dateOnly(row.startDate), examDate: dateOnly(row.examDate), status: String(row.status ?? ''),
          },
          brief,
          stages.map(stage => ({ name: stage.name, startDate: dateOnly(stage.startDate), endDate: dateOnly(stage.endDate) })),
          items.map(item => ({
            subject: String(item.subject ?? ''), planDate: dateOnly(item.planDate),
            minutes: Number(item.minutes ?? 0), status: String(item.status ?? 'pending'),
          })),
        ),
      }
    },

    /** 长文档轻量读取:文档页只需要 document,不值得把全量 items(数百 KB)一起拉下来 */
    getActiveDocument: async (userGuid: string): Promise<PlanDocument | null> => {
      const row = await db.plan.findFirst({
        where: { userGuid, status: 'active' },
        orderBy: { version: 'desc' },
        select: { documentJson: true },
      })
      return row ? (jsonObject(row.documentJson) as PlanDocument | null) : null
    },

    /**
     * 补生成长文档:生成计划时文档部分失败(加分项不连累主计划)后,用当前生效计划
     * 已落库的阶段/任务重出一份文档。每日清单原样不动,只改写 documentJson。
     * 文档生成 prompt 需要 weekVariants 里的首周任务示例,而细化轮产物没有落库 ——
     * 这里直接从已排的计划项里取每阶段前 7 天的真实任务,比模型当时编的更贴近执行。
     */
    regenerateActiveDocument: async (userGuid: string): Promise<PlanDocument> => {
      if (!ai.configured()) {
        throw new ApiError(503, 'AI_NOT_CONFIGURED', 'AI 计划生成服务尚未配置,请稍后再试')
      }
      const row = await db.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
      if (!row) throw new ApiError(404, 'PLAN_NOT_FOUND', '没有生效中的计划')
      const [profileRow, stages, items] = await Promise.all([
        db.userProfile.findUnique({ where: { userGuid } }),
        db.planStage.findMany({ where: { planId: row.id }, orderBy: { sortOrder: 'asc' } }),
        db.planItem.findMany({ where: { planId: row.id }, orderBy: [{ planDate: 'asc' }, { sortOrder: 'asc' }] }),
      ])
      if (!profileRow) throw new ApiError(400, 'PROFILE_INCOMPLETE', '备考档案缺失,无法生成文档')

      const parsedProfile = profileInputSchema.safeParse({
        targetType: profileRow.targetType,
        examDate: toDay(profileRow.examDate),
        studyWindows: jsonArray(profileRow.studyWindowsJson),
        foundation: profileRow.foundation ?? '一般',
        weakSubjects: jsonArray(profileRow.weakSubjectsJson),
      })
      if (!parsedProfile.success) {
        throw new ApiError(400, 'INVALID_PARAMS', '备考档案不完整,请重新填写后再生成文档')
      }

      const briefRaw = jsonObject(profileRow.briefJson)
      const brief: PlanBrief | null = briefRaw ? normalizeBrief(briefRaw) : null
      // 有 spec 就交给文档派生器:阶段日期、各科分钟、首周安排直接取计划工件,
      // 保证「补生成」出来的文档与每日清单仍然一致。旧计划没有 spec 时退回原逻辑。
      const spec = row.specJson ? (jsonObject(row.specJson) as unknown as PlanSpec | null) : null
      const generatedStages: GeneratedStage[] = stages.map(stage => {
        const startDate = toDay(stage.startDate)
        const stageItems = items.filter(item => {
          const day = toDay(item.planDate)
          return day >= startDate && day < addDays(startDate, 7)
        })
        return {
          name: stage.name,
          strategy: stage.strategy ?? undefined,
          milestones: parseMilestones(stage.milestonesJson),
          startDate,
          endDate: toDay(stage.endDate),
          sortOrder: Number(stage.sortOrder ?? 0),
          // 首周任务示例取自真实计划项,文档第 2/3 章写作时据此对齐
          weekVariants: stageItems.length ? [stageItems.slice(0, 8).map(item => ({
            weekday: isoWeekdayOf(toDay(item.planDate)),
            subject: item.subject,
            title: item.title,
            minutes: Number(item.minutes ?? 0),
          }))] : undefined,
        }
      })

      let document: PlanDocument | null = null
      try {
        const generated = await generatePlanDocument({
          title: row.title,
          profile: parsedProfile.data as ProfileInput,
          brief,
          stages: generatedStages,
          startDate: toDay(row.startDate),
          spec,
        })
        if (generated && documentMatchesSubjects(
          generated,
          (brief?.examSubjects ?? []).map(subject => subject.name),
          row.targetType === '考研' ? KAOYAN_KNOWN_SUBJECT_NAMES : [],
        )) {
          document = generated
        }
      } catch (error) {
        throw new ApiError(502, 'PLAN_DOCUMENT_FAILED', `文档生成失败:${(error as Error)?.message ?? '未知错误'}`)
      }
      if (!document) {
        throw new ApiError(502, 'PLAN_DOCUMENT_FAILED', '这次生成的文档不完整,已放弃保存,请重试')
      }
      await db.plan.updateMany({
        where: { id: row.id, status: 'active' },
        data: { documentJson: JSON.stringify(document), updatedAt: new Date() },
      })
      return document
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

    async generatePlanDraft(userGuid: string, options: GenerateOptions = {}): Promise<PublicPlan> {
      return buildPlan(userGuid, undefined, options)
    },

    /**
     * 异步生成:立刻返回 jobId,后台跑完整条生成链路。
     * onUpdate 会在每次进度变化/结束/失败时被调用,路由用它推 WS 事件。
     * 同一用户已有运行中的任务时直接复用,避免连点生成产出多份草稿。
     * 口径哈希在创建 job 之前同步校验 —— 校验失败不该留下一个「正在生成」的假任务。
     */
    async startGeneration(
      userGuid: string, onUpdate?: (job: PlanGenerationJob) => void, options: GenerateOptions = {},
    ): Promise<{ jobId: string; job: PlanGenerationJob }> {
      const running = generationJobs.runningFor(userGuid)
      if (running) return { jobId: running.id, job: running }
      const profileRow = await requireProfileRow(userGuid)
      assertGenerateOptions(profileRow, options)
      const horizonDays = Math.max(0, diffDays(toDay(profileRow.examDate), dayStart(new Date())))
      const job = generationJobs.create(userGuid, horizonDays)
      const push = (patch: Partial<PlanGenerationJob>) => {
        const updated = generationJobs.update(job.id, patch)
        if (updated) onUpdate?.(updated)
      }
      void (async () => {
        try {
          const plan = await buildPlan(userGuid, progress => push({ progress }), options)
          push({ status: 'done', planId: plan.id, progress: { percent: 100, phase: 'done', message: '计划草稿已生成' } })
        } catch (error) {
          const code = error instanceof ApiError ? error.code : 'PLAN_GENERATION_FAILED'
          const message = error instanceof Error ? error.message : '计划生成失败'
          console.warn(`[planning] 异步生成失败(${code}):${message}`)
          push({ status: 'failed', error: { code, message }, progress: { percent: 100, phase: 'done', message: '生成失败' } })
        }
      })()
      return { jobId: job.id, job }
    },

    async getGenerationJob(userGuid: string, id: string): Promise<PlanGenerationJob | null> {
      return generationJobs.get(userGuid, id)
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
        // 与 mergeBrief / withCommitments 同一套去重键(含 parity):否则重复上传同一张课表时,
        // 「单周 A」和「双周 A」会被当成同一条,吞掉一条
        const added = result.fixedCommitments.filter(item => !known.some(existing => existing.weekday === item.weekday
          && existing.start === item.start && existing.end === item.end && existing.label === item.label
          && (existing.weekParity ?? '') === (item.weekParity ?? '')))
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
          data: { status: 'done', completedAt: new Date() },
        })
      } else {
        await db.planItem.updateMany({
          where: { id: itemId, planId: planRow.id },
          data: { status: 'pending', completedAt: null },
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
     * 手工编辑计划项:日期/时长/标题/科目/锁定任选其一或组合;单独传 status 等价于勾选。
     * done 项只允许通过 status 取消勾选,内容改动一律拒绝 —— 已打卡的任务被改日期会让历史失真。
     */
    async updatePlanItem(userGuid: string, itemId: number, input: PlanItemEditInput) {
      const hasContent = input.planDate !== undefined || input.minutes !== undefined
        || input.title !== undefined || input.subject !== undefined || input.locked !== undefined
      if (input.status !== undefined && hasContent) {
        throw new ApiError(400, 'INVALID_PARAMS', '勾选状态和任务内容不能在同一次请求里修改')
      }
      if (input.status === undefined && !hasContent) {
        throw new ApiError(400, 'INVALID_PARAMS', '至少修改一个字段')
      }
      const loaded = await loadEditableItem(db, userGuid, itemId)
      if (input.status !== undefined) {
        await applyItemStatus(db, loaded.plan.id, itemId, input.status)
        const plan = await loadPlan(userGuid, loaded.plan)
        return { plan, item: plan.items.find(item => item.id === itemId) ?? null, warnings: [] as string[] }
      }
      if (loaded.item.status === 'done') {
        throw new ApiError(409, 'PLAN_ITEM_NOT_EDITABLE', '已完成的任务不能改内容,先取消勾选再改')
      }

      const profileRow = await db.userProfile.findUnique({ where: { userGuid } })
      const brief = profileRow ? briefFromRow(profileRow) : null
      const result = await db.$transaction(async tx => {
        const { item, plan } = await loadEditableItem(tx, userGuid, itemId)
        if (item.status === 'done') {
          throw new ApiError(409, 'PLAN_ITEM_NOT_EDITABLE', '已完成的任务不能改内容,先取消勾选再改')
        }
        const [stages, items] = await Promise.all([
          tx.planStage.findMany({ where: { planId: plan.id }, orderBy: { sortOrder: 'asc' } }),
          tx.planItem.findMany({ where: { planId: plan.id } }),
        ])
        const oldDay = dateOnly(item.planDate)
        const next = {
          subject: input.subject ?? String(item.subject ?? ''),
          title: input.title ?? String(item.title ?? ''),
          planDate: input.planDate ?? oldDay,
          minutes: input.minutes ?? Number(item.minutes ?? 0),
          locked: input.locked ?? item.locked === true,
        }
        const { planned } = validateItemContent(next, {
          brief, planStart: dateOnly(plan.startDate), examDate: dateOnly(plan.examDate),
          stages, items, excludeId: itemId,
        })
        await tx.planItem.updateMany({
          where: { id: itemId, planId: plan.id, status: 'pending' },
          data: {
            subject: next.subject, title: next.title,
            planDate: new Date(`${next.planDate}T00:00:00.000Z`),
            minutes: next.minutes,
            locked: next.locked,
            stageId: stageIdFor(stages, next.planDate),
          },
        })
        await reflowDates(tx, plan.id, [oldDay, next.planDate], brief)
        await tx.plan.updateMany({ where: { id: plan.id }, data: { updatedAt: new Date() } })
        return { planId: Number(plan.id), day: next.planDate, planned }
      })
      const planRow = await db.plan.findFirst({ where: { id: result.planId } })
      if (!planRow) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划不存在')
      const plan = await loadPlan(userGuid, planRow)
      return { plan, item: plan.items.find(item => item.id === itemId) ?? null, warnings: loadWarnings(brief, result.day, result.planned) }
    },

    /** 手工新增计划项:默认加到当前生效计划,也可显式指定 draft/active;创建后立即重排当天时段 */
    async addPlanItem(userGuid: string, input: AddPlanItemInput) {
      const planRow = input.planId
        ? await db.plan.findFirst({ where: { id: input.planId, userGuid, status: { in: ['active', 'draft'] } } })
        : await db.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
      if (!planRow) throw new ApiError(404, 'PLAN_NOT_FOUND', '没有可编辑的计划')
      const profileRow = await db.userProfile.findUnique({ where: { userGuid } })
      const brief = profileRow ? briefFromRow(profileRow) : null
      const result = await db.$transaction(async tx => {
        const [stages, items] = await Promise.all([
          tx.planStage.findMany({ where: { planId: planRow.id }, orderBy: { sortOrder: 'asc' } }),
          tx.planItem.findMany({ where: { planId: planRow.id } }),
        ])
        const next = {
          subject: input.subject, title: input.title, planDate: input.planDate,
          minutes: input.minutes, locked: input.locked === true,
        }
        const { planned } = validateItemContent(next, {
          brief, planStart: dateOnly(planRow.startDate), examDate: dateOnly(planRow.examDate), stages, items,
        })
        const created = await tx.planItem.create({
          data: {
            planId: planRow.id,
            stageId: stageIdFor(stages, next.planDate),
            subject: next.subject.trim(),
            title: next.title.trim(),
            planDate: new Date(`${next.planDate}T00:00:00.000Z`),
            minutes: next.minutes,
            priority: 0,
            sortOrder: 999,
            status: 'pending',
            completedAt: null,
            locked: next.locked,
          },
        })
        await reflowDates(tx, planRow.id, [next.planDate], brief)
        await tx.plan.updateMany({ where: { id: planRow.id }, data: { updatedAt: new Date() } })
        return { itemId: Number(created.id), day: next.planDate, planned }
      })
      const plan = await loadPlan(userGuid, planRow)
      return {
        plan,
        item: plan.items.find(item => item.id === result.itemId) ?? null,
        warnings: loadWarnings(brief, result.day, result.planned),
      }
    },

    /** 删除未完成的计划项;done 项是历史记录,不允许删(需要的话先取消勾选) */
    async deletePlanItem(userGuid: string, itemId: number): Promise<PublicPlan> {
      const result = await db.$transaction(async tx => {
        const { item, plan } = await loadEditableItem(tx, userGuid, itemId)
        if (item.status === 'done') {
          throw new ApiError(409, 'PLAN_ITEM_NOT_EDITABLE', '已完成的任务不能删除,先取消勾选再删')
        }
        const day = dateOnly(item.planDate)
        await tx.planItem.deleteMany({ where: { id: itemId, planId: plan.id } })
        const profileRow = await tx.userProfile.findUnique({ where: { userGuid } })
        await reflowDates(tx, plan.id, [day], profileRow ? briefFromRow(profileRow) : null)
        await tx.plan.updateMany({ where: { id: plan.id }, data: { updatedAt: new Date() } })
        return Number(plan.id)
      })
      const planRow = await db.plan.findFirst({ where: { id: result } })
      if (!planRow) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划不存在')
      return loadPlan(userGuid, planRow)
    },

    /**
     * 行程调整入口:一句话描述突发情况 → 模型解析意图 → 服务端硬规则定档(L1 顺延/确定性方案/L2 语义重排)
     * → 产出待确认调整单。确认前零副作用:change_commitment/adjust_scope 只改副本,确认时才落档案。
     */
    async adjust(userGuid: string, input: { message: string; history?: Array<{ role: 'user' | 'assistant'; content: string }> }): Promise<{
      adjustment: PublicAdjustment | null
      reply: string
      plan: PublicPlan | null
      changedPlan?: boolean
      clarifyOptions?: Array<{ label: string; value: string }>
    }> {
      const message = String(input?.message ?? '').trim()
      if (!message) throw new ApiError(400, 'INVALID_PARAMS', '请说一说发生了什么')
      // 简短确认(「对」「嗯」)只有对着上一轮追问才读得懂;历史由客户端全量携带,这里只做形状清洗
      const history = Array.isArray(input?.history)
        ? input.history
            .filter(item => item && (item.role === 'user' || item.role === 'assistant') && typeof item.content === 'string')
            .map(item => ({ role: item.role, content: item.content.slice(0, 800) }))
            .slice(-8)
        : undefined
      const planRow = await db.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
      if (!planRow) throw new ApiError(404, 'PLAN_NOT_FOUND', '当前没有生效中的计划')
      if (!ai.adjustIntent || !ai.configured()) {
        throw new ApiError(503, 'AI_NOT_CONFIGURED', '服务端还没有配置大模型,暂时无法理解你的描述')
      }

      const profileRow = await db.userProfile.findUnique({ where: { userGuid } })
      const briefBefore = profileRow ? briefFromRow(profileRow) : normalizeBrief(null)
      const planStart = dateOnly(planRow.startDate)
      const examDate = dateOnly(planRow.examDate)
      const today = dayStart(new Date()).toISOString().slice(0, 10)
      const allItems = await db.planItem.findMany({ where: { planId: planRow.id } })
      const unfinishedDates = [...new Set(allItems.filter(row => row.status !== 'done').map(row => dateOnly(row.planDate)))]
      const window = adjustWindow({ planStart, examDate, today, unfinishedDates })
      if (!window) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划的调整窗口已结束')
      const { from, to } = window
      const inWindow = allItems.filter(row => {
        const day = dateOnly(row.planDate)
        return day >= from && day <= to
      })
      const before = toSnapshot(inWindow)

      // 意图解析上下文:窗口内任务摘要 + 预算余量,模型据此消歧「数学那项」这类说法
      const subjects = [...new Set([...briefBefore.examSubjects.map(subject => subject.name),
        ...allItems.map(row => String(row.subject)).filter(Boolean)])]
      const budgetBefore = buildPlanBudget(briefBefore, new Date(`${planStart}T00:00:00.000Z`), new Date(`${examDate}T00:00:00.000Z`))
      let intent: AdjustIntent
      try {
        intent = await ai.adjustIntent({
          message, history, today, windowTo: to, subjects, brief: briefBefore,
          windowItems: inWindow.slice(0, 60).map(row => ({
            planDate: dateOnly(row.planDate), subject: String(row.subject ?? ''),
            title: String(row.title ?? ''), minutes: Number(row.minutes ?? 0), locked: row.locked === true,
          })),
          budget: {
            targetMinutes: budgetBefore.targetMinutes,
            scheduledMinutes: allItems.filter(row => row.status !== 'done')
              .reduce((sum, row) => sum + Number(row.minutes ?? 0), 0),
          },
        })
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error)
        console.warn(`[planning] 调整意图解析失败:${reason}`)
        throw new ApiError(502, 'PLAN_INTERVIEW_FAILED', `这句没听懂:${reason}`)
      }

      const clarify = (reply: string) => ({
        adjustment: null, reply, plan: null, clarifyOptions: intent.clarifyOptions,
      })

      // 追问 / 闲聊:只回话,不产调整单
      if (intent.needClarify || intent.kind === 'chat') {
        return clarify(intent.needClarify ? (intent.clarifyQuestion || intent.summary) : intent.summary)
      }

      // 延迟写档案:change_commitment 合并固定占用,adjust_scope 调整剩余量/里程碑;
      // 确认前只改副本,容量计算用 briefAfter,保证结果与确认后的口径一致
      let briefAfter = briefBefore
      if (intent.kind === 'change_commitment') {
        if (intent.commitments.length === 0) return clarify('这次的固定占用我没听清,能说具体是周几几点到几点吗?')
        briefAfter = withCommitments(briefBefore, intent.commitments)
      } else if (intent.kind === 'adjust_scope') {
        if (intent.scope.length === 0) return clarify('想调整哪一科的目标?比如「数学剩余量降到 2000 分钟」')
        const scoped = withScope(briefBefore, intent.scope)
        if (scoped.applied.length === 0) return clarify(`没找到要调整的科目:${intent.scope.map(item => item.subject).join('、')}`)
        briefAfter = scoped.brief
      }
      const briefChanged = JSON.stringify(briefAfter) !== JSON.stringify(briefBefore)
      const deferredProfileAfter = briefChanged ? JSON.stringify(briefAfter) : undefined

      // 每日容量:优先按调整后的口径(新增固定占用会直接压缩净空闲)
      const capacity = new Map<string, number>()
      for (const day of datesBetween(from, to)) capacity.set(day, dailyCapacity(briefAfter, day))
      if (intent.kind === 'unavailable') {
        for (const day of intent.days) if (capacity.has(day)) capacity.set(day, 0)
      } else if (intent.kind === 'reduce_capacity') {
        if (intent.windows.length === 0) {
          // 用户只说「时间变少」没说哪天:按今明后三天各降 30% 估
          for (const day of datesBetween(from, shiftDate(today, 2))) {
            const base = capacity.get(day) ?? 0
            if (base > 0) capacity.set(day, Math.round(base * 0.7))
          }
        } else {
          for (const windowDay of intent.windows) {
            if (!capacity.has(windowDay.day)) continue
            capacity.set(windowDay.day, windowDay.minutes <= 0 ? 0 : Math.min(capacity.get(windowDay.day) ?? 0, windowDay.minutes))
          }
        }
      }

      const doneSnapshots = before.filter(item => item.status === 'done')
      const lockedSnapshots = before.filter(item => item.status !== 'done' && item.locked === true)
      // L1/L2 的容量是「可自由安排的剩余量」:先扣掉打卡与锁定占用
      const free = new Map(capacity)
      for (const item of [...doneSnapshots, ...lockedSnapshots]) {
        free.set(item.planDate, Math.max(0, (free.get(item.planDate) ?? 0) - item.minutes))
      }
      const openRows = inWindow.filter(row => row.status !== 'done' && row.locked !== true)
      const open = openRows.map(row => toAdjustable(row))
      const milestones = briefAfter.examSubjects
        .filter(subject => subject.milestoneDate && subject.milestoneMinutes > 0)
        .map(subject => ({ subject: subject.name, date: subject.milestoneDate }))
      const weakSubjects = jsonArray(profileRow?.weakSubjectsJson).map(String)
      const dailyCapFor = (day: string): number => {
        const base = dailyCapacity(briefAfter, day)
        const used = allItems
          .filter(row => dateOnly(row.planDate) === day)
          .reduce((sum, row) => sum + Number(row.minutes ?? 0), 0)
        return Math.max(0, base - used)
      }
      const optionsInput = {
        before, windowFrom: from, windowTo: to, examDate, capacity, dailyCapacity: dailyCapFor,
        brief: briefAfter, intentKind: intent.kind, weakSubjects,
      }
      const profileBeforeFor = (options: AdjustmentOption[]) =>
        options.some(option => option.profileAfter) ? JSON.stringify(briefBefore) : undefined
      const finishSingle = async (option: AdjustmentOption, reply: string) => {
        const row = await saveAdjustmentDraft(db, {
          userGuid, planRow, reason: intent.note || message, summary: intent.summary || option.summary,
          windowFrom: from, windowTo: to, before, options: [option], briefBefore,
          profileBefore: profileBeforeFor([option]),
          profileAfter: option.profileAfter,
        })
        return { adjustment: serializeAdjustment(row), reply, plan: null }
      }
      const finishMulti = async (options: AdjustmentOption[], reply: string) => {
        const row = await saveAdjustmentDraft(db, {
          userGuid, planRow, reason: intent.note || message,
          summary: intent.summary || `${options.length} 种处理方式`,
          windowFrom: from, windowTo: to, before, options, briefBefore,
          profileBefore: profileBeforeFor(options),
          profileAfter: options[0]?.profileAfter,
        })
        return { adjustment: serializeAdjustment(row), reply, plan: null }
      }
      const noOptions = (overload: Array<{ date: string; load: number; capacity: number }>) => {
        if (overload.length > 0) {
          throw new ApiError(409, 'LOCKED_ITEMS_CONFLICT',
            `${overload[0].date} 已经排了 ${overload[0].load} 分钟,其中包含锁定/已完成任务;先解锁或换一天再调整`, { days: overload })
        }
        throw new ApiError(409, 'PLAN_CAPACITY_INSUFFICIENT',
          `到 ${to} 之前都放不下这些任务,换几天休息时间或先删减任务再试`, { windowTo: to })
      }

      // 锁定:用户明确指令,直接写锁(确认前零副作用的唯一例外),不产调整单
      if (intent.kind === 'lock_task') {
        const resolved = resolveTaskRefs(inWindow, intent.tasks, { unique: false })
        if (resolved.rows.length === 0) return clarify('想锁定哪一项?可以说科目或任务标题')
        if (resolved.ambiguous.length > 0) return clarify(`「${resolved.ambiguous[0]}」对应多条任务,说个日期或标题我再锁`)
        const ids = resolved.rows.map(row => Number(row.id))
        const days = [...new Set(resolved.rows.map(row => dateOnly(row.planDate)))]
        await db.$transaction(async tx => {
          await tx.planItem.updateMany({ where: { id: { in: ids }, planId: planRow.id }, data: { locked: true } })
          await reflowDates(tx, planRow.id, days, briefAfter)
          await tx.plan.updateMany({ where: { id: planRow.id }, data: { updatedAt: new Date() } })
        })
        const plan = await loadPlan(userGuid, planRow)
        return {
          adjustment: null,
          reply: `已锁定 ${ids.length} 项任务,之后的 AI 调整不会再动它们;想解锁在计划项里改。`,
          plan, changedPlan: true,
        }
      }

      // 挪动:先尝试直接移动单条,不触发整体重排;目标日放不下再进方案引擎
      if (intent.kind === 'move_task') {
        if (intent.tasks.length === 0) return clarify('想把哪项任务挪到哪天?')
        const moves: Array<{ row: any; targetDate: string }> = []
        const used = new Set<number>()
        for (const ref of intent.tasks) {
          if (!ref.targetDate) return clarify('想把这项挪到哪一天?')
          if (ref.targetDate < from || ref.targetDate > to || ref.targetDate >= examDate) {
            return clarify(`${ref.targetDate} 不在这次可调整的日期范围(${from} 至 ${to})内`)
          }
          const pool = inWindow.filter(row => row.status !== 'done' && !used.has(Number(row.id)))
          const one = resolveTaskRefs(pool, [ref], { unique: true })
          if (one.rows.length !== 1) {
            return clarify(`没找到唯一对应的任务:${ref.subject}${ref.title ? `「${ref.title}」` : ''};说个日期或标题我再挪`)
          }
          const row = one.rows[0]
          if (row.locked === true) {
            throw new ApiError(409, 'LOCKED_ITEMS_CONFLICT', `「${row.title}」是锁定任务,先解锁再调整`)
          }
          used.add(Number(row.id))
          if (dateOnly(row.planDate) !== ref.targetDate) moves.push({ row, targetDate: ref.targetDate })
        }
        if (moves.length === 0) return clarify('目标日期和现在一样,换个日期试试?')
        const movedById = new Map(moves.map(move => [Number(move.row.id), move.targetDate]))
        const after = before.map(item => movedById.has(item.id) ? { ...item, planDate: movedById.get(item.id)! } : item)
        const overload = overloadedDays(after, capacity, from, to)
        if (overload.length === 0) {
          const option = manualOption('manual', '按你的要求挪动',
            `把 ${moves.length} 项任务挪到你指定的日期`, 'L1', before, after, capacity, deferredProfileAfter)
          if (!option) return clarify('这些任务本来就在目标日期上')
          return finishSingle(option, '已按你说的挪好,确认后生效。')
        }
        // 目标日放不下:只压缩目标日上的可动任务,不替用户把任务挪去别的日期
        const compressed = compressDaysToCapacity(after, capacity, overload.map(day => day.date))
        if (compressed) {
          const option = manualOption('compress', '压缩目标日任务',
            '目标日超出可用时间,把当天任务压缩到能排下的程度', 'L2',
            before, compressed, capacity, deferredProfileAfter)
          if (option) return finishSingle(option, '目标日期放不下,已帮你把当天任务压缩到可用时间内,确认后生效。')
        }
        noOptions(overload)
      }

      // 新增:直接构造 after;容量通过给单方案,放不下把新增项塞进各方案再比较
      if (intent.kind === 'add_task') {
        if (intent.newTasks.length === 0) return clarify('想加什么任务?说一下科目、内容和大概时长')
        const allowedSubjects = new Set([...briefAfter.examSubjects.map(subject => subject.name), REVIEW_SUBJECT,
          ...allItems.map(row => String(row.subject))])
        const additions: WindowSnapshotItem[] = []
        for (const [index, task] of intent.newTasks.entries()) {
          if (!allowedSubjects.has(task.subject)) return clarify(`科目「${task.subject}」不在现有计划里,先确认科目名`)
          if (!task.title.trim()) return clarify('新任务需要有个标题')
          if (!Number.isFinite(task.minutes) || task.minutes < 5 || task.minutes > 480) return clarify('新增任务时长需在 5..480 分钟')
          if (task.date < from || task.date > to || task.date >= examDate) {
            return clarify(`${task.date} 不在这次可调整的日期范围(${from} 至 ${to})内`)
          }
          additions.push({
            id: -(index + 1), subject: task.subject.trim(), title: task.title.trim().slice(0, 64),
            planDate: task.date, minutes: task.minutes, status: 'pending', locked: false,
            startTime: null, slot: null, priority: 0, sortOrder: 999,
          })
        }
        const after = [...before, ...additions]
        const overload = overloadedDays(after, capacity, from, to)
        if (overload.length === 0) {
          const total = additions.reduce((sum, item) => sum + item.minutes, 0)
          const option = manualOption('manual', '按你的要求新增', `新增 ${additions.length} 项任务(${total} 分钟)`,
            'L1', before, after, capacity, deferredProfileAfter)
          if (!option) return clarify('这条任务已经在计划里了')
          return finishSingle(option, '已加进计划,确认后生效。')
        }
        // 目标日放不下:先压缩当天既有任务,再把新增任务顺延到最近放得下的日期
        const options: AdjustmentOption[] = []
        const compressed = compressDaysToCapacity(after, capacity, overload.map(day => day.date))
        if (compressed) {
          const option = manualOption('compress', '压缩当天任务',
            `把 ${overload.map(day => day.date).join('、')} 的任务压缩后容纳新增`, 'L2',
            before, compressed, capacity, deferredProfileAfter)
          if (option) options.push(option)
        }
        const shifted = shiftAdditionsToFit(before, additions, capacity, [...capacity.keys()])
        if (shifted) {
          const option = manualOption('spill', '顺延新增任务',
            '把新增任务放到最近放得下的日期', 'L1', before, shifted, capacity, deferredProfileAfter)
          if (option) options.push(option)
        }
        if (options.length === 0) noOptions(overload)
        if (options.length === 1) return finishSingle(options[0], '目标日放不下,已调整安排,确认后生效。')
        return finishMulti(options, '要加的任务和目标日冲突,有几种处理方式,选一个再确认。')
      }

      // 删除:只允许未锁定的 pending 项
      if (intent.kind === 'drop_task') {
        if (intent.tasks.length === 0) return clarify('想删掉哪项任务?')
        const resolved = resolveTaskRefs(inWindow, intent.tasks, { unique: false })
        if (resolved.rows.length === 0) return clarify('没找到要删的任务,说个科目或标题我再找')
        if (resolved.ambiguous.length > 0) return clarify(`「${resolved.ambiguous[0]}」对应多条任务,说个日期或标题我再删`)
        const locked = resolved.rows.find(row => row.locked === true)
        if (locked) throw new ApiError(409, 'LOCKED_ITEMS_CONFLICT', `「${locked.title}」是锁定任务,先解锁再删除`)
        const ids = new Set(resolved.rows.map(row => Number(row.id)))
        const after = before.filter(item => !ids.has(item.id))
        const option = manualOption('manual', '按你的要求删除', `删掉 ${ids.size} 项任务`,
          'L1', before, after, capacity, deferredProfileAfter)
        if (!option) return clarify('这些任务已经不在计划里了')
        return finishSingle(option, '已按你说的删掉,确认后生效。')
      }

      // 调整目标:即使只改档案也要产调整单;任务量超过新目标时先让模型重排,失败再确定性删减
      if (intent.kind === 'adjust_scope') {
        const budgetAfter = buildPlanBudget(briefAfter, new Date(`${planStart}T00:00:00.000Z`), new Date(`${examDate}T00:00:00.000Z`))
        const remainingScheduled = allItems
          .filter(row => row.status !== 'done' && String(row.subject) !== REVIEW_SUBJECT)
          .reduce((sum, row) => sum + Number(row.minutes ?? 0), 0)
        let after = before
        if (budgetAfter.targetMinutes < remainingScheduled) {
          try {
            const target = await runAdjustL2Plan(ai, {
              message, pending: open, capacity: free, from, to, brief: briefAfter,
              mode: 'scope', locked: lockedSnapshots,
            })
            // 锁定项不参与模型输出,必须原样带回
            after = [...doneSnapshots, ...lockedSnapshots, ...target]
          } catch (error) {
            const reason = error instanceof Error ? error.message : String(error)
            console.warn(`[planning] scope 重排失败,降级到确定性删减:${reason}`)
            const milestoneMap = new Map(milestones.map(item => [item.subject, item.date]))
            after = trimToTarget(before, budgetAfter.targetMinutes, weakSubjects, milestoneMap)
          }
        }
        const option = manualOption('manual', '按新目标调整',
          `科目目标已更新,计划同步调整 ${diffSnapshots(before, after).length} 项`, 'L2',
          before, after, capacity, deferredProfileAfter)
        if (!option) return clarify('这些科目的目标没有变化')
        return finishSingle(option, '已按新的目标重新算过,确认后生效。')
      }

      // 落后重排:用户明确要把落后/未完成任务整体重新排进后面的空档。
      // 与日期类不同——放置下限钳到今天(落后项必须前移),且不设 L1 影响天数上限(用户就是要整体重排)。
      if (intent.kind === 'reschedule') {
        const l1 = planL1Shuffle({ pending: open, capacity: free, windowFrom: today, windowTo: to, milestones })
        if (l1.overflow.length === 0) {
          const after = applyMoves(before, l1.moves)
          if (overloadedDays(after, capacity, today, to).length === 0) {
            const option = manualOption('manual', '重新排过',
              `把 ${open.length} 项未完成任务从今天起重新排进空档`, 'L1', before, after, capacity, deferredProfileAfter)
            if (!option) return clarify('任务本来就排得下,不用重排。')
            return finishSingle(option, '已把落后的任务重新排进后面的空档,确认后生效。')
          }
        }
        // 从今天起仍塞不下:交给方案引擎(压缩/顺延/删减),窗口头同样钳到今天
        const options = attachProfile(buildAdjustmentOptions({ ...optionsInput, windowFrom: today }), deferredProfileAfter)
        if (options.length === 0) {
          noOptions(overloadedDays(before, capacity, today, to))
          return clarify('没有找到可行的重排方式')
        }
        return finishMulti(options, options.length >= 2
          ? `有 ${options.length} 种重排方式,影响不同,选一个再确认。`
          : '已重新排过,确认后生效。')
      }

      // 纯日期类(unavailable / reduce_capacity / change_commitment):先 L1 顺延
      const l1 = planL1Shuffle({ pending: open, capacity: free, windowFrom: from, windowTo: to, milestones })
      const l1Safe = l1.overflow.length === 0 && affectedDays(l1.moves) <= L1_MAX_AFFECTED_DAYS
      if (l1Safe) {
        const after = applyMoves(before, l1.moves)
        const overload = overloadedDays(after, capacity, from, to)
        if (overload.length === 0) {
          const option = manualOption('manual', '快速顺延',
            l1.moves.length > 0 ? `顺延 ${l1.moves.length} 项任务,其余不动` : (deferredProfileAfter ? '更新固定占用' : '当前安排不用挪动'),
            'L1', before, after, capacity, deferredProfileAfter)
          if (!option) {
            if (!deferredProfileAfter) return clarify('这几天的安排本来就排得下,计划不用改。')
            return finishSingle({
              id: 'manual', label: '更新固定占用', summary: '只更新档案里的固定占用',
              tier: 'L1', after: before, changes: [], impact: computeImpact(before, before, [], capacity),
              profileAfter: deferredProfileAfter,
            }, '固定占用已更新,确认后生效。')
          }
          return finishSingle(option, '已按快速顺延算好,确认后生效。')
        }
      }

      // L1 塞不下/影响过大:交给确定性方案引擎;L2 只在语义重排(adjust_scope)时使用
      const options = attachProfile(buildAdjustmentOptions(optionsInput), deferredProfileAfter)
      if (options.length === 0) {
        noOptions(overloadedDays(before, capacity, from, to))
        return clarify('没有找到可行的调整方式')
      }
      return finishMulti(options, options.length >= 2
        ? `有 ${options.length} 种处理方式,影响不同,选一个再确认。`
        : '已重新排过,确认后生效。')
    },

    /** 最近一张待确认/已生效的调整单:App 冷启动/切页恢复卡片用。按事件时间倒序(draft 看 createdAt,applied 看 appliedAt) */
    async latestAdjustment(userGuid: string): Promise<PublicAdjustment | null> {
      const rows = await db.planAdjustment.findMany({ where: { userGuid, status: { in: ['draft', 'applied'] } } })
      const eventTime = (row: any) => ms(row.status === 'applied' ? row.appliedAt : row.createdAt) ?? 0
      const sorted = [...rows].sort((a, b) => eventTime(b) - eventTime(a) || Number(b.id) - Number(a.id))
      return sorted.length > 0 ? serializeAdjustment(sorted[0]) : null
    },

    /** 切换多方案:draft + 指纹校验 + 选项存在 → 更新 afterJson 与 selectedOption,不改变 beforeJson/fingerprint */
    async selectAdjustmentOption(userGuid: string, adjustmentId: number, optionId: string): Promise<PublicAdjustment> {
      const row = await db.planAdjustment.findFirst({ where: { id: adjustmentId, userGuid, status: 'draft' } })
      if (!row) throw new ApiError(404, 'ADJUSTMENT_NOT_FOUND', '调整单不存在,或已经确认/撤销过了')
      const options = jsonList<AdjustmentOption>(row.optionsJson)
      const chosen = options.find(option => option.id === optionId)
      if (!chosen) throw new ApiError(404, 'ADJUSTMENT_OPTION_NOT_FOUND', '这个调整方案不存在')
      const planRow = await db.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
      if (!planRow || Number(planRow.id) !== Number(row.planId)) {
        throw new ApiError(404, 'PLAN_NOT_FOUND', '这份调整单对应的计划已经不在了')
      }
      const [items, profileRow] = await Promise.all([
        db.planItem.findMany({ where: { planId: planRow.id } }),
        db.userProfile.findUnique({ where: { userGuid } }),
      ])
      const currentBrief = profileRow ? briefFromRow(profileRow) : normalizeBrief(null)
      const briefBefore = briefFromJson(row.profileBeforeJson) ?? currentBrief
      const examDate = dateOnly(planRow.examDate)
      if (row.profileBeforeJson
        && briefContextHash(currentBrief, examDate) !== briefContextHash(briefBefore, examDate)) {
        throw new ApiError(409, 'ADJUSTMENT_STALE', '备考口径在生成调整单之后被改过,请重新说一遍')
      }
      const from = dateOnly(row.windowFrom)
      const to = dateOnly(row.windowTo)
      const inWindow = items.filter(item => {
        const day = dateOnly(item.planDate)
        return day >= from && day <= to
      })
      if (windowFingerprint(toSnapshot(inWindow), briefContextHash(briefBefore, examDate)) !== String(row.fingerprint)) {
        throw new ApiError(409, 'ADJUSTMENT_STALE', '计划在生成调整单之后又被改过,这张调整单已失效,请重新说一遍')
      }
      await db.planAdjustment.updateMany({
        where: { id: row.id, status: 'draft' },
        data: {
          afterJson: JSON.stringify(chosen.after),
          selectedOption: chosen.id,
          tier: chosen.tier,
          profileAfterJson: chosen.profileAfter ?? null,
        },
      })
      const updated = await db.planAdjustment.findFirst({ where: { id: row.id } })
      return serializeAdjustment(updated)
    },

    /** 确认调整单:口径/窗口指纹校验 → 事务内应用快照、写延迟档案、重排时段 → 记录 afterFingerprint */
    async confirmAdjustment(userGuid: string, adjustmentId: number): Promise<PublicPlan> {
      const appliedPlanId = await db.$transaction(async tx => {
        const row = await tx.planAdjustment.findFirst({ where: { id: adjustmentId, userGuid, status: 'draft' } })
        if (!row) throw new ApiError(404, 'ADJUSTMENT_NOT_FOUND', '调整单不存在,或已经确认/撤销过了')
        const planRow = await tx.plan.findFirst({ where: { userGuid, status: 'active' }, orderBy: { version: 'desc' } })
        if (!planRow || Number(planRow.id) !== Number(row.planId)) {
          throw new ApiError(404, 'PLAN_NOT_FOUND', '这份调整单对应的计划已经不在了')
        }
        const [stages, items, profileRow] = await Promise.all([
          tx.planStage.findMany({ where: { planId: planRow.id }, orderBy: { sortOrder: 'asc' } }),
          tx.planItem.findMany({ where: { planId: planRow.id } }),
          tx.userProfile.findUnique({ where: { userGuid } }),
        ])
        const examDate = dateOnly(planRow.examDate)
        const currentBrief = profileRow ? briefFromRow(profileRow) : normalizeBrief(null)
        const briefBefore = briefFromJson(row.profileBeforeJson) ?? currentBrief
        const briefAfter = briefFromJson(row.profileAfterJson) ?? currentBrief
        // 延迟写档案:确认前口径必须还是生成调整单时那份,否则用户在别处改过档案,这张单作废
        if (row.profileAfterJson
          && briefContextHash(currentBrief, examDate) !== briefContextHash(briefBefore, examDate)) {
          throw new ApiError(409, 'ADJUSTMENT_STALE', '备考口径在确认前被改过,这张调整单已失效,请重新说一遍')
        }
        const from = dateOnly(row.windowFrom)
        const to = dateOnly(row.windowTo)
        const inWindow = items.filter(item => {
          const day = dateOnly(item.planDate)
          return day >= from && day <= to
        })
        if (windowFingerprint(toSnapshot(inWindow), briefContextHash(briefBefore, examDate)) !== String(row.fingerprint)) {
          throw new ApiError(409, 'ADJUSTMENT_STALE', '计划在生成调整单之后又被改过,这张调整单已失效,请重新说一遍')
        }
        await syncWindowItems(tx, planRow.id, from, to,
          jsonList<WindowSnapshotItem>(row.afterJson), stages, briefAfter)
        if (row.profileAfterJson) {
          await tx.userProfile.update({
            where: { userGuid },
            data: { briefJson: JSON.stringify(briefAfter), updatedAt: new Date() },
          })
        }
        const finalRows = await tx.planItem.findMany({ where: { planId: planRow.id } })
        const finalWindow = finalRows.filter(item => {
          const day = dateOnly(item.planDate)
          return day >= from && day <= to
        })
        await tx.planAdjustment.updateMany({
          where: { id: row.id },
          data: {
            status: 'applied', appliedAt: new Date(),
            afterFingerprint: windowFingerprint(toSnapshot(finalWindow), briefContextHash(briefAfter, examDate)),
          },
        })
        await tx.plan.updateMany({ where: { id: planRow.id }, data: { updatedAt: new Date() } })
        return Number(planRow.id)
      })
      const planRow = await db.plan.findFirst({ where: { id: appliedPlanId } })
      if (!planRow) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划不存在')
      return loadPlan(userGuid, planRow)
    },

    /** 撤销:恢复 beforeJson;先比对 afterFingerprint,发现确认后又被手工改过就拒绝,避免覆盖用户修改 */
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
        const profileRow = await tx.userProfile.findUnique({ where: { userGuid } })
        const currentBrief = profileRow ? briefFromRow(profileRow) : normalizeBrief(null)
        const briefBefore = briefFromJson(latestApplied.profileBeforeJson)
        const briefAfter = briefFromJson(latestApplied.profileAfterJson) ?? currentBrief
        const examDate = dateOnly(planRow.examDate)
        const from = dateOnly(latestApplied.windowFrom)
        const to = dateOnly(latestApplied.windowTo)
        const inWindow = items.filter(item => {
          const day = dateOnly(item.planDate)
          return day >= from && day <= to
        })
        if (latestApplied.afterFingerprint) {
          const currentFingerprint = windowFingerprint(toSnapshot(inWindow), briefContextHash(briefAfter, examDate))
          if (currentFingerprint !== String(latestApplied.afterFingerprint)) {
            throw new ApiError(409, 'ADJUSTMENT_UNDO_STALE', '调整后你手工改过计划,撤销会覆盖这些修改;请再描述一次新的调整')
          }
        }
        const stages = await tx.planStage.findMany({ where: { planId: planRow.id }, orderBy: { sortOrder: 'asc' } })
        await syncWindowItems(tx, planRow.id, from, to,
          jsonList<WindowSnapshotItem>(latestApplied.beforeJson), stages, briefBefore ?? currentBrief)
        if (latestApplied.profileBeforeJson) {
          await tx.userProfile.update({
            where: { userGuid },
            data: { briefJson: JSON.stringify(briefBefore ?? currentBrief), updatedAt: new Date() },
          })
        }
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

    /** 主动体检:本周执行、落后任务与未来 7 天负荷(纯计算,无副作用) */
    async checkup(
      userGuid: string,
      options: { tzOffset?: number; now?: Date } = {},
    ): Promise<CheckupSummary> {
      const now = options.now ?? new Date()
      const tzOffset = normalizeTzOffset(options.tzOffset ?? DEFAULT_TZ_OFFSET)
      const planRow = await db.plan.findFirst({
        where: { userGuid, status: 'active' },
        orderBy: { version: 'desc' },
      })
      if (!planRow) return emptyCheckup()
      const [items, profileRow, sessions] = await Promise.all([
        db.planItem.findMany({ where: { planId: planRow.id } }),
        db.userProfile.findUnique({ where: { userGuid } }),
        db.pomodoroSession.findMany({
          where: {
            userGuid,
            valid: true,
            isDeleted: false,
            startedAt: { gte: BigInt(now.getTime() - 15 * 24 * 60 * 60 * 1000) },
          },
        }),
      ])
      return buildCheckup({
        now,
        tzOffset,
        items,
        sessions,
        brief: briefFromRow(profileRow),
      })
    },
  }

  async function buildPlan(
    userGuid: string,
    onProgress?: (progress: PlanProgress) => void,
    options: { allowShortfall?: boolean } = {},
  ): Promise<PublicPlan> {
    const allowShortfall = options.allowShortfall === true
    const profileRow = await requireProfileRow(userGuid)
      assertGenerateOptions(profileRow, options)
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

      // 预算:把逐科剩余任务钉成一份排得下的目标,生成/校验/概览共用同一份数字
      const budget = remainingBrief
        ? buildPlanBudget(remainingBrief, dayStart(new Date()), examDate, { allowShortfall })
        : null
      if (budget && budget.shortfallMinutes > 0 && !allowShortfall) {
        throw new ApiError(
          409,
          'PLAN_CAPACITY_INSUFFICIENT',
          `已确认任务比考前有效容量多 ${budget.shortfallMinutes} 分钟,请先调整作息、下调目标或选择尽力版`,
          {
            availableMinutes: budget.effectiveCapacityMinutes,
            requiredMinutes: budget.confirmedMinutes,
            deficits: budget.deficits,
            choices: ['adjust_schedule', 'lower_target', 'best_effort'],
          },
        )
      }

      let generated: GeneratedPlan
      let title: string
      let document: PlanDocument | null = null
      let spec: PlanSpec | null = null
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
            budget,
            ...(lastFailure ? { repairHint: lastFailure.message } : {}),
            ...(onProgress ? { onProgress } : {}),
          })
          assertGeneratedPlanValid(result.plan, examDate, brief, budget, allowShortfall)
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
          }
          generated = result.plan
          title = result.title
          document = result.document ?? null
          spec = result.spec ?? null
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
            // spec 是后续补生成文档、审计「为什么这样排」的唯一来源;旧客户端不受影响
            specJson: spec ? JSON.stringify(spec) : null,
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
            slot: item.slot ?? null,
            startTime: item.startTime ?? null,
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
