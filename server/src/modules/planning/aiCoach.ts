import { chatComplete, extractJson, LlmError, type ChatMessage } from '../../shared/llm/client'
import { AiUnavailable } from './aiGenerator'
import { mergeBrief, normalizeBrief, assessPlanningFacts, type PlanBrief } from './document'
import { dayStart, type ProfileInput } from './schemas'

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
/**
 * 回复字数硬上限。客户端靠它把「一句接话」和「本轮唯一的问题」分开高亮,
 * 所以这里卡得很死:AI 说得越长,考生越抓不住重点。
 */
const MAX_REPLY_LENGTH = 180
const MAX_OPTIONS = 4
const MAX_OPTION_LENGTH = 14
const MAX_INPUT_MESSAGES = 24
const MAX_MESSAGE_LENGTH = 1000
/** 面谈输出很短(一个 JSON 对象),显式压小 max_tokens 可以规避部分厂商的上限校验(400) */
const INTERVIEW_MAX_TOKENS = 1600

export type InterviewInput = {
  /** 从早到晚的完整对话(不含系统提示);为空表示「刚开始,请提第一个问题」 */
  messages: ChatMessage[]
  /** 已填的问卷档案;没填也要能聊,所以可空 */
  profile: ProfileInput | null
  /** 问卷/课表已经确认的事实(正式科目、真实空闲、固定占用);这些绝不能再问一遍 */
  brief?: PlanBrief | null
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
1. 每轮只问 1 个最关键的问题,绝不要一次抛出多个问题,更不要输出问题清单。
2. reply 必须极简,用换行分成最多两段:
   第一段:针对考生上一句的一句接话(认可、确认或一句专业判断),不超过 30 字,可以省略;
   第二段:本轮唯一的问题,不超过 60 字,只问一件事。
   整段 reply 不超过 100 字;禁止序号、项目符号、Markdown 和括号清单;不要复述档案里或上一轮已经确认过的信息。
3. options 必须能直接回答「第二段刚提出的那个问题」:2–4 个,每个不超过 14 字,彼此互斥并覆盖最常见的答案。
   不要给出与问题无关、或还需要考生再解释一遍的选项。不提问(收尾)时 options 给空数组。
4. 提问顺序建议:目标院校与专业方向 → 一战/二战/三战、是否跨考、全职还是在职 →
   各科当前水平与最薄弱的环节 → 已有哪些资料/课程 → 最近一次自测或模考分数 → 复习环境与干扰因素。
   考生已经答过的不要重复问;档案里已有的信息(考试日期、每日时长、薄弱科目、正式科目、空闲时段、固定占用)也不要再问。
   若上面「考生已填写问卷档案」里已经确认了正式科目/空闲时段/固定占用,你只需要补齐每一科的:
   当前进度、已知考试范围(自命题范围不明就留空)、剩余任务分钟数、里程碑及其截止日期与目标分钟数。
5. 需要逐步确认的事实:正式考试科目名称;每门的当前进度/已知范围/剩余任务分钟数/里程碑及其明确截止日期(YYYY-MM-DD)和目标分钟数;按星期与起止时间记录的真实空闲时段和固定占用(没有固定占用也须明确确认)。已经聊清楚的不要重复问,直接进入下一项。
6. 缺少上述事实时不要声称信息足够;但要用自然对话的方式补齐,不要输出核对清单。自命题范围不明时标为空,不得补写章节。

严格只输出一个 JSON 对象,不要任何解释文字、不要 Markdown 代码块:
{
  "reply": "第一段接话(可省略)\\n第二段本轮唯一的问题",
  "options": ["快捷选项1", "快捷选项2"],
  "done": false,
  "brief": { 把「到目前为止已经确认的事实」全部写进来 }
}

收尾时(done 为 true)reply 只用一句不超过 60 字的话说明可以生成计划了,不要再提问,options 给空数组。

关键:无论 done 是 true 还是 false,brief 都必须给出,并且要累积 —— 每轮把考生已经确认的事实合并进去,
不要因为还没聊完就写 null,也不要丢掉前面几轮已经确认的内容。

brief 形如:{
  "summary": "一句话考生画像,如「在职二战,目标浙大 408,数学基础薄弱」",
  "goals": ["目标院校与专业", "目标总分或单科分"],
  "constraints": ["在职每周只有晚上 3 小时", "二战,已过一遍数学基础"],
  "focus": ["数学中值定理与级数反复失分", "408 操作系统薄弱"],
  "materials": ["已有王道 408 四本", "计划用张宇 1000 题"],
  "notes": ["周末全天可支配", "易受手机干扰"],
  "examSubjects": [{"name":"考生确认的正式科目","progress":"当前具体进度","scope":"已知考试范围,未知留空","remainingMinutes":120,"milestone":"待完成的具体里程碑","milestoneDate":"YYYY-MM-DD","milestoneMinutes":60}],
  "availability": [{"weekday":1,"windows":[{"start":"19:00","end":"21:00"}]}],
  "fixedCommitments": [{"weekday":1,"start":"19:00","end":"20:00","label":"固定工作"}],
  "availabilityConfirmed": true,
  "commitmentsConfirmed": true
}
brief 里的科目、进度、范围、时间和固定占用必须来自考生明确确认,未确认时标 false 或留空,严禁编造。`

function clip(value: unknown, max: number): string {
  return String(value ?? '').replace(/\s+/g, ' ').trim().slice(0, max)
}

/**
 * 回复专用裁剪:保留换行,让客户端能把「接话」和「本轮唯一的问题」分成两块来高亮。
 * 与 clip() 一样会压掉多余空格并丢掉空行。
 */
function clipReply(value: unknown, max: number): string {
  return String(value ?? '')
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .map(line => line.replace(/[ \t]+/g, ' ').trim())
    .filter(Boolean)
    .join('\n')
    .slice(0, max)
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
function profileContext(profile: ProfileInput | null, brief: PlanBrief | null): string {
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
  // 问卷和课表已经确认过的事实写清楚,面谈就不必再逐项追问,只补「逐科的具体量」
  if (brief && brief.examSubjects.length) {
    lines.push(`- 正式考试科目(已确认,不要再问有哪些科):${brief.examSubjects.map(s => s.name).join('、')}`)
  }
  if (brief && brief.availabilityConfirmed && brief.availability.length) {
    lines.push(`- 真实空闲时段(已确认,不要再问作息):${JSON.stringify(brief.availability)}`)
  }
  if (brief && brief.commitmentsConfirmed) {
    lines.push(`- 固定占用(已确认,空数组表示考生确认没有):${JSON.stringify(brief.fixedCommitments)}`)
  }
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
  const wantsWrapUp = forced || turns >= MAX_INTERVIEW_TURNS

  // 问卷/课表已经确认的事实是「底稿」:模型每轮只补它认出来的部分,由这里负责合并,
  // 避免某一轮模型少写一个字段就把上一轮确认过的科目或空闲时间弄丢。
  const knownBrief = input.brief ?? null
  const hasKnownFacts = knownBrief !== null && (knownBrief.examSubjects.length > 0
    || (knownBrief.availabilityConfirmed && knownBrief.availability.length > 0)
    || knownBrief.commitmentsConfirmed)

  const userPrompt = [
    profileContext(input.profile, knownBrief),
    '',
    '对话记录:',
    transcript(messages),
    '',
    `这已经是第 ${turns} 轮考生回答。`,
    wantsWrapUp
      ? '请把已确认的事实汇总进 brief;若还有必要事实没问到,用自然的问句继续补,done 保持 false(不要输出核对清单)。'
      : turns < MIN_INTERVIEW_TURNS
        ? `请继续追问,至少聊满 ${MIN_INTERVIEW_TURNS} 轮再考虑收尾,并把已确认的事实持续写进 brief。`
        : hasKnownFacts
          ? '科目、空闲时段和固定占用已在问卷里确认过,不要再问这些;只问题目上还缺的逐科细节(进度、范围、剩余任务量与里程碑),补齐后即可收尾。'
          : '仅当正式科目、逐科进度、空闲时段和固定占用都聊清楚后才可收尾;差哪项就自然地接着问哪项。',
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
      maxTokens: INTERVIEW_MAX_TOKENS,
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

  const reply = clipReply(parsed?.reply, MAX_REPLY_LENGTH)
  if (!reply) throw new AiUnavailable('模型没有返回有效的回复内容')

  const optionSource = Array.isArray(parsed?.options) ? parsed.options : []
  const options: string[] = []
  for (const item of optionSource) {
    const text = clip(item, MAX_OPTION_LENGTH)
    if (text && !options.includes(text)) options.push(text)
    if (options.length >= MAX_OPTIONS) break
  }

  // 问卷/课表确认过的事实是底稿:模型每轮只补它认出来的部分,合并后不会丢上一轮确认过的内容
  const modelBrief = normalizeBrief(parsed?.brief)
  const brief = knownBrief ? mergeBrief(knownBrief, modelBrief) : modelBrief
  const facts = assessPlanningFacts(brief, dayStart(new Date()), input.profile?.examDate ?? dayStart(new Date()),
    input.profile?.dailyMinutes ?? Infinity)
  const modelSaysDone = parsed?.done === true || wantsWrapUp
  const done = modelSaysDone && facts.ready && (turns >= MIN_INTERVIEW_TURNS || forced)
  if (done) return { reply, options: [], done: true, brief }

  // 保留模型自己的话 —— 这是「像真人」的关键,绝不能用模板句把它顶掉。
  // 只有当模型自认为可以收尾、但必要事实其实还没齐时,才在末尾附一句「还差什么」的提示,
  // 让考生知道卡在哪,同时模型的自然表达仍然完整保留。
  let finalReply = reply
  if (modelSaysDone && !facts.ready) {
    const hint = facts.missing.length
      ? `还差这几项:${facts.missing.join('、')}`
      : facts.deficits.length
        ? `现在的空闲时间还盖不住:${facts.deficits.map(d => `${d.subject} ${d.milestone}缺${d.missingMinutes}分钟`).join('、')}`
        : ''
    if (hint) finalReply = clipReply(`${reply}\n${hint}`, MAX_REPLY_LENGTH)
  }
  return { reply: finalReply, options, done: false, brief }
}
