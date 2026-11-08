import Router from '@koa/router'
import { z } from 'zod'
import { ApiError } from '../../middlewares/error'
import { requireAuth } from '../../middlewares/auth'
import { apiLimit } from '../../middlewares/ratelimit'
import { hub } from '../../shared/ws/Hub'
import { profileInputSchema, type ProfileInput } from './schemas'
import { PLAN_ITEM_STATUSES, planningService, slimToggleResponse } from './service'
import { maybeNotifyPlanReminder } from './planReminder'
import { normalizeTzOffset, shouldNotifyPlanReminder } from './checkup'

const router = new Router({ prefix: '/api/v1' })

/**
 * 客户端(Kotlin explicitNulls)会把未填字段显式发成 null。
 * 在这些接口里 null 与「没传」等价,所以统一用 nullish 接收,再在入库前剔除 null。
 */
type DropNull<T extends object> = {
  [K in keyof T as undefined extends T[K] ? never : K]: Exclude<T[K], null>
} & {
  [K in keyof T as undefined extends T[K] ? K : never]?: Exclude<T[K], null>
}

function dropNulls<T extends object>(input: T): DropNull<T> {
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(input)) {
    if (value !== null && value !== undefined) out[key] = value
  }
  return out as DropNull<T>
}

/** 计划项手工编辑:status 与内容字段二选一,混用由 service 拒绝;字段范围与 service 校验保持一致 */
const planItemPatchSchema = z.object({
  status: z.enum(PLAN_ITEM_STATUSES).nullish(),
  planDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).nullish(),
  minutes: z.number().int().min(5).max(480).nullish(),
  title: z.string().trim().min(1).max(64).nullish(),
  subject: z.string().trim().min(1).max(32).nullish(),
  locked: z.boolean().nullish(),
})

const planItemCreateSchema = z.object({
  planId: z.number().int().positive().nullish(),
  subject: z.string().trim().min(1).max(32),
  title: z.string().trim().min(1).max(64),
  planDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  minutes: z.number().int().min(5).max(480),
  locked: z.boolean().nullish(),
})

/** 生成请求可带客户端确认过的口径哈希;allowShortfall 表示用户明确选择「尽力而为」 */
const generateSchema = z.object({
  briefHash: z.string().length(64).nullish(),
  allowShortfall: z.boolean().nullish(),
}).optional()

/** 口径手工修改:逐科剩余量/里程碑与每周固定占用 */
const briefPatchSchema = z.object({
  examSubjects: z.array(z.object({
    name: z.string().trim().min(1).max(32),
    remainingMinutes: z.number().int().min(0).max(500000).nullish(),
    milestone: z.string().max(64).nullish(),
    // 空串表示「清掉截止日」,和未填一样按不修改处理
    milestoneDate: z.union([z.string().regex(/^\d{4}-\d{2}-\d{2}$/), z.literal('')]).nullish(),
    milestoneMinutes: z.number().int().min(0).max(500000).nullish(),
    estimated: z.boolean().nullish(),
    scope: z.string().max(500).nullish(),
    progress: z.string().max(500).nullish(),
  })).max(20).nullish(),
  fixedCommitments: z.array(z.object({
    weekday: z.number().int().min(1).max(7),
    start: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/),
    end: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/),
    label: z.string().trim().min(1).max(32),
    // 省略 = 每周都占。Zod 默认会剥掉未声明的键,少这一行会让手工改口径的入口静默丢掉单双周标记
    weekParity: z.enum(['odd', 'even']).optional(),
  })).max(40).nullish(),
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

/** 计划口径:确认页读取简报、预算与每周容量;生成请求带 briefHash 回来证明用户看过这份口径 */
router.get('/plans/brief', requireAuth, async ctx => {
  ctx.body = { ...await planningService.getBrief(ctx.state.auth!.userGuid), serverTime: Date.now() }
})

/** 手工改口径:逐科剩余量/里程碑与每周固定占用;计划生成中禁止修改 */
router.patch('/plans/brief', requireAuth, apiLimit(), async ctx => {
  const parsed = briefPatchSchema.safeParse(ctx.request.body ?? {})
  if (!parsed.success) {
    throw new ApiError(400, 'INVALID_PARAMS', '计划口径参数不合法', {
      fields: parsed.error.issues.map(issue => ({ field: issue.path.join('.'), message: issue.message })),
    })
  }
  const normalized = dropNulls(parsed.data)
  const examSubjects = normalized.examSubjects?.map(subject => {
    const item = dropNulls(subject)
    if (item.milestoneDate === '') delete item.milestoneDate
    return item
  })
  const result = await planningService.patchBrief(ctx.state.auth!.userGuid, {
    ...normalized,
    examSubjects,
  })
  ctx.body = { ...result, serverTime: Date.now() }
})

