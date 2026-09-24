# 全局界面减密度改造实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变现有业务能力和专注锁定逻辑的前提下，降低首页、计划、专注、统计和我的页面的信息密度，并让首页今日待办明确显示任务时间点。

**Architecture:** 优先复用现有 Compose 页面和业务状态，不改动计时引擎、Room 实体、Repository 或导航行为。通过减少卡片嵌套、统一页面间距、调整信息层级、折叠次要内容和优化窄屏布局完成视觉改造；任务时间使用已有 `TaskEntity.dueAt` 和 `TimeUtils.formatHm()`。

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Navigation Compose, Room, Gradle Kotlin compiler.

---

### Task 1: 首页待办与页面层级

**Files:**
- Modify: `app/src/main/java/com/yanzhong/app/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/yanzhong/app/ui/home/TaskListSection.kt`
- Modify: `app/src/main/java/com/yanzhong/app/ui/home/CountdownCard.kt`

- [ ] **Step 1: 将首页纵向节奏统一为 16dp，并降低底部冗余空间**

将首页 `LazyColumn` 的 `verticalArrangement` 调整为 `Arrangement.spacedBy(16.dp)`，保留横向 16dp；将底部 padding 从 `padding.calculateBottomPadding() + 88.dp` 调整为 `padding.calculateBottomPadding() + 72.dp`，避免底部出现大块空白。

- [ ] **Step 2: 让今日待办时间优先显示**

在 `TaskRow` 的标题下方单独增加时间摘要，优先显示 `TimeUtils.formatHm(task.dueAt)`；没有时间时显示“未设时间”。日期标签保留为次要信息，仅在不是当天或任务过期时显示。时间文本使用 `labelLarge` 或 `bodyMedium`，颜色使用科目色或 `onSurfaceVariant`，避免把时间挤进过多横向标签。

- [ ] **Step 3: 减少任务行内部嵌套和高度**

将任务行内边距统一为水平 16dp、垂直 12dp；标题最多一行，时间和科目放在第二行；重复规则和顺延次数合并到第三行的辅助信息，仅在存在时显示。开始专注按钮保持现有行为，但尺寸控制在 40dp 左右。

- [ ] **Step 4: 将首页节奏信息改成轻量区域**

保留倒计时主卡片，降低阶段条和今日节奏区域的视觉权重。对于今日节奏，默认只显示当前时间段或最多三个学习块，其他内容以“还有 N 个安排”收起提示呈现；不得删除已有数据入口。

- [ ] **Step 5: 压缩首页快速开始区域并保持可操作性**

将快速开始从完整大卡片改为单行主操作区域，保留新建任务和自由专注入口；次要说明使用 `bodySmall`，避免与今日待办争夺视觉焦点。

- [ ] **Step 6: 运行首页相关编译检查**

运行 `./gradlew.bat :app:compileDebugKotlin --no-daemon`，确认首页改造没有引入 Kotlin 或 Compose 编译错误。

### Task 2: 计划页面折叠与卡片减重

**Files:**
- Modify: `app/src/main/java/com/yanzhong/app/ui/plan/PlanScreen.kt`

- [ ] **Step 1: 统一计划页页面间距和底部空间**

将计划页纵向间距统一为 16dp，卡片内部保持 16dp；底部 padding 调整为导航栏上方约 72dp。标题副标题使用 `bodySmall` 或 `labelMedium`，避免头部占用过多高度。

- [ ] **Step 2: 优化顶部 Tab 在窄屏上的显示**

将固定 `TabRow` 改为可横向滚动的紧凑 Tab 容器，确保“军规·资料”等长标签不压缩其他 Tab。保持 selected tab、状态恢复和点击行为不变。

- [ ] **Step 3: 本周视图默认只突出今天**

保持今天默认展开；其他日期默认折叠，并把非今天日期卡片的任务数量、完成状态和首个时间点作为摘要。避免七个日期卡片同时呈现完整内容。

- [ ] **Step 4: 对日模板、全程和资料区域建立折叠层级**

为信息量较大的次要卡片增加页面内折叠状态，默认展开核心内容，默认收起低频内容。折叠标题只保留标题、摘要和箭头，删除重复说明文字；不要修改 ViewModel 数据和编辑行为。

- [ ] **Step 5: 保留计划页任务时间点并统一样式**

继续使用 `TimeUtils.formatHm(task.dueAt)` 显示时间，确保时间与任务标题之间有稳定的视觉层级，不再把时间作为拥挤的尾部标签堆叠。

- [ ] **Step 6: 编译验证计划页**

