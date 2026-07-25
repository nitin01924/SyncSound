package com.syncsound.app.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.syncsound.app.audio.ClientAudioReceiver
import com.syncsound.app.audio.HostAudioStreamer
import com.syncsound.app.audio.SyncTonePlayer
import com.syncsound.app.model.ClientConnectionState
import com.syncsound.app.model.ClientUiState
import com.syncsound.app.model.DeviceStatus
import com.syncsound.app.model.HostUiState
import com.syncsound.app.model.PlaybackState
import com.syncsound.app.logging.AppLog
import com.syncsound.app.logging.LogArea
import com.syncsound.app.network.ClientCommand
import com.syncsound.app.network.ClientControlConnection
import com.syncsound.app.network.HostControlServer
import com.syncsound.app.network.NetworkMonitor
import com.syncsound.app.network.Protocol
import com.syncsound.app.network.SessionDiscovery
import com.syncsound.app.service.SessionForegroundService
import com.syncsound.app.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.InetAddress
import kotlin.random.Random

/**
 * Single source of truth for the active Host or Client session. It coordinates
 * Android services while exposing immutable screen state to ViewModels.
 */
class SessionRepository(
    private val context: Context,
    private val discovery: SessionDiscovery,
) {
    private val _hostState = MutableStateFlow(HostUiState())
    val hostState: StateFlow<HostUiState> = _hostState
    private val _clientState = MutableStateFlow(ClientUiState())
    val clientState: StateFlow<ClientUiState> = _clientState
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        AppLog.e(LogArea.CRASH, "repository_coroutine_failed", error)
        _hostState.value = _hostState.value.copy(message = "An internal session error occurred")
        _clientState.value = _clientState.value.copy(message = "An internal session error occurred")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private val hostServer = HostControlServer()
    private val hostAudio = HostAudioStreamer(context)
    private val clientControl = ClientControlConnection()
    private val clientAudio = ClientAudioReceiver()
    private val syncTone = SyncTonePlayer()
    private val networkMonitor = NetworkMonitor(context)
    private var playlist: List<Uri> = emptyList()
    private var currentTrackIndex = 0
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
            clientControl.quality.collectLatest {
                _clientState.value = _clientState.value.copy(connectionQuality = it)
            }
        }
        scope.launch {
            clientControl.errors.collect {
                _clientState.value = _clientState.value.copy(message = it)
            }
        }
        scope.launch {
            hostServer.errors.collect {
                _hostState.value = _hostState.value.copy(message = it)
            }
        }
        scope.launch {
            networkMonitor.available.collect { available ->
                if (!available) {
                    if (_hostState.value.isHosting) {
                        _hostState.value = _hostState.value.copy(
                            message = "Network unavailable. Session will recover when it returns.",
                        )
                    }
                    if (_clientState.value.connectionState !in listOf(
                            ClientConnectionState.IDLE,
                            ClientConnectionState.DISCOVERING,
                        )
                    ) {
                        _clientState.value = _clientState.value.copy(
                            message = "Network unavailable. Reconnecting when it returns.",
                        )
                    }
                } else if (_hostState.value.isHosting) {
                    val address = NetworkUtils.localIpv4Address()
                    _hostState.value = _hostState.value.copy(hostAddress = address)
                    discovery.stopAdvertising()
                    discovery.advertise(
                        "SyncSound ${_hostState.value.sessionCode}",
                        Protocol.CONTROL_PORT,
                    )
                }
            }
        }
        scope.launch {
            clientAudio.state.collectLatest {
                _clientState.value = _clientState.value.copy(playbackState = it)
            }
        }
        scope.launch {
            clientAudio.errors.collectLatest { error ->
                if (error != null) {
                    _clientState.value = _clientState.value.copy(message = error)
                }
            }
        }
        scope.launch {
            clientControl.commands.collect { command ->
                runCatching {
                    when (command) {
                        is ClientCommand.HostInfo -> _clientState.value = _clientState.value.copy(
                            hostName = command.hostName,
                        )
                        is ClientCommand.Format ->
                            clientAudio.configure(command.sampleRate, command.channels)
                        is ClientCommand.Play -> clientAudio.playAt(command.localPresentationTimeNanos)
                        is ClientCommand.Pause ->
                            clientAudio.pauseAt(command.localPresentationTimeNanos)
                        is ClientCommand.TestTone -> syncTone.playAt(
                            command.localPresentationTimeNanos,
                        ) { success ->
                            clientControl.sendTestResult(command.testId, success)
                        }
                        is ClientCommand.Removed -> {
                            clientAudio.close()
                            _clientState.value = _clientState.value.copy(
                                connectionState = ClientConnectionState.ERROR,
                                message = command.reason,
                            )
                        }
                        ClientCommand.Stop -> clientAudio.stopPlayback()
                    }
                }.onFailure {
                    AppLog.e(LogArea.AUDIO, "client_command_failed", it, "command" to command)
                    _clientState.value = _clientState.value.copy(
                        message = "This phone could not prepare audio: ${it.message.orEmpty()}",
                    )
                }
            }
        }
    }

    fun createSession() {
        leaveClient()
        val foregroundStarted = SessionForegroundService.start(context)
        val hostName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val sessionCode = Random.nextInt(100_000, 1_000_000).toString()
        hostServer.start(hostName, sessionCode)
        discovery.advertise("SyncSound $sessionCode", Protocol.CONTROL_PORT)
        _hostState.value = HostUiState(
            isHosting = true,
            hostAddress = NetworkUtils.localIpv4Address(),
            hostName = hostName,
            sessionCode = sessionCode,
            message = if (foregroundStarted) "Session ready" else
                "Session ready, but background playback is unavailable",
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
    fun remove(deviceId: String) = hostServer.remove(deviceId)

    fun testSync() {
        val approved = hostServer.devices.value.count { it.status == DeviceStatus.APPROVED }
        if (approved == 0) {
            _hostState.value = _hostState.value.copy(message = "Connect and approve a device first")
            return
        }
        val testId = System.nanoTime()
        val startTime = android.os.SystemClock.elapsedRealtimeNanos() +
            Protocol.START_LEAD_TIME_MS * 1_000_000
        hostServer.startSyncTest(testId, startTime)
        syncTone.playAt(startTime) { success ->
            _hostState.value = _hostState.value.copy(
                message = if (success) "Sync test played" else "Host could not play the sync test",
            )
        }
    }

    fun selectAudio(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure {
                AppLog.w(
                    LogArea.PERMISSION,
                    "persist_media_permission_failed",
                    it,
                    "uri" to uri,
                )
            }
        }
        playlist = uris
        currentTrackIndex = 0
        hostAudio.select(playlist[currentTrackIndex])
        _hostState.value = _hostState.value.copy(
            selectedTrack = displayName(playlist[currentTrackIndex]),
            queuePosition = 1,
            queueSize = playlist.size,
            playbackState = PlaybackState.IDLE,
            message = null,
        )
    }

    fun hostPlay() {
        AppLog.i(LogArea.AUDIO, "play_requested", "track" to _hostState.value.selectedTrack)
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
        AppLog.i(LogArea.AUDIO, "pause_requested")
        val pauseTime = android.os.SystemClock.elapsedRealtimeNanos() + 300_000_000L
        hostAudio.pauseAt(pauseTime)
        hostServer.broadcastPlayback(Protocol.Message.PAUSE, pauseTime)
    }

    fun hostStop() {
        AppLog.i(LogArea.AUDIO, "stop_requested")
        hostAudio.stop()
        hostServer.broadcastPlayback(Protocol.Message.STOP)
    }

    fun hostSkip() {
        if (playlist.size < 2) {
            _hostState.value = _hostState.value.copy(message = "Add more than one track to use Skip")
            return
        }
        hostAudio.stop()
        hostServer.broadcastPlayback(Protocol.Message.STOP)
        currentTrackIndex = (currentTrackIndex + 1) % playlist.size
        AppLog.i(LogArea.AUDIO, "skip_requested", "queueIndex" to currentTrackIndex)
        val uri = playlist[currentTrackIndex]
        hostAudio.select(uri)
        _hostState.value = _hostState.value.copy(
            selectedTrack = displayName(uri),
            queuePosition = currentTrackIndex + 1,
            playbackState = PlaybackState.IDLE,
            message = null,
        )
        hostPlay()
    }

    fun startDiscovery() {
        stopHosting()
        val foregroundStarted = SessionForegroundService.start(context)
        discovery.startDiscovery()
        _clientState.value = ClientUiState(
            connectionState = ClientConnectionState.DISCOVERING,
            message = if (foregroundStarted) null else
                "Keep SyncSound open; background service could not start.",
        )
    }

    fun join(
        address: String,
        port: Int = Protocol.CONTROL_PORT,
        sessionCode: String = "",
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val host = InetAddress.getByName(address.trim())
                clientAudio.start(host)
                discovery.stopDiscovery()
                clientControl.connect(host, port, sessionCode)
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

    fun clientError(message: String) {
        _clientState.value = _clientState.value.copy(message = message)
    }

    fun permissionDenied(permissionName: String) {
        AppLog.w(LogArea.PERMISSION, "permission_denied", null, "permission" to permissionName)
        val message = when (permissionName) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES ->
                "Nearby-device permission denied. Automatic discovery may not work; use manual IP."
            android.Manifest.permission.POST_NOTIFICATIONS ->
                "Notifications denied. Android may hide the active-session notification."
            else -> "A requested permission was denied."
        }
        _clientState.value = _clientState.value.copy(message = message)
        _hostState.value = _hostState.value.copy(message = message)
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        return uri.lastPathSegment ?: "Selected audio"
    }

    fun close() {
        stopHosting()
        leaveClient()
        scope.cancel()
        syncTone.close()
        networkMonitor.close()
    }
}
