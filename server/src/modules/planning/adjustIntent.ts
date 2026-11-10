import { chatComplete, extractJson, LlmError } from '../../shared/llm/client'
import { AiUnavailable } from './aiGenerator'
import { DEFAULT_ADJUST_DAYS, MAX_ADJUST_DAYS } from './adjust'
import type { PlanBrief } from './document'

/**
 * 行程调整的「意图解析」:模型唯一的工作是把用户这句话拆成结构化意图,
 * 档位、日期、容量全部由服务端判定 —— 模型不输出任何计划内容。
 */

/**
 * unavailable=某几天完全没空 / reduce_capacity=几天可用时间变少 / change_commitment=每周固定占用变了
 * / move_task=挪某条任务 / add_task=加一条任务 / drop_task=删一条任务
 * / adjust_scope=调整科目剩余量或里程碑 / lock_task=锁定任务不再让 AI 动 / chat=闲聊或没说清
 */
export type AdjustIntentKind =
  | 'unavailable' | 'reduce_capacity' | 'change_commitment'
  | 'move_task' | 'add_task' | 'drop_task' | 'adjust_scope' | 'lock_task' | 'reschedule' | 'chat'

/** change_commitment:每周固定的新占用(与 brief.fixedCommitments 同构) */
export interface AdjustCommitment {
  weekday: number
  start: string
  end: string
  label: string
  /** 省略 = 每周都占;odd/even 由 daySchedule 按校历锚点判定 */
  weekParity?: 'odd' | 'even'
}

/** reduce_capacity:某天可用时间只剩 minutes 分钟 */
export interface AdjustCapacityWindow {
  day: string
  minutes: number
}

/** move/drop/lock 指向的任务:subject+title+当前日期用于消歧 */
export interface AdjustTaskRef {
  subject: string
  title?: string
  date?: string
  targetDate?: string
}

/** add_task 要新增的任务 */
export interface AdjustNewTask {
  subject: string
  title: string
  date: string
  minutes: number
}

/** adjust_scope:要改的科目目标 */
export interface AdjustScopeItem {
  subject: string
  remainingMinutes?: number
  milestone?: string
  milestoneDate?: string
  milestoneMinutes?: number
  estimated?: boolean
}

/** 用户本次话术里的重要性提示:protect=重要别动;elastic=可以少点/先缓一缓 */
export interface AdjustImportanceHint {
  subject: string
  title?: string
  date?: string
  level: 'protect' | 'elastic'
}

export interface AdjustIntent {
  kind: AdjustIntentKind
  /** unavailable:完全没空的日期(YYYY-MM-DD,已裁剪到调整窗口内) */
  days: string[]
  windows: AdjustCapacityWindow[]
  commitments: AdjustCommitment[]
  tasks: AdjustTaskRef[]
  newTasks: AdjustNewTask[]
  scope: AdjustScopeItem[]
  /** 用户原话的补充说明,写入调整单 reason */
  note: string
  /** 给确认卡片的一句话摘要(模型写,服务端兜底) */
  summary: string
  needClarify: boolean
  clarifyQuestion: string
  clarifyOptions: Array<{ label: string; value: string }>
  /** 用户本次明确说「哪科/哪项重要」或「可以少点」时才有的提示 */
  importance?: AdjustImportanceHint[]
  /** 用户明确说「这周/未来 N 天」时的总天数(含今天),已夹到 5..14 */
  horizonDays?: number
}

export interface AdjustIntentInput {
  message: string
  today: string
  /** 调整窗口上限(未来 30 天或考前一天),模型给出的日期越界一律丢弃 */
  windowTo: string
  /** 当前计划涉及的科目名 */
  subjects: string[]
  brief: PlanBrief | null
  /**
   * 本次消息之前的最近几轮对话(不含 message 本身,旧到新)。
   * 没有它,「对」「嗯」这类对上一轮追问的简短确认就是天书——
   * 模型只能回「抱歉没接住上下文」再问一遍,考生答得越省事越被绕圈。
   */
  history?: Array<{ role: 'user' | 'assistant'; content: string }>
  /** 窗口内任务摘要:模型据它消歧「数学那项」指的是哪一条 */
  windowItems?: Array<{ planDate: string; subject: string; title: string; minutes: number; locked?: boolean }>
  /** 预算余量:scope 调整时模型能判断还剩多少没排 */
  budget?: { targetMinutes: number; scheduledMinutes: number }
}

