import test from 'node:test'
import assert from 'node:assert/strict'
import { createPlanningService, overdueSuggestion, slimToggleResponse, PlanningDb } from './service'
import { dayStart, addDays, profileInputSchema } from './schemas'
import { generateRulePlan as generateRulePlanRaw } from './generator'
import { normalizeBrief } from './document'
import type { PlanBrief } from './document'
import type { AiPlanningInput } from './aiGenerator'
import { EventEmitter } from 'node:events'
import { WsHub } from '../../shared/ws/Hub'
import type WebSocket from 'ws'

/**
 * 本机没有 Docker,不方便起真实 MySQL,所以用内存假库跑服务层测试:
 * 只实现 service 真正用到的方法,并且 `$transaction` 真的会回滚 ——
 * 否则「失败时不得留下半份计划」这条最关键的保证就没法验证。
 */

type Row = Record<string, any>

function dayIso(offsetDays: number): string {
  const d = dayStart(new Date())
  d.setUTCDate(d.getUTCDate() + offsetDays)
  return d.toISOString().slice(0, 10)
}

const validProfile = (overrides: Row = {}) => ({
  targetType: '考研',
  examDate: dayIso(120),
  dailyMinutes: 180,
  studyWindows: ['上午', '晚上'],
  foundation: '一般',
  weakSubjects: ['数学', '英语'],
  ...overrides,
})

/**
 * 规则生成器现在按「课表净空闲」算容量,真实空闲在面谈 brief 里(顶层 availability 是问卷旧字段)。
 * 这里桥接一份 AiPlanningInput:有 brief 就用 brief 的空闲,否则退回顶层(与生产链路一致),
 * 免得每个用例都手写 PlanningInput。
 */
function generateRulePlan(input: AiPlanningInput) {
  return generateRulePlanRaw({
    availability: input.brief?.availability ?? input.availability,
    fixedCommitments: input.brief?.fixedCommitments ?? input.fixedCommitments,
    examDate: input.examDate,
    startDate: input.startDate,
    weakSubjects: input.weakSubjects,
    studyWindows: input.studyWindows,
  })
}

function createFakeDb(options: { failOnItemInsertOnce?: boolean; beforeItemUpdate?: () => Promise<void> } = {}) {
  const state = {
    profiles: [] as Row[],
    plans: [] as Row[],
    stages: [] as Row[],
    items: [] as Row[],
    adjustments: [] as Row[],
  }
  let seq = { plan: 0, stage: 0, item: 0, adjustment: 0 }
  let failNextItemInsert = options.failOnItemInsertOnce ?? false

  const snapshot = () => ({
    profiles: structuredClone(state.profiles),
    plans: structuredClone(state.plans),
    stages: structuredClone(state.stages),
    items: structuredClone(state.items),
    adjustments: structuredClone(state.adjustments),
    seq: { ...seq },
    failNextItemInsert,
  })
  const restore = (snap: ReturnType<typeof snapshot>) => {
    state.profiles = snap.profiles
    state.plans = snap.plans
    state.stages = snap.stages
    state.items = snap.items
    state.adjustments = snap.adjustments
    seq = snap.seq
    failNextItemInsert = snap.failNextItemInsert
  }

  const whereMatch = (row: Row, where: Row) =>
    Object.entries(where).every(([key, value]) => {
      // 支持 { in: [...] }:历史列表用一条 groupBy 聚合多份计划的进度
      if (value && typeof value === 'object' && Array.isArray((value as Row).in)) {
        return (value as Row).in.includes(row[key])
      }
      return row[key] === value
    })

  const sortRows = (rows: Row[], orderBy?: Record<string, 'asc' | 'desc'> | Array<Record<string, 'asc' | 'desc'>>) => {
    if (!orderBy) return rows
    const keys = (Array.isArray(orderBy) ? orderBy : [orderBy]).flatMap(entry => Object.entries(entry))
    return [...rows].sort((a, b) => {
      for (const [key, dir] of keys) {
        if (a[key] === b[key]) continue
        const cmp = String(a[key]) > String(b[key]) ? 1 : -1
        return dir === 'desc' ? -cmp : cmp
      }
      return 0
    })
  }

  const db: any = {
    userProfile: {
      findUnique: async ({ where }: any) => state.profiles.find(r => whereMatch(r, where)) ?? null,
      upsert: async ({ where, create, update }: any) => {
        const existing = state.profiles.find(r => whereMatch(r, where))
        if (existing) {
          Object.assign(existing, update)
          return existing
        }
        const row = { ...create }
        state.profiles.push(row)
        return row
      },
      update: async ({ where, data }: any) => {
        const existing = state.profiles.find(r => whereMatch(r, where))
        if (!existing) throw new Error('profile not found')
        Object.assign(existing, data)
        return existing
      },
    },
    plan: {
      findFirst: async ({ where, orderBy }: any) =>
        sortRows(state.plans.filter(r => whereMatch(r, where)), orderBy)[0] ?? null,
      findMany: async ({ where, orderBy }: any = {}) =>
        sortRows(state.plans.filter(r => !where || whereMatch(r, where)), orderBy),
      create: async ({ data }: any) => {
        const row = { id: ++seq.plan, ...data }
        state.plans.push(row)
        return row
      },
      updateMany: async ({ where, data }: any) => {
        const hit = state.plans.filter(r => whereMatch(r, where))
        for (const row of hit) Object.assign(row, data)
        return { count: hit.length }
      },
    },
    planStage: {
      create: async ({ data }: any) => {
        const row = { id: ++seq.stage, ...data }
        state.stages.push(row)
        return row
      },
      findMany: async ({ where, orderBy }: any = {}) =>
        sortRows(state.stages.filter(r => !where || whereMatch(r, where)), orderBy),
      deleteMany: async ({ where }: any) => {
        const hit = state.stages.filter(r => whereMatch(r, where))
        state.stages = state.stages.filter(r => !whereMatch(r, where))
        return { count: hit.length }
      },
    },
    planItem: {
      createMany: async ({ data }: any) => {
        if (failNextItemInsert) {
          failNextItemInsert = false
          throw new Error('boom: item insert failed')
        }
        for (const row of data) state.items.push({ id: ++seq.item, ...row })
        return { count: data.length }
      },
      findMany: async ({ where, orderBy }: any = {}) =>
        sortRows(state.items.filter(r => !where || whereMatch(r, where)), orderBy),
      groupBy: async ({ by, where, _count }: any) => {
        const rows = state.items.filter(r => !where || whereMatch(r, where))
        const groups = new Map<string, Row>()
        for (const row of rows) {
          const key = by.map((field: string) => String(row[field])).join('\u0000')
          let group = groups.get(key)
          if (!group) {
            group = {}
            for (const field of by) group[field] = row[field]
            group._count = { _all: 0 }
            groups.set(key, group)
          }
          if (_count?._all) group._count._all += 1
        }
        return [...groups.values()]
      },
      updateMany: async ({ where, data }: any) => {
        await options.beforeItemUpdate?.()
        const hit = state.items.filter(r => whereMatch(r, where))
        for (const row of hit) Object.assign(row, data)
        return { count: hit.length }
      },
      deleteMany: async ({ where }: any) => {
        const hit = state.items.filter(r => whereMatch(r, where))
        state.items = state.items.filter(r => !whereMatch(r, where))
        return { count: hit.length }
      },
    },
    planAdjustment: {
      findFirst: async ({ where }: any = {}) =>
        state.adjustments.filter(r => !where || whereMatch(r, where))[0] ?? null,
      findMany: async ({ where }: any = {}) =>
        state.adjustments.filter(r => !where || whereMatch(r, where)),
      create: async ({ data }: any) => {
        const row = { id: ++seq.adjustment, createdAt: new Date(), ...structuredClone(data) }
        state.adjustments.push(row)
        return row
      },
      updateMany: async ({ where, data }: any) => {
        const hit = state.adjustments.filter(r => whereMatch(r, where))
        for (const row of hit) Object.assign(row, structuredClone(data))
        return { count: hit.length }
      },
    },
    $transaction: async (fn: (tx: any) => Promise<any>) => {
      const snap = snapshot()
      try {
        return await fn(db)
      } catch (error) {
        restore(snap)
        throw error
      }
    },
  }

  return { db: db as PlanningDb, state, breakNextItemInsert: () => { failNextItemInsert = true } }
}

