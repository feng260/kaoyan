import test from 'node:test'
import assert from 'node:assert/strict'
import { buildUserPrompt, normalizeAdjustIntent, parseBlockedRange, type AdjustIntentInput } from './adjustIntent'

const input: AdjustIntentInput = {
  message: '明天有事',
  today: '2026-09-29',
  windowTo: '2026-10-06',
  subjects: ['数学', '英语'],
  brief: null,
}

test('unavailable:窗口外日期被丢弃、去重,正常字段保留', () => {
  const intent = normalizeAdjustIntent({
    kind: 'unavailable',
    days: ['2026-09-30', '2026-10-10', 'bad', '2026-09-30'],
    note: '家里有事',
    summary: '9月30日没空',
  }, input)
  assert.equal(intent.kind, 'unavailable')
  assert.deepEqual(intent.days, ['2026-09-30'])
  assert.equal(intent.note, '家里有事')
  assert.equal(intent.summary, '9月30日没空')
  assert.equal(intent.needClarify, false)
})

test('数据撑不起 kind → 降级为 chat', () => {
  assert.equal(normalizeAdjustIntent({ kind: 'unavailable', days: [] }, input).kind, 'chat')
  assert.equal(normalizeAdjustIntent({ kind: 'reduce_capacity', windows: [] }, input).kind, 'chat')
  assert.equal(normalizeAdjustIntent({ kind: 'change_commitment', commitments: [] }, input).kind, 'chat')
  assert.equal(normalizeAdjustIntent({ kind: 'whatever' }, input).kind, 'chat')
})

test('needClarify 优先于降级,追问原样透传', () => {
  const intent = normalizeAdjustIntent(
    { kind: 'unavailable', days: [], needClarify: true, clarifyQuestion: '哪天没空?' }, input)
  assert.equal(intent.needClarify, true)
  assert.equal(intent.clarifyQuestion, '哪天没空?')
  assert.equal(intent.kind, 'unavailable')
})

test('windows:越界/负数/重复日被过滤,分钟取整', () => {
  const intent = normalizeAdjustIntent({
    kind: 'reduce_capacity',
    windows: [
      { day: '2026-09-30', minutes: 90.6 },
      { day: '2026-10-10', minutes: 60 },
      { day: '2026-09-30', minutes: 30 },
      { day: '2026-10-01', minutes: -5 },
    ],
  }, input)
  assert.deepEqual(intent.windows, [{ day: '2026-09-30', minutes: 91 }])
})

test('commitments:非法星期/时间段被过滤,label 缺省兜底', () => {
  const intent = normalizeAdjustIntent({
    kind: 'change_commitment',
    commitments: [
      { weekday: 1, start: '19:00', end: '21:00', label: '晚自习' },
      { weekday: 9, start: '19:00', end: '21:00', label: 'x' },
      { weekday: 2, start: '21:00', end: '19:00', label: 'x' },
      { weekday: 3, start: '08:00', end: '10:00' },
    ],
  }, input)
  assert.deepEqual(intent.commitments, [
    { weekday: 1, start: '19:00', end: '21:00', label: '晚自习' },
    { weekday: 3, start: '08:00', end: '10:00', label: '固定占用' },
  ])
})

test('summary 缺省时按 kind 生成兜底文案', () => {
  const intent = normalizeAdjustIntent({ kind: 'unavailable', days: ['2026-09-30'] }, input)
  assert.equal(intent.summary, '09月30日没空,把任务顺延')
})

test('buildUserPrompt:历史对话拼入 prompt,只取最近 6 条且逐条截断', () => {
  const history = Array.from({ length: 10 }, (_, i) => ({
    role: (i % 2 === 0 ? 'user' : 'assistant') as 'user' | 'assistant',
    content: `第${i}轮消息${'长'.repeat(300)}`,
  }))
  const prompt = buildUserPrompt({ ...input, history })
  assert.ok(prompt.includes('最近对话(旧到新):'))
  // slice(-6) 只保留第 4-9 条,更早的丢掉;单条截到 200 字
  assert.ok(!prompt.includes('第3轮'))
  assert.ok(prompt.includes('第4轮'))
  assert.ok(prompt.includes('用户说(本次消息):明天有事'))
})

test('buildUserPrompt:无历史时不出现对话段,兼容旧客户端', () => {
  const prompt = buildUserPrompt(input)
  assert.ok(!prompt.includes('最近对话'))
  assert.ok(prompt.includes('用户说(本次消息):明天有事'))
})

test('parseBlockedRange:两种常见写法与非法输入', () => {
  assert.deepEqual(parseBlockedRange('今天14:00-18:00有事'), [840, 1080])
  assert.deepEqual(parseBlockedRange('14点到18点'), [840, 1080])
  assert.deepEqual(parseBlockedRange('明天9点到11点30分'), [540, 690])
  assert.equal(parseBlockedRange('没有时段'), null)
  assert.equal(parseBlockedRange('18点到14点'), null)
})

