package com.kqstone.mtphotos.data.local

import android.content.Context
import java.io.File

object ThumbnailCachePaths {
    private const val COIL_IMAGE_CACHE_DIR = "coil_image_cache"
    private const val LOCAL_THUMBS_DIR = "thumbs"

    fun coilImageCacheDir(context: Context): File {
        return activeDir(context, COIL_IMAGE_CACHE_DIR)
    }

    fun localThumbsDir(context: Context): File {
        return activeDir(context, LOCAL_THUMBS_DIR)
    }

    fun legacyCoilImageCacheDir(context: Context): File {
        return File(context.cacheDir, COIL_IMAGE_CACHE_DIR)
    }

    fun legacyLocalThumbsDir(context: Context): File {
        return File(context.cacheDir, LOCAL_THUMBS_DIR)
    }

    private fun activeDir(context: Context, name: String): File {
        val target = File(context.filesDir, name).also { it.mkdirs() }
        migrateContents(File(context.cacheDir, name), target)
        return target
    }

    private fun migrateContents(source: File, target: File) {
        val files = source.listFiles() ?: return
        for (file in files) {
            val destination = File(target, file.name)
            if (destination.exists()) continue
            file.renameTo(destination)
        }
    }
}