const serviceWith = (fake: ReturnType<typeof createFakeDb>) => createPlanningService(fake.db, {
  configured: () => true,
  generate: async (input: AiPlanningInput) => ({
    title: `${input.targetType}备考计划`,
    plan: generateRulePlan(input),
  }),
})
const USER = 'user-guid-0000-0000-0000-000000000001'

function serviceWithConfirmedSubject(fake: ReturnType<typeof createFakeDb>) {
  return createPlanningService(fake.db, {
    configured: () => true,
    generate: async input => {
      const plan = generateRulePlan({ ...input, weakSubjects: ['英语一'] })
      for (const item of plan.items) {
        if (item.subject === '复盘') {
          item.subject = '英语一'
          item.title = '一轮阅读真题'
        }
      }
      return { title: '英语计划', plan }
    },
  })
}

function confirmedBrief(subjects: string[]): PlanBrief {
  const brief = qualityBrief()
  brief.examSubjects = subjects.map(name => ({
    name, progress: '已完成基础复习', scope: '基础复习', remainingMinutes: 30,
    milestone: '完成基础复习', milestoneDate: dayIso(59), milestoneMinutes: 15, estimated: false,
  }))
  return brief
}

async function confirmGeneratedPlan(
  service: ReturnType<typeof createPlanningService>, fake: ReturnType<typeof createFakeDb>,
  userGuid = USER, brief: PlanBrief = qualityBrief(),
) {
  fake.state.profiles.find(row => row.userGuid === userGuid)!.briefJson = JSON.stringify(brief)
  const draft = await service.generatePlanDraft(userGuid)
  return service.confirmPlan(userGuid, draft.id)
}

// ---------- 行程调整(D1):播种与注入助手 ----------

/** 行程测试的 brief:每天 19:00-22:00 空闲(180 分钟)、无固定占用,容量完全可预期 */
function adjustBrief(): PlanBrief {
  const brief = qualityBrief()
  brief.availability = [1, 2, 3, 4, 5, 6, 7].map(weekday => ({
    weekday, windows: [{ start: '19:00', end: '22:00' }],
  }))
  brief.fixedCommitments = []
  return brief
}

const dayOnlyRow = (value: any) => new Date(value).toISOString().slice(0, 10)

/** 播种一份 active plan:plan=900 / stage=901 / item=1000+i,stage 窗口与每日项均可控 */
async function seedActivePlan(
  fake: ReturnType<typeof createFakeDb>, userGuid = USER,
  entries: Array<{ day: number; minutes: number; subject?: string; title?: string; status?: string }>,
  options: { stageFrom?: number; stageTo?: number; brief?: PlanBrief; dailyMinutes?: number } = {},
) {
  fake.state.profiles.push({
    id: 1, userGuid,
    targetType: '考研', examDate: dayIso(120), dailyMinutes: options.dailyMinutes ?? 180,
    studyWindowsJson: '["上午","晚上"]', foundation: '一般', weakSubjectsJson: '[]',
    briefJson: JSON.stringify(options.brief ?? adjustBrief()), updatedAt: new Date(),
  })
  fake.state.plans.push({
    id: 900, userGuid, profileId: 1, title: '测试计划', targetType: '考研',
    source: 'rule', status: 'active', version: 1,
    startDate: new Date(`${dayIso(options.stageFrom ?? 0)}T00:00:00.000Z`),
    examDate: new Date(`${dayIso(120)}T00:00:00.000Z`),
    updatedAt: new Date(),
  })
  fake.state.stages.push({
    id: 901, planId: 900, name: '第一阶段', sortOrder: 0,
    startDate: new Date(`${dayIso(options.stageFrom ?? 0)}T00:00:00.000Z`),
    endDate: new Date(`${dayIso(options.stageTo ?? 6)}T00:00:00.000Z`),
  })
  entries.forEach((entry, index) => {
    fake.state.items.push({
      id: 1000 + index, planId: 900, stageId: 901,
      subject: entry.subject ?? '数学', title: entry.title ?? `任务${1000 + index}`,
      planDate: new Date(`${dayIso(entry.day)}T00:00:00.000Z`),
      minutes: entry.minutes, priority: 0, sortOrder: index,
      status: entry.status ?? 'pending', completedAt: null,
    })
  })
}

/** 注入固定意图返回值的 service;intent 里的字段覆盖默认值 */
function serviceWithAdjust(fake: ReturnType<typeof createFakeDb>, intent: Row) {
  return createPlanningService(fake.db, {
    configured: () => true,
    generate: async input => ({ title: `${input.targetType}备考计划`, plan: generateRulePlan(input) }),
    adjustIntent: async () => ({
      kind: 'unavailable', days: [], windows: [], commitments: [],
      note: '', summary: '临时有事', needClarify: false, clarifyQuestion: '',
      ...intent,
    }),
  })
}

test('an old socket closing after reconnect does not remove the new device connection', () => {
  const hub = new WsHub()
  const oldSocket = new EventEmitter() as WebSocket
  const newSocket = new EventEmitter() as WebSocket
  hub.add(oldSocket, USER, 7, 'old')
  hub.add(newSocket, USER, 7, 'new')

  oldSocket.emit('close')

  assert.deepEqual(hub.onlineDevices(USER).map(device => device.deviceName), ['new'])
})

test('an old socket cannot rename or update the replacement connection', () => {
  const hub = new WsHub()
  const oldSocket = new EventEmitter() as WebSocket
  const newSocket = new EventEmitter() as WebSocket
  const oldConn = hub.add(oldSocket, USER, 7, 'old')
  const newConn = hub.add(newSocket, USER, 7, 'new')

  hub.rename(7, 'stale', oldConn)
  hub.setStatus(7, { phase: 'focus' }, oldConn)
  hub.setStatus(7, { phase: 'break' }, newConn)

  assert.deepEqual(hub.onlineDevices(USER), [{
    deviceId: 7, deviceName: 'new', lastStatus: { phase: 'break' },
  }])
})

test('getProfile returns null before onboarding', async () => {
  const fake = createFakeDb()
  assert.equal(await serviceWith(fake).getProfile(USER), null)
})

test('upsertProfile persists the profile and returns parsed arrays', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)

  const profile = await service.upsertProfile(USER, validProfile() as any)

  assert.equal(profile.targetType, '考研')
  assert.equal(profile.examDate, dayIso(120))
  assert.equal(profile.dailyMinutes, 180)
  assert.deepEqual(profile.weakSubjects, ['数学', '英语'])
  assert.deepEqual(profile.studyWindows, ['上午', '晚上'])
  assert.equal(profile.onboardingDoneAt !== null, true)
  // 数据库列是 Text:落库必须是 JSON 字符串
  assert.equal(typeof fake.state.profiles[0].weakSubjectsJson, 'string')
  assert.equal(typeof fake.state.profiles[0].studyWindowsJson, 'string')
})

