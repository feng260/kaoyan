import { chatComplete, extractJson, LlmError } from '../../shared/llm/client'
import type { GeneratedStage } from './generator'
import { dayStart, diffDays, type ProfileInput } from './schemas'
import {
  DOC_CHAPTER_PLAN,
  briefIsEmpty,
  briefToPrompt,
  normalizeDocument,
  type PlanBrief,
  type PlanDocument,
} from './document'

/**
 * 计划长文档生成器 —— 对标「468 天考研全程作战计划」那份 PlanDocument。
 *
 * 分段并发生成的理由:
 * 1. 完整 8 章一次生成必然超过 max_tokens 被截断,截断后 JSON 直接不可解析;
 * 2. 拆成 5 段后每段输出量可控,一段失败只丢那两章,不至于整份文档消失;
 * 3. 各段互不依赖,并发跑,总耗时接近单段耗时。
 *
 * 文档是「加分项」:它失败不能连累每日清单 —— 所以这里任何异常都只 log 并返回 null,
 * 由 service 层决定「没有文档也照样把计划落库」。
 */

export type AiDocumentInput = {
  title: string
  profile: ProfileInput
  brief: PlanBrief | null
  stages: GeneratedStage[]
  startDate: Date
}

/** 少于这个章数就不值得展示:半份计划书比没有更让人困惑 */
const MIN_CHAPTERS = 4
const DOC_TIMEOUT_MS = 180_000

const SYSTEM_PROMPT = `你是一位资深的中国考研全程规划师,正在为考生撰写一份与《468 天考研全程作战计划》同等水准的全程计划书。

写作要求:
- 内容必须具体、可执行、有信息量:写清楚看什么书、练什么题、练到什么程度、怎么检验成果。
  严禁「认真复习」「夯实基础」「注重理解」这类放在任何人身上都成立的空话。
- 语言像真正的作战计划书:简练、有判断、有取舍。不要客套、不要免责声明、不要复述考生已知信息。
- 每一章都要体现考生的真实情况(目标院校与专业、在职与否、薄弱科目、每天可用时间),不要写成通用模板。
- 只输出 JSON 对象,不要 Markdown 代码块,不要 JSON 之外的任何字符。

可用的内容块(blocks)只有三种:
1. {"type":"text","text":"一段正文,可用 \\n 换行分点","emph":true}
   —— 概述性文字;emph 为 true 表示这段需要重点强调(最多 1–2 处)。
2. {"type":"cards","cards":[{"icon":"🎯","title":"卡片标题","subtitle":"补充小字","lines":["要点1","要点2"]}]}
   —— 并列的条目 / 阶段 / 军规;title 必填,lines 1–5 条,icon 用单个 emoji。
3. {"type":"tables","tables":[{"title":"表标题","columns":["列1","列2"],"rows":[["值1","值2"]]}]}
   —— 时间线 / 清单 / 分数目标等结构化数据;每行长度必须与 columns 一致。

输出结构:
{"hero": {...}?, "chapters": [{"no":"01","title":"章节标题","intro":"本章导语(可空)","blocks":[...]}]}
chapters 里每章的 no 与 title 必须原样使用我指定的编号与标题,不得改动或增删章节。`

function iso(d: Date): string {
  return dayStart(d).toISOString().slice(0, 10)
}

/** 所有分段共享的考生上下文:档案 + 面谈画像 + 已定阶段区间 */
function buildContext(input: AiDocumentInput): string {
  const today = dayStart(new Date())
  const exam = dayStart(input.profile.examDate)
  const lines = [
    `计划名称:${input.title}`,
    `目标类型:${input.profile.targetType}`,
    `今天:${iso(today)}`,
    `考试日期:${iso(exam)}(距今 ${diffDays(exam, today)} 天)`,
    `每日可用学习时长:${input.profile.dailyMinutes} 分钟`,
    `固定学习时段:${input.profile.studyWindows.join('、')}`,
    `自评基础:${input.profile.foundation}`,
    `薄弱科目:${input.profile.weakSubjects.join('、')}`,
  ]
  if (input.brief && !briefIsEmpty(input.brief)) {
    lines.push('', '面谈得到的考生画像(必须体现到内容里,不要写与画像矛盾的建议):', briefToPrompt(input.brief))
  }
  lines.push('', '阶段划分(服务端已排定,严禁改动日期与阶段数量):')
  input.stages.forEach((stage, index) => {
    lines.push(`  第 ${index + 1} 阶段:${stage.name},${iso(stage.startDate)} ~ ${iso(stage.endDate)}(共 ${diffDays(stage.endDate, stage.startDate) + 1} 天)`)
  })
  return lines.join('\n')
}

