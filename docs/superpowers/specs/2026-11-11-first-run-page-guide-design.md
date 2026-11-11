# 首次使用分页引导设计

**日期：** 2026-11-11
**状态：** 待实现
**目标：** 新用户第一次进入五个主 tab 时，各看到一段 2–3 步的高亮说明；完成或跳过后不再自动出现，可从「我的」重看。

## 1. 背景与问题

现有首次问卷（`OnboardingScreen`）只收集备考档案：考试目标、科目、作息、课表和固定占用。它是生成计划的前置问卷，不解释软件本身怎么用。新用户填完问卷后直接落到计划页或今日页，面对倒计时、AI 计划、今日待办、专注计时和统计图表，没有任何说明。

首页另有一个只在 `onboardingDone = false` 时出现的一次性「欢迎来到研钟」弹窗，但它只讲倒计时节点，和本次要做的逐页引导内容重复，且弹窗本身也缺少对主流程的解释。

结果是新用户需要自己猜：计划怎么来、今日待办怎么操作、底部中间的按钮是什么、统计数据从哪里产生。

## 2. 目标与非目标

**目标**

- 五个主 tab（今日 / 计划 / 专注 / 统计 / 我的）各配一套 2–3 步高亮引导，第一次进入该页时自动出现一次。
- 完成、跳过、系统返回都记为已看；已看状态只存本机 DataStore，不进入云同步。
- 所有用户（含老用户升级）各页首次进入都会看到一次，不做新旧用户区分。
- 「我的 → 账号与档案」提供「新手引导」重看入口：重置全部标记并回到今日重新开始。
- 引导只高亮当前页首屏可见的锚点，不自动切换 tab、不自动滚动页面。
- 删除旧的「欢迎来到研钟」一次性弹窗，倒计时节点说明并入今日引导第一步。

**非目标**

- 不改问卷、AI 面谈、计划生成、同步协议、计时规则和统计口径。
- 不为计划面谈、备考档案、账号、个人资料、学霸模式、白名单等深层页做引导。
- 不做远程配置、A/B 实验、埋点上报和多语言。
- 不做可点击的交互式教学（强制用户点某个按钮才能继续）；引导只做说明。

## 3. 行为规则

### 3.1 页面与已看状态

五个页面键固定为：

| 键 | 页面 | 路由 |
| --- | --- | --- |
| `home` | 今日 | `Routes.HOME` |
| `plan` | 计划 | `Routes.PLAN` |
| `focus` | 专注 | `Routes.FOCUS` |
| `stats` | 统计 | `Routes.STATS` |
| `mine` | 我的 | `Routes.MINE` |

已看集合存本地 settings DataStore，键名 `guide_seen_pages`，类型为字符串集合；不写入 `AppSettings`，不进入 `SettingsRepository.syncMapOf()` / `applySyncMap()`，因此不同步云端。已看状态按设备记，不区分账号；退出登录不清理。

### 3.2 自动触发条件

必须同时满足：

1. 当前路由是上表五个主 tab 之一。
2. 当前不在深层页（沿用 `YanZhongAppRoot` 现有 `deepPage` 判定）。
3. 该页不在已看集合里。
4. 页面已就绪：计划页的展示状态不是 `LOADING`；今日页已有首帧数据；专注页计时状态为 `IDLE`；统计页和我的页首帧数据已到。
5. 引导层当前不可见。

页面就绪后等待锚点布局。首步锚点在 1.5 秒内仍未登记时，不展示、也不标记，下次进入该页再试。

### 3.3 步骤与结束语义

- 每一步只高亮一个锚点；锚点未登记的步骤直接跳过。
- 若整页步骤都找不到锚点，不展示、不标记。
- 点击「下一步」进入下一步；最后一步按钮文案为「知道了」。
- 点击「跳过」、按系统返回、点击「知道了」都会把当前页写入已看集合并关闭引导。
- 引导期间遮罩拦截所有触摸，不能点 tab、不能点页面内容；系统返回键可用。
- 旋转或进程重建导致引导重来时不写已看标记；只有主动结束（完成 / 跳过 / 返回）才写入。
- 若引导已显示后页面转为不就绪（例如专注计时被外部拉起），关闭引导；已经展示过至少一步时写入已看，否则不写。

### 3.4 重看入口

「我的 → 账号与档案」分组新增一行：

- 标题：`新手引导`
- 副标题：`重看各页的功能说明`
- 图标：`AppIcons.Info`

点击后弹二次确认：

- 标题：`重看新手引导？`
- 正文：`各页的功能说明会重新出现一遍。`
- 确认按钮：`重看`；取消按钮：`取消`

