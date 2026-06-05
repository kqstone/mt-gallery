package com.kqstone.mtphotos.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.google.gson.Gson
import com.kqstone.mtphotos.AppContainer
import com.kqstone.mtphotos.data.local.db.AppDatabase
import com.kqstone.mtphotos.data.local.db.MediaEntity
import com.kqstone.mtphotos.data.local.db.ServerOpStatus
import com.kqstone.mtphotos.data.local.db.ServerOpTaskDao
import com.kqstone.mtphotos.data.local.db.ServerOpTaskEntity
import com.kqstone.mtphotos.data.local.db.ServerOpType
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.network.NetworkFailure
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
private const val NOT_FOUND_VERIFY_FIRST_DELAY_MS = 60_000L
private val NOT_FOUND_VERIFY_FINAL_DELAY_MS = TimeUnit.HOURS.toMillis(1)
private const val CLOUD_DELETE_NOT_FOUND_VERIFY_PREFIX = "CLOUD_DELETE_NOT_FOUND_VERIFY"
private const val CLOUD_DELETE_NOT_FOUND_VERIFY_FIRST = "FIRST"
private const val CLOUD_DELETE_NOT_FOUND_VERIFY_FINAL = "FINAL"

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
            var inserted = 0
            var skipped = 0
            database.withTransaction {
                for (task in tasks) {
                    val cloudId = task.mediaCloudId ?: continue
                    if (dao.countActiveTasksForCloudId(ServerOpType.CLOUD_DELETE, cloudId) > 0) {
                        skipped++
                    } else {
                        dao.insert(task)
                        inserted++
                    }
                }
            }
            Log.d(TAG, "Enqueued $inserted cloud delete tasks, skipped $skipped active duplicates")
        }
    }

    /**
     * 提交收藏/取消收藏任务。
     */
    suspend fun enqueueFavorite(
        cloudId: Double?,
        dbId: Long,
        isFavorite: Boolean,
        fileName: String,
        md5: String
    ) = withContext(Dispatchers.IO) {
        updateLocalFavorite(
            cloudId = cloudId,
            dbId = dbId,
            md5 = md5,
            fileName = fileName,
            isFavorite = isFavorite
        )

        val fileId = cloudId ?: run {
            Log.d(TAG, "Updated local-only favorite state: $fileName, isFavorite=$isFavorite")
            return@withContext
        }

        val now = System.currentTimeMillis()
        val params = gson.toJson(mapOf("fileId" to fileId, "isFavorite" to isFavorite))
        database.withTransaction {
            dao.deleteUnfinishedTasksForCloudId(ServerOpType.FAVORITE, fileId)
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
        }
        Log.d(TAG, "Enqueued favorite task: $fileName, isFavorite=$isFavorite")
    }

    /**
     * 提交修改人物名称任务。
     */
    suspend fun enqueueFavorites(
        photos: List<UnifiedPhotoItem>,
        isFavorite: Boolean
    ): Int = withContext(Dispatchers.IO) {
        val distinctPhotos = photos.distinctBy { photo ->
            photo.cloudId?.let { "cloud:$it" }
                ?: photo.dbId.takeIf { it > 0 }?.let { "db:$it" }
                ?: photo.md5.takeIf { it.isNotBlank() }?.let { "md5:$it" }
                ?: photo.uniqueKey
        }
        if (distinctPhotos.isEmpty()) return@withContext 0

        for (photo in distinctPhotos) {
            updateLocalFavorite(
                cloudId = photo.cloudId,
                dbId = photo.dbId,
                md5 = photo.md5,
                fileName = photo.fileName,
                fileType = photo.fileType,
                mtime = photo.mtime,
                width = photo.width.toInt(),
                height = photo.height.toInt(),
                isFavorite = isFavorite
            )
        }

        val now = System.currentTimeMillis()
        val tasks = distinctPhotos.mapNotNull { photo ->
            val cloudId = photo.cloudId ?: return@mapNotNull null
            ServerOpTaskEntity(
                opType = ServerOpType.FAVORITE,
                mediaFileName = photo.fileName,
                mediaMd5 = photo.md5,
                mediaCloudId = cloudId,
                params = gson.toJson(mapOf("fileId" to cloudId, "isFavorite" to isFavorite)),
                nextAttemptAt = now,
                createdAt = now,
                updatedAt = now
            )
        }
        if (tasks.isNotEmpty()) {
            database.withTransaction {
                for (task in tasks) {
                    val cloudId = task.mediaCloudId ?: continue
                    dao.deleteUnfinishedTasksForCloudId(ServerOpType.FAVORITE, cloudId)
                    dao.insert(task)
                }
            }
            Log.d(TAG, "Enqueued ${tasks.size} favorite tasks, isFavorite=$isFavorite")
        }
        tasks.size
    }

    private suspend fun updateLocalFavorite(
        cloudId: Double?,
        dbId: Long,
        md5: String,
        fileName: String,
        fileType: String = "",
        mtime: String = "",
        width: Int = 0,
        height: Int = 0,
        isFavorite: Boolean
    ) {
        val mediaDao = database.mediaDao()
        var updated = 0
        if (cloudId != null) {
            updated = mediaDao.updateFavoriteByCloudId(cloudId, isFavorite)
        }
        if (updated == 0 && dbId > 0) {
            updated = mediaDao.updateFavoriteById(dbId, isFavorite)
        }
        if (updated == 0 && md5.isNotEmpty()) {
            mediaDao.findByMd5(md5)?.let { existing ->
                updated = mediaDao.updateFavoriteById(existing.id, isFavorite)
            }
        }
        if (updated == 0 && cloudId != null) {
            mediaDao.insert(
                MediaEntity(
                    cloudId = cloudId,
                    md5 = md5,
                    cloudMd5 = md5,
                    fileName = fileName,
                    fileType = fileType,
                    mtime = mtime,
                    width = width,
                    height = height,
                    syncStatus = SyncStatus.CLOUD_ONLY,
                    isFavorite = isFavorite
                )
            )
        }
    }

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

            when (executeWithRetry(task)) {
                true -> succeeded++
                false -> failed++
                null -> Unit
            }
        }

        ServerOpProcessResult(processed = succeeded + failed, succeeded = succeeded, failed = failed)
    }

    suspend fun markRetryableTasksDueNow(): Int = withContext(Dispatchers.IO) {
        dao.markRetryableDueNow()
    }

    /**
     * 执行单个任务，包含立即重试逻辑。
     * @return true 如果最终成功
     */
    private suspend fun executeWithRetry(task: ServerOpTaskEntity): Boolean? {
        if (task.opType == ServerOpType.CLOUD_DELETE) {
            return executeCloudDeleteWithRetry(task)
        }

        var currentAttempt = task.attemptCount

        // 包含立即重试的循环
        while (true) {
            if (cancelIfObsolete(task)) {
                return null
            }
            try {
                executeTask(task)
                dao.markSuccess(task.id)
                Log.d(TAG, "Task ${task.id} (${task.opType}) succeeded")
                return true
            } catch (e: Exception) {
                currentAttempt++
                markRecoverableFailure(e)

                // 对于 "已删除" 类的错误，视为成功
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

    private suspend fun cancelIfObsolete(task: ServerOpTaskEntity): Boolean {
        if (task.opType != ServerOpType.FAVORITE) return false

        if (dao.countById(task.id) == 0) {
            Log.d(TAG, "Drop obsolete FAVORITE task ${task.id}: task row was replaced")
            return true
        }

        val target = favoriteTargetFromParams(task.params) ?: return false
        val cloudId = task.mediaCloudId ?: return false
        val current = database.mediaDao().findByCloudId(cloudId)?.isFavorite ?: return false
        if (current == target) return false

        dao.deleteById(task.id)
        Log.d(TAG, "Drop obsolete FAVORITE task ${task.id}: local=$current, taskTarget=$target")
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun favoriteTargetFromParams(params: String): Boolean? {
        return try {
            val map = gson.fromJson(params, Map::class.java) as? Map<String, Any>
            map?.get("isFavorite") as? Boolean
        } catch (_: Exception) {
            null
        }
    }

    // ===== 任务执行路由 =====

    private suspend fun executeCloudDeleteWithRetry(task: ServerOpTaskEntity): Boolean {
        val cloudId = task.mediaCloudId ?: run {
            dao.markError(
                id = task.id,
                status = ServerOpStatus.FAILED,
                attemptCount = task.attemptCount + 1,
                nextAttemptAt = System.currentTimeMillis(),
                lastError = "Missing cloudId for CLOUD_DELETE"
            )
            return false
        }
        val md5 = task.mediaMd5
        var currentAttempt = task.attemptCount

        val pendingVerify = parseCloudDeleteNotFoundVerify(task.lastError)
        if (pendingVerify != null) {
            val now = System.currentTimeMillis()
            if (now < pendingVerify.notBefore) {
                dao.markError(
                    id = task.id,
                    status = ServerOpStatus.ERROR,
                    attemptCount = currentAttempt,
                    nextAttemptAt = pendingVerify.notBefore,
                    lastError = task.lastError ?: ""
                )
                Log.d(TAG, "Task ${task.id} (CLOUD_DELETE) delayed confirmation not due yet")
                return false
            }
            try {
                if (isCloudFileStatNotFound(cloudId, md5)) {
                    if (pendingVerify.stage == CLOUD_DELETE_NOT_FOUND_VERIFY_FIRST) {
                        scheduleCloudDeleteNotFoundVerify(
                            task = task,
                            attemptCount = currentAttempt,
                            stage = CLOUD_DELETE_NOT_FOUND_VERIFY_FINAL,
                            delayMillis = NOT_FOUND_VERIFY_FINAL_DELAY_MS
                        )
                        Log.d(TAG, "Task ${task.id} (CLOUD_DELETE) confirmed missing after 1 minute, scheduled 1 hour confirmation")
                        return false
                    } else {
                        dao.markSuccess(task.id)
                        Log.d(TAG, "Task ${task.id} (CLOUD_DELETE) confirmed missing after 1 hour")
                        return true
                    }
                }
                Log.d(TAG, "Task ${task.id} (CLOUD_DELETE) file exists on delayed check, retrying delete")
            } catch (e: Exception) {
                currentAttempt++
                markRecoverableFailure(e)
                return markCloudDeleteRetry(task, currentAttempt, e)
            }
        }

        while (true) {
            try {
                deleteCloudFile(cloudId)
                dao.markSuccess(task.id)
                Log.d(TAG, "Task ${task.id} (${task.opType}) succeeded")
                return true
            } catch (deleteError: Exception) {
                markRecoverableFailure(deleteError)

                if (md5.isNotBlank()) {
                    try {
                        if (isCloudFileStatNotFound(cloudId, md5)) {
                            currentAttempt++
                            scheduleCloudDeleteNotFoundVerify(
                                task = task,
                                attemptCount = currentAttempt,
                                stage = CLOUD_DELETE_NOT_FOUND_VERIFY_FIRST,
                                delayMillis = NOT_FOUND_VERIFY_FIRST_DELAY_MS
                            )
                            Log.d(TAG, "Task ${task.id} (CLOUD_DELETE) not found, scheduled 1 minute confirmation")
                            return false
                        }
                    } catch (statError: Exception) {
                        markRecoverableFailure(statError)
                    }
                }

                currentAttempt++
                val shouldRetryImmediately = markCloudDeleteRetry(task, currentAttempt, deleteError)
                if (!shouldRetryImmediately) return false

                val reclaimed = dao.claim(task.id)
                if (reclaimed == 0) return false
            }
        }
    }

    private data class NotFoundVerifyState(
        val stage: String,
        val notBefore: Long
    )

    private suspend fun scheduleCloudDeleteNotFoundVerify(
        task: ServerOpTaskEntity,
        attemptCount: Int,
        stage: String,
        delayMillis: Long
    ) {
        val notBefore = System.currentTimeMillis() + delayMillis
        dao.markError(
            id = task.id,
            status = ServerOpStatus.ERROR,
            attemptCount = attemptCount,
            nextAttemptAt = notBefore,
            lastError = formatCloudDeleteNotFoundVerify(stage, notBefore)
        )
    }

    private fun formatCloudDeleteNotFoundVerify(stage: String, notBefore: Long): String {
        return "$CLOUD_DELETE_NOT_FOUND_VERIFY_PREFIX:$stage:$notBefore"
    }

    private fun parseCloudDeleteNotFoundVerify(lastError: String?): NotFoundVerifyState? {
        val parts = lastError?.split(":") ?: return null
        if (parts.size != 3 || parts[0] != CLOUD_DELETE_NOT_FOUND_VERIFY_PREFIX) return null
        val stage = parts[1]
        if (stage != CLOUD_DELETE_NOT_FOUND_VERIFY_FIRST &&
            stage != CLOUD_DELETE_NOT_FOUND_VERIFY_FINAL
        ) {
            return null
        }
        val notBefore = parts[2].toLongOrNull() ?: return null
        return NotFoundVerifyState(stage, notBefore)
    }

    private suspend fun markCloudDeleteRetry(
        task: ServerOpTaskEntity,
        attemptCount: Int,
        error: Exception
    ): Boolean {
        if (attemptCount >= task.maxAttempts) {
            dao.markError(
                id = task.id,
                status = ServerOpStatus.FAILED,
                attemptCount = attemptCount,
                nextAttemptAt = System.currentTimeMillis(),
                lastError = error.message ?: error::class.java.simpleName
            )
            Log.w(TAG, "Task ${task.id} (${task.opType}) permanently failed after $attemptCount attempts", error)
            return false
        }

        val delay = retryDelayMillis(attemptCount)
        val shouldRetryImmediately = delay == 0L && attemptCount <= IMMEDIATE_RETRY_COUNT
        dao.markError(
            id = task.id,
            status = if (shouldRetryImmediately) ServerOpStatus.PENDING else ServerOpStatus.ERROR,
            attemptCount = attemptCount,
            nextAttemptAt = System.currentTimeMillis() + delay,
            lastError = error.message ?: error::class.java.simpleName
        )

        return if (shouldRetryImmediately) {
            Log.d(TAG, "Task ${task.id} (${task.opType}) immediate retry #$attemptCount")
            true
        } else {
            Log.w(TAG, "Task ${task.id} (${task.opType}) error #$attemptCount, retry in ${delay}ms", error)
            false
        }
    }

    private suspend fun markRecoverableFailure(error: Throwable) {
        val prefsManager = container.prefsManager
        if (NetworkFailure.isDeviceOffline(prefsManager.context)) {
            prefsManager.setNetworkRetryPending(true)
        } else if (NetworkFailure.isServerUnreachable(error)) {
            prefsManager.setServerUnreachable(true)
        }
    }

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
        val fileId = cloudId.toInt()
        val body: Map<String, Any> = mapOf("fileIds" to listOf(fileId))
        val response = container.gatewayApi.GatewayControllerPart3DeleteFiles(body)
        if (!isDeleteSuccessResponse(response, fileId)) {
            val message = response["message"]?.toString()
                ?: response["msg"]?.toString()
                ?: response["error"]?.toString()
                ?: "Delete response did not confirm success: $response"
            throw IllegalStateException(message)
        }
    }

    private fun isDeleteSuccessResponse(response: Map<String, Any>, fileId: Int): Boolean {

        fun Any?.containsFileId(): Boolean {
            val items = this as? Iterable<*> ?: return false
            return items.any { item ->
                when (item) {
                    is Number -> item.toInt() == fileId
                    is String -> item.toIntOrNull() == fileId
                    else -> false
                }
            }
        }

        return response["deleteIds"].containsFileId() ||
            response["deletedIds"].containsFileId()
    }

    // ===== 重试策略 =====

    /**
     * 计算重试延迟。
     * 前 3 次立即重试（返回 0），之后按指数退避。
     */
    private suspend fun isCloudFileStatNotFound(cloudId: Double, md5: String): Boolean {
        if (md5.isBlank()) return false
        return try {
            val response = container.gatewayApi.GatewayControllerPart2StatOneFile(
                cloudId.toInt().toString(),
                md5
            )
            isFileStatNotFoundBody(response)
        } catch (e: HttpException) {
            if (isFileStatNotFoundError(e)) true else throw e
        }
    }

    private fun isFileStatNotFoundBody(response: Map<String, Any>): Boolean {
        val statusCode = (response["statusCode"] as? Number)?.toInt()
            ?: response["statusCode"]?.toString()?.toIntOrNull()
        val msg = response["msg"]?.toString()
        val message = response["message"]?.toString()
        return statusCode == 404 &&
            (msg.equals("Not Found", ignoreCase = true) ||
                message.equals("Not Found", ignoreCase = true))
    }

    private fun isFileStatNotFoundError(error: HttpException): Boolean {
        if (error.code() != 404) return false
        val body = error.response()?.errorBody()?.string() ?: return false
        return try {
            @Suppress("UNCHECKED_CAST")
            val response = gson.fromJson(body, Map::class.java) as? Map<String, Any>
            response != null && isFileStatNotFoundBody(response)
        } catch (_: Exception) {
            false
        }
    }

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
