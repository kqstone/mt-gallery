package com.kqstone.mtphotos.ui.settings

import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import com.kqstone.mtphotos.MTPhotosApp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.kqstone.mtphotos.data.local.LocalMediaScanner
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.data.local.StorageOptimizer
import com.kqstone.mtphotos.data.repository.BackupDestinationNode
import com.kqstone.mtphotos.data.repository.BackupDestinationRepository
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.worker.BackupScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "BackupSettingsVM"
private const val DEFAULT_BACKUP_DEST_ID = 1L
private const val ROOT_BACKUP_DEST_PATH = "/"
private const val ROOT_BACKUP_DEST_LABEL = "/"
private val DEFAULT_BACKUP_DEST_LABEL = Build.MODEL
private val DEFAULT_BACKUP_DEST_PATH = defaultBackupDestPath()

private fun defaultBackupDestPath(username: String = ""): String {
    val cleanUser = username.trim().trim('/')
    val cleanModel = Build.MODEL.trim().trim('/').ifEmpty { "Android" }
    return if (cleanUser.isNotEmpty()) "/$cleanUser/$cleanModel" else "/$cleanModel"
}

data class BackupDestinationBreadcrumb(
    val id: Long,
    val label: String,
    val path: String
)

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
    val backupDestinationId: Long = DEFAULT_BACKUP_DEST_ID,
    val backupDestinationLabel: String = DEFAULT_BACKUP_DEST_LABEL,
    val backupDestinationPath: String = DEFAULT_BACKUP_DEST_PATH,
    val backupDestinationNodes: List<BackupDestinationNode> = emptyList(),
    val backupDestinationBreadcrumbs: List<BackupDestinationBreadcrumb> = emptyList(),
    val isLoadingBackupDestinations: Boolean = false,
    val isCreatingFolder: Boolean = false,
    val backupDestinationError: String? = null,
    val deleteMode: String = "",
    val syncInterval: Int = 60,
    val coilDiskCacheMb: Int = 512,
    val thumbnailCacheSizeFormatted: String = "0 MB",
    val mediaCacheSizeFormatted: String = "0 MB",
    val error: String? = null
)

