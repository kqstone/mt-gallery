package com.kqstone.mtphotos.ui.media

import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.data.repository.GalleryRepository
import com.kqstone.mtphotos.data.repository.SyncRepository
import com.kqstone.mtphotos.ui.util.LocalVideoThumbnailWarmup
import com.kqstone.mtphotos.ui.util.ThumbnailUrlResolver

object MediaThumbnailResolver {
    fun resolveTimelineThumb(
        photo: UnifiedPhotoItem,
        galleryRepository: GalleryRepository
    ): String {
        return ThumbnailUrlResolver.resolve(
            photo = photo,
            galleryRepository = galleryRepository,
            imageCloudUrl = { galleryRepository.getThumbUrlByMd5(it.md5) }
        )
    }

    fun resolveCloudThumbByMd5(
        md5: String,
        galleryRepository: GalleryRepository
    ): String {
        return galleryRepository.getThumbUrlByMd5(md5)
    }

    fun resolveCloudThumb(
        md5: String,
        fileId: Double,
        galleryRepository: GalleryRepository
    ): String {
        return if (md5.isNotBlank()) {
            resolveCloudThumbByMd5(md5, galleryRepository)
        } else {
            galleryRepository.getThumbUrl(md5, fileId)
        }
    }

    suspend fun warmLocalVideoThumbs(
        photos: List<UnifiedPhotoItem>,
        syncRepository: SyncRepository?
    ): List<Pair<Long, String>> {
        val updates = mutableListOf<Pair<Long, String>>()
        LocalVideoThumbnailWarmup.warm(photos, syncRepository) { photo, path ->
            updates.add(photo.dbId to path)
        }
        return updates
    }
}
