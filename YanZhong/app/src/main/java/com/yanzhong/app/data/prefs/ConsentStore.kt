package com.yanzhong.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.consentDataStore by preferencesDataStore("consent")

/** 首启同意门状态:独立 DataStore,清设置/注销时不误删,也不进云同步 */
class ConsentStore(private val context: Context) {

    companion object {
        /** 必须与 server/src/modules/auth/consent.ts 的 POLICY_VERSION 一致 */
        const val CONSENT_VERSION = "2026-11"
        private val KEY_VERSION = stringPreferencesKey("consent_version")
        private val KEY_ACCEPTED_AT = longPreferencesKey("accepted_at")
        private val KEY_PENDING_SYNC = booleanPreferencesKey("pending_sync")
        private val KEY_NOTIFICATION_ASKED = booleanPreferencesKey("notification_asked")
    }

    private val data = context.consentDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    val accepted: Flow<Boolean> = data.map { it[KEY_VERSION] == CONSENT_VERSION }

    val notificationAsked: Flow<Boolean> = data.map { it[KEY_NOTIFICATION_ASKED] ?: false }

    suspend fun accept() {
        context.consentDataStore.edit {
            it[KEY_VERSION] = CONSENT_VERSION
            it[KEY_ACCEPTED_AT] = System.currentTimeMillis()
            it[KEY_PENDING_SYNC] = true
        }
    }

    suspend fun needsSync(): Boolean = data.first()[KEY_PENDING_SYNC] == true

    suspend fun markSynced() {
        context.consentDataStore.edit { it[KEY_PENDING_SYNC] = false }
    }

    suspend fun markNotificationAsked() {
        context.consentDataStore.edit { it[KEY_NOTIFICATION_ASKED] = true }
    }
}
