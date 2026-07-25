package com.syncsound.app.ui

import androidx.lifecycle.ViewModel
import com.syncsound.app.data.SessionRepository
import com.syncsound.app.model.DiscoveredSession

/**
 * Owns discovery and joining from the Client screen.
 */
class ClientViewModel(private val repository: SessionRepository) : ViewModel() {
    val state = repository.clientState

    init {
        repository.startDiscovery()
    }

    fun join(session: DiscoveredSession) =
        repository.join(session.address.hostAddress.orEmpty(), session.port)

    fun joinManual(address: String) = repository.join(address)
    fun refresh() = repository.startDiscovery()

    override fun onCleared() {
        repository.leaveClient()
    }
}
