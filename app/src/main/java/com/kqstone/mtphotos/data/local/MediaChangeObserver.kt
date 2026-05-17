package com.kqstone.mtphotos.data.local

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * 监听 MediaStore 变化的 ContentObserver。
 * 外部删除/添加媒体时设置脏标记，下次 sync 时做轻量级 ID 验证。
 */
class MediaChangeObserver(context: Context) {

    companion object {
        @Volatile
        var isDirty: Boolean = false
            private set

        fun markDirty() {
            isDirty = true
        }

        fun clearDirty() {
            isDirty = false
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val imageObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            markDirty()
        }
    }

    private val videoObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            markDirty()
        }
    }

    private val contentResolver = context.contentResolver

    fun register() {
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            imageObserver
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            videoObserver
        )
    }

    fun unregister() {
        contentResolver.unregisterContentObserver(imageObserver)
        contentResolver.unregisterContentObserver(videoObserver)
    }
}
