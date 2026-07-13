package com.signidesign.dailytasks.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.signidesign.dailytasks.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "settings")
private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

class ThemeRepository(private val context: Context) {
    val themeMode: Flow<ThemeMode> = context.themeDataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY]?.let { stored ->
            ThemeMode.entries.firstOrNull { it.name == stored }
        } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { it[THEME_MODE_KEY] = mode.name }
    }
}
