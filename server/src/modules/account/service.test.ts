import test from 'node:test'
import assert from 'node:assert/strict'
import { ApiError } from '../../middlewares/error'
import { createAccountService, type AccountDb } from './service'

/**
 * 导出与注销。
 * 这两条是「数据是我自己的」这个承诺的兑现方式,所以测的是:
 * - 导出的内容里有我全部的数据,且没有任何能拿去冒充我的东西;
 * - 注销要么全删干净,要么什么都不动(不能留半个账号)。
 */

type Row = Record<string, any>

const TABLES = [
  'device', 'refreshToken', 'subject', 'countdownNode', 'task', 'pomodoroSession',
  'dailyReview', 'weeklyReview', 'monthlyReview', 'userSetting',
  'planItem', 'planStage', 'plan', 'userProfile', 'user',
] as const

const USER = 'user-guid-0000-0000-0000-000000000001'
const OTHER = 'user-guid-0000-0000-0000-000000000002'

function matches(row: Row, where: Row = {}): boolean {
  return Object.entries(where).every(([key, value]) => {
    if (value && typeof value === 'object' && Array.isArray((value as any).in)) {
      return (value as any).in.includes(row[key])
    }
    return row[key] === value
  })
}

/** 内存假库:$transaction 真快照/回滚,并可在指定表上制造一次失败以验证原子性 */
function createFakeAccountDb(options: { failOn?: string } = {}) {
  const state: Record<string, Row[]> = {}
  for (const t of TABLES) state[t] = []
  let failOn = options.failOn ?? null

  const snapshot = () => structuredClone(state)
  const restore = (snap: Record<string, Row[]>) => {
    for (const t of TABLES) state[t] = snap[t]
  }

  const db: any = {}
  for (const t of TABLES) {
    db[t] = {
      findMany: async ({ where }: any = {}) => state[t].filter(r => matches(r, where)),
      findUnique: async ({ where }: any = {}) => state[t].find(r => matches(r, where)) ?? null,
      deleteMany: async ({ where }: any = {}) => {
        if (failOn === t) { failOn = null; throw new Error(`boom: ${t} delete failed`) }
        const keep = state[t].filter(r => !matches(r, where))
        const count = state[t].length - keep.length
        state[t] = keep
        return { count }
      },
      updateMany: async ({ where, data }: any) => {
        const hit = state[t].filter(r => matches(r, where))
        for (const row of hit) Object.assign(row, data)
        return { count: hit.length }
      },
    }
  }
  db.$transaction = async (fn: (tx: AccountDb) => Promise<any>) => {
    const snap = snapshot()
    try {
      return await fn(db as AccountDb)
    } catch (error) {
      restore(snap)
      throw error
    }
  }
  return { db: db as AccountDb, state, breakTable: (t: string) => { failOn = t } }
}

function seed(state: Record<string, Row[]>) {
  state.user.push({ id: 1, guid: USER, username: 'chensi', email: null, passwordHash: 'HASH', resetTokenHash: 'RESET', resetTokenExpiry: 123, createdAt: 1, termsAcceptedAt: new Date('2026-09-25'), privacyAcceptedAt: new Date('2026-09-25') })
  state.device.push({ id: 10, userGuid: USER, deviceGuid: 'd-1', name: 'Pixel', platform: 'android', lastIp: null, lastActiveAt: 5 })
  state.refreshToken.push({ id: 20, userGuid: USER, deviceId: 10, tokenHash: 'T', revokedAt: null, expiresAt: 9, createdAt: 4 })
  state.subject.push({ id: 30, userGuid: USER, name: '数学' })
  state.task.push({ id: 40, userGuid: USER, title: '做题' })
  state.userSetting.push({ id: 50, userGuid: USER, key: 'theme', value: '"dark"' })
  state.userProfile.push({ id: 60, userGuid: USER, targetType: '考研', examDate: new Date('2026-12-20') })
  state.plan.push({ id: 70, userGuid: USER, status: 'active', version: 1 })
  state.planStage.push({ id: 80, planId: 70, name: '基础' })
  state.planItem.push({ id: 90, planId: 70, subject: '数学' })

  // 另一个用户的数据:任何操作都不该碰到
  state.user.push({ id: 2, guid: OTHER, username: 'other', email: null, passwordHash: 'HASH2', resetTokenHash: null, resetTokenExpiry: null, createdAt: 2 })
  state.subject.push({ id: 31, userGuid: OTHER, name: '英语' })
  state.plan.push({ id: 71, userGuid: OTHER, status: 'active', version: 1 })
  state.planItem.push({ id: 91, planId: 71, subject: '英语' })
}

const PROFILE = { targetType: '考研', examDate: '2026-12-20', dailyMinutes: 180 } as any
const PLAN = { id: 70, title: '考研备考计划', stale: false } as any
const BACKUP = { version: 2, exportedAt: 1, subjects: [{ name: '数学' }], tasks: [], settings: {} }

function serviceWith(fake: { db: AccountDb }) {
  const kicked: string[] = []
  return {
    kicked,
    service: createAccountService(fake.db, {
      exportBackup: async () => BACKUP,
      getProfile: async () => PROFILE,
      getActivePlan: async () => PLAN,
      verifyPassword: async (plain: string, hash: string) => `${plain}:${hash}` === 'right:HASH',
      kickUser: (userGuid: string) => { kicked.push(userGuid) },
    }),
  }
}

