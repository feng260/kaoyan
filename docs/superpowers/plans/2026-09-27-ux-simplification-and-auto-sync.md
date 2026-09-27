# 主要页面体验简化与自动同步实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不修改服务端接口的前提下，把“我的”页收敛为「同步状态 + 常用偏好 + 科目管理 + 一个次级入口」，让登录后自动同步对普通用户零配置，并定点修正首页、专注页、统计页的信息层级与文案。

**Architecture:** 同步能力已存在（`DataSyncer` 增量推拉、设置 KV、WebSocket 通知、全量备份）。本轮只做两件事：① 修复 `DataSyncer` 的协程生命周期与跨账号水位归属，把“上次同步时间”“用户名”落到 `TokenStore`（DataStore）以获得响应式与持久化；② 把技术性入口从 `MineScreen` 迁到新页面 `MineDataScreen`，`MineViewModel` 通过 `combine`（5 流）把账号、同步状态与既有学习档案合成到 `MineUiState`，UI 层只负责渲染。纯格式化逻辑抽到 `MineFormat.kt` 以便单测覆盖。

**Tech Stack:** Kotlin 2.0, Jetpack Compose + Material 3, DataStore Preferences, Room, Kotlin Coroutines/Flow (`combine`/`stateIn`/`Mutex`/`debounce`), Retrofit, JUnit 4, Gradle Android Plugin。

**工作目录（所有 Gradle 命令）:** `F:\kaoyan-app-prd\YanZhong`

**服务端:** 无改动。

---

## 文件变更地图

- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/DataSyncer.kt`
  - 汇总长期协程到父 Job 并支持取消；新增水位归属校验与重置；同步成功后持久化上次同步时间。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/TokenStore.kt`
  - 新增用户名读写与响应式订阅；新增上次同步时间订阅；`clearAll()` 一并清除用户名。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/cloud/CloudSyncViewModel.kt`
  - 登录成功保存用户名；退出登录清除（由 `clearAll` 承担）。
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineFormat.kt`
  - 同步时间文案、头部科目副标题、同步失败可理解措辞三个纯函数。
- Create: `YanZhong/app/src/test/java/com/yanzhong/app/ui/mine/MineFormatTest.kt`
  - 覆盖三个纯函数的边界（0 值、跨天、超 4 科、各类错误串）。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineViewModel.kt`
  - `MineUiState` 增加 `loggedIn`/`username`/`lastSyncAt`/`sync`，`uiState` 由 3 流扩为 5 流；新增 `syncNow()`。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt`
  - 头部改为动态用户名 + 动态科目；新增同步状态卡；把账号、备份、导入导出、注销、服务器相关控件迁出到 `MineDataScreen`。
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineDataScreen.kt`
  - 数据与账号次级页：云端同步入口、手动备份/恢复、文件导入导出、WebDAV、账号、服务器地址（默认折叠）。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt`
  - 新增 `Routes.DATA`，注册 `MineDataScreen`，把 `Routes.DATA` 纳入 `deepPage` 判定。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/home/HomeScreen.kt`
  - 复盘卡移到待办与快速开始之后；页面头部右侧增加“＋ 添加任务”；`TodayRhythmCard` 默认收起为一行概览。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt`
  - 空闲态把今日任务提到首屏；模式/方案收敛为紧凑选择行；把开关收进“更多设置”折叠区；非严格模式结束操作改文案。
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/stats/StatsScreen.kt`
  - 筛选行改“小结范围”并加口径说明；新增“长期趋势（不随小结范围变化）”分组标题；统一图表标题窗口措辞；累计卡改“累计纪录”。

---

## 阶段一：同步可靠性修复

### Task 1: 修复 `DataSyncer` 协程生命周期与水位归属

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/DataSyncer.kt`

**背景（当前缺陷）:** `start()`（第 51-99 行）每次调用新开 6 个长期协程；`stop()`（第 110-113 行）只把 `started` 置否，协程继续运行。反复“退出登录 → 重新登录”会累积多份 15 分钟周期循环，同步频率成倍增加。同时 `SINCE_KEY`/`SETTINGS_SYNC_KEY` 与 `lastAppliedRemote` 没有账号归属，换成另一个账号后可能漏拉或被他账号设置覆盖。

**设计说明（对 spec 的实现细化）:** 设计稿写的是“在 `CloudSyncViewModel.login` 的 `previousAccount != resp.user.guid` 分支里重置水位”。但退出登录会清空 `KEY_ACCOUNT_GUID`，之后登录另一个账号时 `previousAccount` 为 `null`，该分支不触发，仍会串号。因此改用**在水位侧记录归属账号**的方式：`DataSyncer` 持久化 `WATERMARK_ACCOUNT_KEY`，每轮同步前校验当前账号与水位归属是否一致，不一致才清空。这样「同账号重登保留水位、跨账号（含先登出再登入他号）重置水位」都成立，且不依赖调用顺序。

- [ ] **Step 1: 增加 Job 容器与水位归属常量**

在 `DataSyncer` 字段区（`@Volatile private var started = false` 附近）加入：

```kotlin
    /** 长期协程句柄:start() 重建,stop() 取消,避免重复登录累积多份周期循环 */
    private var jobs: List<Job> = emptyList()
```

在 `import kotlinx.coroutines.CoroutineScope` 相邻处补 `import kotlinx.coroutines.Job`。

在 `companion object`（第 162-165 行）加入：

```kotlin
        private const val WATERMARK_ACCOUNT_KEY = "watermark_account"
```

- [ ] **Step 2: 把 6 个协程收进 `jobs` 并在 `stop()` 取消**

将 `start()` 内的 6 个 `scope.launch { ... }` 整体改为赋值给 `jobs`（内容逐字保持，仅外层结构变化）：

