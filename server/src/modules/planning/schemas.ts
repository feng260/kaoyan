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

/** 24 小时制时刻,统一 `HH:mm` —— 与 brief 里的时间格式保持一致,netAvailableMinutes 直接可用 */
const timeSchema = z.string().trim().regex(/^([01]\d|2[0-3]):[0-5]\d$/, '时间格式应为 HH:mm')
const clockMinutes = (time: string) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3))

const weekdaySchema = z.number().int('星期应为 1–7 的整数').min(1).max(7)

const windowSchema = z
  .object({ start: timeSchema, end: timeSchema })
  .refine(w => clockMinutes(w.start) < clockMinutes(w.end), '结束时间需要晚于开始时间')

/**
 * 问卷阶段的「按星期空闲窗口」。
 * 为什么要问这个:光有「每日可用 180 分钟」排不出计划 —— 周三只有 1 小时、周末有 8 小时,
 * 容量是按天算净空闲的,必须知道每天真实的窗口才能判断「考前盖不盖得住已确认的任务量」。
 * 客户端没有新增这些字段时(老版本 App)默认空数组,不会把已有档案打脏。
 */
const availabilitySchema = z.preprocess(
  normalizeJsonArray,
  z
    .array(
      z.object({
        weekday: weekdaySchema,
        windows: z
          .preprocess(normalizeJsonArray, z.array(windowSchema).min(1, '至少填一个空闲时段').max(6))
          .default([]),
      }),
    )
    .max(7)
    .default([]),
)

/** 固定占用:上课、上班、通勤等每周固定被占掉的时间段 */
const fixedCommitmentsSchema = z.preprocess(
  normalizeJsonArray,
  z
    .array(
      z
        .object({
          weekday: weekdaySchema,
          start: timeSchema,
          end: timeSchema,
          label: z.string().trim().min(1, '固定占用需要说明是什么事').max(16),
          // 省略 = 每周都占。单双周只在填了 termWeekOneStart 时才真正参与容量计算
          weekParity: z.enum(['odd', 'even']).optional(),
        })
        .refine(c => clockMinutes(c.start) < clockMinutes(c.end), '结束时间需要晚于开始时间'),
    )
    .max(20, '固定占用最多 20 条')
    .default([]),
)

/** 正式考试科目名(问卷只收「有哪些科」,逐科的进度/范围/里程碑由面谈补齐) */
const examSubjectsSchema = z.preprocess(
  normalizeJsonArray,
  z
    .array(z.string().trim().min(1, '科目名称不能为空').max(16, '科目名称最多 16 个字'))
    .max(12, '考试科目最多 12 门')
    .default([]),
)

export const profileInputSchema = z.object({
  targetType: z.enum(SUPPORTED_TARGET_TYPES).default('考研'),
  examDate: examDateSchema,
  // 每日容量以课表净空闲为准,问卷不再收「每天投入多少分钟」;字段保留以兼容旧数据,传了仍校验范围
  dailyMinutes: z
    .number()
    .int('每日时长应为整数分钟')
    .min(MIN_DAILY_MINUTES, `每日可用时长至少 ${MIN_DAILY_MINUTES} 分钟`)
    .max(MAX_DAILY_MINUTES, `每日可用时长最多 ${MAX_DAILY_MINUTES} 分钟`)
    .optional(),
  studyWindows: studyWindowsSchema,
  foundation: z.enum(FOUNDATION_LEVELS).default('一般'),
  weakSubjects: weakSubjectsSchema,
  examSubjects: examSubjectsSchema,
  availability: availabilitySchema,
  fixedCommitments: fixedCommitmentsSchema,
  // 校历第 1 周的周一。三种取值:缺键(undefined)= 客户端没提供、写入层保留旧值;
  // 空串 = 用户显式清除;合法日期 = 设置。老客户端不带该字段,不能因此把已确认的开学周清掉。
  // 空串表示不启用单双周,带 weekParity 的占用保守按每周都占计算
  termWeekOneStart: z
    .string()
    .trim()
    .regex(/^\d{4}-\d{2}-\d{2}$/, '校历第 1 周要写成 2026-09-07 这样(年月日全都要)')
    // 正则挡不住不存在的日期:格式合法但已失效的值会穿过去存进 briefJson,
    // 而 normalizeBrief 读回时把它归一成空串 —— 接口说「存好了」、用户看到的日期却没生效
    .refine(value => {
      if (value === '') return true
      const parsed = new Date(`${value}T00:00:00.000Z`)
      // 日溢出(2026-02-30)会被 V8 静默进位成 3 月 2 日,只有往返比对挡得住;月份越界给 Invalid Date
      return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
    }, '校历第 1 周不是一个真实存在的日期')
    .optional()
    .or(z.literal('')),
  availabilityConfirmed: z.boolean().default(false),
  commitmentsConfirmed: z.boolean().default(false),
})

export type ProfileInput = z.infer<typeof profileInputSchema>

/**
 * 口径手工修改:逐科剩余量/里程碑与每周固定占用。
 * 放在这里而不是 routes.ts,是为了能直接单测 —— 路由文件会牵出 router/service/db 整条依赖链。
 */
export const briefPatchSchema = z.object({
  examSubjects: z.array(z.object({
    name: z.string().trim().min(1).max(32),
    remainingMinutes: z.number().int().min(0).max(500000).nullish(),
    milestone: z.string().max(64).nullish(),
    // 空串表示「清掉截止日」,和未填一样按不修改处理
    milestoneDate: z.union([z.string().regex(/^\d{4}-\d{2}-\d{2}$/), z.literal('')]).nullish(),
    milestoneMinutes: z.number().int().min(0).max(500000).nullish(),
    estimated: z.boolean().nullish(),
    scope: z.string().max(500).nullish(),
    progress: z.string().max(500).nullish(),
  })).max(20).nullish(),
  fixedCommitments: z.array(z.object({
    weekday: z.number().int().min(1).max(7),
    start: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/),
    end: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/),
    label: z.string().trim().min(1).max(32),
    // 省略 = 每周都占。Zod 默认会剥掉未声明的键,少这一行会让手工改口径的入口静默丢掉单双周标记
    weekParity: z.enum(['odd', 'even']).optional(),
  })).max(40).nullish(),
})
