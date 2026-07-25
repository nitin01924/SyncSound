package com.syncsound.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.syncsound.app.data.SessionRepository

/**
 * Maps Host UI actions to the session repository and exposes read-only state.
 */
class HostViewModel(private val repository: SessionRepository) : ViewModel() {
    val state = repository.hostState

    init {
        repository.createSession()
    }

    fun approve(id: String) = repository.approve(id)
    fun reject(id: String) = repository.reject(id)
    fun selectAudio(uri: Uri) = repository.selectAudio(uri)
    fun play() = repository.hostPlay()
    fun pause() = repository.hostPause()
    fun stop() = repository.hostStop()

    override fun onCleared() {
        repository.stopHosting()
    }
}
