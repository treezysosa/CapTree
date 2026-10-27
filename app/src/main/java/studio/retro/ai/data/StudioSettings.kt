package studio.retro.ai.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("console")
data class Preferences(val theme: String = "Classic Purple", val haptics: Boolean = true, val sound: Boolean = false, val backend: String = "", val remoteConsent: Boolean = false, val quality: Int = 92)
class StudioSettings(private val context: Context) {
    val flow = context.store.data.map { p -> Preferences(p[stringPreferencesKey("theme")] ?: "Classic Purple", p[booleanPreferencesKey("haptics")] ?: true, p[booleanPreferencesKey("sound")] ?: false, p[stringPreferencesKey("backend")] ?: "", p[booleanPreferencesKey("consent")] ?: false, p[intPreferencesKey("quality")] ?: 92) }
    suspend fun theme(value: String) { context.store.edit { it[stringPreferencesKey("theme")] = value } }
    suspend fun backend(value: String) { context.store.edit { it[stringPreferencesKey("backend")] = value.trim().trimEnd('/'); it[booleanPreferencesKey("consent")] = false } }
    suspend fun consent(value: Boolean) { context.store.edit { it[booleanPreferencesKey("consent")] = value } }
    suspend fun haptics(value: Boolean) { context.store.edit { it[booleanPreferencesKey("haptics")] = value } }
    suspend fun sound(value: Boolean) { context.store.edit { it[booleanPreferencesKey("sound")] = value } }
    suspend fun quality(value: Int) { context.store.edit { it[intPreferencesKey("quality")] = value } }
}
