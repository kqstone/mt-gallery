package com.kqstone.mtphotos.ui.oplog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.data.local.db.ServerOpStatus
import com.kqstone.mtphotos.data.local.db.ServerOpTaskEntity
import com.kqstone.mtphotos.data.local.db.ServerOpType
import com.kqstone.mtphotos.data.repository.ServerOpTaskRepository
import com.kqstone.mtphotos.worker.BackupScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class OpLogViewModel(
    private val serverOpTaskRepository: ServerOpTaskRepository,
    private val triggerServerOp: () -> Unit
) : ViewModel() {

    data class UiState(
        val tasks: List<OpLogItem> = emptyList(),
        val statusFilter: ServerOpStatus? = null,
        val typeFilter: ServerOpType? = null,
        val isLoading: Boolean = true
    )

    data class OpLogItem(
        val id: Long,
        val opType: ServerOpType,
        val status: ServerOpStatus,
        val mediaFileName: String,
        val mediaMd5: String,
        val mediaCloudId: Double?,
        val opDescription: String,
        val statusText: String,
        val attemptCount: Int,
        val nextAttemptAt: Long,
        val canRetry: Boolean,
        val createdAt: Long,
        val updatedAt: Long
    )

    private val _statusFilter = MutableStateFlow<ServerOpStatus?>(null)
    private val _typeFilter = MutableStateFlow<ServerOpType?>(null)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(_statusFilter, _typeFilter) { status, type -> status to type }
                .collectLatest { (status, type) ->
                    val statuses = status?.let { setOf(it) }
                    val types = type?.let { setOf(it) }
                    serverOpTaskRepository.observeFilteredTasks(statuses, types)
                        .collectLatest { tasks ->
                            _uiState.value = UiState(
                                tasks = tasks.map { it.toOpLogItem() },
                                statusFilter = status,
                                typeFilter = type,
                                isLoading = false
                            )
                        }
                }
        }
    }

    fun setStatusFilter(status: ServerOpStatus?) {
        _statusFilter.value = status
    }

    fun setTypeFilter(type: ServerOpType?) {
        _typeFilter.value = type
    }

    fun retryTask(taskId: Long) {
        viewModelScope.launch {
            serverOpTaskRepository.retryTask(taskId)
            triggerServerOp()
        }
    }

    fun clearSuccessLogs() {
        viewModelScope.launch {
            serverOpTaskRepository.cleanupOldSuccessTasks(retainDays = 0)
        }
    }

    private fun ServerOpTaskEntity.toOpLogItem(): OpLogItem {
        return OpLogItem(
            id = id,
            opType = opType,
            status = status,
            mediaFileName = mediaFileName,
            mediaMd5 = mediaMd5,
            mediaCloudId = mediaCloudId,
            opDescription = opType.toDescription(params),
            statusText = formatStatus(status, attemptCount, nextAttemptAt),
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            canRetry = status == ServerOpStatus.FAILED,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    companion object {
        fun ServerOpType.toDescription(params: String): String {
            return when (this) {
                ServerOpType.CLOUD_DELETE -> "云端删除"
                ServerOpType.FAVORITE -> {
                    val isFav = params.contains("true", ignoreCase = true)
                    if (isFav) "收藏" else "取消收藏"
                }
                ServerOpType.RENAME_PERSON -> "修改人物名称"
                ServerOpType.TAG -> {
                    val isAdd = params.contains("\"isAdd\":true") || params.contains("\"isAdd\": true")
                    if (isAdd) "添加标签" else "移除标签"
                }
                ServerOpType.HIDE -> {
                    val isHide = params.contains("\"isHide\":true") || params.contains("\"isHide\": true")
                    if (isHide) "隐藏" else "取消隐藏"
                }
                ServerOpType.BACKUP_UPLOAD -> "备份上传"
            }
        }

        fun formatStatus(status: ServerOpStatus, attemptCount: Int, nextAttemptAt: Long): String {
            return when (status) {
                ServerOpStatus.SUCCESS -> "成功"
                ServerOpStatus.PENDING -> "待执行"
                ServerOpStatus.RUNNING -> "执行中"
                ServerOpStatus.ERROR -> {
                    val remaining = nextAttemptAt - System.currentTimeMillis()
                    val retryText = if (remaining > 0) {
                        val minutes = remaining / 60_000
                        val hours = minutes / 60
                        when {
                            hours > 0 -> "${hours}小时后重试"
                            minutes > 0 -> "${minutes}分钟后重试"
                            else -> "即将重试"
                        }
                    } else {
                        "等待重试"
                    }
                    "错误(${attemptCount}次) · $retryText"
                }
                ServerOpStatus.FAILED -> "失败(${attemptCount}次)"
            }
        }

        fun opTypeDisplayName(type: ServerOpType): String {
            return when (type) {
                ServerOpType.CLOUD_DELETE -> "删除"
                ServerOpType.FAVORITE -> "收藏"
                ServerOpType.RENAME_PERSON -> "改名"
                ServerOpType.TAG -> "标签"
                ServerOpType.HIDE -> "隐藏"
                ServerOpType.BACKUP_UPLOAD -> "上传"
            }
        }

        fun statusDisplayName(status: ServerOpStatus): String {
            return when (status) {
                ServerOpStatus.SUCCESS -> "成功"
                ServerOpStatus.PENDING -> "待执行"
                ServerOpStatus.RUNNING -> "进行中"
                ServerOpStatus.ERROR -> "错误中"
                ServerOpStatus.FAILED -> "失败"
            }
        }
    }

    class Factory(
        private val serverOpTaskRepository: ServerOpTaskRepository,
        private val triggerServerOp: () -> Unit
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return OpLogViewModel(serverOpTaskRepository, triggerServerOp) as T
        }
    }
}
