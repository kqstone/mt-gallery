package com.kqstone.mtphotos.ui.util

import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.SyncRepository

object LocalVideoThumbnailWarmup {
    suspend fun warm(
        photos: List<UnifiedPhotoItem>,
        syncRepository: SyncRepository?,
        onThumbnailReady: (UnifiedPhotoItem, String) -> Unit
    ) {
        val repository = syncRepository ?: return
        photos
            .filter { it.needsLocalVideoThumbnail() }
            .forEach { photo ->
                repository.ensureLocalVideoThumbnail(photo)?.let { path ->
                    onThumbnailReady(photo, path)
                }
            }
    }

    private fun UnifiedPhotoItem.needsLocalVideoThumbnail(): Boolean {
        return isVideo() &&
            cloudId == null &&
            !isStorageOptimized &&
            !localUri.isNullOrEmpty()
    }
}
