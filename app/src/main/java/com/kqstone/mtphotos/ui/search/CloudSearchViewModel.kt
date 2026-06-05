package com.kqstone.mtphotos.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.model.sortedForTimeline
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.PersonItem
import com.kqstone.mtphotos.data.repository.PhotoItem
import com.kqstone.mtphotos.data.repository.SearchFilters
import com.kqstone.mtphotos.data.repository.SearchRequest
import com.kqstone.mtphotos.data.repository.ServerOpTaskRepository
import com.kqstone.mtphotos.data.repository.SearchTipItem
import com.kqstone.mtphotos.data.repository.SearchType
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.data.repository.toCloudOnlyUnifiedPhotoItem
import com.kqstone.mtphotos.ui.gallery.DayGroup
import com.kqstone.mtphotos.ui.gallery.MonthGroup
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import com.kqstone.mtphotos.ui.util.ShareManager
import com.kqstone.mtphotos.ui.util.ThumbnailUrlResolver
import com.kqstone.mtphotos.ui.util.UiText
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.worker.BackupScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CloudSearchUiState(
    val query: String = "",
    val searchType: SearchType = SearchType.VISUAL_TEXT,
    val filters: SearchFilters = SearchFilters(),
    val suggestions: List<SearchTipItem> = emptyList(),
    val people: List<PersonItem> = emptyList(),
    val locations: List<LocationItem> = emptyList(),
    val isLoadingFilters: Boolean = false,
    val isActive: Boolean = false,
    val isSearching: Boolean = false,
    val isRefreshing: Boolean = false,
    val resultMonths: List<MonthGroup> = emptyList(),
    val error: UiText? = null,
    val isClipAvailable: Boolean = true,
    val columnCount: Int = 4
)

