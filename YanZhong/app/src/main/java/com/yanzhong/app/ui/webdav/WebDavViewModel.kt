package com.yanzhong.app.ui.webdav

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yanzhong.app.YanZhongApp
import com.yanzhong.app.data.prefs.WebDavConfig
import com.yanzhong.app.data.prefs.WebDavStore
import com.yanzhong.app.data.remote.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WebDavUiState(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val configured: Boolean = false,
    val busy: Boolean = false,
    val message: String = ""
)

/** WebDAV 云同步:保存配置 / 测试连接 / 上传全量备份 JSON / 下载并覆盖恢复 */
class WebDavViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as YanZhongApp
    private val repo = container.repository
    private val _ui = MutableStateFlow(WebDavUiState())
    val ui: StateFlow<WebDavUiState> = _ui

    init {
        viewModelScope.launch {
            val cfg = WebDavStore.load()
            _ui.update {
                it.copy(url = cfg.url, username = cfg.username, password = cfg.password, configured = cfg.url.isNotBlank())
            }
        }
    }

    fun saveConfig(url: String, username: String, password: String) {
        viewModelScope.launch {
            WebDavStore.save(WebDavConfig(url, username, password))
            _ui.update { it.copy(configured = true, message = "配置已保存") }
        }
    }

    fun testConnection(url: String, username: String, password: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "") }
            val r = WebDavClient.test(url, username, password)
            _ui.update { it.copy(busy = false, message = r.getOrElse { "连接失败:${it.message}" }) }
        }
    }

    fun uploadBackup(url: String, username: String, password: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "") }
            val r = runCatching {
                val json = withContext(Dispatchers.IO) { repo.exportBackupJson() }
                WebDavClient.upload(url, username, password, json).getOrThrow()
            }
            _ui.update { it.copy(busy = false, message = r.fold({ it }, { "上传失败:${it.message}" })) }
        }
    }

    fun restoreFromWebDav(url: String, username: String, password: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "") }
            val r = runCatching {
                val json = WebDavClient.download(url, username, password).getOrThrow()
                withContext(Dispatchers.IO) { repo.restoreBackupJson(json) }
                "已从 WebDAV 恢复本机数据"
            }
            _ui.update { it.copy(busy = false, message = r.getOrElse { "恢复失败:${it.message}" }) }
        }
    }
}