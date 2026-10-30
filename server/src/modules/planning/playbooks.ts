/**
 * 备考知识库(playbooks):把「468 天全程作战计划」这类人工规划里的可复用经验,
 * 从一段 few-shot 文本升级成结构化资产。
 *
 * 它解决三个具体问题:
 * 1. 模型每次都要重新发明「阶段怎么切、每科按什么顺序学」,质量随温度和上下文漂移;
 * 2. 服务端缺少可校验的「科目知识点序列」,无法判断一周的标题是不是在原地打转;
 * 3. 文档、每日清单、补生成三条路径各自写一套科目内容,数字容易互相打架。
 *
 * 这里的知识只描述**通用备考规律**(阶段比例、题型顺序、日节奏、执行规则),
 * 不包含任何具体书单或分数承诺;考生确认过的范围与里程碑永远优先。
 */

export type PlaybookPhase = 'foundation' | 'strengthen' | 'sprint'

export type SubjectPhasePlan = {
  /** 本阶段该科的主线,一句话 */
  focus: string
  /** 按先修顺序排列的知识点/题型序列;用于周推进与去重校验 */
  topics: string[]
  /** 每周循环动作模板,{topic} 会被替换成当周知识点 */
  actions: string[]
}

export type SubjectPlaybook = {
  id: string
  /** 展示名(文档与 prompt 用) */
  name: string
  /** 匹配考生确认科目名用的别名,含常见写法 */
  aliases: string[]
  category: 'math' | 'english' | 'politics' | 'major' | 'generic'
  /** 该科在全程中的权重基准(相对值,数学/专业课更高) */
  weight: number
  phases: Record<PlaybookPhase, SubjectPhasePlan>
  materials: Array<{ category: string; name: string; use: string; priority: string }>
}

const GENERIC_MATERIALS = [
  { category: '教材', name: '考生已确认的教材/讲义', use: '基础期逐章过一遍,标注不熟章节', priority: '高' },
  { category: '习题', name: '考生已确认的习题册', use: '基础与强化期按章配套练习', priority: '高' },
  { category: '真题', name: '考生已确认的历年真题', use: '强化后期与冲刺期限时训练', priority: '高' },
]

