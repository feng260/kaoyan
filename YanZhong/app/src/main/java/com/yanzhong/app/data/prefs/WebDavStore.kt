package com.yanzhong.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.webdavStore by preferencesDataStore("webdav")

/** WebDAV 云同步配置(url / username / password,Basis 鉴权,存 DataStore) */
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = ""
)

object WebDavStore {
    private val KEY_URL = stringPreferencesKey("url")
    private val KEY_USER = stringPreferencesKey("username")
    private val KEY_PASS = stringPreferencesKey("password")

    private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }
    private fun ctx(): Context = appContext ?: error("WebDavStore 未初始化")

    suspend fun load(): WebDavConfig {
        val p = ctx().webdavStore.data.first()
        return WebDavConfig(
            url = p[KEY_URL] ?: "",
            username = p[KEY_USER] ?: "",
            password = SecretCipher.decrypt(p[KEY_PASS] ?: "")
        )
    }

    suspend fun save(config: WebDavConfig) {
        ctx().webdavStore.edit { prefs ->
            prefs[KEY_URL] = config.url.trim().trimEnd('/')
            prefs[KEY_USER] = config.username
            prefs[KEY_PASS] = SecretCipher.encrypt(config.password)
        }
    }
}