test('upsertProfile accepts the already normalized profile handed over by the route layer', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)

  // 回归:路由层用 profileInputSchema 解析过一次,examDate 到这里已经是 Date。
  // service 层为防脏数据再校验一次时,不能反手把这个合法 Date 判成「格式错误」。
  const normalized = profileInputSchema.parse(validProfile())

  const profile = await service.upsertProfile(USER, normalized)

  assert.equal(profile.examDate, dayIso(120))
  assert.equal(profile.dailyMinutes, 180)
  assert.deepEqual(profile.weakSubjects, ['数学', '英语'])
})

test('upsertProfile overwrites an existing profile for the same user only', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile() as any)
  await service.upsertProfile('other-user', validProfile({ dailyMinutes: 60 }) as any)

  const updated = await service.upsertProfile(USER, validProfile({ dailyMinutes: 240, weakSubjects: ['政治'] }) as any)

  assert.equal(updated.dailyMinutes, 240)
  assert.deepEqual(updated.weakSubjects, ['政治'])
  assert.equal(fake.state.profiles.length, 2)
  assert.equal(fake.state.profiles.find(p => p.userGuid === 'other-user')?.dailyMinutes, 60)
  assert.equal((await service.getProfile('other-user'))!.dailyMinutes, 60)
})

test('setItemStatus persists completion time and clears it when reopened', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const plan = await confirmGeneratedPlan(service, fake)
  const itemId = plan.items[0].id
  assert.equal(plan.items[0].completedAt, null)

  const done = await service.setItemStatus(USER, itemId, 'done')
  const completedAt = done.items.find(i => i.id === itemId)!.completedAt
  assert.equal(typeof completedAt, 'number')
  assert.equal((await service.getActivePlan(USER))!.items.find(i => i.id === itemId)!.completedAt, completedAt)

  const repeated = await service.setItemStatus(USER, itemId, 'done')
  assert.equal(repeated.items.find(i => i.id === itemId)!.completedAt, completedAt)

  const reopened = await service.setItemStatus(USER, itemId, 'pending')
  assert.equal(reopened.items.find(i => i.id === itemId)!.completedAt, null)
})

test('a stale completion cannot restore its old timestamp after another device reopens it', async () => {
  let release!: () => void
  const blocked = new Promise<void>(resolve => { release = resolve })
  let entered!: () => void
  const atUpdate = new Promise<void>(resolve => { entered = resolve })
  let blockFirst = false
  const fake = createFakeDb({ beforeItemUpdate: async () => {
    if (blockFirst) {
      blockFirst = false
      entered()
      await blocked
    }
  } })
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const plan = await confirmGeneratedPlan(service, fake)
  const itemId = plan.items[0].id
  await service.setItemStatus(USER, itemId, 'done')
  const original = fake.state.items.find(i => i.id === itemId)!.completedAt

  blockFirst = true
  const staleDone = service.setItemStatus(USER, itemId, 'done')
  await atUpdate
  await service.setItemStatus(USER, itemId, 'pending')
  release()
  await staleDone

  const item = fake.state.items.find(i => i.id === itemId)!
  assert.equal(item.status, 'done')
  assert.notEqual(item.completedAt, original)
})

test('getActivePlan returns null when the user has no plan', async () => {
  const fake = createFakeDb()
  assert.equal(await serviceWith(fake).getActivePlan(USER), null)
})

test('generatePlanDraft rejects users without a profile', async () => {
  const fake = createFakeDb()
  await assert.rejects(
    () => serviceWith(fake).generatePlanDraft(USER),
    (error: any) => error.code === 'PROFILE_INCOMPLETE',
  )
  assert.equal(fake.state.plans.length, 0)
})

test('generatePlanDraft refuses to create a rule plan when AI is unavailable', async () => {
  const fake = createFakeDb()
  const service = createPlanningService(fake.db, {
    configured: () => false,
    generate: async () => { throw new Error('AI should not be called') },
  })
  await service.upsertProfile(USER, validProfile() as any)

  await assert.rejects(
    () => service.generatePlanDraft(USER),
    (error: any) => error.code === 'AI_NOT_CONFIGURED',
  )
  assert.equal(fake.state.plans.length, 0)
})

test('generatePlanDraft preserves the active plan when AI fails', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const previous = await confirmGeneratedPlan(service, fake)
  const failedService = createPlanningService(fake.db, {
    configured: () => true,
    generate: async () => { throw new Error('model unavailable') },
  })

  await assert.rejects(
    () => failedService.generatePlanDraft(USER),
    (error: any) => error.code === 'PLAN_GENERATION_FAILED',
  )
  assert.equal(fake.state.plans.length, 1)
  assert.equal((await service.getActivePlan(USER))!.id, previous.id)
})

test('the planning service exposes no direct active generation entry', () => {
  const service = serviceWith(createFakeDb())
  assert.equal('generatePlan' in service, false)
})

test('generatePlanDraft creates a draft without changing the active plan', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const active = await confirmGeneratedPlan(service, fake)

  const draft = await service.generatePlanDraft(USER)

  assert.equal(draft.status, 'draft')
  assert.equal((await service.getActivePlan(USER))!.id, active.id)
  assert.equal(fake.state.plans.find(p => p.id === active.id)!.status, 'active')
})

test('confirmPlan activates the draft and archives the previous active plan', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const active = await confirmGeneratedPlan(service, fake)
  const draft = await service.generatePlanDraft(USER)

  const confirmed = await service.confirmPlan(USER, draft.id)

  assert.equal(confirmed.status, 'active')
  assert.equal((await service.getActivePlan(USER))!.id, draft.id)
  assert.equal(fake.state.plans.find(p => p.id === active.id)!.status, 'archived')
})
test('generatePlanDraft persists an active plan with stages covering the whole preparation window', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)

  const plan = await confirmGeneratedPlan(service, fake)

  assert.equal(plan.status, 'active')
  assert.equal(plan.version, 1)
  assert.equal(plan.source, 'ai')
  assert.equal(plan.startDate, dayIso(0))
  assert.equal(plan.examDate, dayIso(120))
  assert.deepEqual(plan.stages.map((s: any) => s.name), ['基础阶段', '强化阶段', '冲刺阶段'])
  assert.equal(plan.stages[0].startDate, dayIso(0))
  // 阶段连续且最后一天停在考前一天
  assert.equal(plan.stages[2].endDate, dayIso(119))
  assert.equal(plan.items.length > 0, true)
  // 落库的项全部挂在本次新建的计划上
  assert.equal(fake.state.items.every(i => i.planId === plan.id), true)
  assert.equal(plan.progress.totalItems, plan.items.length)
  assert.equal(plan.progress.totalMinutes > 0, true)
})

test('generatePlanDraft covers every weak subject and never exceeds the daily budget', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile({ dailyMinutes: 180, weakSubjects: ['数学', '英语', '政治'] }) as any)

  const plan = await confirmGeneratedPlan(service, fake, USER, confirmedBrief(['数学', '英语', '政治', '复盘']))

  const byDay = new Map<string, any[]>()
  for (const item of plan.items) {
    byDay.set(item.planDate, [...(byDay.get(item.planDate) ?? []), item])
  }
  // 180 分钟 >= 120:每天排 2 门主科 + 1 项复盘,科目靠跨天轮转补齐
  assert.deepEqual(byDay.get(dayIso(0))!.map((i: any) => i.subject), ['数学', '英语', '复盘'])
  const covered = new Set(plan.items.map((i: any) => i.subject))
  for (const subject of ['数学', '英语', '政治']) {
    assert.equal(covered.has(subject), true, `${subject} 没有被排进计划`)
  }
  for (const items of byDay.values()) {
    assert.equal(items.length <= 3, true)
    assert.equal(items.reduce((sum, i) => sum + i.minutes, 0), 180)
  }
})