export const SUBJECT_PLAYBOOKS: SubjectPlaybook[] = [
  {
    id: 'math',
    name: '考研数学',
    aliases: ['数学一', '数学二', '数学三', '高数', '高等数学', '线性代数', '概率论', '微积分', '数学'],
    category: 'math',
    weight: 2,
    phases: {
      foundation: {
        focus: '教材与基础课过一轮,把定义、定理和基本运算练到不卡壳',
        topics: ['极限与连续', '一元函数微分学', '一元函数积分学', '多元函数微分学', '重积分', '微分方程', '行列式与矩阵', '向量与线性方程组', '特征值与二次型', '随机事件与概率', '随机变量及其分布', '数字特征'],
        actions: ['{topic}基础课一讲 + 课堂例题重做', '{topic}教材精读 + 课后基础题', '{topic}定义与公式默写 + 典型题 10 道', '{topic}错题订正:写清错因与正确思路'],
      },
      strengthen: {
        focus: '按题型专题突破,从「会做」转向「做对且做快」',
        topics: ['极限计算专题', '中值定理证明', '积分计算与几何应用', '多元最值与条件极值', '级数敛散性', '线性方程组解的判定', '相似对角化', '分布函数与密度', '数字特征计算', '大数定律与中心极限定理'],
        actions: ['{topic}专题精讲 + 例题独立重做', '{topic}专项练习 15 题,限时 45 分钟', '{topic}错题归因:概念/计算/思路各占多少', '{topic}一题多解整理,挑 2 道写清最优路径'],
      },
      sprint: {
        focus: '真题套卷限时 + 错题回炉,保持手感与计算准确率',
        topics: ['历年真题套卷', '高频错题回炉', '公式与结论速查', '计算准确率训练', '考场时间分配'],
        actions: ['真题套卷限时 3 小时(或拆分模块限时)', '套卷批改:按题型统计失分并归档', '错题本二刷:只做标记过的题', '公式与结论默写 + 易错点自检'],
      },
    },
    materials: [
      { category: '教材', name: '考生已确认的基础讲义/教材', use: '基础期逐章精读,课后题全做', priority: '高' },
      { category: '习题', name: '考生已确认的习题册', use: '基础期配套、强化期二刷错题', priority: '高' },
      { category: '真题', name: '考生已确认的历年真题', use: '强化后期按套限时,冲刺期二刷', priority: '高' },
      { category: '工具', name: '错题本/公式手册', use: '每周整理一次,冲刺期只看这本', priority: '中' },
    ],
  },
  {
    id: 'english',
    name: '考研英语',
    aliases: ['英语一', '英语二', '英语', '考研英语'],
    category: 'english',
    weight: 1.5,
    phases: {
      foundation: {
        focus: '词汇与长难句打底,阅读从精读一篇开始建立手感',
        topics: ['核心词汇 Unit 1-10', '核心词汇 Unit 11-20', '长难句:主干与修饰', '长难句:从句拆分', '阅读精读:细节题', '阅读精读:主旨题', '阅读精读:态度题', '翻译:句子结构'],
        actions: ['{topic}:背 1 个单元 + 当天回滚前一日', '{topic}:精读一篇真题阅读,逐句翻译', '{topic}:生词与长难句整理进笔记本', '{topic}:做题后复盘每个选项的对错依据'],
      },
      strengthen: {
        focus: '阅读题型专项 + 翻译写作起步,把正确率稳定下来',
        topics: ['阅读:例证题与推理题', '阅读:词义句意题', '新题型:七选五/排序', '翻译:真题长句', '写作:小作文模板', '写作:大作文结构', '完形:逻辑与搭配', '真题阅读二刷'],
        actions: ['{topic}:真题 2 篇限时 40 分钟 + 逐题复盘', '{topic}:总结该题型的定位句与干扰项特征', '{topic}:写一篇并对照范文改 3 处', '{topic}:生词与同义替换整理'],
      },
      sprint: {
        focus: '套卷节奏 + 作文成稿背诵,把时间分配固定下来',
        topics: ['真题套卷(近 5 年)', '作文模板个性化', '高频词与同义替换', '翻译踩分点', '考场时间分配'],
        actions: ['真题套卷限时 180 分钟,作文必须写完', '套卷复盘:阅读错题按题型归类', '作文成稿默写 + 替换词升级', '高频词快速过一遍,只标记不熟的'],
      },
    },
    materials: [
      { category: '教材', name: '考生已确认的词汇书/长难句资料', use: '基础期每天固定时段,滚动复习', priority: '高' },
      { category: '真题', name: '考生已确认的历年真题', use: '基础期精读、强化期限时、冲刺期套卷', priority: '高' },
      { category: '工具', name: '作文模板与错词本', use: '强化期开始积累,冲刺期只背自己的版本', priority: '中' },
    ],
  },
  {
    id: 'politics',
    name: '考研政治',
    aliases: ['政治', '考研政治', '思想政治'],
    category: 'politics',
    weight: 1,
    phases: {
      foundation: {
        focus: '跟课过一遍知识框架,选择题只求眼熟不求背诵',
        topics: ['马原:唯物论与辩证法', '马原:认识论与历史唯物主义', '马原:政治经济学', '毛中特:新民主主义革命', '毛中特:社会主义改造与建设', '史纲:近代史主线', '思修:人生观与价值观', '思修:法治观'],
        actions: ['{topic}:看课 1 讲 + 画知识框架', '{topic}:配套选择题 20 道,错题标回讲义', '{topic}:时间线/概念对比整理', '{topic}:只做选择题,不背大题'],
      },
      strengthen: {
        focus: '选择题二刷 + 高频考点对比,开始接触分析题框架',
        topics: ['马原高频选择题', '毛中特高频选择题', '史纲时间线与会议', '思修易混概念', '时政热点专题', '分析题答题框架'],
        actions: ['{topic}:选择题 30 道 + 错题归因', '{topic}:易混点对比表默写', '{topic}:分析题写答题要点(不展开)', '{topic}:时政与考点对应关系整理'],
      },
      sprint: {
        focus: '押题卷选择题 + 分析题背诵,时政集中突破',
        topics: ['押题卷选择题', '分析题背诵(马原)', '分析题背诵(毛中特/史纲)', '分析题背诵(思修/时政)', '时政专题', '错题回炉'],
        actions: ['{topic}:选择题限时 30 分钟 + 订正', '{topic}:背诵 2 个专题并默写关键词', '{topic}:分析题按「原理+材料+结论」写一遍', '{topic}:只回看标记过的错题'],
      },
    },
    materials: [
      { category: '网课', name: '考生已确认的政治课程', use: '基础期跟课,强化期只看薄弱模块', priority: '中' },
      { category: '习题', name: '选择题题库/1000 题', use: '基础期配套、强化期二刷错题', priority: '高' },
      { category: '真题', name: '历年真题选择题', use: '强化期开始按年份做', priority: '中' },
    ],
  },
  {
    id: 'cs408',
    name: '计算机 408',
    aliases: ['408', '计算机学科专业基础', '数据结构', '计算机组成原理', '操作系统', '计算机网络'],
    category: 'major',
    weight: 2,
    phases: {
      foundation: {
        focus: '四门课教材/基础课过一轮,概念与典型题先建立骨架',
        topics: ['数据结构:线性表', '数据结构:栈与队列', '数据结构:树与二叉树', '数据结构:图', '数据结构:查找与排序', '组成原理:数据表示与运算', '组成原理:存储系统', '组成原理:指令系统与 CPU', '操作系统:进程与线程', '操作系统:内存管理', '操作系统:文件与 I/O', '计算机网络:分层与物理层', '计算机网络:数据链路层', '计算机网络:网络层', '计算机网络:传输层与应用层'],
        actions: ['{topic}:教材精读一节 + 课后选择题', '{topic}:基础课一讲 + 笔记补全', '{topic}:手写核心流程图/结构图', '{topic}:选择题 20 道,错题回看对应小节'],
      },
      strengthen: {
        focus: '按四门课专题刷题,大题开始动手写完整过程',
        topics: ['数据结构:树与图算法题', '数据结构:排序与查找综合题', '组成原理:存储与 Cache 计算', '组成原理:指令与流水线分析', '操作系统:PV 操作与死锁', '操作系统:页面置换计算', '计算机网络:子网划分与路由', '计算机网络:TCP 拥塞控制', '四科综合大题'],
        actions: ['{topic}:专项选择题 30 道 + 错题归因', '{topic}:大题手写完整步骤,对照答案找缺口', '{topic}:画图/列表整理易混机制', '{topic}:真题对应章节限时训练'],
      },
      sprint: {
        focus: '真题套卷限时 + 高频大题模板,四科轮流保温',
        topics: ['历年真题套卷', '四科高频大题', '易混概念对比', '计算题准确率', '考场时间分配'],
        actions: ['真题套卷限时 180 分钟', '套卷批改:按四科统计失分', '高频大题模板默写', '易混概念对比表快速过'],
      },
    },
    materials: [
      { category: '教材', name: '考生已确认的四科教材/辅导书', use: '基础期逐章,强化期查漏', priority: '高' },
      { category: '习题', name: '考生已确认的分科习题', use: '基础期配套、强化期二刷', priority: '高' },
      { category: '真题', name: '考生已确认的历年真题', use: '强化后期按套限时,冲刺期二刷', priority: '高' },
      { category: '工具', name: '四科错题本/概念对比表', use: '每周整理,冲刺期只看这本', priority: '中' },
    ],
  },
]