const KINDS: AdjustIntentKind[] = [
  'unavailable', 'reduce_capacity', 'change_commitment',
  'move_task', 'add_task', 'drop_task', 'adjust_scope', 'lock_task', 'reschedule', 'chat',
]
const DAY_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/
// 与面谈/文档生成器同口径:意图 JSON 含 tasks/windows/commitments/scope/clarifyOptions/summary/note,
// 900 会在数组中途截断(finish_reason=length)→ extractJson 失败 → 同 prompt 重试必复现 → 502。
// 厂商上限更低时由 LLM 客户端的 400/422 降级阶梯自动收紧。
const ADJUST_MAX_TOKENS = 4096

export async function runAdjustIntent(input: AdjustIntentInput): Promise<AdjustIntent> {
  // 小模型偶发吐非 JSON(截断/混入闲话),重试一次;两次都坏才向上抛
  let lastError: unknown
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const raw = await chatComplete({
        feature: 'adjust_intent',
        messages: [
          { role: 'system', content: buildSystemPrompt() },
          { role: 'user', content: buildUserPrompt(input) },
        ],
        json: true,
        temperature: 0.2,
        maxTokens: ADJUST_MAX_TOKENS,
        timeoutMs: 30_000,
      })
      return normalizeAdjustIntent(extractJson<any>(raw), input)
    } catch (error) {
      lastError = error
    }
  }
  if (lastError instanceof LlmError) throw new AiUnavailable(lastError.message)
  throw lastError
}

