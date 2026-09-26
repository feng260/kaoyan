import { prisma } from '../../shared/prisma'
import { ApiError } from '../../middlewares/error'
import { dayStart, diffDays, profileInputSchema, type ProfileInput } from './schemas'
import { generateRulePlan, PlanGenerationError, type GeneratedPlan } from './generator'

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
  }
  plan: {
    findFirst(args?: AnyArgs): Promise<any>
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
  stages: Array<{ id: number; name: string; startDate: string; endDate: string; sortOrder: number }>
  items: PublicPlanItem[]
  progress: { totalItems: number; pendingItems: number; doneItems: number; totalMinutes: number; totalDays: number }
  createdAt: number | null
  updatedAt: number | null
}

const DAY_MS = 24 * 3600_000

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

function toDay(value: unknown): Date {
  const d = value instanceof Date ? value : new Date(Number(value))
  return dayStart(d)
}

function serializeProfile(row: any): PublicProfile {
  return {
    targetType: row.targetType,
    examDate: dateOnly(row.examDate),
    dailyMinutes: Number(row.dailyMinutes ?? 0),
    studyWindows: jsonArray(row.studyWindowsJson),
    foundation: row.foundation ?? null,
    weakSubjects: jsonArray(row.weakSubjectsJson),
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

/**
 * 落库前的最后一道闸:宁可在这里抛错让事务不开始,也不要写进一份自相矛盾的计划。
 * 检查项对应生成器的核心承诺 —— 阶段连续、计划项落在所属阶段区间内、每天不超预算。
 */
function assertGeneratedPlanValid(generated: GeneratedPlan, dailyMinutes: number, examDate: Date): void {
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
    if (minutes > dailyMinutes) fail(`${day} 安排 ${minutes} 分钟,超出每日可用 ${dailyMinutes} 分钟`)
  }
}

export function createPlanningService(db: PlanningDb) {
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
    const stale = !profileRow
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

    async generatePlan(userGuid: string): Promise<PublicPlan> {
      const profileRow = await requireProfileRow(userGuid)
      const profile = serializeProfile(profileRow)
      const examDate = toDay(profileRow.examDate)

      let generated: GeneratedPlan
      try {
        generated = generateRulePlan({
          examDate,
          startDate: dayStart(new Date()),
          dailyMinutes: profile.dailyMinutes,
          weakSubjects: profile.weakSubjects,
          studyWindows: profile.studyWindows,
        })
      } catch (error) {
        if (error instanceof PlanGenerationError) {
          throw new ApiError(400, error.code, error.message)
        }
        throw new ApiError(500, 'PLAN_GENERATION_FAILED', '计划生成失败,请稍后重试')
      }
      assertGeneratedPlanValid(generated, profile.dailyMinutes, examDate)

      const lastPlan = await db.plan.findFirst({ where: { userGuid }, orderBy: { version: 'desc' } })
      const version = Number(lastPlan?.version ?? 0) + 1

      const created = await db.$transaction(async tx => {
        // 先把旧计划归档,再建新的;整个过程在同一事务里,失败一起回滚
        await tx.plan.updateMany({
          where: { userGuid, status: 'active' },
          data: { status: 'archived', updatedAt: new Date() },
        })
        const planRow = await tx.plan.create({
          data: {
            userGuid,
            profileId: profileRow.id,
            title: `${profileRow.targetType ?? '考研'}备考计划`,
            targetType: profileRow.targetType ?? '考研',
            source: 'rule',
            status: 'active',
            startDate: generated.stages[0].startDate,
            examDate,
            version,
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
    },
  }
}

export type PlanningService = ReturnType<typeof createPlanningService>

/** 生产实例:注入真实的 Prisma 客户端 */
export const planningService = createPlanningService(prisma as unknown as PlanningDb)
