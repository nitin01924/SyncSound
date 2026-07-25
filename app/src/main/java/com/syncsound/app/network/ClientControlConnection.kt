package com.syncsound.app.network

import android.os.Build
import android.os.SystemClock
import com.syncsound.app.model.ClientConnectionState
import com.syncsound.app.network.Protocol.message
import com.syncsound.app.network.Protocol.readMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

sealed interface ClientCommand {
    data class Format(val sampleRate: Int, val channels: Int) : ClientCommand
    data class Play(val localPresentationTimeNanos: Long) : ClientCommand
    data class Pause(val localPresentationTimeNanos: Long) : ClientCommand
    data object Stop : ClientCommand
}

/**
 * Maintains the reliable Client control channel and estimates Host clock offset
 * using low-round-trip ping samples. Media commands are translated to local time.
 */
class ClientControlConnection {
    private var scope: CoroutineScope? = null
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var bestRoundTrip = Long.MAX_VALUE
    @Volatile private var hostMinusClientNanos = 0L
    private val _state = MutableStateFlow(ClientConnectionState.IDLE)
    val state: StateFlow<ClientConnectionState> = _state
    val commands = MutableSharedFlow<ClientCommand>(extraBufferCapacity = 16)

    fun connect(address: InetAddress, port: Int = Protocol.CONTROL_PORT) {
        disconnect()
        _state.value = ClientConnectionState.CONNECTING
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { clientScope ->
            clientScope.launch {
                try {
                    val connected = Socket().apply {
                        connect(InetSocketAddress(address, port), 5_000)
                        tcpNoDelay = true
                    }.also { socket = it }
                    val input = DataInputStream(BufferedInputStream(connected.getInputStream()))
                    val out = DataOutputStream(BufferedOutputStream(connected.getOutputStream())).also { output = it }
                    out.message(Protocol.Message.HELLO) {
                        writeInt(Protocol.VERSION)
                        writeUTF(deviceId())
                        writeUTF("${Build.MANUFACTURER} ${Build.MODEL}")
                    }
                    launch {
                        while (isActive) {
                            delay(500)
                            out.message(Protocol.Message.PING) {
                                writeLong(SystemClock.elapsedRealtimeNanos())
                            }
                        }
                    }
                    while (isActive) {
                        when (input.readMessage()) {
                            Protocol.Message.PENDING -> _state.value = ClientConnectionState.WAITING_APPROVAL
                            Protocol.Message.APPROVED -> _state.value = ClientConnectionState.APPROVED
                            Protocol.Message.REJECTED -> {
                                _state.value = ClientConnectionState.ERROR
                                disconnectSocket()
                            }
                            Protocol.Message.PONG -> {
                                val sent = input.readLong()
                                val host = input.readLong()
                                val received = SystemClock.elapsedRealtimeNanos()
                                val roundTrip = received - sent
                                if (roundTrip < bestRoundTrip) {
                                    bestRoundTrip = roundTrip
                                    hostMinusClientNanos = host - ((sent + received) / 2)
                                }
                            }
                            Protocol.Message.FORMAT ->
                                commands.emit(ClientCommand.Format(input.readInt(), input.readInt()))
                            Protocol.Message.PLAY -> {
                                val hostTime = input.readLong()
                                commands.emit(ClientCommand.Play(hostTime - hostMinusClientNanos))
                            }
                            Protocol.Message.PAUSE -> {
                                val hostTime = input.readLong()
                                commands.emit(ClientCommand.Pause(hostTime - hostMinusClientNanos))
                            }
                            Protocol.Message.STOP -> commands.emit(ClientCommand.Stop)
                            else -> Unit
                        }
                    }
                } catch (_: Exception) {
                    if (_state.value != ClientConnectionState.IDLE) {
                        _state.value = ClientConnectionState.ERROR
                    }
                } finally {
                    disconnectSocket()
                }
            }
        }
    }

    private fun deviceId(): String = UUID.nameUUIDFromBytes(
        "${Build.BOARD}:${Build.MODEL}:${Build.FINGERPRINT}".toByteArray(),
    ).toString()

    private fun disconnectSocket() {
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    fun disconnect() {
        scope?.cancel()
        scope = null
        disconnectSocket()
        bestRoundTrip = Long.MAX_VALUE
        hostMinusClientNanos = 0L
        _state.value = ClientConnectionState.IDLE
    }
}