```kotlin
    fun start() {
        if (started) return
        started = true
        jobs = listOf(
            scope.launch { syncOnce() }, // 登录后首拉:云端他端改动全量落本机
            scope.launch {
                // 新设备首拉设置:从未推送过(无水位)时才拉,老设备本地为准
                if (prefs.getLong(SETTINGS_SYNC_KEY, 0L) == 0L) pullSettingsOnce()
            },
            scope.launch {
                app.repository.dirtySignal
                    .debounce(3000L)
                    .collect { if (started) syncOnce() }
            },
            scope.launch {
                // 他端写云实时通知 → 立即拉取(1s 防抖:多资源连推只触发一轮)
                app.statusSync.notices
                    .debounce(1000L)
                    .collect { notice ->
                        if (!started) return@collect
                        when (notice) {
                            is SyncNotice.DataChanged -> {
                                if (notice.full) prefs.edit().putLong(SINCE_KEY, 0L).apply()
                                syncOnce()
                            }
                            is SyncNotice.SettingsChanged -> pullSettingsOnce()
                        }
                    }
            },
            scope.launch {
                app.settingsRepo.settings
                    .map { app.settingsRepo.syncMapOf(it) }
                    .distinctUntilChanged()
                    .debounce(5000L)
                    .collect { map ->
                        if (!started) return@collect
                        if (map == lastAppliedRemote) return@collect // 云端应用触发的变更,不回推
                        runCatching {
                            val resp = ApiClient.api().putSettings(PutSettingsReq(map))
                            prefs.edit().putLong(SETTINGS_SYNC_KEY, resp.serverTime).apply()
                        }
                    }
            },
            scope.launch {
                while (true) {
                    delay(15 * 60_000L)
                    if (started) syncOnce()
                }
            }
        )
    }
```

`stop()` 改为：

```kotlin
    fun stop() {
        started = false
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _state.value = SyncState()
    }
```

- [ ] **Step 3: 新增水位归属校验与重置**

在 `pullSettingsOnce()` 之前插入：

```kotlin
    /**
     * 水位归属校验:同步水位属于某个账号。当前登录账号与水位归属不一致时(换号、先登出再登入他号),
     * 清空 `since` 与设置水位、丢弃设置快照,下一轮即全量拉取,避免漏拉或被他账号设置覆盖。
     * 同账号重登时归属一致,水位保留,不重复全量拉取。
     */
    private suspend fun ensureWatermarkOwner() {
        val account = TokenStore.currentAccountGuid() ?: return
        val owner = prefs.getString(WATERMARK_ACCOUNT_KEY, null)
        if (owner == account) return
        prefs.edit()
            .remove(SINCE_KEY)
            .remove(SETTINGS_SYNC_KEY)
            .putString(WATERMARK_ACCOUNT_KEY, account)
            .apply()
        lastAppliedRemote = null
    }
```

在 `syncOnce()` 的 `mutex.withLock {` 内、`if (TokenStore.currentAccess() == null) return@withLock` 之后调用：

```kotlin
            ensureWatermarkOwner()
```

在 `pullSettingsOnce()` 的 `runCatching {` 之前调用：

```kotlin
    private suspend fun pullSettingsOnce() {
        ensureWatermarkOwner()
        runCatching {
            ...
        }
    }
```

- [ ] **Step 4: 同步成功后持久化上次同步时间**

把 `syncOnce()` 的成功分支（第 147-153 行）改为：

```kotlin
            outcome.fold({ applied ->
                val now = System.currentTimeMillis()
                // 持久化:应用重启后"我的"页仍能显示上次同步时间
                TokenStore.saveLastSync(now)
                _state.update {
                    it.copy(
                        running = false, lastSyncAt = now,
                        lastError = null, pushed = pushed, pulled = applied
                    )
                }
            }, { e ->
                _state.update {
                    it.copy(running = false, lastError = e.message ?: "同步失败", pushed = pushed)
                }
            })
```

失败分支不改写 `TokenStore`，保证“上次成功同步时间”不被失败清掉。

- [ ] **Step 5: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

预期：BUILD SUCCESSFUL。确认 `DataSyncer` 中不再有裸露在 `jobs` 之外的长期 `scope.launch`。

- [ ] **Step 6: 提交**

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/data/remote/DataSyncer.kt
git commit -m "fix(app): cancel sync jobs on stop and scope watermarks per account"
```

---

### Task 2: `TokenStore` 支持用户名与上次同步时间

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/data/remote/TokenStore.kt`

**背景:** `saveLastSync` / `currentLastSync`（第 67、107-109 行）已存在但无人调用；没有任何地方保存用户名，“我的”页无法展示账号。`observeLoggedIn()`（第 70-71 行）是现成的响应式写法，照此扩展。

- [ ] **Step 1: 新增用户名 key**

在 key 定义区（第 21-27 行，`KEY_LAST_SYNC` 之后）加入：

```kotlin
    private val KEY_USERNAME = stringPreferencesKey("username")
```

- [ ] **Step 2: 新增读写与响应式订阅**

在 `currentLastSync()`（第 67 行）之后插入：

```kotlin
    /** 当前账号用户名(未登录或尚未保存为 null) */
    suspend fun currentUsername(): String? = context().cloudStore.data.first()[KEY_USERNAME]

    /** 用户名(响应式):未登录时为 "" */
    fun observeUsername(): Flow<String> =
        context().cloudStore.data.map { it[KEY_USERNAME] ?: "" }

    /** 上次成功同步时间(响应式,毫秒);从未同步为 0 */
    fun observeLastSync(): Flow<Long> =
        context().cloudStore.data.map { it[KEY_LAST_SYNC] ?: 0L }

    /** 保存/清除用户名:传 null 或空白即清除 */
    suspend fun saveUsername(name: String?) {
        context().cloudStore.edit { prefs ->
            if (name.isNullOrBlank()) prefs.remove(KEY_USERNAME) else prefs[KEY_USERNAME] = name
        }
    }
```

