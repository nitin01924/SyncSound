package com.syncsound.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketReorderBufferTest {
    @Test
    fun reorderedPacketIsPlayedInSequenceWhenItArrivesInsideWindow() {
        val buffer = PacketReorderBuffer(startupPackets = 1, reorderWaitNanos = 20)
        buffer.offer(packet(1))
        assertEquals(1L, buffer.poll(0).packet?.sequence)
        buffer.offer(packet(3))
        assertNull(buffer.poll(5).packet)
        buffer.offer(packet(2))
        assertEquals(2L, buffer.poll(10).packet?.sequence)
        assertEquals(3L, buffer.poll(11).packet?.sequence)
    }

    @Test
    fun missingPacketIsSkippedAfterDeadline() {
        val buffer = PacketReorderBuffer(startupPackets = 1, reorderWaitNanos = 20)
        buffer.offer(packet(4))
        assertEquals(4L, buffer.poll(0).packet?.sequence)
        buffer.offer(packet(7))
        assertNull(buffer.poll(10).packet)
        val result = buffer.poll(31)
        assertEquals(2L, result.skippedPackets)
        assertEquals(7L, result.packet?.sequence)
    }

    private fun packet(sequence: Long) = PcmPacket(
        streamId = 1,
        sequence = sequence,
        presentationTimeNanos = sequence * 1_000,
        sampleRate = 48_000,
        channelCount = 2,
        payload = byteArrayOf(0, 0, 0, 0),
    )
}
