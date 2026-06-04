package com.kqstone.mtphotos.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kqstone.mtphotos.R
import com.kqstone.mtphotos.data.repository.AuthRepository
import com.kqstone.mtphotos.ui.util.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val isTestingUrl: Boolean = false,
    val urlTestMessage: UiText? = null,
    val error: UiText? = null,
    val isLoggedIn: Boolean = false
)

class SettingsViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        viewModelScope.launch {
            val prefs = authRepository.prefs()
            val savedUrl = prefs.getServerUrlSync()
            val savedUser = prefs.getUsernameSync()
            val savedPass = prefs.getPasswordSync()
            _uiState.value = _uiState.value.copy(
                serverUrl = savedUrl,
                username = savedUser,
                password = savedPass
            )
        }
    }

    fun resetLoginState() {
        _uiState.value = _uiState.value.copy(isLoggedIn = false, error = null)
    }

    fun onServerUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(
            serverUrl = value,
            urlTestMessage = null,
            error = null
        )
    }

    fun onUsernameChange(value: String) {
        _uiState.value = _uiState.value.copy(username = value, error = null)
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun testUrl() {
        val state = _uiState.value
        val url = state.serverUrl.trim()
        if (url.isBlank()) {
            _uiState.value = state.copy(urlTestMessage = UiText.StringResource(R.string.please_fill_server_url))
            return
        }

        _uiState.value = state.copy(isTestingUrl = true, error = null)
        viewModelScope.launch {
            val result = authRepository.testServerUrl(url, state.username, state.password)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isTestingUrl = false,
                        urlTestMessage = UiText.StringResource(R.string.test_connection_success)
                    )
                },
                onFailure = { e ->
                    val msg = e.message
                    val uiMsg = if (msg.isNullOrBlank()) {
                        UiText.StringResource(R.string.test_connection_failed)
                    } else {
                        UiText.DynamicString(msg)
                    }
                    _uiState.value = _uiState.value.copy(isTestingUrl = false, urlTestMessage = uiMsg)
                }
            )
        }
    }

    fun connect() {
        val state = _uiState.value
        val serverUrl = state.serverUrl.trim()
        if (serverUrl.isBlank()) {
            _uiState.value = state.copy(error = UiText.StringResource(R.string.please_fill_server_url))
            return
        }
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(error = UiText.StringResource(R.string.please_fill_username_password))
            return
        }

        _uiState.value = state.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val result = authRepository.login(
                serverUrl = serverUrl,
                username = state.username,
                password = state.password
            )
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

    class Factory(
        private val authRepository: AuthRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(authRepository) as T
        }
    }
}