test('exportAccount bundles account, profile, plan and synced data', async () => {
  const fake = createFakeAccountDb()
  seed(fake.state)
  const { service } = serviceWith(fake)

  const dump = await service.exportAccount(USER)
  assert.equal(dump.account.username, 'chensi')
  assert.equal(dump.account.guid, USER)
  assert.deepEqual(dump.profile, PROFILE)
  assert.deepEqual(dump.plan, PLAN)
  assert.deepEqual(dump.data, BACKUP)
  assert.ok(dump.exportedAt > 0)
})

test('exportAccount never hands out anything that could be used to impersonate the user', async () => {
  const fake = createFakeAccountDb()
  seed(fake.state)
  const { service } = serviceWith(fake)

  const dump = await service.exportAccount(USER)
  const flat = JSON.stringify(dump)
  assert.ok(!flat.includes('HASH'), 'export leaked passwordHash')
  assert.ok(!flat.includes('RESET'), 'export leaked reset token')
  assert.equal('passwordHash' in dump.account, false)
  assert.equal('resetTokenHash' in dump.account, false)
})

test('exportAccount survives a user who never filled the onboarding form', async () => {
  const fake = createFakeAccountDb()
  seed(fake.state)
  const service = createAccountService(fake.db, {
    exportBackup: async () => BACKUP,
    getProfile: async () => null,
    getActivePlan: async () => null,
    verifyPassword: async () => false,
    kickUser: () => {},
  })
  const dump = await service.exportAccount(USER)
  assert.equal(dump.profile, null)
  assert.equal(dump.plan, null)
  assert.equal(dump.account.username, 'chensi')
})

test('exportAccount refuses an unknown user', async () => {
  const fake = createFakeAccountDb()
  const { service } = serviceWith(fake)
  await assert.rejects(() => service.exportAccount(USER), (err: unknown) => {
    assert.ok(err instanceof ApiError)
    assert.equal(err.status, 404)
    return true
  })
})

test('deleteAccount demands the username as confirmation and changes nothing otherwise', async () => {
  const fake = createFakeAccountDb()
  seed(fake.state)
  const { service, kicked } = serviceWith(fake)

  await assert.rejects(
    () => service.deleteAccount({ userGuid: USER, confirm: 'DELETE', password: 'right' }),
    (err: unknown) => {
      assert.ok(err instanceof ApiError)
      assert.equal(err.code, 'CONFIRM_MISMATCH')
      return true
    },
  )
  assert.equal(fake.state.user.length, 2)
  assert.equal(fake.state.subject.length, 2)
  assert.equal(kicked.length, 0)
})

test('deleteAccount demands the current password and changes nothing otherwise', async () => {
  const fake = createFakeAccountDb()
  seed(fake.state)
  const { service, kicked } = serviceWith(fake)

  await assert.rejects(
    () => service.deleteAccount({ userGuid: USER, confirm: 'chensi', password: 'wrong' }),
    (err: unknown) => {
      assert.ok(err instanceof ApiError)
      assert.equal(err.code, 'WRONG_PASSWORD')
      return true
    },
  )
  assert.equal(fake.state.user.length, 2)
  assert.equal(fake.state.refreshToken[0].revokedAt, null)
  assert.equal(kicked.length, 0)
})

test('deleteAccount wipes every table for that user only', async () => {
  const fake = createFakeAccountDb()
  seed(fake.state)
  const { service } = serviceWith(fake)

  const result = await service.deleteAccount({ userGuid: USER, confirm: 'chensi', password: 'right' })

  for (const t of TABLES) {
    const left = fake.state[t].filter(r => r.userGuid === USER || (t === 'planStage' && r.planId === 70) || (t === 'planItem' && r.planId === 70))
    assert.equal(left.length, 0, `${t} 里还留着这个用户的数据`)
  }
  // 别人的东西一根毛都不能少
  assert.equal(fake.state.user.filter(r => r.guid === OTHER).length, 1)
  assert.equal(fake.state.subject.filter(r => r.userGuid === OTHER).length, 1)
  assert.equal(fake.state.planItem.filter(r => r.planId === 71).length, 1)
  assert.ok(result.deleted >= 1)
})

test('deleteAccount revokes refresh tokens and kicks the devices', async () => {
  const fake = createFakeAccountDb()
  seed(fake.state)
  const { service, kicked } = serviceWith(fake)

  await service.deleteAccount({ userGuid: USER, confirm: 'chensi', password: 'right' })
  assert.deepEqual(kicked, [USER])
})

test('deleteAccount rolls back entirely when a delete fails midway', async () => {
  const fake = createFakeAccountDb({ failOn: 'planStage' })
  seed(fake.state)
  const { service } = serviceWith(fake)

  await assert.rejects(() => service.deleteAccount({ userGuid: USER, confirm: 'chensi', password: 'right' }))
  // 事务回滚:计划还在、档案还在、账号还在,用户重新登录后看到的是完整的数据
  assert.equal(fake.state.plan.filter(r => r.id === 70).length, 1)
  assert.equal(fake.state.userProfile.filter(r => r.userGuid === USER).length, 1)
  assert.equal(fake.state.user.filter(r => r.guid === USER).length, 1)
})
