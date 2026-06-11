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
import com.kqstone.mtphotos.data.local.db.TimelineMonthCount
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.model.sortedForTimeline
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.MediaUiMutation
import com.kqstone.mtphotos.data.repository.MediaUiMutationBus
import com.kqstone.mtphotos.data.repository.PhotoItem
import com.kqstone.mtphotos.data.repository.ServerOpTaskRepository
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.data.repository.TimelineMonth
import com.kqstone.mtphotos.data.repository.TimelineSnapshot
import com.kqstone.mtphotos.data.repository.toCloudOnlyUnifiedPhotoItem
import com.kqstone.mtphotos.ui.media.MediaThumbnailResolver
import com.kqstone.mtphotos.ui.util.PullRefreshSupport
import com.kqstone.mtphotos.worker.BackupScheduler
import com.kqstone.mtphotos.ui.gallery.SelectionManager
import com.kqstone.mtphotos.ui.util.ShareManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private const val TAG = "GalleryVM"
private const val LOCAL_VIDEO_THUMB_WARMUP_LIMIT = 80
private const val INITIAL_PREVIEW_LIMIT = 160
private const val TIMELINE_EXPAND_BATCH_MONTHS = 3

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
    val toastMessage: UiText? = null
)

class GalleryViewModel(
    private val galleryRepository: GalleryRepository,
    private val syncRepository: SyncRepository? = null,
    private val prefsManager: PrefsManager? = null,
    private val serverOpTaskRepository: ServerOpTaskRepository? = null,
    private val appContext: Context? = null,
    private val mediaUiMutationBus: MediaUiMutationBus? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState

    private var localVideoThumbJob: Job? = null
    private var initialSyncJob: Job? = null
    private var roomTimelineExpandJob: Job? = null
    private val monthLoadJobs = mutableMapOf<String, Job>()
    private var timelineMonthsByYearMonth: Map<String, TimelineMonth> = emptyMap()
    private var currentLocalFolders: Set<String>? = null
    private var lastFolderSelectionKey: String? = null
    private var skipNextResumeRefresh = false
    private var refreshJob: Job? = null

    val selectionManager = SelectionManager(
        scope = viewModelScope,
        onDelete = { ids -> galleryRepository.deleteFiles(ids) },
        onError = { msg -> _uiState.value = _uiState.value.copy(error = msg) }
    )
    val shareManager = ShareManager(galleryRepository, viewModelScope)

    init {
        observeMediaUiMutations()
        loadTimeline()
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
                    months = _uiState.value.months.removePhotos(mutation.photos)
                )
            }
            is MediaUiMutation.FavoriteChanged -> {
                _uiState.value = _uiState.value.copy(
                    months = _uiState.value.months.updateFavorite(
                        mutation.photos,
                        mutation.isFavorite
                    )
                )
            }
            is MediaUiMutation.HideChanged -> {
                _uiState.value = _uiState.value.copy(
                    months = if (mutation.isHide) {
                        _uiState.value.months.removePhotos(mutation.photos)
                    } else {
                        _uiState.value.months.upsertPhotos(
                            mutation.photos.map { it.copy(isHide = false) },
                            ::buildMonthGroups
                        )
                    }
                )
            }
            is MediaUiMutation.PersonRenamed -> Unit
            else -> Unit
        }
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
                        val folders = folderSelection.effectiveFolders
                        loadInitialRoomPreview(folders)
                        launch {
                            syncRepository.reconcileFolderSelection(folders)
                            startRoomTimelineExpansion(folders)
                        }
                        launch {
                            val snapshot = galleryRepository.getTimelineSnapshot().getOrNull()
                            if (snapshot != null && snapshot.photosByMonth.isNotEmpty()) {
                                applyCloudTimelineSnapshot(snapshot, folders)
                            } else if (_uiState.value.months.isEmpty()) {
                                _uiState.value = _uiState.value.copy(isLoading = false, isHybridMode = true)
                            }
                        }
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
                    // triggerInitialSync expands the remaining months progressively from Room.
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
                            startRoomTimelineExpansion(folders)
                        }
                        "done" -> {
                            _uiState.value = _uiState.value.copy(isSyncing = false, syncProgressText = null)
                            startRoomTimelineExpansion(folders)
                            launch {
                                syncRepository.computeMd5InBackground()
                                startRoomTimelineExpansion(folders)
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
            val (photos, months) = withContext(Dispatchers.Default) {
                val loadedPhotos = syncRepository?.getAllPhotos(folders).orEmpty()
                loadedPhotos to buildMonthGroups(loadedPhotos)
            }
            _uiState.value = _uiState.value.copy(
                months = months,
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

    private suspend fun loadInitialRoomPreview(folders: Set<String>?) {
        try {
            currentLocalFolders = folders
            val photos = syncRepository?.getInitialPreviewPhotos(folders, INITIAL_PREVIEW_LIMIT).orEmpty()
            if (photos.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isHybridMode = true
                )
                return
            }
            val months = withContext(Dispatchers.Default) { buildMonthGroups(photos) }
            _uiState.value = _uiState.value.copy(
                months = months,
                isLoading = false,
                isHybridMode = true
            )
            warmLocalVideoThumbnails(photos)
        } catch (e: Exception) {
            Log.w(TAG, "Initial Room preview failed", e)
            _uiState.value = _uiState.value.copy(isLoading = false, isHybridMode = true)
        }
    }

    private fun startRoomTimelineExpansion(folders: Set<String>?) {
        val repo = syncRepository ?: return
        currentLocalFolders = folders
        roomTimelineExpandJob?.cancel()
        roomTimelineExpandJob = viewModelScope.launch {
            expandRoomTimelineIncrementally(repo, folders)
        }
    }

    private suspend fun expandRoomTimelineIncrementally(
        repo: SyncRepository,
        folders: Set<String>?
    ) {
        try {
            currentLocalFolders = folders
            val monthCounts = repo.getTimelineMonths(folders)
            val loadedPhotoCount = _uiState.value.months.sumOf { month ->
                month.days.sumOf { it.photos.size }
            }
            var warmedVideoThumbnails = false
            val remainingMonthCounts = monthCounts.toMutableList()
            if (loadedPhotoCount == 0 && remainingMonthCounts.isNotEmpty()) {
                val firstMonth = remainingMonthCounts.removeAt(0)
                val photos = repo.getMonthPhotos(firstMonth.yearMonth, folders)
                val days = withContext(Dispatchers.Default) { buildDayGroups(photos) }
                _uiState.value = _uiState.value.copy(
                    months = replaceLoadedMonth(_uiState.value.months, firstMonth, days, photos.size),
                    isLoading = false,
                    isHybridMode = true
                )
                if (photos.isNotEmpty()) {
                    warmLocalVideoThumbnails(photos)
                    warmedVideoThumbnails = true
                }
            }
            applyRoomTimelineSkeleton(monthCounts)

            val pendingLoadedMonths = mutableListOf<LoadedMonth>()
            for (monthCount in remainingMonthCounts) {
                yield()
                val photos = repo.getMonthPhotos(monthCount.yearMonth, folders)
                val days = withContext(Dispatchers.Default) { buildDayGroups(photos) }
                pendingLoadedMonths.add(
                    LoadedMonth(
                        monthCount = monthCount,
                        days = days,
                        loadedCount = photos.size
                    )
                )
                if (!warmedVideoThumbnails && photos.isNotEmpty()) {
                    warmLocalVideoThumbnails(photos)
                    warmedVideoThumbnails = true
                }
                if (pendingLoadedMonths.size >= TIMELINE_EXPAND_BATCH_MONTHS) {
                    applyLoadedMonthBatch(pendingLoadedMonths)
                    pendingLoadedMonths.clear()
                }
            }
            if (pendingLoadedMonths.isNotEmpty()) {
                applyLoadedMonthBatch(pendingLoadedMonths)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Incremental timeline expansion failed", e)
            if (_uiState.value.months.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message?.let { UiText.DynamicString(it) }
                        ?: UiText.StringResource(R.string.load_failed)
                )
            }
        }
    }

    private data class LoadedMonth(
        val monthCount: TimelineMonthCount,
        val days: List<DayGroup>,
        val loadedCount: Int
    )

    private fun applyLoadedMonthBatch(loadedMonths: List<LoadedMonth>) {
        if (loadedMonths.isEmpty()) return
        val updatedMonths = loadedMonths.fold(_uiState.value.months) { months, loaded ->
            replaceLoadedMonth(
                months = months,
                monthCount = loaded.monthCount,
                days = loaded.days,
                loadedCount = loaded.loadedCount
            )
        }
        _uiState.value = _uiState.value.copy(
            months = updatedMonths,
            isLoading = false,
            isHybridMode = true
        )
    }

    private suspend fun applyRoomTimelineSkeleton(monthCounts: List<TimelineMonthCount>) {
        val currentMonths = _uiState.value.months
        val mergedMonths = withContext(Dispatchers.Default) {
            val existingByMonth = currentMonths.associateBy { it.yearMonth }
            val roomYearMonths = monthCounts.map { it.yearMonth }.toSet()
            val roomMonths = monthCounts.map { count ->
                val existing = existingByMonth[count.yearMonth]
                existing?.copy(
                    totalCount = maxOf(existing.totalCount, count.count),
                    isLoaded = existing.isLoaded && existing.days.isNotEmpty(),
                    isLoading = false,
                    loadError = null
                ) ?: MonthGroup(
                    yearMonth = count.yearMonth,
                    displayTitle = formatYearMonth(count.yearMonth),
                    totalCount = count.count,
                    isLoaded = count.count == 0
                )
            }
            val cloudOnlyMonths = currentMonths.filter { it.yearMonth !in roomYearMonths }
            (roomMonths + cloudOnlyMonths).sortedByDescending { it.yearMonth }
        }
        _uiState.value = _uiState.value.copy(
            months = mergedMonths,
            isLoading = false,
            isHybridMode = true
        )
    }

    private fun replaceLoadedMonth(
        months: List<MonthGroup>,
        monthCount: TimelineMonthCount,
        days: List<DayGroup>,
        loadedCount: Int
    ): List<MonthGroup> {
        var replaced = false
        val updated = months.map { current ->
            if (current.yearMonth == monthCount.yearMonth) {
                replaced = true
                current.copy(
                    totalCount = maxOf(monthCount.count, loadedCount),
                    days = days,
                    isLoaded = true,
                    isLoading = false,
                    loadError = null
                )
            } else {
                current
            }
        }
        if (replaced) return updated
        return (updated + MonthGroup(
            yearMonth = monthCount.yearMonth,
            displayTitle = formatYearMonth(monthCount.yearMonth),
            totalCount = maxOf(monthCount.count, loadedCount),
            days = days,
            isLoaded = true
        )).sortedByDescending { it.yearMonth }
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
        val prefetched = syncRepository?.hydrateCloudPhotosByMonth(snapshot.photosByMonth, folders)
            ?: withContext(Dispatchers.Default) {
                snapshot.photosByMonth.mapValues { (_, photos) ->
                    photos.map { it.toUnifiedPhotoItem() }
                }
            }

        val currentMonths = _uiState.value.months
        val (mergedCloudMonths, localOnlyMonths) = withContext(Dispatchers.Default) {
            val existingByMonth = currentMonths.associateBy { it.yearMonth }
            val cloudYearMonths = snapshot.months.map { it.yearMonth }.toSet()
            val merged = snapshot.months.map { month ->
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
            merged to currentMonths.filter { it.yearMonth !in cloudYearMonths }
        }
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
        val month = timelineMonthsByYearMonth[yearMonth]
        if (monthLoadJobs[yearMonth]?.isActive == true) return

        _uiState.value = _uiState.value.copy(
            months = _uiState.value.months.map {
                if (it.yearMonth == yearMonth) it.copy(isLoading = true, loadError = null) else it
            }
        )

        monthLoadJobs[yearMonth] = viewModelScope.launch {
            if (syncRepository != null) {
                val roomPhotos = syncRepository.getMonthPhotos(yearMonth, currentLocalFolders)
                if (roomPhotos.isNotEmpty() || group.totalCount == 0) {
                    _uiState.value = _uiState.value.copy(
                        months = _uiState.value.months.map { current ->
                            if (current.yearMonth == yearMonth) {
                                current.copy(
                                    totalCount = maxOf(current.totalCount, roomPhotos.size),
                                    days = withContext(Dispatchers.Default) { buildDayGroups(roomPhotos) },
                                    isLoaded = true,
                                    isLoading = false,
                                    loadError = null
                                )
                            } else {
                                current
                            }
                        }
                    )
                    monthLoadJobs.remove(yearMonth)
                    return@launch
                }
            }

            if (month == null) {
                _uiState.value = _uiState.value.copy(
                    months = _uiState.value.months.map { current ->
                        if (current.yearMonth == yearMonth) {
                            current.copy(
                                isLoading = false,
                                loadError = UiText.StringResource(R.string.load_failed)
                            )
                        } else {
                            current
                        }
                    }
                )
                monthLoadJobs.remove(yearMonth)
                return@launch
            }

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
        if (refreshJob?.isActive == true || _uiState.value.isRefreshing) return

        _uiState.value = _uiState.value.copy(
            isRefreshing = true,
            error = null,
            toastMessage = null
        )
        refreshJob = viewModelScope.launch {
            val refreshError = PullRefreshSupport.run(
                isDeviceOffline = { galleryRepository.isDeviceOffline() },
                onOffline = { galleryRepository.markNetworkRetryPending() }
            ) {
            if (_uiState.value.isHybridMode && syncRepository != null) {
                try {
                    val folderSelection = getFolderSelectionState()
                    if (!folderSelection.isConfigured) {
                        Log.w(TAG, "Refresh skipped local scope because folder selection is missing")
                        loadFromCloud()
                        _uiState.value = _uiState.value.copy(isRefreshing = false)
                        return@run
                    }
                    val folders = folderSelection.effectiveFolders

                    syncRepository.reconcileFolderSelection(folders)
                    val snapshot = galleryRepository.getTimelineSnapshot().getOrElse { throw it }

                    if (snapshot.photosByMonth.isNotEmpty()) {
                        applyCloudTimelineSnapshot(snapshot, folders)
                    }
                    _uiState.value = _uiState.value.copy(isRefreshing = false)

                    // Keep the expensive full cloud sync off the visible refresh path.
                    // It updates Room, but avoids rebuilding the whole timeline during the gesture.
                    viewModelScope.launch {
                        try {
                            syncRepository.refreshCloudState(existingSnapshot = snapshot)
                            startRoomTimelineExpansion(folders)
                            _uiState.value = _uiState.value.copy(
                                toastMessage = UiText.StringResource(R.string.sync_complete_message)
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Background sync failed after refresh", e)
                            _uiState.value = _uiState.value.copy(
                                toastMessage = UiText.StringResource(R.string.sync_failed, e.message.orEmpty())
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Refresh failed", e)
                    throw e
                    // 不覆盖已展示的数据，仅记录错误
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
                        throw e
                    }
                )
            }

            }

            if (refreshError != null) {
                val hasContent = _uiState.value.months.isNotEmpty()
                _uiState.value = _uiState.value.copy(
                    error = if (hasContent) _uiState.value.error else refreshError,
                    toastMessage = refreshError
                )
            }
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    fun clearToastMessage() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
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
                startRoomTimelineExpansion(folders)
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
                startRoomTimelineExpansion(folders)
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
        val allPhotos = _uiState.value.months.flatMap { month -> month.days.flatMap { it.photos } }
        val selectedPhotos = allPhotos.filter { it.id in selectedIds }
        selectionManager.deleteSelected(
            photos = allPhotos,
            onDeletePhotos = { photos -> galleryRepository.deletePhotos(photos) }
        ) {
            _uiState.value = _uiState.value.copy(
                months = _uiState.value.months.removePhotos(selectedPhotos)
            )
        }
    }

    fun favoriteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        if (selectedIds.isEmpty()) return
        val selectedPhotos = getAllLoadedPhotos().filter { it.id in selectedIds }
        if (selectedPhotos.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            months = _uiState.value.months.updateFavorite(selectedPhotos, isFavorite = true)
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
            months = _uiState.value.months.removePhotos(selectedPhotos)
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

    fun updateColumnCount(newCount: Int) {
        val clamped = newCount.coerceIn(2, 6)
        if (clamped != _uiState.value.columnCount) {
            _uiState.value = _uiState.value.copy(columnCount = clamped)
        }
    }

    fun getThumbUrl(photo: UnifiedPhotoItem): String {
        return MediaThumbnailResolver.resolveTimelineThumb(photo, galleryRepository)
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

    fun getThumbUrl(md5: String, fileId: Double): String {
        return MediaThumbnailResolver.resolveCloudThumb(md5, fileId, galleryRepository)
    }

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
            val updates = MediaThumbnailResolver.warmLocalVideoThumbs(
                photos.take(LOCAL_VIDEO_THUMB_WARMUP_LIMIT),
                syncRepository
            )
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
        private val appContext: Context? = null,
        private val mediaUiMutationBus: MediaUiMutationBus? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GalleryViewModel(
                galleryRepository,
                syncRepository,
                prefsManager,
                serverOpTaskRepository,
                appContext,
                mediaUiMutationBus
            ) as T
        }
    }
}