test('generatePlanDraft keeps every weak subject on the same day when the budget is small', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile({ weakSubjects: ['数学', '英语', '政治'] }) as any)

  // 容量来自课表净空闲:每天 19:00-20:30 只有 90 分钟
  const brief = confirmedBrief(['数学', '英语', '政治'])
  brief.availability = [1, 2, 3, 4, 5, 6, 7].map(weekday => ({
    weekday, windows: [{ start: '19:00', end: '20:30' }],
  }))

  const plan = await confirmGeneratedPlan(service, fake, USER, brief)

  const firstDay = plan.items.filter((i: any) => i.planDate === dayIso(0))
  // 90 分钟 < 120:不插复盘,三门课当天全排上
  assert.deepEqual(firstDay.map((i: any) => i.subject), ['数学', '英语', '政治'])
  assert.equal(firstDay.reduce((sum, i) => sum + i.minutes, 0), 90)
})

test('generatePlanDraft archives the previous plan and keeps its items for history', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const first = await confirmGeneratedPlan(service, fake)

  const second = await confirmGeneratedPlan(service, fake)

  assert.equal(second.version, 2)
  assert.equal(second.status, 'active')
  const archived = fake.state.plans.find(p => p.id === first.id)!
  assert.equal(archived.status, 'archived')
  assert.equal(fake.state.items.filter(i => i.planId === first.id).length, first.items.length)
  assert.equal((await service.getActivePlan(USER))!.id, second.id)
  assert.equal((await service.getActivePlan(USER))!.version, 2)
})

test('generatePlanDraft is scoped to the authenticated user', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile('other-user', validProfile({ weakSubjects: ['政治'] }) as any)
  const otherPlan = await confirmGeneratedPlan(service, fake, 'other-user')
  await service.upsertProfile(USER, validProfile() as any)

  await confirmGeneratedPlan(service, fake)

  assert.equal(fake.state.plans.find(p => p.id === otherPlan.id)!.status, 'active')
  assert.equal((await service.getActivePlan('other-user'))!.id, otherPlan.id)
})

test('generatePlanDraft rolls back completely when persisting items fails', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const previous = await confirmGeneratedPlan(service, fake)
  fake.breakNextItemInsert()

  await assert.rejects(() => service.generatePlanDraft(USER))

  // 事务回滚:没有新计划、新阶段、新计划项残留,旧计划仍是唯一 active
  assert.equal(fake.state.plans.length, 1)
  assert.equal(fake.state.plans[0].id, previous.id)
  assert.equal(fake.state.plans[0].status, 'active')
  assert.equal(fake.state.stages.every(s => s.planId === previous.id), true)
  assert.equal(fake.state.items.every(i => i.planId === previous.id), true)
  const active = await service.getActivePlan(USER)
  assert.equal(active!.id, previous.id)
  assert.equal(active!.items.length, previous.items.length)
})

test('generatePlanDraft reports INVALID_EXAM_DATE when the stored exam date has already passed', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  // 直接塞入一份「过期」档案,模拟档案建立后考试日期已过
  fake.state.profiles.push({
    id: 1,
    userGuid: USER,
    targetType: '考研',
    examDate: addDays(dayStart(new Date()), -3),
    dailyMinutes: 180,
    studyWindowsJson: JSON.stringify(['上午']),
    foundation: '一般',
    weakSubjectsJson: JSON.stringify(['数学']),
    onboardingDoneAt: Date.now(),
    createdAt: new Date(),
    updatedAt: new Date(),
  })

  await assert.rejects(
    () => service.generatePlanDraft(USER),
    (error: any) => error.code === 'INVALID_EXAM_DATE',
  )
  assert.equal(fake.state.plans.length, 0)
})

test('getActivePlan flags the plan as stale when the profile no longer matches it', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  await confirmGeneratedPlan(service, fake)
  assert.equal((await service.getActivePlan(USER))!.stale, false)

  await service.upsertProfile(USER, validProfile({ examDate: dayIso(150), dailyMinutes: 240 }) as any)

  const plan = (await service.getActivePlan(USER))!
  assert.equal(plan.stale, true)
  // 档案变了但还没重新生成:旧计划必须原样留着,不能变成空档
  assert.equal(plan.examDate, dayIso(120))
  assert.equal(plan.items.length > 0, true)
})

test('listPlanHistory returns an empty list before any plan is generated', async () => {
  const fake = createFakeDb()
  assert.deepEqual(await serviceWith(fake).listPlanHistory(USER), [])
})

test('listPlanHistory lists the active plan and every archived version, newest first', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const first = await confirmGeneratedPlan(service, fake)
  const second = await confirmGeneratedPlan(service, fake)

  // 勾一个第一版的项,确认归档计划保留自己的打卡数据
  await service.setItemStatus(USER, second.items[0].id, 'done')

  const history = await service.listPlanHistory(USER)

  assert.deepEqual(history.map(p => p.version), [2, 1])
  assert.deepEqual(history.map(p => p.status), ['active', 'archived'])
  assert.equal(history[0].id, second.id)
  assert.equal(history[1].id, first.id)
  // 摘要只带计数:进度按 planId 聚合,不需要拉回上千条明细
  assert.equal(history[0].totalItems, second.items.length)
  assert.equal(history[0].doneItems, 1)
  assert.equal(history[1].totalItems, first.items.length)
  assert.equal(history[1].doneItems, 0)
  assert.equal(history[0].startDate, dayIso(0))
  assert.equal(history[0].totalDays > 0, true)
})

test('listPlanHistory only counts items that belong to each plan', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile('other-user', validProfile() as any)
  const otherPlan = await confirmGeneratedPlan(service, fake, 'other-user')
  await service.upsertProfile(USER, validProfile() as any)
  const mine = await confirmGeneratedPlan(service, fake)

  const history = await service.listPlanHistory(USER)

  assert.equal(history.length, 1)
  assert.equal(history[0].id, mine.id)
  assert.equal(history[0].totalItems, mine.items.length)
  assert.notEqual(history[0].id, otherPlan.id)
})

test('getPlanById returns the archived plan with its stages and items intact', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const first = await confirmGeneratedPlan(service, fake)
  await confirmGeneratedPlan(service, fake)

  const archived = (await service.getPlanById(USER, first.id))!

  assert.equal(archived.id, first.id)
  assert.equal(archived.status, 'archived')
  assert.equal(archived.version, 1)
  assert.equal(archived.stages.length, first.stages.length)
  assert.equal(archived.items.length, first.items.length)
  assert.equal(archived.progress.totalItems, first.items.length)
  // 归档计划不该再被标成「需要重新生成」
  assert.equal(archived.stale, false)
})

test('getPlanById refuses to read another user\'s plan', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile('other-user', validProfile() as any)
  const otherPlan = await confirmGeneratedPlan(service, fake, 'other-user')
  await service.upsertProfile(USER, validProfile() as any)

  assert.equal(await service.getPlanById(USER, otherPlan.id), null)
  assert.equal(await service.getPlanById('other-user', otherPlan.id) !== null, true)
})

