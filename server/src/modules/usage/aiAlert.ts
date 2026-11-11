import { env, smtpConfigured } from '../../config/env'
import { prisma } from '../../shared/prisma'
import { sendMail } from '../../shared/mailer'
import { dayStartForOffset } from './service'

export const CST_OFFSET_MINUTES = 480
const CHECK_INTERVAL_MS = 5 * 60_000

export type AiAlertOptions = {
  dailyTokens: number
  jobTokens: number
  concurrencyWarn: number
}

export type AiAlertDeps = {
  sumTokensSince: (since: Date) => Promise<number>
  sendAlert: (subject: string, text: string) => Promise<void>
  clock: () => number
  log: (message: string) => void
}

/** 以 UTC+8 计算自然日键,不依赖服务器本地时区 */
export function cstDayKey(nowMs: number): string {
  return new Date(nowMs + CST_OFFSET_MINUTES * 60_000).toISOString().slice(0, 10)
}

export function createAiAlert(deps: AiAlertDeps, opts: AiAlertOptions) {
  let lastCheckAt = 0
  let lastAlertDay = ''

  return {
    /** 每日 token 总量越线时发送一封管理员邮件;5 分钟节流、当日去重 */
    async checkDailyTokenAlert(): Promise<void> {
      const now = deps.clock()
      if (now - lastCheckAt < CHECK_INTERVAL_MS) return
      lastCheckAt = now
      const day = cstDayKey(now)
      if (lastAlertDay === day) return
      const since = dayStartForOffset(new Date(now), CST_OFFSET_MINUTES)
      try {
        const total = await deps.sumTokensSince(since)
        if (total < opts.dailyTokens) return
        await deps.sendAlert(
          '研钟 AI 用量告警',
          `今日(${day} CST)AI token 用量已达 ${total},超过阈值 ${opts.dailyTokens}。请登录管理后台查看明细。`,
        )
        lastAlertDay = day
        deps.log(`[ai-alert] 已发送日报警 total=${total}`)
      } catch (error) {
        deps.log(`[ai-alert] 检查失败:${(error as Error)?.message ?? error}`)
      }
    },

    warnIfJobExceeds(totalTokens: number, jobId: string): void {
      if (totalTokens < opts.jobTokens) return
      deps.log(`[ai-alert] 生成任务 ${jobId} 消耗 ${totalTokens} tokens,超过单任务阈值 ${opts.jobTokens}`)
    },

    warnIfBusy(running: number): void {
      if (running < opts.concurrencyWarn) return
      deps.log(`[ai-alert] 当前运行中的计划生成任务 ${running} 个,请关注并发`)
    },
  }
}

export const aiAlert = createAiAlert({
  sumTokensSince: async since => {
    const agg = await prisma.llmUsage.aggregate({
      where: { createdAt: { gte: since } },
      _sum: { totalTokens: true },
    })
    return agg._sum.totalTokens ?? 0
  },
  sendAlert: async (subject, text) => {
    if (!smtpConfigured() || !env.alertEmail) return
    await sendMail({ to: env.alertEmail, subject, text })
  },
  clock: () => Date.now(),
  log: message => console.warn(message),
}, {
  dailyTokens: env.aiAlertDailyTokens,
  jobTokens: env.aiAlertJobTokens,
  concurrencyWarn: 4,
})
