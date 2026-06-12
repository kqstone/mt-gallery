package com.kqstone.mtphotos.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.model.sortedForTimeline
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.LocationItem
import com.kqstone.mtphotos.data.repository.MediaUiMutation
import com.kqstone.mtphotos.data.repository.MediaUiMutationBus
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
import com.kqstone.mtphotos.ui.gallery.removePhotos
import com.kqstone.mtphotos.ui.gallery.updateFavorite
import com.kqstone.mtphotos.ui.gallery.updateHide
import com.kqstone.mtphotos.ui.util.PullRefreshSupport
import com.kqstone.mtphotos.ui.util.ShareManager
import com.kqstone.mtphotos.ui.util.UiText
import com.kqstone.mtphotos.ui.media.MediaThumbnailResolver
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.worker.BackupScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val MaxSearchHistoryItems = 20

data class SearchHistoryItem(
    val query: String = "",
    val searchType: SearchType = SearchType.VISUAL_TEXT,
    val filters: SearchFilters = SearchFilters(),
    val updatedAt: Long = 0L
)

private data class StoredSearchHistoryItem(
    val query: String? = null,
    val searchType: SearchType? = null,
    val filters: SearchFilters? = null,
    val updatedAt: Long? = null
)

data class CloudSearchUiState(
    val query: String = "",
    val searchType: SearchType = SearchType.VISUAL_TEXT,
    val filters: SearchFilters = SearchFilters(),
    val searchHistory: List<SearchHistoryItem> = emptyList(),
    val suggestions: List<SearchTipItem> = emptyList(),
    val people: List<PersonItem> = emptyList(),
    val locations: List<LocationItem> = emptyList(),
    val isLoadingFilters: Boolean = false,
    val isActive: Boolean = false,
    val isSearching: Boolean = false,
    val isRefreshing: Boolean = false,
    val resultMonths: List<MonthGroup> = emptyList(),
    val error: UiText? = null,
    val toastMessage: UiText? = null,
    val isClipAvailable: Boolean = true,
    val columnCount: Int = 4
)