确认后清空已看集合，并按现有 tab 导航规则切到今日页，今日引导随即出现。之后用户访问计划、专注、统计、我的时，各自再出现一次。`AppNav.kt` 里的 `navigateTab` 当前是私有函数，需要提升为 `internal` 供「我的」页复用，避免另写一套 popUpTo / restoreState 规则。

### 3.5 旧弹窗移除

删除首页的 `OnboardingCard` 及其状态：

- `HomeScreen` 中的 `showOnboarding` 状态、`LaunchedEffect(state.onboardingDone)`、`OnboardingCard` 调用与 `OnboardingCard` 组件本体。
- `HomeViewModel` 中 `onboardingDone` 字段映射与 `dismissOnboarding()`。
- `HomeUiState` 中的 `onboardingDone` 字段与对应构造参数。
- `SettingsRepository` 中的 `ONBOARDING` 键、`AppSettings.onboardingDone`、`setOnboardingDone()`。

服务端档案接口里的 `onboardingDoneAt`（`GET /profile` 返回字段）与本次无关，保持不动。老用户 DataStore 里遗留的 `onboarding_done` 键不迁移、不读取，DataStore 会自然忽略未知键。

## 4. 交互与视觉

### 4.1 层级与遮罩

- 引导层挂在 `YanZhongAppRoot` 最上层，覆盖底部导航 / 左侧导航栏 / 中央专注 FAB / 所有页面内容；现有深链弹窗和更新弹窗属于独立 Window，仍可盖在引导之上。
- 全屏遮罩颜色为黑色 72% 不透明度；高亮区为圆角 16dp 的挖空，外沿 2dp 主色描边。
- 高亮区在目标矩形基础上四周外扩 8dp，并夹在屏幕安全区内。
- 遮罩用四个矩形（上 / 下 / 左 / 右）绘制，不依赖 `BlendMode.Clear`，避免图层合成差异。

### 4.2 说明卡

- 使用 `Surface` + `MaterialTheme.shapes.large` + `outlineVariant` 1dp 描边，零阴影；宽度不超过 320dp，左右至少留 20dp。
- 内容顺序：标题（`titleMedium`）、正文（`bodyMedium`）、步骤计数（`labelSmall`，如 `第 2 / 3 步`）、按钮行。
- 按钮行：左侧 `跳过`（`TextAction`），右侧 `下一步` / `知道了`（`PrimaryButton`，高度 48dp）。
- 位置：目标在屏幕上半部分时放目标下方，否则放目标上方；水平方向与目标居中，夹在 20dp 页边距内；垂直方向避开状态栏和导航栏安全区。
- 目标占满屏幕高度（如整个导航栏）时，说明卡放在剩余空间较大的一侧。

### 4.3 动效与主题

- 遮罩淡入 180ms；高亮框的 left / top / right / bottom 四个 Float 各自 `animateFloatAsState` 220ms；步骤文字交叉淡入 140ms。
- 深浅色主题共用同一套遮罩；说明卡颜色全部取自 `MaterialTheme`。
- 不做震动反馈。

### 4.4 无障碍

- 遮罩本身不获取语义焦点；说明卡标题和正文可被读屏连续朗读。
- `跳过`、`下一步`、`知道了` 都有明确文本，不依赖图标。
- 高亮框只做视觉提示，不承担信息表达；所有信息都在说明卡正文里。

## 5. 每页步骤与文案

锚点 id 约定：

| id | 位置 |
| --- | --- |
| `nav_bar` | 手机底部导航整体（含中央专注 FAB）；平板左侧导航栏整体（含顶部专注 FAB） |
| `home_countdown` | 今日页倒计时卡 |
| `home_progress` | 今日页「今日进度」卡（有生效计划时） |
| `home_plan_guide` | 今日页「还没有学习计划」引导卡（无生效计划时） |
| `plan_action_card` | 计划页「开始制定」空态卡（无计划时） |
| `plan_history_action` | 计划页右上角历史计划图标按钮 |
| `plan_overview` | 计划页概览卡（当前阶段 / 总进度） |
| `plan_primary_action` | 计划页「调整档案并重新生成」主按钮 |
| `focus_start` | 专注页主开始按钮 |
| `focus_task_row` | 专注页「选择任务」行 |
| `focus_mode` | 专注页模式切换（番茄钟 / 正计时 / 倒计时） |
| `stats_range` | 统计页时间范围切换 |
| `stats_kpi` | 统计页 KPI 横幅 |
| `mine_header` | 我的页头部个人卡 |
| `mine_account_group` | 我的页「账号与档案」分组 |
| `mine_guide_row` | 我的页「新手引导」行 |

### 5.1 今日

