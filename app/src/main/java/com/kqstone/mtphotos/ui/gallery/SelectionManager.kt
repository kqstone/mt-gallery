package com.kqstone.mtphotos.ui.gallery

import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.ui.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SelectionManager(
    private val scope: CoroutineScope,
    private val onDelete: suspend (List<Double>) -> Result<Unit>,
    private val onError: (UiText) -> Unit
) {
    private val _selectedPhotoIds = MutableStateFlow<Set<Double>>(emptySet())
    val selectedPhotoIds: StateFlow<Set<Double>> = _selectedPhotoIds

    val isSelectionMode: Boolean get() = _selectedPhotoIds.value.isNotEmpty()

    fun toggleSelection(photoId: Double) {
        val current = _selectedPhotoIds.value
        _selectedPhotoIds.value = if (photoId in current) current - photoId else current + photoId
    }

    fun setSelectedIds(photoIds: Set<Double>) {
        _selectedPhotoIds.value = photoIds
    }

    fun startDragSelection(photoId: Double) {
        if (!isSelectionMode) {
            _selectedPhotoIds.value = setOf(photoId)
        }
    }

    fun dragSelect(photoId: Double) {
        if (isSelectionMode) {
            _selectedPhotoIds.value = _selectedPhotoIds.value + photoId
        }
    }

    fun clearSelection() {
        _selectedPhotoIds.value = emptySet()
    }

    fun selectAll(photoIds: List<Double>) {
        _selectedPhotoIds.value = photoIds.toSet()
    }

    fun deleteSelected(onSuccess: () -> Unit) {
        val ids = _selectedPhotoIds.value.toList()
        if (ids.isEmpty()) return

        scope.launch {
            onDelete(ids).fold(
                onSuccess = {
                    _selectedPhotoIds.value = emptySet()
                    onSuccess()
                },
                onFailure = { e ->
                    onError(UiText.StringResource(R.string.delete_failed_format, e.message.orEmpty()))
                }
            )
        }
    }
}
