package com.kqstone.mtphotos.data.repository

import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface MediaUiMutation {
    data class Deleted(val photos: List<UnifiedPhotoItem>) : MediaUiMutation
    data class FavoriteChanged(
        val photos: List<UnifiedPhotoItem>,
        val isFavorite: Boolean
    ) : MediaUiMutation

    data class HideChanged(
        val photos: List<UnifiedPhotoItem>,
        val isHide: Boolean
    ) : MediaUiMutation

    data class PersonRenamed(
        val personId: String,
        val newName: String
    ) : MediaUiMutation
}

class MediaUiMutationBus {
    private val _mutations = MutableSharedFlow<MediaUiMutation>(
        extraBufferCapacity = 64
    )

    val mutations: SharedFlow<MediaUiMutation> = _mutations.asSharedFlow()

    fun publish(mutation: MediaUiMutation) {
        _mutations.tryEmit(mutation)
    }
}
