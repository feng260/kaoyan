import { compilePlanSpec, type PlanSpec } from './planSpec'
import type { DocBlock, DocCard, DocChapter, DocHero, DocTable, PlanDocument } from './document'
import { dayTemplateFor, playbookForSubject } from './playbooks'
import { diffDays } from './schemas'

/**
 * 从 PlanSpec 派生计划文档的「骨架章节」。
 *
 * 文档过去是另一次独立生成:模型没看到最终展开的逐日清单,阶段日期、各科时间线、
 * 每日作息表都可能与 App 里真正执行的任务不一致。这里把**可计算的部分**全部改为
 * 从 spec 派生 —— 阶段日期、各科投入分钟、首周安排、每日模板 —— 模型只保留
 * 01/05/06/07/08 这类需要判断与文风的章节。
 *
 * 结果是:即使 5 段文档生成全部失败,只要 spec 在,也能得到一份数字自洽的 8 章计划书。
 */

const DAY_MS = 24 * 3600_000
const MAX_ROWS = 20
const MAX_CELL = 80

function iso(date: Date): string {
  return date.toISOString().slice(0, 10)
}

function fromIso(value: string): Date {
  return new Date(`${value}T00:00:00.000Z`)
}

function addDays(date: Date, days: number): Date {
  return new Date(date.getTime() + days * DAY_MS)
}

function cell(value: unknown): string {
  return String(value ?? '').replace(/\s+/g, ' ').trim().slice(0, MAX_CELL)
}

function table(title: string, columns: string[], rows: unknown[][]): DocTable | null {
  const cleanRows = rows
    .map(row => row.slice(0, columns.length).map(cell))
    .filter(row => row.some(Boolean))
    .slice(0, MAX_ROWS)
  return cleanRows.length ? { title, columns, rows: cleanRows } : null
}

function text(value: string, emph = false): DocBlock {
  return { type: 'text', text: value.slice(0, 600), ...(emph ? { emph: true } : {}) }
}

function cards(items: DocCard[]): DocBlock | null {
  return items.length ? { type: 'cards', cards: items.slice(0, 12) } : null
}

function blocksOf(...items: Array<DocBlock | null>): DocBlock[] {
  return items.filter((item): item is DocBlock => item !== null)
}

/** 每个阶段每科实际排入的分钟数:文档里的表格与每日清单共用同一份编译结果 */
function stageSubjectMinutes(spec: PlanSpec): Map<number, Map<string, number>> {
  const plan = compilePlanSpec(spec)
  const out = new Map<number, Map<string, number>>()
  for (const item of plan.items) {
    const stage = out.get(item.stageOrder) ?? new Map<string, number>()
    stage.set(item.subject, (stage.get(item.subject) ?? 0) + item.minutes)
    out.set(item.stageOrder, stage)
  }
  return out
}

function weekdayLabel(weekday: number): string {
  return ['周一', '周二', '周三', '周四', '周五', '周六', '周日'][weekday - 1] ?? ''
}

function stageDays(spec: PlanSpec, index: number): number {
  return diffDays(fromIso(spec.stages[index].endDate), fromIso(spec.stages[index].startDate)) + 1
}

function buildHero(spec: PlanSpec, title: string, fallback?: DocHero | null): DocHero {
  const totalDays = diffDays(fromIso(spec.examDate), fromIso(spec.startDate))
  const plan = compilePlanSpec(spec)
  const totalMinutes = plan.items.reduce((sum, item) => sum + item.minutes, 0)
  const weeks = Math.max(1, Math.round(totalDays / 7))
  return {
    badge: fallback?.badge || '全程作战计划',
    titleLead: fallback?.titleLead || title.slice(0, 12),
    titleAccent: fallback?.titleAccent || String(totalDays),
    titleTail: fallback?.titleTail || `天${spec.targetType}全程作战计划`,
    subtitle: fallback?.subtitle || `为 ${spec.subjects.map(subject => subject.name).join('、')} 定制的全程安排,按周推进、按日执行`,
    subjects: spec.subjects.map(subject => subject.name),
    stats: [
      { label: '备考天数', value: `${totalDays} 天` },
      { label: '阶段', value: `${spec.stages.length} 个` },
      { label: '科目', value: `${spec.subjects.length} 门` },
      { label: '周均投入', value: `${Math.round(totalMinutes / weeks / 60)} 小时` },
    ],
  }
}

