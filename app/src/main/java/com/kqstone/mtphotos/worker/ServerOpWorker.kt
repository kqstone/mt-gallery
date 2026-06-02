package com.kqstone.mtphotos.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kqstone.mtphotos.MTPhotosApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ServerOpWorker"

/**
 * 统一的服务器操作 Worker。
 * 替代原有的 CloudDeleteWorker，处理所有类型的服务器操作任务
 * （云端删除、收藏、标签、隐藏等）。
 */
class ServerOpWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val app = applicationContext as MTPhotosApp
            val repo = app.container.serverOpTaskRepository

            val result = repo.processPendingTasks()
            Log.d(
                TAG,
                "Server ops processed=${result.processed}, succeeded=${result.succeeded}, failed=${result.failed}"
            )

            // 如果还有待执行任务，调度下次执行
            if (repo.hasPendingTasks()) {
                BackupScheduler.scheduleServerOpRetry(
                    applicationContext,
                    repo.nextRetryDelayMillis()
                )
            }

            // 清理 7 天前的成功日志
            repo.cleanupOldSuccessTasks()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Server op worker failed", e)
            Result.retry()
        }
    }
}
