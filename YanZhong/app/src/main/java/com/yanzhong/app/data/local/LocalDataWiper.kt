package com.yanzhong.app.data.local

import android.content.Context
import android.content.Intent
import com.yanzhong.app.MainActivity
import com.yanzhong.app.data.db.YanZhongDatabase
import com.yanzhong.app.data.prefs.SettingsRepository
import com.yanzhong.app.data.remote.TokenStore
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 注销账号后的本机清理:凭证、设置、缓存、Room 数据库;完成后重启回登录页。
 *
 * 服务端已删档,本机若留下任何一份副本(任务、番茄、设置、恢复前快照)都等于删号失败,
 * 所以这里把「本机所有用户数据落点」逐个清掉:
 * - TokenStore(DataStore cloud_sync):登录态与身份缓存
 * - 设置 / 计时状态(DataStore settings、timer_state)
 * - filesDir/updates:已下载的更新包
 * - filesDir/pre_restore_backup.json(.tmp):云端恢复前快照,含全量学习数据
 * - cacheDir/avatars:头像缓存(当前版本不落盘,留着防御历史残留)
 * - Room 数据库:关闭单例后删除文件,连带 -wal/-shm
 *
 * 顺序上数据库放最后:前面任一步失败都不会留下一个「数据库已关、进程还在」的半死状态。
 * [wipe] 正常返回即代表全部清理成功;[restart] 只能在成功之后调用。
 */
object LocalDataWiper {

    /** 与 CloudDataViewModel.SNAPSHOT_NAME / SNAPSHOT_TMP_NAME 保持一致 */
    private const val SNAPSHOT_NAME = "pre_restore_backup.json"
    private const val SNAPSHOT_TMP_NAME = "$SNAPSHOT_NAME.tmp"

    /** 全量清理本机用户数据;抛异常即代表没清干净,调用方不得重启 */
    suspend fun wipe(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        TokenStore.clearAll()
        SettingsRepository(app).clearAllLocal()
        File(app.filesDir, "updates").deleteRecursively()
        File(app.filesDir, SNAPSHOT_NAME).delete()
        File(app.filesDir, SNAPSHOT_TMP_NAME).delete()
        File(app.cacheDir, "avatars").deleteRecursively()
        YanZhongDatabase.closeAndDelete(app)
    }

    /**
     * 冷启动回登录页:数据库单例已关闭,继续活在当前进程里只会撞上已关闭的 Room。
     * startActivity 已经交给 system_server 排队,随后杀进程会被系统重新拉起成新进程。
     */
    fun restart(context: Context): Nothing {
        val app = context.applicationContext
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(intent)
        exitProcess(0)
    }
}
