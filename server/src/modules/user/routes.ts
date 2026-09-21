import Router from '@koa/router'
import { requireAuth } from '../../middlewares/auth'
import * as userService from './service'

const router = new Router({ prefix: '/api/v1/devices' })

router.get('/', requireAuth, async ctx => {
  ctx.body = { devices: await userService.listDevices(ctx.state.auth!.userGuid) }
})

router.delete('/:id', requireAuth, async ctx => {
  const id = Number(ctx.params.id)
  if (!Number.isInteger(id)) { ctx.status = 400; ctx.body = { error: 'INVALID_PARAMS', message: '设备 id 不合法' }; return }
  await userService.revokeDevice(ctx.state.auth!.userGuid, id)
  ctx.status = 204
})

router.delete('/', requireAuth, async ctx => {
  await userService.revokeAll(ctx.state.auth!.userGuid)
  ctx.status = 204
})

export default router
