package com.yanzhong.app.data.remote

import android.content.Context
import com.yanzhong.app.YanZhongApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** 最近一次从云端应用的设置快照:推送前比对,相同即回声,跳过(防拉取→推送死循环) */
    @Volatile
    private var lastAppliedRemote: Map<String, kotlinx.serialization.json.JsonElement>? = null

    fun start() {
        if (started) return
        started = true
        scope.launch { syncOnce() } // 登录后首拉:云端他端改动全量落本机
        scope.launch {
            // 新设备首拉设置:从未推送过(无水位)时才拉,老设备本地为准
            if (prefs.getLong(SETTINGS_SYNC_KEY, 0L) == 0L) pullSettingsOnce()
        }
        scope.launch {
            app.repository.dirtySignal
                .debounce(3000L)
                .collect { if (started) syncOnce() }
        }
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
        }
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
        }
        scope.launch {
            while (true) {
                delay(15 * 60_000L)
                if (started) syncOnce()
            }
        }
    }

    /** 拉取云端设置并应用;记录快照防回推 */
    private suspend fun pullSettingsOnce() {
        runCatching {
            val resp = ApiClient.api().getSettings()
            app.settingsRepo.applySyncMap(resp.settings)
            lastAppliedRemote = resp.settings
        }
    }

    fun stop() {
        started = false
        _state.value = SyncState()
    }

    /** 单轮:push → pull;互斥串行化(信号/周期/手动三源并发只跑一轮) */
    suspend fun syncOnce() {
        mutex.withLock {
            if (TokenStore.currentAccess() == null) return@withLock
            _state.update { it.copy(running = true) }
            var pushed = 0
            val outcome = runCatching {
                app.repository.collectPush().forEach { batch ->
                    ApiClient.api().pushResource(batch.resource, batch.rows)
                    app.repository.markPushed(batch.resource, batch.keys)
                    pushed += batch.rows.size
                }
                val resp = ApiClient.api().pullChanges(prefs.getLong(SINCE_KEY, 0L))
                val applied = app.repository.applyPull(resp.changes)
                // 拉取成功才推进水位;失败下轮重拉,不丢变更
                prefs.edit().putLong(SINCE_KEY, resp.serverTime).apply()
                applied
            }
            outcome.fold({ applied ->
                _state.update {
                    it.copy(
                        running = false, lastSyncAt = System.currentTimeMillis(),
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
    }
}
