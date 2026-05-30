package com.kqstone.mtphotos.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kqstone.mtphotos.MTPhotosApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SyncWorker"

/**
 * 后台同步 Worker：扫描本地 MediaStore，更新 Room DB，触发备份。
 * 由 MediaChangeObserver 检测到媒体变化后触发（expedited）。
 * 也由 PeriodicSyncWorker 周期性触发作为兜底。
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

            if (!prefsManager.getBackupEnabledSync()) {
                Log.d(TAG, "Backup not enabled, skipping sync")
                return@withContext Result.success()
            }

            // 获取用户选择的备份文件夹
            val folderSelection = prefsManager.getBackupFolderSelectionSync()
            val folders = folderSelection.effectiveFolders
            if (folderSelection.isConfigured) {
                syncRepo.reconcileFolderSelection(folders)
            }

            // 执行轻量本地同步
            val result = syncRepo.syncLocalMedia(folders)
            Log.d(TAG, "Sync complete: ${result.newCount} new, ${result.removedCount} removed")

            // 有待备份文件 → 触发备份
            val pending = syncRepo.getPendingBackupMedia(folders)
            if (pending.isNotEmpty()) {
                Log.d(TAG, "Found ${pending.size} files pending backup, triggering backup")
                val wifiOnly = prefsManager.getBackupWifiOnlySync()
                BackupScheduler.triggerImmediateBackup(applicationContext, wifiOnly)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.retry()
        }
    }
}