function buildSystemPrompt(): string {
  return [
    '你是考研备考计划助手「研钟」的行程调整分析师。',
    '用户会告诉你一件突发事情、想改的任务或想调整的目标。',
    '你的任务是把这句话解析成结构化意图,只输出 JSON,不要输出多余文本。',
    'JSON 字段:',
    '- kind: "unavailable"(某几天完全没空) | "reduce_capacity"(某几天可用时间变少) | "change_commitment"(每周固定占用变了,如换课表)',
    '  | "move_task"(把某条任务挪到另一天) | "add_task"(新增一条任务) | "drop_task"(删掉某条任务)',
    '  | "adjust_scope"(调整科目剩余量/里程碑) | "lock_task"(以后别再动某科/某条) | "reschedule"(把落后/未完成的任务整体重新排进后面的空档) | "chat"(闲聊或信息不足)',
    '- days: 完全没空的日期数组,格式 YYYY-MM-DD,必须在给定窗口内',
    '- windows: 数组,每项 {day: "YYYY-MM-DD", minutes: 数字},minutes = 用户当天扣除没空时段后「还能学」的总分钟数,',
    '  按该日课表净空闲总分钟 − 没空时段与课表重叠的分钟估算(取 5 的倍数即可);',
    '  禁止把没空时长本身填进 minutes;能从课表算就一定给出估算值,确实没有任何课表依据才留空。',
    '- commitments: 数组,每项 {weekday: 1-7(周一=1), start: "HH:mm", end: "HH:mm", label: "事项名", weekParity?: "odd"|"even"},表示每周固定的新占用;',
    '  只有课表明确标了「单周」才填 "odd"、标了「双周」才填 "even";标的是「1-16 周」这类完整区间(全周都上)',
    '  或根本没标周次的,一律省略 weekParity,不要猜,也不要写 null 或空串。',
    '- tasks: move_task/drop_task/lock_task 指向的任务数组,每项 {subject, title?, date?, targetDate?};',
    '  date 是任务当前所在日期(用于消歧),move_task 必须给 targetDate',
    '- newTasks: add_task 要新增的任务数组,每项 {subject, title, date, minutes}',
    '- scope: adjust_scope 的目标数组,每项 {subject, remainingMinutes?, milestone?, milestoneDate?, milestoneMinutes?, estimated?};至少要给一个数值或日期目标',
    '- importance: 仅当用户明确说「哪科/哪项重要、别动」或「可以少点、先放一放」时输出,',
    '  每项 {subject, title?, date?, level};level 只能是 "protect"(重要) 或 "elastic"(可让);没有明确说法时不要输出该字段',
    '- horizonDays: 仅当用户明确说「这周/到周末/未来 N 天」时输出含今天在内的总天数(5..14);没说就省略',
    '- 日程类调整(没空/时间变少/挪任务/新增任务)最多只看 14 天;更远的日期不要用于这些字段。',
    '  科目目标/里程碑(adjust_scope)可以看 30 天。',
    '- note: 从用户原话提取的补充说明,一句话',
    '- summary: 用一句话向用户复述你的理解,不超过 30 字',
    '- needClarify: 布尔,信息不足需要追问时为 true',
    '- clarifyQuestion: 需要追问时的问题,不超过 50 字',
    '- clarifyOptions: 追问时可点的选项数组 [{label, value}],最多 4 个',
    '规则:',
    '- 一次只允许一个意图。用户一句话里有多件事(如「明天没空,顺便把数学减一点」)时,先追问要处理哪一件。',
    '- 用户没说清日期时先追问,不要猜测。',
    '- 「明天」「后天」按给出的今天日期换算;所有日期必须在给定窗口内,超出就追问。',
    '- 生病、临时有事按 unavailable;换课表、长期占用按 change_commitment。',
    '- 挪/删/锁任务时必须先从窗口任务清单里定位到唯一一条;匹配不到或多条匹配就追问,不要猜。',
    '- add_task 缺日期或时长就追问;adjust_scope 缺目标值就追问。',
    '- 「别再动数学」「把今天那项锁住」按 lock_task。',
    '- 用户说「任务落后了/重新排一下/重排/把未来几天重新排/填满空档/重新分配」这类整体重排诉求时,按 reschedule:',
    '  默认只处理今天之前未完成和今天已超载的任务;只有用户明确要求「重新安排这周/未来 N 天」时才输出 horizonDays,',
    '  由服务端只在该范围内重排非保护任务。不需要逐条任务、也不需要具体日期,直接 needClarify=false;',
    '  不要追问「哪几条落后」「挪到哪天」「顺延几天还是填满空档」——这些服务端自己处理。只有用户明确只挪某一条时才用 move_task。',
    '- 与备考计划无关的闲聊一律 kind=chat。',
    '- 可能附带最近几轮对话:用户这次说的可能是对上一轮追问的简短确认(如「对」「嗯」「是的」「下午」),',
    '  必须先读懂上一轮助手问了什么,把确认掉的结论并入意图;信息仍缺时只追问缺的那一块,',
    '  追问里要复述已确认的部分(如「好,今天下午没事——几点到几点?」),禁止说「没接住上下文」之类的话。',
    '- 用户已说过的信息(哪天、时段、时长)绝不重复追问——问了第二遍就是事故;本轮要问的只能是仍缺的那一项。',
    '- 「一整个下午/整个上午/一整天」这类整段表述视为时段已给全:按该日课表估算剩余可用分钟,直接产出意图,不要再追问具体几点。',
    '- 用户说「你看着办/你看着调整」时,基于已确认的信息直接产出意图(needClarify=false),宁可范围宽一点也别继续追问;把你的理解写进 summary。',
  ].join('\n')
}

