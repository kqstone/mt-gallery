package com.kqstone.mtphotos.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.local.db.BackupStatus
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.data.repository.TimelineMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DayGroup(
    val date: String,
    val photos: List<UnifiedPhotoItem>
)

data class MonthGroup(
    val yearMonth: String,
    val displayTitle: String,
    val totalCount: Int,
    val days: List<DayGroup> = emptyList(),
    val isLoaded: Boolean = false
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
    val isSyncing: Boolean = false
)

class GalleryViewModel(
    private val galleryRepository: GalleryRepository,
    private val syncRepository: SyncRepository? = null
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
     * 加载时间线数据。
     * 如果 SyncRepository 可用且有本地数据，则使用合并模式。
     * 否则降级为纯云端模式。
     */
    fun loadTimeline() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            if (syncRepository != null) {
                // 尝试混合模式：从 Room DB 加载
                try {
                    val hasData = syncRepository.hasData()
                    if (hasData) {
                        loadFromRoom()
                        return@launch
                    }
                    // 首次加载：先用纯云端模式快速显示
                    loadFromCloud()
                } catch (e: Exception) {
                    // Room 失败，降级到纯云端
                    loadFromCloud()
                }
            } else {
                loadFromCloud()
            }
        }
    }

    /**
     * 从 Room DB 加载时间线（混合模式）
     */
    private suspend fun loadFromRoom() {
        try {
            val months = syncRepository!!.getTimelineMonths()
            val groups = months.map { tm ->
                MonthGroup(
                    yearMonth = tm.yearMonth,
                    displayTitle = formatYearMonth(tm.yearMonth),
                    totalCount = tm.count
                )
            }
            _uiState.value = _uiState.value.copy(
                months = groups,
                isLoading = false,
                isHybridMode = true
            )
            // 自动加载第一个月
            if (groups.isNotEmpty()) {
                loadMonthFiles(groups.first().yearMonth)
            }
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
        val result = galleryRepository.getTimeline()
        result.fold(
            onSuccess = { timelineMonths ->
                val groups = timelineMonths.map { tm ->
                    MonthGroup(
                        yearMonth = tm.yearMonth,
                        displayTitle = formatYearMonth(tm.yearMonth),
                        totalCount = tm.count
                    )
                }
                _uiState.value = _uiState.value.copy(
                    months = groups,
                    isLoading = false,
                    isHybridMode = false
                )
                if (groups.isNotEmpty()) {
                    loadMonthFiles(groups.first().yearMonth)
                }
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
                // 混合模式：重新同步
                try {
                    syncRepository.performFullSync()
                    loadFromRoom()
                    _uiState.value = _uiState.value.copy(isRefreshing = false)
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        error = e.message ?: "同步失败"
                    )
                }
            } else {
                // 纯云端模式
                val result = galleryRepository.getTimeline()
                result.fold(
                    onSuccess = { timelineMonths ->
                        val existingLoaded = _uiState.value.months.filter { it.isLoaded }
                        val groups = timelineMonths.map { tm ->
                            val existing = existingLoaded.find { it.yearMonth == tm.yearMonth }
                            existing ?: MonthGroup(
                                yearMonth = tm.yearMonth,
                                displayTitle = formatYearMonth(tm.yearMonth),
                                totalCount = tm.count
                            )
                        }
                        _uiState.value = _uiState.value.copy(
                            months = groups,
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

    fun loadMonthFiles(yearMonth: String) {
        val current = _uiState.value
        if (current.months.any { it.yearMonth == yearMonth && it.isLoaded }) return

        viewModelScope.launch {
            if (current.isHybridMode && syncRepository != null) {
                // 从 Room 加载
                try {
                    val photos = syncRepository.getMonthPhotos(yearMonth)
                    updateMonthWithPhotos(yearMonth, photos)
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        error = "加载 ${formatYearMonth(yearMonth)} 失败: ${e.message}"
                    )
                }
            } else {
                // 从云端加载
                val result = galleryRepository.getMonthFiles(yearMonth)
                result.fold(
                    onSuccess = { files ->
                        val photos = files.map { p ->
                            UnifiedPhotoItem(
                                cloudId = p.id,
                                md5 = p.md5,
                                fileName = p.fileName,
                                fileType = p.fileType,
                                mtime = p.mtime,
                                width = p.width,
                                height = p.height,
                                syncStatus = SyncStatus.CLOUD_ONLY,
                                backupStatus = BackupStatus.NOT_STARTED
                            )
                        }
                        updateMonthWithPhotos(yearMonth, photos)
                    },
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(
                            error = "加载 ${formatYearMonth(yearMonth)} 失败: ${e.message}"
                        )
                    }
                )
            }
        }
    }

    private fun updateMonthWithPhotos(yearMonth: String, photos: List<UnifiedPhotoItem>) {
        val days = photos
            .groupBy { extractDate(it.mtime) }
            .toSortedMap(compareByDescending { it })
            .map { (date, dayPhotos) -> DayGroup(date, dayPhotos) }

        val current = _uiState.value
        val updatedMonths = current.months.map { month ->
            if (month.yearMonth == yearMonth) {
                month.copy(days = days, isLoaded = true)
            } else {
                month
            }
        }
        _uiState.value = _uiState.value.copy(months = updatedMonths)
    }

    /**
     * 触发首次本地+云端全量同步
     */
    fun triggerFullSync(localFolders: Set<String>? = null) {
        if (syncRepository == null) return
        _uiState.value = _uiState.value.copy(isSyncing = true)
        viewModelScope.launch {
            try {
                syncRepository.performFullSync(localFolders)
                loadFromRoom()
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
                    totalCount = if (month.isLoaded) newTotal else month.totalCount
                )
            }.filter { !it.isLoaded || it.days.isNotEmpty() }
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
        // 优先使用本地缩略图缓存
        photo.thumbCachePath?.let { if (it.isNotEmpty()) return "file://$it" }
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
            .filter { it.isLoaded }
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

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val syncRepository: SyncRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GalleryViewModel(galleryRepository, syncRepository) as T
        }
    }
}
