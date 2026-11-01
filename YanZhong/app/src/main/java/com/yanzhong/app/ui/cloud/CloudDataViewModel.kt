package com.yanzhong.app.ui.cloud

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.remote.ApiClient
import com.yanzhong.app.data.remote.BackupDownload
import com.yanzhong.app.data.remote.BackupUpload
import com.yanzhong.app.data.remote.apiErrorMessage
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 账号页「云端数据」VM:导出账号数据、下载云端备份、下载恢复前快照、从备份恢复。
 *
 * 恢复是最危险的一条路径,顺序写死在这儿:
 * ① 先把服务端当前数据快照落到 filesDir,快照失败就整体取消,绝不停同步;
 * ② 停后台同步(否则恢复期间本机脏数据会被推上去);
 * ③ 服务端恢复 → 本机覆盖学习数据 → 应用设置,三步都成功才算成功;
 * ④ 无论成败都把同步重新拉起来。
 * 停同步之后的三步放在 NonCancellable 里,页面被销毁也不会只做一半;
 * 同一份 pending 的重试复用第一次拍的快照,不会把恢复后的数据写进唯一的恢复前快照。
 * 取消 pending 只在用户点取消时发生,失败时预览留着,方便直接重试。
 */
data class CloudDataUiState(
    val busy: Boolean = false,
    val pending: BackupDownload? = null,
    val pendingName: String = "",
    val snapshotAvailable: Boolean = false,
    val message: String = ""
)

class CloudDataViewModel(app: Application) : AndroidViewModel(app) {
    private val yanZhong = app as YanZhongApp
    private val repo = yanZhong.repository
    private val settingsRepo = yanZhong.settingsRepo
    private val syncer = yanZhong.dataSyncer
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val snapshotFile get() = yanZhong.filesDir.resolve(SNAPSHOT_NAME)

    /** 这次 pending 的快照是否已经拍过;重试时沿用,免得覆盖掉唯一的恢复前数据 */
    private var pendingSnapshotTaken = false

    private val _ui = MutableStateFlow(
        CloudDataUiState(snapshotAvailable = yanZhong.filesDir.resolve(SNAPSHOT_NAME).exists())
    )
    val ui: StateFlow<CloudDataUiState> = _ui

    fun exportAccountTo(uri: Uri) = write(uri) {
        ApiClient.transferApi().exportAccount().toString()
    }

    fun downloadBackupTo(uri: Uri) = write(uri) {
        val backup = ApiClient.transferApi().backup()
        json.encodeToString(backup)
    }

    fun downloadSnapshotTo(uri: Uri) = write(uri) {
        if (!snapshotFile.exists()) throw IllegalStateException("还没有恢复前快照")
        withContext(Dispatchers.IO) { snapshotFile.readText() }
    }

