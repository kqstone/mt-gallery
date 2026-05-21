package com.kqstone.mtphotos.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val TAG = "BackupScheduler"
private const val PERIODIC_BACKUP_WORK = "periodic_backup"
private const val PERIODIC_SYNC_WORK = "periodic_sync"
private const val ONE_TIME_SYNC_WORK = "one_time_sync"
private const val ONE_TIME_BACKUP_WORK = "one_time_backup"
private const val DEFAULT_SYNC_INTERVAL_MINUTES = 60L

/**
 * 备份调度器：管理 WorkManager 的同步和备份任务调度。
 */
object BackupScheduler {

    // ===== 同步任务 =====

    /**
     * 触发一次本地同步（由 MediaChangeObserver 调用）。
     * 使用 KEEP 策略，避免重复排队。
     */
    fun triggerSyncWork(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_SYNC_WORK,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
        Log.d(TAG, "Triggered sync work")
    }

    /**
     * 调度周期性同步（兜底安全网）。
     * @param intervalMinutes 同步间隔（分钟），默认 60
     */
    fun schedulePeriodicSync(context: Context, intervalMinutes: Long = DEFAULT_SYNC_INTERVAL_MINUTES) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            intervalMinutes, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        Log.d(TAG, "Scheduled periodic sync (interval=${intervalMinutes}min)")
    }

    // ===== 备份任务 =====

    /**
     * 启动周期性备份任务（每小时检查一次）
     * @param wifiOnly 是否仅在 Wi-Fi 下备份
     */
    fun schedulePeriodicBackup(context: Context, wifiOnly: Boolean = true) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<BackupWorker>(
            1, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_BACKUP_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        Log.d(TAG, "Scheduled periodic backup (wifiOnly=$wifiOnly)")
    }

    /**
     * 手动触发一次备份
     */
    fun triggerImmediateBackup(
        context: Context,
        wifiOnly: Boolean = true,
        replaceExisting: Boolean = false
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()

        val workRequest = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_BACKUP_WORK,
            if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            workRequest
        )
        Log.d(TAG, "Triggered immediate backup")
    }

    // ===== 组合调度 =====

    /**
     * 调度所有后台任务（同步 + 备份）。
     * 应在 app 启动和开启备份时调用。
     * @param wifiOnly 备份是否仅 Wi-Fi
     * @param syncIntervalMinutes 同步间隔（分钟），默认 60
     */
    fun scheduleAll(context: Context, wifiOnly: Boolean = true, syncIntervalMinutes: Long = DEFAULT_SYNC_INTERVAL_MINUTES) {
        schedulePeriodicSync(context, syncIntervalMinutes)
        schedulePeriodicBackup(context, wifiOnly)
        Log.d(TAG, "Scheduled all work (wifiOnly=$wifiOnly, syncInterval=${syncIntervalMinutes}min)")
    }

    // ===== 取消任务 =====

    /**
     * 取消周期性备份
     */
    fun cancelPeriodicBackup(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_BACKUP_WORK)
        Log.d(TAG, "Cancelled periodic backup")
    }

    /**
     * 取消所有同步和备份任务
     */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC_SYNC_WORK)
        wm.cancelUniqueWork(PERIODIC_BACKUP_WORK)
        wm.cancelUniqueWork(ONE_TIME_SYNC_WORK)
        wm.cancelUniqueWork(ONE_TIME_BACKUP_WORK)
        Log.d(TAG, "Cancelled all work")
    }
}
