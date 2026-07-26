package com.syncsound.app.network

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Versioned binary control protocol. Data streams avoid ambiguous parsing and
 * keep the MVP independent from a third-party serialization runtime.
 */
object Protocol {
    const val VERSION = 2
    const val CONTROL_PORT = 45_121
    const val MEDIA_PORT = 45_122
    const val SERVICE_TYPE = "_syncsound._tcp."
    const val PACKET_MAGIC = 0x53534E44
    const val MAX_DATAGRAM_BYTES = 1_200
    const val HEADER_BYTES = 40
    const val MAX_PCM_PAYLOAD = MAX_DATAGRAM_BYTES - HEADER_BYTES
    const val START_LEAD_TIME_MS = 1_500L

    enum class Message(val wire: Int) {
        HELLO(1),
        PENDING(2),
        APPROVED(3),
        REJECTED(4),
        PING(5),
        PONG(6),
        FORMAT(7),
        PLAY(8),
        PAUSE(9),
        STOP(10),
        ERROR(11),
        REMOVE(12),
        TEST_TONE(13),
        TEST_RESULT(14),
        HOST_INFO(15),
        VOLUME(16);

        companion object {
            fun fromWire(value: Int): Message =
                entries.firstOrNull { it.wire == value }
                    ?: throw IllegalArgumentException("Unknown protocol message: $value")
        }
    }

    fun DataOutputStream.message(type: Message, writeBody: DataOutputStream.() -> Unit = {}) {
        synchronized(this) {
            writeInt(type.wire)
            writeBody()
            flush()
        }
    }

    fun DataInputStream.readMessage(): Message = Message.fromWire(readInt())
}
