# Step 4 个人界面重构验证记录

## 验证日期

2026-09-26

## 变更范围

- `YanZhong/app/src/main/java/com/yanzhong/app/data/stats/LearningProfile.kt`
  - 新增纯 Kotlin 学习档案模型和聚合函数。
  - 统计仅使用 `valid == true` 的番茄会话。
  - 支持累计专注、活跃天数、连续打卡、科目投入、峰值小时和最近七日趋势。
- `YanZhong/app/src/test/java/com/yanzhong/app/data/stats/LearningProfileTest.kt`
  - 新增空数据、无效会话、科目聚合、峰值小时和七日趋势测试。
- `YanZhong/app/src/main/java/com/yanzhong/app/timer/PomodoroEngine.kt`
  - 为 `streakDays` 增加可注入的本地日期参数，默认行为不变。
- `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineViewModel.kt`
  - 组合设置、科目和会话 Flow，向 Mine 页提供 `LearningProfile`。
- `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt`
  - 按“学习档案 → 账号与安全 → 偏好设置 → 数据与其他”组织页面。
  - 保留账号导出、账号注销、主题、番茄方案、专注偏好、科目管理和本地备份导入。

## 执行命令与结果

### Kotlin 编译

命令：

```powershell
./gradlew.bat :app:compileDebugKotlin
```

结果：通过。

```text
BUILD SUCCESSFUL in 14s
16 actionable tasks: 16 up-to-date
```

### 学习档案单元测试

命令：

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "com.yanzhong.app.data.stats.LearningProfileTest"
```

结果：未完成测试断言执行。`compileDebugUnitTestKotlin` 已完成，但 Gradle 测试 worker 在当前 Windows 环境中启动失败：

```text
Could not write standard input to Gradle Test Executor
ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain
```

JVM 崩溃日志进一步显示 `Native memory allocation failed`，且未生成 JUnit XML 报告，因此当前证据不支持“测试断言失败”的判断。随后尝试使用 ASCII Gradle 用户目录并限制 worker：

```powershell
$env:GRADLE_USER_HOME = 'C:\gradle-home'
./gradlew.bat :app:testDebugUnitTest --tests "com.yanzhong.app.data.stats.LearningProfileTest" --offline -Dorg.gradle.workers.max=1
```

该命令触发下载独立的 Gradle 8.9 发行包，按用户要求已停止。未将单元测试标记为通过，用户将使用 Android Studio/IDEA 的本地 Gradle 环境完成打包与测试。

### IDEA 测试结果（2026-09-26 18:32）

用户提供的首次 `测试结果 -YanZhong_app_[testDebugUnitTest].xml` 证明 IDEA 环境已成功运行全部 6 项单元测试：4 项通过、2 项失败。两项失败均为 `LearningProfileTest` 中的测试预期写错：有效会话在 9 点为 25 分钟、10 点为 35 分钟，峰值应为 10；科目同为 60 分钟时，当前名称排序结果为“政治、未分类、英语二”。已修正这两处断言，未修改聚合逻辑。用户随后确认在 IDEA 中重新运行后全部测试通过；此结果依据用户反馈，未提供第二次导出报告。

先前命令行环境中的 worker 启动失败与本次 IDEA 测试断言失败是两次不同的运行，不应混为一谈。

## 源码级验收

- 学习统计统一过滤 `valid == true` 会话。
- 日期和小时均基于 `ZoneId` 转换。
- 最近七日固定返回 7 项，缺失日期补 0。
- 峰值小时按累计分钟排序，同值取较早小时。
- 科目不存在或会话没有科目时归入“未分类”。
- Mine 页头部统计和学习档案均读取共享 `LearningProfile`。
- `StatsViewModel` 未修改。
- 账号注销仍仅在服务端成功回调后清理本地凭证。
- 页面区块顺序已调整为设计要求的主区块顺序。

## 布局检查

已完成源码级检查：学习档案使用权重布局，科目行使用可伸缩文本区域，页面使用现有最大宽度约束和 `LazyColumn`。本次未在模拟器或真机上完成窄屏、大屏截图人工检查，待用户在 IDEA 中打包运行后确认实际渲染效果。

## 当前结论

实现代码和 debug Kotlin 编译已完成。IDEA 首次运行 6 项测试，其中 4 项通过、2 项因错误断言失败；修正断言后用户确认重新运行全部通过。`assembleDebug` 和窄屏、大屏人工布局检查尚未得到验证结果，因此不将它们标记为通过。命令行 Gradle 测试 worker 问题与 IDEA 中已完成的测试分开记录。