/** 生成计划:同一档案重复调用是幂等的(内容一致),但会产出新版本并归档旧版本 */
router.post('/plans/generate', requireAuth, apiLimit(), async ctx => {
  const parsed = generateSchema.safeParse(ctx.request.body ?? {})
  if (!parsed.success) throw new ApiError(400, 'INVALID_PARAMS', '生成参数不合法')
  const plan = await planningService.generatePlanDraft(ctx.state.auth!.userGuid, dropNulls(parsed.data ?? {}))
  ctx.status = 201
  ctx.body = { plan, serverTime: Date.now() }
})

/**
 * 异步生成计划:立刻返回 jobId,后台跑骨架/细化/校验/评审/文档全链路。
 * 进度同时通过 WebSocket 的 planGeneration 事件推送;客户端也可以用下面的 GET 轮询兜底。
 * 同一用户已有运行中的任务时复用同一个 jobId,连点不会产出多份草稿。
 */
router.post('/plans/generate/jobs', requireAuth, apiLimit(), async ctx => {
  const userGuid = ctx.state.auth!.userGuid
  const deviceId = ctx.state.auth!.deviceId
  const parsed = generateSchema.safeParse(ctx.request.body ?? {})
  if (!parsed.success) throw new ApiError(400, 'INVALID_PARAMS', '生成参数不合法')
  const { jobId, job } = await planningService.startGeneration(userGuid, updated => {
    hub.sendToUser(userGuid, { type: 'planGeneration', job: updated, serverTime: Date.now() }, deviceId)
    if (updated.status === 'done') notifyPlanChanged(userGuid, deviceId)
  }, dropNulls(parsed.data ?? {}))
  ctx.status = 202
  ctx.body = { jobId, job, serverTime: Date.now() }
})

router.get('/plans/generate/jobs/:id', requireAuth, async ctx => {
  const job = await planningService.getGenerationJob(ctx.state.auth!.userGuid, String(ctx.params.id))
  if (!job) throw new ApiError(404, 'GENERATION_JOB_NOT_FOUND', '生成任务不存在或已过期,请重新发起')
  ctx.body = { job, serverTime: Date.now() }
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
  // 本次消息之前的最近几轮对话(旧到新):「对」「嗯」这类简短确认要对着上一轮追问才读得懂;
  // 旧客户端不带此字段,可选,服务端按没有历史处理
  history: z.array(z.object({
    role: z.enum(['user', 'assistant']),
    content: z.string().min(1).max(8000),
  })).max(12).optional(),
})

/**
 * 行程调整:一句话描述突发情况,服务端解析意图并产出待确认调整单。
 * 返回 { adjustment, reply, plan }:adjustment 为 null 时 reply 是追问/闲聊回复。
 * 必须注册在 `/plans/:id` 之前(与 interview/timetable 同理)。
 */
router.post('/plans/adjust', requireAuth, apiLimit(), async ctx => {
  const parsed = adjustSchema.safeParse(ctx.request.body)
  if (!parsed.success) throw parsed.error
  const result = await planningService.adjust(ctx.state.auth!.userGuid, {
    message: parsed.data.message,
    history: parsed.data.history,
  })
  // lock_task 是确认前唯一会直接改计划的意图;调整单本身不广播,等确认
  if (result.changedPlan) notifyPlanChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
  ctx.body = { ...result, serverTime: Date.now() }
})

/** 最近一张待确认/已生效调整单:App 冷启动、切页恢复卡片 */
router.get('/plans/adjustments/latest', requireAuth, async ctx => {
  const adjustment = await planningService.latestAdjustment(ctx.state.auth!.userGuid)
  ctx.body = { adjustment, serverTime: Date.now() }
})

/** 计划体检:周执行/落后任务/未来负荷。App 周期轮询;服务端按 notify 开关顺手触发提醒 */
router.get('/plans/checkup', requireAuth, async ctx => {
  const tzOffset = normalizeTzOffset(ctx.query.tzOffset)
  const notify = shouldNotifyPlanReminder(ctx.query.notify)
  const checkup = await planningService.checkup(ctx.state.auth!.userGuid, { tzOffset })
  if (notify) void maybeNotifyPlanReminder(ctx.state.auth!.userGuid)
  ctx.body = { ...checkup, serverTime: Date.now() }
})

