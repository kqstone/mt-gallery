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
import java.io.File
import java.util.concurrent.TimeUnit

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

            // 设置前台通知（部分设备可能不支持，降级处理）
            try {
                setForeground(createForegroundInfo("准备备份...", 0, pendingFiles.size))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set foreground, continuing in background", e)
            }

            val serverUrl = prefsManager.getServerUrlSync()
            val token = prefsManager.getTokenSync()
            var successCount = 0
            var failCount = 0

            // 构建共享的 OkHttpClient（带认证和合理超时）
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
                    // 更新通知
                    try {
                        setForeground(createForegroundInfo(
                            "正在备份 (${index + 1}/${pendingFiles.size}): ${media.fileName}",
                            index + 1,
                            pendingFiles.size
                        ))
                    } catch (_: Exception) { /* 通知更新失败不阻塞备份 */ }

                    // 仅标记为上传中（不改变 syncStatus！）
                    container.database.mediaDao().updateBackupStatus(media.id, BackupStatus.UPLOADING)

                    // 执行上传
                    val uploadResult = uploadFile(media, serverUrl, token, uploadClient)
                    if (uploadResult != null) {
                        // 上传成功 → 标记为已备份 + 已同步
                        syncRepo.markAsBackedUp(media.id, cloudId = uploadResult.first, cloudMd5 = uploadResult.second)
                        successCount++
                        Log.d(TAG, "Uploaded: ${media.fileName} -> cloudId=${uploadResult.first}")
                    } else {
                        // 上传失败 → 标记为失败，保持 syncStatus=LOCAL_ONLY 以便重试
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

    /**
     * 上传单个文件到服务端。
     * @return Pair<cloudId, cloudMd5> 或 null（上传失败）
     */
    private suspend fun uploadFile(
        media: MediaEntity,
        serverUrl: String,
        token: String,
        client: okhttp3.OkHttpClient
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

        // Step 1: 检查文件是否已存在于服务端（通过 checkPathForUpload API）
        try {
            val checkBody: Map<String, Any> = buildMap {
                put("fileName", media.fileName)
                put("size", media.fileSize)
                put("deviceName", deviceName)
                put("name_type", "")
                put("duplicate", 0)
                if (media.md5.isNotEmpty()) put("md5", media.md5)
                put("ctime", media.mtime)
                put("v", "1.0")
            }

            val checkResult = container.gatewayApi.GatewayControllerPart4CheckPathForUpload(checkBody)
            Log.d(TAG, "checkPathForUpload result: $checkResult")

            val existingId = (checkResult["id"] as? Double) ?: 0.0
            val abort = checkResult["abort"] as? Boolean ?: false

            if (existingId > 0 || abort) {
                // 文件已存在于服务端，直接标记为已备份
                Log.d(TAG, "File already exists on server: ${media.fileName}, id=$existingId")
                return Pair(existingId, media.md5)
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkPathForUpload failed for ${media.fileName}, proceeding with upload", e)
        }

        // Step 2: 通过 Multipart 上传
        try {
            val mimeType = media.fileType.ifEmpty { "application/octet-stream" }
            val requestFile = file.asRequestBody(mimeType.toMediaTypeOrNull())

            // 将 ISO 8601 时间转为 Unix 时间戳（毫秒）
            val ctimeMs = try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                sdf.parse(media.mtime)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }.toString()

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", media.fileName, requestFile)
                .addFormDataPart("c_time", ctimeMs)
                .build()

            // URL 编码文件名
            val encodedFileName = java.net.URLEncoder.encode(media.fileName, "UTF-8")
                .replace("+", "%20")

            // mtextra 参数
            val folderName = media.localFolderPath?.let { java.io.File(it).name } ?: "__NO_SUB_FOLDER__"
            val mtextra = """{"dist_id":1,"source_folder_path":"$folderName","trigger_thumb_task":"on","duplicate":1}"""
            val encodedMtextra = java.net.URLEncoder.encode(mtextra, "UTF-8")

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

            if (response.isSuccessful) {
                // 解析返回的 file ID
                val gson = com.google.gson.Gson()
                @Suppress("UNCHECKED_CAST")
                val map = gson.fromJson(respBody, Map::class.java) as? Map<String, Any>

                // 检查服务端是否返回了错误消息
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
            } else {
                Log.w(TAG, "Upload HTTP error: ${response.code} ${response.message} - body: $respBody")
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
