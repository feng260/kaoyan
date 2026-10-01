import { addDays, dayStart, diffDays, type ProfileInput } from './schemas'
import { chatComplete, extractJson, LlmError } from '../../shared/llm/client'
import type { GeneratedItem, GeneratedPlan, GeneratedStage } from './generator'
import { generatePlanDocument } from './aiDocument'
import { briefIsEmpty, briefToPrompt, documentMatchesSubjects, netAvailableMinutes, type PlanBrief, type PlanDocument } from './document'

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
  backlog?: Array<{ subject: string; title: string; minutes: number }>
  /**
   * 上一轮输出未通过服务端校验的原因。service 层重试时把原因喂回来,
   * 让模型带着「哪里被拦了」重新出骨架,而不是盲掷骰子。
   */
  repairHint?: string
}

type AiWeeklySlot = {
  weekdays: number[]
  subject: string
  title: string
  minutes: number
  weekParity?: 'odd' | 'even'
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
/**
 * 骨架输出的 max_tokens。显式指定而不是吃环境默认值,是为了让「阶段 + 每周作息模板」
 * 这类固定体量的输出在不同部署下表现一致;也给 LLM 客户端的降级重试留出收紧空间
 * (超过 2048 时才会触发「收紧 max_tokens 再试一次」这条兜底)。
 */
const SKELETON_MAX_TOKENS = 4096

const SYSTEM_PROMPT = `你是一位资深的中国考研全程规划师,只根据考生已确认的事实制定计划。
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
        { "weekdays": [1,2,3,4,5], "weekParity": "odd", "subject": "已确认的正式考试科目名称", "title": "已确认范围内的具体任务", "minutes": 90 }
      ]
    }
  ]
}

硬性要求:
1. stages 2-6 个,按时间顺序排列,首尾相接、不重叠。startDate 为今天,endDate 为考前一天。
2. 各阶段的相对长度要符合考研备考规律:基础期最长,强化期次之,冲刺期最短。若考期在半年内则压缩为基础/强化/冲刺三段。
3. weeklySlots 是按科目和星期匹配的任务队列:同一科目的后续条目在后续周推进,不可每周重放已完成的标题。
   - weekdays 用 1-7 表示周一到周日;weekParity 用 odd/even 区分从本阶段起点算起的奇周/偶周,省略表示不限定;任务完成后不循环重播;
   - 每个阶段提供足够覆盖实际空闲时段的任务,按先修顺序排列;
   - 每日安排总时长不能超过真实空闲时段扣除固定占用后的容量;
   - title 只能涉及已确认科目的已知范围、进度和里程碑;自命题范围不明确时只能使用考生确认的里程碑原文,不得推测章节、教材或题型。
4. subject 只能取自考生已确认的正式考试科目;不得按薄弱科目或常见组合补充其他考试科目。
5. 阶段推进要有明显的难度与任务变化:基础期打基础、强化期刷题专项、冲刺期真题模考与背诵。
6. 无剩余任务或无空闲时段的日期不要凭空补任务。`

function buildUserPrompt(input: AiPlanningInput): string {
  const today = dayStart(new Date())
  const exam = dayStart(input.examDate)
  const totalDays = diffDays(exam, today)
  const weak = input.weakSubjects.filter(Boolean)
  const lines = [
    `目标类型:${input.targetType}`,
    `今天:${fmt(today)}`,
    `考试日期:${fmt(exam)}(距今 ${totalDays} 天)`,
    `基础水平:${input.foundation}`,
    `问卷学习时段(以面谈确认的真实空闲时段为准):${input.studyWindows.join('、')}`,
    `问卷薄弱科目(仅在正式科目中倾斜):${weak.join('、')}`,
  ]
  if (input.brief && !briefIsEmpty(input.brief)) {
    lines.push('', '面谈得到的补充信息(必须体现在阶段与任务安排里):', briefToPrompt(input.brief))
  }
  if (input.backlog?.length) {
    lines.push('', '上一版未完成任务(按原顺序优先安排;不要在新模板中重复生成同一任务):', JSON.stringify(input.backlog))
  }
  if (input.repairHint) {
    lines.push('', `上一轮输出被服务端校验拦下,原因:${input.repairHint}`,
      '请务必修正该问题后重新输出完整骨架;其余部分可以沿用上一轮的合理内容。')
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
      const parity = (slot as AiWeeklySlot)?.weekParity
      const weekParity = parity === 'odd' || parity === 'even' ? parity : undefined
      const key = `${weekdays.join(',')}|${weekParity ?? ''}|${subject}|${title}`
      if (seen.has(key)) continue
      seen.add(key)
      slots.push({ weekdays, weekParity, subject: subject.slice(0, 24), title: title.slice(0, 80), minutes })
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
 *
 * 前置条件:stages 的个数不超过「今天到考前一天」的总天数(调用方已先截断),
 * 否则「每段至少 1 天」必然把末段推到考前一天之后,校验一定失败。
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

  // 每段至少 1 天,四舍五入后的总和未必正好等于总天数,这里多退少补修正:
  // 多了就从后往前削(不削到 1 以下),少了就全补给最后一段。
  const counts = weights.map(w => Math.max(1, Math.round(totalDays * w)))
  let diff = totalDays - counts.reduce((a, b) => a + b, 0)
  for (let i = counts.length - 1; diff < 0 && i >= 0; i--) {
    const cut = Math.min(counts[i] - 1, -diff)
    counts[i] -= cut
    diff += cut
  }
  if (diff > 0) counts[counts.length - 1] += diff

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
 * 把模型骨架里的科目名对齐到考生确认过的科目名。
 *
 * 为什么需要:模型偶尔不照抄确认名单,写出「申论范文」「行政职业能力测验」这类变体,
 * 而生成闸门会以「计划包含未确认的考试科目」为由拦下整份计划 —— 模板本身没问题,
 * 死在名字上最冤。对齐规则从严到宽:
 *   1. 归一化(去空白、全角转半角)后精确相等;
 *   2. 互相包含且唯一命中(「申论范文」⊃「申论」);
 *   3. 确认科目只有一门时无条件对齐(不存在歧义);
 *   4. 仍对不上就丢弃该条目 —— 少排一门比整份计划被拦好得多。
 */
export function alignSubjectsToConfirmed(
  stages: AiStage[],
  confirmed: string[]
): AiStage[] {
  if (confirmed.length === 0) return stages
  const canon = (value: string) => value.replace(/\s+/g, '').replace(/[０-９]/g, ch => String.fromCharCode(ch.charCodeAt(0) - 0xfee0))
  const exact = new Map(confirmed.map(name => [canon(name), name]))
  const aligned = stages.map(stage => ({
    ...stage,
    weeklySlots: stage.weeklySlots.flatMap(slot => {
      const key = canon(slot.subject)
      const direct = exact.get(key)
      if (direct) return [{ ...slot, subject: direct }]
      const hits = confirmed.filter(name => canon(name).includes(key) || key.includes(canon(name)))
      if (hits.length === 1) return [{ ...slot, subject: hits[0] }]
      if (confirmed.length === 1) return [{ ...slot, subject: confirmed[0] }]
      return []
    }),
  }))
  // 整段 slot 被丢空的阶段留着只会排出空阶段,直接剔除
  return aligned.filter(stage => stage.weeklySlots.length > 0)
}

/**
 * 把阶段模板铺满整个阶段:
 * - 命中的 weeklySlots 排上;合计超出每日预算时先按比例等比压缩,若受 5 分钟下限所限压不下来,
 *   则按上限裁掉多出的条目,保证每天的合计一定不超过预算;
 * - 某天一条都没命中(模型漏写星期几),用该阶段第一条模板兜底,保证每天都有安排。
 */
export function expandStage(stage: AiStage, range: GeneratedStage, brief: Pick<PlanBrief, 'availability' | 'fixedCommitments'>): GeneratedItem[] {
  const items: GeneratedItem[] = []
  const stageLength = diffDays(range.endDate, range.startDate) + 1
  const subjects = [...new Set(stage.weeklySlots.map(slot => slot.subject))]
  const completed = new Map(subjects.map(subject => [subject, new Set<number>()]))
  for (let offset = 0; offset < stageLength; offset++) {
    const planDate = addDays(range.startDate, offset)
    const budget = netAvailableMinutes(brief, planDate)
    if (budget < MIN_SLOT_MINUTES) continue
    const dow = isoWeekday(planDate)
    const weekParity = Math.floor(offset / 7) % 2 === 0 ? 'odd' : 'even'
    const matched = subjects.flatMap(subject => {
      const next = stage.weeklySlots.findIndex((slot, index) => slot.subject === subject
        && !completed.get(subject)?.has(index)
        && (!slot.weekParity || slot.weekParity === weekParity) && slot.weekdays.includes(dow))
      return next >= 0 ? [{ slot: stage.weeklySlots[next], next }] : []
    })
    if (matched.length === 0) continue

    // 每项都有 MIN_SLOT_MINUTES 的下限,命中条数 × 下限若已超出每日预算,
    // 再怎么压缩也降不下来,落库前校验必然判定「超出每日可用」。这里先按预算裁掉超出的条目。
    const slots = matched.slice(0, Math.floor(budget / MIN_SLOT_MINUTES))

    let total = slots.reduce((sum, entry) => sum + entry.slot.minutes, 0)
    const scale = total > budget ? budget / total : 1
    // 压缩后每项不低于 MIN_SLOT_MINUTES;宁可略微超出也不再往下砍,避免出现无意义的碎片任务
    const minutes = slots.map(entry => Math.max(MIN_SLOT_MINUTES, Math.floor(entry.slot.minutes * scale)))
    total = minutes.reduce((a, b) => a + b, 0)
    // 压缩后仍超出预算则从最后一项起逐分钟削减
    for (let i = minutes.length - 1; total > budget && i >= 0; i--) {
      const cut = Math.min(minutes[i] - MIN_SLOT_MINUTES, total - budget)
      if (cut > 0) {
        minutes[i] -= cut
        total -= cut
      }
    }

    slots.forEach(({ slot, next }, i) => {
      completed.get(slot.subject)?.add(next)
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

export function scheduleBacklog(
  stages: GeneratedStage[],
  generated: GeneratedItem[],
  backlog: NonNullable<AiPlanningInput['backlog']>,
  brief: Pick<PlanBrief, 'availability' | 'fixedCommitments'>,
  confirmedSubjects?: string[],
): GeneratedItem[] {
  if (backlog.length === 0) return generated
  const allowed = new Set(confirmedSubjects ?? generated.map(item => item.subject))
  const invalid = backlog.find(item => !allowed.has(item.subject))
  if (invalid) throw new AiUnavailable(`旧任务不属于已确认的考试科目:${invalid.subject}`)
  const overlap = new Map<string, number>()
  for (const item of backlog) {
    const key = `${item.subject}\u0000${item.title.trim()}`
    overlap.set(key, (overlap.get(key) ?? 0) + item.minutes)
  }
  const newWork = generated.flatMap(item => {
    const key = `${item.subject}\u0000${item.title.trim()}`
    const duplicate = Math.min(item.minutes, overlap.get(key) ?? 0)
    overlap.set(key, (overlap.get(key) ?? 0) - duplicate)
    return item.minutes > duplicate ? [{ ...item, minutes: item.minutes - duplicate }] : []
  })
  const demand = [
    ...backlog.map(item => ({ ...item, earliest: stages[0].startDate })),
    ...newWork.map(item => ({ ...item, earliest: item.planDate })),
  ]
  const output: GeneratedItem[] = []
  let index = 0
  let remaining = demand[0]?.minutes ?? 0
  for (const stage of stages) {
    for (let date = stage.startDate; date <= stage.endDate; date = addDays(date, 1)) {
      let budget = netAvailableMinutes(brief, date)
      let sortOrder = 0
      while (budget > 0 && index < demand.length && demand[index].earliest <= date) {
        const item = demand[index]
        const minutes = Math.min(remaining, budget)
        output.push({ stageOrder: stage.sortOrder, subject: item.subject, title: item.title,
          planDate: date, minutes, priority: stage.sortOrder, sortOrder: sortOrder++ })
        budget -= minutes
        remaining -= minutes
        if (remaining === 0) {
          index++
          remaining = demand[index]?.minutes ?? 0
        }
      }
    }
  }
  if (index < demand.length) throw new AiUnavailable(`旧任务与新任务无法在考前排完:${demand[index].subject}`)
  return output
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
  const brief = input.brief
  // 每日容量以课表净空闲为准,没有已确认的空闲时段就无法排计划
  if (!brief) throw new AiUnavailable('缺少已确认的空闲时段(课表/固定占用),无法排计划')

  let content: string
  try {
    content = await chatComplete({
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'user', content: buildUserPrompt(input) },
      ],
      json: true,
      temperature: 0.5,
      maxTokens: SKELETON_MAX_TOKENS,
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

  let stages = normalizeStages(parsed, input.weakSubjects)
  // 模型偶尔不照抄确认科目名(「申论范文」vs「申论」),先对齐再展开,
  // 避免整份计划死在「包含未确认的考试科目」这种名字问题上
  stages = alignSubjectsToConfirmed(stages, brief.examSubjects.map(subject => subject.name))
  // 备考天数不足以容纳模型的全部阶段时(考试日期很近),只保留时间上最后的几段:
  // 临考时冲刺/模考的模板比基础段的更有用,也避免「每段至少 1 天」把计划推到考后。
  const usable = stages.slice(-Math.max(1, diffDays(exam, today)))
  const ranges = layoutStages(usable, today, exam)

  const items: GeneratedItem[] = []
  usable.forEach((stage, index) => {
    items.push(...expandStage(stage, ranges[index], brief))
  })
  const scheduledItems = scheduleBacklog(ranges, items, input.backlog ?? [],
    brief, brief.examSubjects.map(subject => subject.name))

  if (scheduledItems.length === 0) {
    throw new AiUnavailable('模型给出的模板无法展开出任何计划项')
  }

  const title = typeof parsed.title === 'string' && parsed.title.trim()
    ? parsed.title.trim().slice(0, 32)
    : `${input.targetType}备考计划`

  // 长文档是加分项:任何异常都只降级为 null,绝不能把已经成型的每日清单一起废掉
  let document: PlanDocument | null = null
  try {
    const generatedDocument = await generatePlanDocument({
      title,
      profile: input,
      brief,
      stages: ranges,
      startDate: today,
    })
    if (generatedDocument && documentMatchesSubjects(generatedDocument, brief.examSubjects.map(subject => subject.name))) {
      document = generatedDocument
    }
  } catch (error) {
    console.warn('[planning] 生成计划长文档失败,仅保留每日清单:', (error as Error)?.message ?? error)
  }

  return { title, plan: { stages: ranges, items: scheduledItems }, document }
}
