package com.yanzhong.app.data.remote

import android.os.Build
import android.util.Log
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.timer.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** 他端(同账号其他设备)的实时专注快照 */
data class PeerState(
    val deviceId: Long,
    val deviceName: String,
    /** focus / break / idle */
    val phase: String,
    val remainMs: Long,
    val taskTitle: String,
    val sessionGuid: String,
    /** 该端正处于跟随态(镜像我方或第三方) */
    val following: Boolean,
    val paused: Boolean,
    /** 到达时刻估算的本机墙钟终点:倒计时 = endsAtLocalMs - now */
    val endsAtLocalMs: Long
) {
    val isFocusing: Boolean get() = phase == "focus" || phase == "break"
}

/** 他端写云通知:HTTP 写成功后服务端经 WS 广播,本端立即拉取 */
sealed interface SyncNotice {
    /** 他端推送了数据/全量恢复;full=true 需清 since 水位全量重拉 */
    data class DataChanged(val deviceId: Long, val resource: String?, val full: Boolean) : SyncNotice
    /** 他端更新了云端设置 */
    data class SettingsChanged(val deviceId: Long) : SyncNotice
}

/**
 * 多端状态同步客户端:登录后常驻 WebSocket。
 * - 上行:引擎状态变更 + 每 30s 心跳重发(跟随端修正漂移)
 * - 下行:presence 全量 / peerStatus 增量 → peers 列表;跟随中的主端更新直通引擎
 * - 断线指数退避重连;4001(access 过期)先刷新 token 再重连;被踢清理凭证回登录门
 */
class StatusSyncClient(private val app: YanZhongApp) {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()

    private val _peers = MutableStateFlow<List<PeerState>>(emptyList())
    val peers: StateFlow<List<PeerState>> = _peers.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** 他端写云通知流(dataChanged/settingsChanged),DataSyncer 订阅后立即拉取 */
    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<SyncNotice>(extraBufferCapacity = 32)
    val notices: kotlinx.coroutines.flow.SharedFlow<SyncNotice> = _notices.asSharedFlow()

    @Volatile private var started = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var myDeviceId = -1L
    @Volatile private var backoffMs = 1000L
    private var workScope: CoroutineScope? = null
    private var reconnectJob: Job? = null

    fun start() {
        if (started) return
        started = true
        backoffMs = 1000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        workScope = scope
        connect()
        // 引擎状态变化 → 广播(去重键:阶段/任务/会话/10 秒桶,避免每秒打点)
        scope.launch {
            app.engine.state
                .map { s ->
                    Triple(
                        s.phase.name + "|" + s.mode.name + "|" + s.taskTitle + "|" +
                            s.sessionGuid + "|" + (s.follow?.sessionGuid ?: ""),
                        s.remainingMs / 10_000L,
                        s
                    )
                }
                .distinctUntilChanged { a, b -> a.first == b.first && a.second == b.second }
                .collect { (_, _, s) -> sendStatus(buildPayload(s)) }
        }
        // 周期心跳:运行中每 30s 重发全量,跟随端据此校正终点
        scope.launch {
            while (true) {
                delay(30_000L)
                val s = app.engine.state.value
                if (s.isRunning) sendStatus(buildPayload(s))
            }
        }
    }

    fun stop() {
        started = false
        workScope?.cancel()
        workScope = null
        reconnectJob?.cancel()
        reconnectJob = null
        runCatching { ws?.close(1000, "logout") }
        ws = null
        _peers.value = emptyList()
        _connected.value = false
        myDeviceId = -1L
    }

    // ---------- 载荷 ----------

    private fun buildPayload(s: com.yanzhong.app.timer.TimerState): String {
        val following = s.follow != null
        val phase = when {
            following -> when (s.phase) {
                Phase.SHORT_BREAK, Phase.LONG_BREAK -> "break"
                else -> "focus"
            }
            s.phase == Phase.IDLE -> "idle"
            s.phase == Phase.SHORT_BREAK || s.phase == Phase.LONG_BREAK -> "break"
            else -> "focus"
        }
        val sessionGuid = s.follow?.sessionGuid ?: s.sessionGuid
        val obj = JsonObject(
            mapOf(
                "type" to kotlinx.serialization.json.JsonPrimitive("status"),
                "phase" to kotlinx.serialization.json.JsonPrimitive(phase),
                "remainMs" to kotlinx.serialization.json.JsonPrimitive(s.remainingMs),
                "taskTitle" to kotlinx.serialization.json.JsonPrimitive(s.taskTitle),
                "planName" to kotlinx.serialization.json.JsonPrimitive(s.planName),
                "sessionGuid" to kotlinx.serialization.json.JsonPrimitive(sessionGuid),
                "following" to kotlinx.serialization.json.JsonPrimitive(following),
                "paused" to kotlinx.serialization.json.JsonPrimitive(s.phase == Phase.PAUSED)
            )
        )
        return obj.toString()
    }

    private fun sendStatus(payload: String) {
        val socket = ws ?: return
        runCatching { socket.send(payload) }
    }

    // ---------- 连接管理 ----------