export function buildUserPrompt(input: AdjustIntentInput): string {
  const briefLines: string[] = []
  if (input.brief) {
    for (const subject of input.brief.examSubjects) {
      briefLines.push(`科目:${subject.name},剩余约${subject.remainingMinutes}分钟` +
        (subject.milestone ? `,里程碑「${subject.milestone}」${subject.milestoneDate ?? ''}` : ''))
    }
  }
  const lines = [
    `今天:${input.today}`,
    `本次最多可以调整到:${input.windowTo}(只允许这个日期及之前,且不早于今天)`,
    `当前计划涉及的科目:${input.subjects.join('、') || '未知'}`,
    ...(briefLines.length > 0 ? ['', '各科概况:', ...briefLines] : []),
  ]
  if (input.budget) {
    lines.push('', `预算:目标 ${input.budget.targetMinutes} 分钟,已排 ${input.budget.scheduledMinutes} 分钟`)
  }
  if (input.windowItems?.length) {
    lines.push('', '窗口内任务(最多 60 条,用于定位「那项」):')
    for (const item of input.windowItems.slice(0, 60)) {
      lines.push(`- ${item.planDate} | ${item.subject} | ${item.title} | ${item.minutes} 分钟${item.locked ? ' | 已锁定' : ''}`)
    }
  }
  // 只带最近 6 条、每条截 200 字:意图解析不需要完整史,足够定位「对」指的是哪个问题即可
  const history = (input.history ?? []).slice(-6)
  if (history.length > 0) {
    lines.push('', '最近对话(旧到新):')
    for (const item of history) {
      lines.push(`${item.role === 'user' ? '用户' : '助手'}:${item.content.slice(0, 200)}`)
    }
  }
  lines.push('', `用户说(本次消息):${input.message}`)
  return lines.join('\n')
}

