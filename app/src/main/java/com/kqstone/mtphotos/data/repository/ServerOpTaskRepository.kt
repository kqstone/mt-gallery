package com.kqstone.mtphotos.data.repository

import android.util.Log
import com.google.gson.Gson
import com.kqstone.mtphotos.AppContainer
import com.kqstone.mtphotos.data.local.db.AppDatabase
import com.kqstone.mtphotos.data.local.db.MediaEntity
import com.kqstone.mtphotos.data.local.db.ServerOpStatus
import com.kqstone.mtphotos.data.local.db.ServerOpTaskDao
import com.kqstone.mtphotos.data.local.db.ServerOpTaskEntity
import com.kqstone.mtphotos.data.local.db.ServerOpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

private const val TAG = "ServerOpTaskRepo"

/** 超时 RUNNING 任务的阈值（30 分钟） */
private const val STALE_RUNNING_MINUTES = 30L

/** 前 N 次立即重试（在同一 Worker 执行周期内） */
private const val IMMEDIATE_RETRY_COUNT = 3

data class ServerOpProcessResult(
    val processed: Int,
    val succeeded: Int,
    val failed: Int
)

class ServerOpTaskRepository(
    private val container: AppContainer,
    private val database: AppDatabase
) {
    private val dao: ServerOpTaskDao get() = database.serverOpTaskDao()
    private val gson = Gson()

    // ===== 提交任务 =====

    /**
     * 提交云端删除任务（批量）。
     * 调用方应已先完成本地 DB 变更和 UI 更新。
     */
    suspend fun enqueueCloudDelete(entities: List<MediaEntity>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val tasks = entities
            .mapNotNull { entity ->
                val cloudId = entity.cloudId?.takeIf { it > 0 } ?: return@mapNotNull null
                ServerOpTaskEntity(
                    opType = ServerOpType.CLOUD_DELETE,
                    mediaFileName = entity.fileName,
                    mediaMd5 = entity.cloudMd5 ?: entity.md5,
                    mediaCloudId = cloudId,
                    params = "{}",
                    nextAttemptAt = now,
                    createdAt = now,
                    updatedAt = now
                )
            }
            .distinctBy { it.mediaCloudId }

        if (tasks.isNotEmpty()) {
            dao.insertAll(tasks)
            Log.d(TAG, "Enqueued ${tasks.size} cloud delete tasks")
        }
    }

    /**
     * 提交收藏/取消收藏任务。
     */
    suspend fun enqueueFavorite(
        fileId: Double,
        isFavorite: Boolean,
        fileName: String,
        md5: String
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val params = gson.toJson(mapOf("fileId" to fileId, "isFavorite" to isFavorite))
        dao.insert(
            ServerOpTaskEntity(
                opType = ServerOpType.FAVORITE,
                mediaFileName = fileName,
                mediaMd5 = md5,
                mediaCloudId = fileId,
                params = params,
                nextAttemptAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
        Log.d(TAG, "Enqueued favorite task: $fileName, isFavorite=$isFavorite")
    }

    /**
     * 提交修改人物名称任务。
     */
    suspend fun enqueueRenamePerson(
        personId: Double,
        newName: String,
        personName: String
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val params = gson.toJson(mapOf("personId" to personId, "newName" to newName))
        dao.insert(
            ServerOpTaskEntity(
                opType = ServerOpType.RENAME_PERSON,
                mediaFileName = personName,
                mediaMd5 = "",
                mediaCloudId = personId,
                params = params,
                nextAttemptAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
        Log.d(TAG, "Enqueued rename person task: $personName -> $newName")
    }

    /**
     * 提交添加/移除标签任务。
     */
    suspend fun enqueueTag(
        fileId: Double,
        tagName: String,
        isAdd: Boolean,
        fileName: String,
        md5: String
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val params = gson.toJson(mapOf("fileId" to fileId, "tagName" to tagName, "isAdd" to isAdd))
        dao.insert(
            ServerOpTaskEntity(
                opType = ServerOpType.TAG,
                mediaFileName = fileName,
                mediaMd5 = md5,
                mediaCloudId = fileId,
                params = params,
                nextAttemptAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
        Log.d(TAG, "Enqueued tag task: $fileName, tag=$tagName, isAdd=$isAdd")
    }

    /**
     * 提交隐藏/取消隐藏任务。
     */
    suspend fun enqueueHide(
        fileIds: List<Double>,
        isHide: Boolean,
        fileName: String,
        md5: String
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val params = gson.toJson(mapOf("fileIds" to fileIds, "isHide" to isHide))
        dao.insert(
            ServerOpTaskEntity(
                opType = ServerOpType.HIDE,
                mediaFileName = fileName,
                mediaMd5 = md5,
                mediaCloudId = fileIds.firstOrNull(),
                params = params,
                nextAttemptAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
        Log.d(TAG, "Enqueued hide task: $fileName, isHide=$isHide, count=${fileIds.size}")
    }

    /**
     * 记录备份上传结果（由 BackupWorker 调用）。
     * 不由队列执行，仅作日志记录。
     */
    suspend fun recordBackupResult(
        fileName: String,
        md5: String,
        cloudId: Double?,
        success: Boolean,
        error: String? = null
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.insert(
            ServerOpTaskEntity(
                opType = ServerOpType.BACKUP_UPLOAD,
                status = if (success) ServerOpStatus.SUCCESS else ServerOpStatus.ERROR,
                mediaFileName = fileName,
                mediaMd5 = md5,
                mediaCloudId = cloudId,
                params = "{}",
                attemptCount = if (success) 0 else 1,
                nextAttemptAt = now,
                lastError = error,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    // ===== 执行任务（由 Worker 调用） =====

    /**
     * 处理待执行的服务器操作任务。
     * 前 [IMMEDIATE_RETRY_COUNT] 次失败在本次调用中立即重试（循环内同步重试），
     * 之后按指数退避调度。
     */
    suspend fun processPendingTasks(limit: Int = 25): ServerOpProcessResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.resetStaleRunning(
            staleBefore = now - TimeUnit.MINUTES.toMillis(STALE_RUNNING_MINUTES),
            now = now
        )

        val tasks = dao.getDueTasks(now, limit)
        var succeeded = 0
        var failed = 0

        for (task in tasks) {
            val claimed = dao.claim(task.id)
            if (claimed == 0) continue

            val result = executeWithRetry(task)
            if (result) {
                succeeded++
            } else {
                failed++
            }
        }

        ServerOpProcessResult(processed = succeeded + failed, succeeded = succeeded, failed = failed)
    }

    /**
     * 执行单个任务，包含立即重试逻辑。
     * @return true 如果最终成功
     */
    private suspend fun executeWithRetry(task: ServerOpTaskEntity): Boolean {
        var currentAttempt = task.attemptCount

        // 包含立即重试的循环
        while (true) {
            try {
                executeTask(task)
                dao.markSuccess(task.id)
                Log.d(TAG, "Task ${task.id} (${task.opType}) succeeded")
                return true
            } catch (e: Exception) {
                currentAttempt++

                // 对于 "已删除" 类的错误，视为成功
                if (task.opType == ServerOpType.CLOUD_DELETE && isAlreadyDeletedError(e)) {
                    dao.markSuccess(task.id)
                    Log.d(TAG, "Task ${task.id} (CLOUD_DELETE) already deleted, marking success")
                    return true
                }

                if (currentAttempt >= task.maxAttempts) {
                    // 超过最大重试次数 → FAILED
                    dao.markError(
                        id = task.id,
                        status = ServerOpStatus.FAILED,
                        attemptCount = currentAttempt,
                        nextAttemptAt = System.currentTimeMillis(),
                        lastError = e.message ?: e::class.java.simpleName
                    )
                    Log.w(TAG, "Task ${task.id} (${task.opType}) permanently failed after $currentAttempt attempts", e)
                    return false
                }

                val delay = retryDelayMillis(currentAttempt)
                if (delay == 0L && currentAttempt <= IMMEDIATE_RETRY_COUNT) {
                    // 立即重试：更新 attemptCount 后继续循环
                    dao.markError(
                        id = task.id,
                        status = ServerOpStatus.PENDING,
                        attemptCount = currentAttempt,
                        nextAttemptAt = System.currentTimeMillis(),
                        lastError = e.message ?: e::class.java.simpleName
                    )
                    Log.d(TAG, "Task ${task.id} (${task.opType}) immediate retry #$currentAttempt")
                    // 重新 claim
                    val reclaimed = dao.claim(task.id)
                    if (reclaimed == 0) return false
                    continue
                } else {
                    // 指数退避 → ERROR 状态
                    dao.markError(
                        id = task.id,
                        status = ServerOpStatus.ERROR,
                        attemptCount = currentAttempt,
                        nextAttemptAt = System.currentTimeMillis() + delay,
                        lastError = e.message ?: e::class.java.simpleName
                    )
                    Log.w(TAG, "Task ${task.id} (${task.opType}) error #$currentAttempt, retry in ${delay}ms", e)
                    return false
                }
            }
        }
    }

    // ===== 任务执行路由 =====

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeTask(task: ServerOpTaskEntity) {
        val params = gson.fromJson(task.params, Map::class.java) as? Map<String, Any> ?: emptyMap()
        when (task.opType) {
            ServerOpType.CLOUD_DELETE -> {
                val cloudId = task.mediaCloudId ?: throw IllegalStateException("Missing cloudId for CLOUD_DELETE")
                deleteCloudFile(cloudId)
            }
            ServerOpType.FAVORITE -> {
                val fileId = (params["fileId"] as? Number)?.toDouble()
                    ?: throw IllegalStateException("Missing fileId for FAVORITE")
                val isFavorite = params["isFavorite"] as? Boolean
                    ?: throw IllegalStateException("Missing isFavorite for FAVORITE")
                container.galleryRepository.toggleFavorite(fileId, isFavorite).getOrThrow()
                Log.d(TAG, "FAVORITE task executed: fileId=$fileId, isFavorite=$isFavorite")
            }
            ServerOpType.RENAME_PERSON -> {
                // 预留
                Log.d(TAG, "RENAME_PERSON task executed (placeholder): params=$params")
            }
            ServerOpType.TAG -> {
                // 预留
                Log.d(TAG, "TAG task executed (placeholder): params=$params")
            }
            ServerOpType.HIDE -> {
                // 预留
                Log.d(TAG, "HIDE task executed (placeholder): params=$params")
            }
            ServerOpType.BACKUP_UPLOAD -> {
                // 备份上传记录不由队列执行
            }
        }
    }

    private suspend fun deleteCloudFile(cloudId: Double) {
        val body: Map<String, Any> = mapOf("fileIds" to listOf(cloudId.toInt()))
        val response = container.gatewayApi.GatewayControllerPart3DeleteFiles(body)
        val code = response["code"]?.toString()
        if (!code.isNullOrBlank() && code !in setOf("0", "OK", "SUCCESS", "success")) {
            val message = response["message"]?.toString()
                ?: response["msg"]?.toString()
                ?: code
            throw IllegalStateException(message)
        }
    }

    // ===== 重试策略 =====

    /**
     * 计算重试延迟。
     * 前 3 次立即重试（返回 0），之后按指数退避。
     */
    private fun retryDelayMillis(attemptCount: Int): Long {
        if (attemptCount <= IMMEDIATE_RETRY_COUNT) return 0L
        val minutes = when (attemptCount) {
            4 -> 5L
            5 -> 15L
            6 -> 60L
            7 -> 3 * 60L
            8 -> 6 * 60L
            else -> 24 * 60L
        }
        return TimeUnit.MINUTES.toMillis(minutes)
    }

    private fun isAlreadyDeletedError(error: Throwable): Boolean {
        if ((error as? HttpException)?.code() == 404) return true
        val text = listOfNotNull(error.message, error.cause?.message)
            .joinToString(" ")
            .lowercase()
        return listOf(
            "not found",
            "not exist",
            "notfound",
            "already deleted",
            "不存在",
            "未找到",
            "已删除"
        ).any { it in text }
    }

    // ===== 日志观察 =====

    /** 观察全部任务 */
    fun observeAllTasks(): Flow<List<ServerOpTaskEntity>> = dao.getAllTasksFlow()

    /** 组合过滤观察 */
    fun observeFilteredTasks(
        statuses: Set<ServerOpStatus>?,
        opTypes: Set<ServerOpType>?
    ): Flow<List<ServerOpTaskEntity>> {
        val statusStrings = statuses?.map { it.name }
        val typeStrings = opTypes?.map { it.name }
        return when {
            statusStrings != null && typeStrings != null ->
                dao.getFilteredTasksFlow(statusStrings, typeStrings)
            statusStrings != null ->
                dao.getTasksByStatusFlow(statusStrings)
            typeStrings != null ->
                dao.getTasksByTypeFlow(typeStrings)
            else ->
                dao.getAllTasksFlow()
        }
    }

    // ===== 手动重试 =====

    /** 手动重试失败的任务 */
    suspend fun retryTask(taskId: Long) = withContext(Dispatchers.IO) {
        val updated = dao.retryFailed(taskId)
        if (updated > 0) {
            Log.d(TAG, "Task $taskId reset to PENDING for manual retry")
        }
    }

    // ===== 统计 =====

    suspend fun hasPendingTasks(): Boolean = withContext(Dispatchers.IO) {
        dao.countPending() > 0
    }

    suspend fun nextRetryDelayMillis(): Long = withContext(Dispatchers.IO) {
        val next = dao.nextAttemptAt() ?: return@withContext 0L
        (next - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    // ===== 清理 =====

    /** 清理 N 天前的成功日志 */
    suspend fun cleanupOldSuccessTasks(retainDays: Int = 7) = withContext(Dispatchers.IO) {
        val olderThan = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retainDays.toLong())
        val deleted = dao.deleteOldSuccessTasks(olderThan)
        if (deleted > 0) {
            Log.d(TAG, "Cleaned up $deleted old success tasks")
        }
    }
}
