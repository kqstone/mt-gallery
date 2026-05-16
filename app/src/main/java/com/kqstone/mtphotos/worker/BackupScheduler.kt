package com.kqstone.mtphotos.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val TAG = "BackupScheduler"
private const val PERIODIC_BACKUP_WORK = "periodic_backup"
private const val ONE_TIME_BACKUP_WORK = "one_time_backup"

/**
 * 备份调度器：管理 WorkManager 的备份任务调度。
 */
object BackupScheduler {

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
    fun triggerImmediateBackup(context: Context, wifiOnly: Boolean = true) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // 手动触发时只需要网络连接
            .build()

        val workRequest = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
        Log.d(TAG, "Triggered immediate backup")
    }

    /**
     * 取消周期性备份
     */
    fun cancelPeriodicBackup(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_BACKUP_WORK)
        Log.d(TAG, "Cancelled periodic backup")
    }

    /**
     * 取消所有备份任务
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(ONE_TIME_BACKUP_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_BACKUP_WORK)
        Log.d(TAG, "Cancelled all backup work")
    }
}
