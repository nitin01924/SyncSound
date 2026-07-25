package com.syncsound.app.network

import android.os.SystemClock
import com.syncsound.app.model.DeviceStatus
import com.syncsound.app.model.RemoteDevice
import com.syncsound.app.network.Protocol.message
import com.syncsound.app.network.Protocol.readMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Accepts Client control connections and exposes all membership decisions to
 * the Host. Audio is never sent until approve() changes a pending connection.
 */
class HostControlServer {
    private data class Connection(
        val device: RemoteDevice,
        val address: InetAddress,
        val socket: Socket,
        val output: DataOutputStream,
    )

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private val connections = ConcurrentHashMap<String, Connection>()
    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices

    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { serverScope ->
            serverScope.launch {
                val server = ServerSocket(Protocol.CONTROL_PORT).also { serverSocket = it }
                while (isActive) {
                    runCatching { server.accept() }
                        .onSuccess { socket -> launch { handle(socket) } }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        var id: String? = null
        try {
            require(input.readMessage() == Protocol.Message.HELLO)
            require(input.readInt() == Protocol.VERSION)
            id = input.readUTF().take(128)
            val name = input.readUTF().take(80)
            val device = RemoteDevice(id, name, socket.inetAddress.hostAddress.orEmpty(), DeviceStatus.PENDING)
            connections[id] = Connection(device, socket.inetAddress, socket, output)
            publishDevices()
            output.message(Protocol.Message.PENDING)

            while (!socket.isClosed) {
                when (input.readMessage()) {
                    Protocol.Message.PING -> {
                        val clientSend = input.readLong()
                        output.message(Protocol.Message.PONG) {
                            writeLong(clientSend)
                            writeLong(SystemClock.elapsedRealtimeNanos())
                        }
                    }
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            id?.let { key ->
                connections.computeIfPresent(key) { _, value ->
                    value.copy(device = value.device.copy(status = DeviceStatus.DISCONNECTED))
                }
                publishDevices()
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    fun approve(id: String) {
        connections.computeIfPresent(id) { _, value ->
            value.output.message(Protocol.Message.APPROVED)
            value.copy(device = value.device.copy(status = DeviceStatus.APPROVED))
        }
        publishDevices()
    }

    fun reject(id: String) {
        connections.remove(id)?.let {
            it.output.message(Protocol.Message.REJECTED)
            runCatching { it.socket.close() }
        }
        publishDevices()
    }

    fun approvedAddresses(): List<InetAddress> = connections.values
        .filter { it.device.status == DeviceStatus.APPROVED }
        .map { it.address }

    fun broadcastFormat(sampleRate: Int, channels: Int) =
        approvedConnections().forEach { it.output.message(Protocol.Message.FORMAT) {
            writeInt(sampleRate)
            writeInt(channels)
        } }

    fun broadcastPlayback(message: Protocol.Message, hostTimeNanos: Long = 0L) =
        approvedConnections().forEach { connection ->
            connection.output.message(message) {
                if (message == Protocol.Message.PLAY || message == Protocol.Message.PAUSE) {
                    writeLong(hostTimeNanos)
                }
            }
        }

    private fun approvedConnections() =
        connections.values.filter { it.device.status == DeviceStatus.APPROVED }

    private fun publishDevices() {
        _devices.value = connections.values.map { it.device }.sortedBy { it.name }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        connections.values.forEach { runCatching { it.socket.close() } }
        connections.clear()
        _devices.value = emptyList()
        scope?.cancel()
        scope = null
        serverSocket = null
    }
}
