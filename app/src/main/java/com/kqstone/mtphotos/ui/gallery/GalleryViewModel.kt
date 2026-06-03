package com.kqstone.mtphotos.ui.gallery

import android.util.Log
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.local.BackupFolderSelection
import com.kqstone.mtphotos.data.local.MediaChangeObserver
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.data.local.db.BackupStatus
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.model.sortedForTimeline
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.PhotoItem
import com.kqstone.mtphotos.data.repository.ServerOpTaskRepository
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.data.repository.TimelineMonth
import com.kqstone.mtphotos.data.repository.TimelineSnapshot
import com.kqstone.mtphotos.data.repository.toCloudOnlyUnifiedPhotoItem
import com.kqstone.mtphotos.ui.util.LocalVideoThumbnailWarmup
import com.kqstone.mtphotos.ui.util.ThumbnailUrlResolver
import com.kqstone.mtphotos.worker.BackupScheduler
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import com.kqstone.mtphotos.ui.util.ShareManager
import kotlinx.coroutines.Job
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "GalleryVM"
private const val LOCAL_VIDEO_THUMB_WARMUP_LIMIT = 80

data class DayGroup(
    val date: String,
    val photos: List<UnifiedPhotoItem>,
    val addrSummary: UiText? = null
)

data class MonthGroup(
    val yearMonth: String,
    val displayTitle: UiText,
    val totalCount: Int,
    val days: List<DayGroup> = emptyList(),
    val isLoaded: Boolean = true,
    val isLoading: Boolean = false,
    val loadError: UiText? = null
)

data class GalleryUiState(
    val months: List<MonthGroup> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: UiText? = null,
    val columnCount: Int = 4,
    val isHybridMode: Boolean = false,
    val isSyncing: Boolean = false,
    val syncProgressText: UiText? = null,
    val syncCompleteMessage: UiText? = null
)

