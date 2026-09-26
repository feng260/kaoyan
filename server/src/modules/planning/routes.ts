import Router from '@koa/router'
import { ApiError } from '../../middlewares/error'
import { requireAuth } from '../../middlewares/auth'
import { apiLimit } from '../../middlewares/ratelimit'
import { hub } from '../../shared/ws/Hub'
import { profileInputSchema, type ProfileInput } from './schemas'
import { planningService } from './service'

const router = new Router({ prefix: '/api/v1' })

/**
 * 档案/计划接口(Phase 0)。计划是服务端权威资源:
 * 客户端只负责把问卷答案提交上来、把生成好的计划读回去。
 *
 * 错误码约定:
 * - PROFILE_INCOMPLETE  还没有档案就想生成计划
 * - INVALID_EXAM_DATE   考试日期不合法(已过期/格式错误)
 * - INVALID_PARAMS      其它字段校验失败,details 里逐条说明
 * - PLAN_GENERATION_FAILED 服务端生成/落库失败
 */
function parseProfileBody(body: unknown): ProfileInput {
  const parsed = profileInputSchema.safeParse(body)
  if (parsed.success) return parsed.data
  // 考试日期单独给错误码:客户端要据此把光标打回日期选择器,而不是泛泛提示「参数错误」
  const examIssue = parsed.error.issues.find(issue => issue.path[0] === 'examDate')
  if (examIssue) {
    throw new ApiError(400, 'INVALID_EXAM_DATE', examIssue.message)
  }
  // 其余字段沿用统一约定:ZodError 由错误中间件转成 INVALID_PARAMS + details
  throw parsed.error
}

/** 计划变更后通知该用户其它设备(多端登录时不用手动下拉刷新) */
function notifyPlanChanged(userGuid: string, deviceId: number) {
  hub.sendToUser(userGuid, { type: 'planChanged', serverTime: Date.now(), deviceId }, deviceId)
}

/** 读取备考档案 */
router.get('/profile', requireAuth, async ctx => {
  ctx.body = {
    profile: await planningService.getProfile(ctx.state.auth!.userGuid),
    serverTime: Date.now(),
  }
})

/** 提交/更新备考档案(首次问卷和后续修改走同一个接口) */
router.put('/profile', requireAuth, apiLimit(), async ctx => {
  const input = parseProfileBody(ctx.request.body)
  const profile = await planningService.upsertProfile(ctx.state.auth!.userGuid, input)
  ctx.body = { profile, serverTime: Date.now() }
})

/** 生成计划:同一档案重复调用是幂等的(内容一致),但会产出新版本并归档旧版本 */
router.post('/plans/generate', requireAuth, apiLimit(), async ctx => {
  const plan = await planningService.generatePlan(ctx.state.auth!.userGuid)
  ctx.status = 201
  ctx.body = { plan, serverTime: Date.now() }
  notifyPlanChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
})

/** 读取当前生效计划;没有档案/没有计划时返回 null,由客户端回退到本地计划 */
router.get('/plans/active', requireAuth, async ctx => {
  ctx.body = {
    plan: await planningService.getActivePlan(ctx.state.auth!.userGuid),
    serverTime: Date.now(),
  }
})

export default router
