import { chatComplete, extractJson, LlmError } from '../../shared/llm/client'
import { AiUnavailable } from './aiGenerator'
import type { PlanBrief } from './document'

/**
 * 行程调整的「意图解析」:模型唯一的工作是把用户这句话拆成结构化意图,
 * 档位、日期、容量全部由服务端判定 —— 模型不输出任何计划内容。
 */

/** unavailable=某几天完全没空 / reduce_capacity=几天可用时间变少 / change_commitment=每周固定占用变了 / chat=闲聊或没说清 */
export type AdjustIntentKind = 'unavailable' | 'reduce_capacity' | 'change_commitment' | 'chat'

/** change_commitment:每周固定的新占用(与 brief.fixedCommitments 同构) */
export interface AdjustCommitment {
  weekday: number
  start: string
  end: string
  label: string
}

/** reduce_capacity:某天可用时间只剩 minutes 分钟 */
export interface AdjustCapacityWindow {
  day: string
  minutes: number
}

export interface AdjustIntent {
  kind: AdjustIntentKind
  /** unavailable:完全没空的日期(YYYY-MM-DD,已裁剪到调整窗口内) */
  days: string[]
  windows: AdjustCapacityWindow[]
  commitments: AdjustCommitment[]
  /** 用户原话的补充说明,写入调整单 reason */
  note: string
  /** 给确认卡片的一句话摘要(模型写,服务端兜底) */
  summary: string
  needClarify: boolean
  clarifyQuestion: string
}

export interface AdjustIntentInput {
  message: string
  today: string
  /** 调整窗口上限(当前阶段末),模型给出的日期越界一律丢弃 */
  windowTo: string
  /** 当前计划涉及的科目名 */
  subjects: string[]
  brief: PlanBrief | null
}

const KINDS: AdjustIntentKind[] = ['unavailable', 'reduce_capacity', 'change_commitment', 'chat']
const DAY_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/
const ADJUST_MAX_TOKENS = 700

export async function runAdjustIntent(input: AdjustIntentInput): Promise<AdjustIntent> {
  try {
    const raw = await chatComplete({
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
    if (error instanceof LlmError) throw new AiUnavailable(error.message)
    throw error
  }
}

function buildSystemPrompt(): string {
  return [
    '你是考研备考计划助手「研钟」的行程调整分析师。',
    '用户会告诉你一件突发事情(临时有事、生病、加课、固定安排变了等)。',
    '你的任务是把这句话解析成结构化意图,只输出 JSON,不要输出多余文本。',
    'JSON 字段:',
    '- kind: "unavailable"(某几天完全没空) | "reduce_capacity"(某几天可用时间变少) | "change_commitment"(每周固定占用变了,如换课表) | "chat"(闲聊或信息不足)',
    '- days: 完全没空的日期数组,格式 YYYY-MM-DD,必须在给定窗口内',
    '- windows: 数组,每项 {day: "YYYY-MM-DD", minutes: 数字},表示那天只能学这么多分钟',
    '- commitments: 数组,每项 {weekday: 1-7(周一=1), start: "HH:mm", end: "HH:mm", label: "事项名"},表示每周固定的新占用',
    '- note: 从用户原话提取的补充说明,一句话',
    '- summary: 用一句话向用户复述你的理解,不超过 30 字',
    '- needClarify: 布尔,信息不足需要追问时为 true',
    '- clarifyQuestion: 需要追问时的问题,不超过 50 字',
    '规则:',
    '- 用户没说清日期时先追问,不要猜测。',
    '- 「明天」「后天」按给出的今天日期换算。',
    '- 生病、临时有事按 unavailable;换课表、长期占用按 change_commitment。',
    '- 与备考计划无关的闲聊一律 kind=chat。',
  ].join('\n')
}

function buildUserPrompt(input: AdjustIntentInput): string {
  const briefLines: string[] = []
  if (input.brief) {
    for (const subject of input.brief.examSubjects) {
      briefLines.push(`科目:${subject.name},剩余约${subject.remainingMinutes}分钟` +
        (subject.milestone ? `,里程碑「${subject.milestone}」${subject.milestoneDate ?? ''}` : ''))
    }
  }
  return [
    `今天:${input.today}`,
    `本次最多可以调整到:${input.windowTo}(只允许这个日期及之前,且不早于今天)`,
    `当前计划涉及的科目:${input.subjects.join('、') || '未知'}`,
    ...(briefLines.length > 0 ? ['', '各科概况:', ...briefLines] : []),
    '',
    `用户说:${input.message}`,
  ].join('\n')
}

/** 清洗模型输出:越界日期丢弃、非法条目丢弃、数据撑不起 kind 降级为 chat、summary 缺省走兜底文案 */
export function normalizeAdjustIntent(raw: any, input: AdjustIntentInput): AdjustIntent {
  const text = (value: unknown, max: number) => typeof value === 'string' ? value.trim().slice(0, max) : ''
  const kind = KINDS.includes(raw?.kind) ? raw.kind as AdjustIntentKind : 'chat'
  const needClarify = raw?.needClarify === true

  // 模型给出的日期必须落在调整窗口内(不早于今天、不晚于阶段末),越界一律丢弃
  const days = Array.isArray(raw?.days)
    ? [...new Set(raw.days.filter((day: unknown) => typeof day === 'string' && DAY_PATTERN.test(day)
        && day >= input.today && day <= input.windowTo) as string[])].sort()
    : []

  const windows: AdjustCapacityWindow[] = []
  if (Array.isArray(raw?.windows)) {
    for (const entry of raw.windows) {
      const day = typeof entry?.day === 'string' ? entry.day : ''
      const minutes = Math.round(Number(entry?.minutes))
      if (!DAY_PATTERN.test(day) || day < input.today || day > input.windowTo) continue
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
      if (commitments.some(existing => existing.weekday === weekday && existing.start === start
        && existing.end === end && existing.label === label)) continue
      commitments.push({ weekday, start, end, label })
    }
  }

  // 降级:解析出的数据撑不起这个 kind,就当闲聊处理(needClarify 优先,让模型先问清楚)
  let finalKind = kind
  if (!needClarify) {
    if (finalKind === 'unavailable' && days.length === 0 && windows.length === 0) finalKind = 'chat'
    if (finalKind === 'reduce_capacity' && windows.length === 0) finalKind = 'chat'
    if (finalKind === 'change_commitment' && commitments.length === 0) finalKind = 'chat'
  }

  const note = text(raw?.note, 300)
  const summary = text(raw?.summary, 300) || defaultSummary(finalKind, days, windows, commitments)
  return {
    kind: finalKind,
    days,
    windows,
    commitments,
    note,
    summary,
    needClarify,
    clarifyQuestion: text(raw?.clarifyQuestion, 200),
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
  return '聊聊近期的备考安排'
}
