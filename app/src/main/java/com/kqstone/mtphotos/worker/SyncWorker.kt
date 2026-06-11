package com.kqstone.mtphotos.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kqstone.mtphotos.MTPhotosApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SyncWorker"
private val PENDING_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L)

/**
 * Refreshes the local MediaStore index into Room.
 *
 * This worker is also used as the entry point for backup, but backup-specific
 * MD5 work and upload scheduling only run when backup is enabled.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val app = applicationContext as MTPhotosApp
            val container = app.container
            val syncRepo = container.syncRepository
            val prefsManager = container.prefsManager
            val backupEnabled = prefsManager.getBackupEnabledSync()
            val incremental = inputData.getBoolean(KEY_SYNC_INCREMENTAL, false)
            val retryCount = inputData.getInt(KEY_SYNC_RETRY_COUNT, 0)

            val folderSelection = prefsManager.getBackupFolderSelectionSync()
            val folders = folderSelection.effectiveFolders
            if (folderSelection.isConfigured) {
                syncRepo.reconcileFolderSelection(folders)
            }

            val result = syncRepo.syncLocalMedia(
                folders = folders,
                incremental = incremental
            )
            Log.d(TAG, "Local index sync complete: ${result.newCount} new, ${result.removedCount} removed")

            if (incremental && result.newCount == 0 && retryCount < PENDING_RETRY_DELAYS_MS.size) {
                BackupScheduler.triggerSyncWork(
                    applicationContext,
                    incremental = true,
                    retryCount = retryCount + 1,
                    delayMillis = PENDING_RETRY_DELAYS_MS[retryCount]
                )
            }

            if (backupEnabled) {
                syncRepo.computeMd5InBackground()

                val pending = syncRepo.getPendingBackupMedia(folders)
                if (pending.isNotEmpty()) {
                    Log.d(TAG, "Found ${pending.size} files pending backup, triggering backup")
                    val wifiOnly = prefsManager.getBackupWifiOnlySync()
                    BackupScheduler.triggerImmediateBackup(applicationContext, wifiOnly)
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            (applicationContext as? MTPhotosApp)?.container?.prefsManager?.markRecoverableFailure(e)
            Result.retry()
        }
    }
}
