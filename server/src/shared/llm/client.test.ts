import test from 'node:test'
import assert from 'node:assert/strict'
import { runWithAiUsage, type LlmUsageEvent } from './usage'

process.env.LLM_API_KEY = 'test-key'
process.env.LLM_BASE_URL = 'https://fake.local'
process.env.LLM_MODEL = 'fake-model'
process.env.DATABASE_URL ??= 'mysql://test:test@127.0.0.1:3306/test'
process.env.JWT_SECRET ??= 'test-secret'

async function loadClient() {
  return import('./client')
}

function stubFetch(responses: Array<{ status?: number; body: unknown }>): () => void {
  const original = globalThis.fetch
  let index = 0
  globalThis.fetch = (async () => {
    const next = responses[Math.min(index++, responses.length - 1)]
    return new Response(JSON.stringify(next.body), {
      status: next.status ?? 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }) as typeof fetch
  return () => { globalThis.fetch = original }
}

const reply = (content: string, usage?: Record<string, number>) => ({
  choices: [{ message: { content }, finish_reason: 'stop' }],
  ...(usage ? { usage } : {}),
})

test('json 空内容重试:每次 200 都记一条,按调用次数累加', async () => {
  const { chatComplete } = await loadClient()
  const restore = stubFetch([
    { body: reply('', { prompt_tokens: 10, completion_tokens: 0, total_tokens: 10 }) },
    { body: reply('好', { prompt_tokens: 20, completion_tokens: 10, total_tokens: 30 }) },
  ])
  let captured: LlmUsageEvent[] = []
  try {
    const text = await runWithAiUsage('u1', () => chatComplete({
      feature: 'interview',
      json: true,
      messages: [{ role: 'user', content: '继续' }],
    }), { flush: async (_guid, events) => { captured = events } })
    assert.equal(text, '好')
    assert.equal(captured.length, 2)
    assert.equal(captured[0].feature, 'interview')
    assert.equal(captured[0].totalTokens, 10)
    assert.equal(captured[1].totalTokens, 30)
  } finally {
    restore()
  }
})

test('400 降预算重试:失败响应不计,成功响应记一条', async () => {
  const { chatComplete } = await loadClient()
  const restore = stubFetch([
    { status: 400, body: { error: { message: 'max_tokens too large' } } },
    { body: reply('ok', { prompt_tokens: 3, completion_tokens: 2, total_tokens: 5 }) },
  ])
  let captured: LlmUsageEvent[] = []
  try {
    const text = await runWithAiUsage('u1', () => chatComplete({
      feature: 'plan_generate',
      messages: [{ role: 'user', content: 'hi' }],
    }), { flush: async (_guid, events) => { captured = events } })
    assert.equal(text, 'ok')
    assert.equal(captured.length, 1)
    assert.equal(captured[0].totalTokens, 5)
  } finally {
    restore()
  }
})

test('厂商缺 usage:按字符估算并标记 estimated', async () => {
  const { chatComplete } = await loadClient()
  const restore = stubFetch([{ body: reply('好') }])
  let captured: LlmUsageEvent[] = []
  try {
    await runWithAiUsage('u1', () => chatComplete({
      feature: 'timetable_ocr',
      messages: [{ role: 'user', content: '你好' }],
    }), { flush: async (_guid, events) => { captured = events } })
    assert.equal(captured.length, 1)
    assert.equal(captured[0].estimated, true)
    assert.equal(captured[0].promptTokens, 2) // 2 × 0.6 = 1.2 → 2
    assert.equal(captured[0].completionTokens, 1) // 1 × 0.6 = 0.6 → 1
    assert.equal(captured[0].totalTokens, 3)
  } finally {
    restore()
  }
})

test('图片消息估算:每张按 1000 tokens', async () => {
  const { chatComplete } = await loadClient()
  const restore = stubFetch([{ body: reply('ok') }])
  let captured: LlmUsageEvent[] = []
  try {
    await runWithAiUsage('u1', () => chatComplete({
      feature: 'timetable_ocr',
      vision: true,
      messages: [{
        role: 'user',
        content: [
          { type: 'text', text: 'ok' },
          { type: 'image_url', image_url: { url: 'data:image/png;base64,xx' } },
        ],
      }],
    }), { flush: async (_guid, events) => { captured = events } })
    assert.equal(captured[0].promptTokens, 1001)
    assert.equal(captured[0].estimated, true)
  } finally {
    restore()
  }
})