const GENERIC_PLAYBOOK: SubjectPlaybook = {
  id: 'generic',
  name: '通用科目',
  aliases: [],
  category: 'generic',
  weight: 1,
  phases: {
    foundation: {
      focus: '按考生确认的范围过一轮基础,建立知识框架',
      topics: ['基础概念与框架', '核心章节一', '核心章节二', '基础题型'],
      actions: ['{topic}:精读并整理框架笔记', '{topic}:配套基础练习并订正', '{topic}:用自己的话复述核心结论', '{topic}:标记不熟处,下一轮优先'],
    },
    strengthen: {
      focus: '按题型/专题突破,把会做变成做对',
      topics: ['高频专题一', '高频专题二', '易错题型', '综合练习'],
      actions: ['{topic}:专项练习并统计正确率', '{topic}:错题归因,补对应知识点', '{topic}:限时训练,记录用时', '{topic}:整理解题步骤模板'],
    },
    sprint: {
      focus: '真题/模拟限时 + 错题回炉,保持状态',
      topics: ['历年真题/模拟卷', '高频错题', '核心结论速查', '考场节奏'],
      actions: ['套卷限时完成并批改', '错题本回炉,只做标记题', '核心结论与易错点默写', '按考试时间调整作息'],
    },
  },
  materials: GENERIC_MATERIALS,
}

