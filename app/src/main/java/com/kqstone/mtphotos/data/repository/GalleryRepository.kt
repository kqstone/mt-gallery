package com.kqstone.mtphotos.data.repository

import com.kqstone.mtphotos.AppContainer
import java.net.URLEncoder

data class TimelineMonth(
    val yearMonth: String,
    val count: Int,
    val monthKey: String? = null,
    val year: Int? = null,
    val month: Int? = null
)

data class TimelineSnapshot(
    val months: List<TimelineMonth>,
    val photos: List<PhotoItem>,
    val photosByMonth: Map<String, List<PhotoItem>> = emptyMap()
)

data class PhotoItem(
    val id: Double,
    val md5: String,
    val fileName: String,
    val fileType: String,
    val mtime: String,
    val width: Double,
    val height: Double,
    val addr: String? = null,
    val livePhotosVideoId: Double? = null,
    val isLivePhotosVideo: Boolean = false,
    val livePhotoUuid: String? = null
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
    val count: Int,
    val coverMd5: String = ""
)

/**
 * 地图专用照片项，包含 GPS 坐标信息。
 */
data class MapPhotoItem(
    val id: Double,
    val md5: String,
    val lat: Double,
    val lng: Double,
    val fileName: String = "",
    val fileType: String = "",
    val mtime: String = ""
)

enum class SearchType {
    AUTO,
    FILE_NAME,
    OCR_TEXT,
    VISUAL_TEXT
}

data class SearchFilters(
    val personId: String? = null,
    val personName: String? = null,
    val location: String? = null
)

data class SearchRequest(
    val query: String,
    val type: SearchType = SearchType.AUTO,
    val filters: SearchFilters = SearchFilters()
)

data class SearchTipItem(
    val value: String,
    val type: String,
    val label: String = value
)

class GalleryRepository(private val container: AppContainer) {

    private val prefsManager get() = container.prefsManager
    private val authRepository get() = container.authRepository

    // URL 构建缓存：避免每次缩略图加载都读 SharedPreferences + URL 编码
    @Volatile private var cachedServerUrl: String? = null
    @Volatile private var cachedRawAuth: String? = null
    @Volatile private var cachedSuffix: String? = null

    private fun urlSuffix(): String {
        val serverUrl = prefsManager.getServerUrlSync()
        val authCode = authRepository.getAuthCode()
        if (serverUrl == cachedServerUrl && authCode == cachedRawAuth && cachedSuffix != null) {
            return cachedSuffix!!
        }
        val encoded = URLEncoder.encode(authCode, "UTF-8")
        val suffix = "?auth_code=$encoded"
        cachedServerUrl = serverUrl
        cachedRawAuth = authCode
        cachedSuffix = suffix
        return suffix
    }

    private fun urlBase(): String = prefsManager.getServerUrlSync()

