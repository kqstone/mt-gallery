package com.kqstone.mtphotos.ui.gallery

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.local.BackupFolderSelection
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.data.local.db.BackupStatus
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.PhotoItem
import com.kqstone.mtphotos.data.repository.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "GalleryVM"

data class DayGroup(
    val date: String,
    val photos: List<UnifiedPhotoItem>
)

data class MonthGroup(
    val yearMonth: String,
    val displayTitle: String,
    val totalCount: Int,
    val days: List<DayGroup> = emptyList()
)

data class GalleryUiState(
    val months: List<MonthGroup> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val columnCount: Int = 4,
    /** true 时使用本地+云端合并模式，false 时使用纯云端模式 */
    val isHybridMode: Boolean = false,
    /** 是否正在执行首次同步 */
    val isSyncing: Boolean = false,
    /** 扫描进度文字 */
    val syncProgressText: String? = null
)

class GalleryViewModel(
    private val galleryRepository: GalleryRepository,
    private val syncRepository: SyncRepository? = null,
    private val prefsManager: PrefsManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState

    val selectionManager = SelectionManager(
        scope = viewModelScope,
        onDelete = { ids -> galleryRepository.deleteFiles(ids) },
        onError = { msg -> _uiState.value = _uiState.value.copy(error = msg) }
    )

    init {
        loadTimeline()
    }

    /**
     * 获取用户选择的文件夹路径
     */
    private fun getFolderSelectionState(): BackupFolderSelection {
        return prefsManager?.getBackupFolderSelectionSync() ?: BackupFolderSelection(null, false)
    }

    /**
     * 加载时间线数据。
     * 如果 SyncRepository 可用且有本地数据，使用合并模式。
     * 否则使用增量同步或纯云端模式。
     */
    fun loadTimeline() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            if (syncRepository != null) {
                try {
                    if (prefsManager != null && !prefsManager.isFolderSetupComplete()) {
                        loadFromCloud()
                        return@launch
                    }
                    val folderSelection = getFolderSelectionState()
                    if (prefsManager != null && !folderSelection.isConfigured) {
                        Log.w(
                            TAG,
                            "Folder setup is marked complete but backup folder selection is missing, fallback to cloud-only"
                        )
                        loadFromCloud()
                        return@launch
                    }
                    val hasData = syncRepository.hasData()
                    if (hasData) {
                        syncRepository.reconcileFolderSelection(folderSelection.folders)
                        loadFromRoom(folderSelection.folders)
                        return@launch
                    }
                    // 首次加载：使用渐进式同步
                    triggerInitialSync(folderSelection.folders)
                } catch (e: Exception) {
                    Log.e(TAG, "Hybrid load failed, falling back to cloud", e)
                    loadFromCloud()
                }
            } else {
                loadFromCloud()
            }
        }
    }

    /**
     * 触发渐进式同步 — 边扫描边显示
     */
    private fun triggerInitialSync(folders: Set<String>?) {
        if (syncRepository == null) return
        _uiState.value = _uiState.value.copy(isSyncing = true, isLoading = false, syncProgressText = "正在加载云端数据...")

        viewModelScope.launch {
            try {
                syncRepository.performInitialSync(folders).collect { progress ->
                    when (progress.phase) {
                        "cloud" -> {
                            _uiState.value = _uiState.value.copy(syncProgressText = "正在加载云端数据...")
                        }
                        "cloud_indexed" -> {
                            _uiState.value = _uiState.value.copy(syncProgressText = "云端 ${progress.cloudCount} 个文件，正在扫描本地...")
                            // 云端数据已写入 Room，先加载显示
                        }
                        "scanning" -> {
                            _uiState.value = _uiState.value.copy(syncProgressText = "已扫描 ${progress.scanned} 个本地文件...")
                            // 每批次刷新 UI
                        }
                        "done" -> {
                            _uiState.value = _uiState.value.copy(
                                isSyncing = false,
                                syncProgressText = null
                            )
                            loadFromRoom(folders)
                            // 后台补算 MD5
                            launch {
                                syncRepository.computeMd5InBackground()
                                // MD5 计算完后刷新一次
                                loadFromRoom(folders)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Incremental sync failed", e)
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    syncProgressText = null,
                    error = "同步失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 从 Room DB 加载时间线（混合模式）
     */
    private suspend fun loadFromRoom(folders: Set<String>?) {
        try {
            val photos = syncRepository!!.getAllPhotos(folders)
            _uiState.value = _uiState.value.copy(
                months = buildMonthGroups(photos),
                isLoading = false,
                isHybridMode = true
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = e.message ?: "加载失败"
            )
        }
    }

    /**
     * 从云端 API 加载时间线（纯云端模式，兼容原始逻辑）
     */
    private suspend fun loadFromCloud() {
        val result = galleryRepository.getTimelineSnapshot()
        result.fold(
            onSuccess = { snapshot ->
                _uiState.value = _uiState.value.copy(
                    months = buildMonthGroups(snapshot.photos.map { it.toUnifiedPhotoItem() }),
                    isLoading = false,
                    isHybridMode = false
                )
            },
            onFailure = { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "加载失败"
                )
            }
        )
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        viewModelScope.launch {
            if (_uiState.value.isHybridMode && syncRepository != null) {
                // 混合模式：使用增量同步（不计算 MD5，速度快）
                try {
                    val folderSelection = getFolderSelectionState()
                    if (!folderSelection.isConfigured) {
                        Log.w(TAG, "Refresh skipped local scope because backup folder selection is missing")
                        loadFromCloud()
                        _uiState.value = _uiState.value.copy(isRefreshing = false)
                        return@launch
                    }
                    val folders = folderSelection.folders
                    syncRepository.reconcileFolderSelection(folders)
                    syncRepository.refreshCloudState()
                    loadFromRoom(folders)
                    _uiState.value = _uiState.value.copy(isRefreshing = false)
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        error = e.message ?: "同步失败"
                    )
                }
            } else {
                // 纯云端模式
                val result = galleryRepository.getTimelineSnapshot()
                result.fold(
                    onSuccess = { snapshot ->
                        _uiState.value = _uiState.value.copy(
                            months = buildMonthGroups(snapshot.photos.map { it.toUnifiedPhotoItem() }),
                            isRefreshing = false
                        )
                    },
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(
                            isRefreshing = false,
                            error = e.message ?: "刷新失败"
                        )
                    }
                )
            }
        }
    }

    /**
     * 轻量级刷新：App 回到前台时调用。
     * 仅做一次快速本地扫描（不计算 MD5），检测新增的媒体文件。
     * 比 refresh() 更快，不显示刷新动画。
     */
    fun quickRefresh() {
        if (syncRepository == null || !_uiState.value.isHybridMode) return
        if (_uiState.value.isSyncing || _uiState.value.isRefreshing) return

        viewModelScope.launch {
            try {
                val folderSelection = getFolderSelectionState()
                if (!folderSelection.isConfigured) {
                    Log.w(TAG, "Quick refresh skipped local scope because backup folder selection is missing")
                    loadFromCloud()
                    return@launch
                }
                syncRepository.reconcileFolderSelection(folderSelection.folders)
                val folders = folderSelection.folders
                syncRepository.syncLocalMedia(folders)
                loadFromRoom(folders)
            } catch (e: Exception) {
                Log.w(TAG, "Quick refresh failed", e)
            }
        }
    }

    private fun buildMonthGroups(photos: List<UnifiedPhotoItem>): List<MonthGroup> {
        return photos
            .groupBy { it.mtime.take(7) }
            .toSortedMap(compareByDescending { it })
            .map { (yearMonth, monthPhotos) ->
                val days = monthPhotos
                    .groupBy { extractDate(it.mtime) }
                    .toSortedMap(compareByDescending { it })
                    .map { (date, dayPhotos) -> DayGroup(date, dayPhotos) }
                MonthGroup(
                    yearMonth = yearMonth,
                    displayTitle = formatYearMonth(yearMonth),
                    totalCount = monthPhotos.size,
                    days = days
                )
            }
    }

    private fun PhotoItem.toUnifiedPhotoItem(): UnifiedPhotoItem {
        return UnifiedPhotoItem(
            cloudId = id,
            md5 = md5,
            fileName = fileName,
            fileType = fileType,
            mtime = mtime,
            width = width,
            height = height,
            syncStatus = SyncStatus.CLOUD_ONLY,
            backupStatus = BackupStatus.NOT_STARTED
        )
    }

    /**
     * 触发首次本地+云端全量同步
     */
    fun triggerFullSync(localFolders: Set<String>? = null) {
        if (syncRepository == null) return
        _uiState.value = _uiState.value.copy(isSyncing = true)
        viewModelScope.launch {
            try {
                val folders = localFolders ?: run {
                    val folderSelection = getFolderSelectionState()
                    if (!folderSelection.isConfigured) {
                        Log.w(TAG, "Full sync skipped local scope because backup folder selection is missing")
                        loadFromCloud()
                        _uiState.value = _uiState.value.copy(isSyncing = false)
                        return@launch
                    }
                    folderSelection.folders
                }
                syncRepository.reconcileFolderSelection(folders)
                syncRepository.performFullSync(folders)
                loadFromRoom(folders)
                _uiState.value = _uiState.value.copy(isSyncing = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    error = "同步失败: ${e.message}"
                )
            }
        }
    }

    fun selectAll() {
        val allPhotos = _uiState.value.months
            .flatMap { month -> month.days.flatMap { it.photos } }
        selectionManager.selectAll(allPhotos.map { it.id })
    }

    fun deleteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        selectionManager.deleteSelected {
            val updatedMonths = _uiState.value.months.map { month ->
                val updatedDays = month.days.map { day ->
                    day.copy(photos = day.photos.filter { it.id !in selectedIds })
                }.filter { it.photos.isNotEmpty() }
                val newTotal = updatedDays.sumOf { it.photos.size }
                month.copy(
                    days = updatedDays,
                    totalCount = newTotal
                )
            }.filter { it.days.isNotEmpty() }
            _uiState.value = _uiState.value.copy(months = updatedMonths)
        }
    }

    // Column count for pinch-to-zoom
    fun updateColumnCount(newCount: Int) {
        val clamped = newCount.coerceIn(2, 6)
        if (clamped != _uiState.value.columnCount) {
            _uiState.value = _uiState.value.copy(columnCount = clamped)
        }
    }

    fun getThumbUrl(photo: UnifiedPhotoItem): String {
        // 优先使用本地缩略图缓存（验证文件存在）
        photo.thumbCachePath?.let {
            if (it.isNotEmpty() && isLocalFileValid(it)) return "file://$it"
        }
        // 有本地 URI 且未存储优化 → 使用本地 URI
        photo.localUri?.let { if (it.isNotEmpty() && !photo.isStorageOptimized) return it }
        // 使用云端缩略图
        val md5 = photo.md5
        return if (photo.isVideo()) {
            galleryRepository.getVideoThumbUrl(md5)
        } else {
            galleryRepository.getThumbUrlByMd5(md5)
        }
    }

    fun getFullImageUrl(photo: UnifiedPhotoItem): String {
        // 有本地 URI 且未存储优化 → 使用本地文件
        photo.localUri?.let { if (it.isNotEmpty() && !photo.isStorageOptimized) return it }
        // 使用云端原图
        val cloudId = photo.cloudId ?: return ""
        return galleryRepository.getFullImageUrl(cloudId, photo.md5)
    }

    /**
     * 检查本地文件路径是否有效（存在且可读）。
     * 用于验证 thumbCachePath 等本地缓存文件。
     */
    private fun isLocalFileValid(path: String): Boolean {
        return try {
            val file = File(path)
            file.exists() && file.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    // 兼容旧代码的方法
    fun getThumbUrl(md5: String, fileId: Double): String {
        return galleryRepository.getThumbUrl(md5, fileId)
    }

    fun getVideoThumbUrl(md5: String): String {
        return galleryRepository.getVideoThumbUrl(md5)
    }

    fun getFullImageUrl(id: Double, md5: String): String {
        return galleryRepository.getFullImageUrl(id, md5)
    }

    fun getAllLoadedPhotos(): List<UnifiedPhotoItem> {
        return _uiState.value.months
            .flatMap { month ->
                month.days.flatMap { it.photos }
            }
    }

    private fun formatYearMonth(ym: String): String {
        val parts = ym.split("-")
        return if (parts.size >= 2) "${parts[0]}年${parts[1].toIntOrNull() ?: parts[1]}月" else ym
    }

    private fun extractDate(mtime: String): String {
        return mtime.take(10) // "2024-01-15" from "2024-01-15T12:30:00"
    }

    /** 获取删除模式，空字符串表示用户尚未选择 */
    fun getDeleteMode(): String = prefsManager?.getDeleteModeSync() ?: ""

    /** 保存删除模式 */
    fun saveDeleteMode(mode: String) {
        viewModelScope.launch {
            prefsManager?.saveDeleteMode(mode)
        }
    }

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val syncRepository: SyncRepository? = null,
        private val prefsManager: PrefsManager? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GalleryViewModel(galleryRepository, syncRepository, prefsManager) as T
        }
    }
}
