package com.syncsound.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TonePcmGeneratorTest {
    @Test
    fun generatedToneHasExpectedDurationAndAudibleSamples() {
        val pcm = TonePcmGenerator.generate()
        val samples = pcm.toLittleEndianShorts()

        assertEquals(
            SyncTonePlayer.SAMPLE_RATE * SyncTonePlayer.DURATION_MS / 1_000,
            samples.size,
        )
        assertTrue(samples.any { abs(it.toInt()) > 2_000 })
        assertTrue(abs(samples.first().toInt()) < 10)
        assertTrue(abs(samples.last().toInt()) < 500)
    }

    @Test
    fun generatedToneIsApproximately440Hz() {
        val samples = TonePcmGenerator.generate().toLittleEndianShorts()
        val settled = samples.drop(SyncTonePlayer.SAMPLE_RATE / 20)
            .take(SyncTonePlayer.SAMPLE_RATE / 5)
        val risingCrossings = settled.zipWithNext().count { (left, right) ->
            left <= 0 && right > 0
        }
        val measuredHz = risingCrossings / 0.2

        assertTrue("Measured frequency was $measuredHz Hz", measuredHz in 435.0..445.0)
    }

    private fun ByteArray.toLittleEndianShorts(): List<Short> =
        indices.step(2).map { index ->
            ((this[index].toInt() and 0xff) or
                ((this[index + 1].toInt() and 0xff) shl 8)).toShort()
        }
}