- [ ] **Step 3: `clearAll()` 一并清除用户名**

```kotlin
    suspend fun clearAll() {
        context().cloudStore.edit {
            it.remove(KEY_ACCESS)
            it.remove(KEY_REFRESH)
            it.remove(KEY_ACCOUNT_GUID)
            it.remove(KEY_USERNAME)
        }
    }
```

`KEY_LAST_SYNC` 刻不清除：退出登录后同步卡不展示时间，重新登录后新一轮成功即覆盖。

- [ ] **Step 4: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

- [ ] **Step 5: 提交**

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/data/remote/TokenStore.kt
git commit -m "feat(app): persist username and expose reactive sync time"
```

---

### Task 3: 登录成功保存用户名

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/cloud/CloudSyncViewModel.kt`

- [ ] **Step 1: `login()` 成功分支写入用户名**

把 `login()` 内的 `result.fold` 成功分支（第 123-131 行）改为（其余行为不变，水位重置已由 `DataSyncer.ensureWatermarkOwner()` 承担，此处不再重复）：

```kotlin
            result.fold({ resp ->
                val previousAccount = TokenStore.currentAccountGuid()
                if (previousAccount != null && previousAccount != resp.user.guid) {
                    repo.clearPlanProjections(previousAccount)
                }
                TokenStore.saveAccountGuid(resp.user.guid)
                TokenStore.saveTokens(resp.tokens.access, resp.tokens.refresh)
                TokenStore.saveUsername(resp.user.username)
                _ui.update { it.copy(busy = false, loggedIn = true, username = resp.user.username) }
                refreshDevices()
            }, { e ->
                _ui.update { it.copy(busy = false, message = "登录失败:${e.userMessage()}") }
            })
```

- [ ] **Step 2: 确认 `logout()` 已覆盖清理**

`logout()`（第 138-145 行）已调用 `TokenStore.clearAll()`，Task 2 扩展后用户名随之清除。确认不需要额外改动，不新增代码。

- [ ] **Step 3: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

- [ ] **Step 4: 提交**

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/cloud/CloudSyncViewModel.kt
git commit -m "feat(app): remember username on login"
```

---

## 阶段二：“我的”页收敛

### Task 4: 抽取 `MineFormat` 纯函数并补单测

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineFormat.kt`
- Create: `YanZhong/app/src/test/java/com/yanzhong/app/ui/mine/MineFormatTest.kt`

- [ ] **Step 1: 写实现**

创建 `MineFormat.kt`（不依赖 Android / Compose，便于纯 JVM 单测）：

```kotlin
package com.yanzhong.app.ui.mine

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 同步时间文案:尚未同步 / 刚刚 / N 分钟前 / 今天 HH:mm / M月d日 HH:mm */
fun formatSyncTime(lastSyncAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (lastSyncAt <= 0L) return "尚未同步"
    val diff = now - lastSyncAt
    if (diff in 0 until 60_000L) return "刚刚"
    if (diff in 0 until 3_600_000L) return "${diff / 60_000L} 分钟前"
    val syncTime = Instant.ofEpochMilli(lastSyncAt).atZone(zone)
    val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = syncTime.format(DateTimeFormatter.ofPattern("HH:mm"))
    return if (syncTime.toLocalDate() == nowDate) "今天 $clock"
    else "${syncTime.monthValue}月${syncTime.dayOfMonth}日 $clock"
}

/** 头部副标题:最多 4 个科目名以 " + " 连接,超出追加 "等 N 科";无科目给占位文案 */
fun formatSubjectSubtitle(names: List<String>): String {
    val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return "尚未设置科目"
    val head = cleaned.take(4).joinToString(" + ")
    return if (cleaned.size > 4) "$head 等 ${cleaned.size} 科" else head
}

/** 同步失败的可理解措辞:不向普通用户暴露 HTTP 状态码与异常类名 */
fun readableSyncError(raw: String?): String {
    val msg = raw.orEmpty()
    if (msg.isBlank()) return "同步遇到问题"
    val lower = msg.lowercase()
    return when {
        "401" in msg || "unauthorized" in lower || "登录" in msg -> "登录已过期，请重新登录"
        "timeout" in lower || "超时" in msg -> "网络较慢，稍后会自动重试"
        "unknownhost" in lower || "unable to resolve" in lower || "failed to connect" in lower ||
            "connect" in lower -> "网络不可用，稍后会自动重试"
        "500" in msg || "502" in msg || "503" in msg || "server" in lower -> "服务器暂时不可用"
        else -> "同步遇到问题"
    }
}
```

- [ ] **Step 2: 写单测**

创建 `MineFormatTest.kt`（JUnit 4，固定 `Asia/Shanghai`，不使用 `System.currentTimeMillis()`）：

