# 研钟 UI 全面精修设计

日期：2026-10-30
范围：Android 客户端全部页面
状态：待评审

## 背景

经过多轮功能迭代，客户端 UI 已出现系统性分叉：

- 页面头部有三种写法：`PageHeader`（HomeScreen/PlanScreen）、手写两行 Text（StatsScreen）、居中大标题（FocusScreen）；字号从 20sp 到 26sp 不等，字重有的 Bold 有的默认。
- 同一类“状态胶囊”至少四处内联手写：HomeScreen 的“目标达成”、StatsScreen 的“已达成/月报”、WeekReportCard 的百分比胶囊；共享的 `StatusPill` 反而无人使用。
- 卡片规格混用：`SectionCard`、各页私有 `Surface`、`HeroCard` 并存；内边距 16/20、圆角 14/16/20、区块间距 14/18 混用；只有可点击卡片带 2–3dp 阴影，静态卡没有，导致同类卡片看起来像两种东西。
- 统计页把实现口径直接铺在正文：“任务完成率 = 0 已完成 / 0 计划”“筛选作用于上方小结与科目占比；下方长期图表各自标注统计窗口。”
- 首页把 🍅 emoji 当数据图标；专注页空闲态把任务列表放在开始按钮之前，首屏看不到主操作，下方留出半屏空白。
- 底部导航 5 项 + 中央凸起 FAB，FAB 压住“专注”标签，形成一个灰色禁用项（`enabled = false`）。
- 代码侧有 217 处散落的 `RoundedCornerShape(...)` / `fontWeight = FontWeight...` / `shadowElevation` / `tonalElevation`，没有任何组件能约束它们的用法。

结论：问题不在某个页面画得不好，而在缺少唯一的事实来源。本设计先建立令牌与组件原语，再按页面迁移，避免再次分叉。

## 目标

1. 全 App 的排版、颜色、间距、圆角、阴影、动效只来自一套令牌。
2. 每类 UI 元素只有一个共享实现，页面不再自行拼装卡片、胶囊、按钮、空态。
3. 四个主 tab 与全部二级页统一页头、导航、卡片与表单规格。
4. 每个页面只有一个视觉主角，其余信息降为行；长解释文案收进信息弹窗。
5. 修复底部导航的遮挡与禁用态，专注入口在全局始终可达且能显示计时状态。
6. 行为、数据、路由、ViewModel 逻辑保持不变。

## 非目标

- 不改动数据模型、数据库、同步协议、计划生成、计时规则。
- 不新增功能、不新增路由、不更换图标库或动画库。
- 不做品牌重塑（保留“研钟”名称与低饱和靛蓝主色）。
- 不为平板单独设计新布局；平板沿用现有 `NavigationRail` 与首页双栏，仅替换为新组件。
- 不改动服务端。

## 设计原则

1. **单一来源**：页面里不出现字面量圆角、阴影、胶囊；需要新样式先在组件层加。
2. **一个主角**：每屏最多一张主渐变卡加一个行动 CTA，其余用中性表面。
3. **信息先行**：标题说结论，数字说进度，解释放弹窗；正文不写实现说明。
4. **触达一致**：所有可点行 ≥ 48dp，所有图标按钮有 contentDescription，所有主操作在首屏可见。
5. **深浅同源**：颜色全部取自 `MaterialTheme` 或语义色，深浅模式自动成立。

## 一、令牌层

### 1.1 排版

`Type.kt` 收敛为 7 个语义角色 + 1 个展示数字令牌（Countdown）。页面不得再写 `fontSize` 或 `fontWeight`。Material 组件内部使用的槽位同步调整，避免按钮、输入框与正文不在同一标尺。

| 语义角色 | Typography 槽位 | 规格（字号 / 行高 / 字重） | 用途 |
| --- | --- | --- | --- |
| PageTitle | `headlineSmall` | 22 / 28 / Bold | 四个 tab 与二级页标题 |
| PageMeta | `bodySmall` | 12 / 18 / Regular | 日期、阶段、页副标题 |
| SectionTitle | `titleMedium` | 16 / 22 / SemiBold | 卡片标题、分组标题 |
| Body | `bodyMedium` | 14 / 22 / Regular | 列表内容、说明 |
| Caption | `bodySmall` | 12 / 18 / Regular | 时间、次要信息、指标标签 |
| Micro | `labelSmall` | 11 / 14 / Medium | 胶囊、单位、导航标签 |
| Metric | `headlineMedium` | 24 / 30 / SemiBold / tnum | KPI 数字（紧凑变体 20/26） |
| Countdown（展示令牌） | `displayLarge` | 40 / 44 / Bold / tnum | 仅倒计时与专注主数字 |

