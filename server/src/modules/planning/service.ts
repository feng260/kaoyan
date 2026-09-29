import { prisma } from '../../shared/prisma'
import { ApiError } from '../../middlewares/error'
import { llmConfigured } from '../../config/env'
import { dayStart, diffDays, profileInputSchema, type ProfileInput } from './schemas'
import type { GeneratedPlan } from './generator'
import { generateAiPlan, type AiPlanningInput } from './aiGenerator'
import { runPlanInterview, type InterviewInput, type InterviewResult } from './aiCoach'
import { briefIsEmpty, normalizeBrief, assessPlanningFacts, netAvailableMinutes, type PlanBrief, type PlanDocument } from './document'

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
    groupBy(args: AnyArgs): Promise<any[]>
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
  stages: Array<{ id: number; name: string; startDate: string; endDate: string; sortOrder: number }>
  items: PublicPlanItem[]
  progress: { totalItems: number; pendingItems: number; doneItems: number; totalMinutes: number; totalDays: number }
  createdAt: number | null
  updatedAt: number | null
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

function requirePlanningFacts(brief: PlanBrief | null, start: Date, exam: Date, dailyMinutes: number, allowCompletedMilestones = false): void {
  const result = assessPlanningFacts(brief ?? normalizeBrief(null), start, exam, dailyMinutes, allowCompletedMilestones)
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

/** 计划第一天的时间预算:用于判断档案里的每日时长是否已经和计划对不上 */
function firstDayBudget(items: any[]): number {
  if (items.length === 0) return 0
  const first = items
    .map(i => dateOnly(i.planDate))
    .filter(Boolean)
    .sort()[0]
  return items.filter(i => dateOnly(i.planDate) === first).reduce((sum, i) => sum + Number(i.minutes ?? 0), 0)
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

/** 从库里读出的档案行还原成 ProfileInput;档案不可用时返回 null —— 面谈没有档案也能进行 */
function profileInputFromRow(row: any): ProfileInput | null {
  const parsed = profileInputSchema.safeParse({
    targetType: row.targetType,
    examDate: toDay(row.examDate),
    dailyMinutes: Number(row.dailyMinutes ?? 0),
    studyWindows: jsonArray(row.studyWindowsJson),
    foundation: row.foundation ?? '一般',
    weakSubjects: jsonArray(row.weakSubjectsJson),
  })
  return parsed.success ? parsed.data : null
}

/**
 * 落库前的最后一道闸:宁可在这里抛错让事务不开始,也不要写进一份自相矛盾的计划。
 * 检查项对应生成器的核心承诺 —— 阶段连续、计划项落在所属阶段区间内、每天不超预算。
 */
function assertGeneratedPlanValid(generated: GeneratedPlan, dailyMinutes: number, examDate: Date, brief?: PlanBrief | null): void {
  const fail = (reason: string): never => {
    throw new ApiError(500, 'PLAN_GENERATION_FAILED', `计划生成结果不完整:${reason}`)
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
    const available = brief ? Math.min(dailyMinutes, netAvailableMinutes(brief, new Date(`${day}T00:00:00.000Z`))) : dailyMinutes
    if (minutes > available) fail(`${day} 安排 ${minutes} 分钟,超出当天净空闲 ${available} 分钟`)
  }
}

/**
 * 服务端依赖的 AI 能力。`interview` 刻意是可选的:
 * 单测注入的假 AI 只需要 generate,不必为了跑通测试去 mock 一整套面谈。
 */
export interface PlanningAi {
  configured: () => boolean
  generate: (input: AiPlanningInput) => Promise<{ title: string; plan: GeneratedPlan; document?: PlanDocument | null }>
  interview?: (input: InterviewInput) => Promise<InterviewResult>
}

export function createPlanningService(
  db: PlanningDb,
  ai: PlanningAi = {
    configured: llmConfigured,
    generate: generateAiPlan,
    interview: runPlanInterview,
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
    // 只有当前的计划才谈得上「档案变了需要重新生成」;历史计划一律不标 stale
    const stale = planRow.status !== 'active'
      ? false
      : !profileRow
        ? true
        : diffDays(toDay(planRow.examDate), toDay(profileRow.examDate)) !== 0
          || firstDayBudget(items) !== Number(profileRow.dailyMinutes ?? 0)
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
      const data = {
        targetType: parsed.targetType,
        examDate: parsed.examDate,
        dailyMinutes: parsed.dailyMinutes,
        studyWindowsJson: JSON.stringify(parsed.studyWindows),
        foundation: parsed.foundation,
        weakSubjectsJson: JSON.stringify(parsed.weakSubjects),
      }
      const row = await db.userProfile.upsert({
        where: { userGuid },
        create: { userGuid, ...data, onboardingDoneAt: new Date(), createdAt: new Date(), updatedAt: new Date() },
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
        requirePlanningFacts(raw ? normalizeBrief(raw) : null, dayStart(new Date()), toDay(profile.examDate), Number(profile.dailyMinutes))
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
      const messages = Array.isArray(input.messages) ? (input.messages as InterviewInput['messages']) : []

      let result: InterviewResult
      try {
        result = await ai.interview({ messages, profile, force: input.force === true })
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error)
        console.warn(`[planning] 备考面谈失败:${reason}`)
        throw new ApiError(502, 'PLAN_INTERVIEW_FAILED', 'AI 面谈失败,请稍后重试')
      }

      const brief = normalizeBrief(result.brief)
      const facts = assessPlanningFacts(brief, dayStart(new Date()), profile?.examDate ?? dayStart(new Date()), profile?.dailyMinutes ?? 0)
      if (!facts.ready || !profile) {
        const followUp = facts.missing.length ? `还需确认:${facts.missing.join('、')}`
          : facts.deficits.length ? `考前时间不足:${facts.deficits.map(d => `${d.subject}「${d.milestone}」缺${d.missingMinutes}分钟`).join('、')}`
            : '请先完成备考档案'
        return { reply: result.done ? followUp : result.reply, options: result.done ? [] : result.options, done: false, brief: null }
      }
      if (result.done && !briefIsEmpty(brief) && profileRow) {
        await db.userProfile.update({
          where: { userGuid },
          data: { briefJson: JSON.stringify(brief), updatedAt: new Date() },
        })
      }

      return { ...result, brief: result.done ? brief : null }
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
  }

  async function buildPlan(userGuid: string): Promise<PublicPlan> {
    const profileRow = await requireProfileRow(userGuid)
      const initialSnapshot = profileSnapshot(profileRow)
      const profile = serializeProfile(profileRow)
      const examDate = toDay(profileRow.examDate)

      // 库里读出来的是宽松字符串,这里再过一遍 schema 收成 ProfileInput 的字面量联合类型。
      // 校验失败说明存进库的档案本身就不可用(日期过期、时长越界等),交给路由层转成业务错误码。
      const parsedProfile = profileInputSchema.safeParse({
        targetType: profile.targetType,
        examDate,
        dailyMinutes: profile.dailyMinutes,
        studyWindows: profile.studyWindows,
        foundation: profile.foundation ?? '一般',
        weakSubjects: profile.weakSubjects,
      })
      if (!parsedProfile.success) {
        const issue = parsedProfile.error.issues[0]
        const field = String(issue?.path[0] ?? '')
        if (field === 'examDate') throw new ApiError(400, 'INVALID_EXAM_DATE', issue.message)
        if (field === 'dailyMinutes') throw new ApiError(400, 'INVALID_DAILY_MINUTES', issue.message)
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
      const activeItems = active ? await db.planItem.findMany({ where: { planId: active.id }, orderBy: [{ planDate: 'asc' }, { sortOrder: 'asc' }] }) : []
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
      const backlog = activeItems.filter(item => item.status !== 'done')
        .map(item => ({ subject: String(item.subject), title: String(item.title), minutes: Number(item.minutes) }))
      requirePlanningFacts(brief, dayStart(new Date()), examDate, profile.dailyMinutes)
      if (active && brief) requirePlanningFacts(remainingBrief, dayStart(new Date()), examDate, profile.dailyMinutes, true)

      let generated: GeneratedPlan
      let title: string
      let document: PlanDocument | null = null
      try {
        const result = await ai.generate({ ...parsedProfile.data, startDate: dayStart(new Date()), brief: remainingBrief, backlog })
        assertGeneratedPlanValid(result.plan, profile.dailyMinutes, examDate, brief)
        if (brief) {
          const allowed = new Set(brief.examSubjects.map(subject => subject.name))
          const invalid = result.plan.items.find(item => !allowed.has(item.subject))
          if (invalid) throw new ApiError(502, 'PLAN_GENERATION_FAILED', `计划包含未确认的考试科目:${invalid.subject}`)
          for (const subject of brief.examSubjects) {
            if (!subject.scope && result.plan.items.some(item => item.subject === subject.name && item.title !== subject.milestone)) {
              throw new ApiError(502, 'PLAN_GENERATION_FAILED', `${subject.name}考试范围不明,不得编造具体任务`)
            }
          }
          const deficits = remainingBrief!.examSubjects.flatMap(subject => {
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
      } catch (error) {
        if (error instanceof ApiError) throw error
        const reason = error instanceof Error ? error.message : String(error)
        console.warn(`[planning] AI 计划生成失败:${reason}`)
        throw new ApiError(502, 'PLAN_GENERATION_FAILED', `AI 计划生成失败:${reason}`)
      }

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

export type PlanningService = ReturnType<typeof createPlanningService>

/** 生产实例:注入真实的 Prisma 客户端 */
export const planningService = createPlanningService(prisma as unknown as PlanningDb)
