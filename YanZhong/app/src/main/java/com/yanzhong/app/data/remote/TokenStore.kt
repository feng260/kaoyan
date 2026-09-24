package com.yanzhong.app.data.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.cloudStore by preferencesDataStore("cloud_sync")

/**
 * 云同步凭证(DataStore):access/refresh token、设备 UUID、服务器地址、上次同步时间。
 * deviceGuid 首次生成后持久,服务端按其幂等登记设备。
 */
object TokenStore {
    private val KEY_ACCESS = stringPreferencesKey("access")
    private val KEY_REFRESH = stringPreferencesKey("refresh")
    private val KEY_DEVICE_GUID = stringPreferencesKey("device_guid")
    private val KEY_SERVER = stringPreferencesKey("server_url")
    private val KEY_LAST_SYNC = longPreferencesKey("last_sync")

    private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }
    private fun context(): Context = appContext ?: error("TokenStore 未初始化")

    suspend fun currentAccess(): String? = context().cloudStore.data.first()[KEY_ACCESS]
    suspend fun currentRefresh(): String? = context().cloudStore.data.first()[KEY_REFRESH]
    suspend fun currentServer(): String? = context().cloudStore.data.first()[KEY_SERVER]
    suspend fun currentLastSync(): Long = context().cloudStore.data.first()[KEY_LAST_SYNC] ?: 0L

    /** 登录态(响应式):access token 存在即已登录;登录写 token/退出清 token 都会即时翻转启动门 */
    fun observeLoggedIn(): Flow<Boolean> =
        context().cloudStore.data.map { it[KEY_ACCESS] != null }

    /** 首次调用生成并持久化设备 UUID */
    suspend fun ensureDeviceGuid(): String {
        val ctx = context()
        val existing = ctx.cloudStore.data.first()[KEY_DEVICE_GUID]
        if (existing != null) return existing
        val guid = UUID.randomUUID().toString()
        ctx.cloudStore.edit { it[KEY_DEVICE_GUID] = guid }
        return guid
    }

    suspend fun saveTokens(access: String?, refresh: String?) {
        context().cloudStore.edit { prefs ->
            if (access != null) prefs[KEY_ACCESS] = access else prefs.remove(KEY_ACCESS)
            if (refresh != null) prefs[KEY_REFRESH] = refresh else prefs.remove(KEY_REFRESH)
        }
    }

    suspend fun saveServer(url: String) {
        val normalized = url.trim().trimEnd('/')
        context().cloudStore.edit { it[KEY_SERVER] = normalized }
    }

    suspend fun saveLastSync(t: Long) {
        context().cloudStore.edit { it[KEY_LAST_SYNC] = t }
    }

    suspend fun clearAll() {
        context().cloudStore.edit {
            it.remove(KEY_ACCESS); it.remove(KEY_REFRESH)
        }
    }
}
