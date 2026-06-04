package com.kqstone.mtphotos.ui.util

import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import java.io.File

object ThumbnailCacheKeys {
    fun forPhoto(photo: UnifiedPhotoItem, url: String): String? {
        val id = when {
            photo.md5.isNotEmpty() -> photo.md5
            photo.dbId > 0 -> "db_${photo.dbId}"
            else -> return null
        }
        val source = sourceTag(url, isVideo = photo.isVideo())
        return "${source}_${id}_256"
    }

    fun forUrl(key: String?, url: String): String? {
        if (key.isNullOrEmpty()) return null
        return "${sourceTag(url, isVideo = false)}_${key}_256"
    }

    private fun sourceTag(url: String, isVideo: Boolean): String {
        return when {
            url.startsWith("file://") -> {
                val file = File(url.removePrefix("file://"))
                val version = if (file.exists()) "${file.length()}_${file.lastModified()}" else "missing"
                if (isVideo) "local_video_$version" else "local_image_$version"
            }
            url.contains("/gateway/h220/") -> "cloud_video_h220"
            url.contains("/gateway/s260/") -> "cloud_image_s260"
            url.contains("/gateway/file/") -> if (isVideo) "cloud_video_file" else "cloud_image_file"
            isVideo -> "video"
            else -> "image"
        }
    }
}