补充规则：

- `Metric`、`Body`、`Caption`、`Micro` 全部启用 `tnum`，防止数字跳动。
- `titleSmall` 调整为 14/20/Medium，供 Material 内部与少量行标题使用，不直接出现在页面。
- 中文正文行高不得小于字号的 1.5 倍：14sp → 22sp，12sp → 18sp。
- 禁用 `headlineLarge`（26sp）与 `displayMedium/displaySmall` 在页面中的直接使用；它们不属于任何语义角色。

### 1.2 颜色与表面

保留现有品牌色与科目色，新增/调整如下：

- 新增 `BrandGradient = listOf(Color(0xFF4338CA), Color(0xFF6D28D9))`（深靛 → 深紫），白色内容对比度分别为 8.4:1 与 6.4:1，深色模式同样使用。使用点四处：今日倒计时、统计 KPI、我的头部、登录 / 启动过场。倒计时进入琥珀/红色预警档时，分别改用 `WarnAmberContainer` / `WarnRedContainer` 语义底，文字用对应 content 色，不再用渐变。
- 现有 `StatBannerStart #5865F2 → StatBannerEnd #7B9AF8` 白字在小字号上对比度不足（#7B9AF8 上白字约 2.6:1），废弃并删除这两个常量，统计横幅改用 `BrandGradient`。
- 删除 `NightSkyTop / NightSkyMid / NightSkyBottom / NightMountainFar / NightMountainNear`（当前仅 `MainActivity` 使用）；登录检查过场改用 `BrandGradient`。
- 珊瑚红行动渐变调整为 `#D9482E → #B3321F`，白色标题与说明对比度 ≥ 4.7:1；`actionCardGradient()` 返回该值（深色模式保持低饱和一档，但同样满足白字对比度）。每屏最多一处。
- 卡片表面：`surface` 底 + `1dp outlineVariant` 描边，**零阴影**。深色模式同理，描边用 `outlineVariant`。
- 内嵌槽（次级块、图表底、输入框底色）：`surfaceVariant.copy(alpha = 0.5f)`，全 App 不再出现 0.4/0.45 等变体。
- 语义色使用范围：
  - `SuccessGreen`：达成、同步成功。
  - `WarnAmber* / WarnRed*`：倒计时预警、同步失败、危险操作。
  - `MintGreen / CoralRed / SkyBlue / AmberGold`：仅作为用户自建科目的调色板与图表分类色，不再用作分组图标底色。
- 分组图标底色统一 `primary.copy(alpha = 0.12f)`，图标 `primary`。

### 1.3 间距、圆角、阴影

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| 页面左右内边距 | 20dp | 所有主页面与二级页 |
| 区块间距 | 16dp | LazyColumn `verticalArrangement` |
| 卡片内边距 | 16dp | 所有 `AppCard` 默认值 |
| 卡内元素间距 | 12dp | 标题与内容、列表行之间 |
| 列表行最小高度 | 56dp | `ListRow` |
| 卡片圆角 | 20dp | `shapes.large` |
| 内嵌块圆角 | 14dp | `shapes.medium` |
| 按钮高度 | 48dp | 主/次按钮 |
| 胶囊 | 999dp | 状态、标签、筛选 |

- 删除页面上所有 `shadowElevation`；仅底部 FAB 保留 4dp 阴影。
- `tonalElevation` 在页面中不再出现，统一由 `AppCard` 内部处理。

### 1.4 动效与触感

- tab 切换：淡入 140ms / 淡出 100ms。
- 深层页进出：淡入 + 水平位移 220ms / 180ms（保留现有参数）。
- 进度条：`animateFloatAsState` 300ms。
- 列表增删：`Modifier.animateItem()`。
- 底部 FAB 计时态切换：内容 `AnimatedContent` 200ms。
- 触感：任务完成、开始专注、暂停/继续、结束专注时调用 `LocalHapticFeedback` 的 `TextHandleMove` 或 `LongPress`；不做全局长震动。
- 所有动画尊重系统“移除动画”设置（Compose 默认遵循 `AnimatorDurationScale`，不额外覆盖）。

