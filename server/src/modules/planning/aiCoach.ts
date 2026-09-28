import { chatComplete, extractJson, LlmError, type ChatMessage } from '../../shared/llm/client'
import { AiUnavailable } from './aiGenerator'
import { normalizeBrief, briefIsEmpty, type PlanBrief } from './document'
import type { ProfileInput } from './schemas'

/**
 * 「备考面谈」生成器:把「填问卷 → 直接出计划」换成「和 AI 规划师聊几轮 → 出一份真正贴身的计划」。
 *
 * 为什么必须多轮:
 * 一份 400+ 天的全程计划要覆盖目标院校、跨考与否、在职还是全职、每科真实水平、手上有什么资料、
 * 最近一次自测多少分……这些问卷问不出来,一次性让模型猜,出来的就只能是「数学每天 2 小时」这类废话。
 *
 * 接口是无状态的:客户端把整段对话历史带上来,服务端只负责推进一轮。
 * 轮次上限在服务端兜底,避免用户一直聊而导致永远生成不出计划。
 */

/** 至少问够几轮才允许收尾:低于这个数模型就说「够了」,基本是敷衍 */
export const MIN_INTERVIEW_TURNS = 3
/** 轮次上限:到了这里强制收尾,把已有信息整理成简报 */
export const MAX_INTERVIEW_TURNS = 8
const MAX_REPLY_LENGTH = 400
const MAX_OPTIONS = 4
const MAX_INPUT_MESSAGES = 24
const MAX_MESSAGE_LENGTH = 1000

export type InterviewInput = {
  /** 从早到晚的完整对话(不含系统提示);为空表示「刚开始,请提第一个问题」 */
  messages: ChatMessage[]
  /** 已填的问卷档案;没填也要能聊,所以可空 */
  profile: ProfileInput | null
  /** 用户主动点了「直接开始生成」 */
  force?: boolean
}

export type InterviewResult = {
  reply: string
  /** 快捷回答选项:一键点选,降低用户输入成本 */
  options: string[]
  /** 信息已足够,可以生成计划了;客户端据此显示「生成完整计划」按钮 */
  done: boolean
  /** done 为 true 时的考生画像简报 */
  brief: PlanBrief | null
}

const SYSTEM_PROMPT = `你是一位资深的中国考研全程规划师,正在和考生做一对一的「备考面谈」,目标是把一份模板计划变成真正贴合他的全程作战计划。

对话规则:
1. 每轮只问 1–2 个最关键的问题,绝不要一次抛出一串问题清单。
2. 提问时,针对每个问题给 2–4 个「快捷选项」放进 options,每个选项不超过 16 字,让考生能一键作答。
   不提问(收尾)时 options 给空数组。
3. 提问顺序建议:目标院校与专业方向 → 一战/二战/三战、是否跨考、全职还是在职 → 每天可支配时长与作息 →
   各科当前水平与最薄弱的环节 → 已有哪些资料/课程 → 最近一次自测或模考分数 → 复习环境与干扰因素。
   考生已经答过的不要重复问;档案里已有的信息(考试日期、每日时长、薄弱科目等)也不要再问。
4. 先对考生上一句做一句简短的回应或点评(像真人一样接话,可以是提醒、确认或一句专业判断),再提问。
5. 只有在目标、时间、各科基础、短板这四类信息都基本清楚时,才把 done 置为 true;
   或者考生明确表示要开始时也置 true。信息明显不足时不要急着收尾。
6. 如果考生要求直接开始(done 必须为 true),reply 要用一句话说明「信息已足够」,并概括你打算怎么排。

严格只输出一个 JSON 对象,不要任何解释文字、不要 Markdown 代码块:
{
  "reply": "给考生的回应 + 提问(或收尾语)",
  "options": ["快捷选项1", "快捷选项2"],
  "done": false,
  "brief": null
}

当 done 为 true 时,brief 必须给出,形如:
{
  "summary": "一句话考生画像,如「在职二战,目标浙大 408,数学基础薄弱」",
  "goals": ["目标院校与专业", "目标总分或单科分"],
  "constraints": ["在职每周只有晚上 3 小时", "二战,已过一遍数学基础"],
  "focus": ["数学中值定理与级数反复失分", "408 操作系统薄弱"],
  "materials": ["已有王道 408 四本", "计划用张宇 1000 题"],
  "notes": ["周末全天可支配", "易受手机干扰"]
}
brief 里每一句都必须来自考生的真实回答,信息不足的字段就给空字符串或空数组,严禁编造。`

function clip(value: unknown, max: number): string {
  return String(value ?? '').replace(/\s+/g, ' ').trim().slice(0, max)
}

