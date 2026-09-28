package com.yanzhong.app.data.remote

import android.content.Context
import com.yanzhong.app.YanZhongApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FullPullRequests {
    private var requested = 0L
    private var acknowledged = 0L

    @Synchronized fun request() { requested++ }
    @Synchronized fun snapshot(): Long = requested
    @Synchronized fun pendingAt(version: Long): Boolean = version > acknowledged
    @Synchronized fun acknowledge(version: Long) {
        acknowledged = maxOf(acknowledged, version)
    }
    @Synchronized fun reset() {
        requested = 0L
        acknowledged = 0L
    }
    val pending: Boolean get() = pendingAt(snapshot())
}

/** 最近一轮同步结果(云同步页展示用) */
data class SyncState(
    val running: Boolean = false,
    val lastSyncAt: Long = 0,
    val lastError: String? = null,
    val pushed: Int = 0,
    val pulled: Int = 0
)

/**
 * 增量数据同步调度器:登录后常驻,多端数据一致性的客户端半边。
 * - 数据通道:本地变更信号(3s 防抖)/ 周期 15min / 手动 → push 脏行+墓碑 → pull since 水位 → LWW 应用
 * - 设置通道:本地设置变更(5s 防抖)推送 KV;从未推送过(新设备)则登录后首拉云端设置
 * - 未登录静默跳过;失败留待下轮重试(服务端 LWW 幂等,重推安全)
 * - since 水位持久化:重启后从上次进度续传,首登 since=0 即全量拉取
 */
class DataSyncer(private val app: YanZhongApp) {

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val prefs = app.getSharedPreferences("data_syncer", Context.MODE_PRIVATE)

    @Volatile private var started = false
    private val fullPullRequests = FullPullRequests()

    /** 长期协程句柄:start() 重建,stop() 取消,避免重复登录累积多份周期循环 */
    private var jobs: List<Job> = emptyList()

    /** 最近一次从云端应用的设置快照:推送前比对,相同即回声,跳过(防拉取→推送死循环) */
    @Volatile
    private var lastAppliedRemote: Map<String, kotlinx.serialization.json.JsonElement>? = null

    fun start() {
        if (started) return
        started = true
        jobs = listOf(
            scope.launch { syncOnce() }, // 登录后首拉:云端他端改动全量落本机
            scope.launch { pullPlanOnce() },
            scope.launch {
                // 新设备首拉设置:从未推送过(无水位)时才拉,老设备本地为准
                // 先校验水位归属(换号会清 SETTINGS_SYNC_KEY),再判断;
                // 否则可能与 syncOnce() 并发读到上一账号的水位而漏拉本账号设置
                ensureWatermarkOwner()
                if (prefs.getLong(SETTINGS_SYNC_KEY, 0L) == 0L) pullSettingsOnce()
            },
            scope.launch {
                app.repository.dirtySignal
                    .debounce(3000L)
                    .collect { if (started) syncOnce() }
            },
            scope.launch {
                app.statusSync.notices.filterIsInstance<SyncNotice.DataChanged>()
                    .map { notice ->
                        if (notice.full) fullPullRequests.request()
                        notice
                    }
                    .debounce(1000L)
                    .collect {
                        if (!started) return@collect
                        syncOnce()
                    }
            },
            scope.launch {
                app.statusSync.notices.filterIsInstance<SyncNotice.SettingsChanged>()
                    .debounce(1000L)
                    .collect { if (started) pullSettingsOnce() }
            },
            scope.launch {
                app.statusSync.notices.filterIsInstance<SyncNotice.PlanChanged>()
                    .debounce(1000L)
                    .collect { if (started) pullPlanOnce() }
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

    /** 拉取云端设置并应用;记录快照防回推 */
    private suspend fun pullSettingsOnce() {
        ensureWatermarkOwner()
        runCatching {
            val resp = ApiClient.api().getSettings()
            app.settingsRepo.applySyncMap(resp.settings)
            lastAppliedRemote = resp.settings
        }
    }

    private suspend fun pullPlanOnce() {
        runCatching {
            val account = TokenStore.currentAccountGuid() ?: return
            val plan = ApiClient.api().getActivePlan().plan
            if (!started || TokenStore.currentAccountGuid() != account) return
            if (plan == null) app.repository.clearPlanProjections(account)
            else app.repository.applyPlanProjection(plan, account)
        }
    }

    fun stop() {
        started = false
        jobs.forEach { it.cancel() }
        fullPullRequests.reset()
        jobs = emptyList()
        _state.value = SyncState()
    }

    /** 单轮:push → pull;互斥串行化(信号/周期/手动三源并发只跑一轮) */
    suspend fun syncOnce() {
        mutex.withLock {
            if (TokenStore.currentAccess() == null) return@withLock
            ensureWatermarkOwner()
            val fullPullVersion = fullPullRequests.snapshot()
            if (fullPullRequests.pendingAt(fullPullVersion)) prefs.edit().putLong(SINCE_KEY, 0L).apply()
            _state.update { it.copy(running = true) }
            var pushed = 0
            val outcome = runCatching {
                app.repository.collectPush().forEach { batch ->
                    batch.rows.indices.chunked(2000).forEach { indices ->
                        val rows = indices.map { batch.rows[it] }
                        val keys = indices.map { batch.keys[it] }
                        val result = ApiClient.api().pushResource(batch.resource, rows)
                        val rejected = result.rejected
                        if (
                            rejected == null ||
                            result.truncated == null ||
                            result.truncated != 0 ||
                            rejected.isNotEmpty() ||
                            result.applied + result.skipped != rows.size
                        ) {
                            error("${batch.resource} 同步未完整确认，保留本地待同步数据")
                        }
                        app.repository.markPushed(batch.resource, keys)
                        pushed += rows.size
                    }
                }
                val resp = ApiClient.api().pullChanges(prefs.getLong(SINCE_KEY, 0L))
                val applied = app.repository.applyPull(resp.changes)
                // 拉取成功才推进水位;失败下轮重拉,不丢变更
                prefs.edit().putLong(SINCE_KEY, resp.serverTime).apply()
                fullPullRequests.acknowledge(fullPullVersion)
                applied
            }
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
        }
    }

    companion object {
        private const val SINCE_KEY = "since"
        private const val SETTINGS_SYNC_KEY = "settings_sync_at"
        private const val WATERMARK_ACCOUNT_KEY = "watermark_account"
    }
}
