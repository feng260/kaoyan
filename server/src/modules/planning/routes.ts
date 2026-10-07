import Router from '@koa/router'
import { z } from 'zod'
import { ApiError } from '../../middlewares/error'
import { requireAuth } from '../../middlewares/auth'
import { apiLimit } from '../../middlewares/ratelimit'
import { hub } from '../../shared/ws/Hub'
import { profileInputSchema, type ProfileInput } from './schemas'
import { PLAN_ITEM_STATUSES, planningService, slimToggleResponse } from './service'
import { maybeNotifyPlanReminder } from './planReminder'

const router = new Router({ prefix: '/api/v1' })

const itemStatusSchema = z.object({
  status: z.enum(PLAN_ITEM_STATUSES),
})

const interviewSchema = z.object({
  // 整段对话历史由客户端携带;内容长度与角色在 aiCoach 里还会再收敛一次(裁到 24 条、每条 1000 字),
  // 这里放宽上限只是为了别让考生多打几行字就吃一个 400。
  messages: z
    .array(
      z.object({
        role: z.enum(['user', 'assistant']),
        content: z.string().min(1).max(8000),
      }),
    )
    .max(60)
    .optional(),
  // 用户主动点「直接开始生成」
  force: z.boolean().optional(),
})

/**
 * 课表图片识别:把「单双周课表」这类每周固定占用交给视觉模型识别,免去手工填格子。
 * 图片走 base64 + JSON(bodyparser 上限 8mb),客户端需先压缩;必须注册在 `/plans/:id` 之前。
 */
const timetableSchema = z.object({
  image: z.string().min(64).max(7_000_000),
  mimeType: z.enum(['image/jpeg', 'image/png', 'image/webp']).optional(),
})

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
  const plan = await planningService.generatePlanDraft(ctx.state.auth!.userGuid)
  ctx.status = 201
  ctx.body = { plan, serverTime: Date.now() }
})

router.post('/plans/:id/confirm', requireAuth, apiLimit(), async ctx => {
  const planId = Number(ctx.params.id)
  if (!Number.isInteger(planId) || planId <= 0) {
    throw new ApiError(400, 'INVALID_PARAMS', '计划 id 不合法')
  }
  const plan = await planningService.confirmPlan(ctx.state.auth!.userGuid, planId)
  ctx.body = { plan, serverTime: Date.now() }
  notifyPlanChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
})

/**
 * 备考面谈:推进一轮对话。无状态,客户端每次把整段历史带上来。
 * 返回 { reply, options, done, brief }:done 为 true 时客户端显示「生成完整计划」。
 * 必须注册在 `/plans/:id` 之前。
 */
router.post('/plans/interview', requireAuth, apiLimit(), async ctx => {
  const parsed = interviewSchema.safeParse(ctx.request.body ?? {})
  if (!parsed.success) {
    throw new ApiError(400, 'INVALID_PARAMS', '面谈参数不合法')
  }
  const result = await planningService.interview(ctx.state.auth!.userGuid, {
    messages: parsed.data.messages ?? [],
    force: parsed.data.force === true,
  })
  ctx.body = { ...result, serverTime: Date.now() }
})

/**
 * 课表图片识别:返回识别到的固定占用与存疑提示,由用户校正后再随问卷一起提交。
 * 必须注册在 `/plans/:id` 之前。
 */
router.post('/plans/timetable', requireAuth, apiLimit(), async ctx => {
  const parsed = timetableSchema.safeParse(ctx.request.body ?? {})
  if (!parsed.success) {
    throw new ApiError(400, 'INVALID_PARAMS', '课表图片不合法(需要 base64 或链接,且不超过 8mb)')
  }
  const result = await planningService.parseTimetable(ctx.state.auth!.userGuid, parsed.data)
  ctx.body = { ...result, serverTime: Date.now() }
})

/** 行程调整:一句话描述突发情况;长度口径与面谈消息一致 */
const adjustSchema = z.object({
  message: z.string().min(1).max(1000),
})

/**
 * 行程调整:一句话描述突发情况,服务端解析意图并产出待确认调整单。
 * 返回 { adjustment, reply, plan }:adjustment 为 null 时 reply 是追问/闲聊回复。
 * 必须注册在 `/plans/:id` 之前(与 interview/timetable 同理)。
 */
router.post('/plans/adjust', requireAuth, apiLimit(), async ctx => {
  const parsed = adjustSchema.safeParse(ctx.request.body)
  if (!parsed.success) throw parsed.error
  const result = await planningService.adjust(ctx.state.auth!.userGuid, { message: parsed.data.message })
  ctx.body = { ...result, serverTime: Date.now() }
})

/** 最近一张待确认/已生效调整单:App 冷启动、切页恢复卡片 */
router.get('/plans/adjustments/latest', requireAuth, async ctx => {
  const adjustment = await planningService.latestAdjustment(ctx.state.auth!.userGuid)
  ctx.body = { adjustment, serverTime: Date.now() }
})

