package com.kqstone.mtphotos.data.repository

import com.kqstone.mtphotos.AppContainer

data class TimelineMonth(
    val yearMonth: String,
    val count: Int
)

data class PhotoItem(
    val id: Double,
    val md5: String,
    val fileName: String,
    val fileType: String,
    val mtime: String,
    val width: Double,
    val height: Double
)

class GalleryRepository(private val container: AppContainer) {

    private val prefsManager get() = container.prefsManager
    private val authRepository get() = container.authRepository

    suspend fun getTimeline(): Result<List<TimelineMonth>> {
        return try {
            val response = container.gatewayApi.GatewayControllerGetTimelineData()
            val months = response.mapNotNull { item ->
                val ym = item["yearMonth"] as? String ?: item["year_month"] as? String
                val count = (item["count"] as? Double)?.toInt() ?: (item["count"] as? Int)
                if (ym != null && count != null) TimelineMonth(ym, count) else null
            }
            Result.success(months)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMonthFiles(yearMonth: String): Result<List<PhotoItem>> {
        return try {
            val body = mapOf("yearMonth" to yearMonth)
            val response = container.gatewayApi.GatewayControllerGetTimelineMonthData(body)
            val files = response.mapNotNull { item ->
                val id = item["id"] as? Double ?: return@mapNotNull null
                val md5 = item["MD5"] as? String ?: item["md5"] as? String ?: return@mapNotNull null
                val fileName = item["fileName"] as? String ?: item["file_name"] as? String ?: ""
                val fileType = item["fileType"] as? String ?: item["file_type"] as? String ?: ""
                val mtime = item["mtime"] as? String ?: ""
                val width = item["width"] as? Double ?: 0.0
                val height = item["height"] as? Double ?: 0.0
                PhotoItem(id, md5, fileName, fileType, mtime, width, height)
            }
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getThumbUrl(md5: String, fileId: Double): String {
        val serverUrl = prefsManager.getServerUrlSync()
        val authCode = authRepository.getAuthCode()
        return "$serverUrl/gateway/thumbs/$md5?auth_code=$authCode&id=$fileId"
    }

    fun getFullImageUrl(id: Double, md5: String): String {
        val serverUrl = prefsManager.getServerUrlSync()
        val authCode = authRepository.getAuthCode()
        return "$serverUrl/gateway/file/$id/$md5?auth_code=$authCode"
    }

    fun getServerUrl(): String = prefsManager.getServerUrlSync()
}
