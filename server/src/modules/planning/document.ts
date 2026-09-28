/**
 * 计划长文档的数据契约(对标「468 天考研全程作战计划」)。
 *
 * 为什么单独定义一层「文档」结构,而不是让客户端直接渲染大段 Markdown:
 * 1. App 端要把它渲染成 Hero + 章节 + 玻璃拟态卡片 + 表格,结构化数据比 Markdown 好落地;
 * 2. 模型分段产出后需要一个收敛层,把超长文本、空块、缺列的行统一裁掉,避免脏数据直接进库;
 * 3. 客户端只认这三种 block,模型换多少轮 prompt 也不会把渲染搞崩。
 *
 * 刻意只保留三种 block(段落 / 卡片组 / 表格组):
 * - 参考文档里的「阶段总览」「军规」「四周启动」都是卡片;
 * - 「日计划表」「资料清单」「初试时间表」都是表格;
 * - 其余是正文段落。
 * 再多一种类型就要在两端各写一套渲染分支,收益不划算。
 */

export interface DocCard {
  /** 卡片左上角的小图标标识(emoji 或短标识),客户端取不到就留空 */
  icon?: string
  title: string
  /** 卡片标题下的一行小字(阶段日期区间、科目定位等) */
  subtitle?: string
  lines: string[]
}

export interface DocTable {
  title: string
  columns: string[]
  /** 每行长度按 columns 对齐;多出来的列会被裁掉,缺失的补空串 */
  rows: string[][]
}

export type DocBlock =
  | { type: 'text'; text: string; emph?: boolean }
  | { type: 'cards'; cards: DocCard[] }
  | { type: 'tables'; tables: DocTable[] }

export interface DocHero {
  badge: string
  titleLead: string
  /** 标题中需要渐变高亮的那一段(如「468」) */
  titleAccent: string
  titleTail: string
  subtitle: string
  /** 顶部科目标签,如 ["408","数学二","英语二","政治"] */
  subjects: string[]
  stats: Array<{ label: string; value: string }>
}

export interface DocChapter {
  /** 章序号,如 "01";客户端用它做章节眉标 */
  no: string
  title: string
  /** 章首导语(可空) */
  intro?: string
  blocks: DocBlock[]
}

export interface PlanDocument {
  title: string
  hero: DocHero
  chapters: DocChapter[]
}

/**
 * 给生成器用的章节骨架:8 章的顺序与标题在这里写死,
 * 模型只负责往每章里填内容,不能让模型自由改章号/章名 —— 否则客户端的分章排版会漂。
 */
export const DOC_CHAPTER_PLAN = [
  { no: '01', title: '起点盘点与目标设定', section: 'target' },
  { no: '02', title: '阶段总览', section: 'phases' },
  { no: '03', title: '各科全程规划', section: 'subjects' },
  { no: '04', title: '每日作息表', section: 'daily' },
  { no: '05', title: '启动四周', section: 'launch' },
  { no: '06', title: '资料清单', section: 'materials' },
  { no: '07', title: '执行军规', section: 'rules' },
  { no: '08', title: '附录:关键时间线', section: 'appendix' },
] as const

export type DocSection = (typeof DOC_CHAPTER_PLAN)[number]['section']

/**
 * AI 面试阶段产出的「考生画像简报」。
 * 它的作用是补上问卷问不到的信息(目标院校、专业课科目、在职与否、已有资料……),
 * 生成长文档和每日清单时都会把它作为上下文喂给模型。
 */
export interface PlanBrief {
  /** 一句话画像,如「在职二战,目标浙大 408,数学基础薄弱」 */
  summary: string
  goals: string[]
  constraints: string[]
  focus: string[]
  materials: string[]
  notes: string[]
}

export const EMPTY_BRIEF: PlanBrief = {
  summary: '',
  goals: [],
  constraints: [],
  focus: [],
  materials: [],
  notes: [],
}

