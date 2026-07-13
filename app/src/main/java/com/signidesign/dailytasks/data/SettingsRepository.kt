package com.signidesign.dailytasks.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.signidesign.dailytasks.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
private val REMINDERS_ENABLED_KEY = booleanPreferencesKey("reminders_enabled")
private val DIGEST_ENABLED_KEY = booleanPreferencesKey("digest_enabled")
private val SYNC_URL_KEY = stringPreferencesKey("sync_url")
private val SYNC_TOKEN_KEY = stringPreferencesKey("sync_token")
private val LAST_SYNC_AT_KEY = longPreferencesKey("last_sync_at")

/** User preferences: theme plus notification switches. */
class SettingsRepository(private val context: Context) {
    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY]?.let { stored ->
            ThemeMode.entries.firstOrNull { it.name == stored }
        } ?: ThemeMode.SYSTEM
    }

    val remindersEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[REMINDERS_ENABLED_KEY] ?: true
    }

    val digestEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[DIGEST_ENABLED_KEY] ?: true
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[THEME_MODE_KEY] = mode.name }
    }

    suspend fun setRemindersEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[REMINDERS_ENABLED_KEY] = enabled }
    }

    suspend fun setDigestEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[DIGEST_ENABLED_KEY] = enabled }
    }

    val syncUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[SYNC_URL_KEY] ?: ""
    }

    val syncToken: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[SYNC_TOKEN_KEY] ?: ""
    }

    val lastSyncAt: Flow<Long> = context.settingsDataStore.data.map { prefs ->
        prefs[LAST_SYNC_AT_KEY] ?: 0L
    }

    suspend fun setSyncConfig(url: String, token: String) {
        context.settingsDataStore.edit {
            it[SYNC_URL_KEY] = url.trim()
            it[SYNC_TOKEN_KEY] = token.trim()
        }
    }

    suspend fun setLastSyncAt(value: Long) {
        context.settingsDataStore.edit { it[LAST_SYNC_AT_KEY] = value }
    }
}
