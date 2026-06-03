package com.kqstone.mtphotos.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.repository.AuthRepository
import com.kqstone.mtphotos.ui.util.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsUiState(
    val primaryServerUrl: String = "",
    val secondaryServerUrl: String = "",
    val activeServerUrl: String = "",
    val username: String = "",
    val password: String = "",
    val credentialsEditable: Boolean = true,
    val isLoading: Boolean = false,
    val testingUrlSlot: ServerUrlSlot? = null,
    val primaryUrlTestMessage: UiText? = null,
    val secondaryUrlTestMessage: UiText? = null,
    val error: UiText? = null,
    val isLoggedIn: Boolean = false
)

enum class ServerUrlSlot {
    PRIMARY,
    SECONDARY
}

class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val credentialsEditable: Boolean = true
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(credentialsEditable = credentialsEditable)
    )
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        viewModelScope.launch {
            val prefs = authRepository.prefs()
            val savedUrl = prefs.getServerUrlSync()
            val savedPrimaryUrl = prefs.getPrimaryServerUrlSync().ifBlank { savedUrl }
            val savedSecondaryUrl = prefs.getSecondaryServerUrlSync()
            val savedUser = prefs.getUsernameSync()
            val savedPass = prefs.getPasswordSync()
            _uiState.value = _uiState.value.copy(
                primaryServerUrl = savedPrimaryUrl,
                secondaryServerUrl = savedSecondaryUrl,
                activeServerUrl = savedUrl.ifBlank { savedPrimaryUrl },
                username = savedUser,
                password = savedPass,
                credentialsEditable = credentialsEditable
            )
        }
    }

    fun resetLoginState() {
        _uiState.value = _uiState.value.copy(isLoggedIn = false, error = null)
    }

    fun onPrimaryServerUrlChange(value: String) {
        val activeUrl = if (_uiState.value.activeServerUrl == _uiState.value.primaryServerUrl) {
            value
        } else {
            _uiState.value.activeServerUrl
        }
        _uiState.value = _uiState.value.copy(
            primaryServerUrl = value,
            activeServerUrl = activeUrl,
            primaryUrlTestMessage = null,
            error = null
        )
    }

    fun onSecondaryServerUrlChange(value: String) {
        val activeUrl = if (_uiState.value.activeServerUrl == _uiState.value.secondaryServerUrl) {
            value
        } else {
            _uiState.value.activeServerUrl
        }
        _uiState.value = _uiState.value.copy(
            secondaryServerUrl = value,
            activeServerUrl = activeUrl,
            secondaryUrlTestMessage = null,
            error = null
        )
    }

    fun selectActiveUrl(slot: ServerUrlSlot) {
        val state = _uiState.value
        _uiState.value = state.copy(
            activeServerUrl = when (slot) {
                ServerUrlSlot.PRIMARY -> state.primaryServerUrl
                ServerUrlSlot.SECONDARY -> state.secondaryServerUrl
            },
            error = null
        )
    }

    fun onUsernameChange(value: String) {
        if (!_uiState.value.credentialsEditable) return
        _uiState.value = _uiState.value.copy(username = value, error = null)
    }

    fun onPasswordChange(value: String) {
        if (!_uiState.value.credentialsEditable) return
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun testUrl(slot: ServerUrlSlot) {
        val state = _uiState.value
        val url = when (slot) {
            ServerUrlSlot.PRIMARY -> state.primaryServerUrl
            ServerUrlSlot.SECONDARY -> state.secondaryServerUrl
        }.trim()
        if (url.isBlank()) {
            setTestMessage(slot, UiText.StringResource(R.string.please_fill_server_url))
            return
        }

        _uiState.value = state.copy(testingUrlSlot = slot, error = null)
        viewModelScope.launch {
            val result = authRepository.testServerUrl(url, state.username, state.password)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(testingUrlSlot = null)
                    setTestMessage(slot, UiText.StringResource(R.string.test_connection_success))
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(testingUrlSlot = null)
                    val msg = e.message
                    val uiMsg = if (msg.isNullOrBlank()) {
                        UiText.StringResource(R.string.test_connection_failed)
                    } else {
                        UiText.DynamicString(msg)
                    }
                    setTestMessage(slot, uiMsg)
                }
            )
        }
    }

    fun connect() {
        val state = _uiState.value
        val activeUrl = state.activeServerUrl.trim()
        val primaryUrl = state.primaryServerUrl.trim()
        val secondaryUrl = state.secondaryServerUrl.trim()
        if (activeUrl.isBlank() || primaryUrl.isBlank()) {
            _uiState.value = state.copy(error = UiText.StringResource(R.string.please_fill_primary_url))
            return
        }
        if (secondaryUrl.isNotBlank() && primaryUrl.trimEnd('/') == secondaryUrl.trimEnd('/')) {
            _uiState.value = state.copy(error = UiText.StringResource(R.string.primary_secondary_cannot_be_same))
            return
        }
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(error = UiText.StringResource(R.string.please_fill_username_password))
            return
        }

        _uiState.value = state.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val result = if (state.credentialsEditable) {
                authRepository.login(
                    serverUrl = activeUrl,
                    username = state.username,
                    password = state.password,
                    primaryServerUrl = primaryUrl,
                    secondaryServerUrl = secondaryUrl
                )
            } else {
                authRepository.loginWithSavedCredentials(
                    serverUrl = activeUrl,
                    primaryServerUrl = primaryUrl,
                    secondaryServerUrl = secondaryUrl
                )
            }
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isLoading = false, isLoggedIn = true)
                },
                onFailure = { e ->
                    val msg = e.message
                    val uiMsg = if (msg.isNullOrBlank()) {
                        UiText.StringResource(R.string.connection_failed)
                    } else {
                        UiText.DynamicString(msg)
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = uiMsg
                    )
                }
            )
        }
    }

    private fun setTestMessage(slot: ServerUrlSlot, message: UiText?) {
        _uiState.value = when (slot) {
            ServerUrlSlot.PRIMARY -> _uiState.value.copy(primaryUrlTestMessage = message)
            ServerUrlSlot.SECONDARY -> _uiState.value.copy(secondaryUrlTestMessage = message)
        }
    }

    class Factory(
        private val authRepository: AuthRepository,
        private val credentialsEditable: Boolean = true
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(authRepository, credentialsEditable) as T
        }
    }
}