    private fun write(uri: Uri, produce: suspend () -> String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "") }
            runCatching {
                // 拼文本(序列化最大 7.5MB 的 JSON)也不该占着主线程
                val text = withContext(Dispatchers.Default) { produce() }
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法写入这个文件")
                }
            }.fold({
                _ui.update { it.copy(busy = false, message = "已保存到所选位置") }
            }, { e ->
                _ui.update { it.copy(busy = false, message = "保存失败:${e.message ?: "无法写入文件"}") }
            })
        }
    }

    fun prepareRestore(uri: Uri) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "", pending = null) }
            // 读文件、查大小、解析 JSON 都是重活(最大 7.5MB),必须在 IO 线程一口气做完,
            // 否则主线程会卡帧;显示名也从 provider 查,同样放在这里,异常只影响名字不影响流程。
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = readLimited(uri)
                    CloudDataRules.validateSize(bytes.size)?.let { throw IllegalArgumentException(it) }
                    val backup = CloudDataRules.parseBackup(bytes.toString(Charsets.UTF_8)).getOrThrow()
                    backup to displayName(uri)
                }
            }
            result.fold({ (backup, name) ->
                // 换了一份新文件:上一次的快照不再代表这次恢复前的数据
                pendingSnapshotTaken = false
                _ui.update {
                    it.copy(
                        busy = false,
                        pending = backup,
                        pendingName = name,
                        message = ""
                    )
                }
            }, { e ->
                _ui.update { it.copy(busy = false, message = "读取失败:${e.message ?: "文件格式不正确"}") }
            })
        }
    }

    fun clearPendingRestore() {
        pendingSnapshotTaken = false
        _ui.update { it.copy(pending = null, pendingName = "") }
    }

    fun confirmRestore() {
        val pending = _ui.value.pending ?: return
        if (_ui.value.busy) return
        // 同步置 busy:双击的第二下到这里就被挡掉,不会和正在跑的那一路恢复叠在一起
        _ui.update { it.copy(busy = true, message = "") }
        viewModelScope.launch {
            // ① 快照(可取消,且发生在停同步之前);同一份 pending 的重试复用第一次的快照
            val snapshot: Result<Unit> = if (pendingSnapshotTaken) {
                Result.success(Unit)
            } else {
                runCatching {
                    val backup = ApiClient.transferApi().backup()
                    withContext(Dispatchers.IO) {
                        writeSnapshotAtomically(json.encodeToString(BackupDownload.serializer(), backup))
                    }
                    pendingSnapshotTaken = true
                }
            }
            if (snapshot.isFailure) {
                _ui.update {
                    it.copy(busy = false, message = "恢复前快照失败，已取消恢复;请检查网络后重试")
                }
                return@launch
            }
            // ② 停同步 → ③ 覆盖(不可取消,起了头就必须走完)→ ④ 一定重新拉起
            var serverRestored = false
            syncer.stop()
            val result = try {
                withContext(NonCancellable) {
                    runCatching {
                        // 服务端 /backup/restore 收 BackupUpload;文件解析出来的是 BackupDownload,
                        // 两者字段一致但没有继承关系,必须显式转换(settings 也要带上,否则恢复后设置丢失)。
                        val upload = BackupUpload(
                            subjects = pending.subjects,
                            countdownNodes = pending.countdownNodes,
                            tasks = pending.tasks,
                            sessions = pending.sessions,
                            dailyReviews = pending.dailyReviews,
                            weeklyReviews = pending.weeklyReviews,
                            monthlyReviews = pending.monthlyReviews,
                            settings = pending.settings
                        )
                        val resp = ApiClient.transferApi().restoreBackup(upload)
                        // 服务端已经落地了:从这里往后只可能是「部分成功」
                        serverRestored = true
                        // 只有服务端接受之后才动本机:服务端失败时本机数据保持原样
                        repo.restoreFromBackup(pending)
                        settingsRepo.applySyncMap(pending.settings)
                        resp
                    }
                }
            } finally {
                syncer.start()
            }
            result.fold({ resp ->
                pendingSnapshotTaken = false
                val skipped = resp.resources.values.sumOf { it.skipped }
                _ui.value = CloudDataUiState(
                    snapshotAvailable = true,
                    message = if (skipped > 0) "已恢复，$skipped 条记录未能导入"
                    else "已恢复，其他设备会自动刷新"
                )
            }, { e ->
                if (serverRestored) {
                    // 服务端已经是新数据,本机没跟上:别让用户再点恢复,补数据交给后台同步
                    pendingSnapshotTaken = false
                    _ui.value = CloudDataUiState(
                        snapshotAvailable = true,
                        busy = false,
                        pending = null,
                        pendingName = "",
                        message = "服务端已恢复，但本机刷新失败；网络恢复后同步会自动补齐"
                    )
                } else {
                    // 服务端不一定没提交(可能是响应丢了),所以 pending 和快照都留着:
                    // 用户可以直接再点一次「继续恢复」,重试沿用同一个快照,不覆盖恢复前数据
                    _ui.update {
                        it.copy(
                            busy = false,
                            snapshotAvailable = true,
                            message = "恢复失败:${e.apiErrorMessage()}"
                        )
                    }
                }
            })
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = "") }

    /** 先写 .tmp 再改名:进程中途被杀也不会留下半份快照 */
    private fun writeSnapshotAtomically(text: String) {
        val tmp = File(snapshotFile.parentFile, SNAPSHOT_TMP_NAME)
        tmp.writeText(text)
        if (!tmp.renameTo(snapshotFile)) {
            tmp.copyTo(snapshotFile, overwrite = true)
            tmp.delete()
        }
    }

    /** 读 SAF 文件,超过上限立刻停,不把整份大文件读进内存再说 */
    private fun readLimited(uri: Uri): ByteArray {
        val input = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取这个文件")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > CloudDataRules.MAX_BACKUP_BYTES) {
                    throw IllegalArgumentException("备份文件超过 7.5MB，请换一个小一点的文件")
                }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    /** provider 的查询随时可能抛异常;拿不到名字就退回默认文案,不能让它把整个流程带崩 */
    private fun displayName(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull() ?: "备份文件"

    companion object {
        private const val SNAPSHOT_NAME = "pre_restore_backup.json"
        private const val SNAPSHOT_TMP_NAME = "$SNAPSHOT_NAME.tmp"
    }
}
