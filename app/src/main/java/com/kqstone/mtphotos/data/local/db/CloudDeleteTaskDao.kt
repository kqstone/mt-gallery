package com.kqstone.mtphotos.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CloudDeleteTaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tasks: List<CloudDeleteTaskEntity>)

    @Query("""
        SELECT * FROM cloud_delete_tasks
        WHERE status IN ('PENDING', 'FAILED_WAITING')
        AND nextAttemptAt <= :now
        ORDER BY nextAttemptAt ASC, createdAt ASC
        LIMIT :limit
    """)
    suspend fun getDueTasks(now: Long, limit: Int): List<CloudDeleteTaskEntity>

    @Query("""
        UPDATE cloud_delete_tasks
        SET status = 'RUNNING', updatedAt = :now
        WHERE cloudId = :cloudId
        AND status IN ('PENDING', 'FAILED_WAITING')
    """)
    suspend fun claim(cloudId: Double, now: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM cloud_delete_tasks WHERE cloudId = :cloudId")
    suspend fun deleteByCloudId(cloudId: Double)

    @Query("""
        UPDATE cloud_delete_tasks
        SET status = :status,
            attemptCount = :attemptCount,
            nextAttemptAt = :nextAttemptAt,
            lastError = :lastError,
            updatedAt = :now
        WHERE cloudId = :cloudId
    """)
    suspend fun markFailed(
        cloudId: Double,
        status: CloudDeleteStatus,
        attemptCount: Int,
        nextAttemptAt: Long,
        lastError: String,
        now: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE cloud_delete_tasks
        SET status = 'PENDING', updatedAt = :now
        WHERE status = 'RUNNING'
        AND updatedAt < :staleBefore
    """)
    suspend fun resetStaleRunning(
        staleBefore: Long,
        now: Long = System.currentTimeMillis()
    ): Int

    @Query("SELECT COUNT(*) FROM cloud_delete_tasks")
    suspend fun countAll(): Int

    @Query("SELECT MIN(nextAttemptAt) FROM cloud_delete_tasks")
    suspend fun nextAttemptAt(): Long?
}