class CloudSearchViewModel(
    private val galleryRepository: GalleryRepository,
    private val prefsManager: PrefsManager? = null,
    private val syncRepository: SyncRepository? = null,
    private val serverOpTaskRepository: ServerOpTaskRepository? = null,
    private val appContext: Context? = null,
    private val mediaUiMutationBus: MediaUiMutationBus? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudSearchUiState())
    val uiState: StateFlow<CloudSearchUiState> = _uiState

    private var searchTipsJob: Job? = null
    private var refreshJob: Job? = null
    private var filterCandidatesLoaded = false
    private val gson = Gson()

    val selectionManager = SelectionManager(
        scope = viewModelScope,
        onDelete = { ids -> galleryRepository.deleteFiles(ids) },
        onError = { msg -> _uiState.value = _uiState.value.copy(error = msg) }
    )

    val shareManager = ShareManager(galleryRepository, viewModelScope)

    init {
        observeSearchHistory()
        observeMediaUiMutations()
        refreshClipAvailability()
    }

    private fun observeSearchHistory() {
        val prefs = prefsManager ?: return
        viewModelScope.launch {
            prefs.searchHistory.collect { json ->
                _uiState.value = _uiState.value.copy(searchHistory = parseSearchHistory(json))
            }
        }
    }

    private fun observeMediaUiMutations() {
        val bus = mediaUiMutationBus ?: return
        viewModelScope.launch {
            bus.mutations.collect { mutation ->
                applyMediaUiMutation(mutation)
            }
        }
    }

    private fun applyMediaUiMutation(mutation: MediaUiMutation) {
        when (mutation) {
            is MediaUiMutation.Deleted -> {
                _uiState.value = _uiState.value.copy(
                    resultMonths = _uiState.value.resultMonths.removePhotos(mutation.photos)
                )
            }
            is MediaUiMutation.FavoriteChanged -> {
                _uiState.value = _uiState.value.copy(
                    resultMonths = _uiState.value.resultMonths.updateFavorite(
                        mutation.photos,
                        mutation.isFavorite
                    )
                )
            }
            is MediaUiMutation.HideChanged -> {
                _uiState.value = _uiState.value.copy(
                    resultMonths = if (mutation.isHide) {
                        _uiState.value.resultMonths.removePhotos(mutation.photos)
                    } else {
                        _uiState.value.resultMonths.updateHide(mutation.photos, isHide = false)
                    }
                )
            }
            is MediaUiMutation.PersonRenamed -> {
                val state = _uiState.value
                _uiState.value = state.copy(
                    people = state.people.map { person ->
                        if (person.id == mutation.personId) {
                            person.copy(name = mutation.newName)
                        } else {
                            person
                        }
                    },
                    filters = if (state.filters.personId == mutation.personId) {
                        state.filters.copy(personName = mutation.newName)
                    } else {
                        state.filters
                    }
                )
            }
            else -> Unit
        }
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

    fun applyHistory(item: SearchHistoryItem) {
        searchTipsJob?.cancel()
        _uiState.value = _uiState.value.copy(
            query = item.query,
            searchType = item.searchType,
            filters = item.filters,
            suggestions = emptyList()
        )
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
            error = null,
            toastMessage = null
        )
    }

    fun removeSearchHistory(item: SearchHistoryItem) {
        val prefs = prefsManager ?: return
        viewModelScope.launch {
            val current = parseSearchHistory(prefs.searchHistory.first())
            val next = current.filterNot { it.historyKey() == item.historyKey() }
            saveSearchHistory(next)
        }
    }

    fun clearSearchHistory() {
        val prefs = prefsManager ?: return
        viewModelScope.launch {
            prefs.saveSearchHistory("")
            _uiState.value = _uiState.value.copy(searchHistory = emptyList())
        }
    }

    fun executeSearch() {
        searchTipsJob?.cancel()
        viewModelScope.launch {
            performSearch(isRefresh = false)
        }
    }

    fun refresh() {
        if (!_uiState.value.isActive) return
        if (refreshJob?.isActive == true || _uiState.value.isRefreshing) return

        refreshJob = viewModelScope.launch {
            val refreshError = PullRefreshSupport.run(
                isDeviceOffline = { galleryRepository.isDeviceOffline() },
                onOffline = { galleryRepository.markNetworkRetryPending() }
            ) {
                performSearch(isRefresh = true)
            }
            if (refreshError != null) {
                val hasResults = _uiState.value.resultMonths.isNotEmpty()
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    isRefreshing = false,
                    error = if (hasResults) _uiState.value.error else refreshError,
                    toastMessage = refreshError
                )
            }
        }
    }

    fun clearToastMessage() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
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

        if (!isRefresh) {
            recordSearchHistory(query, state.searchType, filters)
        }

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
                val message = if (e.message != null) {
                    UiText.DynamicString(e.message!!)
                } else {
                    UiText.StringResource(R.string.search_failed_simple)
                }
                val hasResults = _uiState.value.resultMonths.isNotEmpty()
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    isRefreshing = false,
                    error = if (isRefresh && hasResults) _uiState.value.error else message,
                    toastMessage = if (isRefresh) message else _uiState.value.toastMessage
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
        val selectedPhotos = getAllLoadedPhotos().filter { it.id in selectedIds }
        selectionManager.deleteSelected(
            photos = getAllLoadedPhotos(),
            onDeletePhotos = { photos -> galleryRepository.deletePhotos(photos) }
        ) {
            _uiState.value = _uiState.value.copy(
                resultMonths = _uiState.value.resultMonths.removePhotos(selectedPhotos)
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
            resultMonths = _uiState.value.resultMonths.updateFavorite(selectedPhotos, isFavorite = true)
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

    fun hideSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        if (selectedIds.isEmpty()) return
        val selectedPhotos = getAllLoadedPhotos().filter { it.id in selectedIds }
        if (selectedPhotos.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            resultMonths = _uiState.value.resultMonths.removePhotos(selectedPhotos)
        )
        selectionManager.clearSelection()

        val repo = serverOpTaskRepository ?: return
        viewModelScope.launch {
            repo.enqueueHides(selectedPhotos, isHide = true)
            if (selectedPhotos.any { it.cloudId != null }) {
                appContext?.let { BackupScheduler.triggerServerOpWork(it) }
            }
        }
    }

    fun getThumbUrl(photo: UnifiedPhotoItem): String {
        return MediaThumbnailResolver.resolveTimelineThumb(photo, galleryRepository)
    }

    fun getAllLoadedPhotos(): List<UnifiedPhotoItem> {
        return _uiState.value.resultMonths.flatMap { month ->
            month.days.flatMap { it.photos }
        }
    }

    private fun recordSearchHistory(
        query: String,
        searchType: SearchType,
        filters: SearchFilters
    ) {
        val prefs = prefsManager ?: return
        val normalizedQuery = query.trim()
        if (!hasSearchContent(normalizedQuery, filters)) return

        viewModelScope.launch {
            val current = parseSearchHistory(prefs.searchHistory.first())
            val item = SearchHistoryItem(
                query = normalizedQuery,
                searchType = searchType,
                filters = filters,
                updatedAt = System.currentTimeMillis()
            )
            val next = listOf(item)
                .plus(current.filterNot { it.historyKey() == item.historyKey() })
                .take(MaxSearchHistoryItems)
            saveSearchHistory(next)
        }
    }

    private suspend fun saveSearchHistory(items: List<SearchHistoryItem>) {
        val prefs = prefsManager ?: return
        prefs.saveSearchHistory(if (items.isEmpty()) "" else gson.toJson(items))
    }

    private fun parseSearchHistory(json: String): List<SearchHistoryItem> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<StoredSearchHistoryItem>>() {}.type
            gson.fromJson<List<StoredSearchHistoryItem>>(json, type).orEmpty()
                .mapNotNull { it.toSearchHistoryItemOrNull() }
                .distinctBy { it.historyKey() }
                .sortedByDescending { it.updatedAt }
                .take(MaxSearchHistoryItems)
        }.getOrDefault(emptyList())
    }

    private fun StoredSearchHistoryItem.toSearchHistoryItemOrNull(): SearchHistoryItem? {
        val normalizedQuery = query.orEmpty().trim()
        val normalizedFilters = filters ?: SearchFilters()
        if (!hasSearchContent(normalizedQuery, normalizedFilters)) return null
        return SearchHistoryItem(
            query = normalizedQuery,
            searchType = searchType ?: SearchType.VISUAL_TEXT,
            filters = normalizedFilters,
            updatedAt = updatedAt ?: 0L
        )
    }

    private fun SearchHistoryItem.historyKey(): String {
        return listOf(
            query.trim().lowercase(),
            searchType.name,
            filters.personId.orEmpty(),
            filters.personName.orEmpty(),
            filters.location.orEmpty()
        ).joinToString("|")
    }

    private fun hasSearchContent(query: String, filters: SearchFilters): Boolean {
        return query.isNotBlank() ||
            !filters.personId.isNullOrBlank() ||
            !filters.personName.isNullOrBlank() ||
            !filters.location.isNullOrBlank()
    }

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val prefsManager: PrefsManager? = null,
        private val syncRepository: SyncRepository? = null,
        private val serverOpTaskRepository: ServerOpTaskRepository? = null,
        private val appContext: Context? = null,
        private val mediaUiMutationBus: MediaUiMutationBus? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CloudSearchViewModel(
                galleryRepository,
                prefsManager,
                syncRepository,
                serverOpTaskRepository,
                appContext,
                mediaUiMutationBus
            ) as T
        }
    }
}
