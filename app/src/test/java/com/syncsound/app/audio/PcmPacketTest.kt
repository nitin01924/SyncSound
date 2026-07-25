package com.syncsound.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmPacketTest {
    @Test
    fun roundTripPreservesHeaderAndPayload() {
        val source = PcmPacket(
            streamId = 9,
            sequence = 42,
            presentationTimeNanos = 123_456,
            sampleRate = 48_000,
            channelCount = 2,
            payload = byteArrayOf(1, 2, 3, 4),
        )

        val encoded = source.encode()
        val decoded = PcmPacket.decode(encoded, encoded.size)!!

        assertEquals(source.streamId, decoded.streamId)
        assertEquals(source.sequence, decoded.sequence)
        assertEquals(source.presentationTimeNanos, decoded.presentationTimeNanos)
        assertEquals(source.sampleRate, decoded.sampleRate)
        assertEquals(source.channelCount, decoded.channelCount)
        assertArrayEquals(source.payload, decoded.payload)
    }

    @Test
    fun malformedPacketIsRejected() {
        assertNull(PcmPacket.decode(ByteArray(8), 8))
    }
}
