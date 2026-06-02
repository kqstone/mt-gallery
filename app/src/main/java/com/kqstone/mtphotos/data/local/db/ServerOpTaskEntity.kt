package com.kqstone.mtphotos.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 服务器操作类型。
 * 每种类型通过 [ServerOpTaskEntity.params] JSON 区分方向/参数。
 */
enum class ServerOpType {
    /** 云端删除（移到回收站） */
    CLOUD_DELETE,
    /** 收藏 / 取消收藏 — params: {"isFavorite": true/false} */
    FAVORITE,
    /** 修改人物名称 — params: {"personId": ..., "newName": "..."} */
    RENAME_PERSON,
    /** 添加 / 移除标签 — params: {"tagName": "...", "isAdd": true/false} */
    TAG,
    /** 隐藏 / 取消隐藏媒体 — params: {"isHide": true/false, "fileIds": [...]} */
    HIDE,
    /** 备份上传记录（由 BackupWorker 写入，不由队列执行） */
    BACKUP_UPLOAD
}

/**
 * 服务器操作任务状态。
 */
enum class ServerOpStatus {
    /** 待执行 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 成功 */
    SUCCESS,
    /** 暂时失败（等待重试） */
    ERROR,
    /** 永久失败（超过最大重试次数） */
    FAILED
}

/**
 * 统一的服务器操作任务实体。
 * 所有对服务器的写操作（删除、收藏、标签、隐藏等）都记录在此表中。
 */
@Entity(
    tableName = "server_op_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["opType"]),
        Index(value = ["nextAttemptAt"]),
        Index(value = ["createdAt"])
    ]
)
data class ServerOpTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 操作类型 */
    val opType: ServerOpType,
    /** 当前状态 */
    val status: ServerOpStatus = ServerOpStatus.PENDING,
    /** 媒体源文件名（显示用） */
    val mediaFileName: String = "",
    /** 媒体 MD5（缩略图用） */
    val mediaMd5: String = "",
    /** 云端 ID */
    val mediaCloudId: Double? = null,
    /** JSON 序列化的操作参数 */
    val params: String = "{}",
    /** 已尝试次数 */
    val attemptCount: Int = 0,
    /** 最大重试次数（默认 10：3 次立即 + 7 次退避） */
    val maxAttempts: Int = 10,
    /** 下次重试时间戳 */
    val nextAttemptAt: Long = System.currentTimeMillis(),
    /** 最后一次错误信息 */
    val lastError: String? = null,
    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 最后更新时间 */
    val updatedAt: Long = System.currentTimeMillis()
)