function chapter01(spec: PlanSpec): DocChapter {
  const totalDays = diffDays(fromIso(spec.examDate), fromIso(spec.startDate))
  const weeklyCapacity = Array.from({ length: 7 }, (_, index) => {
    const weekday = index + 1
    const windows = spec.brief.availability.filter(day => day.weekday === weekday).flatMap(day => day.windows)
    const minutes = windows.reduce((sum, window) => {
      const [sh, sm] = window.start.split(':').map(Number)
      const [eh, em] = window.end.split(':').map(Number)
      return sum + (eh * 60 + em) - (sh * 60 + sm)
    }, 0)
    return { weekday, minutes, windows }
  })
  const weeklyHours = Math.round(weeklyCapacity.reduce((sum, day) => sum + day.minutes, 0) / 60)
  const weak = spec.subjects.filter(subject => subject.weak).map(subject => subject.name)
  const summaryCards: DocCard[] = [
    {
      icon: '📅', title: '目标与考期', subtitle: `${spec.startDate} ~ ${spec.examDate}`,
      lines: [`目标类型:${spec.targetType}`, `今天距考试 ${totalDays} 天`, `计划覆盖考前一天,共 ${totalDays} 天`],
    },
    {
      icon: '⏱️', title: '可投入时间', subtitle: `每周约 ${weeklyHours} 小时`,
      lines: weeklyCapacity.filter(day => day.minutes > 0)
        .map(day => `${weekdayLabel(day.weekday)}:${day.windows.map(window => `${window.start}-${window.end}`).join('、')}(${Math.round(day.minutes / 60 * 10) / 10}h)`)
        .slice(0, 5),
    },
    {
      icon: '📚', title: '科目与薄弱项', subtitle: spec.subjects.map(subject => subject.name).join('、'),
      lines: [
        ...spec.subjects.map(subject => `${subject.name}:${subject.estimated ? '逐科细节为估计值,可改' : subject.progress || '按计划推进'}`),
        weak.length ? `需要倾斜:${weak.join('、')}` : '没有标记薄弱科目,按常规配额推进',
      ].slice(0, 6),
    },
    {
      icon: '🧰', title: '已有资料', subtitle: spec.brief.materials.length ? `${spec.brief.materials.length} 项` : '待确认',
      lines: (spec.brief.materials.length ? spec.brief.materials : ['考生尚未确认资料清单,先用已确认的教材与真题']).slice(0, 5),
    },
    {
      icon: '🧭', title: '作息与区间', subtitle: (spec.brief.periods ?? []).length ? `${(spec.brief.periods ?? []).length} 段特殊作息` : '按平时课表',
      lines: (spec.brief.periods ?? []).map(period => `${period.start}~${period.end} ${period.note || period.kind}`).slice(0, 4),
    },
    {
      icon: '🎯', title: '执行原则', subtitle: '按周推进,按日验收',
      lines: spec.rules.slice(0, 4),
    },
  ]
  const scoreTable = table('目标分数拆解表', ['科目', '满分', '目标分', '依据与差距'],
    spec.subjects.map(subject => [subject.name, '', '', subject.estimated ? '逐科细节为估计值' : subject.progress || '待自测后填写']))
  return {
    no: '01',
    title: '起点盘点与目标设定',
    intro: spec.brief.summary || `这份计划从 ${spec.startDate} 开始,到考前一天结束。`,
    blocks: blocksOf(cards(summaryCards), scoreTable ? { type: 'tables', tables: [scoreTable] } : null,
      text(`总方针:先按阶段把地基、题型、真题三步走完,任何阶段都不追求「排满」,而是追求当天的任务能验收。`, true)),
  }
}

