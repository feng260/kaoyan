package com.yanzhong.app

import android.app.Application
import com.yanzhong.app.data.db.YanZhongDatabase
import com.yanzhong.app.data.prefs.SettingsRepository
import com.yanzhong.app.data.repo.StudyRepository
import com.yanzhong.app.service.FocusService
import com.yanzhong.app.timer.PomodoroEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
        appScope.launch { importBuiltinPlanPack() }
        appScope.launch {
            // 登录门翻转联动:登录→常驻状态+数据同步,登出/被踢→断开
            authState.collect { logged ->
                if (logged == true) {
                    statusSync.start()
                    dataSyncer.start()
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
     * 首启自动装载内置 468 天作战计划包(assets/plan/yanzhong-plan-import.json):
     * 周期任务模板 + 阶段里程碑任务 + 关键日期节点。导入逻辑按标题/名称幂等去重;
     * [PLAN_PACK_VERSION] 随计划包内容升级,老安装升级后自动补导新增条目,
     * 导入失败不记录版本,下次启动自动重试。
     */
    private suspend fun importBuiltinPlanPack() {
        if (settingsRepo.current().planPackVersion >= PLAN_PACK_VERSION) return
        val json = runCatching {
            assets.open("plan/yanzhong-plan-import.json").use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return
        runCatching { repository.importJson(json) }
            .onSuccess { settingsRepo.setPlanPackVersion(PLAN_PACK_VERSION) }
    }

    companion object {
        /** 内置计划包内容版本:assets 里的 JSON 变更时 +1 */
        const val PLAN_PACK_VERSION = 2
    }
}