## 二、组件层

`ui/theme/Components.kt` 重写为唯一组件入口；另建 `ui/theme/Controls.kt` 存放按钮、分段控件、行组件。任何页面不得再定义私有卡片/胶囊/按钮。

| 组件 | 签名要点 | 替代 / 约束 |
| --- | --- | --- |
| `PageHeader(title, meta, actions)` | 22/Bold 标题 + 12 副标题 + 右侧最多 2 个 40dp 图标按钮 | 替换三套页头写法 |
| `SubPageHeader(title, meta, onBack, actions)` | 同上，带返回 | 替换 Account/SuperMode 等私有头部 |
| `AppCard(onClick?, padding = 16dp, content)` | surface + 1dp 描边；可点击时整卡波纹、按圆角裁剪 | 替换页面内所有 `Surface(shape = large)` |
| `RowGroup(content)` | `AppCard(padding = 0)` + 行间 1dp 分隔线（左缩进 16dp） | 设置类页面不再卡中卡 |
| `ListRow(icon?, title, subtitle?, trailing?, onClick?)` | 高 ≥56，标题 Body，副标题 Caption，右侧箭头或自定义 | 替换 Mine 的 `NavRow/ValueRow/SettingRowBase` 与 Account 私有行 |
| `SwitchRow(title, subtitle?, checked, onChange)` | 同 `ListRow`，trailing 为标准 `Switch` | 替换 Focus/Mine/SuperMode 三套开关行 |
| `SectionHeader(title, meta?, action?)` | 卡片外的分组标题，16/SemiBold | 替换手写标题 |
| `MetricTile(value, unit, label, tone)` | 值 24/SemiBold，单位 11，标签 12；tone 决定白字或主题色 | 统计 KPI、累计纪录 |
| `ProgressLine(progress, color, height = 6dp)` | 圆角轨道 + 动画进度 | 替换 5 处手写进度条 |
| `SegmentedChips(options, selectedIndex, onSelect)` | 单行胶囊，选中 `primaryContainer`，未选中描边 | 替换 FilterChip 行、主题选择、专注模式 |
| `PrimaryButton / SecondaryButton / TextAction` | 48dp / 48dp / 40dp；圆角 14；loading 态显示 16dp 转圈 | 统一所有按钮 |
| `StatusPill(text, tone)` | 保留现有枚举 | 强制替换内联胶囊 |
| `EmptyState(icon, title, hint?, action?)` | 新增紧凑变体：图标 56、标题 16、说明 12、可选动作 | 替换大留白空态 |
| `InfoAction(title, message)` | `Info` 图标 + 弹窗，正文可滚动 | 承载口径说明与长解释 |
| `SkeletonBlock(modifier)` | 单一 alpha 脉冲（0.35↔0.85，900ms） | 替换 PlanLoadingCard 与统计加载 |
| `AppSheet(title, onDismiss, content)` | ModalBottomSheet：20dp 顶部圆角、拖拽条、20dp 内边距、`imePadding` | 统一任务编辑、节点管理、选择器 |

`HeroCard` 保留但收口：圆角 20、内边距 20、标题 16、说明 12、右侧装饰图标透明度 0.15；只用于“制定计划”与“开始专注”类 CTA。
`StatBanner` / `StatBannerCell` 保留，内部改用 `MetricTile`。

## 三、导航

### 手机底栏

- 4 个 tab：今日、计划、统计、我的。
- 中央 56dp 圆形 FAB 承担专注入口，位于底栏上沿，垂直偏移 -18dp；FAB 下方在底栏内正常显示“专注”11sp 标签，不再存在禁用项。
- 底栏 `Surface` 高 64dp + 导航栏 inset，顶部 1dp `outlineVariant` 分隔线，无 tonal elevation。
- 选中态：图标与标签 `primary`，未选中 `onSurfaceVariant`；不再使用 M3 默认的胶囊指示器。
- 计时运行中：FAB 内显示剩余时间（`mm:ss`，11sp，tnum）；点击回到专注页。FAB 背景保持 `primary`。
- 专注页计时运行中隐藏底栏（全屏专注）；其余 4 个 tab 与专注空闲态始终显示。
- 深层页（学霸模式、账号、计划设置、面谈、规划文档、历史、白名单）隐藏底栏；若此时计时运行，保留现有右下角迷你计时胶囊。

