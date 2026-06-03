package com.kqstone.mtphotos.ui.oplog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.local.db.ServerOpStatus
import com.kqstone.mtphotos.data.local.db.ServerOpTaskEntity
import com.kqstone.mtphotos.data.local.db.ServerOpType
import com.kqstone.mtphotos.data.repository.ServerOpTaskRepository
import com.kqstone.mtphotos.ui.util.UiText
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
        val opDescription: UiText,
        val statusText: UiText,
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
        fun ServerOpType.toDescription(params: String): UiText {
            return when (this) {
                ServerOpType.CLOUD_DELETE -> UiText.StringResource(R.string.op_desc_delete)
                ServerOpType.FAVORITE -> {
                    val isFav = params.contains("true", ignoreCase = true)
                    if (isFav) UiText.StringResource(R.string.op_desc_favorite)
                    else UiText.StringResource(R.string.op_desc_unfavorite)
                }
                ServerOpType.RENAME_PERSON -> UiText.StringResource(R.string.op_desc_rename_person)
                ServerOpType.TAG -> {
                    val isAdd = params.contains("\"isAdd\":true") || params.contains("\"isAdd\": true")
                    if (isAdd) UiText.StringResource(R.string.op_desc_tag_add)
                    else UiText.StringResource(R.string.op_desc_tag_remove)
                }
                ServerOpType.HIDE -> {
                    val isHide = params.contains("\"isHide\":true") || params.contains("\"isHide\": true")
                    if (isHide) UiText.StringResource(R.string.op_desc_hide)
                    else UiText.StringResource(R.string.op_desc_unhide)
                }
                ServerOpType.BACKUP_UPLOAD -> UiText.StringResource(R.string.op_desc_backup)
            }
        }

        fun formatStatus(status: ServerOpStatus, attemptCount: Int, nextAttemptAt: Long): UiText {
            return when (status) {
                ServerOpStatus.SUCCESS -> UiText.StringResource(R.string.status_success)
                ServerOpStatus.PENDING -> UiText.StringResource(R.string.status_pending)
                ServerOpStatus.RUNNING -> UiText.StringResource(R.string.status_running)
                ServerOpStatus.ERROR -> {
                    val remaining = nextAttemptAt - System.currentTimeMillis()
                    val retryTextRes = if (remaining > 0) {
                        val minutes = remaining / 60_000
                        val hours = minutes / 60
                        when {
                            hours > 0 -> UiText.StringResource(R.string.retry_in_hours, hours)
                            minutes > 0 -> UiText.StringResource(R.string.retry_in_minutes, minutes)
                            else -> UiText.StringResource(R.string.retry_soon)
                        }
                    } else {
                        UiText.StringResource(R.string.waiting_retry)
                    }
                    UiText.StringResource(R.string.status_error_format, attemptCount, retryTextRes)
                }
                ServerOpStatus.FAILED -> UiText.StringResource(R.string.status_failed_format, attemptCount)
            }
        }

        fun opTypeDisplayName(type: ServerOpType): Int {
            return when (type) {
                ServerOpType.CLOUD_DELETE -> R.string.op_type_delete
                ServerOpType.FAVORITE -> R.string.op_type_favorite
                ServerOpType.RENAME_PERSON -> R.string.op_type_rename_person
                ServerOpType.TAG -> R.string.op_type_tag
                ServerOpType.HIDE -> R.string.op_type_hide
                ServerOpType.BACKUP_UPLOAD -> R.string.op_type_backup
            }
        }

        fun statusDisplayName(status: ServerOpStatus): Int {
            return when (status) {
                ServerOpStatus.SUCCESS -> R.string.status_success
                ServerOpStatus.PENDING -> R.string.status_pending
                ServerOpStatus.RUNNING -> R.string.status_running_simple
                ServerOpStatus.ERROR -> R.string.status_error
                ServerOpStatus.FAILED -> R.string.status_failed
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
