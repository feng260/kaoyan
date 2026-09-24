# 五阶段总览卡 UI 优化设计

日期：2026-09-08
范围：`PlanSections.kt` 的 `PhasesCard`（计划页 → 全程 → 第二张卡），附带 `Phases.kt` 补全 `mainLine` 数据与 `PlanScreen.kt` 调用点适配。

## 目标

解决当前卡片的问题：五行同权重平铺、圆点彼此割裂、长文本（主线/里程碑/占比）堆成文字墙、当前阶段与其它阶段视觉区分弱。

## 设计（已确认）

信息分层 + 当前阶段突出的纵向进度轨道：

1. **纵向连续轨道**：左侧 26dp 序号徽标列，徽标之间用 3dp 竖线（TrackGap）连接，形成从阶段一到阶段五的连续视觉轨道。
2. **三态徽标**：已完成 = `primary.copy(alpha=0.18f)` 底 + `ic_check` 图标；进行中 = `primary` 底 + 白色序号；未开始 = `surfaceVariant` 底 + 序号。
3. **状态胶囊**：阶段名右侧显示「进行中 / 已完成 / 未开始」；进行中用 `primary` 强底色，另附「剩 X 天」胶囊。
4. **当前阶段强调块**：内容整体包入 `primaryContainer.copy(alpha=0.30f)` 圆角容器，内含「第 X / Y 天 · 剩 Z 天」文字 + 4dp 细进度条（进度 = dayIdx/totalDays）。
5. **紧凑信息行**：日期区间 · 周数 · h/周 合并为一行 `labelMedium`。
6. **主线分层**：当前阶段完整展示；其余阶段 `maxLines=2` + Ellipsis（时间/里程碑/占比仍完整可见）。
7. **里程碑块**：弱底色圆角块（沿用 NoteText 风格），完整展示。
8. **占比彩点胶囊**：`alloc` 按「·」拆分为胶囊流（FlowRow），数学/408/英语/政治沿用四科 tagColor 彩点，其余（订正/复盘）用中性色。
9. **状态判定基于 `today`**（不再依赖 current 非空）：备考期外自动全「未开始」或全「已完成」，不出现错误的「进行中」。

## 不变项

- `StudyPhase` 数据结构不变；仅补全阶段 1/3/4 缺失的 `mainLine`（内容取自计划 HTML 第 02 节）。
- 导入 JSON、`PLAN_PACK_VERSION`、ViewModel 均不动。
- 调用点：`PhasesCard(phaseInfo?.phase)` → `PhasesCard(state.today)`（Long 毫秒，卡内转 LocalDate）。

## 验收

- `:app:compileDebugKotlin` 通过；五阶段全部渲染、轨道连续、当前阶段高亮且显示进度与剩余天数。
- 占比胶囊在暑期封闭期（5 个 token）下自动换行不溢出。
