import test from 'node:test'
import assert from 'node:assert/strict'
import { createPlanningService, PlanningDb } from './service'
import { dayStart, addDays, profileInputSchema } from './schemas'

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

function createFakeDb(options: { failOnItemInsertOnce?: boolean } = {}) {
  const state = {
    profiles: [] as Row[],
    plans: [] as Row[],
    stages: [] as Row[],
    items: [] as Row[],
  }
  let seq = { plan: 0, stage: 0, item: 0 }
  let failNextItemInsert = options.failOnItemInsertOnce ?? false

  const snapshot = () => ({
    profiles: structuredClone(state.profiles),
    plans: structuredClone(state.plans),
    stages: structuredClone(state.stages),
    items: structuredClone(state.items),
    seq: { ...seq },
    failNextItemInsert,
  })
  const restore = (snap: ReturnType<typeof snapshot>) => {
    state.profiles = snap.profiles
    state.plans = snap.plans
    state.stages = snap.stages
    state.items = snap.items
    seq = snap.seq
    failNextItemInsert = snap.failNextItemInsert
  }

  const whereMatch = (row: Row, where: Row) =>
    Object.entries(where).every(([key, value]) => row[key] === value)

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
    },
    plan: {
      findFirst: async ({ where, orderBy }: any) =>
        sortRows(state.plans.filter(r => whereMatch(r, where)), orderBy)[0] ?? null,
      findMany: async ({ where }: any = {}) =>
        state.plans.filter(r => !where || whereMatch(r, where)),
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
      deleteMany: async ({ where }: any) => {
        const hit = state.items.filter(r => whereMatch(r, where))
        state.items = state.items.filter(r => !whereMatch(r, where))
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

const serviceWith = (fake: ReturnType<typeof createFakeDb>) => createPlanningService(fake.db)
const USER = 'user-guid-0000-0000-0000-000000000001'

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

test('getActivePlan returns null when the user has no plan', async () => {
  const fake = createFakeDb()
  assert.equal(await serviceWith(fake).getActivePlan(USER), null)
})

test('generatePlan rejects users without a profile', async () => {
  const fake = createFakeDb()
  await assert.rejects(
    () => serviceWith(fake).generatePlan(USER),
    (error: any) => error.code === 'PROFILE_INCOMPLETE',
  )
  assert.equal(fake.state.plans.length, 0)
})

test('generatePlan persists an active plan with stages covering the whole preparation window', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile() as any)

  const plan = await service.generatePlan(USER)

  assert.equal(plan.status, 'active')
  assert.equal(plan.version, 1)
  assert.equal(plan.source, 'rule')
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

test('generatePlan covers every weak subject and never exceeds the daily budget', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile({ dailyMinutes: 180, weakSubjects: ['数学', '英语', '政治'] }) as any)

  const plan = await service.generatePlan(USER)

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

test('generatePlan keeps every weak subject on the same day when the budget is small', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile({ dailyMinutes: 90, weakSubjects: ['数学', '英语', '政治'] }) as any)

  const plan = await service.generatePlan(USER)

  const firstDay = plan.items.filter((i: any) => i.planDate === dayIso(0))
  // 90 分钟 < 120:不插复盘,三门课当天全排上
  assert.deepEqual(firstDay.map((i: any) => i.subject), ['数学', '英语', '政治'])
  assert.equal(firstDay.reduce((sum, i) => sum + i.minutes, 0), 90)
})

test('generatePlan archives the previous plan and keeps its items for history', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const first = await service.generatePlan(USER)

  const second = await service.generatePlan(USER)

  assert.equal(second.version, 2)
  assert.equal(second.status, 'active')
  const archived = fake.state.plans.find(p => p.id === first.id)!
  assert.equal(archived.status, 'archived')
  assert.equal(fake.state.items.filter(i => i.planId === first.id).length, first.items.length)
  assert.equal((await service.getActivePlan(USER))!.id, second.id)
  assert.equal((await service.getActivePlan(USER))!.version, 2)
})

test('generatePlan is scoped to the authenticated user', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile('other-user', validProfile({ weakSubjects: ['政治'] }) as any)
  const otherPlan = await service.generatePlan('other-user')
  await service.upsertProfile(USER, validProfile() as any)

  await service.generatePlan(USER)

  assert.equal(fake.state.plans.find(p => p.id === otherPlan.id)!.status, 'active')
  assert.equal((await service.getActivePlan('other-user'))!.id, otherPlan.id)
})

test('generatePlan rolls back completely when persisting items fails', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile() as any)
  const previous = await service.generatePlan(USER)
  fake.breakNextItemInsert()

  await assert.rejects(() => service.generatePlan(USER))

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

test('generatePlan reports INVALID_EXAM_DATE when the stored exam date has already passed', async () => {
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
    () => service.generatePlan(USER),
    (error: any) => error.code === 'INVALID_EXAM_DATE',
  )
  assert.equal(fake.state.plans.length, 0)
})

test('generatePlan reports INVALID_DAILY_MINUTES when the stored profile is too short', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  fake.state.profiles.push({
    id: 1,
    userGuid: USER,
    targetType: '考研',
    examDate: addDays(dayStart(new Date()), 120),
    dailyMinutes: 5,
    studyWindowsJson: JSON.stringify(['上午']),
    foundation: '一般',
    weakSubjectsJson: JSON.stringify(['数学']),
    onboardingDoneAt: Date.now(),
    createdAt: new Date(),
    updatedAt: new Date(),
  })

  await assert.rejects(
    () => service.generatePlan(USER),
    (error: any) => error.code === 'INVALID_DAILY_MINUTES',
  )
  assert.equal(fake.state.plans.length, 0)
})

test('getActivePlan flags the plan as stale when the profile no longer matches it', async () => {
  const fake = createFakeDb()
  const service = serviceWith(fake)
  await service.upsertProfile(USER, validProfile() as any)
  await service.generatePlan(USER)
  assert.equal((await service.getActivePlan(USER))!.stale, false)

  await service.upsertProfile(USER, validProfile({ examDate: dayIso(150), dailyMinutes: 240 }) as any)

  const plan = (await service.getActivePlan(USER))!
  assert.equal(plan.stale, true)
  // 档案变了但还没重新生成:旧计划必须原样留着,不能变成空档
  assert.equal(plan.examDate, dayIso(120))
  assert.equal(plan.items.length > 0, true)
})