### 平板

沿用 `NavigationRail`：顶部专注 FAB，下面 4 个 tab；样式与新底栏一致，去掉第 5 个“专注”项。

## 四、页面设计

### 4.1 今日

结构（单列，自上而下）：

0. 删除页面顶部固定的 300dp 背景渐变；主视觉只保留倒计时卡。

1. `PageHeader("今日", "{月日} {周几} · {阶段名}")`，右侧 `+` 图标按钮直接打开任务编辑弹层；meta 单行省略。
2. 倒计时卡：`AppCard` 内使用 `BrandGradient`，白字。
   - 首行：节点名 16/SemiBold + “管理”文字按钮（含节点数，如“管理 · 2 个”）。
   - 次行：`初试 · 2026-12-20 08:30`，12sp，白 70%。
   - 数字行：天/时/分/秒四列，40/44 Bold tnum，单位 11sp 白 70%；≤3 天保留呼吸动画。
   - 多节点：左右滑动切换保留；底部增加圆点指示（≤6 个，超出显示“1/8”）。
   - 预警档位：背景与数字改用 `WarnAmber/WarnRed` 语义色，不再用灰底。
3. 阶段条：`AppCard`。标题 16 + 右侧“第 x / y 天”Caption primary；四段占比条 6dp；底部 12sp 说明（下一阶段或距考试天数）。
4. 今日概览：`AppCard`，三行紧凑进度：
   - 今日任务：`0/2` + `ProgressLine`；
   - 今日番茄：`0/8` + `ProgressLine`（用 `AppIcons.Timer` 12dp 替代 🍅）；
   - 本周净学习：`0.0h / 16h` + `ProgressLine`（达成时 `SuccessGreen`）。
   - 行内标签 14、数值 12 primary；不写“目标”“还差”等长句。
5. 今日节奏：`AppCard`，默认收起为一行：`标准学习日` + `净学习 3h20m · 8 番茄 · 0/2 段`；展开后为时段行（时间列 64dp、科目色点、标题），当前时段 `primaryContainer 50%` 底 + “现在”胶囊。
6. 今日待办：
   - `SectionHeader("今日待办", action = "0/2 胶囊 + 添加")`；
   - 任务行使用 `AppCard(padding = 12dp)`：左侧 24dp 完成圆环；主体两行（标题 14、`科目 · 时间 · N 番茄` 12）；右侧“开始”胶囊（32dp 高，primaryContainer）。
   - 左滑顺延、右滑完成保留；完成后触感反馈；已完成任务折叠为“已完成 3”行，可展开。
   - 空态：紧凑 `EmptyState`（图标 56）：“今天还没有任务” + 主按钮“添加任务”。
   - 列表底部一行 `TextAction("批量录入")`，替代原 QuickStartBar 中的入口。
7. 复盘：`RowGroup` 三行（今日复盘 / 本周复盘 / 本月复盘），右侧状态 Caption 沿用现有可见性规则：今日为“未写 / 已完成”；本周与本月在“未写”或对应时间点（周日 / 月末）时显示，状态为“未写 / 已完成 / 周日提醒（月底提醒）”，点击打开原有弹层。
8. 删除页面底部的 `QuickStartBar`（新番茄 HeroCard、最近任务快捷开始、添加/批量按钮）。理由：中央 FAB 已承担自由专注，任务行已承担任务专注，三处重复。

未生成计划时：倒计时之后插入一张 `HeroCard`（珊瑚渐变）：“还没有学习计划 / 和 AI 聊 5 分钟，按目标院校与每天可用时间排一份全程计划 / 开始制定”。阶段条、概览、节奏、任务区不显示，页面直接进入复盘与空态。

### 4.2 专注

空闲态（垂直居中，可滚动）：