/** 清洗模型输出:越界日期丢弃、非法条目丢弃、数据撑不起 kind 降级为 chat、summary 缺省走兜底文案 */
export function normalizeAdjustIntent(raw: any, input: AdjustIntentInput): AdjustIntent {
  const text = (value: unknown, max: number) => typeof value === 'string' ? value.trim().slice(0, max) : ''
  const kind = KINDS.includes(raw?.kind) ? raw.kind as AdjustIntentKind : 'chat'
  let needClarify = raw?.needClarify === true
  let outOfRange = false
  const inWindow = (day: string) => day >= input.today && day <= input.windowTo

  // 模型给出的日期必须落在调整窗口内(不早于今天、不晚于窗口末),越界一律丢弃
  const days = Array.isArray(raw?.days)
    ? [...new Set(raw.days.filter((day: unknown) => typeof day === 'string' && DAY_PATTERN.test(day)
        && inWindow(day)) as string[])].sort()
    : []

  const windows: AdjustCapacityWindow[] = []
  if (Array.isArray(raw?.windows)) {
    for (const entry of raw.windows) {
      const day = typeof entry?.day === 'string' ? entry.day : ''
      const minutes = Math.round(Number(entry?.minutes))
      if (!DAY_PATTERN.test(day) || !inWindow(day)) continue
      if (!Number.isFinite(minutes) || minutes < 0 || minutes > 1440) continue
      if (windows.some(existing => existing.day === day)) continue
      windows.push({ day, minutes })
    }
  }
  windows.sort((a, b) => a.day.localeCompare(b.day))

  const commitments: AdjustCommitment[] = []
  if (Array.isArray(raw?.commitments)) {
    for (const entry of raw.commitments) {
      const weekday = Math.round(Number(entry?.weekday))
      const start = typeof entry?.start === 'string' ? entry.start : ''
      const end = typeof entry?.end === 'string' ? entry.end : ''
      const label = text(entry?.label, 60) || '固定占用'
      if (!Number.isInteger(weekday) || weekday < 1 || weekday > 7) continue
      if (!TIME_PATTERN.test(start) || !TIME_PATTERN.test(end) || start >= end) continue
      // 非法 parity 只丢属性不丢整条:说错「单周」不该让整条占用消失,退化成每周都占更安全
      const parity = entry?.weekParity
      const weekParity = parity === 'odd' || parity === 'even' ? parity : undefined
      if (commitments.some(existing => existing.weekday === weekday && existing.start === start
        && existing.end === end && existing.label === label
        && (existing.weekParity ?? '') === (weekParity ?? ''))) continue
      commitments.push({ weekday, start, end, label, ...(weekParity ? { weekParity } : {}) })
    }
  }

  const tasks: AdjustTaskRef[] = []
  if (Array.isArray(raw?.tasks)) {
    for (const entry of raw.tasks) {
      const subject = text(entry?.subject, 32)
      if (!subject) continue
      const title = text(entry?.title, 64) || undefined
      const date = text(entry?.date, 10)
      const targetDate = text(entry?.targetDate, 10)
      if (date && !DAY_PATTERN.test(date)) continue
      if (targetDate && !DAY_PATTERN.test(targetDate)) continue
      if ((date && !inWindow(date)) || (targetDate && !inWindow(targetDate))) outOfRange = true
      if (tasks.length < 8) tasks.push({ subject, ...(title ? { title } : {}), ...(date ? { date } : {}), ...(targetDate ? { targetDate } : {}) })
    }
  }

  const newTasks: AdjustNewTask[] = []
  if (Array.isArray(raw?.newTasks)) {
    for (const entry of raw.newTasks) {
      const subject = text(entry?.subject, 32)
      const title = text(entry?.title, 64)
      const date = text(entry?.date, 10)
      const minutes = Math.round(Number(entry?.minutes))
      if (date && (!DAY_PATTERN.test(date) || !inWindow(date))) { outOfRange = true; continue }
      if (!subject || !title) continue
      newTasks.push({ subject, title, date, minutes: Number.isFinite(minutes) ? Math.max(0, Math.min(480, minutes)) : 0 })
    }
  }

  const scope: AdjustScopeItem[] = []
  if (Array.isArray(raw?.scope)) {
    for (const entry of raw.scope) {
      const subject = text(entry?.subject, 32)
      if (!subject) continue
      const item: AdjustScopeItem = { subject }
      const remaining = Math.round(Number(entry?.remainingMinutes))
      if (Number.isFinite(remaining) && remaining >= 0 && remaining <= 500000) item.remainingMinutes = remaining
      const milestoneMinutes = Math.round(Number(entry?.milestoneMinutes))
      if (Number.isFinite(milestoneMinutes) && milestoneMinutes >= 0 && milestoneMinutes <= 500000) item.milestoneMinutes = milestoneMinutes
      const milestone = text(entry?.milestone, 64)
      if (milestone) item.milestone = milestone
      const milestoneDate = text(entry?.milestoneDate, 10)
      if (milestoneDate && DAY_PATTERN.test(milestoneDate) && inWindow(milestoneDate)) item.milestoneDate = milestoneDate
      if (typeof entry?.estimated === 'boolean') item.estimated = entry.estimated
      scope.push(item)
    }
  }

  const clarifyOptions = Array.isArray(raw?.clarifyOptions)
    ? raw.clarifyOptions
        .map((entry: any) => ({ label: text(entry?.label, 30), value: text(entry?.value, 100) }))
        .filter((entry: { label: string; value: string }) => entry.label && entry.value)
        .slice(0, 4)
    : []

  // 降级:解析出的数据撑不起这个 kind,就当闲聊处理(needClarify 优先,让模型先问清楚)
  let finalKind = kind
  const missingFor = (target: AdjustIntentKind): string => {
    if (target === 'move_task') return '要把哪一条任务挪到哪天？'
    if (target === 'add_task') return '要加什么任务、放在哪天、学多久？'
    if (target === 'drop_task') return '要删掉哪一条任务？'
    if (target === 'lock_task') return '要锁定哪一条任务或哪个科目？'
    if (target === 'adjust_scope') return '要把哪一科的目标调成多少？'
    return '能再说具体一点吗？'
  }
  if (outOfRange) {
    needClarify = true
  }
  if (!needClarify) {
    if (finalKind === 'unavailable' && days.length === 0 && windows.length === 0) finalKind = 'chat'
    if (finalKind === 'reduce_capacity' && windows.length === 0) finalKind = 'chat'
    if (finalKind === 'change_commitment' && commitments.length === 0) finalKind = 'chat'
    if (finalKind === 'move_task' && (tasks.length === 0 || tasks.some(task => !task.targetDate))) {
      finalKind = 'move_task'
      needClarify = true
    }
    if (finalKind === 'add_task' && (newTasks.length === 0 || newTasks.some(task => !task.date || task.minutes <= 0))) {
      finalKind = 'add_task'
      needClarify = true
    }
    if ((finalKind === 'drop_task' || finalKind === 'lock_task') && tasks.length === 0) {
      needClarify = true
    }
    if (finalKind === 'adjust_scope' && (scope.length === 0
      || !scope.some(item => item.remainingMinutes !== undefined || item.milestoneMinutes !== undefined
        || item.milestoneDate !== undefined || item.estimated !== undefined))) {
      needClarify = true
    }
  }

  // 确定性兜底:模型经常在 note 里写清了「今天 14:00-18:00 有事」「明天整个下午没空」却不产 windows(见实测)。
  // 这一步本就是确定性算术,不该交给模型——从 note+原话解析日期与时段(钟点或"下午"这类时段词),按课表算剩余容量。
  const note = text(raw?.note, 300)
  if (finalKind === 'chat' && !needClarify) {
    const fallback = blockedRangeFallback(input, note)
    if (fallback) {
      if (fallback.minutes === null) {
        // 整天没空:直接归入 unavailable
        if (!days.includes(fallback.day)) days.push(fallback.day)
        days.sort()
        finalKind = 'unavailable'
      } else {
        finalKind = 'reduce_capacity'
        windows.push({ day: fallback.day, minutes: fallback.minutes })
        windows.sort((a, b) => a.day.localeCompare(b.day))
      }
    }
  }

  // 模型常把「整段下午/上午没空」误归 unavailable(整天)——用户明明只是部分时段没空。
  // 按课表算出每天剩余分钟,矫正成 reduce_capacity;只要有任一天算不出(无课表)就保守保留原判。
  if (finalKind === 'unavailable' && days.length > 0 && !needClarify) {
    const source = `${input.message}\n${note}`
    if (!/一?整天|全天/.test(source)) {
      const range = parseBlockedRange(source) ?? dayPartRange(source)
      if (range) {
        const computed = days.map(day => ({ day, minutes: computeRemainingMinutes(input, day, range) }))
        if (computed.every(c => c.minutes !== null)) {
          windows.push(...computed.map(c => ({ day: c.day, minutes: Math.round(c.minutes! / 5) * 5 })))
          windows.sort((a, b) => a.day.localeCompare(b.day))
          days.length = 0
          finalKind = 'reduce_capacity'
        }
      }
    }
  }

  const importance: AdjustImportanceHint[] = []
  if (Array.isArray(raw?.importance)) {
    const allowedSubjects = new Set(input.subjects)
    for (const entry of raw.importance) {
      if (importance.length >= 8) break
      const subject = text(entry?.subject, 32)
      const level = entry?.level === 'protect' || entry?.level === 'elastic' ? entry.level : null
      const title = text(entry?.title, 64) || undefined
      const date = text(entry?.date, 10)
      if (!subject || !level) continue
      if (allowedSubjects.size > 0 && !allowedSubjects.has(subject)) continue
      if (date && (!DAY_PATTERN.test(date) || !inWindow(date))) continue
      importance.push({ subject, level, ...(title ? { title } : {}), ...(date ? { date } : {}) })
    }
  }
  const horizonRaw = Math.round(Number(raw?.horizonDays))
  let horizonDays = Number.isFinite(horizonRaw) && horizonRaw > 0
    ? Math.max(DEFAULT_ADJUST_DAYS, Math.min(MAX_ADJUST_DAYS, horizonRaw))
    : undefined
  // 用户点名了第 6 天以后的日期时,窗口必须覆盖到那天,否则「把阅读挪到下周三」会被误判越界
  const explicitDays = [
    ...days,
    ...windows.map(window => window.day),
    ...tasks.flatMap(task => [task.date, task.targetDate].filter((day): day is string => !!day)),
    ...newTasks.map(task => task.date),
  ].filter(day => DAY_PATTERN.test(day) && inWindow(day))
  if (explicitDays.length > 0) {
    const maxDay = [...explicitDays].sort()[explicitDays.length - 1]
    const diffDays = Math.round(
      (Date.parse(`${maxDay}T00:00:00.000Z`) - Date.parse(`${input.today}T00:00:00.000Z`)) / 86_400_000,
    ) + 1
    const needed = Math.max(DEFAULT_ADJUST_DAYS, Math.min(MAX_ADJUST_DAYS, diffDays))
    if (!horizonDays || needed > horizonDays) horizonDays = needed
  }

  const summary = text(raw?.summary, 300) || defaultSummary(finalKind, days, windows, commitments)
  const clarifyQuestion = text(raw?.clarifyQuestion, 200) || (needClarify ? missingFor(finalKind) : '')
  return {
    kind: finalKind,
    days,
    windows,
    commitments,
    tasks,
    newTasks,
    scope,
    note,
    summary,
    needClarify,
    clarifyQuestion,
    clarifyOptions,
    ...(importance.length > 0 ? { importance } : {}),
    ...(horizonDays ? { horizonDays } : {}),
  }
}