```kotlin
package com.yanzhong.app.ui.mine

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class MineFormatTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val base = 1_800_000_000_000L // 固定基准时刻

    @Test fun syncTime_neverSynced() {
        assertEquals("尚未同步", formatSyncTime(0L, base, zone))
    }

    @Test fun syncTime_justNow() {
        assertEquals("刚刚", formatSyncTime(base - 30_000L, base, zone))
    }

    @Test fun syncTime_minutesAgo() {
        assertEquals("5 分钟前", formatSyncTime(base - 5 * 60_000L, base, zone))
    }

    @Test fun syncTime_sameDayUsesToday() {
        val sync = base - 3 * 3_600_000L
        assertEquals("今天 ${formatSyncTime(sync, base, zone).removePrefix("今天 ")}",
            formatSyncTime(sync, base, zone))
        org.junit.Assert.assertTrue(formatSyncTime(sync, base, zone).startsWith("今天 "))
    }

    @Test fun syncTime_crossDayUsesMonthDay() {
        val sync = base - 48 * 3_600_000L
        val text = formatSyncTime(sync, base, zone)
        org.junit.Assert.assertTrue("无 '今天' 前缀: $text", !text.startsWith("今天 "))
        org.junit.Assert.assertTrue("含 '月' 与 '日': $text", text.contains("月") && text.contains("日"))
    }

    @Test fun subjectSubtitle_empty() {
        assertEquals("尚未设置科目", formatSubjectSubtitle(emptyList()))
        assertEquals("尚未设置科目", formatSubjectSubtitle(listOf("  ", "")))
    }

    @Test fun subjectSubtitle_fourOrFewer() {
        assertEquals("数学", formatSubjectSubtitle(listOf("数学")))
        assertEquals("数学 + 英语 + 政治 + 专业课",
            formatSubjectSubtitle(listOf("数学", "英语", "政治", "专业课")))
    }

    @Test fun subjectSubtitle_moreThanFour() {
        assertEquals("数学 + 英语 + 政治 + 专业课 等 5 科",
            formatSubjectSubtitle(listOf("数学", "英语", "政治", "专业课", "第二外语")))
    }

    @Test fun syncError_mapping() {
        assertEquals("同步遇到问题", readableSyncError(null))
        assertEquals("同步遇到问题", readableSyncError(""))
        assertEquals("登录已过期，请重新登录", readableSyncError("HTTP 401 Unauthorized"))
        assertEquals("网络较慢，稍后会自动重试", readableSyncError("SocketTimeoutException: timeout"))
        assertEquals("网络不可用，稍后会自动重试",
            readableSyncError("java.net.UnknownHostException: Unable to resolve host \"api\""))
        assertEquals("网络不可用，稍后会自动重试",
            readableSyncError("Failed to connect to /10.0.2.2:8080"))
        assertEquals("服务器暂时不可用", readableSyncError("HTTP 503 Service Unavailable"))
    }
}
```

- [ ] **Step 3: 跑单测**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.yanzhong.app.ui.mine.MineFormatTest"
```

预期：全部通过。若 `syncTime_sameDayUsesToday` 因基准时刻跨天假设失败，把 `base` 换成该时区当天中午的固定毫秒值后重跑（不得改用 `System.currentTimeMillis()`）。

- [ ] **Step 4: 提交**

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineFormat.kt YanZhong/app/src/test/java/com/yanzhong/app/ui/mine/MineFormatTest.kt
git commit -m "feat(app): add mine page formatting helpers with tests"
```

---

