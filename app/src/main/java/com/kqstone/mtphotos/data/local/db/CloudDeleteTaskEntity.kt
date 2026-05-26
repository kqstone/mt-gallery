package com.kqstone.mtphotos.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class CloudDeleteStatus {
    PENDING,
    RUNNING,
    FAILED_WAITING
}

@Entity(
    tableName = "cloud_delete_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["nextAttemptAt"])
    ]
)
data class CloudDeleteTaskEntity(
    @PrimaryKey
    val cloudId: Double,
    val md5: String = "",
    val fileName: String = "",
    val status: CloudDeleteStatus = CloudDeleteStatus.PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = System.currentTimeMillis(),
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
