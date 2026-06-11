package com.kqstone.mtphotos.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 媒体文件同步状态
 */
enum class SyncStatus {
    /** 仅存在于本地设备 */
    LOCAL_ONLY,
    /** 仅存在于云端服务器 */
    CLOUD_ONLY,
    /** 本地和云端均存在 */
    SYNCED
}

/**
 * 备份上传状态
 */
enum class BackupStatus {
    /** 未开始备份 */
    NOT_STARTED,
    /** 正在上传中 */
    UPLOADING,
    /** 备份完成 */
    COMPLETED,
    /** 备份失败 */
    FAILED,
    /** Cloud copy was deleted elsewhere; keep local copy without auto re-uploading. */
    REMOTE_DELETED
}

/**
 * 统一媒体实体，用于 Room 数据库存储。
 * 合并本地 MediaStore 数据与云端服务器数据。
 * 使用 MD5 作为匹配键，将本地和云端的同一文件关联起来。
 */
@Entity(
    tableName = "media",
    indices = [
        Index(value = ["md5"]),
        Index(value = ["cloudId"], unique = true),
        Index(value = ["localMediaStoreId"], unique = true),
        Index(value = ["syncStatus"]),
        Index(value = ["backupStatus"]),
        Index(value = ["isFavorite"]),
        Index(value = ["isHide"]),
        Index(value = ["mtime"])
    ]
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 本地 MediaStore 的 _ID */
    val localMediaStoreId: Long? = null,

    /** 云端服务器的 file ID */
    val cloudId: Double? = null,

    /** 文件 MD5 哈希值（用于匹配本地和云端文件） */
    val md5: String = "",

    /** 文件名 */
    val fileName: String = "",

    /** 文件类型（如 image/jpeg, video/mp4） */
    val fileType: String = "",

    /** 文件的修改/拍摄时间（ISO 8601 格式） */
    val mtime: String = "",

    /** 图片/视频宽度 */
    val width: Int = 0,

    /** 图片/视频高度 */
    val height: Int = 0,

    /** 本地 content:// URI */
    val localUri: String? = null,

    /** 本地文件路径（用于上传） */
    val localPath: String? = null,

    /** 云端 MD5（来自服务器） */
    val cloudMd5: String? = null,

    val addr: String? = null,

    /** 本地缓存的缩略图路径 */
    val thumbCachePath: String? = null,

    /** 同步状态 */
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,

    /** 备份状态 */
    val backupStatus: BackupStatus = BackupStatus.NOT_STARTED,

    /** 文件大小（字节） */
    val fileSize: Long = 0,

    /** 视频时长（毫秒） */
    val duration: Long = 0,

    val livePhotosVideoId: Double? = null,

    val isLivePhotosVideo: Boolean = false,

    val livePhotoUuid: String? = null,

    /** 所属本地文件夹路径 */
    val localFolderPath: String? = null,

    /** 是否已被标记为「存储优化」（原图已删，仅保留缩略图） */
    val isStorageOptimized: Boolean = false,

    val isFavorite: Boolean = false,

    val isHide: Boolean = false,

    /** 记录创建时间 */
    val createdAt: Long = System.currentTimeMillis(),

    /** 记录更新时间 */
    val updatedAt: Long = System.currentTimeMillis()
)
