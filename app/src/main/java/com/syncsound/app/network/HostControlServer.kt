package com.syncsound.app.network

import android.os.SystemClock
import com.syncsound.app.logging.AppLog
import com.syncsound.app.logging.LogArea
import com.syncsound.app.model.DeviceStatus
import com.syncsound.app.model.RemoteDevice
import com.syncsound.app.model.SyncTestStatus
import com.syncsound.app.network.Protocol.message
import com.syncsound.app.network.Protocol.readMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/**
 * Exception-contained Host control server. UI actions only enqueue work on its
 * IO scope; a stale socket can evict one Client but can never crash the Host.
 */
class HostControlServer(
    private val port: Int = Protocol.CONTROL_PORT,
) {
    private data class Connection(
        val device: RemoteDevice,
        val address: InetAddress,
        val socket: Socket,
        val output: DataOutputStream,
    )

    private data class Outbound(
        val message: Protocol.Message,
        val body: DataOutputStream.() -> Unit,
    )

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var hostName = ""
    private var sessionCode = ""
    @Volatile private var currentVolume = 1f
    private val connections = ConcurrentHashMap<String, Connection>()
    private val approvedDeviceIds = ConcurrentHashMap.newKeySet<String>()
    private var outbound: Channel<Outbound>? = null
    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun start(hostName: String, sessionCode: String) {
        if (scope != null) return
        this.hostName = hostName
        this.sessionCode = sessionCode
        val sessionOutbound = Channel<Outbound>(Channel.UNLIMITED).also { outbound = it }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { serverScope ->
            serverScope.launch {
                for (command in sessionOutbound) {
                    approvedConnections().forEach {
                        safeSend(it, command.message, command.body)
                    }
                }
            }
            serverScope.launch {
                try {
                    val server = ServerSocket(port).apply { reuseAddress = true }
                        .also { serverSocket = it }
                    AppLog.i(LogArea.NETWORK, "host_server_started", "port" to port)
                    while (isActive) {
                        val socket = try {
                            server.accept()
                        } catch (error: SocketException) {
                            if (isActive) reportError("Host socket stopped unexpectedly", error)
                            break
                        }
                        launch { handle(socket) }
                    }
                } catch (error: Exception) {
                    reportError("Could not start the Host session", error)
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 15_000
        val input = runCatching {
            DataInputStream(BufferedInputStream(socket.getInputStream()))
        }.getOrElse {
            closeQuietly(socket)
            return
        }
        val output = runCatching {
            DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        }.getOrElse {
            closeQuietly(socket)
            return
        }
        var id: String? = null
        try {
            require(input.readMessage() == Protocol.Message.HELLO) { "Expected HELLO" }
            require(input.readInt() == Protocol.VERSION) { "Incompatible protocol" }
            id = input.readUTF().take(128).also { require(it.isNotBlank()) }
            val name = input.readUTF().take(80).ifBlank { "Android device" }
            val requestedCode = input.readUTF().take(12)
            if (requestedCode.isNotEmpty() && requestedCode != sessionCode) {
                runCatching {
                    output.message(Protocol.Message.ERROR) {
                        writeUTF("That session code is not valid for this Host")
                    }
                }
                AppLog.w(
                    LogArea.SESSION,
                    "invalid_session_code",
                    null,
                    "address" to socket.inetAddress.hostAddress,
                )
                return
            }

            val restored = id in approvedDeviceIds
            val device = RemoteDevice(
                id = id,
                name = name,
                address = socket.inetAddress.hostAddress.orEmpty(),
                status = if (restored) DeviceStatus.APPROVED else DeviceStatus.PENDING,
            )
            connections.put(id, Connection(device, socket.inetAddress, socket, output))
                ?.let { closeQuietly(it.socket) }
            publishDevices()
            AppLog.i(
                LogArea.SESSION,
                if (restored) "client_reconnected" else "join_request",
                "deviceId" to id,
                "name" to name,
                "address" to device.address,
            )
            val connection = connections[id] ?: return
            safeSend(connection, Protocol.Message.HOST_INFO) {
                writeUTF(hostName)
                writeUTF(sessionCode)
            }
            safeSend(
                connection,
                if (restored) Protocol.Message.APPROVED else Protocol.Message.PENDING,
            )

            while (!socket.isClosed) {
                when (input.readMessage()) {
                    Protocol.Message.PING -> {
                        val clientSend = input.readLong()
                        safeSend(connection, Protocol.Message.PONG) {
                            writeLong(clientSend)
                            writeLong(SystemClock.elapsedRealtimeNanos())
                        }
                    }
                    Protocol.Message.TEST_RESULT -> {
                        val testId = input.readLong()
                        val success = input.readBoolean()
                        updateSyncResult(id, success)
                        AppLog.i(
                            LogArea.SYNC,
                            "sync_test_result",
                            "deviceId" to id,
                            "testId" to testId,
                            "success" to success,
                        )
                    }
                    else -> AppLog.w(LogArea.NETWORK, "unexpected_client_message", null, "deviceId" to id)
                }
            }
        } catch (error: EOFException) {
            AppLog.i(LogArea.NETWORK, "client_closed_connection", "deviceId" to id)
        } catch (error: Exception) {
            AppLog.w(LogArea.NETWORK, "client_connection_failed", error, "deviceId" to id)
        } finally {
            id?.let { markDisconnected(it, socket) }
            closeQuietly(socket)
        }
    }

    fun approve(id: String) {
        val connection = connections[id] ?: return
        approvedDeviceIds += id
        connections.computeIfPresent(id) { _, value ->
            value.copy(device = value.device.copy(status = DeviceStatus.APPROVED))
        }
        publishDevices()
        AppLog.i(LogArea.SESSION, "client_approved", "deviceId" to id)
        scope?.launch {
            safeSend(connection, Protocol.Message.APPROVED)
            safeSend(connection, Protocol.Message.VOLUME) { writeFloat(currentVolume) }
        }
    }

    fun reject(id: String) {
        val connection = connections.remove(id) ?: return
        approvedDeviceIds -= id
        publishDevices()
        AppLog.i(LogArea.SESSION, "client_rejected", "deviceId" to id)
        scope?.launch {
            safeSend(connection, Protocol.Message.REJECTED)
            closeQuietly(connection.socket)
        }
    }

    fun remove(id: String) {
        val connection = connections.remove(id)
        approvedDeviceIds -= id
        publishDevices()
        AppLog.i(LogArea.SESSION, "client_removed", "deviceId" to id)
        connection?.let {
            scope?.launch {
                safeSend(it, Protocol.Message.REMOVE)
                closeQuietly(it.socket)
            }
        }
    }

    fun approvedAddresses(): List<InetAddress> = connections.values
        .filter { it.device.status == DeviceStatus.APPROVED && !it.socket.isClosed }
        .map { it.address }

    fun broadcastFormat(sampleRate: Int, channels: Int) =
        broadcast(Protocol.Message.FORMAT) {
            writeInt(sampleRate)
            writeInt(channels)
        }

    fun broadcastPlayback(message: Protocol.Message, hostTimeNanos: Long = 0L) =
        broadcast(message) {
            if (message == Protocol.Message.PLAY || message == Protocol.Message.PAUSE) {
                writeLong(hostTimeNanos)
            }
        }

    fun broadcastVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        broadcast(Protocol.Message.VOLUME) { writeFloat(currentVolume) }
    }

    fun startSyncTest(testId: Long, hostTimeNanos: Long) {
        connections.replaceAll { _, value ->
            if (value.device.status == DeviceStatus.APPROVED) {
                value.copy(device = value.device.copy(syncTestStatus = SyncTestStatus.TESTING))
            } else {
                value
            }
        }
        publishDevices()
        broadcast(Protocol.Message.TEST_TONE) {
            writeLong(testId)
            writeLong(hostTimeNanos)
        }
        scope?.launch {
            delay(4_000)
            connections.replaceAll { _, value ->
                if (value.device.syncTestStatus == SyncTestStatus.TESTING) {
                    value.copy(device = value.device.copy(syncTestStatus = SyncTestStatus.FAILED))
                } else {
                    value
                }
            }
            publishDevices()
        }
    }

    private fun broadcast(
        message: Protocol.Message,
        body: DataOutputStream.() -> Unit = {},
    ) {
        if (outbound?.trySend(Outbound(message, body))?.isFailure != false) {
            reportError(
                "Could not queue a session command",
                IllegalStateException("Outbound control queue unavailable"),
            )
        }
    }

    private fun safeSend(
        connection: Connection,
        message: Protocol.Message,
        body: DataOutputStream.() -> Unit = {},
    ): Boolean = try {
        connection.output.message(message, body)
        true
    } catch (error: Exception) {
        AppLog.w(
            LogArea.NETWORK,
            "control_send_failed",
            error,
            "deviceId" to connection.device.id,
            "messageType" to message,
        )
        markDisconnected(connection.device.id, connection.socket)
        closeQuietly(connection.socket)
        false
    }

    private fun updateSyncResult(id: String, success: Boolean) {
        connections.computeIfPresent(id) { _, value ->
            value.copy(
                device = value.device.copy(
                    syncTestStatus = if (success) SyncTestStatus.SUCCESS else SyncTestStatus.FAILED,
                ),
            )
        }
        publishDevices()
    }

    private fun markDisconnected(id: String, socket: Socket) {
        connections.computeIfPresent(id) { _, value ->
            if (value.socket !== socket) {
                value
            } else {
                value.copy(device = value.device.copy(status = DeviceStatus.DISCONNECTED))
            }
        }
        publishDevices()
    }

    private fun approvedConnections(): List<Connection> =
        connections.values.filter { it.device.status == DeviceStatus.APPROVED && !it.socket.isClosed }

    private fun publishDevices() {
        _devices.value = connections.values.map { it.device }.sortedBy { it.name }
    }

    private fun reportError(message: String, error: Throwable) {
        AppLog.e(LogArea.NETWORK, "host_server_error", error, "userMessage" to message)
        errors.tryEmit(message)
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }

    fun stop() {
        AppLog.i(LogArea.SESSION, "host_session_stopped")
        runCatching { serverSocket?.close() }
        val closingConnections = connections.values.toList()
        val closingScope = scope
        connections.clear()
        approvedDeviceIds.clear()
        outbound?.close()
        outbound = null
        _devices.value = emptyList()
        scope = null
        serverSocket = null
        hostName = ""
        sessionCode = ""
        currentVolume = 1f
        closingScope?.launch {
            closingConnections.forEach {
                safeSend(it, Protocol.Message.REMOVE)
                closeQuietly(it.socket)
            }
            closingScope.cancel()
        } ?: closingConnections.forEach { closeQuietly(it.socket) }
    }
}
