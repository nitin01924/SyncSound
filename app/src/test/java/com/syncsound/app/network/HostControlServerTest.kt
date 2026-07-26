package com.syncsound.app.network

import com.syncsound.app.network.Protocol.message
import com.syncsound.app.network.Protocol.readMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

class HostControlServerTest {
    private var server: HostControlServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun approvingClientWhoseSocketClosedDoesNotThrow() {
        val port = ServerSocket(0).use { it.localPort }
        val host = HostControlServer(port).also {
            server = it
            it.start("Test Host", "123456")
        }
        val socket = connectWithRetry(port)
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        output.message(Protocol.Message.HELLO) {
            writeInt(Protocol.VERSION)
            writeUTF("test-device")
            writeUTF("Test Client")
            writeUTF("123456")
        }
        assertEquals(Protocol.Message.HOST_INFO, input.readMessage())
        input.readUTF()
        input.readUTF()
        assertEquals(Protocol.Message.PENDING, input.readMessage())

        socket.close()
        host.approve("test-device")
        Thread.sleep(100)
    }

    @Test
    fun approvalIsDeliveredAndConnectionRemainsOpen() {
        val port = ServerSocket(0).use { it.localPort }
        val host = HostControlServer(port).also {
            server = it
            it.start("Test Host", "123456")
        }
        val socket = connectWithRetry(port).apply { soTimeout = 2_000 }
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        output.message(Protocol.Message.HELLO) {
            writeInt(Protocol.VERSION)
            writeUTF("stable-client")
            writeUTF("Stable Client")
            writeUTF("123456")
        }
        assertEquals(Protocol.Message.HOST_INFO, input.readMessage())
        assertEquals("Test Host", input.readUTF())
        assertEquals("123456", input.readUTF())
        assertEquals(Protocol.Message.PENDING, input.readMessage())

        host.approve("stable-client")

        assertEquals(Protocol.Message.APPROVED, input.readMessage())
        assertEquals(Protocol.Message.VOLUME, input.readMessage())
        assertEquals(1f, input.readFloat(), 0f)
        check(!socket.isClosed)
        socket.close()
    }

    @Test
    fun invalidSessionCodeReturnsFriendlyProtocolError() {
        val port = ServerSocket(0).use { it.localPort }
        HostControlServer(port).also {
            server = it
            it.start("Test Host", "123456")
        }
        val socket = connectWithRetry(port).apply { soTimeout = 2_000 }
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        output.message(Protocol.Message.HELLO) {
            writeInt(Protocol.VERSION)
            writeUTF("wrong-code-client")
            writeUTF("Wrong Code")
            writeUTF("999999")
        }

        assertEquals(Protocol.Message.ERROR, input.readMessage())
        assert(input.readUTF().contains("not valid"))
        socket.close()
    }

    private fun connectWithRetry(port: Int): Socket {
        repeat(20) {
            runCatching { return Socket("127.0.0.1", port) }
            Thread.sleep(25)
        }
        error("Server did not start")
    }
}