/** 计划体检:落后分钟数/过期任务数/一句话建议。App 周期轮询;服务端顺手触发在线提醒( fire-and-forget ) */
router.get('/plans/checkup', requireAuth, async ctx => {
  const checkup = await planningService.checkup(ctx.state.auth!.userGuid)
  void maybeNotifyPlanReminder(ctx.state.auth!.userGuid)
  ctx.body = { ...checkup, serverTime: Date.now() }
})

router.post('/plans/adjustments/:id/confirm', requireAuth, apiLimit(), async ctx => {
  const id = Number(ctx.params.id)
  if (!Number.isInteger(id) || id <= 0) throw new ApiError(400, 'INVALID_PARAMS', '调整单 id 不合法')
  const plan = await planningService.confirmAdjustment(ctx.state.auth!.userGuid, id)
  ctx.body = { plan, serverTime: Date.now() }
  notifyPlanChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
})

router.post('/plans/adjustments/:id/undo', requireAuth, apiLimit(), async ctx => {
  const id = Number(ctx.params.id)
  if (!Number.isInteger(id) || id <= 0) throw new ApiError(400, 'INVALID_PARAMS', '调整单 id 不合法')
  const plan = await planningService.undoAdjustment(ctx.state.auth!.userGuid, id)
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

/**
 * 长文档轻量端点:只回 document 不回全量 items(一份几百天的计划 items 达数百 KB,
 * 文档页只需要这份 8 章文档)。必须注册在 `/plans/:id` 之前。
 */
router.get('/plans/active/document', requireAuth, async ctx => {
  const document = await planningService.getActiveDocument(ctx.state.auth!.userGuid)
  ctx.body = { document, serverTime: Date.now() }
})

/**
 * 长文档补生成:生成计划时文档部分失败(加分项不连累主计划)后,用当前生效计划重出一份。
 * 每日清单不动,只重写 documentJson。服务端要跑 5 段并发的文档生成,耗时约 1-2 分钟。
 * 必须注册在 `/plans/:id` 之前。
 */
router.post('/plans/active/document', requireAuth, apiLimit(), async ctx => {
  const document = await planningService.regenerateActiveDocument(ctx.state.auth!.userGuid)
  ctx.body = { document, serverTime: Date.now() }
})


/**
 * 历史计划列表(含当前生效计划),按版本倒序。
 * 重新生成会归档旧计划,这里是用户回看旧版本的唯一入口。
 * 必须注册在 `/plans/:id` 之前,否则 "history" 会被当成 id 匹配掉。
 */
router.get('/plans/history', requireAuth, async ctx => {
  ctx.body = {
    plans: await planningService.listPlanHistory(ctx.state.auth!.userGuid),
    serverTime: Date.now(),
  }
})

/** 只读查看某一份计划(阶段 + 计划项);只能读到自己名下的计划,否则按不存在处理 */
router.get('/plans/:id', requireAuth, async ctx => {
  const planId = Number(ctx.params.id)
  if (!Number.isInteger(planId) || planId <= 0) {
    throw new ApiError(400, 'INVALID_PARAMS', '计划 id 不合法')
  }
  const plan = await planningService.getPlanById(ctx.state.auth!.userGuid, planId)
  if (!plan) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划不存在')
  ctx.body = { plan, serverTime: Date.now() }
})

/**
 * 勾选/取消勾选某个计划项 —— 「每日完成的计划」多设备同步的唯一写入口。
 * 客户端在本地先把勾选落库(离线可用),在线时再补一发到这里;成功后会广播给该用户其它设备。
 */
router.patch('/plans/items/:id', requireAuth, apiLimit(), async ctx => {
  const itemId = Number(ctx.params.id)
  if (!Number.isInteger(itemId) || itemId <= 0) {
    throw new ApiError(400, 'INVALID_PARAMS', '计划项 id 不合法')
  }
  const parsed = itemStatusSchema.safeParse(ctx.request.body)
  if (!parsed.success) {
    throw new ApiError(400, 'INVALID_PARAMS', 'status 只能是 pending 或 done')
  }
  const plan = await planningService.setItemStatus(ctx.state.auth!.userGuid, itemId, parsed.data.status)
  // slim=1 时只回被改的那一项与进度:全量 plan 数百 KB,而勾选是高频操作;
  // 客户端用本地缓存的 plan 合并该项即可,省掉 ~99% 的下行体积。默认仍回全量(兼容旧客户端)
  if (ctx.query.slim === '1') {
    const slim = slimToggleResponse(plan, itemId) ?? { item: plan.items.find(i => i.id === itemId)!, progress: plan.progress }
    ctx.body = { ...slim, serverTime: Date.now() }
  } else {
    ctx.body = { plan, serverTime: Date.now() }
  }
  notifyPlanChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
})

export default router
