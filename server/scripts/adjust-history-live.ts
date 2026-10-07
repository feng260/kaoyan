/**
 * 「行程小助手没接住上下文」修复的实测脚本(一次性,用真实 LLM):
 * A. 复现原始 bug:无历史,只发「对」→ 预期模型无从理解
 * B. 验证修复:带上轮追问历史,消息=「对」→ 预期结合上下文,只追问缺的时段
 * C. 多轮完整链路:确认今天下午后再给「14点到18点」→ 预期产出带今天日期的意图
 */
import { runAdjustIntent } from '../src/modules/planning/adjustIntent'
import { EMPTY_BRIEF, type PlanBrief } from '../src/modules/planning/document'

const today = new Date().toISOString().slice(0, 10)
// 生产环境必传 brief;这里给一份带课表的,让 windows.minutes 的估算有依据
const brief: PlanBrief = {
  ...EMPTY_BRIEF,
  summary: '在校备考,白天上课晚上自习',
  examSubjects: [{
    name: '申论', progress: '刚开始', scope: '未明确', remainingMinutes: 3600,
    milestone: '过完方法精讲', milestoneDate: '2026-11-30', milestoneMinutes: 1800, estimated: false,
  }],
  // 周三:上午 08:00-11:30 + 下午 14:00-17:00 + 晚 19:00-22:00 ≈ 9.5h
  // 周四(明天)同款课表,供用例 D(明天下午整段没空)估算剩余
  availability: [
    { weekday: 3, windows: [{ start: '08:00', end: '11:30' }, { start: '14:00', end: '17:00' }, { start: '19:00', end: '22:00' }] },
    { weekday: 4, windows: [{ start: '08:30', end: '11:30' }, { start: '14:00', end: '17:00' }, { start: '19:00', end: '22:00' }] },
  ],
  availabilityConfirmed: true,
  commitmentsConfirmed: true,
}
const base = { today, windowTo: '2027-02-01', subjects: ['申论', '行测'], brief }

function show(tag: string, r: any) {
  console.log(`\n=== ${tag} ===`)
  console.log(JSON.stringify({
    kind: r.kind, needClarify: r.needClarify,
    clarifyQuestion: r.clarifyQuestion, summary: r.summary,
    days: r.days, windows: r.windows, note: r.note,
  }, null, 1))
}

async function main() {
  // A. 复现原始 bug:无历史
  const a = await runAdjustIntent({ ...base, message: '对' })
  show('A 无历史 · 消息=「对」(预期:读不懂,泛化追问或闲聊)', a)

  // B. 验证修复:带上轮追问
  const history = [
    { role: 'user' as const, content: '下午有事,' },
    { role: 'assistant' as const, content: '请问是今天下午吗?从几点到几点没空,这段时间完全不能学吗?' },
  ]
  const b = await runAdjustIntent({ ...base, message: '对', history })
  show('B 带历史 · 消息=「对」(预期:结合上下文,只追问几点到几点)', b)

  // C. 多轮完整链路:确认后补时段
  const historyC = [...history, { role: 'assistant' as const, content: '好,今天下午没事——几点到几点?' }]
  const c = await runAdjustIntent({ ...base, message: '14点到18点', history: historyC })
  show('C 带历史 · 消息=「14点到18点」(预期:unavailable/reduce_capacity 且含今天日期)', c)

  // 判定
  const okB = !b.clarifyQuestion.includes('没接住') && (b.needClarify || b.kind !== 'chat')
  const bResolved = b.clarifyQuestion.includes('今天下午') || b.summary.includes('今天') || b.days.includes(today)
  const okC = c.kind !== 'chat' && !c.needClarify && (c.days.includes(today) || c.windows.some(w => w.day === today))
  // minutes 语义:今天(周三)课表净空闲 570m,14:00-18:00 重叠 180m → 剩余应 ≈390;若 =240(被占用时长)则语义仍错
  const w240 = c.windows.find(w => w.day === today)?.minutes
  const minutesOk = w240 === undefined || (w240 !== 240 && w240 > 0 && w240 <= 570)

  // D. 用户截图原话场景:明天下午整段没空 + 授权看着办(明天=周四,课表净空闲 540m,下午 180m 被占 → 剩余≈360)
  const tomorrow = new Date(`${today}T00:00:00.000Z`)
  tomorrow.setUTCDate(tomorrow.getUTCDate() + 1)
  const tomorrowIso = tomorrow.toISOString().slice(0, 10)
  const historyD = [
    { role: 'user' as const, content: '明天下午有事。' },
    { role: 'assistant' as const, content: '明天下午大概几点到几点有事?会影响多少学习时间?' },
  ]
  const d = await runAdjustIntent({ ...base, message: '一整个下午没时间了，你看着把明天的调整一下吧。', history: historyD })
  show(`D 带历史 · 消息=「一整个下午没时间了,你看着把明天的调整一下吧」(预期:直接产出含 ${tomorrowIso} 的意图,不再追问)`, d)
  const hasTomorrow = d.days.includes(tomorrowIso) || d.windows.some(w => w.day === tomorrowIso)
  const notReAskDay = !(d.needClarify && /今天下午还是明天|哪一天|哪天下午/.test(d.clarifyQuestion))
  const okD = d.kind !== 'chat' && hasTomorrow && notReAskDay

  console.log(`\n判定: B上下文接住=${bResolved && okB ? 'PASS' : 'FAIL'} · C产出意图=${okC ? 'PASS' : 'FAIL'} · C剩余分钟语义(期望≈390,实际=${w240})=${minutesOk ? 'PASS' : 'WARN'} · D截图场景(明天下午+看着办)=${okD ? 'PASS' : 'FAIL'}`)

  // E. 第二张截图的 9 轮脏上下文尾部(新 APK 会带这样的历史):模型已自认「明天(10月8日)」,
  //    用户最后说「整天没空,你别管我什么安排」→ 预期直接产 unavailable(10-08),绝不再问哪天
  const historyE = [
    { role: 'assistant' as const, content: '明天是整天都没空，还是只有部分时段？大概几点到几点？' },
    { role: 'user' as const, content: '整天整天' },
    { role: 'assistant' as const, content: '「整天整天」指哪几天、是什么安排呢?比如10月8日全天没空?' },
    { role: 'user' as const, content: '明天' },
    { role: 'assistant' as const, content: '明天(10月8日)具体是什么安排?是整天没空,还是可用时间变少?' },
  ]
  const e = await runAdjustIntent({ ...base, message: '整天没空，你别管我什么安排。', history: historyE })
  show(`E 9轮脏上下文 · 消息=「整天没空,你别管我什么安排」(预期:unavailable ${tomorrowIso},不再问哪天)`, e)
  const okE = e.kind === 'unavailable' && e.days.includes(tomorrowIso)
    && !e.needClarify
  console.log(`E 判定: ${okE ? 'PASS' : 'FAIL'}`)

  if (!okB || !bResolved || !okC || !okD || !okE) process.exit(1)
}

main().catch(e => { console.error('FAILED:', e); process.exit(1) })
