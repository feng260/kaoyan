import Router from '@koa/router'
import { z } from 'zod'
import { requireAuth } from '../../middlewares/auth'
import { apiLimit } from '../../middlewares/ratelimit'
import * as syncService from './service'
import { RESOURCES } from './resources'
import { hub } from '../../shared/ws/Hub'

const router = new Router({ prefix: '/api/v1' })

/**
 * 跨端实时通知:HTTP 写成功后经 WS 广播,他端立即拉取。
 * 带 deviceId 供发送端过滤自身(推送方无需重新拉自己刚写的数据)。
 */
function notifyDataChanged(userGuid: string, deviceId: number, resource: string, full = false) {
  hub.sendToUser(userGuid, { type: 'dataChanged', serverTime: Date.now(), deviceId, resource, full })
}

function notifySettingsChanged(userGuid: string, deviceId: number) {
  hub.sendToUser(userGuid, { type: 'settingsChanged', serverTime: Date.now(), deviceId })
}

/** 批量 upsert:POST /sync/{resource} */
router.post('/sync/:resource', requireAuth, apiLimit(), async ctx => {
  const body = z.union([z.array(z.any()), z.object({ rows: z.array(z.any()) })]).parse(ctx.request.body)
  const rows = Array.isArray(body) ? body : body.rows
  const result = await syncService.upsertResource(ctx.state.auth!.userGuid, ctx.params.resource, rows)
  ctx.body = result
  if (rows.length > 0) {
    notifyDataChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId, ctx.params.resource)
  }
})

/** 增量拉取:GET /sync?since=0&resources=subjects,tasks */
router.get('/sync', requireAuth, apiLimit(), async ctx => {
  const since = Number(ctx.query.since ?? 0)
  if (!Number.isFinite(since) || since < 0) {
    ctx.status = 400
    ctx.body = { error: 'INVALID_PARAMS', message: 'since 应为非负毫秒时间戳' }
    return
  }
  const resources = typeof ctx.query.resources === 'string' && ctx.query.resources
    ? ctx.query.resources.split(',').map(s => s.trim())
    : undefined
  ctx.body = await syncService.pullChanges(ctx.state.auth!.userGuid, since, resources)
})

/** 资源清单(客户端发现可用资源) */
router.get('/sync-meta', requireAuth, async ctx => {
  ctx.body = { resources: RESOURCES, serverTime: Date.now() }
})

/** 全量导出 */
router.get('/backup', requireAuth, apiLimit(), async ctx => {
  ctx.body = await syncService.exportBackup(ctx.state.auth!.userGuid)
})

/** 全量恢复 */
router.post('/backup/restore', requireAuth, apiLimit(), async ctx => {
  ctx.body = await syncService.restoreBackup(ctx.state.auth!.userGuid, ctx.request.body)
  // 覆盖性写:他端需清 since 水位全量重拉(恢复的数据 updated_at 可能早于他端水位)
  notifyDataChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId, 'backup', true)
})

/** 设置 KV */
router.get('/settings', requireAuth, async ctx => {
  ctx.body = await syncService.getSettings(ctx.state.auth!.userGuid)
})

router.put('/settings', requireAuth, async ctx => {
  const body = z.object({ settings: z.record(z.unknown()) }).parse(ctx.request.body)
  ctx.body = await syncService.putSettings(ctx.state.auth!.userGuid, body)
  if (Object.keys(body.settings).length > 0) {
    notifySettingsChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
  }
})

export default router