const SAMPLE_BRIEF = {
  summary: '在职二战,目标浙大 408,数学基础薄弱',
  goals: ['浙江大学计算机专硕'],
  constraints: ['在职,工作日只有晚上 3 小时'],
  focus: ['数学中值定理与级数反复失分'],
  materials: ['已有王道 408 四本'],
  notes: ['周末全天可支配'],
}

const SAMPLE_DOCUMENT = {
  title: '468 天考研全程作战计划',
  hero: {
    badge: '全程作战计划',
    titleLead: '',
    titleAccent: '468',
    titleTail: '天考研全程作战计划',
    subtitle: '从今天到考前,一张表管到底',
    subjects: ['408', '数学一', '英语一', '政治'],
    stats: [{ label: '总天数', value: '468' }],
  },
  chapters: [
    {
      no: '01',
      title: '起点盘点与目标设定',
      intro: '先把家底摸清楚',
      blocks: [{ type: 'text', text: '你现在的起点决定计划的第一阶段怎么排。' }],
    },
    {
      no: '02',
      title: '阶段总览',
      blocks: [
        { type: 'cards', cards: [{ icon: '1', title: '基础阶段', subtitle: '第 1-150 天', lines: ['过教材', '做课后题'] }] },
      ],
    },
  ],
}

test('generatePlanDraft persists the long document and exposes it on the plan', async () => {
  const fake = createFakeDb()
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async (input: AiPlanningInput) => {
      const plan = generateRulePlan({ ...input, weakSubjects: ['英语一'] })
      plan.items = plan.items.filter(item => item.subject === '英语一')
      return { title: '考研备考计划', plan, document: SAMPLE_DOCUMENT as any }
    },
  })
  await service.upsertProfile(USER, validProfile() as any)

  const plan = await confirmGeneratedPlan(service, fake)

  assert.equal(plan.document?.title, '468 天考研全程作战计划')
  assert.equal(plan.document?.chapters.length, 2)
  // 落库的是 JSON 文本,重新读出来仍然完整
  assert.equal(typeof fake.state.plans[0].documentJson, 'string')
  assert.equal(JSON.parse(fake.state.plans[0].documentJson).hero.titleAccent, '468')
})

test('generatePlanDraft degrades to a null document when the AI cannot produce one', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)

  const plan = await confirmGeneratedPlan(service, fake)

  // 长文档是加分项:生成不出来也不能影响每日清单
  assert.equal(plan.document, null)
  assert.equal(fake.state.plans[0].documentJson, null)
  assert.equal(plan.items.length > 0, true)
})

test('generatePlanDraft feeds the interview brief stored on the profile into the AI input', async () => {
  const fake = createFakeDb()
  let captured: AiPlanningInput | null = null
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async (input: AiPlanningInput) => {
      captured = input
      const plan = generateRulePlan({ ...input, weakSubjects: ['英语一'] })
      plan.items = plan.items.filter(item => item.subject === '英语一')
      return { title: '考研备考计划', plan }
    },
  })
  await service.upsertProfile(USER, validProfile() as any)
  const brief = { ...qualityBrief(), ...SAMPLE_BRIEF }

  await confirmGeneratedPlan(service, fake, USER, brief)

  assert.equal(captured!.brief?.summary, SAMPLE_BRIEF.summary)
  assert.deepEqual(captured!.brief?.focus, SAMPLE_BRIEF.focus)
})

test('interview returns the AI result and stores the brief for the next generation', async () => {
  const fake = createFakeDb()
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async (input: AiPlanningInput) => ({ title: '考研备考计划', plan: generateRulePlan(input) }),
    interview: async () => ({ reply: '信息够了,我这就开始排', options: [], done: true, brief: { ...qualityBrief(), ...SAMPLE_BRIEF } }),
  })
  await service.upsertProfile(USER, validProfile() as any)
  fake.state.profiles[0].briefJson = undefined

  const result = await service.interview(USER, { messages: [{ role: 'user', content: '我想考浙大 408' }] })

  assert.equal(result.done, true)
  assert.equal(result.brief?.summary, SAMPLE_BRIEF.summary)
  // 收尾后简报落库,generatePlanDraft 下次直接读得到
  assert.equal(typeof fake.state.profiles[0].briefJson, 'string')
  assert.equal(JSON.parse(fake.state.profiles[0].briefJson).goals[0], SAMPLE_BRIEF.goals[0])
  assert.equal((await service.getProfile(USER))!.brief?.summary, SAMPLE_BRIEF.summary)
})

test('a profile whose dailyMinutes is 0 (the new questionnaire never asks for it) is still usable', async () => {
  const fake = createFakeDb()
  let seenProfile: unknown
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async (input: AiPlanningInput) => ({ title: '考研备考计划', plan: generateRulePlan(input) }),
    interview: async input => {
      seenProfile = input.profile
      return { reply: '信息够了,我这就开始排', options: [], done: true, brief: { ...qualityBrief(), ...SAMPLE_BRIEF } }
    },
  })
  await service.upsertProfile(USER, validProfile() as any)
  // 问卷已不再收「每天投入多少分钟」,新档案落库就是 0 —— 这正是线上档案的常态
  fake.state.profiles[0].dailyMinutes = 0

  const result = await service.interview(USER, { messages: [{ role: 'user', content: '我想考浙大 408' }] })

  // 档案被判成 null 时面谈只会回「先去填档案」,用户永远出不来
  assert.ok(seenProfile, '面谈必须拿到可用档案,否则陷入「先去填档案」的死循环')
  assert.equal(result.done, true)
})

test('interview does not store an empty brief when the AI wrapped up with nothing', async () => {
  const fake = createFakeDb()
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async (input: AiPlanningInput) => ({ title: '考研备考计划', plan: generateRulePlan(input) }),
    interview: async () => ({
      reply: '那我们开始吧',
      options: [],
      done: true,
      brief: normalizeBrief({ summary: '', goals: [], constraints: [], focus: [], materials: [], notes: [] }),
    }),
  })
  await service.upsertProfile(USER, validProfile() as any)

  await service.interview(USER, { messages: [{ role: 'user', content: '直接开始' }], force: true })

  assert.equal(fake.state.profiles[0].briefJson, undefined)
})

test('interview reports AI_NOT_CONFIGURED when no interviewer is wired up', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile() as any)

  await assert.rejects(
    () => service.interview(USER, {}),
    (error: any) => error?.code === 'AI_NOT_CONFIGURED',
  )
})

const qualityBrief = () => normalizeBrief({
  examSubjects: [{ name: '英语一', progress: '真题阅读做至 2015 年', scope: '阅读', remainingMinutes: 120,
    milestone: '一轮阅读真题', milestoneDate: dayIso(59), milestoneMinutes: 60 }],
  availability: [1, 2, 3, 4, 5, 6, 7].map(weekday => ({ weekday, windows: [{ start: '19:00', end: '22:00' }] })),
  fixedCommitments: [], availabilityConfirmed: true, commitmentsConfirmed: true,
})

test('interview cannot finish on force or turn count without structured facts', async () => {
  const fake = createFakeDb()
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async input => ({ title: 'test', plan: generateRulePlan(input) }),
    interview: async () => ({ reply: '可以开始', options: [], done: true, brief: normalizeBrief(SAMPLE_BRIEF) }),
  })
  await service.upsertProfile(USER, validProfile() as any)
  const result = await service.interview(USER, { force: true, messages: Array.from({ length: 9 }, () => ({ role: 'user', content: '直接开始' })) })
  assert.equal(result.done, false)
  assert.equal(fake.state.profiles[0].briefJson, undefined)
})

