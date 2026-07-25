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
        repository.join(
            session.address.hostAddress.orEmpty(),
            session.port,
            session.sessionCode,
        )

    fun joinManual(address: String, sessionCode: String) =
        repository.join(address, sessionCode = sessionCode)

    fun joinByCode(sessionCode: String) {
        val session = state.value.sessions.firstOrNull { it.sessionCode == sessionCode }
        if (session != null) join(session) else repository.clientError(
            "No nearby session matches that code. Try Refresh or use the Host IP.",
        )
    }

    fun refresh() = repository.startDiscovery()
    fun disconnect() {
        repository.leaveClient()
        repository.startDiscovery()
    }

    override fun onCleared() {
        repository.leaveClient()
    }
}
