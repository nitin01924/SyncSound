package com.syncsound.app.model

import java.net.InetAddress

enum class DeviceStatus { PENDING, APPROVED, REJECTED, DISCONNECTED }
enum class PlaybackState { IDLE, BUFFERING, PLAYING, PAUSED, STOPPED }
enum class ClientConnectionState {
    IDLE, DISCOVERING, CONNECTING, WAITING_APPROVAL, APPROVED, RECONNECTING, ERROR
}
enum class SyncTestStatus { NOT_TESTED, TESTING, SUCCESS, FAILED }

data class RemoteDevice(
    val id: String,
    val name: String,
    val address: String,
    val status: DeviceStatus,
    val syncTestStatus: SyncTestStatus = SyncTestStatus.NOT_TESTED,
)

data class DiscoveredSession(
    val name: String,
    val address: InetAddress,
    val port: Int,
    val sessionCode: String = "",
) {
    val key: String = "${address.hostAddress}:$port"
}

data class AudioFormatInfo(
    val sampleRate: Int,
    val channelCount: Int,
) {
    val bytesPerFrame: Int = channelCount * 2
}

data class HostUiState(
    val isHosting: Boolean = false,
    val hostAddress: String = "Unavailable",
    val hostName: String = "",
    val sessionCode: String = "",
    val devices: List<RemoteDevice> = emptyList(),
    val selectedTrack: String? = null,
    val queuePosition: Int = 0,
    val queueSize: Int = 0,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val volume: Float = 1f,
    val systemCaptureSupported: Boolean = false,
    val systemCaptureActive: Boolean = false,
    val message: String? = null,
)

data class ClientUiState(
    val connectionState: ClientConnectionState = ClientConnectionState.IDLE,
    val sessions: List<DiscoveredSession> = emptyList(),
    val connectedHost: String? = null,
    val hostName: String? = null,
    val connectionQuality: String = "Unknown",
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val message: String? = null,
)
