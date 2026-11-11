import test from 'node:test'
import assert from 'node:assert/strict'
import {
  MAX_CODE_ATTEMPTS, createEmailCodeStore, generateCode, hashCode, normalizeEmail,
} from './emailCode'

type Row = {
  id: number; email: string; purpose: string; codeHash: string
  expiresAt: number; attempts: number; consumedAt: number | null; createdAt: number
}

function fakeDb() {
  const rows: Row[] = []
  let nextId = 1
  return {
    rows,
    db: {
      emailCode: {
        async deleteMany({ where }: any) {
          for (let i = rows.length - 1; i >= 0; i--) {
            const r = rows[i]
            if (r.email === where.email && r.purpose === where.purpose && r.consumedAt === null) rows.splice(i, 1)
          }
        },
        async create({ data }: any) {
          rows.push({ id: nextId++, ...data })
        },
        async findFirst({ where }: any) {
          return [...rows].reverse().find(r => r.email === where.email && r.purpose === where.purpose && r.consumedAt === null) ?? null
        },
        async update({ where, data }: any) {
          const row = rows.find(r => r.id === where.id)!
          if (data.attempts?.increment) row.attempts += data.attempts.increment
          if (data.consumedAt !== undefined) row.consumedAt = data.consumedAt
        },
      },
    },
  }
}

test('generateCode 总是 6 位数字', () => {
  for (let i = 0; i < 50; i++) assert.match(generateCode(), /^\d{6}$/)
})

test('normalizeEmail 去空格并转小写', () => {
  assert.equal(normalizeEmail('  Alice@Example.COM '), 'alice@example.com')
})

test('hashCode 与明文验证码、邮箱大小写无关但随 purpose 变化', () => {
  const a = hashCode('123456', 'A@b.com', 'register')
  const b = hashCode('123456', ' a@B.com ', 'register')
  assert.equal(a, b)
  assert.notEqual(a, hashCode('123456', 'a@b.com', 'reset'))
  assert.ok(!a.includes('123456'))
})

test('验证码 10 分钟内有效且只能消费一次', async () => {
  const { db } = fakeDb()
  let now = 1_000
  const store = createEmailCodeStore(db, () => now)
  const code = await store.issue('a@b.com', 'register')
  assert.equal(await store.verify('a@b.com', 'register', code), true)
  assert.equal(await store.verify('a@b.com', 'register', code), false)
})

test('过期验证码被拒绝', async () => {
  const { db } = fakeDb()
  let now = 1_000
  const store = createEmailCodeStore(db, () => now)
  const code = await store.issue('a@b.com', 'reset')
  now += 10 * 60_000 + 1
  assert.equal(await store.verify('a@b.com', 'reset', code), false)
})

test('连续输错 5 次后即使输对也被拒绝', async () => {
  const { db } = fakeDb()
  const store = createEmailCodeStore(db, () => 1_000)
  const code = await store.issue('a@b.com', 'register')
  for (let i = 0; i < MAX_CODE_ATTEMPTS; i++) {
    assert.equal(await store.verify('a@b.com', 'register', '000000'), false)
  }
  assert.equal(await store.verify('a@b.com', 'register', code), false)
})

test('签发新验证码会作废旧验证码', async () => {
  const { db } = fakeDb()
  const store = createEmailCodeStore(db, () => 1_000)
  const first = await store.issue('a@b.com', 'register')
  const second = await store.issue('a@b.com', 'register')
  assert.notEqual(first, second)
  assert.equal(await store.verify('a@b.com', 'register', first), false)
  assert.equal(await store.verify('a@b.com', 'register', second), true)
})
