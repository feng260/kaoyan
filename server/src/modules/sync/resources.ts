import { z } from 'zod'

/**
 * 同步资源注册表:每资源定义 zod 校验 + Prisma 表映射 + 行序列化。
 * 字段与 Android Room 实体一致(Entities.kt);业务关联用对端 clientGuid。
 */
export const RESOURCES = ['subjects', 'countdownNodes', 'tasks', 'sessions', 'dailyReviews', 'weeklyReviews', 'monthlyReviews'] as const
export type ResourceName = (typeof RESOURCES)[number]

const guid = z.string().length(36)
const base = { userGuid: z.string().optional() } // 服务端以 token 为准,忽略客户端传入

export const subjectSchema = z.object({
  ...base,
  clientGuid: guid,
  name: z.string().min(1).max(64),
  colorArgb: z.number().int(),
  sort: z.number().int(),
  archived: z.boolean(),
  isDeleted: z.boolean().optional().default(false),
  updatedAt: z.number().int(),
})

export const countdownNodeSchema = z.object({
  ...base,
  clientGuid: guid,
  name: z.string().min(1).max(64),
  type: z.number().int().min(0).max(5),
  targetAt: z.number().int(),
  pinned: z.boolean(),
  sort: z.number().int(),
  isDeleted: z.boolean().optional().default(false),
  updatedAt: z.number().int(),
})

export const taskSchema = z.object({
  ...base,
  clientGuid: guid,
  subjectClientGuid: guid.nullable().optional(),
  title: z.string().min(1).max(128),
  priority: z.number().int().min(0).max(2),
  pomodoroEstimate: z.number().int().min(0),
  completedPomodoros: z.number().int().min(0),
  dueAt: z.number().int().nullable().optional(),
  repeatRule: z.number().int().min(0).max(2),
  repeatDays: z.number().int(),
  status: z.number().int().min(0).max(3),
  postponeCount: z.number().int().min(0),
  completedAt: z.number().int().nullable().optional(),
  createdAt: z.number().int(),
  note: z.string().max(2000).optional().default(''),
  isDeleted: z.boolean().optional().default(false),
  updatedAt: z.number().int(),
})

export const sessionSchema = z.object({
  ...base,
  clientGuid: guid,
  taskClientGuid: guid.nullable().optional(),
  subjectClientGuid: guid.nullable().optional(),
  startedAt: z.number().int(),
  endedAt: z.number().int(),
  durationMin: z.number().int().min(0),
  valid: z.boolean(),
  planName: z.string().max(32).optional().default(''),
  abandonReason: z.string().max(64).nullable().optional(),
  isDeleted: z.boolean().optional().default(false),
  updatedAt: z.number().int(),
})

export const dailyReviewSchema = z.object({
  ...base,
  epochDay: z.number().int(),
  q1Done: z.string().optional().default(''),
  q2Weak: z.string().optional().default(''),
  q3Tomorrow: z.string().optional().default(''),
  createdAt: z.number().int().optional(),
  updatedAt: z.number().int(),
})

export const weeklyReviewSchema = z.object({
  ...base,
  weekStartEpochDay: z.number().int(),
  weakPoints: z.string().optional().default(''),
  nextWeekTop1: z.string().optional().default(''),
  nextWeekTop2: z.string().optional().default(''),
  nextWeekTop3: z.string().optional().default(''),
  createdAt: z.number().int().optional(),
  updatedAt: z.number().int(),
})

export const monthlyReviewSchema = z.object({
  ...base,
  monthStartEpochDay: z.number().int(),
  summary: z.string().optional().default(''),
  nextMonthTop1: z.string().optional().default(''),
  nextMonthTop2: z.string().optional().default(''),
  nextMonthTop3: z.string().optional().default(''),
  createdAt: z.number().int().optional(),
  updatedAt: z.number().int(),
})

export const schemas: Record<ResourceName, z.ZodTypeAny> = {
  subjects: subjectSchema,
  countdownNodes: countdownNodeSchema,
  tasks: taskSchema,
  sessions: sessionSchema,
  dailyReviews: dailyReviewSchema,
  weeklyReviews: weeklyReviewSchema,
  monthlyReviews: monthlyReviewSchema,
}

export const table: Record<ResourceName, string> = {
  subjects: 'subject',
  countdownNodes: 'countdownNode',
  tasks: 'task',
  sessions: 'pomodoroSession',
  dailyReviews: 'dailyReview',
  weeklyReviews: 'weeklyReview',
  monthlyReviews: 'monthlyReview',
}

export function parseResource(name: string): ResourceName | null {
  return (RESOURCES as readonly string[]).includes(name) ? name as ResourceName : null
}

/** 服务端时钟漂移防护:写入的 updatedAt 取 max(serverNow, incoming) */
export function resolveUpdatedAt(incoming: number): bigint {
  return BigInt(Math.max(Date.now(), incoming))
}