test('确定性兜底:模型降级为 chat 但 note 带日期+时段时,按课表算剩余分钟', () => {
  // 2026-09-29 是周二:课表 08:00-11:30(210m) + 14:00-17:00(180m) = 390m
  const briefWithTimetable = {
    ...input,
    brief: {
      summary: '', goals: [], constraints: [], focus: [], materials: [], notes: [],
      examSubjects: [], fixedCommitments: [], availabilityConfirmed: true, commitmentsConfirmed: true,
      availability: [{ weekday: 2, windows: [{ start: '08:00', end: '11:30' }, { start: '14:00', end: '17:00' }] }],
    },
  }
  // 模型只输出了 chat + note(实测常见行为),原话是「14点到18点」
  const intent = normalizeAdjustIntent(
    { kind: 'chat', note: '今天14:00-18:00有事,该时段不能学习' },
    { ...briefWithTimetable, message: '14点到18点' },
  )
  assert.equal(intent.kind, 'reduce_capacity')
  assert.deepEqual(intent.windows, [{ day: '2026-09-29', minutes: 210 }])
  // 14:00-18:00 与课表重叠 180m,390-180=210;不该出现被占用时长 240
  assert.notEqual(intent.windows[0].minutes, 240)
})

test('确定性兜底:当天无课表/无时段时不误产意图', () => {
  const noWindows = normalizeAdjustIntent({ kind: 'chat', note: '随便聊聊' }, input)
  assert.equal(noWindows.kind, 'chat')
  const noTimetable = normalizeAdjustIntent(
    { kind: 'chat', note: '今天14:00-18:00有事' },
    { ...input, message: '14点到18点', brief: null },
  )
  assert.equal(noTimetable.kind, 'chat')
})

test('确定性兜底:「一整个下午」时段词按课表算剩余(用户截图场景)', () => {
  // 明天=2026-10-08 周四:课表 08:30-11:30(180m) + 14:00-17:00(180m) + 19:00-22:00(180m) = 540m
  // 下午(12:00-18:00)与课表重叠 180m → 剩余 360
  const briefThu = {
    ...input,
    message: '一整个下午没时间了，你看着把明天的调整一下吧。',
    windowTo: '2026-10-20',
    brief: {
      summary: '', goals: [], constraints: [], focus: [], materials: [], notes: [],
      examSubjects: [], fixedCommitments: [], availabilityConfirmed: true, commitmentsConfirmed: true,
      availability: [{ weekday: 4, windows: [{ start: '08:30', end: '11:30' }, { start: '14:00', end: '17:00' }, { start: '19:00', end: '22:00' }] }],
    },
  }
  const intent = normalizeAdjustIntent(
    { kind: 'chat', note: '明天(2026-10-08)整个下午没空,学习时间相应减少' },
    briefThu,
  )
  assert.equal(intent.kind, 'reduce_capacity')
  assert.deepEqual(intent.windows, [{ day: '2026-10-08', minutes: 360 }])
  assert.equal(intent.needClarify, false)
})

test('确定性兜底:「一整天没空」归 unavailable', () => {
  const briefThu = {
    ...input,
    message: '明天一整天都没空',
    windowTo: '2026-10-20',
    brief: {
      summary: '', goals: [], constraints: [], focus: [], materials: [], notes: [],
      examSubjects: [], fixedCommitments: [], availabilityConfirmed: true, commitmentsConfirmed: true,
      availability: [{ weekday: 4, windows: [{ start: '08:30', end: '11:30' }] }],
    },
  }
  const intent = normalizeAdjustIntent(
    { kind: 'chat', note: '明天(2026-10-08)整天没空' },
    briefThu,
  )
  assert.equal(intent.kind, 'unavailable')
  assert.deepEqual(intent.days, ['2026-10-08'])
})

test('矫正:模型把「整段下午没空」误归 unavailable → 按课表改成 reduce_capacity', () => {
  // 明天=周四课表净空闲 540m,下午(12:00-18:00)重叠 180m → 剩余 360
  const briefThu = {
    ...input,
    message: '一整个下午没时间了，你看着把明天的调整一下吧。',
    windowTo: '2026-10-20',
    brief: {
      summary: '', goals: [], constraints: [], focus: [], materials: [], notes: [],
      examSubjects: [], fixedCommitments: [], availabilityConfirmed: true, commitmentsConfirmed: true,
      availability: [{ weekday: 4, windows: [{ start: '08:30', end: '11:30' }, { start: '14:00', end: '17:00' }, { start: '19:00', end: '22:00' }] }],
    },
  }
  const intent = normalizeAdjustIntent(
    { kind: 'unavailable', days: ['2026-10-08'], note: '明天(2026-10-08)整个下午没空,完全不能学' },
    briefThu,
  )
  assert.equal(intent.kind, 'reduce_capacity')
  assert.deepEqual(intent.days, [])
  assert.deepEqual(intent.windows, [{ day: '2026-10-08', minutes: 360 }])
  // 整天表述则保留 unavailable,不矫正
  const wholeDay = normalizeAdjustIntent(
    { kind: 'unavailable', days: ['2026-10-08'], note: '明天(2026-10-08)整天没空' },
    { ...briefThu, message: '明天一整天都没空' },
  )
  assert.equal(wholeDay.kind, 'unavailable')
  assert.deepEqual(wholeDay.days, ['2026-10-08'])
})