/** 分段定义:key 只用于日志,chapters 是本段负责的章号 */
type Section = { key: string; prompt: string }

function sections(context: string): Section[] {
  return [
    {
      key: 'overview',
      prompt: `请撰写以下两章,并给出文档头 hero。

【第 01 章 起点盘点与目标设定】
- 一个 cards 块,6 张卡片,盘点考生现状:①每天可支配时间与作息 ②各科当前水平 ③最薄弱的环节
  ④学习环境与干扰源 ⑤心态与动力 ⑥可支配的整块时间。每张卡 lines 写具体判断,不要写形容词。
- 一个 tables 块,1 张「目标分数拆解表」:columns = ["科目","满分","目标分","依据与差距"]。
  科目按考生实际考试科目(408 / 数学二 / 英语二 / 政治,或按目标类型调整),"依据与差距"要引用考生自述水平。
- 可选一个 text 块,写 3–5 行总方针。

【第 02 章 阶段总览】
- 一个 cards 块:每一阶段一张卡片(阶段名称与数量必须与上面给出的阶段划分完全一致),
  icon 用阶段序号(1️⃣/2️⃣ 等),title = 阶段名,subtitle = 日期区间与天数,
  lines = 本阶段核心任务、重点突破、可检验的阶段产出。

hero 结构:
{
  "badge": "全程作战计划",
  "titleLead": "标题前半段",
  "titleAccent": "需要渐变高亮的数字,如「446」",
  "titleTail": "标题后半段,如「天考研全程作战计划」",
  "subtitle": "一句话说明这份计划为谁而做、该怎么用",
  "subjects": ["408","数学二","英语二","政治"],
  "stats": [{"label":"备考天数","value":"446 天"}]
}
stats 最多 4 个,数值必须来自上面的真实信息。

只输出:{"hero": {...}, "chapters": [第 01 章, 第 02 章]}`,
    },
    {
      key: 'subjects',
      prompt: `请撰写【第 03 章 各科全程规划】。

- 一个 cards 块:每门考试科目一张卡片,title = 科目名,subtitle = 该科定位与目标分,
  lines = 复习主线、关键动作、检验方式,3–5 条。
  关键动作要具体到「用哪本书、刷哪本题、练到什么程度」(如「王道单科书第 3 章后完成 408 真题 2010–2015 对应大题,正确率 80% 以上才进入下一章」)。
- 一个 tables 块:每门科目一张「全程时间线」表,title = "科目名 · 全程时间线",
  columns = ["阶段","时间","核心任务","单日投入"],行数与本计划的阶段一致(相邻阶段安排相同的可合并)。
- 若考生有明显偏弱的科目,再用一个 text 块(emph=true)写一段针对性提醒。

只输出:{"chapters": [第 03 章]}`,
    },
    {
      key: 'daily',
      prompt: `请撰写【第 04 章 每日作息表】。

- 一个 tables 块,含 2–5 张表,覆盖考生确实会遇到的日程场景。
  参考场景:工作日(晚上一段时间)/ 周末全天 / 假期全天 / 暑期封闭集训 / 冲刺模考期 / 考前一周。
  只写与考生情况匹配的场景:例如在职考生不要写「工作日全天」,而要写「工作日 · 晚上 3 小时」;
  学生党要区分「有课日」和「无课日」。每张表 title = 场景名,
  columns = ["时间","安排","时长","说明"],rows 按一天从早到晚排列。
  每天的合计时长必须与考生「每日可用学习时长」对得上,时段要落在考生选定的固定学习时段里。
- 一个 text 块(3–5 行):这张作息表怎么执行 —— 番茄钟怎么切、碎片时间怎么用、被打断后怎么补。

只输出:{"chapters": [第 04 章]}`,
    },
    {
      key: 'launch',
      prompt: `请撰写【第 05 章 启动四周】和【第 06 章 资料清单】。

【第 05 章 启动四周】
- 一个 cards 块:4 张卡片对应第 1–4 周,title = "第 N 周",subtitle = 该周日期区间,
  lines = 本周目标、每天必须完成的动作、周末要达成的里程碑(3–5 条)。
  第 1 周要写「如何把状态拉起来」这类启动动作(进入状态、建立打卡习惯、摸清各科起点)。

【第 06 章 资料清单】
- 一个 tables 块:按科目分组,每科一张表,title = "科目 · 资料清单",
  columns = ["类别","名称","用途","优先级"],类别取「教材 / 习题 / 真题 / 网课 / 工具」之一。
  名称必须是真实存在的经典资料(如「王道考研 408 四件套」「张宇 1000 题」「肖秀荣 1000 题」「英语二历年真题黄皮书」);
  考生已提到的资料优先列上并标为高优先级,考生没提就按主流推荐,严禁编造不存在的书名。
- 一个 text 块(2–3 行):资料使用原则 —— 什么阶段用哪本、为什么不要贪多。

只输出:{"chapters": [第 05 章, 第 06 章]}`,
    },
    {
      key: 'rules',
      prompt: `请撰写【第 07 章 执行军规】和【第 08 章 附录:关键时间线】。

【第 07 章 执行军规】
- 一个 cards 块,8–10 张卡片,每条军规一张,icon 用 emoji,
  title = 军规本身(不超过 12 字,像口号一样有力,如「先做难的,再做熟的」),
  lines = 这条军规具体怎么执行、违反的代价(1–3 条)。
  军规必须针对考生的真实弱点(如容易刷手机、在职精力不足、对数学畏难、背了就忘)。

【第 08 章 附录:关键时间线】
- 一个 tables 块,含两张表:
  · 「初试时间表」:columns = ["日期","上午","下午"],按真实的考研初试安排填写
    (第一天 8:30–11:30 政治 / 14:00–17:00 英语二,第二天 8:30–11:30 数学二 / 14:00–17:00 专业课;
    以考生目标类型为准),日期用考试日期推算。
  · 「关键日期线」:columns = ["时间点","事项"],9–12 行,覆盖报名、网上确认、准考证打印、初试、
    成绩公布、复试/调剂等节点,时间点用「X 月」或「距考前 N 天」的口径。
- 一个 text 块(3–5 行):收尾寄语,呼应计划开始的那一天,给考生一个具体的行动指令。

只输出:{"chapters": [第 07 章, 第 08 章]}`,
    },
  ]
}

