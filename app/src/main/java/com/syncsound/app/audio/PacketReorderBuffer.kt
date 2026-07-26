package com.syncsound.app.audio

import java.util.TreeMap

/**
 * Small sequence-aware jitter buffer. It waits briefly for a missing UDP
 * packet before declaring loss, preventing normal Wi-Fi reordering from
 * becoming an avoidable click or silence insertion.
 */
internal class PacketReorderBuffer(
    private val startupPackets: Int = 8,
    private val reorderWaitNanos: Long = 20_000_000L,
    private val maximumPackets: Int = 400,
) {
    data class PollResult(val packet: PcmPacket?, val skippedPackets: Long = 0)

    private val packets = TreeMap<Long, PcmPacket>()
    private var expectedSequence: Long? = null
    private var missingSinceNanos: Long? = null

    @Synchronized
    fun offer(packet: PcmPacket) {
        packets.putIfAbsent(packet.sequence, packet)
        while (packets.size > maximumPackets) packets.pollFirstEntry()
    }

    @Synchronized
    fun poll(nowNanos: Long): PollResult {
        if (packets.isEmpty()) return PollResult(null)
        if (expectedSequence == null) {
            if (packets.size < startupPackets) return PollResult(null)
            expectedSequence = packets.firstKey()
        }
        val expected = expectedSequence ?: return PollResult(null)
        packets.remove(expected)?.let {
            expectedSequence = expected + 1
            missingSinceNanos = null
            return PollResult(it)
        }
        val next = packets.firstKey()
        if (next < expected) {
            packets.pollFirstEntry()
            return poll(nowNanos)
        }
        val waitingSince = missingSinceNanos ?: nowNanos.also { missingSinceNanos = it }
        if (nowNanos - waitingSince < reorderWaitNanos) return PollResult(null)
        val skipped = next - expected
        expectedSequence = next + 1
        missingSinceNanos = null
        return PollResult(packets.pollFirstEntry()?.value, skipped)
    }

    @Synchronized
    fun clear() {
        packets.clear()
        expectedSequence = null
        missingSinceNanos = null
    }

    @Synchronized
    fun size(): Int = packets.size
}