/** 只保留 user/assistant,并裁掉超长与过量的历史(防 prompt 被灌爆) */
function sanitizeMessages(raw: unknown): ChatMessage[] {
  if (!Array.isArray(raw)) return []
  const out: ChatMessage[] = []
  for (const item of raw.slice(-MAX_INPUT_MESSAGES)) {
    const role = (item as ChatMessage)?.role
    const content = String((item as ChatMessage)?.content ?? '').trim()
    if (role !== 'user' && role !== 'assistant') continue
    if (!content) continue
    out.push({ role, content: content.slice(0, MAX_MESSAGE_LENGTH) })
  }
  return out
}

/** 档案里已有的信息不能让 AI 再问一遍 —— 直接写进系统侧上下文 */
function profileContext(profile: ProfileInput | null): string {
  if (!profile) return '考生尚未填写问卷档案(考试日期、每日时长等未知,需要你在对话里问清楚)。'
  const lines = [
    '考生已填写问卷档案,以下信息不用再问:',
    `- 目标类型:${profile.targetType}`,
    `- 考试日期:${profile.examDate.toISOString().slice(0, 10)}`,
    `- 每日可用学习时长:${profile.dailyMinutes} 分钟`,
    `- 固定学习时段:${profile.studyWindows.join('、')}`,
    `- 自评基础:${profile.foundation}`,
    `- 薄弱科目:${profile.weakSubjects.join('、')}`,
  ]
  return lines.join('\n')
}

function transcript(messages: ChatMessage[]): string {
  if (messages.length === 0) {
    return [
      '面谈刚刚开始,考生还没有说话。',
      '请发出开场白:用一句话说明你会先了解他的情况再定计划,然后提出第 1 个问题(目标院校与专业方向),并给 2–4 个快捷选项。',
    ].join('\n')
  }
  return messages
    .map(m => `${m.role === 'user' ? '考生' : '规划师'}:${m.content}`)
    .join('\n')
}

export async function runPlanInterview(input: InterviewInput): Promise<InterviewResult> {
  const messages = sanitizeMessages(input.messages)
  const turns = messages.filter(m => m.role === 'user').length
  const forced = input.force === true
  const mustWrapUp = forced || turns >= MAX_INTERVIEW_TURNS

  const userPrompt = [
    profileContext(input.profile),
    '',
    '对话记录:',
    transcript(messages),
    '',
    `这已经是第 ${turns} 轮考生回答。`,
    mustWrapUp
      ? forced && turns < MIN_INTERVIEW_TURNS
        ? '考生要求立刻开始:即使信息不算充分,也请把已知信息整理成 brief,done 置为 true。'
        : '已经聊得足够久了:请立即收尾,done 置为 true,并把已知信息整理成 brief。'
      : turns < MIN_INTERVIEW_TURNS
        ? `请继续追问,本轮 done 必须为 false(brief 为 null)。至少聊满 ${MIN_INTERVIEW_TURNS} 轮再考虑收尾。`
        : '如果关键信息已经清楚,可以收尾(done 为 true);否则继续追问。',
  ].join('\n')

  let content: string
  try {
    content = await chatComplete({
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'user', content: userPrompt },
      ],
      json: true,
      temperature: 0.6,
    })
  } catch (error) {
    if (error instanceof LlmError) throw new AiUnavailable(error.message)
    throw new AiUnavailable((error as Error)?.message ?? '调用大模型失败')
  }

  let parsed: any
  try {
    parsed = extractJson<any>(content)
  } catch (error) {
    throw new AiUnavailable((error as Error)?.message ?? '模型返回内容无法解析为 JSON')
  }

  const reply = clip(parsed?.reply, MAX_REPLY_LENGTH)
  if (!reply) throw new AiUnavailable('模型没有返回有效的回复内容')

  const optionSource = Array.isArray(parsed?.options) ? parsed.options : []
  const options: string[] = []
  for (const item of optionSource) {
    const text = clip(item, 32)
    if (text && !options.includes(text)) options.push(text)
    if (options.length >= MAX_OPTIONS) break
  }

  // 模型说要收尾、或轮次兜底要求收尾,才认 done;否则即使模型抢跑也不收
  const modelSaysDone = parsed?.done === true
  const done = mustWrapUp || (modelSaysDone && turns >= MIN_INTERVIEW_TURNS)

  if (!done) return { reply, options, done: false, brief: null }

  const brief = normalizeBrief(parsed?.brief)
  // 收尾却没给出任何可用信息时,不要假装成功(除非是强制收尾,那时只能拿现有信息硬上)
  const briefOrNull = briefIsEmpty(brief) && !mustWrapUp ? null : brief
  return {
    reply,
    options: briefOrNull ? [] : options,
    done: briefOrNull !== null,
    brief: briefOrNull,
  }
}