运行 `./gradlew.bat :app:compileDebugKotlin --no-daemon`，确认计划页改造可编译。

### Task 3: 专注页视觉减密度

**Files:**
- Modify: `app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt`

- [ ] **Step 1: 保留专注锁定行为作为不可变约束**

不得删除或改变 `BackHandler(enabled = timer.phase != Phase.IDLE)`、`FLAG_KEEP_SCREEN_ON`、长按 1.5 秒放弃和二次确认弹窗。

- [ ] **Step 2: 缩短空闲态的设置区域**

将方案卡片和两个开关改为统一的轻量设置区，减少每个设置项的垂直间距；自由专注按钮保持主操作地位，今日任务列表放在其下方。

- [ ] **Step 3: 优化运行态信息层级**

适度降低任务标题、环形进度和辅助说明之间的空隙；保持计时数字突出；将暂停、继续、跳过休息和放弃入口集中在稳定的操作区，避免按钮在窄屏上横向拥挤。

- [ ] **Step 4: 确保自由专注的科目选择不撑高页面**

科目选择使用横向滚动或换行布局，防止科目较多时发生溢出；不改变 `vm.switchSubject()` 行为。

- [ ] **Step 5: 编译验证专注页**

运行 `./gradlew.bat :app:compileDebugKotlin --no-daemon`，确认锁定相关代码未被破坏。

### Task 4: 统计页窄屏布局

**Files:**
- Modify: `app/src/main/java/com/yanzhong/app/ui/stats/StatsScreen.kt`

- [ ] **Step 1: 调整统计页顶部筛选区域**

将时间范围筛选改成横向可滚动 Row，避免多个 `FilterChip` 在小屏幕上挤压或换行造成页面跳动。

- [ ] **Step 2: 将 KPI 改为 2×2 网格**

将“净专注、番茄、任务完成率、连续打卡”从单行改为两列两行，统一单元格高度和间距，确保数字和标签清晰可读。

- [ ] **Step 3: 减轻图表容器视觉重量**

统一图表区域标题、内边距和卡片圆角；保留所有图表数据，只减少重复副标题和多余装饰。

- [ ] **Step 4: 统一统计页底部留白并编译验证**

将底部 padding 调整为约 72dp，纵向卡片间距统一为 16dp，然后运行 `./gradlew.bat :app:compileDebugKotlin --no-daemon`。

### Task 5: 我的页面设置列表化

**Files:**
- Modify: `app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt`

- [ ] **Step 1: 统一页面区块间距和头部层级**

保持横向 16dp，纵向区块间距调整为 16dp；降低用户介绍卡副标题和说明文字的字号权重。

- [ ] **Step 2: 合并专注偏好开关容器**

将四个独立圆角 Surface 改为一个设置列表容器，使用分隔线或稳定的行间距区分开关，保留四个现有 ViewModel 回调。

- [ ] **Step 3: 减轻番茄方案列表**

突出当前方案，其余方案使用轻量行样式；保留点击切换和方案参数显示，不再为每个方案叠加完整卡片背景。

- [ ] **Step 4: 压缩备份与关于区域**

保留导入、导出和版本信息功能；说明文字改为辅助文本，避免低频信息与设置项保持同等视觉权重。

- [ ] **Step 5: 编译验证我的页面**

运行 `./gradlew.bat :app:compileDebugKotlin --no-daemon`，确认所有设置行为仍可编译。

### Task 6: 全量检查

**Files:**
- Check: `app/src/main/java/com/yanzhong/app/ui/home/`
- Check: `app/src/main/java/com/yanzhong/app/ui/plan/PlanScreen.kt`
- Check: `app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt`
- Check: `app/src/main/java/com/yanzhong/app/ui/stats/StatsScreen.kt`
- Check: `app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt`

- [ ] **Step 1: 搜索确认关键业务入口未丢失**

确认首页仍保留任务完成、顺延、编辑、开始专注和节点管理；计划页仍保留四个 Tab 和任务编辑入口；专注页仍保留锁定与放弃确认；统计页仍保留四类图表；我的页面仍保留主题、方案、偏好、科目、备份和关于入口。

- [ ] **Step 2: 执行最终 Kotlin 编译**

运行 `./gradlew.bat :app:compileDebugKotlin --no-daemon`。若失败，记录实际缺失依赖或具体错误，不将验证结果表述为成功。

- [ ] **Step 3: 检查改动范围**

确认没有修改计时引擎、前台服务、Room 实体和 Repository；确认没有生成临时文件到用户工作区。