function chapter02(spec: PlanSpec): DocChapter {
  const minutes = stageSubjectMinutes(spec)
  const stageCards: DocCard[] = spec.stages.map((stage, index) => {
    const total = [...(minutes.get(stage.sortOrder)?.values() ?? [])].reduce((sum, value) => sum + value, 0)
    return {
      icon: ['1️⃣', '2️⃣', '3️⃣', '4️⃣', '5️⃣', '6️⃣'][index] ?? '▪️',
      title: stage.name,
      subtitle: `${stage.startDate} ~ ${stage.endDate}(共 ${stageDays(spec, index)} 天)`,
      lines: [
        `主线:${stage.strategy}`,
        ...stage.milestones.slice(0, 2).map(milestone => `里程碑:${milestone}`),
        `计划投入约 ${Math.round(total / 60)} 小时,${stage.weeks.length} 周`,
      ].slice(0, 5),
    }
  })
  const columns = ['阶段', '时间', '周数', ...spec.subjects.map(subject => subject.name), '合计']
  const rows = spec.stages.map((stage, index) => {
    const per = minutes.get(stage.sortOrder) ?? new Map<string, number>()
    const values = spec.subjects.map(subject => String(per.get(subject.name) ?? 0))
    return [stage.name, `${stage.startDate}~${stage.endDate}`, String(stage.weeks.length),
      ...values, String(values.reduce((sum, value) => sum + Number(value), 0))]
  })
  return {
    no: '02',
    title: '阶段总览',
    intro: `全程拆成 ${spec.stages.length} 个阶段,阶段首尾相接,每阶段都有可验收的里程碑。`,
    blocks: blocksOf(cards(stageCards), table('各阶段科目投入(计划分钟)', columns, rows) ? {
      type: 'tables', tables: [table('各阶段科目投入(计划分钟)', columns, rows)!],
    } : null),
  }
}

function chapter03(spec: PlanSpec): DocChapter {
  const minutes = stageSubjectMinutes(spec)
  const plan = compilePlanSpec(spec)
  const subjectCards: DocCard[] = spec.subjects.map(subject => {
    const playbook = playbookForSubject(subject.name)
    const total = plan.items.filter(item => item.subject === subject.name).reduce((sum, item) => sum + item.minutes, 0)
    return {
      icon: subject.weak ? '⚠️' : '📘',
      title: subject.name,
      subtitle: `${subject.weak ? '薄弱科目 · ' : ''}全程 ${Math.round(total / 60)} 小时`,
      lines: [
        playbook.phases.foundation.focus,
        playbook.phases.strengthen.focus,
        playbook.phases.sprint.focus,
        subject.milestone ? `里程碑:${subject.milestone}(${subject.milestoneDate})` : '里程碑:按周推进,阶段末验收',
      ].slice(0, 5),
    }
  })
  const tables: DocTable[] = []
  for (const subject of spec.subjects) {
    const playbook = playbookForSubject(subject.name)
    const rows = spec.stages.map((stage, index) => {
      const per = minutes.get(stage.sortOrder)?.get(subject.name) ?? 0
      const days = stageDays(spec, index)
      const avg = Math.round(per / Math.max(1, days))
      const phase = stage.phase
      return [stage.name, `${stage.startDate}~${stage.endDate}`,
        playbook.phases[phase].focus, `${avg} 分钟/天`]
    })
    const built = table(`${subject.name} · 全程时间线`, ['阶段', '时间', '核心任务', '单日投入'], rows)
    if (built) tables.push(built)
  }
  const weak = spec.subjects.filter(subject => subject.weak)
  return {
    no: '03',
    title: '各科全程规划',
    intro: '每门科目的投入与阶段主线由计划本身派生,不是另写一份建议。',
    blocks: blocksOf(cards(subjectCards), tables.length ? { type: 'tables', tables: tables.slice(0, 8) } : null,
      weak.length ? text(`重点提醒:${weak.map(subject => subject.name).join('、')} 标记为薄弱科目,计划已上浮投入;状态差的日子优先保住这几科的第一块任务。`, true) : null),
  }
}

/** 找到第一个容量落在模板区间内的真实日期,用它渲染「典型日」 */
function sampleDayForTemplate(spec: PlanSpec, minMinutes: number, maxMinutes: number): string | null {
  const plan = compilePlanSpec(spec)
  const days = new Set(plan.items.map(item => iso(item.planDate)))
  for (const day of days) {
    const date = fromIso(day)
    const capacity = capacityOf(spec, date)
    if (capacity >= minMinutes && capacity <= maxMinutes) return day
  }
  return null
}

