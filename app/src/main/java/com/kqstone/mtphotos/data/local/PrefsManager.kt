package com.kqstone.mtphotos.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mtphotos_prefs")

class PrefsManager(private val context: Context) {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_PASSWORD = stringPreferencesKey("password")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_AUTH_CODE = stringPreferencesKey("auth_code")
        private val KEY_AUTH_CODE_EXPIRY = longPreferencesKey("auth_code_expiry")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[KEY_SERVER_URL] ?: "" }
    val username: Flow<String> = context.dataStore.data.map { it[KEY_USERNAME] ?: "" }
    val password: Flow<String> = context.dataStore.data.map { it[KEY_PASSWORD] ?: "" }
    val token: Flow<String> = context.dataStore.data.map { it[KEY_TOKEN] ?: "" }
    val refreshToken: Flow<String> = context.dataStore.data.map { it[KEY_REFRESH_TOKEN] ?: "" }

    fun getServerUrlSync(): String = runBlocking {
        serverUrl.first().replace(Regex("[\\p{Cf}\\p{Cc}]"), "").trimEnd('/')
    }
    fun getUsernameSync(): String = runBlocking { username.first() }
    fun getPasswordSync(): String = runBlocking { password.first() }
    fun getTokenSync(): String = runBlocking { token.first() }
    fun getRefreshTokenSync(): String = runBlocking { refreshToken.first() }
    fun getAuthCodeSync(): String = runBlocking {
        val prefs = context.dataStore.data.first()
        val code = prefs[KEY_AUTH_CODE] ?: ""
        val expiry = prefs[KEY_AUTH_CODE_EXPIRY] ?: 0L
        if (code.isNotEmpty() && System.currentTimeMillis() < expiry) {
            code
        } else {
            ""
        }
    }

    suspend fun saveCredentials(serverUrl: String, username: String, password: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = serverUrl.sanitizeUrl()
            prefs[KEY_USERNAME] = username.trim()
            prefs[KEY_PASSWORD] = password.trim()
        }
    }

    /** Remove invisible Unicode chars and trim whitespace */
    private fun String.sanitizeUrl(): String {
        return this.trim()
            .replace(Regex("[\\p{Cf}\\p{Cc}]"), "") // control + format chars
            .trimEnd('/')
    }

    suspend fun saveToken(token: String, refreshToken: String = "") {
        context.dataStore.edit { prefs ->
            prefs[KEY_TOKEN] = token
            if (refreshToken.isNotEmpty()) {
                prefs[KEY_REFRESH_TOKEN] = refreshToken
            }
        }
    }

    suspend fun saveAuthCode(authCode: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTH_CODE] = authCode
            // 23 hours expiry (safety margin before 24h actual expiry)
            prefs[KEY_AUTH_CODE_EXPIRY] = System.currentTimeMillis() + 23 * 60 * 60 * 1000
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
