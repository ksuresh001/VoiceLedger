package com.example.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "voiceledger_prefs")

data class UserPrefs(
    val remindersEnabled: Boolean = true,
    val defaultLeadMinutes: Int = 60,
    val dailyDigestEnabled: Boolean = true,
    val selectedWorkspaceId: String = ""
)

class UserPrefsDataStore(private val context: Context) {

    val prefsFlow: Flow<UserPrefs> = context.dataStore.data.map { prefs ->
        UserPrefs(
            remindersEnabled = prefs[KEY_REMINDERS_ENABLED] ?: true,
            defaultLeadMinutes = prefs[KEY_DEFAULT_LEAD_MINUTES] ?: 60,
            dailyDigestEnabled = prefs[KEY_DAILY_DIGEST_ENABLED] ?: true,
            selectedWorkspaceId = prefs[KEY_SELECTED_WORKSPACE_ID] ?: ""
        )
    }

    suspend fun setRemindersEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_REMINDERS_ENABLED] = enabled }
    }

    suspend fun setDefaultLeadMinutes(minutes: Int) {
        context.dataStore.edit { it[KEY_DEFAULT_LEAD_MINUTES] = minutes }
    }

    suspend fun setDailyDigestEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DAILY_DIGEST_ENABLED] = enabled }
    }

    suspend fun setSelectedWorkspaceId(workspaceId: String) {
        context.dataStore.edit { it[KEY_SELECTED_WORKSPACE_ID] = workspaceId }
    }

    private companion object {
        val KEY_REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        val KEY_DEFAULT_LEAD_MINUTES = intPreferencesKey("default_lead_minutes")
        val KEY_DAILY_DIGEST_ENABLED = booleanPreferencesKey("daily_digest_enabled")
        val KEY_SELECTED_WORKSPACE_ID = stringPreferencesKey("selected_workspace_id")
    }
}
