package com.syncsound.app.audio

import com.syncsound.app.network.Protocol
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A bounded UDP media unit. Sequence and presentation times let Clients reorder
 * packets and schedule them without relying on packet arrival time.
 */
data class PcmPacket(
    val streamId: Long,
    val sequence: Long,
    val presentationTimeNanos: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val payload: ByteArray,
) {
    fun encode(): ByteArray = ByteBuffer.allocate(Protocol.HEADER_BYTES + payload.size)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(Protocol.PACKET_MAGIC)
        .putInt(Protocol.VERSION)
        .putLong(streamId)
        .putLong(sequence)
        .putLong(presentationTimeNanos)
        .putInt(sampleRate)
        .putInt(channelCount)
        .put(payload)
        .array()

    companion object {
        fun decode(bytes: ByteArray, length: Int): PcmPacket? = runCatching {
            require(length in (Protocol.HEADER_BYTES + 1)..Protocol.MAX_DATAGRAM_BYTES)
            val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
            require(buffer.int == Protocol.PACKET_MAGIC)
            require(buffer.int == Protocol.VERSION)
            val streamId = buffer.long
            val sequence = buffer.long
            val timestamp = buffer.long
            val sampleRate = buffer.int
            val channels = buffer.int
            require(sampleRate in 8_000..192_000 && channels in 1..2)
            val payload = ByteArray(buffer.remaining())
            buffer.get(payload)
            PcmPacket(streamId, sequence, timestamp, sampleRate, channels, payload)
        }.getOrNull()
    }
}