1. `PageHeader("专注", "选一个任务，或直接开始")`。
2. 中央圆环 240dp：
   - 内部上：当前模式 Caption（番茄钟 / 正计时 / 倒计时）；
   - 中间：时长 40sp tnum（番茄钟取当前方案专注时长，倒计时取滑杆值，正计时显示 `00:00`）；
   - 下：当前任务名或“自由专注”，14sp，单行省略。
3. 主按钮：`开始专注 · 25 分钟`（倒计时为 `开始倒计时 · N 分钟`，正计时为 `开始正计时`）。
4. 任务选择行：`ListRow("选择任务", subtitle = "从今日待办中选一个", trailing = ChevronRight)` → 底部弹层列出今日任务；点击任务立即按当前模式开始（与现状一致）。无任务时该行置灰并显示“今日没有待办”。
5. 模式：`SegmentedChips(["番茄钟", "正计时", "倒计时"])`。
6. 番茄方案：`RowGroup` 两行（标准 25 / 深度 50），选中项右侧 `Check` + `primaryContainer` 行底；点击选择。
7. 倒计时模式：滑杆 + `5 / 15 / 25 / 45` 快捷胶囊。
8. `更多设置`：折叠 `RowGroup`（自动串联、连续专注、静音、震动）；默认收起。
9. 专注接力卡：保留，改为 `AppCard` + 主机设备名 + 任务 + 倒计时 + “跟随”按钮。
10. 移除页内 `headlineLarge` 标题与“从今日待办开始”区块头；今日任务只通过选择行进入。

运行态：

- 中央圆环 280dp：顶部阶段胶囊（专注 / 短休 / 长休 / 暂停），中间剩余时间 48sp tnum，下方任务名。
- 背景：顶部 16% 科目色（休息为 `SuccessGreen`）渐变到 `background`，600ms 过渡（保留）。
- 控制：暂停/继续主按钮 + 结束（普通模式文字按钮，严格模式“紧急退出”保留现有文案与次数说明）。
- 底栏隐藏；系统返回仍拦截并提示。
- 休息态：圆环与按钮使用 `SuccessGreen`，文案“休息一下”，提供“跳过休息”。

### 4.3 计划

1. `PageHeader("计划", "AI 按你的备考档案排的全程计划")`，右侧历史图标 + 刷新图标（保持现状能力）。
2. 无计划 / 未登录 / 网络错误：统一 `AppCard` 形态（不带 3dp 阴影）：
   - 图标 28 primary；
   - 标题 16；
   - 说明最多两行（删除“一份几百天的全程计划，靠两页问卷排不出来”这类长解释）；
   - `PrimaryButton` 主操作；
   - `TextAction` 次操作。
   - 无计划文案：“还没有学习计划” / “和 AI 聊 5 分钟，按目标院校与每天可用时间排一份全程计划。” / 主按钮“开始制定” / 次操作“看历史计划”。
3. 有计划：
   - 概览卡：当前阶段名 16 + 阶段第 x/y 天 + 全程进度条 + 下一阶段 Caption；右上角 `StatusPill("进行中")`。
   - 入口 `RowGroup`：调整计划（AI 面谈 adjust）、查看规划书、历史计划；每行 `ListRow` + ChevronRight。
   - 今日计划卡：`SectionTitle("今日计划")` + 计划项行（完成圆环、科目色点、标题、`时间 · N 番茄`）；点击行切换完成状态（保留）；规划文档入口在入口 `RowGroup`，不在计划项上新增手势。
   - 调整记录：`latestAdjustment` 存在时显示一行 `ListRow("最近调整", 摘要, "撤销")`，撤销逻辑不变。
   - 底部次级行：`TextAction("查看示例计划 · 本地 468 天")`，点击展开原有本地参考计划内容；不再作为主流程中的大折叠卡。
4. 加载态：`SkeletonBlock` × 3，替换现有脉冲卡片。
5. 错误态：`AppCard` + “未能读取云端计划” + 原因摘要（12sp）+ 重试按钮；不展示 HTTP 状态码。

### 4.4 统计

