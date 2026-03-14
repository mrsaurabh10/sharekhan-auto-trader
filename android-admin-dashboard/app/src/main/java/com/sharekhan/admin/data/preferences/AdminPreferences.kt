package com.sharekhan.admin.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sharekhan.admin.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AdminPreferences(private val context: Context) {

    private val dataStore: DataStore<Preferences> = context.adminDataStore

    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyUsername = stringPreferencesKey("username")

    val baseUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[keyBaseUrl] ?: BuildConfig.DEFAULT_BASE_URL
    }

    val lastUsername: Flow<String?> = dataStore.data.map { prefs ->
        prefs[keyUsername]
    }

    suspend fun saveBaseUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[keyBaseUrl] = url
        }
    }

    suspend fun saveLastUsername(username: String) {
        dataStore.edit { prefs ->
            prefs[keyUsername] = username
        }
    }
}

private val Context.adminDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "admin_prefs"
)

