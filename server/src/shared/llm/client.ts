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

/** 多模态消息里的文本片段 */
export type ChatTextPart = { type: 'text'; text: string }
/** 多模态消息里的图片片段:url 支持 https 链接或 data:image/...;base64,xxx */
export type ChatImagePart = { type: 'image_url'; image_url: { url: string } }
export type ChatContentPart = ChatTextPart | ChatImagePart

export type ChatMessage = { role: 'system' | 'user' | 'assistant'; content: string | ChatContentPart[] }

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
  /**
   * 走视觉模型(env.llm.vision)。默认就是对话模型本身;只有显式配了
   * LLM_VISION_MODEL 时才会切到另一个模型,所以调用方只管照开。
   */
  vision?: boolean
}

/** 调一次对话补全,返回首条候选的文本内容。失败一律抛 LlmError,由调用方决定降级策略。 */
export async function chatComplete(options: ChatOptions): Promise<string> {
  const config = options.vision ? env.llm.vision : env.llm
  if (options.vision && (!config.apiKey || !config.model)) {
    throw new LlmError('NOT_CONFIGURED', '未配置视觉模型(需要 LLM_VISION_API_KEY 与 LLM_VISION_MODEL)')
  }
  if (!config.apiKey) {
    throw new LlmError('NOT_CONFIGURED', '未配置 LLM_API_KEY')
  }

  const timeoutMs = options.timeoutMs ?? config.timeoutMs
  const wantedMaxTokens = options.maxTokens ?? config.maxTokens

  /** 发一次请求,超时用 AbortController 兜住(每次重试都要新的 controller) */
  const send = async (body: Record<string, unknown>) => {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), timeoutMs)
    try {
      return await fetch(`${config.baseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${config.apiKey}`,
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      })
    } finally {
      clearTimeout(timeout)
    }
  }

  const buildBody = (json: boolean, maxTokens: number): Record<string, unknown> => ({
    model: config.model,
    messages: options.messages,
    temperature: options.temperature ?? 0.4,
    max_tokens: maxTokens,
    ...(json ? { response_format: { type: 'json_object' } } : {}),
  })

  try {
    // 预算自适应阶梯:两个方向都会自动落到可用档 ——
    // · 400/422(厂商 max_tokens 上限更低 / 不支持 json_object):预算减半 + 去 JSON 约束
    // · 200 空 content 且是思考型模型(finish_reason=length + reasoning_content):
    //   思考把预算花光了,预算翻倍给足再试(上限 65536,覆盖 deepseek-reasoner 的输出上限)
    const HARD_CEILING = 65536
    let budget = wantedMaxTokens
    let useJson = options.json === true

    let res = await send(buildBody(useJson, budget))
    let payload: any = null
    let content: string | null = null

    for (let attempt = 0; attempt < 5; attempt++) {
      if (!res.ok) {
        if (res.status === 400 || res.status === 422) {
          await res.text().catch(() => '')
          if (budget > 2048) budget = Math.max(2048, Math.floor(budget / 2))
          if (useJson) useJson = false
          res = await send(buildBody(useJson, budget))
          continue
        }
        const detail = await res.text().catch(() => '')
        throw new LlmError('HTTP_ERROR', `大模型接口返回 ${res.status}: ${detail.slice(0, 300)}`)
      }

      payload = await res.json().catch(() => null)
      const message = payload?.choices?.[0]?.message
      const text = message?.content
      content = typeof text === 'string' && text.trim() ? text : null
      if (content !== null) break

      // 200 空响应:先怀疑 json_object 空壳(去掉约束),再怀疑思考型模型吃满预算(翻倍)。
      // 每次空响应都是一次完整计费调用,所以两档补救各只走一次,走完就带诊断报错。
      const finishReason = payload?.choices?.[0]?.finish_reason ?? 'unknown'
      const reasoning = typeof message?.reasoning_content === 'string' ? message.reasoning_content : ''
      const describe = `finish_reason=${finishReason}` +
        (reasoning.trim() ? `;reasoning_content ${reasoning.length} 字` : '')
      if (attempt < 4 && useJson) {
        useJson = false
      } else if (attempt < 4 && reasoning.trim() && finishReason === 'length' && budget < HARD_CEILING) {
        budget = Math.min(HARD_CEILING, budget * 2)
      } else {
        const hint = reasoning.trim()
          ? `思考型模型(reasoning_content ${reasoning.length} 字)耗尽了输出预算,建议更换非推理模型或继续加大 max_tokens`
          : '模型未输出任何内容'
        throw new LlmError('BAD_RESPONSE', `大模型返回内容为空(${describe};${hint})`)
      }
      res = await send(buildBody(useJson, budget))
    }
    if (content === null) {
      throw new LlmError('BAD_RESPONSE', '大模型返回内容为空(连续多次尝试仍未得到输出)')
    }
    return content
  } catch (error) {
    if (error instanceof LlmError) throw error
    if ((error as Error)?.name === 'AbortError') {
      throw new LlmError('TIMEOUT', `大模型接口超时(${timeoutMs}ms)`)
    }
    throw new LlmError('HTTP_ERROR', `调用大模型失败:${(error as Error)?.message ?? '未知错误'}`)
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