1. `PageHeader("统计", "数据全部来自本机")`。
2. 范围选择：`SegmentedChips(["今日", "本周", "本月", "累计"])`，紧贴页头，替换 FilterChip 行与“小结范围”标签。
3. KPI 横幅：`StatBanner` + 4 个 `MetricTile`（净专注、番茄、任务完成率、连续打卡）；白字；数字 24。
4. 复盘行：KPI 横幅底部用一行 Caption 展示环比与专注天数（例：`较上周 ▲ 1.2h · 专注 5 天`），替代独立 `WeekReportCard` / `MonthReportCard`；范围非本周/本月时不显示。
5. 科目投入：`AppCard`，标题“科目投入 · {范围}”；左侧环形图，右侧图例行（色点 + 科目名 + 百分比 + 细进度线）。
6. `SectionHeader("长期趋势", action = InfoAction("统计口径", "任务完成率 = 已完成任务 / 计划任务；专注时长只统计单次满 1 分钟的会话；长期图表窗口固定为近 14 天 / 近 30 天 / 近 15 周，不随上方筛选变化。"))`，下方 Caption“以下窗口固定，不随上方筛选变化”。口径说明文本移入弹窗。
7. 四张 `AppCard` 图表，标题统一“标题 · 窗口”：净专注趋势 · 近 14 天、24 小时时段分布 · 近 30 天、一周节奏 · 周几分布、打卡热力图 · 近 15 周。
8. 累计纪录：`AppCard` + 2×2 `MetricTile`（最长单次、最佳单日、日均、累计天数）。
9. 空数据：KPI 显示 0；科目环形换成紧凑 `EmptyState("这个范围还没有专注记录", "完成一个番茄后开始统计")`；“任务完成率 = 0/0”等公式不出现在正文，只在 InfoAction 弹窗。
10. 移除 `KpiNotes` 卡与页面中的筛选说明长句。

### 4.5 我的

结构（单列，沿用现有信息架构，只重排与统一规格）：

1. 头部卡：`AppCard` + `BrandGradient` 白字，点击进入账号页。
   - 头像 52dp（半透明白底 + 学士帽），用户名 18/SemiBold（未登录“本机学习档案”），下方状态行 Caption：已登录显示“已登录 · 云端同步”，未登录显示“数据保存在本机”。
   - 科目胶囊行：最多 4 个科目标签 + “等 N 科”，白字 11sp，底色 `White @ 16%`。
   - 四项指标（紧凑变体 20/26）：累计专注、活跃天数、连续打卡、峰值时段；全 0 时保留“完成第一个番茄后，这里会亮起来”。
   - 删除夜空/山脉相关配色引用；`NightSky*` 常量随本节与 `MainActivity` 改造一并删除。
2. 账号与档案 `RowGroup`：账号（进入 `AccountScreen`）、编辑备考档案（进入 `PlanSetupScreen`）。
3. 投入与节奏 `AppCard`：最近科目投入 Top 3（色点 + 名称 + 时长）+ 最近 7 天柱状图（主色渐变柱、空值 4dp 细条）；空数据显示一句引导。保留 `LearningProfileCard` 的数据，不再使用独立分组标题“学习数据”。
4. 偏好设置 `RowGroup`：
   - 外观：行内 `SegmentedChips(["浅色", "深色", "跟随系统"])`；
   - 番茄方案：`ListRow` 显示当前方案名与参数摘要，点击打开 `PlanEditorDialog`；
   - 学霸模式：`ListRow` 显示开启状态，进入 `SuperModeScreen`；
   - 自动串联、连续专注、静音、阶段切换震动、阶段切换音效：`SwitchRow`；静音开启时后两项置灰但可预改（保留现有行为）；
   - 每周目标净学习、每日番茄目标：`ListRow` 右侧显示当前值，点击打开现有目标弹窗。
5. 科目管理 `RowGroup`：每个科目一行（色点 + 名称 + 删除图标按钮，删除保留二次确认）；底部“添加科目 / 批量添加”两个 `TextAction`。
6. 关于 `RowGroup`：版本号（跟随 `BuildConfig`）、隐私政策、用户协议。
7. 删除 `MineSectionHead` 的“01–05”编号与“账号与档案 / 学习数据 / 偏好设置”三段说明；分组标题统一用 `SectionHeader`，不再带说明副标题。
8. 删除 `MinePalette` 这套页面私有配色与 `GroupCard/RowDivider/RowIcon/SettingRowBase/NavRow/ValueRow` 私有组件，全部换成 `AppCard/RowGroup/ListRow/SwitchRow`；页面背景恢复 `background`，不再整页铺紫渐变。