function capacityOf(spec: PlanSpec, date: Date): number {
  const weekday = date.getUTCDay() || 7
  const period = (spec.brief.periods ?? []).find(item => item.start <= iso(date) && iso(date) <= item.end)
  const windows = (period?.availability ?? []).filter(item => item.weekday === weekday).flatMap(item => item.windows)
  const fallback = spec.brief.availability.filter(item => item.weekday === weekday).flatMap(item => item.windows)
  const chosen = windows.length ? windows : fallback
  const commitments = period && (period.kind === 'vacation' || period.kind === 'summer')
    ? period.fixedCommitments.filter(item => item.weekday === weekday)
    : [...spec.brief.fixedCommitments, ...(period?.fixedCommitments ?? [])].filter(item => item.weekday === weekday)
  const toMinutes = (value: string) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3))
  const boundaries = [...new Set([
    ...chosen.flatMap(window => [toMinutes(window.start), toMinutes(window.end)]),
    ...commitments.flatMap(item => [toMinutes(item.start), toMinutes(item.end)]),
  ])].sort((a, b) => a - b)
  let total = 0
  for (let index = 1; index < boundaries.length; index++) {
    const from = boundaries[index - 1]
    const to = boundaries[index]
    const inside = chosen.some(window => toMinutes(window.start) <= from && toMinutes(window.end) >= to)
    const blocked = commitments.some(item => toMinutes(item.start) <= from && toMinutes(item.end) >= to)
    if (inside && !blocked) total += to - from
  }
  return total
}

function chapter04(spec: PlanSpec): DocChapter {
  const plan = compilePlanSpec(spec)
  const firstDay = spec.startDate
  const firstWeekEnd = iso(addDays(fromIso(firstDay), 6))
  const firstWeek = plan.items.filter(item => iso(item.planDate) >= firstDay && iso(item.planDate) <= firstWeekEnd)
  const weekTable = table('首周实际安排(与每日清单一致)', ['日期', '开始', '时段', '科目', '任务', '分钟'],
    firstWeek.map(item => [iso(item.planDate), item.startTime ?? '', item.slot ?? '', item.subject, item.title, String(item.minutes)]))

  const templateTables: DocTable[] = []
  const seen = new Set<string>()
  for (const template of spec.dayTemplates) {
    if (seen.has(template.id)) continue
    seen.add(template.id)
    const day = sampleDayForTemplate(spec, template.minMinutes, template.maxMinutes)
    if (!day) continue
    const items = plan.items.filter(item => iso(item.planDate) === day)
    if (items.length === 0) continue
    const built = table(`${template.name}:${template.scene}`, ['时间', '安排', '时长', '说明'],
      items.map(item => [`${item.startTime ?? ''}`, `${item.subject} · ${item.title}`, `${item.minutes} 分钟`, template.blocks.find(block => block.kind === (item.slot === 'morning' ? 'deep' : 'practice'))?.label ?? '按当天状态微调']))
    if (built) templateTables.push(built)
  }

  return {
    no: '04',
    title: '每日作息表',
    intro: '任务条数与起止时间来自考生确认的空闲窗口,固定占用已扣除。',
    blocks: blocksOf(
      weekTable ? { type: 'tables', tables: [weekTable] } : null,
      templateTables.length ? { type: 'tables', tables: templateTables.slice(0, 4) } : null,
      text(spec.rules.slice(0, 4).join('\n')),
    ),
  }
}

function chapter05(spec: PlanSpec): DocChapter {
  const plan = compilePlanSpec(spec)
  const weeks = spec.stages[0]?.weeks.slice(0, 4) ?? []
  const weekCards: DocCard[] = weeks.map(week => {
    const items = plan.items.filter(item => iso(item.planDate) >= week.startDate && iso(item.planDate) <= week.endDate)
    const top = items.slice(0, 3).map(item => `${iso(item.planDate)} ${item.subject}·${item.title}`)
    return {
      icon: '🚀',
      title: `第 ${week.index + 1} 周`,
      subtitle: `${week.startDate} ~ ${week.endDate}`,
      lines: [
        `本周主线:${week.focus}`,
        ...top,
        `目标投入约 ${Math.round(week.targetMinutes / 60 * 10) / 10} 小时`,
      ].slice(0, 5),
    }
  })
  return {
    no: '05',
    title: '启动四周',
    intro: '先把第一周跑顺,再谈全程。',
    blocks: blocksOf(cards(weekCards),
      text('启动期只考核两件事:每天第一块任务是否完成、每周复盘是否落地。不要用「今天学了几小时」评价自己。')),
  }
}