const MAX_BLOCKS_PER_CHAPTER = 8
const MAX_CARDS_PER_BLOCK = 12
const MAX_TABLES_PER_BLOCK = 8
const MAX_ROWS_PER_TABLE = 20
const MAX_COLUMNS = 6
const MAX_CARD_LINES = 8
const MAX_TEXT_LENGTH = 600
const MAX_SHORT = 60
const MAX_CELL = 80

/** 收敛任意输入为字符串:null/undefined → '' */
function str(value: unknown, max = MAX_SHORT): string {
  if (value === null || value === undefined) return ''
  const text = String(value).replace(/\s+/g, ' ').trim()
  return text.length > max ? text.slice(0, max) : text
}

/** 保留换行的长文本(正文段落会带分行要点) */
function longText(value: unknown): string {
  if (value === null || value === undefined) return ''
  return String(value).replace(/\r\n?/g, '\n').trim().slice(0, MAX_TEXT_LENGTH)
}

function strList(value: unknown, max = 12, each = MAX_SHORT): string[] {
  if (!Array.isArray(value)) return []
  const out: string[] = []
  for (const item of value) {
    const text = str(item, each)
    if (text) out.push(text)
    if (out.length >= max) break
  }
  return out
}

function normalizeCard(raw: any): DocCard | null {
  const title = str(raw?.title, MAX_SHORT)
  const lines = strList(raw?.lines, MAX_CARD_LINES, 160)
  if (!title && lines.length === 0) return null
  const icon = str(raw?.icon, 8)
  const subtitle = str(raw?.subtitle, 80)
  return {
    title: title || lines[0] || '',
    ...(icon ? { icon } : {}),
    ...(subtitle ? { subtitle } : {}),
    lines: title ? lines : lines.slice(1),
  }
}

function normalizeTable(raw: any): DocTable | null {
  const columns = strList(raw?.columns, MAX_COLUMNS, MAX_SHORT)
  if (columns.length === 0) return null
  const rows: string[][] = []
  for (const row of Array.isArray(raw?.rows) ? raw.rows : []) {
    const cells = (Array.isArray(row) ? row : []).slice(0, columns.length).map(cell => str(cell, MAX_CELL))
    while (cells.length < columns.length) cells.push('')
    if (cells.some(cell => cell)) rows.push(cells)
    if (rows.length >= MAX_ROWS_PER_TABLE) break
  }
  if (rows.length === 0) return null
  return { title: str(raw?.title, MAX_SHORT * 2), columns, rows }
}

function normalizeBlock(raw: any): DocBlock | null {
  const type = str(raw?.type, 16)
  if (type === 'cards') {
    const cards = (Array.isArray(raw?.cards) ? raw.cards : [])
      .map(normalizeCard)
      .filter((card: DocCard | null): card is DocCard => card !== null)
      .slice(0, MAX_CARDS_PER_BLOCK)
    return cards.length > 0 ? { type: 'cards', cards } : null
  }
  if (type === 'tables') {
    const tables = (Array.isArray(raw?.tables) ? raw.tables : [])
      .map(normalizeTable)
      .filter((table: DocTable | null): table is DocTable => table !== null)
      .slice(0, MAX_TABLES_PER_BLOCK)
    return tables.length > 0 ? { type: 'tables', tables } : null
  }
  // 默认按段落处理:模型偶尔会把 type 写错,只要有文本就救回来
  const text = longText(raw?.text)
  if (!text) return null
  return { type: 'text', text, ...(raw?.emph === true ? { emph: true } : {}) }
}