/** 科目名 → playbook:先精确匹配别名,再互相包含;匹配不到走通用模板 */
export function playbookForSubject(name: string): SubjectPlaybook {
  const canon = (value: string) => value.replace(/\s+/g, '').toLowerCase()
  const key = canon(name)
  if (!key) return GENERIC_PLAYBOOK
  for (const playbook of SUBJECT_PLAYBOOKS) {
    if (playbook.aliases.some(alias => canon(alias) === key) || canon(playbook.name) === key) return playbook
  }
  // 「数学二」「英语(二)」这类带修饰的写法:取最长命中,避免「数学」抢走「数学二」
  const hits = SUBJECT_PLAYBOOKS.filter(playbook =>
    playbook.aliases.some(alias => key.includes(canon(alias)) || canon(alias).includes(key)))
  if (hits.length === 0) return GENERIC_PLAYBOOK
  return hits.sort((a, b) => b.name.length - a.name.length)[0]
}

/** 目标类型 → 阶段原型。比例之和为 1,服务端按总天数换算成连续区间 */
export type StageArchetype = {
  name: string
  ratio: number
  /** 阶段该成什么事,写进 strategy */
  strategy: string
  /** 可验收的里程碑模板 */
  milestones: string[]
  /** 期望的周强度(相对值),用于校验与文档 */
  intensity: 'low' | 'medium' | 'high'
}

const KAOYAN_ARCHETYPES: StageArchetype[] = [
  {
    name: '筑基期',
    ratio: 0.43,
    strategy: '教材与基础课过一轮,把每科的地基打到「能听懂、能做基础题」',
    milestones: ['全部基础教材/课程过完一轮', '基础习题一刷完成,错题已标记', '每科形成一页知识框架'],
    intensity: 'low',
  },
  {
    name: '强化期',
    ratio: 0.24,
    strategy: '按题型专题突破,从「过一遍」转向「做对且做快」',
    milestones: ['核心题型全部过一遍', '错题本成型并完成一次回炉', '每科正确率较基础期提升'],
    intensity: 'medium',
  },
  {
    name: '专项突破期',
    ratio: 0.16,
    strategy: '主攻薄弱专题与失分点,把短板补到不拖后腿',
    milestones: ['薄弱专题专项训练完成', '高频失分点逐项清零', '限时训练达到目标正确率'],
    intensity: 'high',
  },
  {
    name: '真题模考期',
    ratio: 0.08,
    strategy: '以年份真题套卷为单位,一天模考一天复盘,严格计时',
    milestones: ['近 5 年真题套卷完成', '每套卷完成逐题复盘', '考场时间分配固定下来'],
    intensity: 'high',
  },
  {
    name: '收官期',
    ratio: 0.09,
    strategy: '回归错题与背诵,保温状态,不再开新内容',
    milestones: ['错题本完成最后一轮回炉', '核心背诵材料默写通过', '作息与考试时间对齐'],
    intensity: 'medium',
  },
]