    private fun connect() {
        if (!started) return
        workScope?.launch {
            val access = TokenStore.currentAccess() ?: return@launch
            val server = effectiveServerUrl()
            val wsUrl = server.replaceFirst("http", "ws") + "/ws?token=" + access
            val request = Request.Builder().url(wsUrl).build()
            http.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        backoffMs = 1000L
                        _connected.value = true
                        webSocket.send(
                            """{"type":"hello","deviceName":"${Build.MODEL ?: "Android 设备"}"}"""
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleEvent(text)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        _connected.value = false
                        if (code == 4001) refreshAndReconnect() else scheduleReconnect()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        _connected.value = false
                        scheduleReconnect()
                    }
                }
            ).also { ws = it }
        }
    }

    private fun scheduleReconnect() {
        if (!started) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = workScope?.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            connect()
        }
    }

    /** access 过期(4001):刷新 token 重连;刷新失败清凭证回登录门 */
    private fun refreshAndReconnect() {
        workScope?.launch {
            val refreshed = runCatching {
                val refreshToken = TokenStore.currentRefresh()
                if (refreshToken == null) null
                else ApiClient.api().refresh(RefreshReq(refreshToken))
            }.getOrNull()
            if (refreshed != null) {
                TokenStore.saveTokens(refreshed.tokens.access, refreshed.tokens.refresh)
                backoffMs = 1000L
                connect()
            } else {
                TokenStore.clearAll()
                stop()
            }
        }
    }

    // ---------- 下行处理 ----------

    private fun handleEvent(text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.content) {
            "presence" -> handlePresence(obj)
            "peerStatus" -> handlePeerStatus(obj)
            "dataChanged" -> {
                val deviceId = obj["deviceId"]?.jsonPrimitive?.longOrNull ?: return
                if (deviceId == myDeviceId) return // 发送方是本机,无需回拉
                _notices.tryEmit(
                    SyncNotice.DataChanged(
                        deviceId = deviceId,
                        resource = obj["resource"]?.jsonPrimitive?.content,
                        full = obj["full"]?.jsonPrimitive?.booleanOrNull ?: false
                    )
                )
            }
            "settingsChanged" -> {
                val deviceId = obj["deviceId"]?.jsonPrimitive?.longOrNull ?: return
                if (deviceId == myDeviceId) return
                _notices.tryEmit(SyncNotice.SettingsChanged(deviceId))
            }
            "kicked" -> {
                workScope?.launch {
                    TokenStore.clearAll()
                }
                stop()
            }
        }
    }

    private fun handlePresence(obj: JsonObject) {
        val devices = obj["devices"]?.jsonArray ?: return
        val list = mutableListOf<PeerState>()
        devices.forEach { el ->
            val d = el.jsonObject
            val deviceId = d["deviceId"]?.jsonPrimitive?.longOrNull ?: return@forEach
            if (d["self"]?.jsonPrimitive?.booleanOrNull == true) {
                myDeviceId = deviceId
                return@forEach
            }
            val status = d["lastStatus"]?.jsonObject
            list += peerOf(deviceId, d["deviceName"]?.jsonPrimitive?.content ?: "设备", status)
        }
        _peers.value = list
    }

    private fun handlePeerStatus(obj: JsonObject) {
        val deviceId = obj["deviceId"]?.jsonPrimitive?.longOrNull ?: return
        if (deviceId == myDeviceId) return
        val status = obj["status"]?.jsonObject ?: return
        val peer = peerOf(deviceId, obj["deviceName"]?.jsonPrimitive?.content ?: "设备", status)
        _peers.value = (_peers.value.filter { it.deviceId != deviceId } + peer)
            .sortedBy { it.deviceId }

        // 跟随路由:主端(被跟随设备)的更新直通引擎
        val follow = app.engine.state.value.follow ?: return
        if (deviceId != follow.peerDeviceId) return
        when {
            peer.sessionGuid == follow.sessionGuid && peer.phase != "idle" ->
                app.engine.updateFollow(peer.phase, peer.remainMs, peer.paused, peer.taskTitle)
            peer.sessionGuid != follow.sessionGuid ->
                app.engine.exitFollow("「${follow.peerName}」开始了新的专注,已停止跟随")
            else ->
                app.engine.exitFollow("「${follow.peerName}」已结束专注")
        }
    }

    private fun peerOf(deviceId: Long, name: String, status: JsonObject?): PeerState {
        val phase = status?.get("phase")?.jsonPrimitive?.content ?: "idle"
        val remainMs = status?.get("remainMs")?.jsonPrimitive?.longOrNull ?: 0L
        val paused = status?.get("paused")?.jsonPrimitive?.booleanOrNull ?: false
        return PeerState(
            deviceId = deviceId,
            deviceName = name,
            phase = phase,
            remainMs = remainMs,
            taskTitle = status?.get("taskTitle")?.jsonPrimitive?.content ?: "",
            sessionGuid = status?.get("sessionGuid")?.jsonPrimitive?.content ?: "",
            following = status?.get("following")?.jsonPrimitive?.booleanOrNull ?: false,
            paused = paused,
            endsAtLocalMs = if (paused) 0L else System.currentTimeMillis() + remainMs
        )
    }
}
