package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.ActiveInvoiceSession
import com.example.model.AppSettings
import com.example.model.SyncMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

private val Context.wmsDataStore: DataStore<Preferences> by preferencesDataStore(name = "wms_preferences")

class PreferencesManager(private val context: Context) {
    private val keySettings = stringPreferencesKey("app_settings_json")
    private val keySyncMeta = stringPreferencesKey("sync_meta_json")
    private val keyActiveSession = stringPreferencesKey("active_session_json")
    private val keyCurrentUserId = stringPreferencesKey("current_user_id")

    val settingsFlow: Flow<AppSettings> = context.wmsDataStore.data.map { prefs ->
        prefs[keySettings]?.let {
            runCatching { wmsJson.decodeFromString<AppSettings>(it) }.getOrNull()
        } ?: AppSettings()
    }

    val syncMetadataFlow: Flow<SyncMetadata> = context.wmsDataStore.data.map { prefs ->
        prefs[keySyncMeta]?.let {
            runCatching { wmsJson.decodeFromString<SyncMetadata>(it) }.getOrNull()
        } ?: SyncMetadata(totalInvoices = 3, totalItems = 7, fileName = "Demo_Master_Invoices.xlsx")
    }

    val activeSessionFlow: Flow<ActiveInvoiceSession?> = context.wmsDataStore.data.map { prefs ->
        prefs[keyActiveSession]?.let {
            runCatching { wmsJson.decodeFromString<ActiveInvoiceSession>(it) }.getOrNull()
        }
    }

    val currentUserIdFlow: Flow<String> = context.wmsDataStore.data.map { prefs ->
        prefs[keyCurrentUserId] ?: "usr-sup-1"
    }

    suspend fun saveSettings(settings: AppSettings) {
        context.wmsDataStore.edit { it[keySettings] = wmsJson.encodeToString(settings) }
    }

    suspend fun saveSyncMetadata(meta: SyncMetadata) {
        context.wmsDataStore.edit { it[keySyncMeta] = wmsJson.encodeToString(meta) }
    }

    suspend fun saveActiveSession(session: ActiveInvoiceSession?) {
        context.wmsDataStore.edit { prefs ->
            if (session == null) prefs.remove(keyActiveSession)
            else prefs[keyActiveSession] = wmsJson.encodeToString(session)
        }
    }

    suspend fun setCurrentUserId(userId: String) {
        context.wmsDataStore.edit { it[keyCurrentUserId] = userId }
    }
}