const GENERIC_ARCHETYPES: StageArchetype[] = [
  {
    name: '基础期',
    ratio: 0.45,
    strategy: '按确认范围过一轮基础,建立知识框架',
    milestones: ['基础内容过完一轮', '基础练习完成并订正', '形成知识框架笔记'],
    intensity: 'low',
  },
  {
    name: '强化期',
    ratio: 0.35,
    strategy: '按题型/专题突破,提升正确率与速度',
    milestones: ['核心题型全部过一遍', '错题本成型', '限时训练达到目标正确率'],
    intensity: 'medium',
  },
  {
    name: '冲刺期',
    ratio: 0.2,
    strategy: '真题/模拟限时 + 错题回炉,调整考场节奏',
    milestones: ['近 5 年真题/模拟卷完成', '错题完成回炉', '考试时间作息固定'],
    intensity: 'high',
  },
]

/** 备考周期不足半年时压缩为三段,避免每个阶段只有几天、什么都做不完 */
export function stageArchetypes(targetType: string, totalDays: number): StageArchetype[] {
  const base = targetType === '考研' ? KAOYAN_ARCHETYPES : GENERIC_ARCHETYPES
  if (totalDays >= 180) return base
  if (targetType !== '考研') return base
  // 短周期:保留筑基/强化/冲刺的实质,把 5 段压成 3 段并重新分配比例
  return [
    { ...KAOYAN_ARCHETYPES[0], ratio: 0.4 },
    { ...KAOYAN_ARCHETYPES[1], ratio: 0.35 },
    { ...KAOYAN_ARCHETYPES[4], name: '冲刺期', ratio: 0.25, strategy: '真题模考与错题回炉并行,优先保住已会内容' },
  ]
}

/** 日节奏模板:只描述块的结构与相对长短,具体起止时间由编译器按当天真实空闲窗口填入 */
export type DayTemplate = {
  id: string
  name: string
  /** 适用场景,文档第 04 章直接用它做表标题 */
  scene: string
  /** 总时长下限/上限(分钟),编译器按容量挑模板 */
  minMinutes: number
  maxMinutes: number
  blocks: Array<{ label: string; kind: 'deep' | 'practice' | 'review' | 'rest' | 'memorize'; share: number }>
}

export const DAY_TEMPLATES: DayTemplate[] = [
  {
    id: 'fragmented',
    name: '碎片日',
    scene: '只有零散小段空闲的工作日',
    minMinutes: 30,
    maxMinutes: 119,
    blocks: [
      { label: '主任务:当天最优先的一个知识点', kind: 'deep', share: 0.6 },
      { label: '复盘:错题/笔记快速过一遍', kind: 'review', share: 0.4 },
    ],
  },
  {
    id: 'workday',
    name: '工作日晚间',
    scene: '白天上课/上班,晚上有 2–3 小时',
    minMinutes: 120,
    maxMinutes: 239,
    blocks: [
      { label: '深度块:新知识/专题推进', kind: 'deep', share: 0.45 },
      { label: '练习块:配套题/限时训练', kind: 'practice', share: 0.35 },
      { label: '收尾:错题订正与明日计划', kind: 'review', share: 0.2 },
    ],
  },
  {
    id: 'halfday',
    name: '半日块',
    scene: '有半天完整空闲的周末/没课日',
    minMinutes: 240,
    maxMinutes: 419,
    blocks: [
      { label: '深度块一:主科推进', kind: 'deep', share: 0.35 },
      { label: '练习块:限时训练', kind: 'practice', share: 0.3 },
      { label: '深度块二:次主科推进', kind: 'deep', share: 0.25 },
      { label: '收尾:错题与复盘', kind: 'review', share: 0.1 },
    ],
  },
  {
    id: 'fullday',
    name: '整日块',
    scene: '全天可支配的假期/周末',
    minMinutes: 420,
    maxMinutes: 720,
    blocks: [
      { label: '上午深度块:主科新内容', kind: 'deep', share: 0.28 },
      { label: '上午练习块:配套题', kind: 'practice', share: 0.17 },
      { label: '下午深度块:次主科/专业课', kind: 'deep', share: 0.25 },
      { label: '下午练习块:限时训练', kind: 'practice', share: 0.15 },
      { label: '晚上复盘块:错题与背诵', kind: 'review', share: 0.1 },
      { label: '弹性缓冲:补欠账或休息', kind: 'rest', share: 0.05 },
    ],
  },
]

