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
    let res = await send(buildBody(options.json === true, wantedMaxTokens))

    // 400/422 多半是厂商配置差异而不是真的失败:有的不支持 response_format=json_object,
    // 有的对 max_tokens 上限卡得更死。这种情况降级(去掉 json 约束、收紧 max_tokens)再试一次,
    // 而不是把厂商的 400 原样透给用户。
    if (!res.ok && (res.status === 400 || res.status === 422) && (options.json === true || wantedMaxTokens > 2048)) {
      await res.text().catch(() => '')
      res = await send(buildBody(false, Math.min(wantedMaxTokens, 2048)))
    }

    if (!res.ok) {
      const detail = await res.text().catch(() => '')
      throw new LlmError('HTTP_ERROR', `大模型接口返回 ${res.status}: ${detail.slice(0, 300)}`)
    }

    /** 读出 choices[0].message.content;为空/缺字段时返回 null,由调用方决定降级或报错 */
    const readContent = async (response: Response): Promise<{ payload: any; content: string | null }> => {
      const payload: any = await response.json().catch(() => null)
      const message = payload?.choices?.[0]?.message
      const text = message?.content
      return { payload, content: typeof text === 'string' && text.trim() ? text : null }
    }

    /** 空响应的诊断:finish_reason 与 reasoning_content 是两种空响应的"验尸报告" */
    const describeEmpty = (payload: any, note?: string): string => {
      const choice = payload?.choices?.[0]
      const parts = [`finish_reason=${choice?.finish_reason ?? 'unknown'}`]
      const reasoning = choice?.message?.reasoning_content
      if (typeof reasoning === 'string' && reasoning.trim()) {
        parts.push(`检测到 reasoning_content ${reasoning.length} 字 —— 思考型模型把输出预算花在了推理上,请更换非推理模型或显著加大 max_tokens`)
      }
      if (note) parts.push(note)
      return parts.join(';')
    }

    let { payload, content } = await readContent(res)
    if (content === null && options.json === true) {
      // 200 但内容为空,而请求带了 response_format=json_object:
      // 部分厂商/中转对 json 模式返回 200 空壳而不是报 400(那种走不到上面的降级)。
      // 去掉 json 约束原参数重试一次 —— extractJson 本就容忍 ```json 代码块,不依赖服务端强制。
      res = await send(buildBody(false, wantedMaxTokens))
      if (res.ok) {
        const retry = await readContent(res)
        if (retry.content !== null) return retry.content
        payload = retry.payload
      }
    }
    if (content === null) {
      throw new LlmError('BAD_RESPONSE', `大模型返回内容为空(${describeEmpty(payload)})`)
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
