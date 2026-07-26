package com.syncsound.app.ui

import android.content.Intent
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
    fun remove(id: String) = repository.remove(id)
    fun testSync() = repository.testSync()
    fun selectAudio(uris: List<Uri>) = repository.selectAudio(uris)
    fun play() = repository.hostPlay()
    fun pause() = repository.hostPause()
    fun stop() = repository.hostStop()
    fun skip() = repository.hostSkip()
    fun setVolume(value: Float) = repository.setHostVolume(value)
    fun startSystemAudioCapture(resultCode: Int, data: Intent?) =
        repository.startSystemAudioCapture(resultCode, data)
    fun permissionDenied(permission: String) = repository.permissionDenied(permission)
    fun disconnect() = repository.stopHosting()

    override fun onCleared() {
        repository.stopHosting()
    }
}
