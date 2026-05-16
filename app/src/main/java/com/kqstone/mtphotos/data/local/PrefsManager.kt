package com.kqstone.mtphotos.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mtphotos_prefs")

class PrefsManager(val context: Context) {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_PASSWORD = stringPreferencesKey("password")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_AUTH_CODE = stringPreferencesKey("auth_code")
        private val KEY_AUTH_CODE_EXPIRY = longPreferencesKey("auth_code_expiry")

        // 备份相关
        private val KEY_BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        private val KEY_BACKUP_WIFI_ONLY = booleanPreferencesKey("backup_wifi_only")
        private val KEY_BACKUP_FOLDERS = stringPreferencesKey("backup_folders") // JSON array of paths
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        private val KEY_FOLDER_SETUP_COMPLETE = booleanPreferencesKey("folder_setup_complete")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[KEY_SERVER_URL] ?: "" }
    val username: Flow<String> = context.dataStore.data.map { it[KEY_USERNAME] ?: "" }
    val password: Flow<String> = context.dataStore.data.map { it[KEY_PASSWORD] ?: "" }
    val token: Flow<String> = context.dataStore.data.map { it[KEY_TOKEN] ?: "" }
    val refreshToken: Flow<String> = context.dataStore.data.map { it[KEY_REFRESH_TOKEN] ?: "" }
    val backupEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_BACKUP_ENABLED] ?: false }
    val backupWifiOnly: Flow<Boolean> = context.dataStore.data.map { it[KEY_BACKUP_WIFI_ONLY] ?: true }
    val backupFolders: Flow<String> = context.dataStore.data.map { it[KEY_BACKUP_FOLDERS] ?: "" }
    val deviceName: Flow<String> = context.dataStore.data.map { it[KEY_DEVICE_NAME] ?: android.os.Build.MODEL }
    val folderSetupComplete: Flow<Boolean> = context.dataStore.data.map { it[KEY_FOLDER_SETUP_COMPLETE] ?: false }

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
    fun getBackupEnabledSync(): Boolean = runBlocking { backupEnabled.first() }
    fun getBackupWifiOnlySync(): Boolean = runBlocking { backupWifiOnly.first() }
    fun getBackupFoldersSync(): String = runBlocking { backupFolders.first() }
    fun getDeviceNameSync(): String = runBlocking { deviceName.first() }
    fun isFolderSetupComplete(): Boolean = runBlocking { folderSetupComplete.first() }

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

    suspend fun saveBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BACKUP_ENABLED] = enabled
        }
    }

    suspend fun saveBackupWifiOnly(wifiOnly: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BACKUP_WIFI_ONLY] = wifiOnly
        }
    }

    suspend fun saveBackupFolders(foldersJson: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BACKUP_FOLDERS] = foldersJson
        }
    }

    suspend fun saveFolderSetupComplete(complete: Boolean = true) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FOLDER_SETUP_COMPLETE] = complete
        }
    }

    suspend fun saveDeviceName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_NAME] = name.trim()
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}

