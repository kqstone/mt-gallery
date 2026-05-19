package com.kqstone.mtphotos.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.kqstone.mtphotos.MTPhotosApp
import com.kqstone.mtphotos.R
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
            val selectedFolders = prefsManager.getBackupFolderSelectionSync().folders
            val backupDestinationId = prefsManager.getBackupDestinationIdSync()

            if (!prefsManager.getBackupEnabledSync()) {
                Log.d(TAG, "Backup not enabled, skipping")
                return@withContext Result.success()
            }

            val resetCount = syncRepo.resetStaleUploading(
                System.currentTimeMillis() - STALE_UPLOAD_TIMEOUT_MS
            )
            if (resetCount > 0) {
                Log.w(TAG, "Reset $resetCount stale uploading records")
            }

            val pendingFiles = syncRepo.getPendingBackupMedia(selectedFolders)
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

            val serverUrl = prefsManager.getServerUrlSync()
            val token = prefsManager.getTokenSync()
            var successCount = 0
            var failCount = 0

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
                        serverUrl = serverUrl,
                        token = token,
                        client = uploadClient,
                        backupDestinationId = backupDestinationId
                    )
                    if (uploadResult != null) {
                        syncRepo.markAsBackedUp(
                            media.id,
                            cloudId = uploadResult.first,
                            cloudMd5 = uploadResult.second
                        )
                        successCount++
                        Log.d(TAG, "Uploaded: ${media.fileName} -> cloudId=${uploadResult.first}")
                    } else {
                        container.database.mediaDao().updateBackupStatus(media.id, BackupStatus.FAILED)
                        failCount++
                        Log.w(TAG, "Upload failed: ${media.fileName}")
                    }
                } catch (e: Exception) {
                    container.database.mediaDao().updateBackupStatus(media.id, BackupStatus.FAILED)
                    failCount++
                    Log.e(TAG, "Upload error: ${media.fileName}", e)
                }
            }

            Log.d(TAG, "Backup complete: $successCount success, $failCount failed")

            if (successCount > 0) {
                try {
                    container.gatewayApi.GatewayControllerPart5ScanAfterUpload()
                    Log.d(TAG, "Triggered server scan after upload")
                } catch (e: Exception) {
                    Log.w(TAG, "scanAfterUpload failed", e)
                }
            }

            if (failCount > 0) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "BackupWorker failed", e)
            Result.retry()
        }
    }

    private suspend fun uploadFile(
        media: MediaEntity,
        serverUrl: String,
        token: String,
        client: okhttp3.OkHttpClient,
        backupDestinationId: Long
    ): Pair<Double, String>? {
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
        val folderName = media.localFolderPath?.let { File(it).name } ?: "__NO_SUB_FOLDER__"

        try {
            val checkBody: Map<String, Any> = buildMap {
                put("fileName", media.fileName)
                put("size", media.fileSize)
                put("deviceName", deviceName)
                put("name_type", "")
                put("duplicate", 0)
                put("dist_id", backupDestinationId)
                put("source_folder_path", folderName)
                if (media.md5.isNotEmpty()) put("md5", media.md5)
                put("ctime", ctimeMs)
                put("v", "1.0")
            }

            val checkResult = container.gatewayApi.GatewayControllerPart4CheckPathForUpload(checkBody)
            Log.d(TAG, "checkPathForUpload result: $checkResult")

            val existingId = (checkResult["id"] as? Double) ?: 0.0
            val abort = checkResult["abort"] as? Boolean ?: false

            if (existingId > 0 || abort) {
                Log.d(TAG, "File already exists on server: ${media.fileName}, id=$existingId")
                return Pair(existingId, media.md5)
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkPathForUpload failed for ${media.fileName}, proceeding with upload", e)
        }

        try {
            val mimeType = media.fileType.ifEmpty { "application/octet-stream" }
            val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", media.fileName, requestFile)
                .addFormDataPart("c_time", ctimeMs)
                .build()

            val encodedFileName = URLEncoder.encode(media.fileName, "UTF-8").replace("+", "%20")
            val mtextra =
                """{"dist_id":$backupDestinationId,"source_folder_path":"$folderName","trigger_thumb_task":"on","duplicate":0}"""
            val encodedMtextra = URLEncoder.encode(mtextra, "UTF-8")

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

            val fileId = (map?.get("id") as? Double) ?: (map?.get("fileId") as? Double) ?: 0.0
            if (fileId <= 0) {
                Log.w(TAG, "Upload returned invalid fileId=$fileId for ${media.fileName}, response: $respBody")
                return null
            }

            Log.d(TAG, "Upload success: ${media.fileName}, cloudId=$fileId")
            return Pair(fileId, media.md5)
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception for ${media.fileName}", e)
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

    private fun createForegroundInfo(
        message: String,
        progress: Int,
        total: Int
    ): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MT Gallery Backup")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
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
