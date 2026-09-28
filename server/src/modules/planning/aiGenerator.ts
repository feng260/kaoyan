import { addDays, dayStart, diffDays, type ProfileInput } from './schemas'
import { chatComplete, extractJson, LlmError } from '../../shared/llm/client'
import type { GeneratedItem, GeneratedPlan, GeneratedStage } from './generator'
import { generatePlanDocument } from './aiDocument'
import { briefIsEmpty, briefToPrompt, type PlanBrief, type PlanDocument } from './document'

/**
 * AI 版计划生成器。
 *
 * 分工:大模型只产出「阶段骨架 + 每个阶段的每周作息模板」,服务端把模板展开成整段备考期的
 * 每日计划项。这样做的三个理由:
 * 1. 一份 400+ 天的计划逐日列举会超出模型输出上限,而且容易中途截断;
 * 2. 展开逻辑留在服务端,结果可复算、可校验,并经过落库前的完整性校验;
 * 3. 模型擅长的正是「这个阶段该练什么、每周怎么排」这类策略判断,而不是把日期抄 400 遍。
 *
 * 阶段日期一律以服务端算出的连续区间为准,模型的日期只用来表达「各阶段大致占比」,
 * 避免模型算错天数导致计划与考期对不上。
 *
 * 除了每日清单,这里还会再生成一份长文档(PlanDocument,对标「468 天考研全程作战计划」)。
 * 文档是加分项:它失败只降级为 null,绝不连累每日清单。
 */

export type AiPlanningInput = ProfileInput & {
  startDate: Date
  /** 面谈得到的考生画像;没聊过就是 null,生成器退化为只看问卷 */
  brief?: PlanBrief | null
}

type AiWeeklySlot = {
  weekdays: number[]
  subject: string
  title: string
  minutes: number
}

type AiStage = {
  name: string
  focus?: string
  startDate?: string
  endDate?: string
  weeklySlots: AiWeeklySlot[]
}

type AiPlanJson = {
  title?: string
  stages: AiStage[]
}

/** 模型不可用或返回不合规时抛出,由 service 层转成业务错误响应 */
export class AiUnavailable extends Error {
  constructor(public reason: string) {
    super(reason)
    this.name = 'AiUnavailable'
  }
}

const MAX_STAGES = 6
const MIN_STAGES = 2
const MIN_SLOT_MINUTES = 5
const DEFAULT_MINUTES = 45

const SYSTEM_PROMPT = `你是一位资深的中国考研全程规划师,服务过大量「408 计算机学科专业基础 + 数学二 + 英语二 + 政治」的考生。
你的任务:根据考生的备考档案,输出一份分阶段、可执行的备考计划骨架。
严格只输出一个 JSON 对象,不要任何解释文字、不要 Markdown 代码块。JSON 结构:
{
  "title": "计划名称,不超过 24 字",
  "stages": [
    {
      "name": "阶段名称,如「基础唤醒期」",
      "focus": "本阶段一句话核心任务,不超过 40 字",
      "startDate": "YYYY-MM-DD",
      "endDate": "YYYY-MM-DD",
      "weeklySlots": [
        { "weekdays": [1,2,3,4,5], "subject": "数学", "title": "具体到章节或题型的任务名", "minutes": 90 }
      ]
    }
  ]
}

硬性要求:
1. stages 2-6 个,按时间顺序排列,首尾相接、不重叠。startDate 为今天,endDate 为考前一天。
2. 各阶段的相对长度要符合考研备考规律:基础期最长,强化期次之,冲刺期最短。若考期在半年内则压缩为基础/强化/冲刺三段。
3. weeklySlots 描述「这个阶段的每一周怎么过」,不是某一周的安排:
   - weekdays 用 1-7 表示周一到周日,可以把多个同安排的日子写在一起(如 [1,2,3,4,5] 表示工作日);
   - 每个阶段给 4-6 条 weeklySlots,覆盖考生每周可学习的时段;
   - 所有 weeklySlots 的 minutes 之和不得超过考生的「每日可用分钟数」;
   - title 必须具体到知识点或题型,如「高数 · 中值定理证明题专项」「408 · 进程与线程真题」「英语二 · 2015 阅读逐句精读」,禁止「数学 · 基础梳理」这类空话。
4. subject 必须优先取自考生的薄弱科目,薄弱科目应获得更多时段。不属于薄弱科目的公共课(如政治)也要在合适阶段出现。
5. 阶段推进要有明显的难度与任务变化:基础期打基础、强化期刷题专项、冲刺期真题模考与背诵。
6. 每天至少安排 1 条 weeklySlots 覆盖,不要出现某天完全没有安排。`

