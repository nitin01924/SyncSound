package com.syncsound.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.syncsound.app.audio.ClientAudioReceiver
import com.syncsound.app.audio.HostAudioStreamer
import com.syncsound.app.audio.PlaybackCaptureStreamer
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    private val playbackCapture = PlaybackCaptureStreamer(context)
    private val clientControl = ClientControlConnection()
    private val clientAudio = ClientAudioReceiver(context)
    private val syncTone = SyncTonePlayer(context)
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
            clientControl.hostMinusClientEstimate.collectLatest { estimate ->
                estimate?.let(clientAudio::updateClockEstimate)
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
                        is ClientCommand.Play -> clientAudio.playAt(
                            command.localPresentationTimeNanos,
                            command.hostPresentationTimeNanos,
                        )
                        is ClientCommand.Pause ->
                            clientAudio.pauseAt(command.localPresentationTimeNanos)
                        is ClientCommand.TestTone -> syncTone.playAt(
                            command.localPresentationTimeNanos,
                        ) { success ->
                            clientControl.sendTestResult(command.testId, success)
                        }
                        is ClientCommand.Volume -> clientAudio.setVolume(command.value)
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
                    clientAudio.stopPlayback()
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
            systemCaptureSupported = playbackCapture.supported,
            message = if (foregroundStarted) "Session ready" else
                "Session ready, but background playback is unavailable",
        )
    }

    fun stopHosting() {
        hostAudio.stop()
        playbackCapture.stop()
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
        playbackCapture.stop()
        _hostState.value = _hostState.value.copy(systemCaptureActive = false)
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
        playbackCapture.stop()
        hostServer.broadcastPlayback(Protocol.Message.STOP)
        _hostState.value = _hostState.value.copy(systemCaptureActive = false)
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

    fun setHostVolume(value: Float) {
        val volume = value.coerceIn(0f, 1f)
        hostAudio.setVolume(volume)
        hostServer.broadcastVolume(volume)
        _hostState.value = _hostState.value.copy(volume = volume)
    }

    fun startSystemAudioCapture(resultCode: Int, data: Intent?) {
        if (!playbackCapture.supported) {
            _hostState.value = _hostState.value.copy(
                message = "System audio capture requires Android 10 or newer.",
            )
            return
        }
        if (data == null) {
            _hostState.value = _hostState.value.copy(
                message = "Android did not grant playback-capture permission.",
            )
            return
        }
        hostAudio.stop()
        hostServer.broadcastPlayback(Protocol.Message.STOP)
        scope.launch {
            if (!SessionForegroundService.enableMediaProjection(context)) {
                _hostState.value = _hostState.value.copy(
                    message = "Could not start Android's playback-capture service.",
                )
                return@launch
            }
            val ready = runCatching {
                withTimeout(3_000) {
                    SessionForegroundService.projectionServiceReady.first { it }
                }
            }.isSuccess
            if (!ready) {
                _hostState.value = _hostState.value.copy(
                    message = "Android did not allow the playback-capture service to start.",
                )
                return@launch
            }
            playbackCapture.start(
                resultCode,
                data,
                object : PlaybackCaptureStreamer.Listener {
                    override fun onFormat(sampleRate: Int, channels: Int) =
                        hostServer.broadcastFormat(sampleRate, channels)

                    override fun onStartScheduled(hostTimeNanos: Long) =
                        hostServer.broadcastPlayback(Protocol.Message.PLAY, hostTimeNanos)

                    override fun approvedTargets(): List<InetAddress> =
                        hostServer.approvedAddresses()

                    override fun onStatus(message: String) {
                        _hostState.value = _hostState.value.copy(
                            systemCaptureActive = true,
                            playbackState = PlaybackState.PLAYING,
                            message = message,
                        )
                    }

                    override fun onFailure(message: String) {
                        hostServer.broadcastPlayback(Protocol.Message.STOP)
                        _hostState.value = _hostState.value.copy(
                            systemCaptureActive = false,
                            playbackState = PlaybackState.STOPPED,
                            message = message,
                        )
                    }
                },
            )
        }
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
            android.Manifest.permission.RECORD_AUDIO ->
                "Audio recording permission is required only for experimental system audio capture."
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
        playbackCapture.stop()
        networkMonitor.close()
    }
}
