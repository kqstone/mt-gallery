package com.kqstone.mtphotos.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerOpTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: ServerOpTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<ServerOpTaskEntity>)

    /** 获取到期的待执行任务（PENDING 或 ERROR 状态且到期） */
    @Query("""
        SELECT * FROM server_op_tasks
        WHERE status IN ('PENDING', 'ERROR')
        AND nextAttemptAt <= :now
        AND opType != 'BACKUP_UPLOAD'
        ORDER BY nextAttemptAt ASC, createdAt ASC
        LIMIT :limit
    """)
    suspend fun getDueTasks(now: Long, limit: Int): List<ServerOpTaskEntity>

    /** CAS 认领：仅 PENDING/ERROR 状态时更新为 RUNNING */
    @Query("""
        UPDATE server_op_tasks
        SET status = 'RUNNING', updatedAt = :now
        WHERE id = :id AND status IN ('PENDING', 'ERROR')
    """)
    suspend fun claim(id: Long, now: Long = System.currentTimeMillis()): Int

    /** 标记成功 */
    @Query("UPDATE server_op_tasks SET status = 'SUCCESS', updatedAt = :now WHERE id = :id")
    suspend fun markSuccess(id: Long, now: Long = System.currentTimeMillis())

    /** 标记错误/失败 */
    @Query("""
        UPDATE server_op_tasks
        SET status = :status,
            attemptCount = :attemptCount,
            nextAttemptAt = :nextAttemptAt,
            lastError = :lastError,
            updatedAt = :now
        WHERE id = :id
    """)
    suspend fun markError(
        id: Long,
        status: ServerOpStatus,
        attemptCount: Int,
        nextAttemptAt: Long,
        lastError: String,
        now: Long = System.currentTimeMillis()
    )

    /** 重置超时的 RUNNING 任务为 PENDING */
    @Query("""
        UPDATE server_op_tasks
        SET status = 'PENDING', updatedAt = :now
        WHERE status = 'RUNNING'
        AND updatedAt < :staleBefore
    """)
    suspend fun resetStaleRunning(
        staleBefore: Long,
        now: Long = System.currentTimeMillis()
    ): Int

    // ===== UI 观察 =====

    /** 全部任务，按创建时间降序 */
    @Query("SELECT * FROM server_op_tasks ORDER BY createdAt DESC")
    fun getAllTasksFlow(): Flow<List<ServerOpTaskEntity>>

    /** 按状态过滤 */
    @Query("SELECT * FROM server_op_tasks WHERE status IN (:statuses) ORDER BY createdAt DESC")
    fun getTasksByStatusFlow(statuses: List<String>): Flow<List<ServerOpTaskEntity>>

    /** 按操作类型过滤 */
    @Query("SELECT * FROM server_op_tasks WHERE opType IN (:opTypes) ORDER BY createdAt DESC")
    fun getTasksByTypeFlow(opTypes: List<String>): Flow<List<ServerOpTaskEntity>>

    /** 组合过滤：状态 + 类型 */
    @Query("SELECT * FROM server_op_tasks WHERE status IN (:statuses) AND opType IN (:opTypes) ORDER BY createdAt DESC")
    fun getFilteredTasksFlow(statuses: List<String>, opTypes: List<String>): Flow<List<ServerOpTaskEntity>>

    // ===== 手动重试 =====

    /** 将 FAILED 状态重置为 PENDING（立即可执行） */
    @Query("""
        UPDATE server_op_tasks
        SET status = 'PENDING', attemptCount = 0, nextAttemptAt = :now, updatedAt = :now
        WHERE id = :id AND status = 'FAILED'
    """)
    suspend fun retryFailed(id: Long, now: Long = System.currentTimeMillis()): Int

    // ===== 清理 =====

    /** 删除指定时间之前的成功记录 */
    @Query("DELETE FROM server_op_tasks WHERE status = 'SUCCESS' AND updatedAt < :olderThan")
    suspend fun deleteOldSuccessTasks(olderThan: Long): Int

    // ===== 统计 =====

    /** 待处理任务数（不含 BACKUP_UPLOAD） */
    @Query("SELECT COUNT(*) FROM server_op_tasks WHERE status IN ('PENDING', 'ERROR', 'RUNNING') AND opType != 'BACKUP_UPLOAD'")
    suspend fun countPending(): Int

    /** 下次应执行的时间戳 */
    @Query("SELECT MIN(nextAttemptAt) FROM server_op_tasks WHERE status IN ('PENDING', 'ERROR') AND opType != 'BACKUP_UPLOAD'")
    suspend fun nextAttemptAt(): Long?
}