/** 章节号归一:'03' / '3' / '第三章' 都收成 '3' */
function sectionKey(no: unknown): string {
  const digits = String(no ?? '').replace(/\D/g, '')
  return digits ? String(Number(digits)) : ''
}

async function runSection(section: Section, context: string): Promise<any[]> {
  const content = await chatComplete({
    messages: [
      { role: 'system', content: SYSTEM_PROMPT },
      { role: 'user', content: `${context}\n\n${section.prompt}` },
    ],
    json: true,
    temperature: 0.65,
    timeoutMs: DOC_TIMEOUT_MS,
  })
  const parsed = extractJson<any>(content)
  const chapters = Array.isArray(parsed?.chapters) ? parsed.chapters : []
  return chapters.map((chapter: any) => ({ chapter, hero: parsed?.hero }))
}

/**
 * 生成整份计划文档。任何一段失败都只降级(丢掉对应章节),不抛错;
 * 有效章节少于 MIN_CHAPTERS 时返回 null,由 service 层当作「没有文档」处理。
 */
export async function generatePlanDocument(input: AiDocumentInput): Promise<PlanDocument | null> {
  const context = buildContext(input)
  const plan = sections(context)
  const settled = await Promise.allSettled(plan.map(section => runSection(section, context)))

  const chapters: any[] = []
  let hero: any = undefined
  settled.forEach((result, index) => {
    if (result.status === 'rejected') {
      const reason = result.reason instanceof LlmError ? result.reason.message : (result.reason as Error)?.message
      console.warn(`[planning] 计划文档第 ${plan[index].key} 段生成失败:${reason ?? '未知错误'}`)
      return
    }
    for (const item of result.value) {
      if (!hero && item.hero) hero = item.hero
      chapters.push(item.chapter)
    }
  })

  // 按固定章序拼装,顺手去重(模型偶尔会把同一章说两遍)
  const byNo = new Map<string, any>()
  for (const chapter of chapters) {
    const key = sectionKey(chapter?.no)
    if (key && !byNo.has(key)) byNo.set(key, chapter)
  }
  const ordered = DOC_CHAPTER_PLAN
    .map(item => byNo.get(sectionKey(item.no)))
    .filter((chapter): chapter is any => Boolean(chapter))

  if (ordered.length < MIN_CHAPTERS) {
    console.warn(`[planning] 计划文档有效章节只有 ${ordered.length} 章,放弃保存`)
    return null
  }

  const document = normalizeDocument({ title: input.title, hero, chapters: ordered }, input.title)
  return document
}
