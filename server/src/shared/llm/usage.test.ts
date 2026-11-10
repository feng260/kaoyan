import test from 'node:test'
import assert from 'node:assert/strict'
import {
  currentUsageSummary,
  estimatePromptTokens,
  estimateTokens,
  normalizeUsage,
  recordLlmUsage,
  runWithAiUsage,
  summarizeUsage,
  type LlmUsageEvent,
} from './usage'

const event = (patch: Partial<LlmUsageEvent> = {}): LlmUsageEvent => ({
  feature: 'interview',
  model: 'fake-model',
  promptTokens: 10,
  completionTokens: 5,
  totalTokens: 15,
  estimated: false,
  ...patch,
})

test('estimateTokens:纯中文按 0.6/字向上取整', () => {
  assert.equal(estimateTokens('考研计划'), 3) // 4 × 0.6 = 2.4 → 3
})

test('estimateTokens:纯英文按 0.3/字符向上取整', () => {
  assert.equal(estimateTokens('hello world'), 4) // 11 × 0.3 = 3.3 → 4
})

test('estimateTokens:中英混合分别计权', () => {
  assert.equal(estimateTokens('AI 计划'), 3) // 3 × 0.3 + 2 × 0.6 = 2.1 → 3
})

test('estimateTokens:空串为 0', () => {
  assert.equal(estimateTokens(''), 0)
})

test('normalizeUsage:完整 usage 原样归一化', () => {
  assert.deepEqual(
    normalizeUsage({ prompt_tokens: 100, completion_tokens: 50, total_tokens: 150 }),
    { promptTokens: 100, completionTokens: 50, totalTokens: 150 },
  )
})

test('normalizeUsage:缺 total 时用 prompt+completion 补齐', () => {
  assert.deepEqual(
    normalizeUsage({ prompt_tokens: 8, completion_tokens: 2 }),
    { promptTokens: 8, completionTokens: 2, totalTokens: 10 },
  )
})

test('normalizeUsage:没有 usage 返回 null', () => {
  assert.equal(normalizeUsage(undefined), null)
  assert.equal(normalizeUsage(null), null)
  assert.equal(normalizeUsage({}), null)
  assert.equal(normalizeUsage({ prompt_tokens: 'x' }), null)
})

test('estimatePromptTokens:文本按字符、图片每张 1000', () => {
  const tokens = estimatePromptTokens([
    { content: '你好' }, // 2 × 0.6 = 1.2 → 2
    { content: [{ type: 'text', text: 'ok' }] }, // 2 × 0.3 = 0.6 → 1
    { content: [{ type: 'image_url' }] }, // 1000
  ])
  assert.equal(tokens, 1003)
})

test('summarizeUsage:累加计数并统计估算次数', () => {
  assert.deepEqual(summarizeUsage([event(), event({ estimated: true, totalTokens: 20 })]), {
    promptTokens: 20,
    completionTokens: 10,
    totalTokens: 35,
    calls: 2,
    estimatedCalls: 1,
  })
})

test('runWithAiUsage:成功时汇总落库并回调 onFinish', async () => {
  let flushed: LlmUsageEvent[] = []
  let finished = 0
  const result = await runWithAiUsage('u1', async () => {
    recordLlmUsage(event())
    recordLlmUsage(event({ totalTokens: 5 }))
    return 42
  }, {
    onFinish: summary => { finished = summary.calls },
    flush: async (_guid, events) => { flushed = events },
  })
  assert.equal(result, 42)
  assert.equal(flushed.length, 2)
  assert.equal(finished, 2)
})

test('runWithAiUsage:抛错时已发生的用量仍然落库', async () => {
  let flushed = false
  await assert.rejects(
    runWithAiUsage('u1', async () => {
      recordLlmUsage(event())
      throw new Error('boom')
    }, { flush: async () => { flushed = true } }),
    /boom/,
  )
  assert.equal(flushed, true)
})

test('runWithAiUsage:落库失败不影响业务结果', async () => {
  const originalWarn = console.warn
  let warned = ''
  console.warn = (message: string) => { warned = message }
  try {
    const result = await runWithAiUsage('u1', async () => {
      recordLlmUsage(event())
      return 'ok'
    }, { flush: async () => { throw new Error('db down') } })
    assert.equal(result, 'ok')
    assert.match(warned, /用量落库失败/)
  } finally {
    console.warn = originalWarn
  }
})

test('runWithAiUsage:没有用量时不调用 flush', async () => {
  let flushed = false
  const result = await runWithAiUsage('u1', async () => 'ok', {
    flush: async () => { flushed = true },
  })
  assert.equal(result, 'ok')
  assert.equal(flushed, false)
})

test('currentUsageSummary:会话外为 null,会话内为本轮汇总', async () => {
  assert.equal(currentUsageSummary(), null)
  const inside = await runWithAiUsage('u1', async () => {
    recordLlmUsage(event())
    return currentUsageSummary()
  }, { flush: async () => {} })
  assert.equal(inside?.totalTokens, 15)
})