### 4.6 二级页

统一要求：`SubPageHeader` + `AppCard`/`RowGroup` + 主次按钮；页面左右 20dp；底部按钮区 `imePadding`；错误信息用 Snackbar + 重试，长说明进 `InfoAction`。

- **登录 / 注册**：全屏 `BrandGradient` 背景 + 顶部 Logo 与“研钟” + 居中 `AppCard` 表单（账号、密码、协议勾选）+ `PrimaryButton`；错误文案在输入框下方 12sp；不改变现有校验与登录逻辑。
- **首次引导（Onboarding/Fallback）**：`SubPageHeader` + 顶部步骤指示（第 x / y 步，细进度线）+ 单题卡片 + 底部主按钮。题型映射：单选题用可勾选 `ListRow`；多选题用带 `Check` 的可勾选行；分档题（如每日学习时长）用 `SegmentedChips`；文本题用标准 `OutlinedTextField`。保留全部题目与提交逻辑。
- **计划设置**：表单分组卡（目标院校、考试科目、每日时长、课表），`RowGroup` + `ListRow`；保存按钮固定在底部安全区。
- **AI 面谈**：顶部返回 + 进度；消息气泡（用户 `primaryContainer`，AI `surface` + 描边）；快捷回复用胶囊；底部输入条 `imePadding`；发送/重试按钮规格统一；不改动对话状态机。
- **规划文档**：排版驱动的阅读页：最大宽度 720dp、正文 14/24、章节标题 18/26、小节标题 16/22；去掉装饰性卡片与多余分隔；顶部吸顶返回 + 文档名；章节目录用一行横向胶囊。
- **历史计划**：`ListRow` 列表（版本名、日期、当前生效 `StatusPill`）；点击查看只读文档；空态紧凑。
- **学霸模式**：状态卡（开启/关闭 + 说明）+ `RowGroup`（白名单、严格程度、紧急退出次数）+ 主按钮；保留现有能力与确认弹窗。
- **白名单选择**：搜索框 + 应用行（图标、名称、`Switch`）；底部“保存”主按钮；不改动已选逻辑。
- **账号**：`SubPageHeader("账号")` + 资料行、退出登录（`SecondaryButton` 危险色描边）、注销（`TextAction` 危险色）；确认弹窗统一规格。

## 五、交互与状态

- **加载**：统计、计划、文档、历史使用 `SkeletonBlock`；不得用整屏转圈（登录检查过场除外）。
- **空态**：一句话 + 一个动作；不再出现两段以上解释。
- **错误**：Snackbar（`SnackbarHost` 统一加在根 `Scaffold`），文案“操作失败，请重试”；需要用户决策的才用 Dialog。
- **二次确认**：仅用于删除、注销、覆盖恢复、严格模式退出；标题为动作本身，正文说明后果，主按钮用危险色。
- **手势**：任务左滑顺延、右滑完成保留；滑动阈值不变；提供可见的“开始”按钮，避免手势成为唯一入口。
- **键盘**：所有含输入框的页面/弹层加 `imePadding`；弹层高度不超过 90%。
- **返回**：计时运行中仍拦截；深层页返回行为不变。
- **主题切换**：即时生效，无重启；深色下检查所有渐变卡白字对比度 ≥ 4.5:1。

## 六、文案规范

- 删除实现说明与元叙述（“逐条可对账”“不随小结范围变化”“筛选作用于…”）；需要解释时放入 `InfoAction`。
- 不再使用 emoji 作为图标或单位；番茄用 `AppIcons.Timer` 或文字“番茄”。
- 数字与单位之间不留空格：`3h20m`、`0/2`、`25 分钟`（中文单位前保留一个空格）。
- 按钮动词开头：`开始专注`、`添加任务`、`保存`、`重试`；不使用“确定/好的”。
- 统一中文标点：全角逗号句号；英文缩写与数字用半角。
- 空态标题 ≤ 12 字，说明 ≤ 24 字。

## 七、可访问性

