package com.kqstone.mtphotos.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mtphotos_prefs")
private const val TAG = "PrefsManager"

data class BackupFolderSelection(
    val folders: Set<String>?,
    val isConfigured: Boolean
) {
    val effectiveFolders: Set<String>?
        get() = folders?.let { it + com.kqstone.mtphotos.data.local.MediaConstants.MT_GALLERY_DOWNLOAD_FOLDER_ABSOLUTE }
}

class PrefsManager(val context: Context) {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_PRIMARY_SERVER_URL = stringPreferencesKey("primary_server_url")
        private val KEY_SECONDARY_SERVER_URL = stringPreferencesKey("secondary_server_url")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_PASSWORD = stringPreferencesKey("password")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_AUTH_CODE = stringPreferencesKey("auth_code")
        private val KEY_AUTH_CODE_EXPIRY = longPreferencesKey("auth_code_expiry")
        private val KEY_AUTH_REQUIRED = booleanPreferencesKey("auth_required")
        private val KEY_SERVER_UNREACHABLE = booleanPreferencesKey("server_unreachable")
        private val KEY_SERVER_UNREACHABLE_EVENT_AT = longPreferencesKey("server_unreachable_event_at")
        private val KEY_SERVER_UNREACHABLE_SHOWN_AT = longPreferencesKey("server_unreachable_shown_at")
        private val KEY_NETWORK_RETRY_PENDING = booleanPreferencesKey("network_retry_pending")

