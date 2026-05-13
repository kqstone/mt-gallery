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
    val error: String? = null
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

    fun getThumbUrl(md5: String, fileId: Double): String {
        return galleryRepository.getThumbUrl(md5, fileId)
    }

    fun getFullImageUrl(id: Double, md5: String): String {
        return galleryRepository.getFullImageUrl(id, md5)
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