| 步骤 | 条件 | 锚点 | 标题 | 正文 |
| --- | --- | --- | --- | --- |
| 1 | 通用 | `home_countdown` | 考试倒计时 | 左右滑可以切换节点；点「添加节点」或「管理」记下模考、报名、复试这些日子。 |
| 2 | 有生效计划 | `home_progress` | 今日进度 | 今天的完成情况和本周目标都在这张卡里。下面的今日待办右滑完成、左滑顺延，点「开始」直接开专注。 |
| 2 | 无生效计划 | `home_plan_guide` | 先有一份计划 | 还没有计划时，这里会带你去和 AI 排一份；有了计划，今日待办会按天送到这里。 |
| 3 | 通用 | `nav_bar` | 主入口 | 今日、计划、统计、我的在底部；中间这个按钮随时开专注。 |

平板横屏时导航栏在左侧，第三步文案改为：`今日、计划、统计、我的在左侧；上面的专注按钮随时开钟。` 同一页只出现一套文案，按 `isTablet()` 选择。

### 5.2 计划

| 步骤 | 条件 | 锚点 | 标题 | 正文 |
| --- | --- | --- | --- | --- |
| 1 | 无生效计划 | `plan_action_card` | 让 AI 排计划 | 点「开始制定」，和 AI 聊几分钟，它会按你的档案排一份全程计划。 |
| 1 | 有生效计划 | `plan_overview` | 全程计划 | 当前阶段、总进度和每天的安排都从这张卡往下看。 |
| 2 | 无生效计划 | `plan_history_action` | 历史版本 | 右上角可以回看以前生成的计划版本。 |
| 2 | 有生效计划 | `plan_primary_action` | 改档案或调近期 | 档案变了（科目、课表、目标）用这里重新生成；临时有事就用下面的「调整近期计划」，说一句就重排。 |

### 5.3 专注

仅在空闲态展示。

| 步骤 | 锚点 | 标题 | 正文 |
| --- | --- | --- | --- |
| 1 | `focus_start` | 直接开始 | 点这里开一次自由专注，不用先选任务。 |
| 2 | `focus_task_row` | 记到任务上 | 想把这次专注记到某个任务上，先「选择任务」，番茄数会记进那个任务。 |
| 3 | `focus_mode` | 三种计时 | 番茄钟、正计时、倒计时；番茄方案和更多设置在下面。 |

### 5.4 统计

| 步骤 | 锚点 | 标题 | 正文 |
| --- | --- | --- | --- |
| 1 | `stats_range` | 先选范围 | 今日、本周、本月、累计，下面的数字跟着变。 |
| 2 | `stats_kpi` | 四个关键数 | 净专注、番茄数、任务完成率、连续打卡都在这；往下还有科目占比和长期趋势。 |

### 5.5 我的

| 步骤 | 锚点 | 标题 | 正文 |
| --- | --- | --- | --- |
| 1 | `mine_header` | 个人资料 | 点头像和昵称可以改个人资料；账号和云端数据在下面这一组。 |
| 2 | `mine_account_group` | 账号与档案 | 备考档案变了（科目、课表、目标）从这里改，之后可以重新生成计划。 |
| 3 | `mine_guide_row` | 随时重看 | 以后想再看这套引导，点这里重置。 |

## 6. 技术设计

### 6.1 新增文件

`YanZhong/app/src/main/java/com/yanzhong/app/ui/guide/`

| 文件 | 职责 |
| --- | --- |
| `GuideSpec.kt` | `GuidePage` 枚举、`GuideStep` / `GuidePageSpec` 数据类、各页步骤构造纯函数 |
| `GuideTargets.kt` | `GuideTargetRegistry`、`LocalGuideTargets`、`GuideAnchor` 组件 |
| `GuideController.kt` | 当前页、冻结的步骤快照、步进、显示判定、已看读写 |
| `GuideOverlay.kt` | 遮罩、挖空、说明卡、按钮、返回键处理 |
| `GuideSession.kt` | 根层组合本地：页面声明 spec、根层观察并驱动 controller |

步骤构造保持纯函数，输入是页面状态，输出 `GuidePageSpec`，便于 JVM 单测：

```kotlin
fun homeGuide(hasActivePlan: Boolean): GuidePageSpec
fun planGuide(hasPlan: Boolean): GuidePageSpec
fun focusGuide(): GuidePageSpec
fun statsGuide(): GuidePageSpec
fun mineGuide(): GuidePageSpec
```

### 6.2 锚点登记

- `GuideAnchor(id, modifier) { content }` 在 `Modifier.onGloballyPositioned` 里把 `boundsInWindow()` 写入注册表；`DisposableEffect(id)` 在离开组合时移除。
- 说明卡摆放时读取目标矩形，用遮罩层自身的 `boundsInWindow()` 原点换算成局部坐标。
- 同一个 id 在手机和平板上只会有一种实现同时存在（底部导航或左侧导航栏），不会重复登记。
- 只给首屏可见元素加锚点。LazyColumn 中滚出屏幕的元素被销毁后自动移除，对应步骤会按 3.3 跳过。

