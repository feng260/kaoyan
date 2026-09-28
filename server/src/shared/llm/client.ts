import { env } from '../../config/env'

/**
 * 极简的 OpenAI 兼容 Chat Completions 客户端。
 *
 * 只依赖 Node 18+ 的原生 fetch,不引入 SDK —— 换厂商就是改 baseUrl/model 两行配置:
 * - DeepSeek   https://api.deepseek.com           deepseek-flash
 * - 豆包方舟   https://ark.cn-beijing.volces.com/api/v3   <接入点 ID>
 * - 智谱       https://open.bigmodel.cn/api/paas/v4       glm-4-flash
 * - 硅基流动   https://api.siliconflow.cn/v1      deepseek-ai/DeepSeek-V3
 */

export type ChatMessage = { role: 'system' | 'user' | 'assistant'; content: string }

export class LlmError extends Error {
  constructor(public code: 'NOT_CONFIGURED' | 'TIMEOUT' | 'HTTP_ERROR' | 'BAD_RESPONSE', message: string) {
    super(message)
    this.name = 'LlmError'
  }
}

export type ChatOptions = {
  messages: ChatMessage[]
  /** 要求模型返回严格 JSON(多数厂商支持 response_format=json_object) */
  json?: boolean
  temperature?: number
  maxTokens?: number
  timeoutMs?: number
}

/** 调一次对话补全,返回首条候选的文本内容。失败一律抛 LlmError,由调用方决定降级策略。 */
export async function chatComplete(options: ChatOptions): Promise<string> {
  if (!env.llm.apiKey) {
    throw new LlmError('NOT_CONFIGURED', '未配置 LLM_API_KEY')
  }

  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), options.timeoutMs ?? env.llm.timeoutMs)
  try {
    const res = await fetch(`${env.llm.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${env.llm.apiKey}`,
      },
      body: JSON.stringify({
        model: env.llm.model,
        messages: options.messages,
        temperature: options.temperature ?? 0.4,
        max_tokens: options.maxTokens ?? env.llm.maxTokens,
        ...(options.json ? { response_format: { type: 'json_object' } } : {}),
      }),
      signal: controller.signal,
    })

    if (!res.ok) {
      const detail = await res.text().catch(() => '')
      throw new LlmError('HTTP_ERROR', `大模型接口返回 ${res.status}: ${detail.slice(0, 300)}`)
    }

    const data: any = await res.json()
    const content = data?.choices?.[0]?.message?.content
    if (typeof content !== 'string' || !content.trim()) {
      throw new LlmError('BAD_RESPONSE', '大模型返回内容为空')
    }
    return content
  } catch (error) {
    if (error instanceof LlmError) throw error
    if ((error as Error)?.name === 'AbortError') {
      throw new LlmError('TIMEOUT', `大模型接口超时(${options.timeoutMs ?? env.llm.timeoutMs}ms)`)
    }
    throw new LlmError('HTTP_ERROR', `调用大模型失败:${(error as Error)?.message ?? '未知错误'}`)
  } finally {
    clearTimeout(timeout)
  }
}

/**
 * 从模型返回里抠出 JSON。
 * 即使要求了 json_object,部分厂商仍会包一层 ```json 代码块或加前后缀说明,这里做容错。
 */
export function extractJson<T = unknown>(raw: string): T {
  const text = raw.trim().replace(/^```(?:json)?/i, '').replace(/```$/, '').trim()
  try {
    return JSON.parse(text) as T
  } catch {
    const start = text.search(/[[{]/)
    const end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'))
    if (start >= 0 && end > start) {
      return JSON.parse(text.slice(start, end + 1)) as T
    }
    throw new LlmError('BAD_RESPONSE', '大模型返回的不是合法 JSON')
  }
}
