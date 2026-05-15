package com.kqstone.mtphotos.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.local.LocalMediaScanner
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.data.local.StorageOptimizer
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.worker.BackupScheduler
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class BackupSettingsUiState(
    val backupEnabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val backedUpCount: Int = 0,
    val backedUpSizeFormatted: String = "0 MB",
    val pendingCount: Int = 0,
    val optimizableCount: Int = 0,
    val optimizableSizeFormatted: String = "0 MB",
    val isBackingUp: Boolean = false,
    val isOptimizing: Boolean = false,
    val folders: List<FolderUiItem> = emptyList(),
    val selectedFolderCount: Int = 0,
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
    }

    fun loadStats() {
        viewModelScope.launch {
            try {
                val backedUp = syncRepository.getBackedUpCount()
                val backedUpSize = syncRepository.getBackedUpSize()
                val pending = syncRepository.getPendingBackupMedia().size
                val stats = storageOptimizer.getOptimizationStats()

                _uiState.value = _uiState.value.copy(
                    backedUpCount = backedUp,
                    backedUpSizeFormatted = formatSize(backedUpSize),
                    pendingCount = pending,
                    optimizableCount = stats.totalFiles,
                    optimizableSizeFormatted = stats.formattedSize()
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun loadFolders() {
        viewModelScope.launch {
            try {
                val folders = localMediaScanner.getMediaFolders()
                val savedFoldersJson = prefsManager.getBackupFoldersSync()
                val savedFolders: Set<String> = if (savedFoldersJson.isNotEmpty()) {
                    try {
                        val type = object : TypeToken<Set<String>>() {}.type
                        gson.fromJson(savedFoldersJson, type)
                    } catch (e: Exception) {
                        emptySet()
                    }
                } else {
                    emptySet()
                }

                val folderItems = folders.map { f ->
                    FolderUiItem(
                        path = f.path,
                        displayName = f.displayName,
                        fileCount = f.fileCount,
                        isSelected = savedFolders.isEmpty() || f.path in savedFolders
                    )
                }

                _uiState.value = _uiState.value.copy(
                    folders = folderItems,
                    selectedFolderCount = if (savedFolders.isEmpty()) 0 else savedFolders.size
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun setBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsManager.saveBackupEnabled(enabled)
            if (enabled) {
                val wifiOnly = prefsManager.getBackupWifiOnlySync()
                BackupScheduler.schedulePeriodicBackup(prefsManager.context, wifiOnly)
            } else {
                BackupScheduler.cancelPeriodicBackup(prefsManager.context)
            }
        }
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            prefsManager.saveBackupWifiOnly(wifiOnly)
            if (prefsManager.getBackupEnabledSync()) {
                BackupScheduler.schedulePeriodicBackup(prefsManager.context, wifiOnly)
            }
        }
    }

    fun triggerBackupNow() {
        val wifiOnly = _uiState.value.wifiOnly
        _uiState.value = _uiState.value.copy(isBackingUp = true)
        BackupScheduler.triggerImmediateBackup(prefsManager.context, wifiOnly)
        // 备份是异步的，状态会通过 WorkManager 观察更新
        // 这里简单延迟重置状态
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
                selectedFolderCount = current.count { it.isSelected }
            )
        }
    }

    fun saveFolderSelection() {
        viewModelScope.launch {
            val selected = _uiState.value.folders
                .filter { it.isSelected }
                .map { it.path }
                .toSet()
            val json = gson.toJson(selected)
            prefsManager.saveBackupFolders(json)
        }
    }

    fun optimizeStorage() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isOptimizing = true)
            try {
                val stats = storageOptimizer.getOptimizationStats()
                storageOptimizer.optimizeStorage(stats.files)
                loadStats() // 刷新统计
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _uiState.value = _uiState.value.copy(isOptimizing = false)
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
            return BackupSettingsViewModel(prefsManager, syncRepository, storageOptimizer, localMediaScanner) as T
        }
    }
}
