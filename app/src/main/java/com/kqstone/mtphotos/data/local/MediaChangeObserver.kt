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
private const val DEBOUNCE_MS = 3000L

/**
 * 监听 MediaStore 变化的 ContentObserver。
 * 外部删除/添加媒体时设置脏标记，并在防抖后触发后台同步。
 * 仅在备份启用时触发 SyncWorker。
 */
class MediaChangeObserver(
    private val context: Context,
    private val isBackupEnabled: () -> Boolean = { false }
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
    private var lastNonPendingCount = -1L

    private val debounceRunnable = Runnable {
        Log.d(TAG, "Debounce fired, backupEnabled=${isBackupEnabled()}")
        if (!isBackupEnabled()) return@Runnable
        checkAndTriggerSync()
    }

    private fun onMediaChanged() {
        Log.d(TAG, "MediaStore changed, backupEnabled=${isBackupEnabled()}")
        markDirty()
        handler.removeCallbacks(debounceRunnable)
        handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    /**
     * 查询 MediaStore 中 IS_PENDING=0 的记录总数，与上次对比。
     * 有变化才触发 SyncWorker，避免拍照未完成时误触发。
     */
    private fun checkAndTriggerSync() {
        try {
            val currentCount = queryNonPendingCount()
            if (currentCount < 0) {
                // 查询失败，直接触发（保守策略）
                BackupScheduler.triggerSyncWork(context)
                return
            }
            if (lastNonPendingCount >= 0 && currentCount == lastNonPendingCount) {
                // 非 PENDING 计数没变，可能是 PENDING 状态变化，不触发
                Log.d(TAG, "Non-pending count unchanged ($currentCount), skipping sync trigger")
                return
            }
            lastNonPendingCount = currentCount
            Log.d(TAG, "Non-pending count changed to $currentCount, triggering sync")
            BackupScheduler.triggerSyncWork(context)
        } catch (e: Exception) {
            Log.w(TAG, "checkAndTriggerSync failed, triggering sync anyway", e)
            BackupScheduler.triggerSyncWork(context)
        }
    }

    /**
     * 查询 MediaStore 中非 PENDING 的图片+视频总数。
     * @return 总数，查询失败返回 -1
     */
    private fun queryNonPendingCount(): Long {
        var total = 0L
        for (uri in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.IS_PENDING} = 0",
                    null,
                    null
                )?.use { cursor ->
                    total += cursor.count.toLong()
                }
            } catch (e: Exception) {
                Log.w(TAG, "queryNonPendingCount failed for $uri", e)
                return -1
            }
        }
        return total
    }

    private val imageObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onMediaChanged()
        }
    }

    private val videoObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onMediaChanged()
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
