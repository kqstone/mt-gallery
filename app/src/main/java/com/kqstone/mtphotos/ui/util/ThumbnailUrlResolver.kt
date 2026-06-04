package com.kqstone.mtphotos.ui.util

import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import java.io.File

object ThumbnailUrlResolver {
    fun resolve(
        photo: UnifiedPhotoItem,
        galleryRepository: GalleryRepository,
        imageCloudUrl: (UnifiedPhotoItem) -> String
    ): String {
        photo.thumbCachePath?.let { path ->
            if (path.isNotEmpty() && File(path).let { it.exists() && it.length() > 0 }) {
                return "file://$path"
            }
        }

        if (photo.isVideo()) {
            return if (photo.cloudId != null && photo.md5.isNotEmpty()) {
                galleryRepository.getVideoThumbUrl(photo.md5)
            } else {
                ""
            }
        }

        photo.localUri?.let { uri ->
            if (uri.isNotEmpty() && !photo.isStorageOptimized) return uri
        }

        return if (photo.md5.isNotEmpty()) imageCloudUrl(photo) else ""
    }
}
