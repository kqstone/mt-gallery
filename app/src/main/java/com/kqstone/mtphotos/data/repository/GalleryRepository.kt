package com.kqstone.mtphotos.data.repository

import com.kqstone.mtphotos.AppContainer
import com.kqstone.mtphotos.data.local.db.BackupStatus
import com.kqstone.mtphotos.data.local.db.MediaEntity
import com.kqstone.mtphotos.data.local.db.SyncStatus
import com.kqstone.mtphotos.data.local.db.toMapPhotoEntity
import com.kqstone.mtphotos.data.model.UnifiedPhotoItem
import com.kqstone.mtphotos.network.NetworkFailure
import com.kqstone.mtphotos.worker.BackupScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher

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
    val livePhotoUuid: String? = null,
    val isFavorite: Boolean = false,
    val duration: Long = 0,
    val isHide: Boolean = false
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

data class AlbumItem(
    val id: Double,
    val name: String,
    val coverMd5: String,
    val coverFileId: Double,
    val fileCount: Int
)

data class PersonItem(
    val id: String,
    val name: String,
    val coverMd5: String,
    val coverFileId: Double,
    val count: Int
)

data class PeopleDescriptorBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)

data class PeopleDescriptorPerson(
    val id: Double,
    val name: String,
    val cover: Double
)

