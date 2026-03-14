package com.sharekhan.admin.ui.state

sealed interface UiState<out T> {
    object Idle : UiState<Nothing>
    object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

inline fun <reified T> UiState<T>.getOrNull(): T? =
    if (this is UiState.Success<T>) data else null

