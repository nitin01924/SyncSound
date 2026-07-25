package com.syncsound.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.syncsound.app.audio.ClientAudioReceiver
import com.syncsound.app.audio.HostAudioStreamer
import com.syncsound.app.model.ClientConnectionState
import com.syncsound.app.model.ClientUiState
import com.syncsound.app.model.HostUiState
import com.syncsound.app.model.PlaybackState
import com.syncsound.app.network.ClientCommand
import com.syncsound.app.network.ClientControlConnection
import com.syncsound.app.network.HostControlServer
import com.syncsound.app.network.Protocol
import com.syncsound.app.network.SessionDiscovery
import com.syncsound.app.service.SessionForegroundService
import com.syncsound.app.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Single source of truth for the active Host or Client session. It coordinates
 * Android services while exposing immutable screen state to ViewModels.
 */
class SessionRepository(
    private val context: Context,
    private val discovery: SessionDiscovery,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hostServer = HostControlServer()
    private val hostAudio = HostAudioStreamer(context)
    private val clientControl = ClientControlConnection()
    private val clientAudio = ClientAudioReceiver()
    private val _hostState = MutableStateFlow(HostUiState())
    val hostState: StateFlow<HostUiState> = _hostState
    private val _clientState = MutableStateFlow(ClientUiState())
    val clientState: StateFlow<ClientUiState> = _clientState

    init {
        scope.launch {
            hostServer.devices.collectLatest {
                _hostState.value = _hostState.value.copy(devices = it)
            }
        }
        scope.launch {
            hostAudio.state.collectLatest {
                _hostState.value = _hostState.value.copy(playbackState = it)
            }
        }
        scope.launch {
            discovery.sessions.collectLatest {
                _clientState.value = _clientState.value.copy(sessions = it)
            }
        }
        scope.launch {
            clientControl.state.collectLatest {
                _clientState.value = _clientState.value.copy(connectionState = it)
            }
        }
        scope.launch {
            clientAudio.state.collectLatest {
                _clientState.value = _clientState.value.copy(playbackState = it)
            }
        }
        scope.launch {
            clientControl.commands.collect { command ->
                when (command) {
                    is ClientCommand.Format ->
                        clientAudio.configure(command.sampleRate, command.channels)
                    is ClientCommand.Play -> clientAudio.playAt(command.localPresentationTimeNanos)
                    is ClientCommand.Pause ->
                        clientAudio.pauseAt(command.localPresentationTimeNanos)
                    ClientCommand.Stop -> clientAudio.stopPlayback()
                }
            }
        }
    }

    fun createSession() {
        leaveClient()
        SessionForegroundService.start(context)
        hostServer.start()
        discovery.advertise("SyncSound ${android.os.Build.MODEL}", Protocol.CONTROL_PORT)
        _hostState.value = HostUiState(
            isHosting = true,
            hostAddress = NetworkUtils.localIpv4Address(),
            message = "Session ready",
        )
    }

    fun stopHosting() {
        hostAudio.stop()
        hostServer.stop()
        discovery.stopAdvertising()
        SessionForegroundService.stop(context)
        _hostState.value = HostUiState()
    }

    fun approve(deviceId: String) = hostServer.approve(deviceId)
    fun reject(deviceId: String) = hostServer.reject(deviceId)

    fun selectAudio(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        hostAudio.select(uri)
        _hostState.value = _hostState.value.copy(
            selectedTrack = displayName(uri),
            playbackState = PlaybackState.IDLE,
            message = null,
        )
    }

    fun hostPlay() {
        hostAudio.play(object : HostAudioStreamer.Listener {
            override fun onFormat(sampleRate: Int, channels: Int) =
                hostServer.broadcastFormat(sampleRate, channels)

            override fun onStartScheduled(hostTimeNanos: Long) =
                hostServer.broadcastPlayback(Protocol.Message.PLAY, hostTimeNanos)

            override fun approvedTargets(): List<InetAddress> = hostServer.approvedAddresses()

            override fun onFailure(message: String) {
                _hostState.value = _hostState.value.copy(message = message)
                hostServer.broadcastPlayback(Protocol.Message.STOP)
            }

            override fun onCompleted() =
                hostServer.broadcastPlayback(Protocol.Message.STOP)
        })
    }

    fun hostPause() {
        val pauseTime = android.os.SystemClock.elapsedRealtimeNanos() + 300_000_000L
        hostAudio.pauseAt(pauseTime)
        hostServer.broadcastPlayback(Protocol.Message.PAUSE, pauseTime)
    }

    fun hostStop() {
        hostAudio.stop()
        hostServer.broadcastPlayback(Protocol.Message.STOP)
    }

    fun startDiscovery() {
        stopHosting()
        SessionForegroundService.start(context)
        discovery.startDiscovery()
        _clientState.value = ClientUiState(connectionState = ClientConnectionState.DISCOVERING)
    }

    fun join(address: String, port: Int = Protocol.CONTROL_PORT) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val host = InetAddress.getByName(address.trim())
                clientAudio.start(host)
                discovery.stopDiscovery()
                clientControl.connect(host, port)
                _clientState.value = _clientState.value.copy(
                    connectedHost = host.hostAddress,
                    message = null,
                )
            }.onFailure {
                _clientState.value = _clientState.value.copy(
                    connectionState = ClientConnectionState.ERROR,
                    message = "Could not resolve that Host address",
                )
            }
        }
    }

    fun leaveClient() {
        clientControl.disconnect()
        clientAudio.close()
        discovery.stopDiscovery()
        SessionForegroundService.stop(context)
        _clientState.value = ClientUiState()
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        return uri.lastPathSegment ?: "Selected MP3"
    }

    fun close() {
        stopHosting()
        leaveClient()
        scope.cancel()
    }
}