### 6.3 根层驱动

`YanZhongAppRoot` 中：

- 新增 `GuideSession`，由根 `remember` 创建，向子树提供 `LocalGuideTargets` 和页面 spec 注册入口。
- 在根 `Box` 的最后放置 `GuideOverlay`，位于 `Scaffold`、导航栏和 `RunningTimerPill` 之上。
- 复用现有 `currentRoute` 与 `deepPage` 判定，只在这五个 tab 路由上允许触发。
- 已看集合从 `app.settingsRepo.guideSeenPages` 收集；标记和重置通过 suspend 方法写回。

每个主页面只做两件事：

1. 用 `ProvideGuideSpec(page, ready, steps)` 声明本页步骤；状态变化时重新计算（有/无计划、专注空闲/运行）。
2. 给锚点元素包 `GuideAnchor("...")`。

### 6.4 已看状态存储

`SettingsRepository` 增加：

```kotlin
val guideSeenPages: Flow<Set<String>>
suspend fun markGuideSeen(page: String)
suspend fun resetGuides()
```

使用 `stringSetPreferencesKey("guide_seen_pages")`；读空为 `emptySet()`。不加入 `AppSettings`，不进入云端同步映射，退出登录不清理。

### 6.5 状态与失败处理

- 页面 spec 声明时带 `ready`；未就绪不触发。今日页的 ready 需要新增 `HomeUiState.loaded`：`stateIn` 的初始值不算就绪，第一次真实合并流产出后才置 true，避免「先无计划、后有计划」的两步文案在半加载状态闪出。计划页的 ready 取展示状态 ≠ `LOADING`；其余三页在首帧组合并延迟 300ms 后视为就绪。
- 触发后等待首步锚点，超时 1.5s 放弃本次展示，不写已看。
- 展示时冻结步骤列表，避免页面状态在引导中途变化导致步骤跳变。
- 当前步骤锚点消失时跳到下一个仍有锚点的步骤；全部消失则关闭并按 3.3 决定是否写已看。
- 引导可见时重新进入同一页面或切换 tab（仅系统返回可能触发）按「跳过」处理。

## 7. 测试

### 7.1 JVM 单元测试

新增 `app/src/test/java/com/yanzhong/app/ui/guide/`：

- `GuideSpecTest`：今日有/无计划、计划有/无计划、专注/统计/我的的步骤数与锚点 id 正确；每页锚点 id 都来自约定集合。
- `GuideEligibilityTest`：路由为五个 tab 且未已看、页面就绪时允许展示；深层页、已看、未就绪、专注运行中不允许。
- `GuideStepNavigatorTest`：缺失锚点的步骤被跳过；全部缺失时返回无步骤；最后一步的按钮语义为完成。

### 7.2 构建与手工验收

构建用本机可用的 PowerShell 方式：

```powershell
powershell -Command "cd F:\kaoyan-app-prd\YanZhong; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=GBK' --console=plain"
```

模拟器手工验收清单：

1. 全新安装并完成问卷后落到计划页：计划页引导只出现一次；完成 / 跳过 / 返回三种结束方式都能记录已看。
2. 逐页首次进入今日、专注、统计、我的：各自出现一次；之后切换 tab 不再出现。
3. 今日在有计划和没有计划两种状态下，第二步锚点不同且文案正确。
4. 专注计时进行中进入专注页：不出现引导；计时结束后进入才出现。
5. 「我的 → 新手引导」确认重看：跳回今日并出现今日引导；再访问其他页会重新出现。
6. 老用户升级场景：升级后各页首次进入同样出现一次。
7. 深色模式、平板横屏（左侧导航栏）、小屏手机：遮罩覆盖完整，说明卡不越界，导航栏高亮位置正确。
8. 引导期间点击页面和 tab 无响应，系统返回可以跳过。

## 8. 风险与边界

- **首屏限制**：只高亮首屏可见元素，屏幕外内容只靠文案带一句。避免为此引入自动滚动和滚动状态耦合。
- **LazyColumn 回收**：滚出屏幕的锚点会消失，步骤自动跳过；每页锚点都选在首屏，正常情况不受影响。
- **弹窗叠加**：更新弹窗是独立 Window，可能盖在引导上；关闭后引导继续，不写已看。
- **设备级已看**：同一设备换账号不会重新出现引导；若以后需要按账号区分，再扩展存储键。
- **老弹窗移除的兼容性**：只删本地 `onboarding_done` 的读写，不改服务端档案接口；遗留键自然忽略。
