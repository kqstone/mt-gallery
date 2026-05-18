package com.kqstone.mtphotos.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kqstone.mtphotos.data.local.LocalMediaScanner
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.data.local.StorageOptimizer
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.worker.BackupScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "BackupSettingsVM"

data class BackupSettingsUiState(
    val backupEnabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val syncedCount: Int = 0,
    val backedUpCount: Int = 0,
    val backedUpSizeFormatted: String = "0 MB",
    val pendingCount: Int = 0,
    val optimizableCount: Int = 0,
    val optimizableSizeFormatted: String = "0 MB",
    val isBackingUp: Boolean = false,
    val isOptimizing: Boolean = false,
    val isSyncing: Boolean = false,
    val folders: List<FolderUiItem> = emptyList(),
    val selectedFolderCount: Int = 0,
    val historicalSelectedFolderCount: Int = 0,
    val deleteMode: String = "",
    val syncInterval: Int = 60,
    val error: String? = null
)

class BackupSettingsViewModel(
    private val prefsManager: PrefsManager,
    private val syncRepository: SyncRepository,
    private val storageOptimizer: StorageOptimizer,
    private val localMediaScanner: LocalMediaScanner
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupSettingsUiState())
    val uiState: StateFlow<BackupSettingsUiState> = _uiState

    private val gson = Gson()

    init {
        viewModelScope.launch {
            prefsManager.backupEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(backupEnabled = enabled)
            }
        }
        viewModelScope.launch {
            prefsManager.backupWifiOnly.collect { wifiOnly ->
                _uiState.value = _uiState.value.copy(wifiOnly = wifiOnly)
            }
        }
        viewModelScope.launch {
            prefsManager.deleteMode.collect { mode ->
                _uiState.value = _uiState.value.copy(deleteMode = mode)
            }
        }
        viewModelScope.launch {
            prefsManager.syncInterval.collect { interval ->
                _uiState.value = _uiState.value.copy(syncInterval = interval)
            }
        }
    }

    fun loadStats() {
        viewModelScope.launch {
            try {
                if (!syncRepository.hasData()) {
                    Log.d(TAG, "Room DB is empty, triggering initial sync...")
                    _uiState.value = _uiState.value.copy(isSyncing = true)
                    try {
                        syncRepository.performFullSync(parseSavedFolders(prefsManager.getBackupFoldersSync()))
                        Log.d(TAG, "Initial sync completed")
                    } catch (e: Exception) {
                        Log.e(TAG, "Initial sync failed", e)
                    } finally {
                        _uiState.value = _uiState.value.copy(isSyncing = false)
                    }
                }

                val synced = syncRepository.getSyncedCount()
                val backedUp = syncRepository.getBackedUpCount()
                val backedUpSize = syncRepository.getBackedUpSize()
                val pending = syncRepository
                    .getPendingBackupMedia(parseSavedFolders(prefsManager.getBackupFoldersSync()))
                    .size
                val stats = storageOptimizer.getOptimizationStats()

                _uiState.value = _uiState.value.copy(
                    syncedCount = synced,
                    backedUpCount = backedUp,
                    backedUpSizeFormatted = formatSize(backedUpSize),
                    pendingCount = pending,
                    optimizableCount = stats.totalFiles,
                    optimizableSizeFormatted = stats.formattedSize()
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadStats failed", e)
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun loadFolders() {
        viewModelScope.launch {
            try {
                val scannedFolders = localMediaScanner.getMediaFolders()
                val scannedByPath = scannedFolders.associateBy { it.path }
                val savedFolders = parseSavedFolders(prefsManager.getBackupFoldersSync())
                val historicalPaths = if (savedFolders != null) {
                    syncRepository.getKnownLocalFolders()
                } else {
                    emptyList()
                }

                val mergedPaths = linkedSetOf<String>()
                scannedFolders.mapTo(mergedPaths) { it.path }
                savedFolders?.forEach { mergedPaths.add(it) }
                historicalPaths.forEach { mergedPaths.add(it) }

                val folderItems = mergedPaths.map { path ->
                    val scanned = scannedByPath[path]
                    FolderUiItem(
                        path = path,
                        displayName = scanned?.displayName ?: File(path).name.ifBlank { path },
                        fileCount = scanned?.fileCount ?: 0,
                        isSelected = savedFolders?.let { path in it } ?: true,
                        hasLocalMedia = scanned != null,
                        isHistoricalOnly = scanned == null
                    )
                }

                _uiState.value = _uiState.value.copy(
                    folders = folderItems,
                    selectedFolderCount = folderItems.count { it.isSelected },
                    historicalSelectedFolderCount = folderItems.count {
                        it.isSelected && it.isHistoricalOnly
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadFolders failed", e)
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun setBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsManager.saveBackupEnabled(enabled)
            if (enabled) {
                val wifiOnly = prefsManager.getBackupWifiOnlySync()
                val syncInterval = prefsManager.getSyncIntervalSync().toLong()
                BackupScheduler.scheduleAll(prefsManager.context, wifiOnly, syncInterval)
                if (!syncRepository.hasData()) {
                    loadStats()
                }
            } else {
                BackupScheduler.cancelAll(prefsManager.context)
            }
        }
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            prefsManager.saveBackupWifiOnly(wifiOnly)
            if (prefsManager.getBackupEnabledSync()) {
                val syncInterval = prefsManager.getSyncIntervalSync().toLong()
                BackupScheduler.scheduleAll(prefsManager.context, wifiOnly, syncInterval)
            }
        }
    }

    fun setDeleteMode(mode: String) {
        viewModelScope.launch {
            prefsManager.saveDeleteMode(mode)
        }
    }

    fun setSyncInterval(minutes: Int) {
        viewModelScope.launch {
            prefsManager.saveSyncInterval(minutes)
            if (prefsManager.getBackupEnabledSync()) {
                val wifiOnly = prefsManager.getBackupWifiOnlySync()
                BackupScheduler.scheduleAll(prefsManager.context, wifiOnly, minutes.toLong())
            }
        }
    }

    fun triggerBackupNow() {
        val wifiOnly = _uiState.value.wifiOnly
        _uiState.value = _uiState.value.copy(isBackingUp = true)
        BackupScheduler.triggerImmediateBackup(prefsManager.context, wifiOnly)
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _uiState.value = _uiState.value.copy(isBackingUp = false)
            loadStats()
        }
    }

    fun toggleFolderSelection(path: String) {
        val current = _uiState.value.folders.toMutableList()
        val index = current.indexOfFirst { it.path == path }
        if (index >= 0) {
            current[index] = current[index].copy(isSelected = !current[index].isSelected)
            _uiState.value = _uiState.value.copy(
                folders = current,
                selectedFolderCount = current.count { it.isSelected },
                historicalSelectedFolderCount = current.count {
                    it.isSelected && it.isHistoricalOnly
                }
            )
        }
    }

    fun saveFolderSelection() {
        viewModelScope.launch {
            val selected = _uiState.value.folders
                .filter { it.isSelected }
                .map { it.path }
                .toSet()
            prefsManager.saveBackupFolders(gson.toJson(selected))

            _uiState.value = _uiState.value.copy(isSyncing = true)
            try {
                syncRepository.reconcileFolderSelection(selected)
                syncRepository.performFullSync(selected)

                val synced = syncRepository.getSyncedCount()
                val backedUp = syncRepository.getBackedUpCount()
                val backedUpSize = syncRepository.getBackedUpSize()
                val pending = syncRepository.getPendingBackupMedia(selected).size
                val stats = storageOptimizer.getOptimizationStats()

                _uiState.value = _uiState.value.copy(
                    syncedCount = synced,
                    backedUpCount = backedUp,
                    backedUpSizeFormatted = formatSize(backedUpSize),
                    pendingCount = pending,
                    optimizableCount = stats.totalFiles,
                    optimizableSizeFormatted = stats.formattedSize(),
                    isSyncing = false
                )
                loadFolders()
            } catch (e: Exception) {
                Log.e(TAG, "Re-sync after folder change failed", e)
                _uiState.value = _uiState.value.copy(isSyncing = false, error = e.message)
            }
        }
    }

    fun optimizeStorage() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isOptimizing = true)
            try {
                val stats = storageOptimizer.getOptimizationStats()
                storageOptimizer.optimizeStorage(stats.files)
                loadStats()
                loadFolders()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _uiState.value = _uiState.value.copy(isOptimizing = false)
        }
    }

    private fun parseSavedFolders(savedFoldersJson: String): Set<String>? {
        if (savedFoldersJson.isBlank()) return null
        return try {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(savedFoldersJson, type) ?: emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved folders, falling back to all folders", e)
            null
        }
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        val mb = bytes / (1024.0 * 1024.0)
        return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(mb)
    }

    class Factory(
        private val prefsManager: PrefsManager,
        private val syncRepository: SyncRepository,
        private val storageOptimizer: StorageOptimizer,
        private val localMediaScanner: LocalMediaScanner
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BackupSettingsViewModel(
                prefsManager,
                syncRepository,
                storageOptimizer,
                localMediaScanner
            ) as T
        }
    }
}
