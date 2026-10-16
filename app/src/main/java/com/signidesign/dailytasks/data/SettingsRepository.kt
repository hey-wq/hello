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
private val VOICE_SPEAK_KEY = booleanPreferencesKey("voice_spoken_replies")
private val VOICE_OFFLINE_KEY = booleanPreferencesKey("voice_prefer_offline")
private val VOICE_CONFIRM_DELETE_KEY = booleanPreferencesKey("voice_confirm_deletions")
private val VOICE_EARCONS_KEY = booleanPreferencesKey("voice_earcons")

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

    // ------------------------------------------------------------------ voice

    /** Spoken replies; off leaves the transcript on screen only. */
    val voiceSpokenReplies: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[VOICE_SPEAK_KEY] ?: true
    }

    /**
     * Force on-device recognition. Off by default: it is private and works
     * on a plane, but needs a downloaded language pack, and without one the
     * recognizer simply reports no match.
     */
    val voicePreferOffline: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[VOICE_OFFLINE_KEY] ?: false
    }

    /** Ask before a spoken delete actually removes the task. */
    val voiceConfirmDeletions: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[VOICE_CONFIRM_DELETE_KEY] ?: true
    }

    /** Short tones for mic-open, applied and failed. */
    val voiceEarcons: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[VOICE_EARCONS_KEY] ?: true
    }

    suspend fun setVoiceEarcons(enabled: Boolean) {
        context.settingsDataStore.edit { it[VOICE_EARCONS_KEY] = enabled }
    }

    suspend fun setVoiceSpokenReplies(enabled: Boolean) {
        context.settingsDataStore.edit { it[VOICE_SPEAK_KEY] = enabled }
    }

    suspend fun setVoicePreferOffline(enabled: Boolean) {
        context.settingsDataStore.edit { it[VOICE_OFFLINE_KEY] = enabled }
    }

    suspend fun setVoiceConfirmDeletions(enabled: Boolean) {
        context.settingsDataStore.edit { it[VOICE_CONFIRM_DELETE_KEY] = enabled }
    }

    suspend fun setLastSyncAt(value: Long) {
        context.settingsDataStore.edit { it[LAST_SYNC_AT_KEY] = value }
    }
}
