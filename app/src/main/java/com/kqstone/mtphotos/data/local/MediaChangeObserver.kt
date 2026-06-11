package com.kqstone.mtphotos.data.local

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.kqstone.mtphotos.worker.BackupScheduler

private const val TAG = "MediaChangeObserver"
private const val DEBOUNCE_MS = 250L

/**
 * Observes MediaStore changes and triggers a local Room index refresh.
 *
 * Backup upload remains controlled by the backup setting inside SyncWorker; the
 * local gallery index should be refreshed even when backup is disabled.
 */
class MediaChangeObserver(
    private val context: Context,
    private val isBackupEnabled: () -> Boolean = { false },
    private val onDebouncedChange: (List<Uri>) -> Unit = {}
) {

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
    private val pendingUris = mutableListOf<Uri>()

    private val debounceRunnable = Runnable {
        Log.d(TAG, "Debounce fired, backupEnabled=${isBackupEnabled()}")
        val uris = pendingUris.toList()
        pendingUris.clear()
        onDebouncedChange(uris)
        triggerLocalIndexSync()
    }

    private fun onMediaChanged(uri: Uri?) {
        Log.d(TAG, "MediaStore changed, backupEnabled=${isBackupEnabled()}")
        markDirty()
        uri?.let(pendingUris::add)
        handler.removeCallbacks(debounceRunnable)
        handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    private fun triggerLocalIndexSync() {
        try {
            BackupScheduler.triggerSyncWork(context)
        } catch (e: Exception) {
            Log.w(TAG, "triggerLocalIndexSync failed, triggering sync anyway", e)
            BackupScheduler.triggerSyncWork(context)
        }
    }

    private val imageObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onMediaChanged(uri)
        }
    }

    private val videoObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onMediaChanged(uri)
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
        Log.d(TAG, "Registered ContentObserver for images and videos")
    }

    fun unregister() {
        contentResolver.unregisterContentObserver(imageObserver)
        contentResolver.unregisterContentObserver(videoObserver)
    }
}
