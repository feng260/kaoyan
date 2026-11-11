package com.yanzhong.app

import android.app.Application
import com.yanzhong.app.data.db.YanZhongDatabase
import com.yanzhong.app.data.prefs.ConsentStore
import com.yanzhong.app.data.prefs.SettingsRepository
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.ConsentReq
import com.yanzhong.app.data.repo.StudyRepository
import com.yanzhong.app.service.FocusService
import com.yanzhong.app.timer.PomodoroEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class YanZhongApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 全局登录门:null=启动检查中,false=未登录(显示登录页),true=已登录(进入主界面)。
     * lazy 规避对象构造期(TokenStore 未 init)访问 DataStore——与 TokenStore 类加载崩溃同一坑;
     * 数据源为 TokenStore DataStore,登录/退出/token 失效清空即时反映。
     */
    val authState: StateFlow<Boolean?> by lazy {
        com.yanzhong.app.data.remote.TokenStore.observeLoggedIn()
            .map { it as Boolean? }
            .stateIn(appScope, SharingStarted.Eagerly, null)
    }

    val database: YanZhongDatabase by lazy { YanZhongDatabase.get(this) }
    val settingsRepo: SettingsRepository by lazy { SettingsRepository(this) }
    val repository: StudyRepository by lazy { StudyRepository(database) }

    /** 首启同意门:独立 DataStore,与设置/登录态分开存 */
    val consentStore: ConsentStore by lazy { ConsentStore(this) }

    /** 同意门状态:null=读取中,false=未同意,true=当前版本已同意 */
    val consentAccepted: StateFlow<Boolean?> by lazy {
        consentStore.accepted
            .map { it as Boolean? }
            .stateIn(appScope, SharingStarted.Eagerly, null)
    }

    /** 多端状态同步:登录后常驻 WS(引擎状态广播 + 他端跟随) */
    val statusSync by lazy { com.yanzhong.app.data.remote.StatusSyncClient(this) }

    /** 多端数据同步:登录后常驻(脏行推送 + 增量拉取) */
    val dataSyncer by lazy { com.yanzhong.app.data.remote.DataSyncer(this) }

    val engine: PomodoroEngine by lazy {
        PomodoroEngine(this, database, settingsRepo)
    }

    override fun onCreate() {
        super.onCreate()
        com.yanzhong.app.data.remote.TokenStore.init(this)
        FocusService.ensureChannels(this)
        appScope.launch {
            settingsRepo.settings.collect { engine.primeSettings(it) }
        }
        appScope.launch {
            // 启动联动:必须「已同意 + 已登录」两个条件同时成立才连网。
            // 未同意就起 WS/同步,等于在用户点「同意并继续」之前就把学习数据发出去;
            // 未登录时起它们只会拿到 401 反复重连。
            combine(authState, consentAccepted) { logged, consented ->
                logged == true && consented == true
            }.collect { active ->
                if (active) {
                    statusSync.start()
                    dataSyncer.start()
                    // 补报单独起一个协程:慢请求不能拖住核心同步,
                    // 也不能让一次 409(协议换版)把这两个 client 的启动一起带崩。
                    appScope.launch { syncPendingConsent() }
                } else {
                    statusSync.stop()
                    dataSyncer.stop()
                }
            }
        }
        // 引擎直写 DB(会话落库/番茄计数)也走脏标,供自动增量同步收集
        engine.onSessionWritten = { repository.notifyDataChanged() }
        engine.restoreIfNeeded()
    }

    /**
     * 同意后若当时未登录,登录成功后补报服务端政策版本。
     *
     * 只做「补报」:成功后清掉 pending 标记。失败(离线/409 版本不一致)时保留标记,
     * 下次启动或下次登录再试,不向用户弹错——同意本身已经本地留档,补报是留痕不是门槛。
     */
    private suspend fun syncPendingConsent() {
        if (!consentStore.needsSync()) return
        runCatching {
            ApiClient.api().acceptConsent(ConsentReq(ConsentStore.CONSENT_VERSION))
        }.onSuccess { consentStore.markSynced() }
    }
}
