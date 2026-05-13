package com.kqstone.mtphotos.data.repository

import com.kqstone.mtphotos.AppContainer
import java.net.URLEncoder

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
            val list = response["list"] as? List<*> ?: emptyList<Any>()
            val months = list.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val year = (map["year"] as? Double)?.toInt() ?: return@mapNotNull null
                val month = (map["month"] as? Double)?.toInt() ?: return@mapNotNull null
                val count = (map["count"] as? Double)?.toInt() ?: return@mapNotNull null
                val ym = "$year-${month.toString().padStart(2, '0')}"
                TimelineMonth(ym, count)
            }
            Result.success(months)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMonthFiles(yearMonth: String): Result<List<PhotoItem>> {
        return try {
            // Get timeline data which includes photo details in "extra"
            val response = container.gatewayApi.GatewayControllerGetTimelineData()
            val extra = response["extra"] as? Map<*, *> ?: return Result.success(emptyList())

            // Find the matching month key in extra (keys are ISO date strings like "2026-04-30T16:00:00.000Z")
            val photos = mutableListOf<PhotoItem>()
            for ((key, value) in extra) {
                val monthData = value as? Map<*, *> ?: continue
                val result = monthData["result"] as? List<*> ?: continue
                for (dayEntry in result) {
                    val dayMap = dayEntry as? Map<*, *> ?: continue
                    val day = dayMap["day"] as? String ?: continue
                    // Filter by yearMonth
                    if (!day.startsWith(yearMonth)) continue
                    val photoList = dayMap["list"] as? List<*> ?: continue
                    for (photo in photoList) {
                        val photoMap = photo as? Map<*, *> ?: continue
                        val id = photoMap["id"] as? Double ?: continue
                        val md5 = photoMap["MD5"] as? String ?: photoMap["md5"] as? String ?: continue
                        val fileType = photoMap["fileType"] as? String ?: photoMap["file_type"] as? String ?: ""
                        val width = photoMap["width"] as? Double ?: 0.0
                        val height = photoMap["height"] as? Double ?: 0.0
                        photos.add(PhotoItem(id, md5, "", fileType, day, width, height))
                    }
                }
            }
            Result.success(photos)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getThumbUrl(md5: String, fileId: Double): String {
        val serverUrl = prefsManager.getServerUrlSync()
        val authCode = URLEncoder.encode(authRepository.getAuthCode(), "UTF-8")
        return "$serverUrl/gateway/file/$fileId/$md5?auth_code=$authCode"
    }

    fun getFullImageUrl(id: Double, md5: String): String {
        val serverUrl = prefsManager.getServerUrlSync()
        val authCode = URLEncoder.encode(authRepository.getAuthCode(), "UTF-8")
        return "$serverUrl/gateway/file/$id/$md5?auth_code=$authCode"
    }

    suspend fun deleteFiles(ids: List<Double>): Result<Unit> {
        return try {
            val body = mapOf("ids" to ids)
            container.gatewayApi.GatewayControllerPart3DeleteFiles(body)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getServerUrl(): String = prefsManager.getServerUrlSync()
}
