package com.syncsound.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.syncsound.app.data.SessionRepository

/**
 * Small typed factory used instead of service locators inside ViewModels.
 */
class HostViewModelFactory(private val repository: SessionRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HostViewModel(repository) as T
}

class ClientViewModelFactory(private val repository: SessionRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ClientViewModel(repository) as T
}
