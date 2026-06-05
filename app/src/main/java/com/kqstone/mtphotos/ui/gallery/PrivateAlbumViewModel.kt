package com.kqstone.mtphotos.ui.gallery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.local.PrefsManager
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.model.sortedForTimeline
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.ServerOpTaskRepository
import com.kqstone.mtphotos.data.repository.toCloudOnlyUnifiedPhotoItem
import com.kqstone.mtphotos.ui.util.ShareManager
import com.kqstone.mtphotos.ui.util.ThumbnailUrlResolver
import com.kqstone.mtphotos.ui.util.UiText
import com.kqstone.mtphotos.worker.BackupScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PrivateAlbumUiState(
    val months: List<MonthGroup> = emptyList(),
    val isLocked: Boolean = true,
    val showIntro: Boolean = false,
    val password: String = "",
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val columnCount: Int = 4,
    val toastMessage: UiText? = null
)

class PrivateAlbumViewModel(
    private val galleryRepository: GalleryRepository,
    private val prefsManager: PrefsManager,
    private val serverOpTaskRepository: ServerOpTaskRepository,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PrivateAlbumUiState(showIntro = !prefsManager.getPrivacyIntroShownSync())
    )
    val uiState: StateFlow<PrivateAlbumUiState> = _uiState

    val selectionManager = SelectionManager(
        scope = viewModelScope,
        onDelete = { ids -> galleryRepository.deleteFiles(ids) },
        onError = { msg -> _uiState.value = _uiState.value.copy(error = msg) }
    )
    val shareManager = ShareManager(galleryRepository, viewModelScope)

    private var sessionPassword: String = ""

    fun acknowledgeIntro() {
        _uiState.value = _uiState.value.copy(showIntro = false)
        viewModelScope.launch {
            prefsManager.setPrivacyIntroShown(true)
        }
    }

    fun updatePassword(value: String) {
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun unlock() {
        val password = _uiState.value.password.trim()
        if (password.isBlank()) {
            _uiState.value = _uiState.value.copy(error = UiText.StringResource(R.string.private_album_password_required))
            return
        }
        loadHidden(password)
    }

    fun refresh() {
        val password = sessionPassword.takeIf { it.isNotBlank() } ?: return
        loadHidden(password)
    }

    private fun loadHidden(password: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val result = galleryRepository.getHiddenFiles(password)
            result.fold(
                onSuccess = { photos ->
                    sessionPassword = password
                    _uiState.value = _uiState.value.copy(
                        isLocked = false,
                        password = "",
                        isLoading = false,
                        months = buildMonthGroups(photos.map { it.toCloudOnlyUnifiedPhotoItem().copy(isHide = true) })
                    )
                },
                onFailure = { error ->
                    val cached = galleryRepository.getCachedHiddenFiles()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        months = if (cached.isNotEmpty()) {
                            buildMonthGroups(cached.map { it.toCloudOnlyUnifiedPhotoItem().copy(isHide = true) })
                        } else {
                            _uiState.value.months
                        },
                        error = UiText.StringResource(
                            R.string.private_album_unlock_failed,
                            error.message.orEmpty()
                        )
                    )
                }
            )
        }
    }

    fun lock() {
        sessionPassword = ""
        selectionManager.clearSelection()
        _uiState.value = _uiState.value.copy(
            isLocked = true,
            password = "",
            months = emptyList(),
            error = null
        )
    }

    fun selectAll() {
        selectionManager.selectAll(getAllLoadedPhotos().map { it.id })
    }

    fun unhideSelected(onPhotosUnhidden: (List<UnifiedPhotoItem>) -> Unit = {}) {
        val selectedIds = selectionManager.selectedPhotoIds.value
        if (selectedIds.isEmpty()) return
        val selectedPhotos = getAllLoadedPhotos().filter { it.id in selectedIds }
        if (selectedPhotos.isEmpty()) return
        val restoredPhotos = selectedPhotos.map { it.copy(isHide = false) }

        _uiState.value = _uiState.value.copy(
            months = removeSelectedPhotos(_uiState.value.months, selectedIds)
        )
        selectionManager.clearSelection()
        onPhotosUnhidden(restoredPhotos)

        viewModelScope.launch {
            serverOpTaskRepository.enqueueHides(selectedPhotos, isHide = false)
            if (selectedPhotos.any { it.cloudId != null }) {
                BackupScheduler.triggerServerOpWork(appContext)
            }
        }
    }

    fun shareSelected(context: Context) {
        val ids = selectionManager.selectedPhotoIds.value
        if (ids.isEmpty()) return
        shareManager.sharePhotos(context, getAllLoadedPhotos().filter { it.id in ids })
    }

    fun deleteSelected() {
        val selectedIds = selectionManager.selectedPhotoIds.value
        selectionManager.deleteSelected {
            _uiState.value = _uiState.value.copy(
                months = removeSelectedPhotos(_uiState.value.months, selectedIds)
            )
        }
    }

    fun updateColumnCount(newCount: Int) {
        val clamped = newCount.coerceIn(2, 6)
        if (clamped != _uiState.value.columnCount) {
            _uiState.value = _uiState.value.copy(columnCount = clamped)
        }
    }

    fun clearToastMessage() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    fun getThumbUrl(photo: UnifiedPhotoItem): String {
        return ThumbnailUrlResolver.resolve(
            photo = photo,
            galleryRepository = galleryRepository,
            imageCloudUrl = { galleryRepository.getThumbUrlByMd5(it.md5) }
        )
    }

    fun getAllLoadedPhotos(): List<UnifiedPhotoItem> {
        return _uiState.value.months.flatMap { month -> month.days.flatMap { it.photos } }
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
                    days = monthPhotos
                        .sortedForTimeline()
                        .groupBy { it.mtime.take(10) }
                        .toSortedMap(compareByDescending { it })
                        .map { (date, dayPhotos) ->
                            DayGroup(date = date, photos = dayPhotos)
                        },
                    isLoaded = true
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
        }.filter { it.days.isNotEmpty() }
    }

    private fun formatYearMonth(ym: String): UiText {
        val parts = ym.split("-")
        return if (parts.size >= 2) {
            UiText.StringResource(R.string.year_month_format, parts[0], parts[1].toIntOrNull() ?: 1)
        } else {
            UiText.DynamicString(ym)
        }
    }

    class Factory(
        private val galleryRepository: GalleryRepository,
        private val prefsManager: PrefsManager,
        private val serverOpTaskRepository: ServerOpTaskRepository,
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PrivateAlbumViewModel(
                galleryRepository,
                prefsManager,
                serverOpTaskRepository,
                appContext
            ) as T
        }
    }
}
