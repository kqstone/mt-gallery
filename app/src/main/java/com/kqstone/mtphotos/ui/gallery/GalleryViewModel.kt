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
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.PersonItem
import com.kqstone.mtphotos.data.repository.PhotoItem
import com.kqstone.mtphotos.data.repository.SearchFilters
import com.kqstone.mtphotos.data.repository.SearchRequest
import com.kqstone.mtphotos.data.repository.SearchTipItem
import com.kqstone.mtphotos.data.repository.SearchType
import com.kqstone.mtphotos.data.repository.SyncRepository
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    val isHybridMode: Boolean = false,
    val isSyncing: Boolean = false,
    val syncProgressText: String? = null,
    val searchQuery: String = "",
    val searchType: SearchType = SearchType.AUTO,
    val searchFilters: SearchFilters = SearchFilters(),
    val searchSuggestions: List<SearchTipItem> = emptyList(),
    val searchPeople: List<PersonItem> = emptyList(),
    val searchLocations: List<LocationItem> = emptyList(),
    val isLoadingSearchFilters: Boolean = false,
    val isSearchMode: Boolean = false,
    val isSearching: Boolean = false,
    val isClipAvailable: Boolean = true
)

class GalleryViewModel(
    private val galleryRepository: GalleryRepository,
    private val syncRepository: SyncRepository? = null,
    private val prefsManager: PrefsManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState

    private var searchTipsJob: Job? = null
    private var filterCandidatesLoaded = false

    val selectionManager = SelectionManager(
        scope = viewModelScope,
        onDelete = { ids -> galleryRepository.deleteFiles(ids) },
        onError = { msg -> _uiState.value = _uiState.value.copy(error = msg) }
    )

    init {
        refreshClipAvailability()
        loadTimeline()
    }

    private fun refreshClipAvailability() {
        viewModelScope.launch {
            galleryRepository.isClipSearchAvailable().onSuccess { enabled ->
                _uiState.value = _uiState.value.copy(isClipAvailable = enabled)
            }
        }
    }

    private fun getFolderSelectionState(): BackupFolderSelection {
        return prefsManager?.getBackupFolderSelectionSync() ?: BackupFolderSelection(null, false)
    }

    fun loadTimeline() {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
            isSearchMode = false,
            isSearching = false
        )
        viewModelScope.launch {
            if (syncRepository != null) {
                try {
                    if (prefsManager != null && !prefsManager.isFolderSetupComplete()) {
                        loadFromCloud()
                        return@launch
                    }
                    val folderSelection = getFolderSelectionState()
                    if (prefsManager != null && !folderSelection.isConfigured) {
                        Log.w(TAG, "Folder setup complete but folder selection missing, fallback to cloud-only")
                        loadFromCloud()
                        return@launch
                    }
                    if (syncRepository.hasData()) {
                        syncRepository.reconcileFolderSelection(folderSelection.folders)
                        loadFromRoom(folderSelection.folders)
                        return@launch
                    }
                    triggerInitialSync(folderSelection.folders)
                } catch (e: Exception) {
                    Log.e(TAG, "Hybrid load failed, fallback to cloud", e)
                    loadFromCloud()
                }
            } else {
                loadFromCloud()
            }
        }
    }

    private fun triggerInitialSync(folders: Set<String>?) {
        if (syncRepository == null) return
        _uiState.value = _uiState.value.copy(
            isSyncing = true,
            isLoading = false,
            syncProgressText = "正在加载云端数据..."
        )

        viewModelScope.launch {
            try {
                syncRepository.performInitialSync(folders).collect { progress ->
                    when (progress.phase) {
                        "cloud" -> {
                            _uiState.value = _uiState.value.copy(syncProgressText = "正在加载云端数据...")
                        }
                        "cloud_indexed" -> {
                            _uiState.value = _uiState.value.copy(
                                syncProgressText = "云端 ${progress.cloudCount} 个文件，正在扫描本地..."
                            )
                        }
                        "scanning" -> {
                            _uiState.value = _uiState.value.copy(
                                syncProgressText = "已扫描 ${progress.scanned} 个本地文件..."
                            )
                        }
                        "done" -> {
                            _uiState.value = _uiState.value.copy(isSyncing = false, syncProgressText = null)
                            loadFromRoom(folders)
                            launch {
                                syncRepository.computeMd5InBackground()
                                loadFromRoom(folders)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initial sync failed", e)
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    syncProgressText = null,
                    error = "同步失败: ${e.message}"
                )
            }
        }
    }

    private suspend fun loadFromRoom(folders: Set<String>?) {
        try {
            val photos = syncRepository?.getAllPhotos(folders).orEmpty()
            _uiState.value = _uiState.value.copy(
                months = buildMonthGroups(photos),
                isLoading = false,
                isHybridMode = true
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "加载失败")
        }
    }

    private suspend fun loadFromCloud() {
        galleryRepository.getTimelineSnapshot().fold(
            onSuccess = { snapshot ->
                _uiState.value = _uiState.value.copy(
                    months = buildMonthGroups(snapshot.photos.map { it.toUnifiedPhotoItem() }),
                    isLoading = false,
                    isHybridMode = false
                )
            },
            onFailure = { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "加载失败")
            }
        )
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        viewModelScope.launch {
            if (_uiState.value.isSearchMode) {
                performSearch(isRefresh = true)
                return@launch
            }

            if (_uiState.value.isHybridMode && syncRepository != null) {
                try {
                    val folderSelection = getFolderSelectionState()
                    if (!folderSelection.isConfigured) {
                        Log.w(TAG, "Refresh skipped local scope because folder selection is missing")
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
                galleryRepository.getTimelineSnapshot().fold(
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

    fun quickRefresh() {
        if (_uiState.value.isSearchMode) return
        if (syncRepository == null || !_uiState.value.isHybridMode) return
        if (_uiState.value.isSyncing || _uiState.value.isRefreshing) return

        viewModelScope.launch {
            try {
                val folderSelection = getFolderSelectionState()
                if (!folderSelection.isConfigured) {
                    Log.w(TAG, "Quick refresh skipped local scope because folder selection is missing")
                    loadFromCloud()
                    return@launch
                }
                val folders = folderSelection.folders
                syncRepository.reconcileFolderSelection(folders)
                syncRepository.syncLocalMedia(folders)
                loadFromRoom(folders)
            } catch (e: Exception) {
                Log.w(TAG, "Quick refresh failed", e)
            }
        }
    }

    private fun buildMonthGroups(photos: List<UnifiedPhotoItem>): List<MonthGroup> {
        return photos
            .sortedByDescending { it.mtime }
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

    fun triggerFullSync(localFolders: Set<String>? = null) {
        if (syncRepository == null) return
        _uiState.value = _uiState.value.copy(isSyncing = true)
        viewModelScope.launch {
            try {
                val folders = localFolders ?: run {
                    val folderSelection = getFolderSelectionState()
                    if (!folderSelection.isConfigured) {
                        Log.w(TAG, "Full sync skipped local scope because folder selection is missing")
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
                _uiState.value = _uiState.value.copy(isSyncing = false, error = "同步失败: ${e.message}")
            }
        }
    }

    fun selectAll() {
        val allPhotos = _uiState.value.months.flatMap { month -> month.days.flatMap { it.photos } }
        selectionManager.selectAll(allPhotos.map { it.id })
    }

    fun deleteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        selectionManager.deleteSelected {
            val updatedMonths = _uiState.value.months.map { month ->
                val updatedDays = month.days.map { day ->
                    day.copy(photos = day.photos.filter { it.id !in selectedIds })
                }.filter { it.photos.isNotEmpty() }
                month.copy(days = updatedDays, totalCount = updatedDays.sumOf { it.photos.size })
            }.filter { it.days.isNotEmpty() }
            _uiState.value = _uiState.value.copy(months = updatedMonths)
        }
    }

    fun updateColumnCount(newCount: Int) {
        val clamped = newCount.coerceIn(2, 6)
        if (clamped != _uiState.value.columnCount) {
            _uiState.value = _uiState.value.copy(columnCount = clamped)
        }
    }

    fun getThumbUrl(photo: UnifiedPhotoItem): String {
        photo.thumbCachePath?.let {
            if (it.isNotEmpty() && isLocalFileValid(it)) return "file://$it"
        }
        photo.localUri?.let {
            if (it.isNotEmpty() && !photo.isStorageOptimized) return it
        }
        return if (photo.isVideo()) {
            galleryRepository.getVideoThumbUrl(photo.md5)
        } else {
            galleryRepository.getThumbUrlByMd5(photo.md5)
        }
    }

    fun getFullImageUrl(photo: UnifiedPhotoItem): String {
        photo.localUri?.let {
            if (it.isNotEmpty() && !photo.isStorageOptimized) return it
        }
        val cloudId = photo.cloudId ?: return ""
        return galleryRepository.getFullImageUrl(cloudId, photo.md5)
    }

    private fun isLocalFileValid(path: String): Boolean {
        return try {
            val file = File(path)
            file.exists() && file.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    fun getThumbUrl(md5: String, fileId: Double): String = galleryRepository.getThumbUrl(md5, fileId)

    fun getVideoThumbUrl(md5: String): String = galleryRepository.getVideoThumbUrl(md5)

    fun getFullImageUrl(id: Double, md5: String): String = galleryRepository.getFullImageUrl(id, md5)

    fun getAllLoadedPhotos(): List<UnifiedPhotoItem> {
        return _uiState.value.months.flatMap { month -> month.days.flatMap { it.photos } }
    }

    fun loadSearchFilterCandidates() {
        if (filterCandidatesLoaded || _uiState.value.isLoadingSearchFilters) return
        _uiState.value = _uiState.value.copy(isLoadingSearchFilters = true)
        viewModelScope.launch {
            val peopleResult = galleryRepository.getPeopleList()
            val locationsResult = galleryRepository.getAddressCountByCity()
            filterCandidatesLoaded = peopleResult.isSuccess || locationsResult.isSuccess
            _uiState.value = _uiState.value.copy(
                searchPeople = peopleResult.getOrDefault(emptyList()).take(12),
                searchLocations = locationsResult.getOrDefault(emptyList()).take(12),
                isLoadingSearchFilters = false
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchTipsJob?.cancel()
        searchTipsJob = viewModelScope.launch {
            val trimmed = query.trim()
            if (trimmed.isBlank()) {
                _uiState.value = _uiState.value.copy(searchSuggestions = emptyList())
                return@launch
            }
            delay(300)
            galleryRepository.getSearchTips(trimmed).onSuccess { tips ->
                if (_uiState.value.searchQuery.trim() == trimmed) {
                    _uiState.value = _uiState.value.copy(searchSuggestions = tips.take(6))
                }
            }
        }
    }

    fun applySuggestion(suggestion: String) {
        searchTipsJob?.cancel()
        _uiState.value = _uiState.value.copy(searchQuery = suggestion, searchSuggestions = emptyList())
        executeSearch()
    }

    fun updateSearchType(type: SearchType) {
        _uiState.value = _uiState.value.copy(searchType = type)
    }

    fun updatePersonFilter(person: PersonItem?) {
        _uiState.value = _uiState.value.copy(
            searchFilters = _uiState.value.searchFilters.copy(
                personId = person?.id,
                personName = person?.name
            )
        )
    }

    fun updateLocationFilter(location: LocationItem?) {
        _uiState.value = _uiState.value.copy(
            searchFilters = _uiState.value.searchFilters.copy(location = location?.city)
        )
    }

    fun clearSearch() {
        searchTipsJob?.cancel()
        _uiState.value = _uiState.value.copy(
            searchQuery = "",
            searchType = SearchType.AUTO,
            searchFilters = SearchFilters(),
            searchSuggestions = emptyList(),
            isSearchMode = false,
            isSearching = false,
            error = null
        )
        loadTimeline()
    }

    fun executeSearch() {
        searchTipsJob?.cancel()
        viewModelScope.launch {
            performSearch(isRefresh = false)
        }
    }

    private suspend fun performSearch(isRefresh: Boolean) {
        val state = _uiState.value
        val query = state.searchQuery.trim()
        val filters = state.searchFilters
        val hasFilters = !filters.personId.isNullOrBlank() ||
            !filters.personName.isNullOrBlank() ||
            !filters.location.isNullOrBlank()

        if (query.isBlank() && !hasFilters) {
            _uiState.value = _uiState.value.copy(
                isRefreshing = false,
                isSearchMode = false,
                isSearching = false,
                searchSuggestions = emptyList()
            )
            loadTimeline()
            return
        }

        if (state.searchType == SearchType.VISUAL_TEXT && !state.isClipAvailable) {
            _uiState.value = _uiState.value.copy(
                isRefreshing = false,
                isSearching = false,
                error = "当前服务端未启用文搜图"
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isSearchMode = true,
            isSearching = !isRefresh,
            isRefreshing = isRefresh,
            isLoading = false,
            error = null,
            searchSuggestions = emptyList()
        )

        galleryRepository.searchMedia(
            SearchRequest(query = query, type = state.searchType, filters = filters)
        ).fold(
            onSuccess = { photos ->
                _uiState.value = _uiState.value.copy(
                    months = buildMonthGroups(photos.map { it.toUnifiedPhotoItem() }),
                    isSearchMode = true,
                    isSearching = false,
                    isRefreshing = false,
                    error = if (photos.isEmpty()) "未找到匹配的云端媒体" else null
                )
            },
            onFailure = { e ->
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    isRefreshing = false,
                    error = e.message ?: "搜索失败"
                )
            }
        )
    }

    private fun formatYearMonth(ym: String): String {
        val parts = ym.split("-")
        return if (parts.size >= 2) {
            "${parts[0]}年${parts[1].toIntOrNull() ?: parts[1]}月"
        } else {
            ym
        }
    }

    private fun extractDate(mtime: String): String = mtime.take(10)

    fun getDeleteMode(): String = prefsManager?.getDeleteModeSync() ?: ""

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