- 所有可点击元素触达区域 ≥ 48×48dp；`ListRow` 整行可点。
- 图标按钮必须有 `contentDescription`；装饰图标置 `null`。
- 支持系统字体放大 1.3×：所有文本行不设固定高度；按钮文字允许两行，不缩小字号。
- 深浅模式对比度：正文 ≥ 4.5:1，大数字 ≥ 3:1。
- 进度条同时提供文字数值，不单靠颜色表达状态。

## 八、实施顺序

1. **地基**：`Type.kt`、`Color.kt`、`Theme.kt`、`Components.kt`、新建 `Controls.kt`、`AppNav.kt` 底栏与 FAB。此步结束后全 App 编译通过，但页面尚未迁移。
2. **今日**：`HomeScreen`、`CountdownCard`、`TaskListSection`、`ReviewCard`。
3. **专注**：`FocusScreen`（空闲态与运行态）。
4. **计划**：`PlanScreen`、`PlanDocumentScreen`、`PlanHistoryScreen`。
5. **统计**：`StatsScreen`、`Charts.kt` 卡片容器、移除 `WeeklyReport` 独立卡（逻辑保留复用）。
6. **我的**：`MineScreen`。
7. **二级页**：`LoginScreen`、`OnboardingScreen`、`PlanSetupScreen`、`PlanInterviewScreen`、`PlanDraftPreview`、`SuperModeScreen`、`AppPickerScreen`、`AccountScreen`、`MainActivity` 过场。
8. **清扫**：全仓 grep 私有卡片/胶囊/按钮/emoji/阴影，删除遗留；跑构建与测试；逐页截图核对。

每步独立提交，便于回滚；页面迁移不跨步混在一起。

## 九、验收标准

1. `./gradlew :app:compileDebugKotlin` 通过。
2. `./gradlew :app:testDebugUnitTest` 通过，现有 10 个测试类不回归。
3. 底栏为 4 tab + 中央 FAB；不存在被遮挡或禁用的“专注”标签；360dp 宽度与 1.3× 字体下不裁切。
4. 五个主 tab 与所有二级页均使用 `PageHeader/SubPageHeader`；页面文件中不再出现 `headlineLarge`、`displayMedium`、`displaySmall`。
5. 页面文件中不再出现 `shadowElevation`、`tonalElevation`、`RoundedCornerShape(999.dp)`（胶囊仅存在于组件层）、手写 `fontWeight`（组件层除外）。
6. 页面文件中不再出现 emoji；不再直接使用 M3 `FilterChip`，单选项统一 `SegmentedChips` 或带选中态的 `ListRow`。
7. 深浅两套截图覆盖：今日、专注（空闲/运行）、计划（无计划/有计划）、统计、我的、登录、引导、面谈、文档。
8. 功能回归：任务完成与撤销、左滑顺延、开始专注/暂停/继续/结束、计划项勾选与撤销、主题切换、统计范围切换、登录/退出、学霸模式开关均保持原行为。
9. 除 UI 文件外，`data/`、`service/`、`timer/`、`util/` 无 diff；路由常量与 ViewModel 无行为变化。

## 十、风险与取舍

- **大文件重排**：`MineScreen` 1207 行、`HomeScreen` 1181 行、`FocusScreen` 1013 行。迁移时只动呈现层，不碰状态与回调；按节拆分私有 Composable，避免一次重写。
- **手势回归**：`TaskListSection` 的滑动逻辑脆弱，迁移时保留原有 `SwipeToDismissBox` 参数，只替换行内容。
- **字体放大**：全面使用流式高度，移除固定行高；1.3× 验收。
- **深色对比**：渐变卡统一白字；珊瑚 CTA 在深色下改用 `#B3321F → #8F2717`，不再使用白字对比不足的 `CoralRedNight`。
- **信息删减**：删除的长说明可能让新用户困惑，统一收进 `InfoAction`；首屏只保留一句话。
- **底部 FAB 遮挡内容**：所有主列表底部额外预留 72dp + 导航栏 inset；FAB 只在 4 tab 与专注空闲态出现。
- **工作量**：全量迁移约 16 个 UI 文件；按页面分步提交，任何一步都可独立回滚。

## 明确不做

- 不做新主题色/新插画/新 Logo。
- 不引入第三方 UI 库、图标库或动画库。
- 不改动平板专属布局结构。
- 不改动任何业务流程、数据口径与接口。
- 不新增页面或路由。