### Task 5: `MineViewModel` 组合账号与同步状态

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineViewModel.kt`

- [ ] **Step 1: 扩展 `MineUiState`**

```kotlin
data class MineUiState(
    val settings: AppSettings = AppSettings(),
    val subjects: List<SubjectEntity> = emptyList(),
    val focusDays: Int = 0,
    val streak: Int = 0,
    val learningProfile: LearningProfile = LearningProfile(),
    /** 是否已登录(决定同步卡三态) */
    val loggedIn: Boolean = false,
    /** 当前账号用户名;已登录但本地未保存时为空串 */
    val username: String = "",
    /** 上次成功同步时间:内存实时值与持久化值取较大者 */
    val lastSyncAt: Long = 0,
    /** 增量同步实时状态 */
    val sync: SyncState = SyncState()
)
```

- [ ] **Step 2: 把 `uiState` 扩为 5 流并合成账号三元组**

`combine` 最多 5 个参数重载，账号三项先内部合成再作为第 4 流传入：

```kotlin
    private data class AccountSnapshot(
        val loggedIn: Boolean,
        val username: String,
        val lastSyncAt: Long
    )

    private val accountFlow: Flow<AccountSnapshot> = combine(
        TokenStore.observeLoggedIn(),
        TokenStore.observeUsername(),
        TokenStore.observeLastSync()
    ) { loggedIn, username, lastSyncAt ->
        AccountSnapshot(loggedIn, username, lastSyncAt)
    }

    val uiState: StateFlow<MineUiState> = combine(
        settingsRepo.settings,
        repo.observeSubjects(),
        repo.observeSessions(),
        accountFlow,
        container.dataSyncer.state
    ) { settings, subjects, sessions, account, sync ->
        val profile = buildLearningProfile(
            sessions = sessions,
            subjects = subjects,
            now = Instant.ofEpochMilli(TimeUtils.now()),
            zone = ZoneId.systemDefault()
        )
        MineUiState(
            settings = settings,
            subjects = subjects,
            focusDays = profile.activeDays,
            streak = profile.streakDays,
            learningProfile = profile,
            loggedIn = account.loggedIn,
            username = account.username,
            lastSyncAt = maxOf(sync.lastSyncAt, account.lastSyncAt),
            sync = sync
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MineUiState())
```

补 import：`com.yanzhong.app.data.remote.SyncState`、`kotlinx.coroutines.flow.Flow`。

- [ ] **Step 3: 新增手动同步入口**

在 `setTheme` 之前插入：

```kotlin
    /** 手动触发一轮增量同步(自动同步已常驻,此处仅用于同步卡"重试/立即同步") */
    fun syncNow() {
        viewModelScope.launch { runCatching { container.dataSyncer.syncOnce() } }
    }
```

- [ ] **Step 4: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

- [ ] **Step 5: 提交**

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineViewModel.kt
git commit -m "feat(app): expose account and sync state on mine page"
```

---

### Task 6: `MineScreen` 头部动态化、新增同步卡、迁出技术入口

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt`

**目标结构（`LazyColumn` item 顺序）:**

1. `sync-card`（新增，置顶）
2. `quick-entries`（保留现有三个快捷入口）
3. `learning-profile`（保留 `LearningProfileCard`）
4. `preferences-heading`（“偏好设置”）+ `theme` + `plans` + `focus-pref`
5. `subjects-heading`（“科目管理”）+ `subjects`
6. `data-entry`（新增，进入 `Routes.DATA`）
7. `about`

移除 `account-heading`/`account` 与 `data-heading`/`backup` 两组 item（内容迁往 `MineDataScreen`，见 Task 7）。

- [ ] **Step 1: 头部改为动态用户名与动态科目**

替换第 201-220 行区域。主标题：

```kotlin
                        Column {
                            Text(
                                when {
                                    !state.loggedIn -> "本机学习档案"
                                    state.username.isNotBlank() -> state.username
                                    else -> "已登录"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                formatSubjectSubtitle(state.subjects.map { it.name }),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.78f)
                            )
                        }
```

两个 `PillTag`（`共专注 X 天` / `连续打卡 X 天`）保持原样不动。

- [ ] **Step 2: 新增同步状态卡 composable**

在文件内（`SectionHeading` 附近）新增私有 composable：

```kotlin
/**
 * 同步状态卡:一张卡承载全部同步感知,用户不必进入任何页面就知道数据是否安全。
 * 未登录 → 说明数据只在本机 + 登录入口;已登录 → 三态(运行中/成功/失败)。
 * 服务器地址、同步频率等实现细节不在本卡出现。
 */
@Composable
private fun SyncStatusCard(
    loggedIn: Boolean,
    username: String,
    lastSyncAt: Long,
    sync: com.yanzhong.app.data.remote.SyncState,
    onLogin: () -> Unit,
    onSyncNow: () -> Unit
) {
    val statusText = when {
        sync.running -> "正在同步…"
        sync.lastError != null -> "同步遇到问题"
        else -> "已同步 · ${formatSyncTime(lastSyncAt, TimeUtils.now())}"
    }
    SectionCard(
        title = if (loggedIn) "自动同步已开启" else "数据只保存在本机",
        subtitle = if (loggedIn) (username.ifBlank { "已登录" }) else null,
        icon = AppIcons.CloudUpload,
        accent = MintGreen
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (loggedIn) {
                    TextButton(
                        onClick = onSyncNow,
                        enabled = !sync.running
                    ) { Text(if (sync.lastError != null) "重试" else "立即同步") }
                }
            }
            if (sync.lastError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    readableSyncError(sync.lastError),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoralRedDeep
                )
            }
            if (!loggedIn) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "登录后自动同步，换设备或重装可恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onLogin,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("登录并开启同步") }
                Spacer(Modifier.height(6.dp))
                Text(
                    "不登录也可以正常使用，数据不会丢失。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

- [ ] **Step 3: 插入同步卡 item 并调整顺序**

在 `LazyColumn` 内、`quick-entries` item 之前插入：

```kotlin
                item(key = "sync-card") {
                    SyncStatusCard(
                        loggedIn = state.loggedIn,
                        username = state.username,
                        lastSyncAt = state.lastSyncAt,
                        sync = state.sync,
                        onLogin = { navController.navigate(Routes.CLOUD) { launchSingleTop = true } },
                        onSyncNow = { vm.syncNow() }
                    )
                }
```

- [ ] **Step 4: 把技术入口替换为“数据与账号”入口项**

删除 `account-heading`、`account`、`data-heading`、`backup` 四个 item，在原位置（科目管理之后、`about` 之前）插入：

```kotlin
                item(key = "data-entry") {
                    SectionCard(
                        title = "数据与账号",
                        subtitle = "云端同步、备份与恢复、导入导出、账号注销",
                        icon = AppIcons.DatabaseBackup,
                        accent = SkyBlue
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navController.navigate(Routes.DATA) { launchSingleTop = true }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("打开数据与账号", modifier = Modifier.weight(1f))
                            Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
```

- [ ] **Step 5: 清理迁出的状态与 launcher**

删除属迁出控件的局部状态与 launcher（这些将在 `MineDataScreen` 中重建）：`deleteSubjectCandidate` **保留**（科目删除仍在主页），删除 `deleteStep`/`deleteConfirmName`/`deletePassword`、`exportLauncher`、`exportAccountLauncher`、`importLauncher`，以及随之不再使用的 import（`rememberLauncherForActivityResult`、`ActivityResultContracts`——若科目相关代码仍需要则保留）。以编译器警告/错误为准清理到无 unused 警告。

- [ ] **Step 6: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

预期：BUILD SUCCESSFUL，`MineScreen.kt` 无未使用符号。注意此时 `Routes.DATA` 尚未定义（Task 7 定义），如编译器报未解析引用，可先完成 Task 7 再统一编译，或临时按 Task 7 Step 1 先加常量。

- [ ] **Step 7: 提交**

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineScreen.kt
git commit -m "refactor(app): simplify mine page and add sync status card"
```

---

### Task 7: 新增 `MineDataScreen` 与路由

**Files:**
- Create: `YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineDataScreen.kt`
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt`

- [ ] **Step 1: 注册路由**

`Routes`（第 56-69 行）加：

```kotlin
    const val DATA = "mine_data"
```

`deepPage` 判定（第 89-90 行）加 `Routes.DATA`：

```kotlin
    val deepPage = currentRoute == Routes.SUPERMODE || currentRoute == Routes.CLOUD ||
        currentRoute == Routes.WEBDAV || currentRoute == Routes.DATA ||
        currentRoute?.startsWith("apppicker") == true
```

`NavHost` 内（`Routes.MINE` 之后）加：

```kotlin
                    composable(Routes.DATA) { MineDataScreen(padding, navController) }
```

并在文件顶部 import 处补 `com.yanzhong.app.ui.mine.MineDataScreen`（若已用全限定名则不必）。

- [ ] **Step 2: 创建页面骨架**

新建 `MineDataScreen.kt`。页面复用两个 ViewModel：`MineViewModel`（导入导出、导出服务器存档、注销账号）与 `CloudSyncViewModel`（手动备份/恢复、手动同步、服务器地址读写）。

```kotlin
package com.yanzhong.app.ui.mine

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yanzhong.app.data.remote.DEFAULT_SERVER_URL
import com.yanzhong.app.data.remote.effectiveServerUrl
import com.yanzhong.app.ui.cloud.CloudSyncViewModel
import com.yanzhong.app.ui.nav.Routes
import com.yanzhong.app.ui.theme.AppIcons
import com.yanzhong.app.ui.theme.CONTENT_MAX_WIDTH
import com.yanzhong.app.ui.theme.MintGreen
import com.yanzhong.app.ui.theme.SectionCard
import com.yanzhong.app.ui.theme.SubPageHeader

/**
 * 数据与账号(次级页):把技术性入口从"我的"主页收拢到这里。
 * 普通用户只需认识"云端同步"与"手动备份"两组;服务器地址属于高级项,默认折叠。
 */
@Composable
fun MineDataScreen(padding: PaddingValues, navController: NavHostController) {
    // ... 见下方 Step 3-6
}
```

`DEFAULT_SERVER_URL` / `effectiveServerUrl()` 的确切包路径与签名，请以 `CloudSyncViewModel.kt`（第 60、78 行）与 `CloudSyncScreen.kt` 现有引用为准，避免猜错。

- [ ] **Step 3: 云端同步入口 + 手动同步**

`SectionCard(title = "云端同步", ...)` 内放一个可点击行 → `navController.navigate(Routes.CLOUD)`；再加一个“立即同步”文字按钮调用 `cloudVm.syncNow()`。

- [ ] **Step 4: 手动备份/恢复**

在“云端同步”之后加 `SectionCard(title = "手动备份")`：

- `Button { cloudVm.backupToCloud() }` → 文案“备份当前设备数据到云端”
- `OutlinedButton { showRestoreConfirm = true }` → 文案“从云端恢复到本机”；点击弹 `AlertDialog` 说明“将用云端数据覆盖本机全部学习记录，且不可撤销”，确认后 `cloudVm.restoreFromCloud()`

两者均以 `cloudUi.busy` 控制 `enabled`。结果通过观察 `cloudUi.message` 变化弹 `Toast`：

```kotlin
    val cloudUi by cloudVm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(cloudUi.message) {
        if (cloudUi.message.isNotBlank()) {
            Toast.makeText(context, cloudUi.message, Toast.LENGTH_SHORT).show()
        }
    }
```

- [ ] **Step 5: 文件导入导出**

`SectionCard(title = "文件导入导出")`，重建三个 SAF launcher（从 `MineScreen` 迁来的同款写法）：

- 导出全量 JSON → `ActivityResultContracts.CreateDocument("application/json")` → `vm.exportToUri(uri) { toast(it) }`
- 批量导入 JSON → `ActivityResultContracts.OpenMultipleDocuments()` → `vm.importFromUris(uris) { toast(it) }`

- [ ] **Step 6: 其他云盘 / 账号 / 服务器地址（高级）**

- `SectionCard(title = "其他云盘")`：行 → `navController.navigate(Routes.WEBDAV)`（文案“WebDAV 网盘同步”）。
- `SectionCard(title = "账号")`：
  - `PolicyLinksRow`（与 `MineScreen` 迁出前用法一致，`prefix = "我们对数据的承诺写在"`）
  - “导出服务器存档” → `CreateDocument("application/json")` → `vm.exportAccountToUri(uri) { toast(it, LENGTH_LONG) }`
  - “注销账号” → 复用迁出的两步确认：`deleteStep` 状态 + 两个 `AlertDialog`（第一步说明后果、第二步用户名+密码 `OutlinedTextField`），确认调用 `vm.deleteAccount(name, pwd) { ok, msg -> toast(msg) }`。实现时把 `MineScreen.kt` 原第 753-826 行的两段弹窗逻辑原样搬迁，不改变校验与文案。
- `SectionCard(title = "服务器地址")`：默认只显示一行“已使用内置服务器”；点击展开后显示当前地址（`cloudUi.serverUrl`）、一个 `OutlinedTextField` + “保存”（`cloudVm.saveServer(url)`）+ “恢复默认”（`cloudVm.resetServer()`），并附一行说明“仅在连接本地或调试服务器时需要修改”。

- [ ] **Step 7: 布局外壳**

根使用 `Column(Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp))`，顶部放 `SubPageHeader(title = "数据与账号", subtitle = "同步、备份与账号操作都在这里", onBack = { navController.popBackStack() })`，底部 `Spacer(Modifier.height(padding.calculateBottomPadding() + 32.dp))`。

- [ ] **Step 8: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

- [ ] **Step 9: 提交**

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/mine/MineDataScreen.kt YanZhong/app/src/main/java/com/yanzhong/app/ui/nav/AppNav.kt
git commit -m "feat(app): add data and account secondary page"
```

---

## 阶段三：首页 / 专注 / 统计 定点改造

### Task 8: 首页顺序与密度

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/home/HomeScreen.kt`

- [ ] **Step 1: 复盘卡移到待办与快速开始之后**

把 `item(key = "review-card")`（第 398-413 行）整块移动到 `item(key = "quick-start")`（第 435-453 行）之后。移动后 `LazyColumn` 顺序为：`page-header` → CountdownCard → `phase-strip` → `rhythm-strip` → `TodayProgress` → `taskListSection` → `quick-start` → `review-card`。`ReviewCard` 的参数与回调逐字不变。

- [ ] **Step 2: 页面头部右侧加“＋ 添加任务”**

`PageHeader`（第 354-357 行）已有 `trailing` 槽位，直接使用，复用现有新增任务回调（`editingTask = null; showTaskEditor = true`）：

```kotlin
                PageHeader(
                    title = "今日",
                    subtitle = dateLabel + (state.phase?.let { " · ${it.phase.name}" } ?: "")
                ) {
                    TextButton(onClick = {
                        editingTask = null
                        showTaskEditor = true
                    }) { Text("＋ 添加任务") }
                }
```

- [ ] **Step 3: 今日节奏默认收起**

在 `TodayRhythmCard`（约第 668 行起）内加本地展开状态，默认收起：

```kotlin
@Composable
private fun TodayRhythmCard(
    tpl: PersonalPlan.DayTemplate,
    rows: List<PersonalPlan.RhythmRow>,
    now: Long,
    focusMinutes: Int = 25
) {
    var expanded by remember { mutableStateOf(false) }
    // 概览行:计划名 + 已完成/总数 进度;点击切换展开
    // 展开后才渲染各时段明细(保持原有行渲染逻辑不变)
}
```

概览行文案用 `"${tpl.name} · 已完成 ${rows.count { it.done }}/${rows.size} 段"`（若现有 `RhythmRow` 没有 `done` 字段，改用现有可用字段拼出等价的进度文案，不新增数据模型）。概览行必须用 `Modifier.clickable { expanded = !expanded }` 可点，并在右侧放 `AppIcons.ChevronDown`/`ChevronUp` 指示。

- [ ] **Step 4: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

- [ ] **Step 5: 提交**

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/home/HomeScreen.kt
git commit -m "refactor(app): surface today tasks on home first screen"
```

---

### Task 9: 专注页空闲态重排与文案

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt`

- [ ] **Step 1: 空闲态把今日任务提到首屏**

在 `IdleContent`（第 152-332 行）中，把 `if (state.todayTasks.isNotEmpty()) { ... }` 整块（第 306-330 行）从末尾移到顶部：紧跟在标题与 `PeerFocusCard` 之后、三种 `ModeChip` 之前。标题下方的副标题由“选一个任务,或者直接开始”保持不变。任务行 `TaskPickRow` 的点击分发逻辑逐字不变。

- [ ] **Step 2: 模式与番茄方案收敛为紧凑选择行**

保持 `ModeChip` 三选一（第 180-184 行）与 POMODORO 分支的 `PlanCard` 行（第 187-208 行）位置在任务列表之后、主按钮之前；不改变 `vm.selectPlan` 与 `vm.quickFocus()` 行为。仅做间距收敛：把 POMODORO 分支上方 `"番茄方案"` 的 `labelLarge` 标题保留，`Spacer` 由 `16.dp` 减为 `8.dp`，使该区在视觉上成为一组紧凑选择行。STOPWATCH / COUNTDOWN 分支保持不变。

- [ ] **Step 3: 开关收进“更多设置”折叠区**

把 POMODORO 分支内的 `SettingSwitchRow("自动串联")`（第 210-215 行）与 `SettingSwitchRow("连续专注")`（第 217-222 行）从主按钮之上移出，改为在三种模式主按钮**之后**统一渲染一个折叠区：

```kotlin
        // 更多设置:默认收起,降低首屏密度;与模式无关的开关集中在此
        var moreExpanded by remember { mutableStateOf(false) }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { moreExpanded = !moreExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("更多设置", modifier = Modifier.weight(1f))
                    Icon(
                        if (moreExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                        contentDescription = null
                    )
                }
                if (moreExpanded) {
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(
                        title = "自动串联",
                        subtitle = "休息结束自动开始下一个番茄",
                        checked = state.settings.autoChain,
                        onChange = { vm.toggleAutoChain(it) }
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(
                        title = "连续专注",
                        subtitle = "跳过休息,继续专注",
                        checked = state.settings.continuousFocus,
                        onChange = { vm.toggleContinuous(it) }
                    )
                    // 若本页存在静音/震动/音效开关,一并移入此处并保持原有 onChange
                }
            }
        }
        Spacer(Modifier.height(20.dp))
```

若 `IdleContent` 中不存在静音/震动/音效开关（它们可能只在计时运行态或“我的”页），则不新增控件，只搬运实际存在的开关。

- [ ] **Step 4: 非严格模式结束操作改文案（规则不变）**

- 第 794-802 行：非严格模式的按钮文案由 `"紧急退出 · 本月剩 $quotaLeft 次"` 改为 `"结束专注"`（严格模式仍进入 `showEmergencyExit`，文案保持“紧急退出”）。为避免混淆，非严格模式的按钮不再显示次数。
- 第 838-844 行确认弹窗：标题改为 `"结束本次专注？"`，正文保留“已专注的时长会记为中断，不计入统计”，删除“紧急退出每月只有 N 次机会……”这半句。
- 第 897-911 行严格模式 `EmergencyExitDialog`：标题保持“紧急退出严格锁定？”，提示中补一句“严格模式下才受次数限制，普通模式可随时结束”，次数提示统一为“本月紧急退出剩余 N 次（仅严格模式）”。
- 第 779-786 行休息时段按钮、第 787-793 行配额用完提示保持原文案不变。

**注意:** 只改字符串常量与提示语，不改动 `vm.endDuringBreak()`、`vm.emergencyExit()`、`AppSettings.EMERGENCY_EXIT_QUOTA`、`quotaLeft` 的计算与消耗逻辑。

- [ ] **Step 5: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

- [ ] **Step 6: 提交**

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/focus/FocusScreen.kt
git commit -m "refactor(app): prioritize start action on focus idle screen"
```

---

### Task 10: 统计页口径标注

**Files:**
- Modify: `YanZhong/app/src/main/java/com/yanzhong/app/ui/stats/StatsScreen.kt`

- [ ] **Step 1: 筛选行加标题与口径说明**

在第 71-84 行的筛选 `item` 内，`Row` 之前加一行小标题“小结范围”，之后加一行说明：

```kotlin
        item {
            Column {
                Text(
                    "小结范围",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatsRange.entries.forEach { range ->
                        FilterChip(
                            selected = state.range == range,
                            onClick = { vm.selectRange(range) },
                            label = { Text(range.label) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "筛选作用于上方小结与科目占比；下方长期图表各自标注统计窗口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
```

- [ ] **Step 2: 新增“长期趋势”分组标题并统一图表标题**

在“科目投入占比”之后、“净专注趋势”之前插入分组标题 item：

```kotlin
        item(key = "trend-heading") {
            Text(
                "长期趋势（不随小结范围变化）",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
```

并把四个图表标题改为（窗口信息前置、措辞统一）：

- 第 124 行 → `ChartCard(title = "净专注趋势 · 近 14 天")`
- 第 130 行 → 保持 `"24 小时时段分布 · 近 30 天"`（已符合格式，确认无改动即可）
- 第 136 行 → `ChartCard(title = "一周节奏 · 周几分布")`（已符合，确认无改动）
- 第 142 行 → 保持 `"打卡热力图 · 近 15 周"`（已符合，确认无改动）

- [ ] **Step 3: 累计卡改名**

第 371 行 `ChartCard(title = "专注纪录 · 累计")` → `ChartCard(title = "累计纪录")`。

- [ ] **Step 4: 编译验证**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

- [ ] **Step 5: 提交**

```powershell
git add YanZhong/app/src/main/java/com/yanzhong/app/ui/stats/StatsScreen.kt
git commit -m "refactor(app): clarify stats range scope and chart windows"
```

---

## 阶段四：验证

### Task 11: 全量验证与验收核对

**Files:**
- 无代码改动（仅验证）

- [ ] **Step 1: 编译与单测**

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
```

工作目录：`F:\kaoyan-app-prd\YanZhong`。预期两项均 BUILD SUCCESSFUL，既有测试（`LearningProfileTest`、`PlanProjectorTest`、`WeeklyReportTest` 等）与新增 `MineFormatTest` 全部通过。

- [ ] **Step 2: 静态核对验收标准**

逐条对照设计稿「验收标准」，用代码检索确认：

1. 空数据 + 未登录：“我的”页无崩溃风险，`SyncStatusCard` 未登录分支文案为“数据只保存在本机”。
2. 登录自动同步：`YanZhongApp` 的 `authState` 联动未被改动（确认第 55-66 行仍在），`DataSyncer.start()` 正常启动。
3. 切换账号不漏拉：确认 `ensureWatermarkOwner()` 在 `syncOnce()` 与 `pullSettingsOnce()` 均被调用。
4. 反复退出登录：确认 `stop()` 已 `jobs.forEach { it.cancel() }`，`start()` 先 `jobs = listOf(...)`。
5. 重启后仍显示上次同步时间：确认 `TokenStore.saveLastSync(now)` 在成功分支，且 `MineViewModel` 用 `maxOf(sync.lastSyncAt, account.lastSyncAt)`。
6. 主页不再出现服务器地址/WebDAV/导出 JSON/批量导入/注销：检索 `MineScreen.kt` 确认无这些字符串与入口。
7. 头部科目与列表一致：确认使用 `formatSubjectSubtitle(state.subjects.map { it.name })`。
8. 首页首屏可见今日待办、头部可添加任务：确认 item 顺序与 `PageHeader(trailing)`。
9. 专注页首屏可直接开始今日任务：确认任务块已移动到 `IdleContent` 顶部。
10. 统计页四种范围下分组与窗口标注清晰。
11. 严格模式次数限制不变、非严格模式文案为“结束专注”：检索确认无计时/配额逻辑改动。
12. `:app:compileDebugKotlin` 通过；`git status` 确认服务端目录（`server/` 或等价路径）无改动。

- [ ] **Step 3: 记录验证结论**

把上述命令的实际输出与核对结果写进本次任务的交付说明。若发现未通过项，回到对应 Task 修正后重跑，不做“看起来没问题”的口头结论。

- [ ] **Step 4: 汇总提交（仅当 Step 2 有遗留小修）**

若无遗留改动则跳过；否则按改动归属的 Task 分别提交，不混提。

---

## 自查记录

- **spec 覆盖:** 设计稿「我的」页结构、头部、同步卡三态、次级页六组、同步行为四项修复、首页四点、专注页四点、统计页五点，均已映射到 Task 1-10；验收标准 12 条在 Task 11 Step 2 逐条对应。
- **对 spec 的唯一实现细化:** 跨账号水位重置由「登录时判断 `previousAccount`」改为「`DataSyncer` 记录水位归属账号并在每轮同步前校验」，原因是登出会清空 `KEY_ACCOUNT_GUID`，登录分支的 `previousAccount != null` 前提不成立；已在 Task 1 中写明理由与行为等价性（同账号重登保留水位、跨账号重置）。
- **占位符扫描:** 无 TBD/TODO；疑似需现场确认处（`DEFAULT_SERVER_URL` 包路径、`RhythmRow` 是否有 `done` 字段、专注页是否存在静音/震动/音效开关）均给出了确认方式与回退处理，不写死猜测值。
- **类型/命名一致性:** `SyncState`（`running`/`lastSyncAt`/`lastError`/`pushed`/`pulled`）、`MineUiState` 新增字段名、`formatSyncTime`/`formatSubjectSubtitle`/`readableSyncError`、`Routes.DATA`、`ensureWatermarkOwner`/`saveUsername`/`observeUsername`/`observeLastSync` 在全文引用一致。
- **服务端:** 全文无服务端改动项。