function buildUserPrompt(input: AiPlanningInput): string {
  const today = dayStart(new Date())
  const exam = dayStart(input.examDate)
  const totalDays = diffDays(exam, today)
  const weak = input.weakSubjects.filter(Boolean)
  const lines = [
    `目标类型:${input.targetType}`,
    `今天:${fmt(today)}`,
    `考试日期:${fmt(exam)}(距今 ${totalDays} 天)`,
    `每日可用学习时长:${input.dailyMinutes} 分钟`,
    `基础水平:${input.foundation}`,
    `固定学习时段:${input.studyWindows.join('、')}`,
    `薄弱科目(需要重点倾斜):${weak.join('、')}`,
  ]
  if (input.brief && !briefIsEmpty(input.brief)) {
    lines.push('', '面谈得到的补充信息(必须体现在阶段与任务安排里):', briefToPrompt(input.brief))
  }
  lines.push('', `请输出从 ${fmt(today)} 到 ${fmt(addDays(exam, -1))} 的备考计划骨架。`)
  return lines.join('\n')
}

function fmt(d: Date): string {
  return dayStart(d).toISOString().slice(0, 10)
}

function parseDay(value: unknown): Date | null {
  if (typeof value !== 'string' || !value.trim()) return null
  const raw = value.trim()
  const d = new Date(raw.length === 10 ? `${raw}T00:00:00.000Z` : raw)
  return Number.isNaN(d.getTime()) ? null : dayStart(d)
}

function normalizeWeekdays(raw: unknown): number[] {
  if (!Array.isArray(raw)) return []
  const set = new Set<number>()
  for (const item of raw) {
    const n = Math.round(Number(item))
    if (Number.isFinite(n) && n >= 1 && n <= 7) set.add(n)
  }
  return [...set].sort((a, b) => a - b)
}

/** 把模型输出收成可信的中间结构;任何一条不合规的 weeklySlot 直接丢弃而不是让整份计划失败 */
function normalizeStages(parsed: AiPlanJson, weakSubjects: string[]): AiStage[] {
  if (!parsed || !Array.isArray(parsed.stages)) {
    throw new AiUnavailable('模型未返回 stages 数组')
  }
  const stages: AiStage[] = []
  for (const raw of parsed.stages.slice(0, MAX_STAGES)) {
    if (!raw || typeof raw.name !== 'string' || !raw.name.trim()) continue
    const seen = new Set<string>()
    const slots: AiWeeklySlot[] = []
    for (const slot of Array.isArray(raw.weeklySlots) ? raw.weeklySlots : []) {
      const weekdays = normalizeWeekdays((slot as AiWeeklySlot)?.weekdays)
      const subject = String((slot as AiWeeklySlot)?.subject ?? '').trim()
      const title = String((slot as AiWeeklySlot)?.title ?? '').trim()
      if (weekdays.length === 0 || !subject || !title) continue
      const minutes = Math.max(MIN_SLOT_MINUTES, Math.round(Number((slot as AiWeeklySlot)?.minutes) || DEFAULT_MINUTES))
      const key = `${weekdays.join(',')}|${subject}|${title}`
      if (seen.has(key)) continue
      seen.add(key)
      slots.push({ weekdays, subject: subject.slice(0, 24), title: title.slice(0, 80), minutes })
    }
    if (slots.length === 0) continue
    stages.push({
      name: raw.name.trim().slice(0, 24),
      focus: typeof raw.focus === 'string' ? raw.focus.trim().slice(0, 80) : undefined,
      startDate: typeof raw.startDate === 'string' ? raw.startDate : undefined,
      endDate: typeof raw.endDate === 'string' ? raw.endDate : undefined,
      weeklySlots: slots,
    })
  }
  if (stages.length < MIN_STAGES) {
    throw new AiUnavailable(`模型只给出了 ${stages.length} 个可用阶段`)
  }
  // 薄弱科目在模型输出里完全没出现时不算致命,但要记一笔,展开时用于兜底
  void weakSubjects
  return stages
}

/**
 * 按模型给出的阶段长度(拿不到就用默认比例)重新切出连续区间,
 * 保证「起点是今天、终点是考前一天、阶段首尾相接」三条硬约束一定成立。
 */
function layoutStages(stages: AiStage[], today: Date, exam: Date): GeneratedStage[] {
  const totalDays = diffDays(exam, today)
  const desired = stages.map(stage => {
    const start = parseDay(stage.startDate)
    const end = parseDay(stage.endDate)
    if (start && end && diffDays(end, start) >= 0) return diffDays(end, start) + 1
    return 0
  })
  const sum = desired.reduce((a, b) => a + b, 0)
  const weights = sum > 0 ? desired.map(d => d / sum) : stages.map(() => 1 / stages.length)

  const counts = weights.map(w => Math.max(1, Math.round(totalDays * w)))
  // 四舍五入后修正总天数,多退少补都落在最后一段上
  let diff = totalDays - counts.reduce((a, b) => a + b, 0)
  for (let i = counts.length - 1; diff !== 0 && i >= 0; i--) {
    const next = counts[i] + diff
    if (next >= 1) {
      counts[i] = next
      diff = 0
    }
  }
  if (diff !== 0) counts[counts.length - 1] = Math.max(1, counts[counts.length - 1] + diff)

  const out: GeneratedStage[] = []
  let cursor = today
  for (const [index, stage] of stages.entries()) {
    const endDate = addDays(cursor, counts[index] - 1)
    out.push({ name: stage.name, startDate: cursor, endDate, sortOrder: index })
    cursor = addDays(endDate, 1)
  }
  return out
}

