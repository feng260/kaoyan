import { chatComplete, extractJson } from '../../shared/llm/client'
import { dayStart, diffDays } from './schemas'
import type { AiWeekVariant, GeneratedStage } from './generator'
import type { PlanBrief } from './document'

/**
 * L2 阶段细化轮:把一个阶段的每周安排交给模型细化为具体的周变体任务序列。
 *
 * 为什么单独一层:骨架轮一次要管数百天,模型只能给"每科每天一条模板句";
 * 而一个阶段一次调用只需要产出 4-8 个周变体(每变体 = 一周的具体任务序列),
 * 输出预算集中、标题可以具体到"动作+对象+验收口径"——这是追平人工规划密度的关键。
 *
 * 失败语义:任何异常都向上抛,由 generateAiPlan 的 Promise.allSettled 捕获——
 * 单个阶段细化失败只让该阶段回退 weeklySlots 模板展开,绝不阻塞整份计划。
 */

export type StageRefineInput = {
  stage: GeneratedStage
  brief: PlanBrief
  confirmedSubjects: string[]
  weakSubjects: string[]
  targetType: string
}

/**
 * 细化输出的 max_tokens:一个阶段 4-8 个周变体 × 每周 ~20 条任务 JSON,
 * 8000 给足余量;厂商上限更低时由 LLM 客户端的预算阶梯自动落档。
 */
const REFINE_MAX_TOKENS = 8000
const REFINE_TIMEOUT_MS = 120_000

const MAX_VARIANTS = 8
const MIN_TASK_MINUTES = 15
const MAX_TASK_MINUTES = 120
const DEFAULT_TASK_MINUTES = 45
const MAX_TITLE_LENGTH = 64

const SYSTEM_PROMPT = `你是一位资深的备考任务细化师。给定一个备考阶段、阶段主线与考生的真实课表,把该阶段的每周安排细化为具体的任务序列。
严格只输出一个 JSON 对象,不要任何解释文字、不要 Markdown 代码块。JSON 结构:
{ "weeks": [
    [ { "weekday": 1, "subject": "已确认科目名", "title": "具体任务", "minutes": 45 } ],
    ... 4-8 个周变体,每个变体是完整一周的任务序列
] }

硬性要求:
1. weeks 给 4-8 个「周变体」;展开时第 1 周用第 1 个变体、第 2 周用第 2 个……变体循环使用,所以相邻变体必须体现递进(入门→加深→换题型→综合),禁止原样重复。
2. weekday 用 1-7 表示周一到周日;每天的任务条数匹配考生课表:有几个空闲窗口就排几条(每窗口至少 1 条),一天通常 2-4 条;没排课的星期允许为空。
3. 单条任务 30-60 分钟,minutes 取 5 的倍数。
4. 同一科目准备 6-10 条互不相同的子任务轮换:标题 = "动作 + 对象 + 验收口径"(如「资料分析限时训练:20 题对 15 题」「申论归纳概括:审题与要点定位方法笔记」),禁止"认真复习""夯实基础"这类不可验收的空话。
5. subject 只能取考生已确认的科目,一字不差。
6. title 不超过 30 字;数量承诺要克制(如"20 题"可以,"做完 5000 题"不行),不得编造教材、页码。
7. 阶段主线与里程碑要体现在变体的递进里:靠前的变体服务主线起步,靠后的变体覆盖里程碑达成。`

function iso(d: Date): string {
  return dayStart(d).toISOString().slice(0, 10)
}

const WEEKDAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** 课表按星期逐行列出:空闲窗口与固定占用同屏,模型才能做到"每个窗口一条任务" */
function buildUserPrompt(input: StageRefineInput): string {
  const { stage, brief, confirmedSubjects, weakSubjects, targetType } = input
  const totalDays = diffDays(stage.endDate, stage.startDate) + 1
  const weeks = Math.max(1, Math.ceil(totalDays / 7))
  const lines = [
    `目标类型:${targetType}`,
    `阶段:${stage.name}`,
    `区间:${iso(stage.startDate)} ~ ${iso(stage.endDate)},共 ${totalDays} 天 ≈ ${weeks} 周(展开时按周循环使用你给出的变体)`,
  ]
  if (stage.strategy) lines.push(`阶段主线(变体递进必须围绕它):${stage.strategy}`)
  if (stage.milestones?.length) lines.push(`阶段里程碑(靠后的变体要覆盖达成):${stage.milestones.join('；')}`)
  lines.push(
    `确认科目(subject 只能用这些,一字不差):${confirmedSubjects.join('、')}`,
    weakSubjects.length ? `薄弱科目(倾斜排课):${weakSubjects.join('、')}` : '',
    '考生课表(任务条数要与每天的空闲窗口匹配):',
  )
  for (let weekday = 1; weekday <= 7; weekday++) {
    const windows = brief.availability
      .filter(day => day.weekday === weekday)
      .flatMap(day => day.windows.map(window => `${window.start}-${window.end}`))
    const commitments = brief.fixedCommitments
      .filter(event => event.weekday === weekday)
      .map(event => `${event.start}-${event.end}${event.label ? ` ${event.label}` : ''}`)
    if (windows.length === 0 && commitments.length === 0) {
      lines.push(`  ${WEEKDAY_NAMES[weekday - 1]}:无安排(这天没确认过空闲)`)
      continue
    }
    lines.push(`  ${WEEKDAY_NAMES[weekday - 1]}:空闲 ${windows.length ? windows.join('、') : '无'};固定占用 ${commitments.length ? commitments.join('、') : '无'}`)
  }
  lines.push('', `请为这个阶段输出 ${Math.min(MAX_VARIANTS, Math.max(4, weeks))} 个周变体。`)
  return lines.join('\n')
}

/** 模型输出 → 可信的周变体:非法星期/未确认科目/空标题逐条丢弃,minutes 收进 [15,120],标题截 64 */
export function normalizeWeekVariants(raw: unknown, confirmedSubjects: string[]): AiWeekVariant[] {
  const allowed = new Set(confirmedSubjects)
  const weeks = Array.isArray((raw as { weeks?: unknown })?.weeks) ? (raw as { weeks: unknown[] }).weeks : []
  const variants: AiWeekVariant[] = []
  for (const week of weeks.slice(0, MAX_VARIANTS)) {
    if (!Array.isArray(week)) continue
    const tasks: AiWeekVariant = []
    for (const task of week) {
      const weekday = Math.round(Number(task?.weekday))
      const subject = String(task?.subject ?? '').trim()
      const title = String(task?.title ?? '').trim()
      if (!(weekday >= 1 && weekday <= 7) || !allowed.has(subject) || !title) continue
      const minutesRaw = Math.round(Number(task?.minutes))
      const minutes = Number.isFinite(minutesRaw) && minutesRaw > 0
        ? Math.max(MIN_TASK_MINUTES, Math.min(MAX_TASK_MINUTES, minutesRaw))
        : DEFAULT_TASK_MINUTES
      tasks.push({ weekday, subject, title: title.slice(0, MAX_TITLE_LENGTH), minutes })
    }
    if (tasks.length > 0) variants.push(tasks)
  }
  return variants
}

export async function refineStageToWeeks(input: StageRefineInput): Promise<AiWeekVariant[]> {
  const content = await chatComplete({
    messages: [
      { role: 'system', content: SYSTEM_PROMPT },
      { role: 'user', content: buildUserPrompt(input) },
    ],
    json: true,
    temperature: 0.6,
    maxTokens: REFINE_MAX_TOKENS,
    timeoutMs: REFINE_TIMEOUT_MS,
  })

  let parsed: unknown
  try {
    parsed = extractJson(content)
  } catch (error) {
    throw new Error(`细化输出无法解析为 JSON:${(error as Error)?.message ?? error}`)
  }
  const variants = normalizeWeekVariants(parsed, input.confirmedSubjects)
  if (variants.length === 0) {
    throw new Error('细化输出清洗后没有任何可用周变体')
  }
  return variants
}