data class PeopleDescriptorItem(
    val id: Double,
    val pass: Boolean,
    val score: Double,
    val box: PeopleDescriptorBox,
    val person: PeopleDescriptorPerson
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

    fun isDeviceOffline(): Boolean {
        return NetworkFailure.isDeviceOffline(prefsManager.context)
    }

    suspend fun markNetworkRetryPending() {
        prefsManager.setNetworkRetryPending(true)
    }

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

    /**
     * 批量请求多个月份的文件数据（单次 API 调用）。
     * 返回按 yearMonth 分组的照片 Map。
     */
    suspend fun getTimelineMonthFilesBatch(months: List<TimelineMonth>): Result<Map<String, List<PhotoItem>>> {
        val monthsWithKey = months.filter { it.monthKey != null }
        if (monthsWithKey.isEmpty()) return Result.success(emptyMap())
        return try {
            val monthKeys = monthsWithKey.map { it.monthKey!! }
            val keyToYearMonth = monthsWithKey.associate { it.monthKey!! to it.yearMonth }
            val response = container.gatewayApi.GatewayControllerGetTimelineMonthData(
                mapOf(
                    "monthList" to monthKeys,
                    "platform" to "web"
                )
            )
            val result = mutableMapOf<String, List<PhotoItem>>()
            for ((key, yearMonth) in keyToYearMonth) {
                val monthData = response[key] ?: continue
                val photos = parseTimelineMonthData(monthData)
                    .filter { it.mtime.startsWith(yearMonth) }
                result[yearMonth] = photos
            }
            Result.success(result)
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

    private fun parsePhotoItems(response: Map<String, Any>): List<PhotoItem> {
        val photos = mutableListOf<PhotoItem>()
        val extra = response["extra"] as? Map<*, *>
        if (extra != null) {
            for ((_, value) in extra) {
                photos += parseTimelineMonthData(value)
            }
        }
        if (photos.isEmpty()) {
            for (sectionKey in listOf("data", "result", "list", "fileList", "files", "rows")) {
                val section = response[sectionKey] as? List<*> ?: continue
                appendPhotoItems(section, photos)
                if (photos.isNotEmpty()) break
            }
        }
        return if (photos.isNotEmpty()) {
            photos.distinctBy { it.id }
        } else {
            parseSearchPhotos(response)
        }
    }

    private fun parsePhotoItems(response: List<*>): List<PhotoItem> {
        val photos = mutableListOf<PhotoItem>()
        appendPhotoItems(response, photos)
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
        val isFavorite = parseBoolean(map["isFavorite"])
            || parseBoolean(map["favorite"])
            || parseBoolean(map["isFav"])
            || parseBoolean(map["fav"])
        val duration = parseDurationMillis(map)
        val isHide = parseBoolean(map["isHide"])
            || parseBoolean(map["hide"])
            || parseBoolean(map["is_hide"])
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
            livePhotoUuid = livePhotoUuid,
            isFavorite = isFavorite,
            duration = duration,
            isHide = isHide
        )
    }

    private fun parseDurationMillis(map: Map<*, *>): Long {
        parseDurationMillisValue(map)?.let { return it }
        val extra = map["extra"] as? Map<*, *> ?: return 0
        return parseDurationMillisValue(extra) ?: 0
    }

    private fun parseDurationMillisValue(map: Map<*, *>): Long? {
        firstNumericValue(map, "durationMs", "durationMillis", "duration_ms", "duration_millis")
            ?.let { return it.toLong().coerceAtLeast(0) }
        val duration = firstNumericValue(map, "duration", "Duration", "videoDuration", "video_duration")
            ?: return null
        if (duration <= 0.0) return 0
        val millis = if (duration < 24 * 60 * 60) duration * 1000 else duration
        return millis.toLong().coerceAtLeast(0)
    }

    private fun firstNumericValue(map: Map<*, *>, vararg keys: String): Double? {
        for (key in keys) {
            val parsed = when (val value = map[key]) {
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull()
                else -> null
            }
            if (parsed != null) return parsed
        }
        return null
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

    fun getOriginalImageUrl(id: Double, md5: String): String {
        return "${urlBase()}/gateway/file/$id/$md5${urlSuffix()}&type=ori"
    }

    fun getTranscodeVideoUrl(id: Double, md5: String): String {
        return "${urlBase()}/gateway/file/$id/$md5${urlSuffix()}&type=transcode"
    }

    suspend fun getFileStreamUrl(id: Double): Result<String> {
        return try {
            val response = container.gatewayApi.GatewayControllerPart2FileStreamLink(fileIdPath(id))
            val link = (response as? Map<*, *>)?.get("link")?.toString().orEmpty()
            if (link.isBlank()) {
                Result.failure(IllegalStateException("Empty stream link"))
            } else {
                Result.success(toAbsoluteUrl(link))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPeopleDescriptorsOfFile(fileId: Double): Result<List<PeopleDescriptorItem>> {
        return try {
            val response = container.peopleDescriptorAdminApi.PeopleDescriptorControllerFindDescriptorOfFile(fileId)
            Result.success(response.mapNotNull(::parsePeopleDescriptorItem))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getFileDownloadUrl(id: Double, md5: String): String {
        return "${urlBase()}/gateway/fileDownload/$id/$md5${urlSuffix()}"
    }

    suspend fun refreshAuthCode(): Result<String> {
        val result = authRepository.refreshAuthCode()
        if (result.isSuccess) {
            cachedRawAuth = null
            cachedSuffix = null
        }
        return result
    }

    suspend fun ensureAuthCode(): Boolean {
        if (authRepository.getAuthCode().isNotBlank()) return true
        return refreshAuthCode().isSuccess
    }

    suspend fun markRecoverableFailure(error: Throwable) {
        if (NetworkFailure.isDeviceOffline(prefsManager.context)) {
            prefsManager.setNetworkRetryPending(true)
        } else if (NetworkFailure.isServerUnreachable(error)) {
            prefsManager.setServerUnreachable(true)
        }
    }

    suspend fun markOriginalDownloaded(md5: String, localUri: String, localPath: String) {
        container.syncRepository.markOriginalDownloaded(md5, localUri, localPath)
    }

    fun getMotionPhotoUrl(id: Double, md5: String): String {
        return "${urlBase()}/gateway/fileMotion/$id/$md5${urlSuffix()}"
    }

    private fun fileIdPath(id: Double): String {
        return if (id % 1.0 == 0.0) id.toLong().toString() else id.toString()
    }

    private fun toAbsoluteUrl(link: String): String {
        return when {
            link.startsWith("http://", ignoreCase = true) ||
                link.startsWith("https://", ignoreCase = true) -> link
            link.startsWith("/") -> "${urlBase()}$link"
            else -> "${urlBase()}/$link"
        }
    }

    suspend fun deleteFiles(ids: List<Double>): Result<Unit> {
        return try {
            val syncRepo = container.syncRepository

            // 查找 Room 中的实体（按 cloudId 和 dbId 查询，覆盖所有同步状态）
            val entities = syncRepo.findMediaEntitiesByIds(ids)
            syncRepo.ensureLocalDeleteAllowed(entities)
            syncRepo.deleteLocalMediaFiles(entities)
            val cloudRequests = entities.toCloudDeleteRequests()

            container.serverOpTaskRepository.enqueueCloudDeleteRequests(cloudRequests)
            syncRepo.deleteMediaRecords(entities)
            if (cloudRequests.isNotEmpty()) {
                BackupScheduler.triggerServerOpWork(container.prefsManager.context)
            }
            val deletedPhotos = entities.map { it.toUnifiedPhotoItem() }
            if (deletedPhotos.isNotEmpty()) {
                container.mediaUiMutationBus.publish(MediaUiMutation.Deleted(deletedPhotos))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePhotos(photos: List<UnifiedPhotoItem>): Result<Unit> {
        return try {
            if (photos.isEmpty()) return Result.success(Unit)

            val syncRepo = container.syncRepository
            val entities = syncRepo.findMediaEntitiesForPhotos(photos)
            val cloudRequests = buildCloudDeleteRequests(photos, entities)

            if (entities.isEmpty() && cloudRequests.isEmpty()) {
                return Result.failure(IllegalArgumentException("No deletable media found"))
            }

            syncRepo.ensureLocalDeleteAllowed(entities)
            syncRepo.deleteLocalMediaFiles(entities)
            container.serverOpTaskRepository.enqueueCloudDeleteRequests(cloudRequests)
            syncRepo.deleteMediaRecords(entities)
            if (cloudRequests.isNotEmpty()) {
                BackupScheduler.triggerServerOpWork(container.prefsManager.context)
            }
            container.mediaUiMutationBus.publish(MediaUiMutation.Deleted(photos))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildCloudDeleteRequests(
        photos: List<UnifiedPhotoItem>,
        entities: List<MediaEntity>
    ): List<CloudDeleteRequest> {
        val entityByCloudId = entities.mapNotNull { entity ->
            entity.cloudId?.let { it to entity }
        }.toMap()
        val entityRequests = entities.toCloudDeleteRequests()
        val cloudOnlyRequests = photos.mapNotNull { photo ->
            val cloudId = photo.cloudId?.takeIf { it > 0 } ?: return@mapNotNull null
            if (cloudId in entityByCloudId) return@mapNotNull null
            CloudDeleteRequest(
                cloudId = cloudId,
                md5 = photo.md5,
                fileName = photo.fileName
            )
        }
        return (entityRequests + cloudOnlyRequests).distinctBy { it.cloudId }
    }

    private fun List<MediaEntity>.toCloudDeleteRequests(): List<CloudDeleteRequest> {
        return mapNotNull { entity ->
            val cloudId = entity.cloudId?.takeIf { it > 0 } ?: return@mapNotNull null
            CloudDeleteRequest(
                cloudId = cloudId,
                md5 = entity.cloudMd5 ?: entity.md5,
                fileName = entity.fileName
            )
        }.distinctBy { it.cloudId }
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

    suspend fun getAlbums(): Result<List<AlbumItem>> {
        return try {
            val rawAlbums = container.albumApi.AlbumControllerFindAll()
            val albums = rawAlbums.mapNotNull { item ->
                val id = parseNumericValue(item["id"]) ?: return@mapNotNull null
                val name = (item["name"] as? String)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val coverRaw = item["cover"]
                val coverFileId = parseNumericValue(
                    item["coverFileId"] ?: item["coverId"] ?: item["fileId"] ?: coverRaw
                ) ?: 0.0
                val coverMd5 = when (coverRaw) {
                    is String -> coverRaw.substringBefore(",").trim().takeIf { it.length >= 16 }.orEmpty()
                    else -> ""
                }
                val fileCount = parseNumericValue(
                    item["fileNums"] ?: item["count"] ?: item["fileCount"] ?: item["total"]
                )?.toInt() ?: 0
                AlbumItem(
                    id = id,
                    name = name,
                    coverMd5 = coverMd5,
                    coverFileId = coverFileId,
                    fileCount = fileCount
                )
            }

            val missingCoverIds = albums
                .filter { it.coverMd5.isBlank() && it.coverFileId > 0 }
                .map { it.coverFileId }
                .distinct()
            val md5Map = if (missingCoverIds.isNotEmpty()) getFileMd5ByIds(missingCoverIds) else emptyMap()

            Result.success(
                albums.map { album ->
                    if (album.coverMd5.isNotBlank()) {
                        album
                    } else {
                        album.copy(coverMd5 = md5Map[album.coverFileId].orEmpty())
                    }
                }
            )
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
            Result.success(parsePhotoItems(response))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAlbumFiles(albumId: Double): Result<List<PhotoItem>> {
        return try {
            val response = container.albumApi.AlbumControllerFindAlbumFilesV2(albumId, "v2")
            Result.success(parsePhotoItems(response))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFavoriteFiles(): Result<List<PhotoItem>> {
        return try {
            val albumId = checkOrCreateFavoritesAlbum().getOrThrow()
            val photos = getAlbumFiles(albumId).getOrThrow()
            Result.success(photos.map { it.copy(isFavorite = true) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFavoriteFileIds(): Result<Set<Double>> {
        return getFavoriteFiles().map { photos -> photos.map { it.id }.toSet() }
    }

    suspend fun getRecentFiles(): Result<List<PhotoItem>> {
        return try {
            val response = container.gatewayApi.GatewayControllerPart3FilesRecent("all")
            Result.success(parsePhotoItems(response))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getVideoFiles(): Result<List<PhotoItem>> {
        return try {
            val response = container.gatewayApi.GatewayControllerPart2FilesInCategoriesV2("all", "videos")
            Result.success(parsePhotoItems(response))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTrashFiles(): Result<List<PhotoItem>> {
        return try {
            val response = container.gatewayApi.GatewayControllerPart2FilesInTrashFlat()
            Result.success(parsePhotoItems(response))
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

    private fun parsePeopleDescriptorItem(item: Map<String, Any>): PeopleDescriptorItem? {
        val boxMap = item["box"] as? Map<*, *> ?: return null
        val personMap = item["people"] as? Map<*, *> ?: return null
        val personName = (personMap["name"] as? String)
            ?.trim()
            ?.takeIf(::hasDisplayPersonName)
            ?: return null
        val box = PeopleDescriptorBox(
            x = parseNumericValue(boxMap["x"]) ?: return null,
            y = parseNumericValue(boxMap["y"]) ?: return null,
            width = parseNumericValue(boxMap["width"]) ?: return null,
            height = parseNumericValue(boxMap["height"]) ?: return null
        )
        if (box.width <= 0.0 || box.height <= 0.0) return null

        return PeopleDescriptorItem(
            id = parseNumericValue(item["id"]) ?: 0.0,
            pass = parseBoolean(item["pass"]),
            score = parseNumericValue(item["score"]) ?: 0.0,
            box = box,
            person = PeopleDescriptorPerson(
                id = parseNumericValue(personMap["id"]) ?: 0.0,
                name = personName,
                cover = parseNumericValue(personMap["cover"]) ?: 0.0
            )
        )
    }

    private fun hasDisplayPersonName(name: String): Boolean {
        val normalized = name.trim()
        return normalized.isNotBlank() &&
            !normalized.equals("null", ignoreCase = true) &&
            !normalized.equals("unknown", ignoreCase = true) &&
            !normalized.equals("unnamed", ignoreCase = true)
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

    suspend fun toggleHide(photoIds: List<Double>, isHide: Boolean): Result<Unit> {
        return try {
            val ids = photoIds.map { it.toInt() }.distinct()
            if (ids.isEmpty()) return Result.success(Unit)
            val body = mapOf("fileIds" to ids)
            if (isHide) {
                container.gatewayApi.GatewayControllerPart3AddHideFiles(body)
            } else {
                container.gatewayApi.GatewayControllerPart3CancelHideFiles(body)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getHiddenFiles(password: String): Result<List<PhotoItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val rsaResponse = container.authApi.AppControllerGetLoginRSAKeys()
                val encryptedPassword = encryptPasswordWithRsa(password, rsaResponse)
                    ?: return@withContext Result.failure(Exception("RSA public key not found"))
                val passwordCodeResponse = container.gatewayApi.GatewayControllerPart3PwdCode(
                    buildPasswordCodeBody(encryptedPassword, rsaResponse)
                )
                val passwordCode = parsePasswordCode(passwordCodeResponse)
                    ?: return@withContext Result.failure(Exception("Password verification response missing passwordCode"))

                val hidden = container.gatewayApi.GatewayControllerPart3FilesInHide(
                    mapOf("passwordCode" to passwordCode)
                )
                val photos = parsePhotoItems(hidden).map { it.copy(isHide = true) }
                cacheHiddenFiles(photos)
                Result.success(photos)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun buildPasswordCodeBody(
        encryptedPassword: String,
        rsaResponse: Map<String, Any>
    ): Map<String, Any> {
        val body = mutableMapOf<String, Any>(
            "password" to "__MT_RSA_ENC",
            "passwordEnc" to encryptedPassword
        )
        parseRsaKeyId(rsaResponse)?.let { key ->
            body["key"] = key
            body["rsaKey"] = key
        }
        return body
    }

    private fun encryptPasswordWithRsa(password: String, rsaResponse: Map<String, Any>): String? {
        val publicKeyPem = parseRsaPublicKey(rsaResponse) ?: return null
        val publicKey = parseRsaPublicKeyPem(publicKeyPem)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.getEncoder().encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
    }

    private fun parseRsaPublicKeyPem(publicKeyPem: String): java.security.PublicKey {
        val keyFactory = KeyFactory.getInstance("RSA")
        val cleanKey = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("-----END RSA PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(cleanKey)
        return if (publicKeyPem.contains("BEGIN RSA PUBLIC KEY")) {
            val (modulus, exponent) = parsePkcs1RsaPublicKey(keyBytes)
            keyFactory.generatePublic(RSAPublicKeySpec(modulus, exponent))
        } else {
            keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))
        }
    }

    private fun parsePkcs1RsaPublicKey(bytes: ByteArray): Pair<BigInteger, BigInteger> {
        val reader = DerReader(bytes)
        reader.readSequence()
        val modulus = reader.readInteger()
        val exponent = reader.readInteger()
        return modulus to exponent
    }

    private class DerReader(private val bytes: ByteArray) {
        private var offset = 0

        fun readSequence() {
            readTag(0x30)
            readLength()
        }

        fun readInteger(): BigInteger {
            readTag(0x02)
            val length = readLength()
            require(offset + length <= bytes.size) { "Invalid RSA public key" }
            val value = bytes.copyOfRange(offset, offset + length)
            offset += length
            return BigInteger(1, value)
        }

        private fun readTag(expected: Int) {
            require(offset < bytes.size && bytes[offset].toInt() and 0xff == expected) {
                "Invalid RSA public key"
            }
            offset += 1
        }

        private fun readLength(): Int {
            require(offset < bytes.size) { "Invalid RSA public key" }
            val first = bytes[offset++].toInt() and 0xff
            if (first and 0x80 == 0) return first
            val count = first and 0x7f
            require(count in 1..4 && offset + count <= bytes.size) { "Invalid RSA public key" }
            var length = 0
            repeat(count) {
                length = (length shl 8) or (bytes[offset++].toInt() and 0xff)
            }
            return length
        }
    }

    private fun parseRsaPublicKey(response: Map<String, Any>): String? {
        val keys = arrayOf("publicKey", "public_key", "rsaPublicKey", "rsa_public_key", "key")
        firstNonBlankString(response, *keys)?.let { value ->
            if (value.contains("BEGIN PUBLIC KEY") || value.length > 80) return value
        }
        for (nestedKey in arrayOf("data", "result")) {
            val nested = response[nestedKey] as? Map<*, *> ?: continue
            firstNonBlankString(nested, *keys)?.let { return it }
        }
        return null
    }

    private fun parseRsaKeyId(response: Map<String, Any>): String? {
        val keys = arrayOf("id", "uuid", "rsaKey", "rsa_key")
        firstNonBlankString(response, *keys)?.let { return it }
        val rawKey = firstNonBlankString(response, "key")
        if (rawKey != null && !rawKey.contains("BEGIN PUBLIC KEY") && rawKey.length <= 80) {
            return rawKey
        }
        for (nestedKey in arrayOf("data", "result")) {
            val nested = response[nestedKey] as? Map<*, *> ?: continue
            firstNonBlankString(nested, *keys)?.let { return it }
            val nestedRawKey = firstNonBlankString(nested, "key")
            if (nestedRawKey != null &&
                !nestedRawKey.contains("BEGIN PUBLIC KEY") &&
                nestedRawKey.length <= 80
            ) {
                return nestedRawKey
            }
        }
        return null
    }

    private fun parsePhotoItems(response: Any?): List<PhotoItem> {
        return when (response) {
            is Map<*, *> -> parsePhotoItems(
                response.entries
                    .mapNotNull { entry ->
                        val key = entry.key as? String ?: return@mapNotNull null
                        val value = entry.value ?: return@mapNotNull null
                        key to value
                    }
                    .toMap()
            )
            is List<*> -> parsePhotoItems(response)
            else -> emptyList()
        }
    }

    private fun parsePasswordCode(response: Map<String, Any>): String? {
        firstNonBlankString(response, "passwordCode", "password_code", "code")?.let { return it }
        val nestedKeys = arrayOf("data", "result")
        for (key in nestedKeys) {
            val nested = response[key]
            if (nested is Map<*, *>) {
                firstNonBlankString(nested, "passwordCode", "password_code", "code")?.let { return it }
            } else if (nested is String && nested.isNotBlank()) {
                return nested
            }
        }
        return null
    }

    private suspend fun cacheHiddenFiles(photos: List<PhotoItem>) {
        if (photos.isEmpty()) return
        val dao = container.database.mediaDao()
        val cloudIds = photos.map { it.id }.distinct()
        dao.updateHideByCloudIds(cloudIds, isHide = true)
        val existingIds = dao.findByCloudIds(cloudIds).mapNotNull { it.cloudId }.toSet()
        val missing = photos
            .filter { it.id !in existingIds }
            .map { photo ->
                MediaEntity(
                    cloudId = photo.id,
                    md5 = photo.md5,
                    cloudMd5 = photo.md5,
                    fileName = photo.fileName,
                    fileType = photo.fileType,
                    mtime = photo.mtime,
                    width = photo.width.toInt(),
                    height = photo.height.toInt(),
                    addr = photo.addr,
                    livePhotosVideoId = photo.livePhotosVideoId,
                    isLivePhotosVideo = photo.isLivePhotosVideo,
                    livePhotoUuid = photo.livePhotoUuid,
                    isFavorite = photo.isFavorite,
                    isHide = true,
                    syncStatus = SyncStatus.CLOUD_ONLY,
                    backupStatus = BackupStatus.NOT_STARTED
                )
            }
        if (missing.isNotEmpty()) {
            dao.insertAll(missing)
        }
    }

    suspend fun getCachedHiddenFiles(): List<PhotoItem> = withContext(Dispatchers.IO) {
        container.database.mediaDao().getHiddenMedia().map { entity ->
            PhotoItem(
                id = entity.cloudId ?: entity.id.toDouble(),
                md5 = entity.md5.ifEmpty { entity.cloudMd5.orEmpty() },
                fileName = entity.fileName,
                fileType = entity.fileType,
                mtime = entity.mtime,
                width = entity.width.toDouble(),
                height = entity.height.toDouble(),
                addr = entity.addr,
                livePhotosVideoId = entity.livePhotosVideoId,
                isLivePhotosVideo = entity.isLivePhotosVideo,
                livePhotoUuid = entity.livePhotoUuid,
                isFavorite = entity.isFavorite,
                isHide = entity.isHide
            )
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

    suspend fun getCachedFavoriteState(
        dbId: Long,
        cloudId: Double?,
        md5: String
    ): Boolean? = withContext(Dispatchers.IO) {
        val dao = container.database.mediaDao()
        if (cloudId != null) {
            dao.findByCloudId(cloudId)?.let { return@withContext it.isFavorite }
        }
        if (dbId > 0) {
            dao.findById(dbId)?.let { return@withContext it.isFavorite }
        }
        if (md5.isNotEmpty()) {
            dao.findByMd5(md5)?.let { return@withContext it.isFavorite }
        }
        null
    }

    /**
     * 获取所有带 GPS 坐标的照片，供足迹地图使用。
     * 调用 /gateway/allFilesForMap 接口。
     */
    suspend fun getAllFilesForMap(): Result<List<MapPhotoItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val list = container.gatewayApi.GatewayControllerPart4GetAllFilesForMap()
                val primaryParsed = list.mapNotNull(::parseMapPhotoItem)
                val parsed = if (primaryParsed.isNotEmpty()) {
                    primaryParsed
                } else {
                    val directList = runCatching {
                        container.gatewayApi.GatewayControllerPart4GetFilesForMapDirect()
                    }.getOrDefault(emptyList())
                    val directParsed = directList.mapNotNull(::parseMapPhotoItem)
                    android.util.Log.d(
                        "MapRepo",
                        "allFilesForMap parsed empty; direct total=${directList.size}, parsed=${directParsed.size}"
                    )
                    directParsed
                }
                val missingInfoIds = parsed.filter { it.md5.isBlank() }.map { it.id }.distinct()
                val infoById = if (missingInfoIds.isNotEmpty()) {
                    getMapFileInfoByIds(missingInfoIds)
                } else {
                    emptyMap()
                }
                val photos = parsed.map { photo ->
                    val info = infoById[photo.id]
                    photo.copy(
                        md5 = photo.md5.ifBlank { info?.md5.orEmpty() },
                        fileName = photo.fileName.ifBlank { info?.fileName.orEmpty() },
                        fileType = photo.fileType.ifBlank { info?.fileType.orEmpty() },
                        mtime = photo.mtime.ifBlank { info?.mtime.orEmpty() }
                    )
                }
                android.util.Log.d(
                    "MapRepo",
                    "getAllFilesForMap: ${list.size} total, ${parsed.size} parsed, ${photos.count { it.md5.isNotBlank() }} with md5"
                )
                cacheMapPhotos(photos)
                Result.success(photos)
            } catch (e: Exception) {
                android.util.Log.e("MapRepo", "getAllFilesForMap failed", e)
                Result.failure(e)
            }
        }
    }

    suspend fun getCachedMapPhotos(): List<MapPhotoItem> = withContext(Dispatchers.IO) {
        runCatching {
            container.database.mapPhotoDao().getAll().map { it.toMapPhotoItem() }
        }.getOrDefault(emptyList())
    }

    private suspend fun cacheMapPhotos(photos: List<MapPhotoItem>) {
        runCatching {
            val updatedAt = System.currentTimeMillis()
            container.database.mapPhotoDao().replaceAll(photos.map { it.toMapPhotoEntity(updatedAt) })
        }.onFailure {
            android.util.Log.w("MapRepo", "cacheMapPhotos failed", it)
        }
    }

    private fun parseMapPhotoItem(item: Map<String, Any>): MapPhotoItem? {
        val id = firstDouble(item, "id", "fileId", "file_id")
            ?: return null
        val coordinates = parseMapCoordinates(item) ?: return null
        if (coordinates.first == 0.0 || coordinates.second == 0.0) return null

        return MapPhotoItem(
            id = id,
            md5 = firstRawString(item, "MD5", "md5", "fileMd5", "file_md5").orEmpty(),
            lat = coordinates.first,
            lng = coordinates.second,
            fileName = firstRawString(item, "fileName", "filename", "name").orEmpty(),
            fileType = firstRawString(item, "fileType", "file_type").orEmpty(),
            mtime = firstRawString(item, "mtime", "createTime", "date").orEmpty()
        )
    }

    private fun parseMapCoordinates(item: Map<String, Any>): Pair<Double, Double>? {
        val gpsMaps = listOfNotNull(
            item["gps"] as? Map<*, *>,
            item["gpsInfo"] as? Map<*, *>,
            item["gps_info"] as? Map<*, *>,
            item["location"] as? Map<*, *>,
            item["coordinate"] as? Map<*, *>,
            item["coordinates"] as? Map<*, *>
        )
        for (gps in gpsMaps) {
            val lat = firstDouble(gps, "lat", "latitude", "Latitude")
            val lng = firstDouble(gps, "lng", "lon", "longitude", "Longitude")
            if (lat != null && lng != null) return lat to lng
        }

        val lat = firstDouble(item, "lat", "latitude", "Latitude", "y")
        val lng = firstDouble(item, "lng", "lon", "longitude", "Longitude", "x")
        if (lat != null && lng != null) return lat to lng

        for (key in listOf("lnglat", "lngLat", "latlng", "latLng", "location", "coordinate", "coordinates", "point")) {
            parseCoordinateValue(item[key])?.let { return it }
        }
        return null
    }

    private fun parseCoordinateValue(value: Any?): Pair<Double, Double>? {
        return when (value) {
            is List<*> -> {
                val first = value.getOrNull(0)?.toString()?.toDoubleOrNull()
                val second = value.getOrNull(1)?.toString()?.toDoubleOrNull()
                if (first != null && second != null) lngLatToPair(first, second) else null
            }
            is String -> {
                val parts = value.split(",", ";", " ").mapNotNull { it.trim().toDoubleOrNull() }
                if (parts.size >= 2) lngLatToPair(parts[0], parts[1]) else null
            }
            else -> null
        }
    }

    private fun lngLatToPair(first: Double, second: Double): Pair<Double, Double> {
        return if (kotlin.math.abs(first) > 90 && kotlin.math.abs(second) <= 90) {
            second to first
        } else {
            first to second
        }
    }

    private suspend fun getMapFileInfoByIds(ids: List<Double>): Map<Double, MapPhotoItem> {
        if (ids.isEmpty()) return emptyMap()
        return runCatching {
            ids.chunked(500).flatMap { chunk ->
                val body = mapOf("ids" to chunk.map { it.toInt() })
                container.gatewayApi.GatewayControllerPart4GetFileMD5List(body)
            }.mapNotNull { item ->
                val id = firstDouble(item, "id", "fileId", "file_id") ?: return@mapNotNull null
                val md5 = firstRawString(item, "MD5", "md5", "fileMd5", "file_md5").orEmpty()
                id to MapPhotoItem(
                    id = id,
                    md5 = md5,
                    lat = 0.0,
                    lng = 0.0,
                    fileName = firstRawString(item, "fileName", "filename", "name").orEmpty(),
                    fileType = firstRawString(item, "fileType", "file_type").orEmpty(),
                    mtime = firstRawString(item, "mtime", "createTime", "date").orEmpty()
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun firstDouble(map: Map<*, *>, vararg keys: String): Double? {
        for (key in keys) {
            val value = map[key]
            val parsed = when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun firstRawString(map: Map<*, *>, vararg keys: String): String? {
        for (key in keys) {
            val value = map[key]?.toString()?.trim()
            if (!value.isNullOrBlank() && !value.equals("null", ignoreCase = true)) return value
        }
        return null
    }
}

