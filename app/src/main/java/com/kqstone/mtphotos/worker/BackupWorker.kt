package com.kqstone.mtphotos.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.kqstone.mtphotos.MTPhotosApp
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.local.BackupDestinationDefaults
import com.kqstone.mtphotos.data.local.db.BackupStatus
import com.kqstone.mtphotos.data.local.db.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "BackupWorker"
private const val NOTIFICATION_CHANNEL_ID = "backup_channel"
private const val NOTIFICATION_ID = 1001
private const val STALE_UPLOAD_TIMEOUT_MS = 30 * 60 * 1000L
private const val MAX_RETRY_ATTEMPTS = 5

private data class UploadResult(
    val cloudId: Double,
    val cloudMd5: String,
    val fromExistingServerRecord: Boolean
)

class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val app = applicationContext as MTPhotosApp
            val container = app.container
            val syncRepo = container.syncRepository
            val prefsManager = container.prefsManager
            val selectedFolders = prefsManager.getBackupFolderSelectionSync().effectiveFolders

            if (!prefsManager.getBackupEnabledSync()) {
                Log.d(TAG, "Backup not enabled, skipping")
                return@withContext Result.success()
            }

            val configuredDestination = prefsManager.isBackupDestinationConfiguredSync()
            val deviceName = prefsManager.getDeviceNameSync().trim().trim('/').ifEmpty { "Android" }
            var sourceFolderDevicePrefix: String? = null
            val backupDestinationId = if (configuredDestination) {
                val savedDestinationId = prefsManager.getBackupDestinationIdSync()
                val expectedDefaultPath = BackupDestinationDefaults.path(
                    prefsManager.getUsernameSync(),
                    deviceName
                )

                if (prefsManager.getBackupDestinationPathSync() == expectedDefaultPath) {
                    val userRoot = container.backupDestinationRepository.enableFileBackup()
                    if (userRoot == null) {
                        Log.w(TAG, "Default backup user root is unavailable, skipping upload")
                        return@withContext Result.retry()
                    }
                    sourceFolderDevicePrefix = deviceName
                    if (userRoot.id != savedDestinationId) {
                        prefsManager.saveBackupDestination(userRoot.id, deviceName, expectedDefaultPath)
                    }
                    userRoot.id
                } else {
                    savedDestinationId
                }
            } else {
                val destination = container.backupDestinationRepository.ensureDefaultDeviceDestination(
                    deviceName
                )
                if (destination == null) {
                    Log.w(TAG, "Default backup destination is unavailable, skipping upload")
                    return@withContext Result.retry()
                }
                prefsManager.saveBackupDestination(destination.id, destination.name, destination.path)
                sourceFolderDevicePrefix = deviceName
                destination.id
            }

            val resetCount = syncRepo.resetStaleUploading(
                System.currentTimeMillis() - STALE_UPLOAD_TIMEOUT_MS
            )
            if (resetCount > 0) {
                Log.w(TAG, "Reset $resetCount stale uploading records")
            }

            val pendingFiles = syncRepo.getPendingBackupMedia(selectedFolders)
                .filter { it.md5.isNotEmpty() }
            if (pendingFiles.isEmpty()) {
                Log.d(TAG, "No files pending backup")
                return@withContext Result.success()
            }

            Log.d(TAG, "Found ${pendingFiles.size} files to backup")

            try {
                setForeground(createForegroundInfo("Preparing backup...", 0, pendingFiles.size))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set foreground, continuing in background", e)
            }

            var successCount = 0
            var failCount = 0
            val existingServerRecordSuccessIds = mutableListOf<Long>()

            val uploadClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()

            for ((index, media) in pendingFiles.withIndex()) {
                if (isStopped) {
                    Log.d(TAG, "Worker stopped, uploaded $successCount files")
                    break
                }

                try {
                    try {
                        setForeground(
                            createForegroundInfo(
                                "Uploading (${index + 1}/${pendingFiles.size}): ${media.fileName}",
                                index + 1,
                                pendingFiles.size
                            )
                        )
                    } catch (_: Exception) {
                    }

                    if (!syncRepo.claimPendingUpload(media.id)) {
                        Log.d(TAG, "Skip ${media.fileName}, already claimed by another worker")
                        continue
                    }

                    val uploadResult = uploadFile(
                        media = media,
                        client = uploadClient,
                        backupDestinationId = backupDestinationId,
                        sourceFolderDevicePrefix = sourceFolderDevicePrefix
                    )
                    if (uploadResult != null) {
                        syncRepo.markAsBackedUp(
                            media.id,
                            cloudId = uploadResult.cloudId,
                            cloudMd5 = uploadResult.cloudMd5
                        )
                        container.database.mediaDao().findById(media.id)?.let { latest ->
                            if (latest.isFavorite) {
                                container.serverOpTaskRepository.enqueueFavorite(
                                    cloudId = uploadResult.cloudId,
                                    dbId = latest.id,
                                    isFavorite = true,
                                    fileName = latest.fileName,
                                    md5 = latest.md5
                                )
                            }
                        }
                        if (uploadResult.fromExistingServerRecord) {
                            existingServerRecordSuccessIds += media.id
                        }
                        successCount++
                        Log.d(TAG, "Uploaded: ${media.fileName} -> cloudId=${uploadResult.cloudId}")
                        // 记录上传成功日志
                        container.serverOpTaskRepository.recordBackupResult(
                            fileName = media.fileName,
                            md5 = media.md5,
                            cloudId = uploadResult.cloudId,
                            success = true
                        )
                    } else {
                        container.database.mediaDao().updateBackupStatus(media.id, BackupStatus.FAILED)
                        failCount++
                        Log.w(TAG, "Upload failed: ${media.fileName}")
                        // 记录上传失败日志
                        container.serverOpTaskRepository.recordBackupResult(
                            fileName = media.fileName,
                            md5 = media.md5,
                            cloudId = null,
                            success = false,
                            error = "Upload returned null"
                        )
                        if (prefsManager.getAuthRequiredSync() || prefsManager.getNetworkRetryPendingSync()) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    prefsManager.markRecoverableFailure(e)
                    container.database.mediaDao().updateBackupStatus(media.id, BackupStatus.FAILED)
                    failCount++
                    Log.e(TAG, "Upload error: ${media.fileName}", e)
                    // 记录上传异常日志
                    container.serverOpTaskRepository.recordBackupResult(
                        fileName = media.fileName,
                        md5 = media.md5,
                        cloudId = null,
                        success = false,
                        error = e.message ?: e::class.java.simpleName
                    )
                }
            }

            Log.d(TAG, "Backup complete: $successCount success, $failCount failed")

            if (successCount > 0) {
                try {
                    container.gatewayApi.GatewayControllerPart5ScanAfterUpload(
                        mapOf("folderId" to backupDestinationId.toInt())
                    )
                    Log.d(TAG, "Triggered server scan after upload")
                } catch (e: Exception) {
                    Log.w(TAG, "scanAfterUpload failed", e)
                }
            }

            if (existingServerRecordSuccessIds.isNotEmpty()) {
                try {
                    val reverted = syncRepo.retryBackupsMissingFromCloud(existingServerRecordSuccessIds)
                    if (reverted > 0) {
                        successCount = (successCount - reverted).coerceAtLeast(0)
                        failCount += reverted
                        Log.w(TAG, "Reverted $reverted existing-record uploads after cloud verification")
                    }
                } catch (e: Exception) {
                    val reverted = syncRepo.markBackupsForRetry(existingServerRecordSuccessIds)
                    successCount = (successCount - reverted).coerceAtLeast(0)
                    failCount += reverted
                    Log.w(TAG, "Cloud verification failed; reverted $reverted existing-record uploads", e)
                }
            }

            if (failCount > 0 && runAttemptCount + 1 < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                if (failCount > 0) {
                    Log.w(TAG, "Backup retry limit reached; failed files remain pending for later runs")
                }
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "BackupWorker failed", e)
            (applicationContext as? MTPhotosApp)?.container?.prefsManager?.markRecoverableFailure(e)
            if (runAttemptCount + 1 < MAX_RETRY_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    private suspend fun uploadFile(
        media: MediaEntity,
        client: okhttp3.OkHttpClient,
        backupDestinationId: Long,
        sourceFolderDevicePrefix: String?
    ): UploadResult? {
        val app = applicationContext as MTPhotosApp
        val container = app.container

        val localPath = media.localPath ?: return null
        val file = File(localPath)
        if (!file.exists() || !file.canRead()) {
            Log.w(TAG, "File not accessible: $localPath")
            return null
        }

        val deviceName = container.prefsManager.getDeviceNameSync()
        val ctimeMs = resolveUploadCtime(media.mtime)
        val sourceFolderPath = resolveSourceFolderPath(
            localFolderName = media.localFolderPath?.let { File(it).name },
            devicePrefix = sourceFolderDevicePrefix
        )

        try {
            val checkBody: Map<String, Any> = buildMap {
                put("fileName", media.fileName)
                put("size", media.fileSize)
                put("deviceName", deviceName)
                put("trigger_thumb_task", "on")
                put("dist_id", backupDestinationId)
                put("source_folder_path", sourceFolderPath)
                if (media.md5.isNotEmpty()) put("md5", media.md5)
                put("ctime", ctimeMs)
            }

            val checkResult = container.gatewayApi.GatewayControllerPart4CheckPathForUpload(checkBody)
            Log.d(TAG, "checkPathForUpload result: $checkResult")

            val existingId = (checkResult["id"] as? Number)?.toDouble() ?: 0.0
            val abort = checkResult["abort"] as? Boolean ?: false

            if (existingId > 0) {
                Log.d(TAG, "File already exists on server: ${media.fileName}, id=$existingId")
                return UploadResult(
                    cloudId = existingId,
                    cloudMd5 = media.md5,
                    fromExistingServerRecord = true
                )
            }

            if (abort) {
                Log.w(TAG, "checkPathForUpload aborted without file id for ${media.fileName}")
                return null
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkPathForUpload failed for ${media.fileName}, proceeding with upload", e)
        }

        try {
            val encodedFileName = URLEncoder.encode(media.fileName, "UTF-8").replace("+", "%20")
            val mtextra = Gson().toJson(
                mapOf(
                    "dist_id" to backupDestinationId,
                    "source_folder_path" to sourceFolderPath,
                    "trigger_thumb_task" to "on",
                    "duplicate" to 1
                )
            )
            val encodedMtextra = URLEncoder.encode(mtextra, "UTF-8")

            var authRetryUsed = false
            while (true) {
                val serverUrl = container.prefsManager.getServerUrlSync()
                val token = container.prefsManager.getTokenSync()
                val mimeType = media.fileType.ifEmpty { "application/octet-stream" }
                val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", media.fileName, requestFile)
                    .addFormDataPart("c_time", ctimeMs)
                    .build()

                val request = okhttp3.Request.Builder()
                    .url("$serverUrl/gateway/upload")
                    .addHeader("jwt", token)
                    .addHeader("filename", encodedFileName)
                    .addHeader("devicename", deviceName)
                    .addHeader("ctime", ctimeMs)
                    .addHeader("mtextra", encodedMtextra)
                    .post(body)
                    .build()

                Log.d(TAG, "Uploading ${media.fileName} (${media.fileSize} bytes) to $serverUrl/gateway/upload")

                val response = client.newCall(request).execute()
                val respBody = response.body?.string() ?: ""

                Log.d(TAG, "Upload response [${response.code}]: $respBody")

                if (response.code in setOf(401, 403) && !authRetryUsed) {
                    authRetryUsed = true
                    if (container.authRecovery.recover()) {
                        Log.d(TAG, "Retrying upload after auth recovery: ${media.fileName}")
                        continue
                    }
                    return null
                }

                if (!response.isSuccessful) {
                    Log.w(TAG, "Upload HTTP error: ${response.code} ${response.message} - body: $respBody")
                    return null
                }

                val gson = Gson()
                @Suppress("UNCHECKED_CAST")
                val map = gson.fromJson(respBody, Map::class.java) as? Map<String, Any>

                val msg = map?.get("msg") as? String
                if (!msg.isNullOrEmpty()) {
                    Log.w(TAG, "Upload server error: $msg for ${media.fileName}")
                    return null
                }

                val fileId = (map?.get("id") as? Number)?.toDouble()
                    ?: (map?.get("fileId") as? Number)?.toDouble()
                    ?: 0.0
                if (fileId <= 0) {
                    Log.w(TAG, "Upload returned invalid fileId=$fileId for ${media.fileName}, response: $respBody")
                    return null
                }

                Log.d(TAG, "Upload success: ${media.fileName}, cloudId=$fileId")
                return UploadResult(
                    cloudId = fileId,
                    cloudMd5 = media.md5,
                    fromExistingServerRecord = false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception for ${media.fileName}", e)
            container.prefsManager.markRecoverableFailure(e)
            return null
        }
    }

    private fun resolveUploadCtime(mtime: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            (sdf.parse(mtime)?.time ?: System.currentTimeMillis()).toString()
        } catch (_: Exception) {
            System.currentTimeMillis().toString()
        }
    }

    private fun resolveSourceFolderPath(
        localFolderName: String?,
        devicePrefix: String?
    ): String {
        val cleanLocalFolder = localFolderName?.trim()?.trim('/').orEmpty()
        val cleanDevicePrefix = devicePrefix?.trim()?.trim('/').orEmpty()
        return when {
            cleanDevicePrefix.isNotEmpty() && cleanLocalFolder.isNotEmpty() ->
                "$cleanDevicePrefix/$cleanLocalFolder"
            cleanDevicePrefix.isNotEmpty() -> cleanDevicePrefix
            cleanLocalFolder.isNotEmpty() -> cleanLocalFolder
            else -> "__NO_SUB_FOLDER__"
        }
    }

    private fun createForegroundInfo(
        message: String,
        progress: Int,
        total: Int
    ): ForegroundInfo {
        createNotificationChannel()

        val intent = Intent(applicationContext, com.kqstone.mtphotos.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_backup_settings", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MT Gallery Backup")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setProgress(total, progress, total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Photo Backup",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows photo backup progress"
            }
            val manager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