export function dayTemplateFor(minutes: number): DayTemplate {
  const hit = DAY_TEMPLATES.find(template => minutes >= template.minMinutes && minutes <= template.maxMinutes)
  if (hit) return hit
  return minutes < DAY_TEMPLATES[0].minMinutes ? DAY_TEMPLATES[0] : DAY_TEMPLATES[DAY_TEMPLATES.length - 1]
}

/** 执行军规:文档第 07 章与 prompt 共用,保证「说的」和「排的」一致 */
export const PLAN_RULES = [
  '先做难的,再做熟的:每天第一个学习块留给最需要动脑的科目。',
  '单条任务不超过 60 分钟;超过就拆成「学习」与「练习」两条。',
  '每天结束前留 10–20 分钟复盘:今天错在哪、明天先做什么。',
  '每周留一次回炉:只做错题本上标记过的题,不碰新题。',
  '课表优先:固定上课/上班时间不排任务,空闲窗口才排。',
  '不追进度追掌握:一个专题没做对,不急着开下一个。',
  '真题只在限时状态下做,平时练习不碰套卷。',
  '每周至少留半天弹性,用来补欠账或休息,不排满。',
  '睡前不安排高强度新内容,留给背诵与复盘。',
  '计划是底稿不是枷锁:状态差时保底完成第一块,其余顺延。',
]

/** 科目在阶段中的权重:薄弱科目上浮,政治类在基础期略低 */
export function subjectWeight(name: string, weakSubjects: string[], phase: PlaybookPhase): number {
  const playbook = playbookForSubject(name)
  let weight = playbook.weight
  if (weakSubjects.includes(name)) weight *= 1.35
  if (playbook.category === 'politics' && phase === 'foundation') weight *= 0.7
  return weight
}

export function phaseOfStage(stageIndex: number, stageCount: number): PlaybookPhase {
  if (stageCount <= 1) return 'strengthen'
  const ratio = stageIndex / (stageCount - 1)
  if (ratio < 0.4) return 'foundation'
  if (ratio < 0.75) return 'strengthen'
  return 'sprint'
}

/** 把知识库压成 prompt 片段:只给当前考试科目相关的部分,避免无关内容干扰 */
export function playbookPrompt(confirmedSubjects: string[], targetType: string, stageCount: number): string {
  const lines: string[] = ['【该考试类型的通用备考规律(只学结构与顺序,不得照抄具体书目)】']
  const archetypes = stageArchetypes(targetType, 365)
  lines.push(`阶段原型:${archetypes.map(a => `${a.name}(${Math.round(a.ratio * 100)}%):${a.strategy}`).join('；')}`)
  if (stageCount < archetypes.length) {
    lines.push(`本次只有 ${stageCount} 个阶段:把相邻阶段合并,但必须保留「基础→强化→冲刺」的递进。`)
  }
  for (const name of confirmedSubjects) {
    const playbook = playbookForSubject(name)
    if (playbook.id === 'generic') continue
    lines.push(`- ${name}:基础期 ${playbook.phases.foundation.focus};强化期 ${playbook.phases.strengthen.focus};冲刺期 ${playbook.phases.sprint.focus}`)
    lines.push(`  知识点推进顺序参考:${playbook.phases.foundation.topics.slice(0, 6).join(' → ')}`)
  }
  lines.push('执行规则:')
  for (const rule of PLAN_RULES) lines.push(`- ${rule}`)
  return lines.join('\n')
}

/** 某科在某个阶段的 topics(供细化轮按周推进;没有专属知识库时给空数组) */
export function topicsForSubject(name: string, phase: PlaybookPhase): string[] {
  return playbookForSubject(name).phases[phase].topics
}

export function actionsForSubject(name: string, phase: PlaybookPhase): string[] {
  return playbookForSubject(name).phases[phase].actions
}