class CloudSearchViewModel(
    private val galleryRepository: GalleryRepository,
    private val syncRepository: SyncRepository? = null,
    private val serverOpTaskRepository: ServerOpTaskRepository? = null,
    private val appContext: Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudSearchUiState())
    val uiState: StateFlow<CloudSearchUiState> = _uiState

    private var searchTipsJob: Job? = null
    private var filterCandidatesLoaded = false

    val selectionManager = SelectionManager(
        scope = viewModelScope,
        onDelete = { ids -> galleryRepository.deleteFiles(ids) },
        onError = { msg -> _uiState.value = _uiState.value.copy(error = msg) }
    )

    val shareManager = ShareManager(galleryRepository, viewModelScope)

    init {
        refreshClipAvailability()
    }

    private fun refreshClipAvailability() {
        viewModelScope.launch {
            galleryRepository.isClipSearchAvailable().onSuccess { enabled ->
                _uiState.value = _uiState.value.copy(isClipAvailable = enabled)
            }
        }
    }

    fun loadFilterCandidates() {
        if (filterCandidatesLoaded || _uiState.value.isLoadingFilters) return
        _uiState.value = _uiState.value.copy(isLoadingFilters = true)
        viewModelScope.launch {
            val peopleResult = galleryRepository.getPeopleList()
            val locationsResult = galleryRepository.getAddressCountByCity()
            filterCandidatesLoaded = peopleResult.isSuccess || locationsResult.isSuccess
            _uiState.value = _uiState.value.copy(
                people = peopleResult.getOrDefault(emptyList())
                    .filter { it.hasSearchDisplayName() }
                    .take(12),
                locations = locationsResult.getOrDefault(emptyList()).take(12),
                isLoadingFilters = false
            )
        }
    }

    fun getPortraitUrl(personId: String, cover: Double): String {
        return galleryRepository.getPortraitUrl(personId, cover)
    }

    private fun PersonItem.hasSearchDisplayName(): Boolean {
        val normalized = name.trim()
        return normalized.isNotEmpty() &&
            normalized != "未知" &&
            normalized != "未命名" &&
            !normalized.equals("unknown", ignoreCase = true) &&
            !normalized.equals("unnamed", ignoreCase = true)
    }

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        searchTipsJob?.cancel()
        searchTipsJob = viewModelScope.launch {
            val trimmed = query.trim()
            if (trimmed.isBlank()) {
                _uiState.value = _uiState.value.copy(suggestions = emptyList())
                return@launch
            }
            delay(300)
            galleryRepository.getSearchTips(trimmed).onSuccess { tips ->
                if (_uiState.value.query.trim() == trimmed) {
                    _uiState.value = _uiState.value.copy(suggestions = tips.take(6))
                }
            }
        }
    }

    fun applySuggestion(suggestion: String) {
        searchTipsJob?.cancel()
        _uiState.value = _uiState.value.copy(query = suggestion, suggestions = emptyList())
        executeSearch()
    }

    fun updateSearchType(type: SearchType) {
        _uiState.value = _uiState.value.copy(searchType = type)
    }

    fun updatePersonFilter(person: PersonItem?) {
        _uiState.value = _uiState.value.copy(
            filters = _uiState.value.filters.copy(
                personId = person?.id,
                personName = person?.name
            )
        )
    }

    fun updateLocationFilter(location: LocationItem?) {
        _uiState.value = _uiState.value.copy(
            filters = _uiState.value.filters.copy(location = location?.city)
        )
    }

    fun clearSearch() {
        searchTipsJob?.cancel()
        selectionManager.clearSelection()
        _uiState.value = _uiState.value.copy(
            query = "",
            searchType = SearchType.VISUAL_TEXT,
            filters = SearchFilters(),
            suggestions = emptyList(),
            isActive = false,
            isSearching = false,
            isRefreshing = false,
            resultMonths = emptyList(),
            error = null
        )
    }

    fun executeSearch() {
        searchTipsJob?.cancel()
        viewModelScope.launch {
            performSearch(isRefresh = false)
        }
    }

    fun refresh() {
        if (!_uiState.value.isActive) return
        viewModelScope.launch {
            performSearch(isRefresh = true)
        }
    }

    private suspend fun performSearch(isRefresh: Boolean) {
        val state = _uiState.value
        val query = state.query.trim()
        val filters = state.filters
        val hasFilters = !filters.personId.isNullOrBlank() ||
            !filters.personName.isNullOrBlank() ||
            !filters.location.isNullOrBlank()

        if (query.isBlank() && !hasFilters) {
            clearSearch()
            return
        }

        if (state.searchType == SearchType.VISUAL_TEXT && !state.isClipAvailable) {
            _uiState.value = state.copy(
                isActive = true,
                isRefreshing = false,
                isSearching = false,
                resultMonths = emptyList(),
                error = UiText.StringResource(R.string.server_clip_disabled)
            )
            return
        }

        _uiState.value = state.copy(
            isActive = true,
            isSearching = !isRefresh,
            isRefreshing = isRefresh,
            suggestions = emptyList(),
            resultMonths = if (isRefresh) state.resultMonths else emptyList(),
            error = null
        )

        galleryRepository.searchMedia(
            SearchRequest(query = query, type = state.searchType, filters = filters)
        ).fold(
            onSuccess = { photos ->
                val unifiedPhotos = syncRepository?.hydrateCloudPhotos(photos)
                    ?: photos.map { it.toUnifiedPhotoItem() }
                val monthGroups = if (state.searchType == SearchType.VISUAL_TEXT) {
                    buildOrderedSearchGroup(unifiedPhotos)
                } else {
                    buildMonthGroups(unifiedPhotos)
                }
                _uiState.value = _uiState.value.copy(
                    resultMonths = monthGroups,
                    isActive = true,
                    isSearching = false,
                    isRefreshing = false,
                    error = if (photos.isEmpty()) UiText.StringResource(R.string.no_matching_cloud_media) else null
                )
            },
            onFailure = { e ->
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    isRefreshing = false,
                    error = if (e.message != null) UiText.DynamicString(e.message!!) else UiText.StringResource(R.string.search_failed_simple)
                )
            }
        )
    }

    private fun PhotoItem.toUnifiedPhotoItem(): UnifiedPhotoItem {
        return toCloudOnlyUnifiedPhotoItem()
    }

    private fun buildMonthGroups(photos: List<UnifiedPhotoItem>): List<MonthGroup> {
        return photos
            .sortedForTimeline()
            .groupBy { it.mtime.take(7) }
            .toSortedMap(compareByDescending { it })
            .map { (yearMonth, monthPhotos) ->
                MonthGroup(
                    yearMonth = yearMonth,
                    displayTitle = formatYearMonth(yearMonth),
                    totalCount = monthPhotos.size,
                    days = buildDayGroups(monthPhotos),
                    isLoaded = true
                )
            }
    }

    private fun buildDayGroups(photos: List<UnifiedPhotoItem>): List<DayGroup> {
        return photos
            .sortedForTimeline()
            .groupBy { it.mtime.take(10) }
            .toSortedMap(compareByDescending { it })
            .map { (date, dayPhotos) ->
                DayGroup(
                    date = date,
                    photos = dayPhotos,
                    addrSummary = buildAddrSummary(dayPhotos)
                )
            }
    }

    private fun buildOrderedSearchGroup(photos: List<UnifiedPhotoItem>): List<MonthGroup> {
        if (photos.isEmpty()) return emptyList()
        return listOf(
            MonthGroup(
                yearMonth = "search",
                displayTitle = UiText.StringResource(R.string.search_results),
                totalCount = photos.size,
                days = listOf(DayGroup("搜索结果", photos)),
                isLoaded = true
            )
        )
    }

    private fun buildAddrSummary(photos: List<UnifiedPhotoItem>): UiText? {
        val addrCounts = photos
            .mapNotNull { normalizeAddr(it.addr) }
            .groupingBy { it }
            .eachCount()

        if (addrCounts.isEmpty()) return null
        val primary = addrCounts.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        )?.key ?: return null

        return if (addrCounts.size == 1) {
            UiText.DynamicString(primary)
        } else {
            UiText.StringResource(R.string.addr_summary_format, primary, addrCounts.size)
        }
    }

    private fun normalizeAddr(addr: String?): String? {
        val normalized = addr?.trim().orEmpty()
        return normalized.takeIf {
            it.isNotEmpty() &&
                !it.equals("null", ignoreCase = true) &&
                it != "未知" &&
                it != "Unknown"
        }
    }

    private fun formatYearMonth(ym: String): UiText {
        val parts = ym.split("-")
        return if (parts.size >= 2) {
            val year = parts[0]
            val month = parts[1].toIntOrNull() ?: 1
            UiText.StringResource(R.string.year_month_format, year, month)
        } else {
            UiText.DynamicString(ym)
        }
    }

    fun updateColumnCount(newCount: Int) {
        val clamped = newCount.coerceIn(2, 6)
        if (clamped != _uiState.value.columnCount) {
            _uiState.value = _uiState.value.copy(columnCount = clamped)
        }
    }

    fun selectAll() {
        selectionManager.selectAll(getAllLoadedPhotos().map { it.id })
    }

    fun deleteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        selectionManager.deleteSelected(
            photos = getAllLoadedPhotos(),
            onDeletePhotos = { photos -> galleryRepository.deletePhotos(photos) }
        ) {
            _uiState.value = _uiState.value.copy(
                resultMonths = removeSelectedPhotos(_uiState.value.resultMonths, selectedIds)
            )
        }
    }

    fun shareSelected(context: android.content.Context) {
        val selectedIds = selectionManager.selectedPhotoIds.value
        val photos = getAllLoadedPhotos().filter { it.id in selectedIds }
        shareManager.sharePhotos(context, photos) {
            selectionManager.clearSelection()
        }
    }

    fun favoriteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        if (selectedIds.isEmpty()) return
        val selectedPhotos = getAllLoadedPhotos().filter { it.id in selectedIds }
        if (selectedPhotos.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            resultMonths = updateFavoriteState(_uiState.value.resultMonths, selectedIds, isFavorite = true)
        )
        selectionManager.clearSelection()

        val repo = serverOpTaskRepository ?: return
        viewModelScope.launch {
            repo.enqueueFavorites(selectedPhotos, isFavorite = true)
            if (selectedPhotos.any { it.cloudId != null }) {
                appContext?.let { BackupScheduler.triggerServerOpWork(it) }
            }
        }
    }

    private fun updateFavoriteState(
        groups: List<MonthGroup>,
        selectedIds: Set<Double>,
        isFavorite: Boolean
    ): List<MonthGroup> {
        return groups.map { month ->
            month.copy(
                days = month.days.map { day ->
                    day.copy(
                        photos = day.photos.map { photo ->
                            if (photo.id in selectedIds) photo.copy(isFavorite = isFavorite) else photo
                        }
                    )
                }
            )
        }
    }

    private fun removeSelectedPhotos(
        groups: List<MonthGroup>,
        selectedIds: Set<Double>
    ): List<MonthGroup> {
        return groups.map { month ->
            val updatedDays = month.days.map { day ->
                day.copy(photos = day.photos.filter { it.id !in selectedIds })
            }.filter { it.photos.isNotEmpty() }
            month.copy(days = updatedDays, totalCount = updatedDays.sumOf { it.photos.size })
        }.filter { it.days.isNotEmpty() || !it.isLoaded || it.totalCount > 0 }
    }

    fun getThumbUrl(photo: UnifiedPhotoItem): String {
        return ThumbnailUrlResolver.resolve(
            photo = photo,
            galleryRepository = galleryRepository,
            imageCloudUrl = { galleryRepository.getThumbUrlByMd5(it.md5) }
        )
    }

    fun getAllLoadedPhotos(): List<UnifiedPhotoItem> {
        return _uiState.value.resultMonths.flatMap { month ->
            month.days.flatMap { it.photos }
        }
    }

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val syncRepository: SyncRepository? = null,
        private val serverOpTaskRepository: ServerOpTaskRepository? = null,
        private val appContext: Context? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CloudSearchViewModel(
                galleryRepository,
                syncRepository,
                serverOpTaskRepository,
                appContext
            ) as T
        }
    }
}
