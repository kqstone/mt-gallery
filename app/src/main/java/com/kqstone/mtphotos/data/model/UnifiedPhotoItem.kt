package com.kqstone.mtphotos.data.model

import com.kqstone.mtphotos.data.local.db.BackupStatus
import com.kqstone.mtphotos.data.local.db.SyncStatus

/**
 * UI 层使用的统一媒体数据模型。
 * 合并了本地和云端照片的信息，供 Gallery/Folder/Discovery 等页面使用。
 */
data class UnifiedPhotoItem(
    /** 内部数据库 ID */
    val dbId: Long = 0,

    /** 云端文件 ID（用于调用服务端 API） */
    val cloudId: Double? = null,

    /** 文件 MD5 */
    val md5: String,

    /** 文件名 */
    val fileName: String,

    /** 文件类型 */
    val fileType: String,

    /** 拍摄/修改时间 (ISO 8601) */
    val mtime: String,

    /** 图片/视频宽度 */
    val width: Double,

    /** 图片/视频高度 */
    val height: Double,

    /** 同步状态 — 用于显示缩略图右上角图标 */
    val syncStatus: SyncStatus,

    /** 备份状态 — 用于显示上传进度 */
    val backupStatus: BackupStatus = BackupStatus.NOT_STARTED,

    /** 本地 content:// URI（仅本地/已同步的文件有） */
    val localUri: String? = null,

    /** 缩略图本地缓存路径 */
    val thumbCachePath: String? = null,

    /** 是否已执行存储优化（原图删除，仅保留缩略图） */
    val isStorageOptimized: Boolean = false,

    /** 文件大小（字节） */
    val fileSize: Long = 0
) {
    /**
     * 用于唯一标识此项（兼容旧 PhotoItem 的 id 字段）
     * 优先使用 cloudId，没有则用 dbId（Room 自增 ID）
     * 注意：dbId 在未经 Room 插入前为 0，此时 id 也为 0
     */
    val id: Double
        get() = cloudId ?: dbId.toDouble()

    /**
     * 用于 LazyColumn/Grid 的唯一 key。
     * 使用 md5（文件内容唯一）+ dbId 组合，确保不会重复。
     */
    val uniqueKey: String
        get() = if (md5.isNotEmpty()) "md5_${md5}" else "db_${dbId}_${cloudId ?: 0.0}"

    /**
     * 是否为视频
     */
    fun isVideo(): Boolean {
        val ft = fileType.lowercase()
        return ft.startsWith("video") ||
                ft == "mp4" || ft == "mov" || ft == "avi" || ft == "mkv" ||
                fileName.endsWith(".mp4", true) ||
                fileName.endsWith(".mov", true) ||
                fileName.endsWith(".avi", true)
    }
}
