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

data class FolderItem(
    val id: String,
    val name: String,
    val coverMd5: String,
    val coverFileId: Double,
    val fileCount: Int
)

data class FolderDetailData(
    val name: String,
    val subfolders: List<FolderItem>
)

data class PersonItem(
    val id: String,
    val name: String,
    val coverMd5: String,
    val coverFileId: Double,
    val count: Int
)

data class SceneItem(
    val id: String,
    val name: String,
    val coverMd5: String,
    val coverFileId: Double,
    val count: Int,
    val cid: String?
)

data class LocationItem(
    val city: String,
    val count: Int
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

    fun getThumbUrlByMd5(md5: String): String {
        val serverUrl = prefsManager.getServerUrlSync()
        val authCode = URLEncoder.encode(authRepository.getAuthCode(), "UTF-8")
        return "$serverUrl/gateway/s260/$md5?auth_code=$authCode"
    }

    fun getVideoThumbUrl(md5: String): String {
        val serverUrl = prefsManager.getServerUrlSync()
        val authCode = URLEncoder.encode(authRepository.getAuthCode(), "UTF-8")
        return "$serverUrl/gateway/h220/$md5?auth_code=$authCode"
    }

    fun getFullImageUrl(id: Double, md5: String): String {
        val serverUrl = prefsManager.getServerUrlSync()
        val authCode = URLEncoder.encode(authRepository.getAuthCode(), "UTF-8")
        return "$serverUrl/gateway/file/$id/$md5?auth_code=$authCode"
    }

    suspend fun deleteFiles(ids: List<Double>): Result<Unit> {
        return try {
            val syncRepo = container.syncRepository

            // 查找 Room 中的实体（按 cloudId 和 dbId 查询，覆盖所有同步状态）
            val entities = syncRepo.findMediaEntitiesByIds(ids)

            // 仅将有 cloudId 的文件发送到云端 API 删除
            val cloudIds = entities.mapNotNull { it.cloudId }.filter { it > 0 }
            if (cloudIds.isNotEmpty()) {
                val body: Map<String, Any> = mapOf("fileIds" to cloudIds.map { it.toInt() })
                val response = container.gatewayApi.GatewayControllerPart3DeleteFiles(body)
                val code = response["code"] as? String
                if (code != null) {
                    return Result.failure(Exception(code))
                }
            }

            // 清理本地文件和 Room 记录
            val deleteMode = container.prefsManager.getDeleteModeSync()
            val useDirectDelete = deleteMode == "direct"
            syncRepo.deleteLocalMediaFiles(entities, useDirectDelete)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getServerUrl(): String = prefsManager.getServerUrlSync()

    // Folder methods

    suspend fun getRootFolders(): Result<List<FolderItem>> {
        return try {
            val response = container.gatewayApi.GatewayControllerPart5FolderTopList()
            android.util.Log.d("FolderRepo", "folderTopList keys=${response.keys}")
            val list = response["folderList"] as? List<*>
                ?: response["list"] as? List<*>
                ?: response["folders"] as? List<*>
                ?: emptyList<Any>()
            if (list.isNotEmpty()) android.util.Log.d("FolderRepo", "folder first=${list.first()}")
            val folders = list.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val id = (map["id"] as? Double)?.toString() ?: return@mapNotNull null
                val name = map["name"] as? String ?: return@mapNotNull null
                val cover = map["cover"] as? String ?: ""
                val subFileNum = (map["subFileNum"] as? Double)?.toInt() ?: 0
                // cover may be comma-separated MD5s; take first one
                val coverMd5 = cover.substringBefore(",").trim()
                val coverFileId = (map["coverFileId"] as? Double) ?: (map["id"] as? Double) ?: 0.0
                FolderItem(id, name, coverMd5, coverFileId, subFileNum)
            }
            Result.success(folders)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFolderDetail(folderId: String): Result<FolderDetailData> {
        return try {
            val response = container.gatewayApi.GatewayControllerPart5FolderViewDetail(folderId)
            val name = response["name"] as? String
                ?: response["path"] as? String
                ?: ""
            val subList = response["subFolders"] as? List<*>
                ?: response["folderList"] as? List<*>
                ?: response["children"] as? List<*>
                ?: emptyList<Any>()
            val subfolders = subList.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val id = (map["id"] as? Double)?.toString() ?: return@mapNotNull null
                val subName = map["name"] as? String ?: return@mapNotNull null
                val cover = map["cover"] as? String ?: ""
                val subFileNum = (map["subFileNum"] as? Double)?.toInt() ?: 0
                val coverMd5 = cover.substringBefore(",").trim()
                val coverFileId = (map["coverFileId"] as? Double) ?: (map["id"] as? Double) ?: 0.0
                FolderItem(id, subName, coverMd5, coverFileId, subFileNum)
            }
            Result.success(FolderDetailData(name, subfolders))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFolderFiles(folderId: String): Result<List<PhotoItem>> {
        return try {
            val response = container.gatewayApi.GatewayControllerPart5FolderFileInTimeline(folderId)
            val photos = mutableListOf<PhotoItem>()

            // Try timeline-style "extra" format
            val extra = response["extra"] as? Map<*, *>
            if (extra != null) {
                for ((_, value) in extra) {
                    val monthData = value as? Map<*, *> ?: continue
                    val result = monthData["result"] as? List<*> ?: continue
                    for (dayEntry in result) {
                        val dayMap = dayEntry as? Map<*, *> ?: continue
                        val day = dayMap["day"] as? String ?: continue
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
            }

            // Try "result" timeline-style format (list of day groups with "day" and "list")
            if (photos.isEmpty()) {
                val resultList = response["result"] as? List<*>
                if (resultList != null) {
                    for (dayEntry in resultList) {
                        val dayMap = dayEntry as? Map<*, *> ?: continue
                        val day = dayMap["day"] as? String ?: continue
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
            }

            // Try flat list format
            if (photos.isEmpty()) {
                val list = response["fileList"] as? List<*>
                    ?: response["list"] as? List<*>
                    ?: response["files"] as? List<*>
                    ?: emptyList<Any>()
                for (item in list) {
                    val map = item as? Map<*, *> ?: continue
                    val id = map["id"] as? Double ?: continue
                    val md5 = map["MD5"] as? String ?: map["md5"] as? String ?: continue
                    val fileType = map["fileType"] as? String ?: map["file_type"] as? String ?: ""
                    val mtime = map["mtime"] as? String ?: ""
                    val width = map["width"] as? Double ?: 0.0
                    val height = map["height"] as? Double ?: 0.0
                    photos.add(PhotoItem(id, md5, "", fileType, mtime, width, height))
                }
            }

            Result.success(photos)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Discovery methods

    suspend fun getPeopleList(): Result<List<PersonItem>> {
        return try {
            val list = container.gatewayApi.GatewayControllerPart3PeopleList("all", "explore")
            android.util.Log.d("DiscRepo", "peopleList size=${list.size}")
            if (list.isNotEmpty()) android.util.Log.d("DiscRepo", "peopleList first=${list.first()}")
            // Collect all cover file IDs and batch-fetch MD5s
            val coverFileIds = list.mapNotNull { (it["cover"] as? Double) }.filter { it > 0 }
            android.util.Log.d("DiscRepo", "coverFileIds=$coverFileIds")
            val md5Map = if (coverFileIds.isNotEmpty()) getFileMd5ByIds(coverFileIds) else emptyMap()
            android.util.Log.d("DiscRepo", "md5Map=$md5Map")
            val people = list.mapNotNull { item ->
                val id = (item["id"] as? Double)?.toInt()?.toString() ?: return@mapNotNull null
                val name = item["name"] as? String ?: "未知"
                val coverFileId = item["cover"] as? Double ?: 0.0
                val count = (item["fileNums"] as? Double)?.toInt() ?: (item["count"] as? Double)?.toInt() ?: 0
                val coverMd5 = md5Map[coverFileId] ?: ""
                android.util.Log.d("DiscRepo", "person id=$id name=$name coverFileId=$coverFileId coverMd5=$coverMd5")
                PersonItem(id, name, coverMd5, coverFileId, count)
            }
            Result.success(people)
        } catch (e: Exception) {
            android.util.Log.e("DiscRepo", "getPeopleList failed", e)
            Result.failure(e)
        }
    }

    suspend fun getPeopleFiles(peopleId: String): Result<List<PhotoItem>> {
        return try {
            android.util.Log.d("DiscRepo", "getPeopleFiles peopleId=$peopleId")
            val response = container.gatewayApi.GatewayControllerPart3PeopleFileListV2(peopleId, "all")
            android.util.Log.d("DiscRepo", "peopleFiles keys=${response.keys}")
            val photos = mutableListOf<PhotoItem>()

            // Try timeline-style "result" format: {result: [{day, list: [{id, MD5, ...}]}]}
            val resultList = response["result"] as? List<*>
            if (resultList != null) {
                for (dayEntry in resultList) {
                    val dayMap = dayEntry as? Map<*, *> ?: continue
                    val day = dayMap["day"] as? String ?: ""
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

            // Try flat list format
            if (photos.isEmpty()) {
                val list = response["list"] as? List<*>
                    ?: response["fileList"] as? List<*>
                    ?: response["files"] as? List<*>
                    ?: emptyList<Any>()
                for (item in list) {
                    val map = item as? Map<*, *> ?: continue
                    val id = map["id"] as? Double ?: continue
                    val md5 = map["MD5"] as? String ?: map["md5"] as? String ?: continue
                    val fileType = map["fileType"] as? String ?: map["file_type"] as? String ?: ""
                    val mtime = map["mtime"] as? String ?: ""
                    val width = map["width"] as? Double ?: 0.0
                    val height = map["height"] as? Double ?: 0.0
                    photos.add(PhotoItem(id, md5, "", fileType, mtime, width, height))
                }
            }

            android.util.Log.d("DiscRepo", "peopleFiles photos=${photos.size}")
            Result.success(photos)
        } catch (e: Exception) {
            android.util.Log.e("DiscRepo", "getPeopleFiles failed", e)
            Result.failure(e)
        }
    }

    suspend fun getFileMd5ByIds(fileIds: List<Double>): Map<Double, String> {
        return try {
            val intIds = fileIds.map { it.toInt() }
            android.util.Log.d("DiscRepo", "getFileMd5ByIds ids=$intIds")
            val body: Map<String, Any> = mapOf("ids" to intIds)
            val response = container.gatewayApi.GatewayControllerPart4GetFileInIds(body)
            android.util.Log.d("DiscRepo", "fileInIds keys=${response.keys}")
            val result = mutableMapOf<Double, String>()

            // Try timeline-style "result" format: {result: [{day, list: [{id, MD5, ...}]}]}
            val resultList = response["result"] as? List<*>
            if (resultList != null) {
                for (dayEntry in resultList) {
                    val dayMap = dayEntry as? Map<*, *> ?: continue
                    val photoList = dayMap["list"] as? List<*> ?: continue
                    for (photo in photoList) {
                        val map = photo as? Map<*, *> ?: continue
                        val id = map["id"] as? Double ?: continue
                        val md5 = map["MD5"] as? String ?: map["md5"] as? String ?: continue
                        result[id] = md5
                    }
                }
            }

            // Try flat list format
            if (result.isEmpty()) {
                val list = response["list"] as? List<*>
                    ?: response["fileList"] as? List<*>
                    ?: response["files"] as? List<*>
                    ?: emptyList<Any>()
                for (item in list) {
                    val map = item as? Map<*, *> ?: continue
                    val id = map["id"] as? Double ?: continue
                    val md5 = map["MD5"] as? String ?: map["md5"] as? String ?: continue
                    result[id] = md5
                }
            }

            android.util.Log.d("DiscRepo", "md5Map=$result")
            result
        } catch (e: Exception) {
            android.util.Log.e("DiscRepo", "getFileMd5ByIds failed", e)
            emptyMap()
        }
    }

    suspend fun getClassifyTopList(): Result<List<SceneItem>> {
        return try {
            val list = container.gatewayApi.GatewayControllerPart2ClassifyTopList("all", "explore")
            android.util.Log.d("DiscRepo", "classifyTopList size=${list.size}")
            if (list.isNotEmpty()) android.util.Log.d("DiscRepo", "classifyTopList first=${list.first()}")
            val scenes = list.mapNotNull { item ->
                val id = item["id"] as? String ?: (item["id"] as? Double)?.toInt()?.toString() ?: return@mapNotNull null
                val name = item["cname"] as? String ?: item["name"] as? String ?: item["label"] as? String ?: return@mapNotNull null
                val coverMd5 = item["cover"] as? String ?: item["MD5"] as? String ?: item["md5"] as? String ?: ""
                val coverFileId = item["fileId"] as? Double ?: item["coverFileId"] as? Double ?: 0.0
                val count = (item["num"] as? Double)?.toInt() ?: (item["count"] as? Double)?.toInt() ?: 0
                val cid = item["cid"] as? String ?: (item["cid"] as? Double)?.toInt()?.toString()
                android.util.Log.d("DiscRepo", "scene id=$id name=$name coverMd5=$coverMd5 cid=$cid")
                SceneItem(id, name, coverMd5, coverFileId, count, cid)
            }
            Result.success(scenes)
        } catch (e: Exception) {
            android.util.Log.e("DiscRepo", "classifyTopList failed", e)
            Result.failure(e)
        }
    }

    suspend fun getClassifyFileList(id: String?, cid: String?): Result<List<PhotoItem>> {
        return try {
            android.util.Log.d("DiscRepo", "classifyFileList id=$id cid=$cid")
            val response = container.gatewayApi.GatewayControllerPart2ClassifyFileList("all", id, cid)
            android.util.Log.d("DiscRepo", "classifyFileList keys=${response.keys}")
            val photos = mutableListOf<PhotoItem>()

            // Try timeline-style "result" format: {result: [{day, list: [{id, MD5, ...}]}]}
            val resultList = response["result"] as? List<*>
            if (resultList != null) {
                for (dayEntry in resultList) {
                    val dayMap = dayEntry as? Map<*, *> ?: continue
                    val day = dayMap["day"] as? String ?: ""
                    val photoList = dayMap["list"] as? List<*> ?: continue
                    for (photo in photoList) {
                        val photoMap = photo as? Map<*, *> ?: continue
                        val fileId = photoMap["id"] as? Double ?: continue
                        val md5 = photoMap["MD5"] as? String ?: photoMap["md5"] as? String ?: continue
                        val fileType = photoMap["fileType"] as? String ?: photoMap["file_type"] as? String ?: ""
                        val width = photoMap["width"] as? Double ?: 0.0
                        val height = photoMap["height"] as? Double ?: 0.0
                        photos.add(PhotoItem(fileId, md5, "", fileType, day, width, height))
                    }
                }
            }

            // Try flat list format
            if (photos.isEmpty()) {
                val list = response["list"] as? List<*>
                    ?: response["fileList"] as? List<*>
                    ?: response["files"] as? List<*>
                    ?: emptyList<Any>()
                for (item in list) {
                    val map = item as? Map<*, *> ?: continue
                    val fileId = map["id"] as? Double ?: continue
                    val md5 = map["MD5"] as? String ?: map["md5"] as? String ?: continue
                    val fileType = map["fileType"] as? String ?: map["file_type"] as? String ?: ""
                    val mtime = map["mtime"] as? String ?: ""
                    val width = map["width"] as? Double ?: 0.0
                    val height = map["height"] as? Double ?: 0.0
                    photos.add(PhotoItem(fileId, md5, "", fileType, mtime, width, height))
                }
            }

            android.util.Log.d("DiscRepo", "classifyFileList photos=${photos.size}")
            Result.success(photos)
        } catch (e: Exception) {
            android.util.Log.e("DiscRepo", "classifyFileList failed", e)
            Result.failure(e)
        }
    }

    suspend fun getAddressCountByCity(): Result<List<LocationItem>> {
        return try {
            val list = container.gatewayApi.GatewayControllerPart2AddressCountByCity("all", "explore")
            val locations = list.mapNotNull { item ->
                val city = item["city"] as? String ?: item["name"] as? String ?: return@mapNotNull null
                val count = (item["count"] as? Double)?.toInt() ?: 0
                LocationItem(city, count)
            }
            Result.success(locations)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFilesInCity(city: String): Result<List<PhotoItem>> {
        return try {
            val list = container.gatewayApi.GatewayControllerPart2FilesInAddressV2("all", "city", city)
            val photos = list.mapNotNull { item ->
                val id = item["id"] as? Double ?: return@mapNotNull null
                val md5 = item["MD5"] as? String ?: item["md5"] as? String ?: return@mapNotNull null
                val fileType = item["fileType"] as? String ?: item["file_type"] as? String ?: ""
                val mtime = item["mtime"] as? String ?: ""
                val width = item["width"] as? Double ?: 0.0
                val height = item["height"] as? Double ?: 0.0
                PhotoItem(id, md5, "", fileType, mtime, width, height)
            }
            Result.success(photos)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
