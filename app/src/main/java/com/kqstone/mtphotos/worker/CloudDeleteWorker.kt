package com.kqstone.mtphotos.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kqstone.mtphotos.MTPhotosApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "CloudDeleteWorker"

class CloudDeleteWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val app = applicationContext as MTPhotosApp
            val syncRepo = app.container.syncRepository

            val result = syncRepo.processPendingCloudDeletes()
            Log.d(
                TAG,
                "Cloud delete processed=${result.processed}, succeeded=${result.succeeded}, failed=${result.failed}"
            )

            if (syncRepo.hasPendingCloudDeleteTasks()) {
                BackupScheduler.scheduleCloudDeleteRetry(
                    applicationContext,
                    syncRepo.nextCloudDeleteDelayMillis()
                )
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Cloud delete worker failed", e)
            Result.retry()
        }
    }
}
