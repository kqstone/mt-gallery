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
import com.kqstone.mtphotos.MTPhotosApp
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.local.db.BackupStatus
import com.kqstone.mtphotos.data.local.db.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

private const val TAG = "BackupWorker"
private const val NOTIFICATION_CHANNEL_ID = "backup_channel"
private const val NOTIFICATION_ID = 1001

/**
 * 后台备份 Worker。
 * 扫描本地待备份文件，通过 Multipart 上传到服务端。
 */
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

            // 检查备份是否启用
            if (!prefsManager.getBackupEnabledSync()) {
                Log.d(TAG, "Backup not enabled, skipping")
                return@withContext Result.success()
            }

            // 获取待备份文件
            val pendingFiles = syncRepo.getPendingBackupMedia()
            if (pendingFiles.isEmpty()) {
                Log.d(TAG, "No files pending backup")
                return@withContext Result.success()
            }

            Log.d(TAG, "Found ${pendingFiles.size} files to backup")

            // 设置前台通知
            setForeground(createForegroundInfo("准备备份...", 0, pendingFiles.size))

            val deviceName = prefsManager.getDeviceNameSync()
            var successCount = 0
            var failCount = 0

            for ((index, media) in pendingFiles.withIndex()) {
                if (isStopped) {
                    Log.d(TAG, "Worker stopped, uploaded $successCount files")
                    break
                }

                try {
                    // 更新通知
                    setForeground(createForegroundInfo(
                        "正在备份: ${media.fileName}",
                        index + 1,
                        pendingFiles.size
                    ))

                    // 标记上传中
                    syncRepo.markAsBackedUp(media.id, cloudId = 0.0, cloudMd5 = "")
                    container.database.mediaDao().updateBackupStatus(media.id, BackupStatus.UPLOADING)

                    // 执行上传
                    val uploadResult = uploadFile(media, deviceName)
                    if (uploadResult != null) {
                        // 上传成功
                        syncRepo.markAsBackedUp(media.id, cloudId = uploadResult.first, cloudMd5 = uploadResult.second)
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

            // 触发服务器扫描新上传的文件
            try {
                container.gatewayApi.GatewayControllerPart5ScanAfterUpload()
            } catch (e: Exception) {
                Log.w(TAG, "scanAfterUpload failed", e)
            }

            if (failCount > 0) Result.retry() else Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "BackupWorker failed", e)
            Result.retry()
        }
    }

    /**
     * 上传单个文件到服务端。
     * @return Pair<cloudId, cloudMd5> 或 null（上传失败）
     */
    private suspend fun uploadFile(media: MediaEntity, deviceName: String): Pair<Double, String>? {
        val app = applicationContext as MTPhotosApp
        val container = app.container

        val localPath = media.localPath ?: return null
        val file = File(localPath)
        if (!file.exists() || !file.canRead()) {
            Log.w(TAG, "File not accessible: $localPath")
            return null
        }

        // Step 1: 检查文件是否已存在于服务端
        val folderName = media.localFolderPath?.let { File(it).name } ?: "Camera"
        val checkBody: Map<String, Any> = buildMap {
            put("fileName", media.fileName)
            put("size", media.fileSize)
            put("deviceName", deviceName)
            put("name_type", "")
            put("duplicate", 0) // 跳过重复
            if (media.md5.isNotEmpty()) put("md5", media.md5)
            put("ctime", media.mtime)
            put("v", "1.0")
        }

        try {
            val checkResult = container.gatewayApi.GatewayControllerPart4CheckPathForUpload(checkBody)
            val existingId = (checkResult["id"] as? Double) ?: 0.0
            val abort = checkResult["abort"] as? Boolean ?: false

            if (existingId > 0 || abort) {
                // 文件已存在于服务端，直接标记为已备份
                Log.d(TAG, "File already exists on server: ${media.fileName}, id=$existingId")
                return Pair(existingId, media.md5)
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkPathForUpload failed, proceeding with upload", e)
        }

        // Step 2: 通过 Multipart 上传
        try {
            val mimeType = media.fileType.ifEmpty { "application/octet-stream" }
            val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", media.fileName, requestFile)
                .addFormDataPart("deviceName", deviceName)
                .addFormDataPart("name_type", "")
                .addFormDataPart("ctime", media.mtime)
                .build()

            // 使用 OkHttp 直接上传（Retrofit 的上传签名不完整）
            val serverUrl = container.prefsManager.getServerUrlSync()
            val token = container.prefsManager.getTokenSync()

            val request = okhttp3.Request.Builder()
                .url("$serverUrl/gateway/upload")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()

            val client = container.retrofitClient.getRetrofit().callFactory() as okhttp3.OkHttpClient
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val respBody = response.body?.string() ?: ""
                Log.d(TAG, "Upload response: $respBody")
                // 解析返回的 file ID
                val gson = com.google.gson.Gson()
                val map = gson.fromJson(respBody, Map::class.java) as? Map<String, Any>
                val fileId = (map?.get("id") as? Double) ?: (map?.get("fileId") as? Double) ?: 0.0
                return Pair(fileId, media.md5)
            } else {
                Log.w(TAG, "Upload failed: ${response.code} ${response.message}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception for ${media.fileName}", e)
            return null
        }
    }

    private fun createForegroundInfo(
        message: String,
        progress: Int,
        total: Int
    ): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MT Gallery 备份")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(total, progress, total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "照片备份",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示照片备份进度"
            }
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
