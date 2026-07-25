package com.syncsound.app.model

import java.net.InetAddress

enum class DeviceStatus { PENDING, APPROVED, REJECTED, DISCONNECTED }
enum class PlaybackState { IDLE, BUFFERING, PLAYING, PAUSED, STOPPED }
enum class ClientConnectionState { IDLE, DISCOVERING, CONNECTING, WAITING_APPROVAL, APPROVED, ERROR }

data class RemoteDevice(
    val id: String,
    val name: String,
    val address: String,
    val status: DeviceStatus,
)

data class DiscoveredSession(
    val name: String,
    val address: InetAddress,
    val port: Int,
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
    val devices: List<RemoteDevice> = emptyList(),
    val selectedTrack: String? = null,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val message: String? = null,
)

data class ClientUiState(
    val connectionState: ClientConnectionState = ClientConnectionState.IDLE,
    val sessions: List<DiscoveredSession> = emptyList(),
    val connectedHost: String? = null,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val message: String? = null,
)
