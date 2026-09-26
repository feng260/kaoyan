import { z } from 'zod'

/**
 * 备考档案输入校验(Phase 0)。
 * 日期统一按「天」处理:对外收 YYYY-MM-DD(或 ISO),对内归一为 UTC 零点,
 * 避免服务端时区把「今天」和「明天」算错一天。
 */

/** 目标类型:本阶段都可选,但只有考研有专属规则,其余走通用规则 */
export const SUPPORTED_TARGET_TYPES = ['考研', '专升本', '考公', '法考', '其他'] as const
/** 固定学习时段 */
export const STUDY_WINDOWS = ['早晨', '上午', '下午', '晚上', '深夜'] as const
/** 基础情况 */
export const FOUNDATION_LEVELS = ['零基础', '一般', '较好'] as const

export const MIN_DAILY_MINUTES = 15
export const MAX_DAILY_MINUTES = 720
/** 备考周期上限:超过三年的计划没有实际意义 */
const MAX_DAYS_AHEAD = 365 * 3
const DAY_MS = 24 * 3600_000

/** 归一为 UTC 零点(按天比较的唯一口径) */
export function dayStart(input: Date): Date {
  const d = new Date(input.getTime())
  d.setUTCHours(0, 0, 0, 0)
  return d
}

export function addDays(input: Date, days: number): Date {
  return new Date(dayStart(input).getTime() + days * DAY_MS)
}

/** 相差天数(按天,不受时分秒影响) */
export function diffDays(later: Date, earlier: Date): number {
  return Math.round((dayStart(later).getTime() - dayStart(earlier).getTime()) / DAY_MS)
}

/**
 * 客户端可能把数组以 JSON 字符串下发(数据库列是 Text),
 * 这里统一预处理:字符串尝试 JSON.parse,解析失败则原样交给数组校验报错。
 */
function normalizeJsonArray(raw: unknown): unknown {
  if (typeof raw !== 'string') return raw
  const trimmed = raw.trim()
  if (!trimmed.startsWith('[')) return raw
  try {
    return JSON.parse(trimmed)
  } catch {
    return raw
  }
}

const examDateStringSchema = z
  .string()
  .trim()
  .regex(/^\d{4}-\d{2}-\d{2}(?:[T ].*)?$/, '考试日期格式应为 YYYY-MM-DD')
  .transform((v, ctx) => {
    const parsed = new Date(v.length === 10 ? `${v}T00:00:00.000Z` : v)
    if (Number.isNaN(parsed.getTime())) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: '考试日期不是有效日期' })
      return z.NEVER
    }
    return dayStart(parsed)
  })
  .superRefine((exam, ctx) => {
    const today = dayStart(new Date())
    if (exam.getTime() <= today.getTime()) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: '考试日期需要晚于今天,至少留出一天的准备时间' })
      return
    }
    if (diffDays(exam, today) > MAX_DAYS_AHEAD) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: `考试日期过远,请填写 ${MAX_DAYS_AHEAD} 天以内` })
    }
  })

/**
 * 这个 schema 必须「幂等」:路由层已经解析过一遍,service 层为防脏数据还会再校验一次,
 * 那时 examDate 已经是归一化后的 Date。先把 Date 转回 ISO 字符串再走同一条管线,
 * 否则第二道防线会把已经合法的数据当成「格式错误」拦下(线上 PUT /profile 400 就是栽在这里)。
 */
const examDateSchema = z.preprocess(
  raw => (raw instanceof Date ? raw.toISOString() : raw),
  examDateStringSchema,
)

const weakSubjectsSchema = z.preprocess(
  normalizeJsonArray,
  z
    .array(z.string().trim().min(1, '科目名称不能为空').max(16))
    .min(1, '至少选择一门薄弱科目')
    .max(6, '薄弱科目最多 6 门'),
)

const studyWindowsSchema = z.preprocess(
  normalizeJsonArray,
  z.array(z.enum(STUDY_WINDOWS)).min(1, '至少选择一个固定学习时段').max(STUDY_WINDOWS.length),
)

export const profileInputSchema = z.object({
  targetType: z.enum(SUPPORTED_TARGET_TYPES).default('考研'),
  examDate: examDateSchema,
  dailyMinutes: z
    .number()
    .int('每日时长应为整数分钟')
    .min(MIN_DAILY_MINUTES, `每日可用时长至少 ${MIN_DAILY_MINUTES} 分钟`)
    .max(MAX_DAILY_MINUTES, `每日可用时长最多 ${MAX_DAILY_MINUTES} 分钟`),
  studyWindows: studyWindowsSchema,
  foundation: z.enum(FOUNDATION_LEVELS).default('一般'),
  weakSubjects: weakSubjectsSchema,
})

export type ProfileInput = z.infer<typeof profileInputSchema>