class BackupSettingsViewModel(
    private val prefsManager: PrefsManager,
    private val syncRepository: SyncRepository,
    private val storageOptimizer: StorageOptimizer,
    private val localMediaScanner: LocalMediaScanner,
    private val backupDestinationRepository: BackupDestinationRepository
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
        viewModelScope.launch {
            prefsManager.coilDiskCacheMb.collect { mb ->
                _uiState.value = _uiState.value.copy(coilDiskCacheMb = mb)
            }
        }
        viewModelScope.launch {
            prefsManager.backupDestinationId.collect { id ->
                _uiState.value = _uiState.value.copy(backupDestinationId = id)
            }
        }
        viewModelScope.launch {
            prefsManager.backupDestinationLabel.collect { label ->
                _uiState.value = _uiState.value.copy(
                    backupDestinationLabel = label.ifBlank { DEFAULT_BACKUP_DEST_LABEL }
                )
            }
        }
        viewModelScope.launch {
            prefsManager.backupDestinationPath.collect { path ->
                _uiState.value = _uiState.value.copy(
                    backupDestinationPath = path.ifBlank { DEFAULT_BACKUP_DEST_PATH }
                )
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
                        syncRepository.performFullSync(prefsManager.getBackupFolderSelectionSync().folders)
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
                    .getPendingBackupMedia(prefsManager.getBackupFolderSelectionSync().folders)
                    .size
                val stats = storageOptimizer.getOptimizationStats()

                val context = prefsManager.context
                val coilCacheDir = context.cacheDir.resolve("coil_image_cache")
                val fullCacheDir = context.cacheDir.resolve("full_image_cache")
                val videoCacheDir = context.cacheDir.resolve("video_cache")
                val thumbnailCacheSize = getDirectorySize(coilCacheDir)
                val mediaCacheSize = getDirectorySize(fullCacheDir) + getDirectorySize(videoCacheDir)

                _uiState.value = _uiState.value.copy(
                    syncedCount = synced,
                    backedUpCount = backedUp,
                    backedUpSizeFormatted = formatSize(backedUpSize),
                    pendingCount = pending,
                    optimizableCount = stats.totalFiles,
                    optimizableSizeFormatted = stats.formattedSize(),
                    thumbnailCacheSizeFormatted = formatSize(thumbnailCacheSize),
                    mediaCacheSizeFormatted = formatSize(mediaCacheSize)
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
                val folderSelection = prefsManager.getBackupFolderSelectionSync()
                val savedFolders = if (folderSelection.isConfigured) folderSelection.folders else null
                val historicalPaths = if (folderSelection.isConfigured) {
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

    private fun getDirectorySize(directory: File): Long {
        var size: Long = 0
        val files = directory.listFiles()
        if (files != null) {
            for (file in files) {
                size += if (file.isDirectory) {
                    getDirectorySize(file)
                } else {
                    file.length()
                }
            }
        }
        return size
    }

    fun setCoilDiskCacheMb(mb: Int) {
        viewModelScope.launch {
            prefsManager.saveCoilDiskCacheMb(mb)
            MTPhotosApp.updateImageLoader(prefsManager.context)
            loadStats()
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearThumbnailCache() {
        viewModelScope.launch {
            try {
                val context = prefsManager.context
                val imageLoader = coil.Coil.imageLoader(context)
                imageLoader.diskCache?.clear()
                imageLoader.memoryCache?.clear()
                loadStats()
            } catch (e: Exception) {
                Log.e(TAG, "clearThumbnailCache failed", e)
            }
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearMediaCache() {
        viewModelScope.launch {
            try {
                val context = prefsManager.context

                // 1. 清理全尺寸大图 Coil 缓存
                val app = context.applicationContext as? MTPhotosApp
                app?.fullImageLoader?.diskCache?.clear()
                app?.fullImageLoader?.memoryCache?.clear()

                // 2. 清理 ExoPlayer 视频缓存
                app?.videoCache?.let { cache ->
                    try {
                        cache.keys.forEach { key ->
                            cache.removeResource(key)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to remove videoCache resources", e)
                    }
                }

                // 3. 清理大图与视频的物理缓存文件兜底
                deleteDirectoryContents(context.cacheDir.resolve("full_image_cache"))
                deleteDirectoryContents(context.cacheDir.resolve("video_cache"))

                loadStats()
            } catch (e: Exception) {
                Log.e(TAG, "clearMediaCache failed", e)
            }
        }
    }

    private fun deleteDirectoryContents(directory: File) {
        val files = directory.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.isDirectory) {
                    deleteDirectoryContents(file)
                }
                file.delete()
            }
        }
    }

    fun loadBackupDestinationRoot() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingBackupDestinations = true,
                backupDestinationError = null,
                backupDestinationBreadcrumbs = listOf(rootBreadcrumb())
            )
            try {
                val nodes = backupDestinationRepository.getRootDestinations()
                _uiState.value = _uiState.value.copy(
                    backupDestinationNodes = nodes,
                    isLoadingBackupDestinations = false
                )
                ensureDeviceFolder(nodes)
            } catch (e: Exception) {
                Log.e(TAG, "loadBackupDestinationRoot failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoadingBackupDestinations = false,
                    backupDestinationError = e.message ?: "加载服务端目录失败"
                )
            }
        }
    }

    fun openBackupDestination(node: BackupDestinationNode) {
        viewModelScope.launch {
            val breadcrumbs = buildList {
                val current = _uiState.value.backupDestinationBreadcrumbs
                if (current.isEmpty()) {
                    add(rootBreadcrumb())
                } else {
                    addAll(current)
                }
                add(node.toBreadcrumb())
            }

            _uiState.value = _uiState.value.copy(
                isLoadingBackupDestinations = true,
                backupDestinationError = null,
                backupDestinationBreadcrumbs = breadcrumbs
            )
            try {
                val nodes = backupDestinationRepository.getSubDestinations(node.id, node.path)
                _uiState.value = _uiState.value.copy(
                    backupDestinationNodes = nodes,
                    isLoadingBackupDestinations = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "openBackupDestination failed", e)
                _uiState.value = _uiState.value.copy(
                    backupDestinationBreadcrumbs = breadcrumbs.dropLast(1).ifEmpty { listOf(rootBreadcrumb()) },
                    isLoadingBackupDestinations = false,
                    backupDestinationError = e.message ?: "加载服务端目录失败"
                )
            }
        }
    }

    fun navigateUpBackupDestination() {
        val breadcrumbs = _uiState.value.backupDestinationBreadcrumbs
        if (breadcrumbs.size <= 1) {
            loadBackupDestinationRoot()
            return
        }

        val parent = breadcrumbs[breadcrumbs.lastIndex - 1]
        val trimmed = breadcrumbs.dropLast(1)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingBackupDestinations = true,
                backupDestinationError = null,
                backupDestinationBreadcrumbs = trimmed
            )
            try {
                val nodes = if (isRootDestination(parent.id, parent.path)) {
                    backupDestinationRepository.getRootDestinations()
                } else {
                    backupDestinationRepository.getSubDestinations(parent.id, parent.path)
                }
                _uiState.value = _uiState.value.copy(
                    backupDestinationNodes = nodes,
                    isLoadingBackupDestinations = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "navigateUpBackupDestination failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoadingBackupDestinations = false,
                    backupDestinationError = e.message ?: "加载服务端目录失败"
                )
            }
        }
    }

    fun selectCurrentBackupDestination() {
        val current = _uiState.value.backupDestinationBreadcrumbs.lastOrNull() ?: rootBreadcrumb()
        saveBackupDestination(current.id, current.label, current.path)
    }

    fun selectBackupDestination(node: BackupDestinationNode) {
        saveBackupDestination(node.id, node.name, node.path)
    }

    fun selectRootBackupDestination() {
        saveBackupDestination(
            id = DEFAULT_BACKUP_DEST_ID,
            label = ROOT_BACKUP_DEST_LABEL,
            path = ROOT_BACKUP_DEST_PATH
        )
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            val parent = _uiState.value.backupDestinationBreadcrumbs.lastOrNull() ?: rootBreadcrumb()
            _uiState.value = _uiState.value.copy(isCreatingFolder = true, backupDestinationError = null)
            try {
                backupDestinationRepository.createFolder(parent.id, name)
                val nodes = if (isRootDestination(parent.id, parent.path)) {
                    backupDestinationRepository.getRootDestinations()
                } else {
                    backupDestinationRepository.getSubDestinations(parent.id, parent.path)
                }
                _uiState.value = _uiState.value.copy(
                    backupDestinationNodes = nodes,
                    isCreatingFolder = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "createFolder failed", e)
                _uiState.value = _uiState.value.copy(
                    isCreatingFolder = false,
                    backupDestinationError = e.message ?: "创建文件夹失败"
                )
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        val mb = bytes / (1024.0 * 1024.0)
        return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(mb)
    }

    private fun saveBackupDestination(id: Long, label: String, path: String) {
        viewModelScope.launch {
            try {
                prefsManager.saveBackupDestination(id, label, path)
                _uiState.value = _uiState.value.copy(
                    backupDestinationId = id,
                    backupDestinationLabel = label.ifBlank { DEFAULT_BACKUP_DEST_LABEL },
                    backupDestinationPath = path.ifBlank { DEFAULT_BACKUP_DEST_PATH },
                    backupDestinationError = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "saveBackupDestination failed", e)
                _uiState.value = _uiState.value.copy(
                    backupDestinationError = e.message ?: "保存服务端目录失败"
                )
            }
        }
    }

    private suspend fun ensureDeviceFolder(rootNodes: List<BackupDestinationNode>) {
        if (prefsManager.isBackupDestinationConfiguredSync()) return

        val modelName = Build.MODEL
        val userRoot = findUserRoot(rootNodes) ?: run {
            Log.w(TAG, "No user root directory found for default backup destination")
            return
        }

        val children = backupDestinationRepository.getSubDestinations(userRoot.id, userRoot.path)
        val existing = children.find { it.name == modelName }
        if (existing != null) {
            saveBackupDestination(existing.id, existing.name, existing.path)
            return
        }

        try {
            backupDestinationRepository.createFolder(userRoot.id, modelName)
            val refreshed = backupDestinationRepository.getSubDestinations(userRoot.id, userRoot.path)
            val created = refreshed.find { it.name == modelName }
            if (created != null) {
                saveBackupDestination(created.id, created.name, created.path)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureDeviceFolder failed", e)
        }
    }

    private fun findUserRoot(rootNodes: List<BackupDestinationNode>): BackupDestinationNode? {
        val username = prefsManager.getUsernameSync().trim()
        if (username.isNotEmpty()) {
            rootNodes.firstOrNull { node ->
                node.name.equals(username, ignoreCase = true) ||
                    node.path.trim('/').equals(username, ignoreCase = true) ||
                    node.path.trim('/').endsWith("/$username", ignoreCase = true)
            }?.let { return it }
        }
        return rootNodes.singleOrNull()
    }

    private fun rootBreadcrumb(): BackupDestinationBreadcrumb {
        return BackupDestinationBreadcrumb(
            id = DEFAULT_BACKUP_DEST_ID,
            label = ROOT_BACKUP_DEST_LABEL,
            path = ROOT_BACKUP_DEST_PATH
        )
    }

    private fun BackupDestinationNode.toBreadcrumb(): BackupDestinationBreadcrumb {
        return BackupDestinationBreadcrumb(id = id, label = name, path = path)
    }

    private fun isRootDestination(id: Long, path: String): Boolean {
        return id == DEFAULT_BACKUP_DEST_ID && path == ROOT_BACKUP_DEST_PATH
    }

    class Factory(
        private val prefsManager: PrefsManager,
        private val syncRepository: SyncRepository,
        private val storageOptimizer: StorageOptimizer,
        private val localMediaScanner: LocalMediaScanner,
        private val backupDestinationRepository: BackupDestinationRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BackupSettingsViewModel(
                prefsManager,
                syncRepository,
                storageOptimizer,
                localMediaScanner,
                backupDestinationRepository
            ) as T
        }
    }
}