class GalleryViewModel(
    private val galleryRepository: GalleryRepository,
    private val syncRepository: SyncRepository? = null,
    private val prefsManager: PrefsManager? = null,
    private val serverOpTaskRepository: ServerOpTaskRepository? = null,
    private val appContext: Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState

    private var localVideoThumbJob: Job? = null
    private var initialSyncJob: Job? = null
    private val monthLoadJobs = mutableMapOf<String, Job>()
    private var timelineMonthsByYearMonth: Map<String, TimelineMonth> = emptyMap()
    private var currentLocalFolders: Set<String>? = null
    private var lastFolderSelectionKey: String? = null
    private var skipNextResumeRefresh = false

    val selectionManager = SelectionManager(
        scope = viewModelScope,
        onDelete = { ids -> galleryRepository.deleteFiles(ids) },
        onError = { msg -> _uiState.value = _uiState.value.copy(error = msg) }
    )
    val shareManager = ShareManager(galleryRepository, viewModelScope)

    init {
        loadTimeline()
    }

    private fun getFolderSelectionState(): BackupFolderSelection {
        return prefsManager?.getBackupFolderSelectionSync() ?: BackupFolderSelection(null, false)
    }

    private fun BackupFolderSelection.refreshKey(): String {
        return if (!isConfigured) {
            "unconfigured"
        } else {
            folders.orEmpty().sorted().joinToString(separator = "\n", prefix = "configured\n")
        }
    }

    fun skipNextResumeRefresh() {
        skipNextResumeRefresh = true
    }

    fun loadTimeline() {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null
        )
        viewModelScope.launch {
            if (syncRepository != null) {
                try {
                    if (prefsManager != null && !prefsManager.isFolderSetupComplete()) {
                        loadFromCloud()
                        return@launch
                    }
                    val folderSelection = getFolderSelectionState()
                    lastFolderSelectionKey = folderSelection.refreshKey()
                    if (prefsManager != null && !folderSelection.isConfigured) {
                        Log.w(TAG, "Folder setup complete but folder selection missing, fallback to cloud-only")
                        loadFromCloud()
                        return@launch
                    }
                    if (syncRepository.hasData()) {
                        syncRepository.reconcileFolderSelection(folderSelection.effectiveFolders)
                        loadFromRoom(folderSelection.effectiveFolders)
                        refreshCloudTimelineIndex(folderSelection.effectiveFolders)
                        return@launch
                    }
                    // 首启无缓存：先获取 snapshot 构建月份骨架
                    val folders = folderSelection.effectiveFolders
                    val snapshot = galleryRepository.getTimelineSnapshot().getOrElse {
                        Log.e(TAG, "Failed to get timeline snapshot for initial load", it)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = it.message?.let { UiText.DynamicString(it) } 
                                ?: UiText.StringResource(R.string.load_failed)
                        )
                        return@launch
                    }
                    timelineMonthsByYearMonth = snapshot.months.associateBy { it.yearMonth }
                    _uiState.value = _uiState.value.copy(
                        months = buildMonthGroups(snapshot, folders),
                        isLoading = false,
                        isHybridMode = true
                    )
                    // 复用下拉刷新流程：hydrate 预取月份数据并写入 Room，立即展开缩略图
                    if (snapshot.photosByMonth.isNotEmpty()) {
                        applyCloudTimelineSnapshot(snapshot, folders)
                    }
                    // triggerInitialSync 中 "cloud_ready" 阶段会调用 loadFromRoom 展开所有月份
                    currentLocalFolders = folders
                    triggerInitialSync(folders)
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
        if (initialSyncJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(
            isSyncing = true,
            isLoading = false,
            syncProgressText = UiText.StringResource(R.string.sync_loading_cloud)
        )

        initialSyncJob = viewModelScope.launch {
            try {
                syncRepository.performInitialSync(folders).collect { progress ->
                    when (progress.phase) {
                        "cloud" -> {
                            _uiState.value = _uiState.value.copy(syncProgressText = UiText.StringResource(R.string.sync_loading_cloud))
                        }
                        "cloud_indexed" -> {
                            _uiState.value = _uiState.value.copy(
                                syncProgressText = UiText.StringResource(R.string.sync_cloud_indexed_scanning_local, progress.cloudCount)
                            )
                        }
                        "scanning" -> {
                            _uiState.value = _uiState.value.copy(
                                syncProgressText = UiText.StringResource(R.string.sync_scanned_local_files, progress.scanned)
                            )
                        }
                        "finalizing" -> {
                            _uiState.value = _uiState.value.copy(
                                syncProgressText = if (progress.total > 0) {
                                    UiText.StringResource(R.string.sync_writing_cloud_index_progress, progress.scanned.coerceAtMost(progress.total), progress.total)
                                } else {
                                    UiText.StringResource(R.string.sync_writing_cloud_index)
                                }
                            )
                        }
                        "cleanup" -> {
                            _uiState.value = _uiState.value.copy(
                                syncProgressText = UiText.StringResource(R.string.sync_cleaning_up)
                            )
                        }
                        "cloud_ready" -> {
                            // 云端数据已入库，立刻展开所有月份
                            loadFromRoom(folders)
                        }
                        "done" -> {
                            _uiState.value = _uiState.value.copy(isSyncing = false, syncProgressText = null)
                            loadFromRoom(folders)
                            launch {
                                syncRepository.computeMd5InBackground {
                                    loadFromRoom(folders)
                                }
                                loadFromRoom(folders)
                                if (prefsManager?.getBackupEnabledSync() == true &&
                                    syncRepository.getPendingBackupMedia(folders).isNotEmpty()
                                ) {
                                    BackupScheduler.triggerImmediateBackup(
                                        prefsManager.context,
                                        prefsManager.getBackupWifiOnlySync()
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initial sync failed", e)
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    syncProgressText = null,
                    error = UiText.StringResource(R.string.sync_failed, e.message.orEmpty())
                )
            }
        }
    }

    private suspend fun loadFromRoom(folders: Set<String>?) {
        try {
            currentLocalFolders = folders
            val photos = syncRepository?.getAllPhotos(folders).orEmpty()
            _uiState.value = _uiState.value.copy(
                months = buildMonthGroups(photos),
                isLoading = false,
                isHybridMode = true
            )
            warmLocalVideoThumbnails(photos)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = e.message?.let { UiText.DynamicString(it) } ?: UiText.StringResource(R.string.load_failed)
            )
        }
    }

    private suspend fun loadFromCloud() {
        galleryRepository.getTimelineSnapshot().fold(
            onSuccess = { snapshot ->
                timelineMonthsByYearMonth = snapshot.months.associateBy { it.yearMonth }
                _uiState.value = _uiState.value.copy(
                    months = buildMonthGroups(snapshot, currentLocalFolders),
                    isLoading = false,
                    isHybridMode = false
                )
                localVideoThumbJob?.cancel()
            },
            onFailure = { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message?.let { UiText.DynamicString(it) } ?: UiText.StringResource(R.string.load_failed)
                )
            }
        )
    }

    /**
     * 刷新云端时间线索引并合并到 UI 状态。
     * 传入已有 snapshot 时直接复用，避免重复 API 调用。
     */
    private fun refreshCloudTimelineIndex(folders: Set<String>?, existingSnapshot: TimelineSnapshot? = null) {
        viewModelScope.launch {
            val snapshot = existingSnapshot ?: galleryRepository.getTimelineSnapshot().getOrNull() ?: return@launch
            applyCloudTimelineSnapshot(snapshot, folders)
        }
    }

    private suspend fun applyCloudTimelineSnapshot(snapshot: TimelineSnapshot, folders: Set<String>?) {
        timelineMonthsByYearMonth = snapshot.months.associateBy { it.yearMonth }
        val prefetchedCloudPhotos = snapshot.photosByMonth.values.flatten()
        if (prefetchedCloudPhotos.isNotEmpty()) {
            syncRepository?.upsertCloudPhotoItems(prefetchedCloudPhotos)
        }
        val prefetched = mutableMapOf<String, List<UnifiedPhotoItem>>()
        for ((yearMonth, photos) in snapshot.photosByMonth) {
            prefetched[yearMonth] = syncRepository?.hydrateCloudPhotos(photos, folders)
                ?: photos.map { it.toUnifiedPhotoItem() }
        }

        val existingByMonth = _uiState.value.months.associateBy { it.yearMonth }
        val cloudYearMonths = snapshot.months.map { it.yearMonth }.toSet()
        val mergedCloudMonths = snapshot.months.map { month ->
            val existing = existingByMonth[month.yearMonth]
            val preloaded = prefetched[month.yearMonth]
            when {
                preloaded != null -> MonthGroup(
                    yearMonth = month.yearMonth,
                    displayTitle = formatYearMonth(month.yearMonth),
                    totalCount = maxOf(
                        month.count,
                        preloaded.size,
                        existing?.totalCount ?: 0
                    ),
                    days = buildDayGroups(mergePhotos(existing, preloaded)),
                    isLoaded = true
                )
                existing != null && existing.days.isNotEmpty() -> existing.copy(
                    totalCount = maxOf(existing.totalCount, month.count),
                    isLoaded = true,
                    isLoading = false,
                    loadError = null
                )
                else -> MonthGroup(
                    yearMonth = month.yearMonth,
                    displayTitle = formatYearMonth(month.yearMonth),
                    totalCount = month.count,
                    isLoaded = month.count == 0
                )
            }
        }
        val localOnlyMonths = _uiState.value.months.filter { it.yearMonth !in cloudYearMonths }
        _uiState.value = _uiState.value.copy(
            months = (mergedCloudMonths + localOnlyMonths).sortedByDescending { it.yearMonth }
        )
    }

    private fun mergePhotos(
        existing: MonthGroup?,
        preloaded: List<UnifiedPhotoItem>
    ): List<UnifiedPhotoItem> {
        if (existing == null) return preloaded
        val preloadedKeys = preloaded.map { it.mergeKey() }.toSet()
        val result = linkedMapOf<String, UnifiedPhotoItem>()
        for (photo in existing.days.flatMap { it.photos }) {
            val key = photo.mergeKey()
            // 云端已删除的项目（cloud: key 不在最新 preloaded 中）不再保留
            if (key.startsWith("cloud:") && key !in preloadedKeys) continue
            result[key] = photo
        }
        for (photo in preloaded) {
            val key = photo.mergeKey()
            val previous = result[key]
            result[key] = when {
                previous == null -> photo
                previous.addr.isNullOrBlank() && !photo.addr.isNullOrBlank() -> photo
                else -> previous
            }
        }
        return result.values.toList()
    }

    private fun UnifiedPhotoItem.mergeKey(): String {
        return cloudId?.let { "cloud:$it" }
            ?: md5.takeIf { it.isNotBlank() }?.let { "md5:$it" }
            ?: "db:$dbId"
    }

    fun loadTimelineMonth(yearMonth: String) {
        val group = _uiState.value.months.firstOrNull { it.yearMonth == yearMonth } ?: return
        if (group.isLoaded || group.isLoading) return
        val month = timelineMonthsByYearMonth[yearMonth] ?: return
        if (monthLoadJobs[yearMonth]?.isActive == true) return

        _uiState.value = _uiState.value.copy(
            months = _uiState.value.months.map {
                if (it.yearMonth == yearMonth) it.copy(isLoading = true, loadError = null) else it
            }
        )

        monthLoadJobs[yearMonth] = viewModelScope.launch {
            galleryRepository.getTimelineMonthFiles(month).fold(
                onSuccess = { photos ->
                    syncRepository?.upsertCloudPhotoItems(photos)
                    val unifiedPhotos = syncRepository?.hydrateCloudPhotos(photos, currentLocalFolders)
                        ?: photos.map { it.toUnifiedPhotoItem() }
                    _uiState.value = _uiState.value.copy(
                        months = _uiState.value.months.map { current ->
                            if (current.yearMonth == yearMonth) {
                                current.copy(
                                    totalCount = maxOf(current.totalCount, unifiedPhotos.size),
                                    days = buildDayGroups(unifiedPhotos),
                                    isLoaded = true,
                                    isLoading = false,
                                    loadError = null
                                )
                            } else {
                                current
                            }
                        }
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        months = _uiState.value.months.map { current ->
                            if (current.yearMonth == yearMonth) {
                                current.copy(
                                    isLoading = false,
                                    loadError = e.message?.let { UiText.DynamicString(it) }
                                        ?: UiText.StringResource(R.string.load_failed)
                                )
                            } else {
                                current
                            }
                        }
                    )
                }
            )
            monthLoadJobs.remove(yearMonth)
        }
    }


    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        viewModelScope.launch {
            if (_uiState.value.isHybridMode && syncRepository != null) {
                try {
                    val folderSelection = getFolderSelectionState()
                    if (!folderSelection.isConfigured) {
                        Log.w(TAG, "Refresh skipped local scope because folder selection is missing")
                        loadFromCloud()
                        _uiState.value = _uiState.value.copy(isRefreshing = false)
                        return@launch
                    }
                    val folders = folderSelection.effectiveFolders

                    // Step 1: 从 Room 加载已有数据（刷新动画保持显示）
                    loadFromRoom(folders)

                    // Step 2: 后台静默同步云端
                    // Step 2a: 获取 snapshot（轻量，只拉索引 + 服务端预取的最近几个月数据）
                    syncRepository.reconcileFolderSelection(folders)
                    val snapshot = galleryRepository.getTimelineSnapshot().getOrElse { throw it }

                    // Step 2b: 用 snapshot 中已预取的最近月份数据更新 UI，然后收起刷新动画
                    if (snapshot.photosByMonth.isNotEmpty()) {
                        applyCloudTimelineSnapshot(snapshot, folders)
                    }
                    _uiState.value = _uiState.value.copy(isRefreshing = false)

                    // Step 2c: 全量拉取并同步到 Room（复用已有 snapshot 避免重复请求）
                    val fullSnapshot = syncRepository.refreshCloudState(existingSnapshot = snapshot)
                    loadFromRoom(folders)
                    applyCloudTimelineSnapshot(fullSnapshot, folders)
                    _uiState.value = _uiState.value.copy(
                        syncCompleteMessage = UiText.StringResource(R.string.sync_complete_message)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Background sync failed after refresh", e)
                    // 不覆盖已展示的数据，仅记录错误
                    if (_uiState.value.months.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            isRefreshing = false,
                            error = e.message?.let { UiText.DynamicString(it) }
                                ?: UiText.StringResource(R.string.sync_failed, "")
                        )
                    }
                }
            } else {
                galleryRepository.getTimelineSnapshot().fold(
                    onSuccess = { snapshot ->
                        timelineMonthsByYearMonth = snapshot.months.associateBy { it.yearMonth }
                        _uiState.value = _uiState.value.copy(
                            months = buildMonthGroups(snapshot, currentLocalFolders),
                            isRefreshing = false
                        )
                    },
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(
                            isRefreshing = false,
                            error = e.message?.let { UiText.DynamicString(it) }
                                ?: UiText.StringResource(R.string.refresh_failed)
                        )
                    }
                )
            }
        }
    }

    fun clearSyncCompleteMessage() {
        _uiState.value = _uiState.value.copy(syncCompleteMessage = null)
    }

    fun quickRefresh() {
        if (syncRepository == null || !_uiState.value.isHybridMode) return
        if (_uiState.value.isSyncing || _uiState.value.isRefreshing) return

        viewModelScope.launch {
            try {
                val folderSelection = getFolderSelectionState()
                val folderKey = folderSelection.refreshKey()
                val folderChanged = lastFolderSelectionKey != null && lastFolderSelectionKey != folderKey
                val mediaChanged = MediaChangeObserver.isDirty

                if (skipNextResumeRefresh && !folderChanged && !mediaChanged) {
                    skipNextResumeRefresh = false
                    Log.d(TAG, "Quick refresh skipped once after navigation")
                    return@launch
                }
                skipNextResumeRefresh = false

                if (!folderChanged && !mediaChanged) {
                    Log.d(TAG, "Quick refresh skipped: no media or folder changes")
                    return@launch
                }

                if (!folderSelection.isConfigured) {
                    Log.w(TAG, "Quick refresh skipped local scope because folder selection is missing")
                    lastFolderSelectionKey = folderKey
                    loadFromCloud()
                    return@launch
                }
                val folders = folderSelection.effectiveFolders
                if (folderChanged) {
                    syncRepository.reconcileFolderSelection(folders)
                    lastFolderSelectionKey = folderKey
                }
                if (mediaChanged) {
                    syncRepository.syncLocalMedia(folders)
                }
                loadFromRoom(folders)
            } catch (e: Exception) {
                Log.w(TAG, "Quick refresh failed", e)
            }
        }
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

    private suspend fun buildMonthGroups(
        snapshot: TimelineSnapshot,
        localFolders: Set<String>?
    ): List<MonthGroup> {
        if (snapshot.months.isEmpty()) {
            val photos = syncRepository?.hydrateCloudPhotos(snapshot.photos, localFolders)
                ?: snapshot.photos.map { it.toUnifiedPhotoItem() }
            return buildMonthGroups(photos)
        }

        return snapshot.months.map { month ->
            val cloudPhotos = snapshot.photosByMonth[month.yearMonth].orEmpty()
            val photos = syncRepository?.hydrateCloudPhotos(cloudPhotos, localFolders)
                ?: cloudPhotos.map { it.toUnifiedPhotoItem() }
            MonthGroup(
                yearMonth = month.yearMonth,
                displayTitle = formatYearMonth(month.yearMonth),
                totalCount = month.count,
                days = buildDayGroups(photos),
                isLoaded = month.count == 0 || snapshot.photosByMonth.containsKey(month.yearMonth)
            )
        }
    }

    private fun buildDayGroups(photos: List<UnifiedPhotoItem>): List<DayGroup> {
        return photos
            .sortedForTimeline()
            .groupBy { extractDate(it.mtime) }
            .toSortedMap(compareByDescending { it })
            .map { (date, dayPhotos) ->
                DayGroup(
                    date = date,
                    photos = dayPhotos,
                    addrSummary = buildAddrSummary(dayPhotos)
                )
            }
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

    private fun PhotoItem.toUnifiedPhotoItem(): UnifiedPhotoItem {
        return toCloudOnlyUnifiedPhotoItem()
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
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    error = UiText.StringResource(R.string.sync_failed, e.message.orEmpty())
                )
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
            _uiState.value = _uiState.value.copy(
                months = removeSelectedPhotos(_uiState.value.months, selectedIds)
            )
        }
    }

    fun favoriteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        if (selectedIds.isEmpty()) return
        val selectedPhotos = getAllLoadedPhotos().filter { it.id in selectedIds }
        if (selectedPhotos.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            months = updateFavoriteState(_uiState.value.months, selectedIds, isFavorite = true)
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

    fun updateColumnCount(newCount: Int) {
        val clamped = newCount.coerceIn(2, 6)
        if (clamped != _uiState.value.columnCount) {
            _uiState.value = _uiState.value.copy(columnCount = clamped)
        }
    }

    fun getThumbUrl(photo: UnifiedPhotoItem): String {
        return ThumbnailUrlResolver.resolve(
            photo = photo,
            galleryRepository = galleryRepository,
            imageCloudUrl = { galleryRepository.getThumbUrlByMd5(it.md5) }
        )
    }

    fun getFullImageUrl(photo: UnifiedPhotoItem): String {
        photo.localUri?.let {
            if (it.isNotEmpty() && !photo.isStorageOptimized) return it
        }
        val cloudId = photo.cloudId ?: return ""
        return galleryRepository.getFullImageUrl(cloudId, photo.md5)
    }

    fun getVideoUrl(photo: UnifiedPhotoItem): String {
        if (!photo.isMotionPhoto()) {
            photo.localUri?.let { if (it.isNotEmpty() && !photo.isStorageOptimized) return it }
        }
        val cloudId = photo.cloudId ?: return ""
        return if (photo.isMotionPhoto()) {
            galleryRepository.getMotionPhotoUrl(cloudId, photo.md5)
        } else {
            galleryRepository.getTranscodeVideoUrl(cloudId, photo.md5)
        }
    }

    fun getThumbUrl(md5: String, fileId: Double): String = galleryRepository.getThumbUrl(md5, fileId)

    fun getVideoThumbUrl(md5: String): String = galleryRepository.getVideoThumbUrl(md5)

    fun getFullImageUrl(id: Double, md5: String): String = galleryRepository.getFullImageUrl(id, md5)

    fun getAllLoadedPhotos(): List<UnifiedPhotoItem> {
        return _uiState.value.months.flatMap { month -> month.days.flatMap { it.photos } }
    }

    fun shareSelected(context: android.content.Context) {
        val selectedIds = selectionManager.selectedPhotoIds.value
        val photos = getAllLoadedPhotos().filter { it.id in selectedIds }
        shareManager.sharePhotos(context, photos) {
            selectionManager.clearSelection()
        }
    }

    private fun warmLocalVideoThumbnails(photos: List<UnifiedPhotoItem>) {
        localVideoThumbJob?.cancel()
        localVideoThumbJob = viewModelScope.launch {
            val updates = mutableListOf<Pair<Long, String>>()
            LocalVideoThumbnailWarmup.warm(photos.take(LOCAL_VIDEO_THUMB_WARMUP_LIMIT), syncRepository) { photo, path ->
                updates.add(photo.dbId to path)
            }
            updatePhotoThumbCachePaths(updates)
        }
    }

    private fun updatePhotoThumbCachePaths(updates: List<Pair<Long, String>>) {
        val byDbId = updates
            .filter { (dbId, path) -> dbId > 0 && path.isNotBlank() }
            .toMap()
        if (byDbId.isEmpty()) return

        val updatedMonths = updateThumbCachePaths(_uiState.value.months, byDbId)
        _uiState.value = _uiState.value.copy(months = updatedMonths)
    }

    private fun updateThumbCachePaths(
        groups: List<MonthGroup>,
        byDbId: Map<Long, String>
    ): List<MonthGroup> {
        return groups.map { month ->
            month.copy(
                days = month.days.map { day ->
                    day.copy(
                        photos = day.photos.map { photo ->
                            byDbId[photo.dbId]?.let { path -> photo.copy(thumbCachePath = path) }
                                ?: photo
                        }
                    )
                }
            )
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

    private fun extractDate(mtime: String): String = mtime.take(10)

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val syncRepository: SyncRepository? = null,
        private val prefsManager: PrefsManager? = null,
        private val serverOpTaskRepository: ServerOpTaskRepository? = null,
        private val appContext: Context? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GalleryViewModel(
                galleryRepository,
                syncRepository,
                prefsManager,
                serverOpTaskRepository,
                appContext
            ) as T
        }
    }
}