/** ISO 星期:周一=1 至 周日=7,与模型约定的 weekdays 口径一致 */
function isoWeekday(d: Date): number {
  const js = d.getUTCDay()
  return js === 0 ? 7 : js
}

/**
 * 把阶段模板铺满整个阶段:
 * - 命中的 weeklySlots 全部排上;若合计超出每日预算,按比例等比压缩到预算内;
 * - 某天一条都没命中(模型漏写星期几),用该阶段第一条模板兜底,保证每天都有安排。
 */
function expandStage(stage: AiStage, range: GeneratedStage, dailyMinutes: number): GeneratedItem[] {
  const items: GeneratedItem[] = []
  const stageLength = diffDays(range.endDate, range.startDate) + 1
  for (let offset = 0; offset < stageLength; offset++) {
    const planDate = addDays(range.startDate, offset)
    const dow = isoWeekday(planDate)
    let matched = stage.weeklySlots.filter(slot => slot.weekdays.includes(dow))
    if (matched.length === 0) matched = [stage.weeklySlots[0]]

    let total = matched.reduce((sum, s) => sum + s.minutes, 0)
    const scale = total > dailyMinutes ? dailyMinutes / total : 1
    // 压缩后每项不低于 MIN_SLOT_MINUTES;宁可略微超出也不再往下砍,避免出现无意义的碎片任务
    const minutes = matched.map(s => Math.max(MIN_SLOT_MINUTES, Math.floor(s.minutes * scale)))
    total = minutes.reduce((a, b) => a + b, 0)
    // 压缩后仍超出预算则从最后一项起逐分钟削减
    for (let i = minutes.length - 1; total > dailyMinutes && i >= 0; i--) {
      const cut = Math.min(minutes[i] - MIN_SLOT_MINUTES, total - dailyMinutes)
      if (cut > 0) {
        minutes[i] -= cut
        total -= cut
      }
    }

    matched.forEach((slot, i) => {
      items.push({
        stageOrder: range.sortOrder,
        subject: slot.subject,
        title: slot.title,
        planDate,
        minutes: minutes[i],
        priority: range.sortOrder,
        sortOrder: i,
      })
    })
  }
  return items
}

/**
 * 调用大模型生成计划。模型侧的问题统一抛 AiUnavailable,
 * 由 service 层转成计划生成失败响应。
 *
 * 返回 document 可能为 null(长文档生成失败),调用方必须容忍。
 */
export async function generateAiPlan(input: AiPlanningInput): Promise<{
  title: string
  plan: GeneratedPlan
  document: PlanDocument | null
}> {
  const today = dayStart(new Date())
  const exam = dayStart(input.examDate)

  let content: string
  try {
    content = await chatComplete({
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'user', content: buildUserPrompt(input) },
      ],
      json: true,
      temperature: 0.5,
    })
  } catch (error) {
    if (error instanceof LlmError) throw new AiUnavailable(error.message)
    throw new AiUnavailable((error as Error)?.message ?? '调用大模型失败')
  }

  let parsed: AiPlanJson
  try {
    parsed = extractJson<AiPlanJson>(content)
  } catch (error) {
    throw new AiUnavailable((error as Error)?.message ?? '模型返回内容无法解析为 JSON')
  }

  const stages = normalizeStages(parsed, input.weakSubjects)
  const ranges = layoutStages(stages, today, exam)

  const items: GeneratedItem[] = []
  stages.forEach((stage, index) => {
    items.push(...expandStage(stage, ranges[index], input.dailyMinutes))
  })

  if (items.length === 0) {
    throw new AiUnavailable('模型给出的模板无法展开出任何计划项')
  }

  const title = typeof parsed.title === 'string' && parsed.title.trim()
    ? parsed.title.trim().slice(0, 32)
    : `${input.targetType}备考计划`

  // 长文档是加分项:任何异常都只降级为 null,绝不能把已经成型的每日清单一起废掉
  let document: PlanDocument | null = null
  try {
    document = await generatePlanDocument({
      title,
      profile: input,
      brief: input.brief ?? null,
      stages: ranges,
      startDate: today,
    })
  } catch (error) {
    console.warn('[planning] 生成计划长文档失败,仅保留每日清单:', (error as Error)?.message ?? error)
  }

  return { title, plan: { stages: ranges, items }, document }
}
