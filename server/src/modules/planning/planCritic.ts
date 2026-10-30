import { chatComplete, extractJson, LlmError } from '../../shared/llm/client'
import type { GeneratedPlan } from './generator'
import type { PlanSpec, SpecVerification } from './planSpec'
import { diffDays } from './schemas'

/**
 * 计划评审(critic):确定性校验器只能判断「容量、覆盖、重复、日期」这类硬指标,
 * 判断不了「这个阶段的安排有没有真正的教学逻辑」。这一层用一次独立调用做代码评审式的复核:
 *
 * 输入是 spec 的结构化摘要 + 编译器实际排出的任务样本 + 校验器发现的问题;
 * 输出是分数、是否通过,以及**按阶段定位**的修改建议。生成器只拿这些建议重跑
 * 出问题的阶段,不整份重掷 —— 这是把「一次性生成」变成「可以收敛的循环」的关键一步。
 *
 * 评审失败(网络/JSON)一律返回 null:它只是质量加成,不能连累主计划。
 */

export type CriticIssue = {
  /** 1-based 阶段序号;缺省表示全局问题 */
  stage?: number
  subject?: string
  code: string
  fix: string
}

export type CriticVerdict = {
  score: number
  approved: boolean
  summary: string
  issues: CriticIssue[]
}

export type CriticInput = {
  spec: PlanSpec
  plan: GeneratedPlan
  verification: SpecVerification
  /** 考生确认过的范围,评审不得建议超出 */
  confirmedSubjects: string[]
}

const CRITIC_MAX_TOKENS = 2048
const CRITIC_TIMEOUT_MS = 90_000

const SYSTEM_PROMPT = `你是一位极其挑剔的备考计划评审员,负责在计划交付给考生之前做最后一道代码评审式复核。
你只根据给定的事实评分,不猜测、不补充考生未确认的信息。

评分维度(每项都要看):
1. 阶段逻辑:阶段比例是否符合备考规律,主线是否说清了「这个阶段成什么事」;
2. 容量:每日/每周任务量是否落在真实空闲内,是否把一天压成一条大任务或排得过满;
3. 科目覆盖:确认科目是否都在推进,薄弱科目是否得到倾斜,政治/英语等是否在合适阶段投入;
4. 递进与去重:相邻周是否真正在推进(新知识点/新题型/更高难度),有没有原样重放;
5. 任务质量:标题是否「动作+对象+验收口径」,能否验收,是否在 30–60 分钟量级;
6. 里程碑可行性:阶段里程碑与逐科截止日是否被前面的安排支撑;
7. 事实边界:是否出现考生未确认的科目、教材、章节或分数承诺。

硬性:凡是校验器标为「[必须修]」的问题,approved 必须为 false。

只输出 JSON,不要解释、不要 Markdown:
{
  "score": 0-100 的整数,
  "approved": true/false,
  "summary": "一句话总评,不超过 80 字",
  "issues": [
    { "stage": 1, "subject": "科目名(可省略)", "code": "SHORT_CODE", "fix": "具体到某一周该怎么改,不超过 120 字" }
  ]
}
issues 最多 6 条,只列真正影响执行的问题;没有问题时给空数组。stage 用 1-based 序号。`

function isoDate(value: Date): string {
  return new Date(value.getTime()).toISOString().slice(0, 10)
}