router.post('/plans/adjustments/:id/select', requireAuth, apiLimit(), async ctx => {
  const id = Number(ctx.params.id)
  if (!Number.isInteger(id) || id <= 0) throw new ApiError(400, 'INVALID_PARAMS', '调整单 id 不合法')
  const parsed = z.object({ optionId: z.string().min(1).max(32) }).safeParse(ctx.request.body ?? {})
  if (!parsed.success) throw new ApiError(400, 'INVALID_PARAMS', '请选择一个调整方案')
  const adjustment = await planningService.selectAdjustmentOption(ctx.state.auth!.userGuid, id, parsed.data.optionId)
  ctx.body = { adjustment, serverTime: Date.now() }
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
 * 最近一份未确认草稿:异步生成任务随进程重启/超过 30 分钟 TTL 丢失后,
 * 客户端用它恢复草稿预览,避免考生白等一次生成。必须注册在 `/plans/:id` 之前。
 */
router.get('/plans/draft', requireAuth, async ctx => {
  ctx.body = {
    plan: await planningService.getLatestDraft(ctx.state.auth!.userGuid),
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
 * 手工新增计划项:默认加到当前生效计划,也可显式指定 draft/active。
 * 创建后服务端立即重排当天时段,返回的 plan 可直接覆盖客户端缓存。
 */
router.post('/plans/items', requireAuth, apiLimit(), async ctx => {
  const parsed = planItemCreateSchema.safeParse(ctx.request.body ?? {})
  if (!parsed.success) {
    throw new ApiError(400, 'INVALID_PARAMS', '计划项参数不合法', {
      fields: parsed.error.issues.map(issue => ({ field: issue.path.join('.'), message: issue.message })),
    })
  }
  const result = await planningService.addPlanItem(ctx.state.auth!.userGuid, dropNulls(parsed.data))
  ctx.status = 201
  ctx.body = { ...result, serverTime: Date.now() }
  notifyPlanChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
})

/** 草案/生效计划的预算摘要(预算表、逐日负荷、阶段目标与警告);静态段必须注册在 /plans/:id 之前 */
router.get('/plans/:id/overview', requireAuth, async ctx => {
  const planId = Number(ctx.params.id)
  if (!Number.isInteger(planId) || planId <= 0) throw new ApiError(400, 'INVALID_PARAMS', '计划 id 不合法')
  const overview = await planningService.getPlanOverview(ctx.state.auth!.userGuid, planId)
  if (!overview) throw new ApiError(404, 'PLAN_NOT_FOUND', '计划不存在')
  ctx.body = { ...overview, serverTime: Date.now() }
})


/**
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
 * 修改计划项:status(勾选/取消)与内容字段(日期/时长/标题/科目/锁定)走同一个接口。
 * 两类字段混用由 service 拒绝,避免一次请求里既打卡又改内容。
 * slim=1 时只回被改的那一项与进度,省掉数百 KB 的下行体积(仅 status 变更支持)。
 */
router.patch('/plans/items/:id', requireAuth, apiLimit(), async ctx => {
  const itemId = Number(ctx.params.id)
  if (!Number.isInteger(itemId) || itemId <= 0) {
    throw new ApiError(400, 'INVALID_PARAMS', '计划项 id 不合法')
  }
  const parsed = planItemPatchSchema.safeParse(ctx.request.body ?? {})
  if (!parsed.success) {
    throw new ApiError(400, 'INVALID_PARAMS', '计划项参数不合法', {
      fields: parsed.error.issues.map(issue => ({ field: issue.path.join('.'), message: issue.message })),
    })
  }
  const patch = dropNulls(parsed.data)
  const contentFields = ['planDate', 'minutes', 'title', 'subject', 'locked'] as const
  const hasContent = contentFields.some(field => patch[field] !== undefined)
  if (patch.status !== undefined && hasContent) {
    throw new ApiError(400, 'INVALID_PARAMS', '状态与内容不能在同一请求里修改')
  }
  if (patch.status !== undefined) {
    const plan = await planningService.setItemStatus(ctx.state.auth!.userGuid, itemId, patch.status)
    if (ctx.query.slim === '1' || ctx.query.slim === 'true') {
      const slim = slimToggleResponse(plan, itemId) ?? { item: plan.items.find(i => i.id === itemId)!, progress: plan.progress }
      ctx.body = { ...slim, serverTime: Date.now() }
    } else {
      ctx.body = { plan, serverTime: Date.now() }
    }
    notifyPlanChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
    return
  }
  if (!hasContent) throw new ApiError(400, 'INVALID_PARAMS', '没有要修改的字段')
  const result = await planningService.updatePlanItem(ctx.state.auth!.userGuid, itemId, patch)
  ctx.body = { ...result, serverTime: Date.now() }
  notifyPlanChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
})

/** 删除未完成的计划项;done 项是历史记录,需要先取消勾选 */
router.delete('/plans/items/:id', requireAuth, apiLimit(), async ctx => {
  const itemId = Number(ctx.params.id)
  if (!Number.isInteger(itemId) || itemId <= 0) {
    throw new ApiError(400, 'INVALID_PARAMS', '计划项 id 不合法')
  }
  const plan = await planningService.deletePlanItem(ctx.state.auth!.userGuid, itemId)
  ctx.body = { plan, serverTime: Date.now() }
  notifyPlanChanged(ctx.state.auth!.userGuid, ctx.state.auth!.deviceId)
})

export default router