        // 备份相关
        private val KEY_BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        private val KEY_BACKUP_WIFI_ONLY = booleanPreferencesKey("backup_wifi_only")
        private val KEY_BACKUP_FOLDERS = stringPreferencesKey("backup_folders") // JSON array of paths
        private val KEY_BACKUP_FOLDER_HISTORY = stringPreferencesKey("backup_folder_history")
        private val KEY_BACKUP_DEST_ID = longPreferencesKey("backup_destination_id")
        private val KEY_BACKUP_DEST_LABEL = stringPreferencesKey("backup_destination_label")
        private val KEY_BACKUP_DEST_PATH = stringPreferencesKey("backup_destination_path")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        private val KEY_FOLDER_SETUP_COMPLETE = booleanPreferencesKey("folder_setup_complete")
        private val KEY_SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes") // 默认 60
        private val KEY_COIL_DISK_CACHE_MB = intPreferencesKey("coil_disk_cache_mb") // 默认 512MB
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[KEY_SERVER_URL] ?: "" }
    val primaryServerUrl: Flow<String> = context.dataStore.data.map {
        it[KEY_PRIMARY_SERVER_URL] ?: it[KEY_SERVER_URL] ?: ""
    }
    val secondaryServerUrl: Flow<String> = context.dataStore.data.map { it[KEY_SECONDARY_SERVER_URL] ?: "" }
    val username: Flow<String> = context.dataStore.data.map { it[KEY_USERNAME] ?: "" }
    val password: Flow<String> = context.dataStore.data.map { it[KEY_PASSWORD] ?: "" }
    val token: Flow<String> = context.dataStore.data.map { it[KEY_TOKEN] ?: "" }
    val refreshToken: Flow<String> = context.dataStore.data.map { it[KEY_REFRESH_TOKEN] ?: "" }
    val authRequired: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTH_REQUIRED] ?: false }
    val serverUnreachable: Flow<Boolean> = context.dataStore.data.map { it[KEY_SERVER_UNREACHABLE] ?: false }
    val serverUnreachableEventAt: Flow<Long> = context.dataStore.data.map {
        it[KEY_SERVER_UNREACHABLE_EVENT_AT] ?: 0L
    }
    val networkRetryPending: Flow<Boolean> = context.dataStore.data.map { it[KEY_NETWORK_RETRY_PENDING] ?: false }
    val backupEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_BACKUP_ENABLED] ?: false }
    val backupWifiOnly: Flow<Boolean> = context.dataStore.data.map { it[KEY_BACKUP_WIFI_ONLY] ?: true }
    val backupFolders: Flow<String> = context.dataStore.data.map { it[KEY_BACKUP_FOLDERS] ?: "" }
    val backupFolderHistory: Flow<String> = context.dataStore.data.map {
        it[KEY_BACKUP_FOLDER_HISTORY] ?: ""
    }
    val backupDestinationId: Flow<Long> = context.dataStore.data.map { it[KEY_BACKUP_DEST_ID] ?: 1L }
    val backupDestinationLabel: Flow<String> = context.dataStore.data.map {
        it[KEY_BACKUP_DEST_LABEL] ?: android.os.Build.MODEL
    }
    val backupDestinationPath: Flow<String> = context.dataStore.data.map {
        it[KEY_BACKUP_DEST_PATH] ?: defaultBackupDestinationPath(it[KEY_USERNAME])
    }
    val isBackupDestinationConfigured: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_BACKUP_DEST_ID] != null ||
            it[KEY_BACKUP_DEST_LABEL] != null ||
            it[KEY_BACKUP_DEST_PATH] != null
    }
    val deviceName: Flow<String> = context.dataStore.data.map { it[KEY_DEVICE_NAME] ?: android.os.Build.MODEL }
    val folderSetupComplete: Flow<Boolean> = context.dataStore.data.map { it[KEY_FOLDER_SETUP_COMPLETE] ?: false }
    val syncInterval: Flow<Int> = context.dataStore.data.map { it[KEY_SYNC_INTERVAL] ?: 60 }
    val coilDiskCacheMb: Flow<Int> = context.dataStore.data.map { it[KEY_COIL_DISK_CACHE_MB] ?: 512 }

    fun getServerUrlSync(): String = runBlocking {
        val prefs = context.dataStore.data.first()
        (prefs[KEY_SERVER_URL] ?: prefs[KEY_PRIMARY_SERVER_URL] ?: "")
            .sanitizeUrl()
    }
    fun getPrimaryServerUrlSync(): String = runBlocking {
        primaryServerUrl.first().sanitizeUrl()
    }
    fun getSecondaryServerUrlSync(): String = runBlocking {
        secondaryServerUrl.first().sanitizeUrl()
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
    fun getAuthRequiredSync(): Boolean = runBlocking { authRequired.first() }
    fun getServerUnreachableSync(): Boolean = runBlocking { serverUnreachable.first() }
    fun getPendingServerUnreachableEventSync(): Long = runBlocking {
        val prefs = context.dataStore.data.first()
        val eventAt = prefs[KEY_SERVER_UNREACHABLE_EVENT_AT] ?: 0L
        val shownAt = prefs[KEY_SERVER_UNREACHABLE_SHOWN_AT] ?: 0L
        if (eventAt > shownAt) eventAt else 0L
    }
    fun getNetworkRetryPendingSync(): Boolean = runBlocking { networkRetryPending.first() }
    fun getBackupWifiOnlySync(): Boolean = runBlocking { backupWifiOnly.first() }
    fun getBackupFoldersSync(): String = runBlocking { backupFolders.first() }
    fun getBackupFolderHistorySync(): Set<String> = runBlocking {
        val prefs = context.dataStore.data.first()
        parseStringSet(prefs[KEY_BACKUP_FOLDER_HISTORY] ?: "")
    }
    fun getBackupFolderSelectionSync(): BackupFolderSelection = runBlocking {
        val prefs = context.dataStore.data.first()
        val foldersJson = prefs[KEY_BACKUP_FOLDERS] ?: ""
        val setupComplete = prefs[KEY_FOLDER_SETUP_COMPLETE] ?: false
        parseBackupFolderSelection(foldersJson, setupComplete)
    }
    fun getBackupDestinationIdSync(): Long = runBlocking { backupDestinationId.first() }
    fun getBackupDestinationLabelSync(): String = runBlocking { backupDestinationLabel.first() }
    fun getBackupDestinationPathSync(): String = runBlocking { backupDestinationPath.first() }
    fun isBackupDestinationConfiguredSync(): Boolean = runBlocking { isBackupDestinationConfigured.first() }
    fun getDeviceNameSync(): String = runBlocking { deviceName.first() }
    fun isFolderSetupComplete(): Boolean = runBlocking { folderSetupComplete.first() }
    fun getSyncIntervalSync(): Int = runBlocking { syncInterval.first() }
    fun getCoilDiskCacheMbSync(): Int = runBlocking { coilDiskCacheMb.first() }

    suspend fun saveCredentials(serverUrl: String, username: String, password: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = serverUrl.sanitizeUrl()
            prefs.remove(KEY_PRIMARY_SERVER_URL)
            prefs.remove(KEY_SECONDARY_SERVER_URL)
            prefs[KEY_USERNAME] = username.trim()
            prefs[KEY_PASSWORD] = password.trim()
        }
    }

    @Deprecated("Only one server address is supported. Use saveCredentials instead.")
    suspend fun saveServerUrls(primaryUrl: String, secondaryUrl: String, activeUrl: String) {
        context.dataStore.edit { prefs ->
            val nextUrl = activeUrl.ifBlank { primaryUrl }.ifBlank { secondaryUrl }
            prefs[KEY_SERVER_URL] = nextUrl.sanitizeUrl()
            prefs.remove(KEY_PRIMARY_SERVER_URL)
            prefs.remove(KEY_SECONDARY_SERVER_URL)
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
            prefs[KEY_AUTH_REQUIRED] = false
        }
    }

    suspend fun setAuthRequired(required: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTH_REQUIRED] = required
        }
    }

    suspend fun setServerUnreachable(unreachable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_UNREACHABLE] = unreachable
            if (unreachable) {
                prefs[KEY_SERVER_UNREACHABLE_EVENT_AT] = System.currentTimeMillis()
            }
        }
    }

    suspend fun markServerUnreachableShown(eventAt: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_UNREACHABLE_SHOWN_AT] = eventAt
            if ((prefs[KEY_SERVER_UNREACHABLE_EVENT_AT] ?: 0L) <= eventAt) {
                prefs[KEY_SERVER_UNREACHABLE] = false
            }
        }
    }

    suspend fun setNetworkRetryPending(pending: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NETWORK_RETRY_PENDING] = pending
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

    suspend fun saveBackupFolderHistory(paths: Set<String>) {
        val cleaned = cleanFolderPaths(paths)
        context.dataStore.edit { prefs ->
            prefs[KEY_BACKUP_FOLDER_HISTORY] = Gson().toJson(cleaned)
        }
    }

    suspend fun addBackupFolderHistory(paths: Collection<String?>) {
        val additions = cleanFolderPaths(paths.filterNotNull())
        if (additions.isEmpty()) return

        context.dataStore.edit { prefs ->
            val existing = parseStringSet(prefs[KEY_BACKUP_FOLDER_HISTORY] ?: "")
            prefs[KEY_BACKUP_FOLDER_HISTORY] = Gson().toJson((existing + additions).toSortedSet())
        }
    }

    suspend fun saveBackupDestination(id: Long, label: String, path: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BACKUP_DEST_ID] = id
            prefs[KEY_BACKUP_DEST_LABEL] = label.trim().ifEmpty { android.os.Build.MODEL }
            prefs[KEY_BACKUP_DEST_PATH] = path.trim().ifEmpty { "/" }
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

    suspend fun saveSyncInterval(minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SYNC_INTERVAL] = minutes
        }
    }

    suspend fun saveCoilDiskCacheMb(mb: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_COIL_DISK_CACHE_MB] = mb
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    private fun parseBackupFolderSelection(
        foldersJson: String,
        setupComplete: Boolean
    ): BackupFolderSelection {
        if (foldersJson.isBlank()) {
            return if (setupComplete) {
                BackupFolderSelection(emptySet(), true)
            } else {
                BackupFolderSelection(null, false)
            }
        }

        return try {
            BackupFolderSelection(parseStringSet(foldersJson), true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse backup folders", e)
            if (setupComplete) {
                BackupFolderSelection(emptySet(), true)
            } else {
                BackupFolderSelection(null, false)
            }
        }
    }

    private fun parseStringSet(json: String): Set<String> {
        if (json.isBlank()) return emptySet()
        return try {
            val type = object : TypeToken<Set<String>>() {}.type
            cleanFolderPaths(Gson().fromJson<Set<String>>(json, type) ?: emptySet())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse string set", e)
            emptySet()
        }
    }

    private fun cleanFolderPaths(paths: Collection<String>): Set<String> {
        return paths
            .map { FolderPathMatcher.normalize(it) }
            .filter { it.isNotBlank() }
            .toSortedSet()
    }

    private fun defaultBackupDestinationPath(username: String?): String {
        return BackupDestinationDefaults.path(username)
    }
}
