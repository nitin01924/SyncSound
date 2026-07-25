package com.syncsound.app.network

import android.os.Build
import android.os.SystemClock
import com.syncsound.app.logging.AppLog
import com.syncsound.app.logging.LogArea
import com.syncsound.app.model.ClientConnectionState
import com.syncsound.app.network.Protocol.message
import com.syncsound.app.network.Protocol.readMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ClientCommand {
    data class HostInfo(val hostName: String, val sessionCode: String) : ClientCommand
    data class Format(val sampleRate: Int, val channels: Int) : ClientCommand
    data class Play(val localPresentationTimeNanos: Long) : ClientCommand
    data class Pause(val localPresentationTimeNanos: Long) : ClientCommand
    data class TestTone(val testId: Long, val localPresentationTimeNanos: Long) : ClientCommand
    data class Removed(val reason: String) : ClientCommand
    data object Stop : ClientCommand
}

/**
 * Reconnecting Client control channel. Every socket read/write is contained in
 * the IO supervisor; failures become state and retry rather than process death.
 */
class ClientControlConnection {
    private var scope: CoroutineScope? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    private var bestRoundTrip = Long.MAX_VALUE
    @Volatile private var hostMinusClientNanos = 0L
    private val userDisconnected = AtomicBoolean(true)
    private val _state = MutableStateFlow(ClientConnectionState.IDLE)
    val state: StateFlow<ClientConnectionState> = _state
    private val _quality = MutableStateFlow("Unknown")
    val quality: StateFlow<String> = _quality
    val commands = MutableSharedFlow<ClientCommand>(extraBufferCapacity = 32)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun connect(
        address: InetAddress,
        port: Int = Protocol.CONTROL_PORT,
        sessionCode: String = "",
    ) {
        disconnect()
        userDisconnected.set(false)
        _state.value = ClientConnectionState.CONNECTING
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { clientScope ->
            clientScope.launch {
                var attempt = 0
                while (isActive && !userDisconnected.get()) {
                    if (attempt > 0) {
                        _state.value = ClientConnectionState.RECONNECTING
                        delay((1_000L shl minOf(attempt - 1, 3)).coerceAtMost(8_000L))
                    }
                    try {
                        runConnection(address, port, sessionCode)
                        attempt = 0
                    } catch (error: Exception) {
                        if (userDisconnected.get()) break
                        attempt++
                        AppLog.w(
                            LogArea.NETWORK,
                            "client_connection_lost",
                            error,
                            "host" to address.hostAddress,
                            "attempt" to attempt,
                        )
                        errors.tryEmit(
                            if (attempt == 1) "Connection lost. Reconnecting…" else
                                "Still trying to reconnect…",
                        )
                    } finally {
                        disconnectSocket()
                    }
                }
            }
        }
    }

