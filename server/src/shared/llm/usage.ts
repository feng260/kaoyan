import { AsyncLocalStorage } from 'async_hooks'
import { prisma } from '../prisma'

export type LlmUsageEvent = {
  feature: string
  model: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  estimated: boolean
}

export type UsageSummary = {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  calls: number
  estimatedCalls: number
}

export type RunWithAiUsageOptions = {
  onFinish?: (summary: UsageSummary) => void
  flush?: (userGuid: string, events: LlmUsageEvent[]) => Promise<void>
}

type Store = { events: LlmUsageEvent[] }
const storage = new AsyncLocalStorage<Store>()

/** CJK 字符按 0.6、其余按 0.3 粗略估算;厂商不返回 usage 时兜底 */
export function estimateTokens(text: string): number {
  if (!text) return 0
  const cjk = text.match(/[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/g)?.length ?? 0
  const other = text.length - cjk
  return Math.ceil(cjk * 0.6 + other * 0.3)
}

function toCount(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return null
  return Math.round(value)
}

/** 把厂商返回的 usage 归一化;缺字段或类型不对返回 null,交给估算 */
export function normalizeUsage(raw: unknown): { promptTokens: number; completionTokens: number; totalTokens: number } | null {
  if (!raw || typeof raw !== 'object') return null
  const value = raw as { prompt_tokens?: unknown; completion_tokens?: unknown; total_tokens?: unknown }
  const promptTokens = toCount(value.prompt_tokens)
  const completionTokens = toCount(value.completion_tokens)
  const reportedTotal = toCount(value.total_tokens)
  if (promptTokens === null && completionTokens === null && reportedTotal === null) return null
  const prompt = promptTokens ?? 0
  const completion = completionTokens ?? 0
  return { promptTokens: prompt, completionTokens: completion, totalTokens: reportedTotal ?? prompt + completion }
}

/** 输入消息的估算 prompt:文本按字符估算,图片每张按 1000 tokens */
export function estimatePromptTokens(
  messages: Array<{ content: string | Array<{ type: string; text?: string }> }>,
): number {
  let total = 0
  for (const message of messages) {
    if (typeof message.content === 'string') {
      total += estimateTokens(message.content)
      continue
    }
    for (const part of message.content) {
      if (part.type === 'text') total += estimateTokens(part.text ?? '')
      else if (part.type === 'image_url') total += 1000
    }
  }
  return total
}

export function summarizeUsage(events: LlmUsageEvent[]): UsageSummary {
  return {
    promptTokens: events.reduce((sum, item) => sum + item.promptTokens, 0),
    completionTokens: events.reduce((sum, item) => sum + item.completionTokens, 0),
    totalTokens: events.reduce((sum, item) => sum + item.totalTokens, 0),
    calls: events.length,
    estimatedCalls: events.filter(item => item.estimated).length,
  }
}

/** 无计量会话时为空操作:脚本、单测与未接入路由的调用不受影响 */
export function recordLlmUsage(event: LlmUsageEvent): void {
  storage.getStore()?.events.push(event)
}

export function currentUsageSummary(): UsageSummary | null {
  const store = storage.getStore()
  return store && store.events.length > 0 ? summarizeUsage(store.events) : null
}

async function flushToDb(userGuid: string, events: LlmUsageEvent[]): Promise<void> {
  await prisma.llmUsage.createMany({
    data: events.map(event => ({ userGuid, ...event })),
  })
}

/**
 * 包装一次 AI 请求/后台任务:期间所有 chatComplete 调用都记入本轮会话,
 * 结束时一次落库。计量是旁路,落库失败只告警,绝不改变业务结果。
 */
export async function runWithAiUsage<T>(
  userGuid: string,
  fn: () => Promise<T>,
  options: RunWithAiUsageOptions = {},
): Promise<T> {
  const store: Store = { events: [] }
  try {
    return await storage.run(store, fn)
  } finally {
    const summary = summarizeUsage(store.events)
    try {
      options.onFinish?.(summary)
    } catch (error) {
      console.warn(`[llm] 用量回填失败:${(error as Error)?.message ?? error}`)
    }
    if (store.events.length > 0) {
      try {
        await (options.flush ?? flushToDb)(userGuid, store.events)
      } catch (error) {
        console.warn(`[llm] 用量落库失败:${(error as Error)?.message ?? error}`)
      }
    }
  }
}
