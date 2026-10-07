import { chatComplete, extractJson, LlmError } from '../../shared/llm/client'
import { AiUnavailable } from './aiGenerator'
import { shiftDate, type AdjustableItem, type WindowSnapshotItem } from './adjust'

/**
 * L2 重排:模型只给「第 N 天、哪科、什么任务、多少分钟」的相对序列(不输出绝对日期),
 * 服务端按硬账本展开成窗口快照并校验 —— 与整计划生成同一分工,模型不给日期就不存在日期算歪。
 */

/** 模型输出的一个任务槽位:dayOffset 0 = 窗口第一天 */
export interface L2Task {
  dayOffset: number
  subject: string
  title: string
  minutes: number
}

export interface L2Ledger {
  /** 窗口内待办总分钟(重排必须守恒) */
  totalMinutes: number
  /** dayCapacity[i] = 第 i 天剩余容量(已扣 done) */
  dayCapacity: number[]
  /** 第 i 天的日期 key */
  dayKeys: string[]
  /** 科目白名单:brief 正式科目 ∪ 窗口内既有科目 */
  subjects: string[]
  /** 落在窗口内的里程碑(科目/日期/名称):对应科目的任务不得排到该日期之后 */
  milestones: Array<{ subject: string; date: string; name: string }>
}

export interface L2Input {
  message: string
  windowFrom: string
  windowTo: string
  ledger: L2Ledger
  /** 窗口内现有待办(展开时按它复用 id) */
  pending: AdjustableItem[]
}

const L2_MAX_TOKENS = 1600

const L2_SYSTEM_PROMPT = [
  '你是考研备考计划的重排引擎。给你一个调整窗口:每天的可用分钟数(账本)、窗口内现有的任务清单(科目/标题/分钟)、科目白名单和里程碑。',
  '请输出一份重排方案,只输出 JSON:{"tasks":[{"dayOffset":数字,"subject":"科目","title":"标题","minutes":数字}]}。',
  '规则:',
  '- dayOffset 从 0 开始,0 表示窗口第一天;所有任务必须落在窗口内。',
  '- subject 必须来自科目白名单;title 尽量沿用原任务标题,可以合并拆分,但每条不超过 30 字。',
  '- 全部任务的 minutes 总和必须等于窗口内现有任务的总分钟数(总量守恒,不凭空加减)。',
  '- 每天的 minutes 总和不得超过那天的可用分钟数。',
  '- 里程碑日期之前要排紧凑,不能把该科任务排到里程碑日期之后。',
  '- 可用分钟为 0 的日子(用户没空)不要排任何任务。',
].join('\n')

export async function runAdjustL2(input: L2Input): Promise<L2Task[]> {
  try {
    const raw = await chatComplete({
      messages: [
        { role: 'system', content: L2_SYSTEM_PROMPT },
        { role: 'user', content: buildL2UserPrompt(input) },
      ],
      json: true,
      temperature: 0.3,
      maxTokens: L2_MAX_TOKENS,
      timeoutMs: 45_000,
    })
    const parsed = extractJson<{ tasks?: unknown }>(raw)
    return Array.isArray(parsed?.tasks) ? parsed.tasks as L2Task[] : []
  } catch (error) {
    if (error instanceof LlmError) throw new AiUnavailable(error.message)
    throw error
  }
}

function buildL2UserPrompt(input: L2Input): string {
  const lines: string[] = []
  lines.push(`调整窗口:${input.windowFrom} 到 ${input.windowTo}`)
  lines.push('每天可用分钟:')
  input.ledger.dayKeys.forEach((day, index) =>
    lines.push(`- dayOffset ${index} = ${day},可用 ${input.ledger.dayCapacity[index]} 分钟`))
  lines.push('窗口内现有任务:')
  for (const item of input.pending) {
    lines.push(`- ${item.subject} | ${item.title} | ${item.minutes} 分钟 | 现在排在 ${item.planDate}`)
  }
  if (input.ledger.milestones.length > 0) {
    lines.push('窗口内的里程碑:')
    for (const milestone of input.ledger.milestones) {
      lines.push(`- ${milestone.subject}:${milestone.name} @ ${milestone.date}`)
    }
  }
  lines.push(`科目白名单:${input.ledger.subjects.join('、')}`)
  lines.push(`用户的情况:${input.message}`)
  return lines.join('\n')
}

/** 服务端先算的硬账本:窗口内待办总量、每日容量、科目白名单与窗口内里程碑 */
export function buildLedger(
  pending: AdjustableItem[],
  capacity: Map<string, number>,
  days: string[],
  subjects: string[],
  milestones: Array<{ subject: string; date: string; name: string }>,
): L2Ledger {
  const first = days[0]
  const last = days[days.length - 1] ?? ''
  return {
    totalMinutes: pending.reduce((sum, item) => sum + item.minutes, 0),
    dayCapacity: days.map(day => capacity.get(day) ?? 0),
    dayKeys: [...days],
    subjects: [...new Set([...subjects, ...pending.map(item => item.subject)])],
    milestones: milestones.filter(m => first !== undefined && m.date >= first && m.date <= last),
  }
}

