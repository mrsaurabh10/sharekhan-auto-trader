package com.sharekhan.admin.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sharekhan.admin.data.repository.AdminRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed interface LoginEvent {
    object Success : LoginEvent
}

class LoginViewModel(
    private val repository: AdminRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events = _events

    init {
        viewModelScope.launch {
            repository.baseUrl.collectLatest { url ->
                _uiState.update { current ->
                    if (current.baseUrl.isNotBlank()) current else current.copy(baseUrl = url)
                }
            }
        }
        viewModelScope.launch {
            repository.lastUsername.collectLatest { username ->
                if (!username.isNullOrBlank()) {
                    _uiState.update { state ->
                        if (state.username.isNotBlank()) state else state.copy(username = username)
                    }
                }
            }
        }
    }

    fun onBaseUrlChanged(value: String) {
        _uiState.update { it.copy(baseUrl = value) }
    }

    fun onUsernameChanged(value: String) {
        _uiState.update { it.copy(username = value) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun login() {
        val baseUrl = _uiState.value.baseUrl.trim()
        val username = _uiState.value.username.trim()
        val password = _uiState.value.password
        if (baseUrl.isBlank() || username.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter base URL, username, and password") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                repository.login(baseUrl, username, password)
                _uiState.update { it.copy(isLoading = false, password = "") }
                _events.send(LoginEvent.Success)
            } catch (ex: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = ex.localizedMessage ?: "Login failed") }
            }
        }
    }

    companion object {
        fun factory(repository: AdminRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LoginViewModel(repository) as T
                }
            }
    }
}

