package com.kqstone.mtphotos.ui.settings

import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import com.kqstone.mtphotos.MTPhotosApp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.kqstone.mtphotos.data.local.BackupDestinationDefaults
import com.kqstone.mtphotos.data.local.FolderPathMatcher
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
private val DEFAULT_BACKUP_DEST_PATH = BackupDestinationDefaults.path("")

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
    val isLoadingFolders: Boolean = false,
    val isRepairingBackups: Boolean = false,
    val backupRepairMessage: String? = null,
    val folders: List<FolderUiItem> = emptyList(),
    val selectedFolderCount: Int = 0,
    val historicalSelectedFolderCount: Int = 0,
    val backupDestinationId: Long = DEFAULT_BACKUP_DEST_ID,
    val backupDestinationLabel: String = DEFAULT_BACKUP_DEST_LABEL,
    val backupDestinationPath: String = DEFAULT_BACKUP_DEST_PATH,
    val defaultBackupDestinationLabel: String = DEFAULT_BACKUP_DEST_LABEL,
    val defaultBackupDestinationPath: String = DEFAULT_BACKUP_DEST_PATH,
    val isDefaultBackupDestination: Boolean = true,
    val backupDestinationNodes: List<BackupDestinationNode> = emptyList(),
    val backupDestinationBreadcrumbs: List<BackupDestinationBreadcrumb> = emptyList(),
    val isLoadingBackupDestinations: Boolean = false,
    val isCreatingFolder: Boolean = false,
    val backupDestinationError: String? = null,
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
    private var currentUsername = prefsManager.getUsernameSync()
    private var currentDeviceName = prefsManager.getDeviceNameSync()

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
                val destinationPath = path.ifBlank { DEFAULT_BACKUP_DEST_PATH }
                _uiState.value = _uiState.value.copy(
                    backupDestinationPath = destinationPath,
                    isDefaultBackupDestination = isDefaultBackupDestinationPath(destinationPath)
                )
            }
        }
        viewModelScope.launch {
            prefsManager.username.collect { username ->
                currentUsername = username
                updateDefaultBackupDestinationState()
            }
        }
        viewModelScope.launch {
            prefsManager.deviceName.collect { deviceName ->
                currentDeviceName = deviceName
                updateDefaultBackupDestinationState()
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
            _uiState.value = _uiState.value.copy(isLoadingFolders = true, error = null)
            try {
                val scannedFolders = localMediaScanner.getMediaFolders()
                    .map { it.copy(path = FolderPathMatcher.normalize(it.path)) }
                    .distinctBy { it.path }
                val scannedByPath = scannedFolders.associateBy { it.path }
                val folderSelection = prefsManager.getBackupFolderSelectionSync()
                val savedFolders = if (folderSelection.isConfigured) {
                    normalizeFolderPaths(folderSelection.folders.orEmpty())
                } else {
                    null
                }
                val historicalPaths = if (folderSelection.isConfigured) {
                    normalizeFolderPaths(
                        syncRepository.getKnownLocalFolders() +
                            prefsManager.getBackupFolderHistorySync()
                    )
                } else {
                    emptySet()
                }
                val defaultSelection = scannedFolders
                    .filter { it.depth == 0 }
                    .map { it.path }
                    .toSet()
                    .ifEmpty { scannedFolders.map { it.path }.toSet() }
                val selectedPaths = canonicalFolderSelection(savedFolders ?: defaultSelection)
                val scannedOrder = scannedFolders
                    .mapIndexed { index, folder -> folder.path to index }
                    .toMap()

                val mergedPaths = linkedSetOf<String>()
                scannedFolders.mapTo(mergedPaths) { it.path }
                expandFoldersWithParents(savedFolders.orEmpty()).forEach { mergedPaths.add(it) }
                expandFoldersWithParents(historicalPaths).forEach { mergedPaths.add(it) }

                val folderItems = orderFolderPaths(mergedPaths, scannedOrder).map { path ->
                    val scanned = scannedByPath[path]
                    FolderUiItem(
                        path = path,
                        displayName = scanned?.displayName ?: File(path).name.ifBlank { path },
                        fileCount = scanned?.fileCount ?: 0,
                        directFileCount = scanned?.directFileCount ?: 0,
                        depth = scanned?.depth ?: folderDepth(path),
                        isSelected = path in selectedPaths,
                        isCoveredBySelectedAncestor = path !in selectedPaths &&
                            FolderPathMatcher.hasAncestorSelected(path, selectedPaths),
                        hasLocalMedia = scanned != null,
                        isHistoricalOnly = scanned == null
                    )
                }

                _uiState.value = _uiState.value.copy(
                    folders = folderItems,
                    selectedFolderCount = folderItems.count { it.isSelected },
                    historicalSelectedFolderCount = folderItems.count {
                        it.isSelected && it.isHistoricalOnly
                    },
                    isLoadingFolders = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadFolders failed", e)
                _uiState.value = _uiState.value.copy(
                    error = e.message,
                    isLoadingFolders = false
                )
            }
        }
    }

    fun setBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsManager.saveBackupEnabled(enabled)
            if (enabled) {
                ensureDeviceFolder()
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
        BackupScheduler.triggerImmediateBackup(
            prefsManager.context,
            wifiOnly,
            replaceExisting = true
        )
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _uiState.value = _uiState.value.copy(isBackingUp = false)
            loadStats()
        }
    }

    fun ensureInitialBackupDefaults() {
        viewModelScope.launch {
            prefsManager.saveBackupEnabled(true)
            ensureDeviceFolder()
            val wifiOnly = prefsManager.getBackupWifiOnlySync()
            val syncInterval = prefsManager.getSyncIntervalSync().toLong()
            BackupScheduler.scheduleAll(prefsManager.context, wifiOnly, syncInterval)
        }
    }

    fun repairMissingBackups() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isRepairingBackups = true,
                backupRepairMessage = null,
                error = null
            )
            try {
                val selectedFolders = prefsManager.getBackupFolderSelectionSync().folders
                val result = syncRepository.repairMissingBackups(selectedFolders)
                val pending = syncRepository.getPendingBackupMedia(selectedFolders).size
                _uiState.value = _uiState.value.copy(
                    pendingCount = pending,
                    isRepairingBackups = false,
                    backupRepairMessage = if (result.resetCount > 0) {
                        "已扫描 ${result.scannedCount} 个文件，发现 ${result.resetCount} 个需要补传"
                    } else {
                        "已扫描 ${result.scannedCount} 个文件，未发现缺失备份"
                    }
                )
                if (result.resetCount > 0) {
                    BackupScheduler.triggerImmediateBackup(
                        prefsManager.context,
                        prefsManager.getBackupWifiOnlySync(),
                        replaceExisting = true
                    )
                }
                loadStats()
            } catch (e: Exception) {
                Log.e(TAG, "repairMissingBackups failed", e)
                _uiState.value = _uiState.value.copy(
                    isRepairingBackups = false,
                    error = e.message,
                    backupRepairMessage = "扫描未备份文件失败"
                )
            }
        }
    }

    fun toggleFolderSelection(path: String) {
        val current = _uiState.value.folders
        val target = current.firstOrNull { it.path == path } ?: return
        if (target.isCoveredBySelectedAncestor) return

        val selected = current.filter { it.isSelected }.map { it.path }.toMutableSet()
        if (target.isSelected) {
            selected.remove(path)
            selected.removeAll { FolderPathMatcher.isDescendantOf(it, path) }
        } else {
            selected.add(path)
            selected.removeAll { it != path && FolderPathMatcher.isDescendantOf(it, path) }
        }
        updateFolderSelection(selected)
    }

    private fun updateFolderSelection(selectedPaths: Set<String>) {
        val updated = _uiState.value.folders.map { folder ->
            val selected = folder.path in selectedPaths
            folder.copy(
                isSelected = selected,
                isCoveredBySelectedAncestor = !selected &&
                    FolderPathMatcher.hasAncestorSelected(folder.path, selectedPaths)
            )
        }
        _uiState.value = _uiState.value.copy(
            folders = updated,
            selectedFolderCount = updated.count { it.isSelected },
            historicalSelectedFolderCount = updated.count { it.isSelected && it.isHistoricalOnly }
        )
    }

    fun saveFolderSelection() {
        viewModelScope.launch {
            val selected = selectedFolderPathsAllowEmpty()
            prefsManager.saveBackupFolders(gson.toJson(selected))
            prefsManager.addBackupFolderHistory(selected.toList())

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

    fun completeInitialSetup(onComplete: () -> Unit) {
        viewModelScope.launch {
            val selected = selectedFolderPathsOrDefault()
            prefsManager.saveBackupEnabled(true)
            prefsManager.saveBackupFolders(gson.toJson(selected))
            prefsManager.addBackupFolderHistory(selected.toList())
            ensureDeviceFolder()
            prefsManager.saveFolderSetupComplete(true)

            val wifiOnly = prefsManager.getBackupWifiOnlySync()
            val syncInterval = prefsManager.getSyncIntervalSync().toLong()
            BackupScheduler.scheduleAll(prefsManager.context, wifiOnly, syncInterval)
            BackupScheduler.triggerSyncWork(prefsManager.context)

            onComplete()
        }
    }

    private fun selectedFolderPathsAllowEmpty(): Set<String> {
        return canonicalFolderSelection(
            _uiState.value.folders
                .filter { it.isSelected }
                .map { it.path }
                .toSet()
        )
    }

    private fun selectedFolderPathsOrDefault(): Set<String> {
        val selected = selectedFolderPathsAllowEmpty()
        if (selected.isNotEmpty()) return selected

        val fallback = _uiState.value.folders
            .filter { it.depth == 0 }
            .map { it.path }
            .toSet()
            .ifEmpty { _uiState.value.folders.map { it.path }.toSet() }
        return canonicalFolderSelection(fallback)
    }

    private fun normalizeFolderPaths(paths: Collection<String>): Set<String> {
        return paths
            .map { FolderPathMatcher.normalize(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun orderFolderPaths(
        paths: Set<String>,
        scannedOrder: Map<String, Int>
    ): List<String> {
        val childrenByParent = mutableMapOf<String?, MutableList<String>>()
        paths.forEach { path ->
            val parent = nearestKnownParent(path, paths)
            childrenByParent.getOrPut(parent) { mutableListOf() }.add(path)
        }

        fun sortKey(path: String): Pair<Int, String> {
            return (scannedOrder[path] ?: Int.MAX_VALUE) to path.lowercase()
        }

        val ordered = mutableListOf<String>()
        fun append(path: String) {
            ordered.add(path)
            childrenByParent[path]
                .orEmpty()
                .sortedWith(compareBy({ sortKey(it).first }, { sortKey(it).second }))
                .forEach { append(it) }
        }

        childrenByParent[null]
            .orEmpty()
            .sortedWith(compareBy({ sortKey(it).first }, { sortKey(it).second }))
            .forEach { append(it) }

        return ordered
    }

    private fun nearestKnownParent(path: String, knownPaths: Set<String>): String? {
        var parent = File(path).parent?.let(FolderPathMatcher::normalize)
        while (!parent.isNullOrBlank()) {
            if (parent in knownPaths) return parent
            val next = File(parent).parent?.let(FolderPathMatcher::normalize)
            if (next.isNullOrBlank() || next == parent) return null
            parent = next
        }
        return null
    }

    private fun expandFoldersWithParents(paths: Iterable<String>): List<String> {
        val expanded = linkedSetOf<String>()
        paths.forEach { rawPath ->
            val storageRoot = storageRootFor(rawPath)
            var current = FolderPathMatcher.normalize(rawPath)
            while (current.isNotBlank() && current != storageRoot) {
                expanded.add(current)
                val parent = File(current).parent?.let(FolderPathMatcher::normalize).orEmpty()
                if (parent.isBlank() || parent == current) break
                current = parent
            }
        }
        return expanded.sortedWith(compareBy<String> { folderDepth(it) }.thenBy { it.lowercase() })
    }

    private fun folderDepth(path: String): Int {
        val normalized = FolderPathMatcher.normalize(path)
        val root = storageRootFor(normalized)
        val relative = root?.let {
            normalized.removePrefix(it).trim('/')
        } ?: normalized.trim('/')
        return relative.split('/').count { it.isNotEmpty() }.minus(1).coerceAtLeast(0)
    }

    private fun storageRootFor(path: String): String? {
        val normalized = FolderPathMatcher.normalize(path)
        val externalRoot = FolderPathMatcher.normalize(Environment.getExternalStorageDirectory().absolutePath)
        if (FolderPathMatcher.contains(externalRoot, normalized)) return externalRoot

        val parts = normalized.split('/').filter { it.isNotEmpty() }
        return if (parts.size >= 3 && parts.first() == "storage") {
            "/" + parts.take(3).joinToString("/")
        } else {
            null
        }
    }

    private fun canonicalFolderSelection(paths: Set<String>): Set<String> {
        return paths.filterNot { path ->
            paths.any { candidate ->
                candidate != path && FolderPathMatcher.isDescendantOf(path, candidate)
            }
        }.toSet()
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
                ensureDeviceFolder()
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

    fun selectDefaultBackupDestination() {
        viewModelScope.launch {
            try {
                val deviceDestination = backupDestinationRepository.ensureDefaultDeviceDestination(
                    defaultDeviceName()
                )
                if (deviceDestination != null) {
                    saveBackupDestinationNow(
                        deviceDestination.id,
                        deviceDestination.name,
                        deviceDestination.path
                    )
                } else {
                    saveBackupDestinationNow(
                        DEFAULT_BACKUP_DEST_ID,
                        defaultDeviceName(),
                        defaultDestinationPath()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "selectDefaultBackupDestination failed", e)
                _uiState.value = _uiState.value.copy(
                    backupDestinationError = e.message ?: "保存默认备份目录失败"
                )
            }
        }
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
                saveBackupDestinationNow(id, label, path)
            } catch (e: Exception) {
                Log.e(TAG, "saveBackupDestination failed", e)
                _uiState.value = _uiState.value.copy(
                    backupDestinationError = e.message ?: "保存服务端目录失败"
                )
            }
        }
    }

    private suspend fun saveBackupDestinationNow(id: Long, label: String, path: String) {
        val destinationPath = path.ifBlank { DEFAULT_BACKUP_DEST_PATH }
        prefsManager.saveBackupDestination(id, label, path)
        _uiState.value = _uiState.value.copy(
            backupDestinationId = id,
            backupDestinationLabel = label.ifBlank { DEFAULT_BACKUP_DEST_LABEL },
            backupDestinationPath = destinationPath,
            isDefaultBackupDestination = isDefaultBackupDestinationPath(destinationPath),
            backupDestinationError = null
        )
    }

    private suspend fun ensureDeviceFolder() {
        if (prefsManager.isBackupDestinationConfiguredSync()) return

        try {
            val deviceDestination = backupDestinationRepository.ensureDefaultDeviceDestination(defaultDeviceName())
            if (deviceDestination != null) {
                saveBackupDestinationNow(
                    deviceDestination.id,
                    deviceDestination.name,
                    deviceDestination.path
                )
            } else {
                Log.w(TAG, "No default backup destination returned by enableFileBackup")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureDeviceFolder failed", e)
        }
    }

    private fun updateDefaultBackupDestinationState() {
        val defaultLabel = defaultDeviceName()
        val defaultPath = defaultDestinationPath()
        _uiState.value = _uiState.value.copy(
            defaultBackupDestinationLabel = defaultLabel,
            defaultBackupDestinationPath = defaultPath,
            isDefaultBackupDestination = _uiState.value.backupDestinationPath == defaultPath
        )
    }

    private fun defaultDeviceName(): String {
        return currentDeviceName.trim().trim('/').ifEmpty {
            Build.MODEL.trim().trim('/').ifEmpty { "Android" }
        }
    }

    private fun defaultDestinationPath(): String {
        return BackupDestinationDefaults.path(
            currentUsername,
            defaultDeviceName()
        )
    }

    private fun isDefaultBackupDestinationPath(path: String): Boolean {
        return path == defaultDestinationPath()
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