function normalizeChapter(raw: any, fallbackNo: string, fallbackTitle: string): DocChapter | null {
  const blocks = (Array.isArray(raw?.blocks) ? raw.blocks : [])
    .map(normalizeBlock)
    .filter((block: DocBlock | null): block is DocBlock => block !== null)
    .slice(0, MAX_BLOCKS_PER_CHAPTER)
  const title = str(raw?.title, MAX_SHORT * 2)
  if (blocks.length === 0 && !title) return null
  const intro = longText(raw?.intro)
  return {
    no: str(raw?.no, 4) || fallbackNo,
    title: title || fallbackTitle,
    ...(intro ? { intro } : {}),
    blocks,
  }
}

/**
 * 模型输出的整份文档 → 可信的 PlanDocument。
 * 任何一块不合规都只丢那一条,不整份失败:长文档是加分项,不能因为一处格式问题
 * 把整份计划(含每日清单)拖垮。
 */
export function normalizeDocument(raw: any, fallbackTitle: string): PlanDocument | null {
  const chapters: DocChapter[] = []
  for (const [index, item] of (Array.isArray(raw?.chapters) ? raw.chapters : []).entries()) {
    const plan = DOC_CHAPTER_PLAN[index] ?? DOC_CHAPTER_PLAN[DOC_CHAPTER_PLAN.length - 1]
    const chapter = normalizeChapter(item, plan.no, plan.title)
    if (chapter) chapters.push(chapter)
  }
  if (chapters.length === 0) return null

  const heroRaw = raw?.hero ?? {}
  const hero: DocHero = {
    badge: str(heroRaw.badge, MAX_SHORT) || '全程作战计划',
    titleLead: str(heroRaw.titleLead, MAX_SHORT * 2),
    titleAccent: str(heroRaw.titleAccent, MAX_SHORT),
    titleTail: str(heroRaw.titleTail, MAX_SHORT * 2),
    subtitle: longText(heroRaw.subtitle) || str(heroRaw.subtitle, MAX_TEXT_LENGTH),
    subjects: strList(heroRaw.subjects, 8),
    stats: (Array.isArray(heroRaw.stats) ? heroRaw.stats : [])
      .map((stat: any) => ({ label: str(stat?.label, MAX_SHORT), value: str(stat?.value, MAX_SHORT) }))
      .filter((stat: { label: string; value: string }) => stat.label && stat.value)
      .slice(0, 6),
  }

  return {
    title: str(raw?.title, MAX_SHORT * 2) || fallbackTitle,
    hero,
    chapters,
  }
}

/** 面试简报的收敛:字段全部可选,缺失时返回空数组而不是 null,客户端少一层判空 */
export function normalizeBrief(raw: any): PlanBrief {
  if (!raw || typeof raw !== 'object') return { ...EMPTY_BRIEF }
  return {
    summary: longText(raw.summary),
    goals: strList(raw.goals, 8, 120),
    constraints: strList(raw.constraints, 8, 120),
    focus: strList(raw.focus, 8, 120),
    materials: strList(raw.materials, 12, 120),
    notes: strList(raw.notes, 8, 200),
  }
}

/** 简报是否「有内容」:全空时不值得存库,也不值得拼进 prompt */
export function briefIsEmpty(brief: PlanBrief): boolean {
  return !brief.summary
    && brief.goals.length === 0
    && brief.constraints.length === 0
    && brief.focus.length === 0
    && brief.materials.length === 0
    && brief.notes.length === 0
}

/** 简报 → 拼进 prompt 的文本块 */
export function briefToPrompt(brief: PlanBrief): string {
  const lines: string[] = []
  if (brief.summary) lines.push(`考生画像:${brief.summary}`)
  if (brief.goals.length) lines.push(`目标:${brief.goals.join(';')}`)
  if (brief.constraints.length) lines.push(`客观约束:${brief.constraints.join(';')}`)
  if (brief.focus.length) lines.push(`需要重点倾斜:${brief.focus.join(';')}`)
  if (brief.materials.length) lines.push(`资料情况:${brief.materials.join(';')}`)
  if (brief.notes.length) lines.push(`其它补充:${brief.notes.join(';')}`)
  return lines.join('\n')
}