function stageStats(input: CriticInput): string[] {
  const lines: string[] = []
  const byStageSubject = new Map<string, number>()
  for (const item of input.plan.items) {
    const key = `${item.stageOrder}|${item.subject}`
    byStageSubject.set(key, (byStageSubject.get(key) ?? 0) + item.minutes)
  }
  for (const [index, stage] of input.spec.stages.entries()) {
    const days = diffDays(new Date(`${stage.endDate}T00:00:00.000Z`), new Date(`${stage.startDate}T00:00:00.000Z`)) + 1
    const actual = input.spec.subjects
      .map(subject => `${subject.name} ${byStageSubject.get(`${stage.sortOrder}|${subject.name}`) ?? 0}m`)
      .join('、')
    lines.push(`第 ${index + 1} 阶段「${stage.name}」${stage.startDate}~${stage.endDate}(${days} 天,${stage.weeks.length} 周,${stage.phase})`)
    lines.push(`  主线:${stage.strategy}`)
    if (stage.milestones.length) lines.push(`  里程碑:${stage.milestones.join('；')}`)
    lines.push(`  实际各科分钟:${actual}`)
    const mix = stage.subjectMix.map(item => `${item.subject} ${Math.round(item.share * 100)}%`).join('、')
    lines.push(`  目标占比:${mix}`)
    const samples = [stage.weeks[0], stage.weeks[Math.floor(stage.weeks.length / 2)], stage.weeks[stage.weeks.length - 1]]
      .filter((week, position, list) => week && list.findIndex(item => item?.index === week.index) === position)
    for (const week of samples) {
      const tasks = week.tasks.slice(0, 6).map(task => `周${task.weekday} ${task.subject}·${task.title}(${task.minutes}m)`).join(' / ')
      lines.push(`  第 ${week.index + 1} 周样本:${tasks || '(空)'}`)
    }
  }
  return lines
}

function buildPrompt(input: CriticInput): string {
  const { spec, verification } = input
  const totalDays = diffDays(new Date(`${spec.examDate}T00:00:00.000Z`), new Date(`${spec.startDate}T00:00:00.000Z`))
  const lines = [
    `计划:${spec.title}`,
    `目标类型:${spec.targetType};备考 ${totalDays} 天(${spec.startDate} ~ ${spec.examDate})`,
    `确认科目:${input.confirmedSubjects.join('、')}`,
    `逐科事实:${JSON.stringify(spec.subjects.map(subject => ({
      name: subject.name, weak: subject.weak, remainingMinutes: subject.remainingMinutes,
      milestone: subject.milestone, milestoneDate: subject.milestoneDate, estimated: subject.estimated,
    })))}`,
    '',
    '阶段与样本:',
    ...stageStats(input),
    '',
    verification.issues.length
      ? `确定性校验发现的问题:\n${verification.issues.slice(0, 12).map(issue => `${issue.severity === 'error' ? '[必须修]' : '[建议]'} ${issue.message} -> ${issue.hint}`).join('\n')}`
      : '确定性校验未发现问题。',
    '',
    '请按评审维度给出结论。',
  ]
  return lines.join('\n')
}

function normalizeVerdict(raw: any): CriticVerdict | null {
  if (!raw || typeof raw !== 'object') return null
  const score = Math.max(0, Math.min(100, Math.round(Number(raw.score))))
  if (!Number.isFinite(score)) return null
  const issues: CriticIssue[] = []
  for (const item of Array.isArray(raw.issues) ? raw.issues : []) {
    const fix = String(item?.fix ?? '').replace(/\s+/g, ' ').trim().slice(0, 160)
    if (!fix) continue
    const stage = Number(item?.stage)
    issues.push({
      ...(Number.isInteger(stage) && stage > 0 ? { stage } : {}),
      ...(item?.subject ? { subject: String(item.subject).trim().slice(0, 24) } : {}),
      code: String(item?.code ?? 'CRITIC').replace(/\s+/g, '_').slice(0, 24),
      fix,
    })
    if (issues.length >= 6) break
  }
  return {
    score,
    approved: raw.approved === true && issues.length === 0,
    summary: String(raw.summary ?? '').replace(/\s+/g, ' ').trim().slice(0, 120),
    issues,
  }
}

export async function critiquePlan(input: CriticInput): Promise<CriticVerdict | null> {
  try {
    const content = await chatComplete({
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'user', content: buildPrompt(input) },
      ],
      json: true,
      temperature: 0.2,
      maxTokens: CRITIC_MAX_TOKENS,
      timeoutMs: CRITIC_TIMEOUT_MS,
    })
    return normalizeVerdict(extractJson<any>(content))
  } catch (error) {
    const reason = error instanceof LlmError ? error.message : (error as Error)?.message
    console.warn('[planning] 计划评审失败,跳过:', reason ?? '未知错误')
    return null
  }
}

export { isoDate }