/** 逐条清洗模型输出:dayOffset 落窗口内、科目在白名单、分钟为正;非法条目直接丢弃 */
export function normalizeL2Tasks(raw: unknown, ledger: L2Ledger): L2Task[] {
  if (!Array.isArray(raw)) return []
  const tasks: L2Task[] = []
  for (const entry of raw as any[]) {
    const dayOffset = Math.round(Number(entry?.dayOffset))
    const subject = typeof entry?.subject === 'string' ? entry.subject.trim() : ''
    const title = typeof entry?.title === 'string' ? entry.title.trim().slice(0, 64) : ''
    const minutes = Math.round(Number(entry?.minutes))
    if (!Number.isInteger(dayOffset) || dayOffset < 0 || dayOffset >= ledger.dayKeys.length) continue
    if (!ledger.subjects.includes(subject)) continue
    if (!title) continue
    if (!Number.isFinite(minutes) || minutes <= 0) continue
    tasks.push({ dayOffset, subject, title, minutes })
  }
  return tasks
}

/** 弹性收缩的单项下限:再紧也不能把任务压成低于 15 分钟的碎片 */
const COMPRESS_MIN_MINUTES = 15

/**
 * 弹性收缩:窗口塞不下时按比例压缩每个任务的分钟数,而不是把用户顶回去。
 * 用户请一天假缺口往往只有几百分钟,摊到几十天上每项任务缩个百分之几——
 * 硬报"排不下"是最蠢的出路。压缩后每项不低于 15 分钟;托不住缺口返回 null,
 * 由调用方继续走"容量不足"报错(那是真排不下的场景)。
 */
export function compressPendingToFit(
  pending: AdjustableItem[], totalCapacity: number,
): { items: AdjustableItem[]; total: number } | null {
  const totalPending = pending.reduce((sum, item) => sum + item.minutes, 0)
  if (totalPending <= 0 || totalCapacity <= 0 || totalCapacity >= totalPending) return null
  const scale = totalCapacity / totalPending
  const items = pending.map(item => ({
    ...item,
    minutes: Math.max(COMPRESS_MIN_MINUTES, Math.round(item.minutes * scale)),
  }))
  const total = items.reduce((sum, item) => sum + item.minutes, 0)
  if (total > totalCapacity) return null
  return { items, total }
}

/**
 * 把模型的任务序列展开成窗口快照,尽量复用既有 pending 项:
 * 同科同题 → 原项改日期/分钟;同科不同题 → 原项改标题/分钟;找不到同科 → 新增(id=0)。
 * 未被消费的 pending 项即被删除(由 diffSnapshots 呈现为 removed)。分钟是否合规交给 validateL2。
 */
export function expandL2(tasks: L2Task[], windowFrom: string, pending: AdjustableItem[]): WindowSnapshotItem[] {
  const pool = new Map<string, AdjustableItem[]>()
  for (const item of pending) {
    const list = pool.get(item.subject) ?? []
    list.push(item)
    pool.set(item.subject, list)
  }
  const usedIds = new Set<number>()
  const target: WindowSnapshotItem[] = []
  for (const task of [...tasks].sort((a, b) => a.dayOffset - b.dayOffset)) {
    const day = shiftDate(windowFrom, task.dayOffset)
    const list = pool.get(task.subject) ?? []
    const match = list.find(item => !usedIds.has(item.id) && item.title === task.title)
      ?? list.find(item => !usedIds.has(item.id))
    if (match != null) {
      usedIds.add(match.id)
      target.push({ id: match.id, subject: task.subject, title: task.title, planDate: day, minutes: task.minutes, status: 'pending' })
    } else {
      target.push({ id: 0, subject: task.subject, title: task.title, planDate: day, minutes: task.minutes, status: 'pending' })
    }
  }
  return target.sort((a, b) => a.planDate.localeCompare(b.planDate) || a.id - b.id)
}

/** 三条硬规则:总量守恒、每日不超容量、里程碑不推迟。返回第一个失败原因,便于回喂重试 */
export function validateL2(target: WindowSnapshotItem[], ledger: L2Ledger): { ok: boolean; reason?: string } {
  const total = target.reduce((sum, item) => sum + item.minutes, 0)
  if (total !== ledger.totalMinutes) {
    return { ok: false, reason: `重排后总分钟 ${total} 与原来的 ${ledger.totalMinutes} 不一致,总量必须守恒` }
  }
  const perDay = new Map<string, number>()
  for (const item of target) {
    perDay.set(item.planDate, (perDay.get(item.planDate) ?? 0) + item.minutes)
  }
  for (const [index, day] of ledger.dayKeys.entries()) {
    const planned = perDay.get(day) ?? 0
    if (planned > ledger.dayCapacity[index]) {
      return { ok: false, reason: `${day} 安排 ${planned} 分钟,超出当天可用的 ${ledger.dayCapacity[index]} 分钟` }
    }
  }
  for (const milestone of ledger.milestones) {
    const late = target.find(item => item.subject === milestone.subject && item.planDate > milestone.date)
    if (late) {
      return { ok: false, reason: `科目「${milestone.subject}」的里程碑「${milestone.name}」在 ${milestone.date},任务不能排到它之后` }
    }
  }
  return { ok: true }
}
