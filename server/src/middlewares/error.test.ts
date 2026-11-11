import test from 'node:test'
import assert from 'node:assert/strict'
import { z } from 'zod'
import { ApiError, errorMiddleware } from './error'

test('zod 校验失败:返回 400 INVALID_PARAMS 与非空 details', async () => {
  const ctx: any = { method: 'POST', path: '/x', status: 404, body: null }
  const zodError = z.object({ n: z.number() }).safeParse({ n: 'x' }).error!
  await errorMiddleware()(ctx, async () => { throw zodError })
  assert.equal(ctx.status, 400)
  assert.equal(ctx.body.error, 'INVALID_PARAMS')
  assert.ok(Array.isArray(ctx.body.details))
  assert.ok(ctx.body.details.length > 0)
  assert.ok(ctx.body.details.every((d: unknown) => typeof d === 'string'))
})

test('ApiError:原样透传 status/code/message/details', async () => {
  const ctx: any = { method: 'POST', path: '/x', status: 404, body: null }
  await errorMiddleware()(ctx, async () => {
    throw new ApiError(409, 'CONFLICT', '冲突', { field: 'x' })
  })
  assert.equal(ctx.status, 409)
  assert.equal(ctx.body.error, 'CONFLICT')
  assert.equal(ctx.body.message, '冲突')
  assert.deepEqual(ctx.body.details, { field: 'x' })
})