function defaultSummary(
  kind: AdjustIntentKind, days: string[], windows: AdjustCapacityWindow[], commitments: AdjustCommitment[],
): string {
  const fmtDay = (day: string) => `${day.slice(5, 7)}月${day.slice(8, 10)}日`
  if (kind === 'unavailable') {
    return days.length > 0 ? `${days.map(fmtDay).join('、')}没空,把任务顺延` : '近期没空,把任务顺延'
  }
  if (kind === 'reduce_capacity') {
    return windows.length > 0
      ? `${windows.map(window => `${fmtDay(window.day)}只剩${window.minutes}分钟`).join('、')},按新时间重排`
      : '近期时间变少,按新时间重排'
  }
  if (kind === 'change_commitment') {
    return commitments.length > 0
      ? `每周固定安排多了${commitments.length}项,重新平衡近期计划`
      : '每周固定安排变了,重新平衡近期计划'
  }
  if (kind === 'move_task') return '按你说的把任务挪到新日期'
  if (kind === 'add_task') return '按你说的往计划里加任务'
  if (kind === 'drop_task') return '按你说的删掉指定任务'
  if (kind === 'lock_task') return '锁定指定任务,以后 AI 调整不再动它'
  if (kind === 'adjust_scope') return '按你说的调整科目目标和里程碑'
  if (kind === 'reschedule') return '把落后的任务从今天起重新排进空档'
  return '聊聊近期的备考安排'
}

