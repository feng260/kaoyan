import test from 'node:test'
import assert from 'node:assert/strict'
import { aiUsageMeter } from './aiUsage'
import { currentUsageSummary, recordLlmUsage, type LlmUsageEvent } from '../shared/llm/usage'

const event: LlmUsageEvent = {
  feature: 'interview',
  model: 'fake-model',
  promptTokens: 1,
  completionTokens: 2,
  totalTokens: 3,
  estimated: false,
}

test('计量中间件:把本轮汇总写进响应并落库', async () => {
  let flushed: LlmUsageEvent[] = []
  const ctx: any = { state: { auth: { userGuid: 'u1', deviceId: 7 } }, body: null }
  await aiUsageMeter({ flush: async (_guid, events) => { flushed = events } })(ctx, async () => {
    recordLlmUsage(event)
    ctx.body = { usage: currentUsageSummary() }
  })
  assert.equal(ctx.body.usage.totalTokens, 3)
  assert.equal(flushed.length, 1)
})

test('未登录时直接放行,不开启计量会话', async () => {
  let flushed = false
  const ctx: any = { state: {} }
  await aiUsageMeter({ flush: async () => { flushed = true } })(ctx, async () => {
    assert.equal(currentUsageSummary(), null)
  })
  assert.equal(flushed, false)
})