function chapter06(spec: PlanSpec): DocChapter {
  const tables: DocTable[] = []
  for (const subject of spec.subjects) {
    const playbook = playbookForSubject(subject.name)
    const built = table(`${subject.name} · 资料清单`, ['类别', '名称', '用途', '优先级'],
      playbook.materials.map(item => [item.category, item.name, item.use, item.priority]))
    if (built) tables.push(built)
  }
  return {
    no: '06',
    title: '资料清单',
    intro: '只列考生确认过的资料;未确认的按通用类别占位,不推荐具体书单。',
    blocks: blocksOf(tables.length ? { type: 'tables', tables: tables.slice(0, 8) } : null,
      text('资料使用原则:一个阶段只保留一套主资料;真题留到限时训练,不要提前刷完。')),
  }
}

function chapter07(spec: PlanSpec): DocChapter {
  return {
    no: '07',
    title: '执行军规',
    intro: '这些规则与每日排课使用同一套口径,不是额外建议。',
    blocks: blocksOf(cards(spec.rules.map(rule => ({
      icon: '📌',
      title: rule,
      lines: ['每天先检查这一条是否做到', '连续两天违反,就在周复盘里调整排课'],
    })))),
  }
}

function chapter08(spec: PlanSpec): DocChapter {
  const exam = fromIso(spec.examDate)
  const keyDates = [
    { offset: -90, label: '距考前 90 天', item: '完成强化期复盘,确认薄弱专题清单' },
    { offset: -60, label: '距考前 60 天', item: '进入真题/套卷阶段,固定模考节奏' },
    { offset: -45, label: '距考前 45 天', item: '关注报名/网上确认公告(以官方通知为准)' },
    { offset: -30, label: '距考前 30 天', item: '完成第一轮套卷复盘,锁定背诵材料' },
    { offset: -14, label: '距考前 14 天', item: '打印准考证(以官方通知为准),调整作息' },
    { offset: -7, label: '考前一周', item: '只回炉错题与背诵,不再开新内容' },
    { offset: 0, label: iso(exam), item: '初试' },
  ]
  const countdown = table('关键日期线', ['时间点', '事项'], keyDates.map(item => [item.label, item.item]))
  const examTable = table('初试时间表', ['日期', '上午', '下午'], [[iso(exam), '待官方通知', '待官方通知']])
  return {
    no: '08',
    title: '附录:关键时间线',
    intro: '倒计时节点按考期推算,官方日期公布后以官方为准。',
    blocks: blocksOf(examTable ? { type: 'tables', tables: [examTable] } : null,
      countdown ? { type: 'tables', tables: [countdown] } : null,
      text(`把这份计划当成底稿:从 ${spec.startDate} 开始,每天完成第一块任务,就已经在正确的路上了。`)),
  }
}

const DERIVED_CHAPTERS = [chapter01, chapter02, chapter03, chapter04, chapter05, chapter06, chapter07, chapter08]

/** LLM 章节只保留需要判断/文风的几章;结构数字全部以 spec 派生为准 */
const KEEP_FROM_LLM = new Set(['01', '05', '06', '07', '08'])

function normalizeNo(value: string): string {
  const digits = String(value ?? '').replace(/\D/g, '')
  return digits ? String(Number(digits)).padStart(2, '0') : ''
}

export function deriveDocument(spec: PlanSpec, llm: PlanDocument | null, title: string): PlanDocument {
  const derived = DERIVED_CHAPTERS.map(builder => builder(spec))
  const byNo = new Map(derived.map(chapter => [chapter.no, chapter]))
  for (const chapter of llm?.chapters ?? []) {
    const no = normalizeNo(chapter.no)
    // 02/03/04 是数字一致性最强的三章,永远用派生版;其余优先用模型写得更像人话的版本
    if (!KEEP_FROM_LLM.has(no) || !byNo.has(no) || chapter.blocks.length === 0) continue
    byNo.set(no, { ...chapter, no, title: byNo.get(no)!.title || chapter.title })
  }
  const chapters = [...byNo.values()].sort((a, b) => a.no.localeCompare(b.no))
  return {
    title: llm?.title || title,
    hero: buildHero(spec, title, llm?.hero),
    chapters,
  }
}