test('slim toggle response exposes only the toggled item and progress', () => {
  const plan = {
    items: [
      { id: 1, subject: '申论', title: '任务甲', planDate: '2026-10-07', minutes: 45, status: 'pending' },
      { id: 2, subject: '行测', title: '任务乙', planDate: '2026-10-07', minutes: 45, status: 'done' },
    ],
    progress: { totalItems: 2, pendingItems: 1, doneItems: 1, totalMinutes: 90, totalDays: 1 },
  } as any
  const slim = slimToggleResponse(plan, 1)
  assert.ok(slim)
  assert.equal(slim.item.id, 1)
  assert.equal(slim.progress.totalItems, 2)
  assert.equal(slimToggleResponse(plan, 99), null)
})

test('a replacement draft subtracts completed estimates and never carries the old backlog', async () => {
  const fake = createFakeDb()
  let captured: AiPlanningInput | undefined
  const service = createPlanningService(fake.db, { configured: () => true, generate: async input => {
    captured = input
    const plan = generateRulePlan({ ...input, weakSubjects: ['英语一'] })
    plan.items = plan.items.filter(item => item.subject === '英语一')
    return { title: '英语计划', plan }
  } })
  await service.upsertProfile(USER, validProfile() as any)
  fake.state.profiles[0].briefJson = JSON.stringify(qualityBrief())
  const active = await service.generatePlanDraft(USER)
  await service.confirmPlan(USER, active.id)
  const first = active.items[0]
  await service.setItemStatus(USER, first.id, 'done')
  const draft = await service.generatePlanDraft(USER)
  assert.equal(draft.status, 'draft')
  assert.equal(captured!.brief!.examSubjects[0].remainingMinutes, Math.max(0, 120 - first.minutes))
  assert.equal(captured!.brief!.examSubjects[0].milestoneMinutes, Math.max(0, 60 - first.minutes))
  // 用户反馈"每生成一次每日任务就累积一轮旧任务":重新生成绝不携带旧计划的未完成任务,
  // 进度只通过 remainingMinutes/milestoneMinutes 的扣除进入 brief(backlog 字段已整体移除)
  assert.equal((await service.getActivePlan(USER))!.id, active.id)
})

test('a second replacement keeps the completion credit after the first active plan is archived', async () => {
  const fake = createFakeDb()
  const inputs: AiPlanningInput[] = []
  const service = createPlanningService(fake.db, { configured: () => true, generate: async input => {
    inputs.push(input)
    const plan = generateRulePlan({ ...input, weakSubjects: ['英语一'] })
    plan.items = plan.items.filter(item => item.subject === '英语一')
    return { title: '英语计划', plan }
  } })
  await service.upsertProfile(USER, validProfile() as any)
  const brief = qualityBrief()
  brief.examSubjects[0].remainingMinutes = 500
  fake.state.profiles[0].briefJson = JSON.stringify(brief)
  const first = await service.generatePlanDraft(USER)
  await service.confirmPlan(USER, first.id)
  await service.setItemStatus(USER, first.items[0].id, 'done')
  const second = await service.generatePlanDraft(USER)
  await service.confirmPlan(USER, second.id)
  await service.generatePlanDraft(USER)
  assert.equal(inputs[2].brief!.examSubjects[0].remainingMinutes, 500 - first.items[0].minutes)
})

test('completed minutes from a different interview brief are not deducted from a new goal', async () => {
  const fake = createFakeDb()
  const inputs: AiPlanningInput[] = []
  const service = createPlanningService(fake.db, { configured: () => true, generate: async input => {
    inputs.push(input)
    const plan = generateRulePlan({ ...input, weakSubjects: ['英语一'] })
    plan.items = plan.items.filter(item => item.subject === '英语一')
    return { title: '英语计划', plan }
  } })
  await service.upsertProfile(USER, validProfile() as any)
  const brief = qualityBrief()
  brief.examSubjects[0].remainingMinutes = 500
  fake.state.profiles[0].briefJson = JSON.stringify(brief)
  const first = await service.generatePlanDraft(USER)
  await service.confirmPlan(USER, first.id)
  await service.setItemStatus(USER, first.items[0].id, 'done')
  brief.examSubjects[0].progress = '重新确认了基础'
  brief.examSubjects[0].remainingMinutes = 600
  fake.state.profiles[0].briefJson = JSON.stringify(brief)
  await service.generatePlanDraft(USER)
  assert.equal(inputs[1].brief!.examSubjects[0].remainingMinutes, 600)
})

test('draft generation requires structured exam facts before invoking AI', async () => {
  const fake = createFakeDb()
  let invoked = false
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async input => { invoked = true; return { title: 'test', plan: generateRulePlan(input) } },
  })
  await service.upsertProfile(USER, validProfile() as any)
  await assert.rejects(() => service.generatePlanDraft(USER), (error: any) => error.code === 'PLANNING_FACTS_INCOMPLETE')
  assert.equal(invoked, false)
  assert.equal(fake.state.plans.length, 0)
})

test('insufficient capacity blocks drafts with a subject and milestone deficit', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const brief = qualityBrief()
  brief.examSubjects[0].remainingMinutes = 999999
  fake.state.profiles[0].briefJson = JSON.stringify(brief)
  await assert.rejects(() => service.generatePlanDraft(USER), (error: any) => error.code === 'PLAN_CAPACITY_INSUFFICIENT'
    && error.message.includes('英语一') && error.message.includes('一轮阅读真题'))
  assert.equal(fake.state.plans.length, 0)
})

test('draft generation ignores the legacy daily minutes and follows the timetable', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  // 旧问卷字段 dailyMinutes 只留 60,但课表每天有 180 分钟空闲:容量必须以课表为准
  await service.upsertProfile(USER, validProfile({ dailyMinutes: 60 }) as any)
  fake.state.profiles[0].briefJson = JSON.stringify(qualityBrief())

  const plan = await service.generatePlanDraft(USER)

  const byDay = new Map<string, number>()
  for (const item of plan.items) byDay.set(item.planDate, (byDay.get(item.planDate) ?? 0) + item.minutes)
  assert.equal(plan.items.length > 0, true)
  // 每天排满课表的 180 分钟,不再被 dailyMinutes=60 卡住
  assert.equal([...byDay.values()].every(minutes => minutes === 180), true)
})

test('draft generation rejects a schedule that does not cover confirmed subject work', async () => {
  const fake = createFakeDb()
  const service = createPlanningService(fake.db, { configured: () => true, generate: async input => {
    const plan = generateRulePlan({ ...input, weakSubjects: ['英语一'] })
    plan.items = plan.items.slice(0, 1).map(item => ({ ...item, subject: '英语一', title: '一轮阅读真题' }))
    return { title: '英语计划', plan }
  } })
  await service.upsertProfile(USER, validProfile() as any)
  const brief = qualityBrief()
  brief.examSubjects[0].remainingMinutes = 240
  fake.state.profiles[0].briefJson = JSON.stringify(brief)
  await assert.rejects(() => service.generatePlanDraft(USER), (error: any) => error.code === 'PLAN_CAPACITY_INSUFFICIENT'
    && error.message.includes('英语一') && error.message.includes('一轮阅读真题'))
  assert.equal(fake.state.plans.length, 0)
})