    suspend fun getTimeline(): Result<List<TimelineMonth>> {
        return try {
            val response = container.gatewayApi.GatewayControllerGetTimelineData()
            Result.success(parseTimelineMonths(response))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTimelineSnapshot(): Result<TimelineSnapshot> {
        return try {
            val response = container.gatewayApi.GatewayControllerGetTimelineData()
            val months = parseTimelineMonths(response)
            val photosByMonth = parseTimelinePhotosByMonth(response, months)
            Result.success(
                TimelineSnapshot(
                    months = months,
                    photos = photosByMonth.values.flatten(),
                    photosByMonth = photosByMonth
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMonthFiles(yearMonth: String): Result<List<PhotoItem>> {
        return try {
            val snapshot = getTimelineSnapshot().getOrElse { throw it }
            val month = snapshot.months.firstOrNull { it.yearMonth == yearMonth }
            val prefetched = snapshot.photosByMonth[yearMonth]
            when {
                prefetched != null -> Result.success(prefetched)
                month != null -> getTimelineMonthFiles(month)
                else -> Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTimelineMonthFiles(month: TimelineMonth): Result<List<PhotoItem>> {
        val monthKey = month.monthKey
            ?: return Result.failure(IllegalArgumentException("Timeline month ${month.yearMonth} has no month key"))
        return try {
            val response = container.gatewayApi.GatewayControllerGetTimelineMonthData(
                mapOf(
                    "monthList" to listOf(monthKey),
                    "platform" to "web"
                )
            )
            val photos = parseTimelineMonthResponse(response, monthKey)
                .filter { it.mtime.startsWith(month.yearMonth) }
            Result.success(photos)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseTimelineMonthResponse(response: Map<String, Any>, monthKey: String): List<PhotoItem> {
        val monthData = response[monthKey]
            ?: response.values.firstOrNull()
            ?: return emptyList()
        return parseTimelineMonthData(monthData)
    }

    suspend fun isClipSearchAvailable(): Result<Boolean> {
        return try {
            val response = container.gatewayApi.GatewayControllerPart5SearchCLIPStatus()
            val enabled = when (val value = response["enable"] ?: response["enabled"] ?: response["status"] ?: response["ok"]) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> value.equals("true", ignoreCase = true) ||
                    value.equals("enabled", ignoreCase = true) ||
                    value.equals("ok", ignoreCase = true) ||
                    value.equals("ready", ignoreCase = true) ||
                    value.equals("done", ignoreCase = true) ||
                    value.equals("available", ignoreCase = true) ||
                    value == "1"
                // Do not block the UI when the status endpoint shape is unknown.
                else -> true
            }
            Result.success(enabled)
        } catch (e: Exception) {
            Result.success(true)
        }
    }

    suspend fun searchMedia(request: SearchRequest): Result<List<PhotoItem>> {
        return try {
            val photos = searchMediaInternal(request)
            Result.success(applyCloudFilters(photos, request.filters))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSearchTips(query: String): Result<List<SearchTipItem>> {
        return try {
            val body = mapOf(
                "search" to query,
                "keyword" to query,
                "key" to query
            )
            val response = container.gatewayApi.GatewayControllerPart5SearchTips(body)
            Result.success(parseSearchTips(response))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseTimelineMonths(response: Map<String, Any>): List<TimelineMonth> {
        val list = response["list"] as? List<*> ?: emptyList<Any>()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val year = (map["year"] as? Number)?.toInt() ?: return@mapNotNull null
            val month = (map["month"] as? Number)?.toInt() ?: return@mapNotNull null
            val count = (map["count"] as? Number)?.toInt() ?: return@mapNotNull null
            val monthKey = map["m"] as? String
            val ym = "$year-${month.toString().padStart(2, '0')}"
            TimelineMonth(
                yearMonth = ym,
                count = count,
                monthKey = monthKey,
                year = year,
                month = month
            )
        }
    }

    private fun parseTimelinePhotos(response: Map<String, Any>): List<PhotoItem> {
        return parseTimelinePhotosByMonth(response, parseTimelineMonths(response)).values.flatten()
    }

    private fun parseTimelinePhotosByMonth(
        response: Map<String, Any>,
        months: List<TimelineMonth>
    ): Map<String, List<PhotoItem>> {
        val extra = response["extra"] as? Map<*, *> ?: return emptyMap()
        val monthKeyToYearMonth = months.mapNotNull { month ->
            month.monthKey?.let { it to month.yearMonth }
        }.toMap()
        val result = linkedMapOf<String, List<PhotoItem>>()
        for ((key, value) in extra) {
            val photos = parseTimelineMonthData(value)
            val yearMonth = monthKeyToYearMonth[key as? String]
                ?: photos.firstOrNull()?.mtime?.take(7)
                ?: continue
            result[yearMonth] = photos
        }
        return result
    }

    private fun parseTimelineMonthData(value: Any?): List<PhotoItem> {
        val photos = mutableListOf<PhotoItem>()
        when (value) {
            is List<*> -> appendPhotoItems(value, photos)
            is Map<*, *> -> {
                val sections = listOf("result", "list", "fileList", "files", "rows")
                var parsed = false
                for (section in sections) {
                    val items = value[section] as? List<*> ?: continue
                    appendPhotoItems(items, photos)
                    parsed = true
                }
                if (!parsed) {
                    parsePhotoItem(value)?.let(photos::add)
                }
            }
        }
        return photos.distinctBy { it.id }
    }

    private fun buildSearchBody(request: SearchRequest): Map<String, Any> {
        val searchType = when (request.type) {
            SearchType.FILE_NAME -> "fileName"
            SearchType.OCR_TEXT -> "OCR"
            SearchType.VISUAL_TEXT -> "CLIP"
            SearchType.AUTO -> "v1"
        }
        return buildMap {
            put("searchKey", request.query)
            put("searchType", searchType)
            if (request.type == SearchType.VISUAL_TEXT) {
                put("count", 200)
            }
        }
    }

    private suspend fun searchMediaInternal(request: SearchRequest): List<PhotoItem> {
        val query = request.query.trim()
        if (query.isBlank()) return emptyList()

        val photos = when (request.type) {
            SearchType.VISUAL_TEXT -> {
                val response = container.gatewayApi.GatewayControllerPart5SearchCLIPV2(buildSearchBody(request.copy(query = query)))
                parseSearchPhotos(response)
            }
            else -> {
                val response = container.gatewayApi.GatewayControllerPart5SearchFilesV2(buildSearchBody(request.copy(query = query)))
                parseSearchPhotos(response)
            }
        }

        return if (request.type == SearchType.FILE_NAME) {
            photos.filter { it.fileName.contains(query, ignoreCase = true) }
        } else {
            photos
        }
    }

    private suspend fun applyCloudFilters(photos: List<PhotoItem>, filters: SearchFilters): List<PhotoItem> {
        val personId = filters.personId?.takeIf { it.isNotBlank() }
        val location = filters.location?.takeIf { it.isNotBlank() }
        if (personId == null && location == null) return photos

        val filterSources = mutableListOf<List<PhotoItem>>()
        if (personId != null) {
            getPeopleFiles(personId).getOrNull()?.let(filterSources::add)
        }
        if (location != null) {
            getFilesInCity(location).getOrNull()?.let(filterSources::add)
        }

        if (filterSources.isEmpty()) return photos

        val allowedIds = filterSources
            .map { source -> source.map { it.id }.toSet() }
            .reduce { acc, ids -> acc intersect ids }

        return if (photos.isEmpty()) {
            filterSources
                .flatten()
                .distinctBy { it.id }
                .filter { it.id in allowedIds }
        } else {
            photos.filter { it.id in allowedIds }
        }
    }

    private fun parseSearchPhotos(response: Map<String, Any>): List<PhotoItem> {
        val photos = mutableListOf<PhotoItem>()

        val resultSections = listOf("result", "list", "fileList", "files", "rows")
        for (sectionKey in resultSections) {
            val section = response[sectionKey] as? List<*> ?: continue
            appendPhotoItems(section, photos)
            if (photos.isNotEmpty()) return photos.distinctBy { it.id }
        }

        val data = response["data"]
        if (data is Map<*, *>) {
            for (sectionKey in resultSections) {
                val section = data[sectionKey] as? List<*> ?: continue
                appendPhotoItems(section, photos)
                if (photos.isNotEmpty()) return photos.distinctBy { it.id }
            }
        }

        return photos.distinctBy { it.id }
    }

    private fun appendPhotoItems(
        items: List<*>,
        target: MutableList<PhotoItem>,
        fallbackMtime: String? = null,
        fallbackAddr: String? = null
    ) {
        for (item in items) {
            val map = item as? Map<*, *> ?: continue
            val day = map["day"] as? String
            val addr = parseAddr(map) ?: fallbackAddr
            val nestedList = map["list"] as? List<*>
            if (nestedList != null) {
                appendPhotoItems(nestedList, target, day ?: fallbackMtime, addr)
                continue
            }
            parsePhotoItem(
                map,
                fallbackMtime = day ?: fallbackMtime,
                fallbackAddr = addr
            )?.let(target::add)
        }
    }

    private fun parsePhotoItem(
        map: Map<*, *>,
        fallbackMtime: String? = null,
        fallbackAddr: String? = null
    ): PhotoItem? {
        val id = (map["id"] as? Number)?.toDouble()
            ?: (map["fileId"] as? Number)?.toDouble()
            ?: return null
        val md5 = map["MD5"] as? String ?: map["md5"] as? String ?: return null
        val fileName = map["name"] as? String
            ?: map["fileName"] as? String
            ?: map["filename"] as? String
            ?: ""
        val fileType = map["fileType"] as? String ?: map["file_type"] as? String ?: ""
        val mtime = map["mtime"] as? String
            ?: map["createTime"] as? String
            ?: map["date"] as? String
            ?: fallbackMtime
            ?: ""
        val width = (map["width"] as? Number)?.toDouble() ?: 0.0
        val height = (map["height"] as? Number)?.toDouble() ?: 0.0
        val addr = parseAddr(map) ?: fallbackAddr
        val livePhotosVideoId = (map["livePhotosVideoId"] as? Number)?.toDouble()
            ?: (map["live_photos_video_id"] as? Number)?.toDouble()
        val isLivePhotosVideo = parseBoolean(map["isLivePhotosVideo"])
            || parseBoolean(map["is_live_photos_video"])
        val livePhotoUuid = map["live_photo_UUID"] as? String
            ?: map["livePhotoUUID"] as? String
            ?: map["livePhotoUuid"] as? String
            ?: map["live_photo_uuid"] as? String
        return PhotoItem(
            id = id,
            md5 = md5,
            fileName = fileName,
            fileType = fileType,
            mtime = mtime,
            width = width,
            height = height,
            addr = addr,
            livePhotosVideoId = livePhotosVideoId,
            isLivePhotosVideo = isLivePhotosVideo,
            livePhotoUuid = livePhotoUuid
        )
    }

    private fun parseAddr(map: Map<*, *>): String? {
        val direct = firstNonBlankString(map, "addr", "address", "location")
        if (direct != null) return direct

        val gpsInfo = map["gpsInfo"] as? Map<*, *> ?: map["gps_info"] as? Map<*, *> ?: return null
        val parts = listOfNotNull(
            firstNonBlankString(gpsInfo, "city"),
            firstNonBlankString(gpsInfo, "district"),
            firstNonBlankString(gpsInfo, "township")
        ).distinct()
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun firstNonBlankString(map: Map<*, *>, vararg keys: String): String? {
        for (key in keys) {
            val value = map[key]
            if (value is String) {
                val normalized = normalizeAddr(value)
                if (normalized != null) return normalized
            }
        }
        return null
    }

    private fun normalizeAddr(value: String): String? {
        val normalized = value.trim()
        return normalized.takeIf {
            it.isNotEmpty() &&
                !it.equals("null", ignoreCase = true) &&
                it != "未知"
        }
    }

    private fun parseBoolean(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }
    }

    private fun parseSearchTips(items: List<Map<String, Any>>): List<SearchTipItem> {
        return items.mapNotNull { item ->
            val value = item["value"] as? String
                ?: item["name"] as? String
                ?: item["text"] as? String
                ?: return@mapNotNull null
            val type = item["type"] as? String
                ?: item["category"] as? String
                ?: "keyword"
            val label = item["label"] as? String ?: value
            SearchTipItem(value = value, type = type, label = label)
        }
    }

    fun getThumbUrl(md5: String, fileId: Double): String {
        return "${urlBase()}/gateway/file/$fileId/$md5${urlSuffix()}"
    }

    fun getThumbUrlByMd5(md5: String): String {
        return "${urlBase()}/gateway/s260/$md5${urlSuffix()}"
    }

    fun getVideoThumbUrl(md5: String): String {
        return "${urlBase()}/gateway/h220/$md5${urlSuffix()}"
    }

    fun getPortraitUrl(personId: String, cover: Double): String {
        val version = cover.toInt()
        val authQuery = urlSuffix().removePrefix("?")
        return "${urlBase()}/gateway/portrait/$personId?v=$version&$authQuery"
    }

    fun getFullImageUrl(id: Double, md5: String): String {
        return "${urlBase()}/gateway/file/$id/$md5${urlSuffix()}"
    }

    fun getMotionPhotoUrl(id: Double, md5: String): String {
        return "${urlBase()}/gateway/fileMotion/$id/$md5${urlSuffix()}"
    }

    suspend fun deleteFiles(ids: List<Double>): Result<Unit> {
        return try {
            val syncRepo = container.syncRepository

            // 查找 Room 中的实体（按 cloudId 和 dbId 查询，覆盖所有同步状态）
            val entities = syncRepo.findMediaEntitiesByIds(ids)
            syncRepo.deleteLocalMediaFiles(entities)

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
            syncRepo.deleteMediaRecords(entities)
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
                        val addr = parseAddr(dayMap)
                        val photoList = dayMap["list"] as? List<*> ?: continue
                        for (photo in photoList) {
                            val photoMap = photo as? Map<*, *> ?: continue
                            parsePhotoItem(photoMap, fallbackMtime = day, fallbackAddr = addr)?.let(photos::add)
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
                        val addr = parseAddr(dayMap)
                        val photoList = dayMap["list"] as? List<*> ?: continue
                        for (photo in photoList) {
                            val photoMap = photo as? Map<*, *> ?: continue
                            parsePhotoItem(photoMap, fallbackMtime = day, fallbackAddr = addr)?.let(photos::add)
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
                    parsePhotoItem(map)?.let(photos::add)
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
                    val addr = parseAddr(dayMap)
                    val photoList = dayMap["list"] as? List<*> ?: continue
                    for (photo in photoList) {
                        val photoMap = photo as? Map<*, *> ?: continue
                        parsePhotoItem(photoMap, fallbackMtime = day, fallbackAddr = addr)?.let(photos::add)
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
                    parsePhotoItem(map)?.let(photos::add)
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
                    val addr = parseAddr(dayMap)
                    val photoList = dayMap["list"] as? List<*> ?: continue
                    for (photo in photoList) {
                        val photoMap = photo as? Map<*, *> ?: continue
                        parsePhotoItem(photoMap, fallbackMtime = day, fallbackAddr = addr)?.let(photos::add)
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
                    parsePhotoItem(map)?.let(photos::add)
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
            val list = container.gatewayApi.GatewayControllerPart2AddressCountByCity("all")
            val locations = parseLocationItems(list)
            Result.success(locations)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAddressCountByDistrict(city: String): Result<List<LocationItem>> {
        return try {
            val list = container.gatewayApi.GatewayControllerPart2AddressCountByDistrict(city, "all")
            Result.success(parseLocationItems(list))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFilesInCity(city: String, district: String? = null): Result<List<PhotoItem>> {
        return try {
            val type = if (district.isNullOrBlank()) "city" else "district"
            val response = container.gatewayApi.GatewayControllerPart2FilesInAddressV2(
                galleryIds = "all",
                type = type,
                city = city,
                district = district?.takeIf { it.isNotBlank() }
            )
            Result.success(parseSearchPhotos(response))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseLocationItems(list: List<Map<String, Any>>): List<LocationItem> {
        return list.mapNotNull { item ->
            val city = (item["city"] as? String
                ?: item["name"] as? String
                ?: item["id"] as? String)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                ?: return@mapNotNull null
            val count = (item["count"] as? Number)?.toInt() ?: 0
            if (count <= 0) return@mapNotNull null
            val coverMd5 = (item["cover_md5"] as? String ?: item["coverMd5"] as? String ?: item["md5"] as? String)
                ?.trim()
                .orEmpty()
            LocationItem(city, count, coverMd5)
        }.sortedByDescending { it.count }
    }

    suspend fun getFileExifInfo(id: Double): Result<Map<String, Any>> {
        return try {
            val response = container.gatewayApi.GatewayControllerPart2FileExifInfo(id.toInt().toString())
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFileDetail(id: Double, md5: String): Result<Map<String, Any>> {
        return try {
            val response = container.gatewayApi.GatewayControllerPart2GetFileDetail(id.toInt().toString(), md5)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkOrCreateFavoritesAlbum(): Result<Double> {
        return try {
            val album = getFavoritesAlbum().getOrThrow()
            val id = parseNumericId(album)
            if (id != null) {
                Result.success(id)
            } else {
                Result.failure(Exception("Favorites album id not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getFavoritesAlbum(): Result<Map<String, Any>> {
        return try {
            val checkedAlbum = container.albumApi.AlbumControllerCheckAlbumForFav()
            if (parseNumericId(checkedAlbum) != null) {
                return Result.success(checkedAlbum)
            }

            val albums = container.albumApi.AlbumControllerFindAll()
            val favAlbum = albums.firstOrNull {
                isFavoritesAlbum(it)
            }
            if (favAlbum != null) {
                Result.success(favAlbum)
            } else {
                Result.failure(Exception("Favorites album not found: ${albums.map { it["name"] }}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isFavoritesAlbum(album: Map<String, Any>): Boolean {
        val name = (album["name"] as? String)?.trim().orEmpty()
        val theme = (album["theme"] as? String)?.trim().orEmpty()
        return name == "收藏" ||
            name == "__mt_photo_favorites__" ||
            theme == "favorites"
    }

    private fun parseNumericId(map: Map<String, Any>): Double? {
        return when (val id = map["id"]) {
            is Number -> id.toDouble()
            is String -> id.toDoubleOrNull()
            else -> null
        }
    }

    private fun parseNumericValue(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun albumContainsFile(album: Map<String, Any>, photoId: Double): Boolean? {
        val files = album["files"] as? List<*> ?: return null
        return files.any { fileId ->
            parseNumericValue(fileId)?.toInt() == photoId.toInt()
        }
    }

    suspend fun toggleFavorite(photoId: Double, isFavorite: Boolean): Result<Unit> {
        return try {
            val albumId = checkOrCreateFavoritesAlbum().getOrThrow()
            if (isFavorite) {
                container.albumApi.AlbumControllerAddFileToAlbum(mapOf(
                    "albumId" to albumId,
                    "files" to listOf(photoId.toInt())
                ))
            } else {
                container.albumApi.AlbumControllerRemoveFileFromAlbum(mapOf(
                    "albumId" to albumId,
                    "files" to listOf(photoId.toInt())
                ))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun isFileInFavorites(photoId: Double): Result<Boolean> {
        return try {
            val favAlbum = getFavoritesAlbum().getOrThrow()
            albumContainsFile(favAlbum, photoId)?.let { return Result.success(it) }

            val favAlbumId = parseNumericId(favAlbum)
                ?: return Result.failure(Exception("Favorites album id not found"))
            val albumIds = container.albumApi.AlbumControllerFileInAlbums(photoId)
            Result.success(albumIds.any { it.toInt() == favAlbumId.toInt() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取所有带 GPS 坐标的照片，供足迹地图使用。
     * 调用 /gateway/allFilesForMap 接口。
     */
    suspend fun getAllFilesForMap(): Result<List<MapPhotoItem>> {
        return try {
            val list = container.gatewayApi.GatewayControllerPart4GetAllFilesForMap()
            val photos = list.mapNotNull { item ->
                val id = item["id"]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
                val md5 = item["MD5"] as? String ?: item["md5"] as? String ?: return@mapNotNull null

                // 尝试从 gps 对象提取坐标
                val gps = item["gps"] as? Map<*, *>
                var lat: Double? = null
                var lng: Double? = null

                if (gps != null) {
                    lat = gps["lat"]?.toString()?.toDoubleOrNull()
                        ?: gps["latitude"]?.toString()?.toDoubleOrNull()
                    lng = gps["lng"]?.toString()?.toDoubleOrNull()
                        ?: gps["lon"]?.toString()?.toDoubleOrNull()
                        ?: gps["longitude"]?.toString()?.toDoubleOrNull()
                }

                // 直接从顶层字段提取
                if (lat == null || lng == null) {
                    lat = item["lat"]?.toString()?.toDoubleOrNull()
                        ?: item["latitude"]?.toString()?.toDoubleOrNull()
                    lng = item["lng"]?.toString()?.toDoubleOrNull()
                        ?: item["lon"]?.toString()?.toDoubleOrNull()
                        ?: item["longitude"]?.toString()?.toDoubleOrNull()
                }

                // 过滤无效坐标
                if (lat == null || lng == null || lat == 0.0 || lng == 0.0) return@mapNotNull null

                val fileName = item["fileName"] as? String ?: item["name"] as? String ?: ""
                val fileType = item["fileType"] as? String ?: ""
                val mtime = item["mtime"] as? String ?: ""

                MapPhotoItem(
                    id = id,
                    md5 = md5,
                    lat = lat,
                    lng = lng,
                    fileName = fileName,
                    fileType = fileType,
                    mtime = mtime
                )
            }
            android.util.Log.d("MapRepo", "getAllFilesForMap: ${list.size} total, ${photos.size} with GPS")
            Result.success(photos)
        } catch (e: Exception) {
            android.util.Log.e("MapRepo", "getAllFilesForMap failed", e)
            Result.failure(e)
        }
    }
}