    private suspend fun runConnection(address: InetAddress, port: Int, sessionCode: String) {
        val connected = Socket().apply {
            connect(InetSocketAddress(address, port), 5_000)
            tcpNoDelay = true
            keepAlive = true
            soTimeout = 15_000
        }.also { socket = it }
        val input = DataInputStream(BufferedInputStream(connected.getInputStream()))
        val out = DataOutputStream(BufferedOutputStream(connected.getOutputStream())).also { output = it }
        out.message(Protocol.Message.HELLO) {
            writeInt(Protocol.VERSION)
            writeUTF(deviceId())
            writeUTF("${Build.MANUFACTURER} ${Build.MODEL}")
            writeUTF(sessionCode)
        }
        AppLog.i(LogArea.NETWORK, "client_connected", "host" to address.hostAddress)

        val pingJob = scope?.launch {
            while (isActive && !connected.isClosed) {
                delay(1_000)
                if (!safeSend(Protocol.Message.PING) {
                        writeLong(SystemClock.elapsedRealtimeNanos())
                    }
                ) {
                    connected.close()
                    break
                }
            }
        }
        try {
            while (!connected.isClosed) {
                when (input.readMessage()) {
                    Protocol.Message.HOST_INFO -> {
                        commands.emit(ClientCommand.HostInfo(input.readUTF(), input.readUTF()))
                    }
                    Protocol.Message.PENDING -> {
                        _state.value = ClientConnectionState.WAITING_APPROVAL
                        AppLog.i(LogArea.SESSION, "waiting_for_approval")
                    }
                    Protocol.Message.APPROVED -> {
                        _state.value = ClientConnectionState.APPROVED
                        AppLog.i(LogArea.SESSION, "client_approved")
                    }
                    Protocol.Message.REJECTED -> {
                        commands.emit(ClientCommand.Removed("The Host rejected this request"))
                        userDisconnected.set(true)
                        connected.close()
                    }
                    Protocol.Message.REMOVE -> {
                        commands.emit(ClientCommand.Removed("The Host removed this device"))
                        userDisconnected.set(true)
                        connected.close()
                    }
                    Protocol.Message.PONG -> updateClock(input)
                    Protocol.Message.FORMAT ->
                        commands.emit(ClientCommand.Format(input.readInt(), input.readInt()))
                    Protocol.Message.PLAY -> commands.emit(
                        ClientCommand.Play(toLocalTime(input.readLong())),
                    )
                    Protocol.Message.PAUSE -> commands.emit(
                        ClientCommand.Pause(toLocalTime(input.readLong())),
                    )
                    Protocol.Message.TEST_TONE -> {
                        val testId = input.readLong()
                        commands.emit(ClientCommand.TestTone(testId, toLocalTime(input.readLong())))
                    }
                    Protocol.Message.STOP -> commands.emit(ClientCommand.Stop)
                    Protocol.Message.ERROR -> {
                        val message = input.readUTF().take(200)
                        errors.emit(message)
                        _state.value = ClientConnectionState.ERROR
                        userDisconnected.set(true)
                        connected.close()
                    }
                    else -> AppLog.w(LogArea.NETWORK, "unexpected_host_message")
                }
            }
        } finally {
            pingJob?.cancel()
        }
    }

    private fun updateClock(input: DataInputStream) {
        val sent = input.readLong()
        val host = input.readLong()
        val received = SystemClock.elapsedRealtimeNanos()
        val roundTrip = received - sent
        if (roundTrip <= 0) return
        if (roundTrip < bestRoundTrip) {
            bestRoundTrip = roundTrip
            hostMinusClientNanos = host - ((sent + received) / 2)
        }
        val milliseconds = roundTrip / 1_000_000
        _quality.value = when {
            milliseconds < 20 -> "Excellent"
            milliseconds < 50 -> "Good"
            milliseconds < 100 -> "Fair"
            else -> "Poor"
        }
        AppLog.d(LogArea.SYNC, "clock_sample", "roundTripMs" to milliseconds)
    }

    fun sendTestResult(testId: Long, success: Boolean) {
        scope?.launch {
            safeSend(Protocol.Message.TEST_RESULT) {
                writeLong(testId)
                writeBoolean(success)
            }
        }
    }

    private fun safeSend(
        type: Protocol.Message,
        body: DataOutputStream.() -> Unit = {},
    ): Boolean {
        val target = output ?: return false
        return try {
            target.message(type, body)
            true
        } catch (error: Exception) {
            AppLog.w(LogArea.NETWORK, "client_send_failed", error, "messageType" to type)
            false
        }
    }

    private fun toLocalTime(hostTime: Long): Long = hostTime - hostMinusClientNanos

    private fun deviceId(): String = UUID.nameUUIDFromBytes(
        "${Build.BOARD}:${Build.MODEL}:${Build.FINGERPRINT}".toByteArray(),
    ).toString()

    private fun disconnectSocket() {
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    fun disconnect() {
        userDisconnected.set(true)
        scope?.cancel()
        scope = null
        disconnectSocket()
        bestRoundTrip = Long.MAX_VALUE
        hostMinusClientNanos = 0L
        _quality.value = "Unknown"
        _state.value = ClientConnectionState.IDLE
        AppLog.i(LogArea.SESSION, "client_disconnected")
    }
}
