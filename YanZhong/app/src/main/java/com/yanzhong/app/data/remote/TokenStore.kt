package com.yanzhong.app.data.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.cloudStore by preferencesDataStore("cloud_sync")

/**
 * 云同步凭证(DataStore):access/refresh token、设备 UUID、服务器地址、上次同步时间、账号用户名。
 * deviceGuid 首次生成后持久,服务端按其幂等登记设备。
 */
object TokenStore {
    private val KEY_ACCESS = stringPreferencesKey("access")
    private val KEY_REFRESH = stringPreferencesKey("refresh")
    private val KEY_ACCOUNT_GUID = stringPreferencesKey("account_guid")
    private val KEY_DEVICE_GUID = stringPreferencesKey("device_guid")
    private val KEY_SERVER = stringPreferencesKey("server_url")
    private val KEY_SERVER_VER = intPreferencesKey("server_url_ver")
    private val KEY_LAST_SYNC = longPreferencesKey("last_sync")
    private val KEY_USERNAME = stringPreferencesKey("username")

    /**
     * 服务器地址配置版本:内置默认地址(DEFAULT_SERVER_URL)发生迁移时 +1。
     * 设备上存的配置版本落后时,说明该地址是上一代地址(多为本地联调时填的局域网 IP),
     * 升级后一次性丢弃,回到内置默认地址——否则老设备会一直被旧地址压住,
     * 表现为"装的明明是刚打的新包,请求却还发往旧地址"。
     */
    private const val SERVER_CONFIG_VERSION = 1

    private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }
    private fun context(): Context = appContext ?: error("TokenStore 未初始化")

    suspend fun currentAccess(): String? = context().cloudStore.data.first()[KEY_ACCESS]
    suspend fun currentRefresh(): String? = context().cloudStore.data.first()[KEY_REFRESH]
    suspend fun currentAccountGuid(): String? = context().cloudStore.data.first()[KEY_ACCOUNT_GUID]

    suspend fun saveAccountGuid(guid: String?) {
        context().cloudStore.edit { prefs ->
            if (guid.isNullOrBlank()) prefs.remove(KEY_ACCOUNT_GUID)
            else prefs[KEY_ACCOUNT_GUID] = guid
        }
    }

    /**
     * 设备上保存的自定义服务器地址;从未保存过(或已按 [SERVER_CONFIG_VERSION] 作废旧值)返回 null。
     * 调用方应回落 [DEFAULT_SERVER_URL],统一走 effectiveServerUrl()。
     */
    suspend fun currentServer(): String? {
        val prefs = context().cloudStore.data.first()
        if ((prefs[KEY_SERVER_VER] ?: 0) >= SERVER_CONFIG_VERSION) return prefs[KEY_SERVER]
        // 配置版本落后:丢弃遗留地址并打上当前版本,之后不再重复迁移
        val legacy = prefs[KEY_SERVER]
        context().cloudStore.edit {
            if (legacy != null) it.remove(KEY_SERVER)
            it[KEY_SERVER_VER] = SERVER_CONFIG_VERSION
        }
        return null
    }
    suspend fun currentLastSync(): Long = context().cloudStore.data.first()[KEY_LAST_SYNC] ?: 0L

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

    /**
     * 登录态(响应式):access token 存在 **且** account_guid 非空才算已登录。
     *
     * 这是全 App 唯一的"是否已登录"口径：启动门(MainActivity)、计划页、我的页、
     * 账号页都订阅这个 Flow。以前各页各写各的判断(有的只看 access，有的还要求 guid
     * 非空)，会出现"同一台设备上这页说已登录、那页说未登录"的割裂现象。
     * 登录写 token/guid、退出 clearAll 都会即时翻转所有订阅者。
     */
    fun observeLoggedIn(): Flow<Boolean> =
        context().cloudStore.data.map {
            it[KEY_ACCESS] != null && !it[KEY_ACCOUNT_GUID].isNullOrBlank()
        }

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

    /** 保存自定义服务器地址,并标记为当前配置版本(避免下次启动被迁移逻辑清掉) */
    suspend fun saveServer(url: String) {
        val normalized = url.trim().trimEnd('/')
        context().cloudStore.edit {
            it[KEY_SERVER] = normalized
            it[KEY_SERVER_VER] = SERVER_CONFIG_VERSION
        }
    }

    /** 丢弃自定义地址,回到内置默认地址 */
    suspend fun clearServer() {
        context().cloudStore.edit {
            it.remove(KEY_SERVER)
            it[KEY_SERVER_VER] = SERVER_CONFIG_VERSION
        }
    }

    suspend fun saveLastSync(t: Long) {
        context().cloudStore.edit { it[KEY_LAST_SYNC] = t }
    }

    suspend fun clearAll() {
        context().cloudStore.edit {
            it.remove(KEY_ACCESS)
            it.remove(KEY_REFRESH)
            it.remove(KEY_ACCOUNT_GUID)
            it.remove(KEY_USERNAME)
        }
    }
}