test('draft generation rejects a milestone whose scheduled work lands after its deadline', async () => {
  const fake = createFakeDb()
  const service = createPlanningService(fake.db, { configured: () => true, generate: async input => {
    const plan = generateRulePlan({ ...input, weakSubjects: ['英语一'] })
    plan.items = plan.items.filter(item => item.subject === '英语一' && item.planDate.getTime() > addDays(dayStart(new Date()), 1).getTime())
    return { title: '英语计划', plan }
  } })
  await service.upsertProfile(USER, validProfile() as any)
  const brief = qualityBrief()
  brief.examSubjects[0].milestoneDate = dayIso(1)
  fake.state.profiles[0].briefJson = JSON.stringify(brief)
  await assert.rejects(() => service.generatePlanDraft(USER), (error: any) => error.code === 'PLAN_CAPACITY_INSUFFICIENT'
    && error.message.includes('英语一') && error.message.includes('一轮阅读真题') && error.message.includes('60 分钟'))
  assert.equal(fake.state.plans.length, 0)
})

test('draft generation rejects tasks exceeding the net availability on their date', async () => {
  const fake = createFakeDb()
  // 模拟一个不守容量约束的模型:按每天 180 分钟排,但档案里的课表只留 60 分钟
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async input => {
      const plan = generateRulePlanRaw({
        ...input,
        availability: [1, 2, 3, 4, 5, 6, 7].map(weekday => ({ weekday, windows: [{ start: '09:00', end: '12:00' }] })),
        fixedCommitments: [],
        weakSubjects: ['英语一'],
      })
      return { title: '英语计划', plan }
    },
  })
  await service.upsertProfile(USER, validProfile() as any)
  const brief = qualityBrief()
  brief.fixedCommitments = [1, 2, 3, 4, 5, 6, 7].map(weekday => ({ weekday, start: '20:00', end: '22:00', label: '工作' }))
  fake.state.profiles[0].briefJson = JSON.stringify(brief)

  await assert.rejects(() => service.generatePlanDraft(USER), (error: any) => error.code === 'PLAN_GENERATION_FAILED')
  assert.equal(fake.state.plans.length, 0)
})

test('draft generation rejects AI subjects not present in the confirmed exam facts', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile() as any)
  fake.state.profiles[0].briefJson = JSON.stringify(qualityBrief())
  await assert.rejects(() => service.generatePlanDraft(USER), (error: any) => error.code === 'PLAN_GENERATION_FAILED')
  assert.equal(fake.state.plans.length, 0)
})

test('generation retries with the validation reason and succeeds when the model fixes its subject names', async () => {
  const fake = createFakeDb()
  let calls = 0
  const hints: Array<string | undefined> = []
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async input => {
      calls++
      hints.push(input.repairHint)
      const plan = generateRulePlan({ ...input, weakSubjects: ['英语一'] })
      // 模拟模型第一次没照抄确认科目名:写出变体「英语阅读」,必然被生成闸门拦下
      if (calls === 1) for (const item of plan.items) item.subject = '英语阅读'
      // 第二次"改对了":所有条目都归到确认过的科目名下
      if (calls >= 2) for (const item of plan.items) item.subject = '英语一'
      return { title: '英语计划', plan }
    },
  })
  await service.upsertProfile(USER, validProfile() as any)
  fake.state.profiles[0].briefJson = JSON.stringify(qualityBrief())

  const draft = await service.generatePlanDraft(USER)

  assert.equal(calls, 2, '第一次被拦后必须自动重试,而不是把失败抛给用户')
  assert.ok(hints[1], '第二次调用必须携带上一轮被拦的原因(repairHint)')
  assert.ok(hints[1]!.includes('未确认的考试科目'))
  assert.equal(draft.status, 'draft')
  assert.equal(fake.state.plans.length, 1)
})

test('confirmation rejects another user and a changed profile without archiving the active plan', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const active = await confirmGeneratedPlan(service, fake)
  const invalid = serviceWith(fake)
  await assert.rejects(() => invalid.generatePlanDraft(USER), (error: any) => error.code === 'PLAN_GENERATION_FAILED')
  const newDraft = await service.generatePlanDraft(USER)
  await assert.rejects(() => service.confirmPlan('other-user', newDraft.id), (error: any) => error.code === 'PLAN_NOT_FOUND')
  await service.upsertProfile(USER, validProfile({ dailyMinutes: 240 }) as any)
  await assert.rejects(() => service.confirmPlan(USER, newDraft.id), (error: any) => error.code === 'PLAN_PROFILE_CHANGED')
  assert.equal(fake.state.plans.find(p => p.id === active.id)!.status, 'active')
})

test('confirmation rejects a draft after active plan progress changes', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const active = await confirmGeneratedPlan(service, fake)
  const draft = await service.generatePlanDraft(USER)
  await service.setItemStatus(USER, active.items[0].id, 'done')

  await assert.rejects(() => service.confirmPlan(USER, draft.id), (error: any) => error.code === 'PLAN_PROGRESS_CHANGED')
  assert.equal((await service.getActivePlan(USER))!.id, active.id)
  assert.equal(fake.state.plans.find(p => p.id === draft.id)!.status, 'draft')
})

test('draft generation refuses a profile changed while AI was generating', async () => {
  const fake = createFakeDb()
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async input => {
      fake.state.profiles[0].dailyMinutes += 30
      const plan = generateRulePlan({ ...input, weakSubjects: ['英语一'] })
      plan.items = plan.items.map(item => ({ ...item, subject: '英语一' }))
      return { title: 'test', plan }
    },
  })
  await service.upsertProfile(USER, validProfile() as any)
  fake.state.profiles[0].briefJson = JSON.stringify(qualityBrief())
  await assert.rejects(() => service.generatePlanDraft(USER), (error: any) => error.code === 'PLAN_PROFILE_CHANGED')
  assert.equal(fake.state.plans.length, 0)
})

test('history excludes drafts', async () => {
  const fake = createFakeDb()
  const service = serviceWithConfirmedSubject(fake)
  await service.upsertProfile(USER, validProfile() as any)
  await confirmGeneratedPlan(service, fake)
  fake.state.plans.push({ ...fake.state.plans[0], id: 999, status: 'draft', version: 2 })
  assert.deepEqual((await service.listPlanHistory(USER)).map(row => row.status), ['active'])
})
test('upsertProfile keeps the brief gathered during the interview', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile() as any)
  fake.state.profiles[0].briefJson = JSON.stringify(SAMPLE_BRIEF)

  await service.upsertProfile(USER, validProfile({ dailyMinutes: 240 }) as any)

  // 改档案不该把面谈得到的画像一起丢掉
  assert.equal(typeof fake.state.profiles[0].briefJson, 'string')
  assert.equal((await service.getProfile(USER))!.brief?.summary, SAMPLE_BRIEF.summary)
})

// ---------- 行程调整(D1):集成用例 A-G ----------

test('adjust A:明天没空 → 当天任务顺延次日;确认前零副作用,确认后计划项被挪动', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [
    { day: 1, minutes: 120 },
    { day: 2, minutes: 60 },
  ])
  const service = serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(1)], summary: '明天没空' })

  const result = await service.adjust(USER, { message: '明天有事,学不了' })
  assert.ok(result.adjustment)
  assert.equal(result.adjustment.status, 'draft')
  assert.equal(result.adjustment.tier, 'L1')
  assert.equal(result.adjustment.changes.length, 1)
  assert.equal(result.adjustment.changes[0].kind, 'moved')
  // 草稿不改变 active plan
  assert.equal(dayOnlyRow(fake.state.items.find(row => row.id === 1000)!.planDate), dayIso(1))

  const plan = await service.confirmAdjustment(USER, result.adjustment.id)
  const moved = plan.items.find(row => row.id === 1000)!
  assert.equal(moved.planDate, dayIso(2))
  // 未受影响的原项保持原日期
  assert.equal(plan.items.find(row => row.id === 1001)!.planDate, dayIso(2))
})

