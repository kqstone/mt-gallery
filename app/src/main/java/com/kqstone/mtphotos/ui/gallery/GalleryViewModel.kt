package com.kqstone.mtphotos.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.PhotoItem
import com.kqstone.mtphotos.data.repository.TimelineMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DayGroup(
    val date: String,
    val photos: List<PhotoItem>
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
    val selectedPhotoIds: Set<Double> = emptySet(),
    val isSelectionMode: Boolean = false,
    val columnCount: Int = 3,
    val isDeleting: Boolean = false
)

class GalleryViewModel(private val galleryRepository: GalleryRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState

    init {
        loadTimeline()
    }

    fun loadTimeline() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
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
                        isLoading = false
                    )
                    // Auto-load the first month
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
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        viewModelScope.launch {
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

    fun loadMonthFiles(yearMonth: String) {
        val current = _uiState.value
        if (current.months.any { it.yearMonth == yearMonth && it.isLoaded }) return

        viewModelScope.launch {
            val result = galleryRepository.getMonthFiles(yearMonth)
            result.fold(
                onSuccess = { files ->
                    val days = files
                        .groupBy { extractDate(it.mtime) }
                        .toSortedMap(compareByDescending { it })
                        .map { (date, dayPhotos) -> DayGroup(date, dayPhotos) }

                    val updatedMonths = current.months.map { month ->
                        if (month.yearMonth == yearMonth) {
                            month.copy(days = days, isLoaded = true)
                        } else {
                            month
                        }
                    }
                    _uiState.value = _uiState.value.copy(months = updatedMonths)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = "加载 ${formatYearMonth(yearMonth)} 失败: ${e.message}"
                    )
                }
            )
        }
    }

    fun toggleMonthExpand(yearMonth: String) {
        val current = _uiState.value
        val month = current.months.find { it.yearMonth == yearMonth } ?: return
        if (month.isLoaded) {
            // Collapse: unload the month
            val updatedMonths = current.months.map { m ->
                if (m.yearMonth == yearMonth) {
                    m.copy(days = emptyList(), isLoaded = false)
                } else {
                    m
                }
            }
            _uiState.value = _uiState.value.copy(months = updatedMonths)
        } else {
            loadMonthFiles(yearMonth)
        }
    }

    // Selection management
    fun toggleSelection(photoId: Double) {
        val current = _uiState.value
        val newSelected = if (photoId in current.selectedPhotoIds) {
            current.selectedPhotoIds - photoId
        } else {
            current.selectedPhotoIds + photoId
        }
        _uiState.value = current.copy(
            selectedPhotoIds = newSelected,
            isSelectionMode = newSelected.isNotEmpty()
        )
    }

    fun startDragSelection(photoId: Double) {
        val current = _uiState.value
        if (!current.isSelectionMode) {
            _uiState.value = current.copy(
                selectedPhotoIds = setOf(photoId),
                isSelectionMode = true
            )
        }
    }

    fun dragSelect(photoId: Double) {
        val current = _uiState.value
        if (current.isSelectionMode) {
            _uiState.value = current.copy(
                selectedPhotoIds = current.selectedPhotoIds + photoId
            )
        }
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedPhotoIds = emptySet(),
            isSelectionMode = false
        )
    }

    fun selectAll() {
        val allPhotos = _uiState.value.months
            .flatMap { month -> month.days.flatMap { it.photos } }
        _uiState.value = _uiState.value.copy(
            selectedPhotoIds = allPhotos.map { it.id }.toSet(),
            isSelectionMode = true
        )
    }

    // Delete
    fun deleteSelected() {
        val selectedIds = _uiState.value.selectedPhotoIds.toList()
        if (selectedIds.isEmpty()) return

        _uiState.value = _uiState.value.copy(isDeleting = true)
        viewModelScope.launch {
            val result = galleryRepository.deleteFiles(selectedIds)
            result.fold(
                onSuccess = {
                    // Remove deleted photos from the loaded months
                    val updatedMonths = _uiState.value.months.map { month ->
                        val updatedDays = month.days.map { day ->
                            day.copy(photos = day.photos.filter { it.id !in selectedIds.toSet() })
                        }.filter { it.photos.isNotEmpty() }
                        val newTotal = updatedDays.sumOf { it.photos.size }
                        month.copy(
                            days = updatedDays,
                            totalCount = if (month.isLoaded) newTotal else month.totalCount
                        )
                    }.filter { !it.isLoaded || it.days.isNotEmpty() }
                    _uiState.value = _uiState.value.copy(
                        months = updatedMonths,
                        selectedPhotoIds = emptySet(),
                        isSelectionMode = false,
                        isDeleting = false
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isDeleting = false,
                        error = "删除失败: ${e.message}"
                    )
                }
            )
        }
    }

    // Column count for pinch-to-zoom
    fun updateColumnCount(newCount: Int) {
        val clamped = newCount.coerceIn(2, 6)
        if (clamped != _uiState.value.columnCount) {
            _uiState.value = _uiState.value.copy(columnCount = clamped)
        }
    }

    fun getThumbUrl(md5: String, fileId: Double): String {
        return galleryRepository.getThumbUrl(md5, fileId)
    }

    fun getFullImageUrl(id: Double, md5: String): String {
        return galleryRepository.getFullImageUrl(id, md5)
    }

    fun getAllLoadedPhotos(): List<PhotoItem> {
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

    class Factory(private val galleryRepository: GalleryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GalleryViewModel(galleryRepository) as T
        }
    }
}