// ---------- 确定性兜底:从「今天14:00-18:00没空」式表述算剩余容量 ----------

/** "HH:MM-HH:MM" 与 "X点(Y分)到Z点(W分)" 两种写法;返回 [startMin, endMin] */
export function parseBlockedRange(text: string): [number, number] | null {
  const valid = (s: number, e: number): [number, number] | null =>
    Number.isFinite(s) && Number.isFinite(e) && s >= 0 && e <= 1440 && s < e ? [s, e] : null
  const hm = text.match(/(\d{1,2}):(\d{2})\s*(?:到|至|-|~|—)\s*(\d{1,2}):(\d{2})/)
  if (hm) return valid(Number(hm[1]) * 60 + Number(hm[2]), Number(hm[3]) * 60 + Number(hm[4]))
  const dian = text.match(/(\d{1,2})点(?:(\d{1,2})分)?\s*(?:到|至|-|~|—)\s*(\d{1,2})点(?:(\d{1,2})分)?/)
  if (dian) return valid(Number(dian[1]) * 60 + Number(dian[2] ?? 0), Number(dian[3]) * 60 + Number(dian[4] ?? 0))
  return null
}

function shiftDay(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00.000Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

/** 时段重叠分钟数:课表窗口 "HH:mm" vs 没空区间 */
function overlapMinutes(windows: Array<{ start: string; end: string }>, startMin: number, endMin: number): number {
  const toMin = (v: string) => {
    const [h, m] = v.split(':')
    return Number(h) * 60 + Number(m ?? 0)
  }
  let total = 0
  for (const w of windows) {
    const s = toMin(w.start), e = toMin(w.end)
    total += Math.max(0, Math.min(endMin, e) - Math.max(startMin, s))
  }
  return total
}

/** 时段词 → 大致钟点区间(兜底用,课表重叠计算会收敛误差) */
function dayPartRange(source: string): [number, number] | null {
  const dayParts: Array<[RegExp, number, number]> = [
    [/上午|早上|早晨/, 6 * 60, 12 * 60],
    [/中午|午休/, 11 * 60, 14 * 60],
    [/下午/, 12 * 60, 18 * 60],
    [/晚上|夜里|晚间/, 18 * 60, 23 * 60],
  ]
  for (const [re, s, e] of dayParts) {
    if (re.test(source)) return [s, e]
  }
  return null
}

/** 某日按课表算「没空时段后还能学」的分钟数;无课表/无重叠返回 null(调用方保守降级) */
function computeRemainingMinutes(
  input: AdjustIntentInput, day: string, range: [number, number],
): number | null {
  if (!input.brief?.availability?.length) return null
  const js = new Date(`${day}T00:00:00.000Z`).getUTCDay()
  const weekday = js === 0 ? 7 : js
  const dayWindows = input.brief.availability.filter(a => a.weekday === weekday).flatMap(a => a.windows ?? [])
  if (dayWindows.length === 0) return null
  const totalFree = dayWindows.reduce((sum, w) => {
    const [h1, m1] = w.start.split(':'), [h2, m2] = w.end.split(':')
    return sum + (Number(h2) * 60 + Number(m2 ?? 0)) - (Number(h1) * 60 + Number(m1 ?? 0))
  }, 0)
  const blocked = overlapMinutes(dayWindows, range[0], range[1])
  if (blocked <= 0) return null
  return Math.max(0, totalFree - blocked)
}

/**
 * note/原话里带「日期 + 没空时段」但模型没产出 windows 时,按课表确定性算出当天剩余分钟。
 * 时段支持钟点(14:00-18:00 / 14点到18点)与时段词(上午/中午/下午/晚上);「一整天/全天」归 unavailable。
 * 日期口径:ISO 日期 > 明天 > 后天 > 今天(默认);超出调整窗口、当天无课表或重叠为 0 则放弃。
 * 返回 minutes=null 表示整天没空(unavailable);否则是当天剩余可学分钟。
 */
function blockedRangeFallback(input: AdjustIntentInput, note: string): { day: string; minutes: number | null } | null {
  const source = `${input.message}\n${note}`
  const iso = source.match(/\d{4}-\d{2}-\d{2}/)?.[0]
  const day = iso
    ?? (source.includes('明天') ? shiftDay(input.today, 1) : null)
    ?? (source.includes('后天') ? shiftDay(input.today, 2) : null)
    ?? input.today
  if (day < input.today || day > input.windowTo) return null

  // 「一整天/全天」:不用算重叠,直接整天没空
  if (/一?整天|全天/.test(source)) return { day, minutes: null }

  // 时段:显式钟点优先,其次时段词
  const range = parseBlockedRange(source) ?? dayPartRange(source)
  if (!range) return null
  const remaining = computeRemainingMinutes(input, day, range)
  if (remaining === null) return null
  return { day, minutes: Math.round(remaining / 5) * 5 }
}