test('adjust B:窗口内塞不下 → PLAN_CAPACITY_INSUFFICIENT 且不落调整单', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [{ day: 1, minutes: 240 }], { stageTo: 1 })
  const service = serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(0), dayIso(1)], summary: '今明都没空' })

  await assert.rejects(
    () => service.adjust(USER, { message: '今明两天都没空' }),
    (error: any) => error.code === 'PLAN_CAPACITY_INSUFFICIENT',
  )
  assert.equal(fake.state.adjustments.length, 0)
})

test('adjust C:已有 draft 时再次调整原地覆盖,不产生第二张', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [{ day: 1, minutes: 120 }, { day: 2, minutes: 60 }])
  const first = await serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(1)], summary: '明天有事' })
    .adjust(USER, { message: '明天有事' })
  const second = await serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(2)], summary: '后天有事' })
    .adjust(USER, { message: '后天也有事' })

  assert.ok(first.adjustment && second.adjustment)
  assert.equal(second.adjustment.id, first.adjustment.id)
  assert.equal(fake.state.adjustments.filter(row => row.status === 'draft').length, 1)
})

test('adjust D:确认前窗口内计划被第三方改过 → ADJUSTMENT_STALE', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [{ day: 1, minutes: 120 }])
  const service = serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(1)], summary: '明天没空' })
  const result = await service.adjust(USER, { message: '明天有事' })
  assert.ok(result.adjustment)
  const adjustmentId = result.adjustment.id

  // 模拟另一台设备在确认前改了窗口内计划项的分钟数
  const row = fake.state.items.find(item => item.id === 1000)!
  row.minutes = 90

  await assert.rejects(
    () => service.confirmAdjustment(USER, adjustmentId),
    (error: any) => error.code === 'ADJUSTMENT_STALE',
  )
})

test('adjust E:追问/闲聊只回话不产调整单', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [{ day: 1, minutes: 120 }])
  const service = serviceWithAdjust(fake,
    { kind: 'chat', needClarify: true, clarifyQuestion: '哪天没空?', summary: '' })

  const result = await service.adjust(USER, { message: '有点事' })
  assert.equal(result.adjustment, null)
  assert.equal(result.reply, '哪天没空?')
  assert.equal(fake.state.adjustments.length, 0)
})

test('adjust F:确认后 latest 取到 applied,撤销后窗口内项回到原日期', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [{ day: 1, minutes: 120 }])
  const service = serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(1)], summary: '明天没空' })
  const result = await service.adjust(USER, { message: '明天有事' })
  assert.ok(result.adjustment)
  await service.confirmAdjustment(USER, result.adjustment.id)

  const latest = await service.latestAdjustment(USER)
  assert.ok(latest)
  assert.equal(latest.id, result.adjustment.id)
  assert.equal(latest.status, 'applied')

  const plan = await service.undoAdjustment(USER, result.adjustment.id)
  assert.equal(plan.items.find(row => row.id === 1000)!.planDate, dayIso(1))
  // 撤销后不再有可展示的调整单
  assert.equal(await service.latestAdjustment(USER), null)
})

test('adjust G:调整生效后打了新卡 → 拒绝撤销', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [{ day: 1, minutes: 120 }])
  const service = serviceWithAdjust(fake, { kind: 'unavailable', days: [dayIso(1)], summary: '明天没空' })
  const result = await service.adjust(USER, { message: '明天有事' })
  assert.ok(result.adjustment)
  const adjustmentId = result.adjustment.id
  await service.confirmAdjustment(USER, adjustmentId)

  // 调整生效后完成了一次打卡
  await service.setItemStatus(USER, 1000, 'done')

  await assert.rejects(
    () => service.undoAdjustment(USER, adjustmentId),
    (error: any) => error.code === 'ADJUSTMENT_STALE',
  )
})

// ---------- 行程调整(D3):档位升级与 L2 重排 ----------

test('adjust L2-1:影响天数超阈值 → 升级 L2,模型序列由服务端展开并复用原项', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [
    { day: 0, minutes: 60 }, { day: 1, minutes: 60 }, { day: 2, minutes: 60 }, { day: 3, minutes: 60 },
  ])
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async input => ({ title: `${input.targetType}备考计划`, plan: generateRulePlan(input) }),
    adjustIntent: async () => ({
      kind: 'unavailable', days: [dayIso(0), dayIso(1), dayIso(2)], windows: [], commitments: [],
      note: '前三天都没空', summary: '前三天没空', needClarify: false, clarifyQuestion: '',
    }),
    adjustL2: async () => [
      { dayOffset: 3, subject: '数学', title: '任务1000', minutes: 60 },
      { dayOffset: 3, subject: '数学', title: '任务1001', minutes: 60 },
      { dayOffset: 4, subject: '数学', title: '任务1002', minutes: 60 },
      { dayOffset: 4, subject: '数学', title: '任务1003', minutes: 60 },
    ],
  })

  const result = await service.adjust(USER, { message: '前三天都有事' })
  assert.ok(result.adjustment)
  assert.equal(result.adjustment.tier, 'L2')
  const plan = await service.confirmAdjustment(USER, result.adjustment.id)
  const dates = plan.items.filter(item => item.id >= 1000 && item.id <= 1003)
    .map(item => item.planDate).sort()
  assert.deepEqual(dates, [dayIso(3), dayIso(3), dayIso(4), dayIso(4)])
})

test('adjust L2-2:校验两次不过 → PLAN_GENERATION_FAILED 且窗口内任务原封不动', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [
    { day: 0, minutes: 60 }, { day: 1, minutes: 60 }, { day: 2, minutes: 60 }, { day: 3, minutes: 60 },
  ])
  const service = createPlanningService(fake.db, {
    configured: () => true,
    generate: async input => ({ title: `${input.targetType}备考计划`, plan: generateRulePlan(input) }),
    adjustIntent: async () => ({
      kind: 'unavailable', days: [dayIso(0), dayIso(1), dayIso(2)], windows: [], commitments: [],
      note: '', summary: '前三天没空', needClarify: false, clarifyQuestion: '',
    }),
    // 240 分钟排进 180 分钟的一天 → 每日容量校验两次失败
    adjustL2: async () => [{ dayOffset: 3, subject: '数学', title: 'x', minutes: 240 }],
  })

  await assert.rejects(
    () => service.adjust(USER, { message: '前三天都有事' }),
    (error: any) => error.code === 'PLAN_GENERATION_FAILED',
  )
  assert.equal(fake.state.adjustments.length, 0)
  assert.equal(fake.state.items.filter(item => item.status === 'pending').length, 4)
})

// ---------- 行程体检(D4) ----------

test('checkup:数出过去未完成的任务;没有计划返回全零', async () => {
  const fake = createFakeDb()
  await seedActivePlan(fake, USER, [
    { day: -1, minutes: 60 },
    { day: 0, minutes: 30, status: 'done' },
    { day: 2, minutes: 45 },
  ])
  const checkup = await serviceWith(fake).checkup(USER)
  assert.equal(checkup.overdueCount, 1)
  assert.equal(checkup.behindMinutes, 60)
  assert.ok(checkup.suggestion.includes('1 项'))

  const none = await serviceWith(createFakeDb()).checkup('other-guid')
  assert.deepEqual(none, { behindMinutes: 0, overdueCount: 0, suggestion: '' })

  assert.equal(overdueSuggestion(0, 0), '')
})
