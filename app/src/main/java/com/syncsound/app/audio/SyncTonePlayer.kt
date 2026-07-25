package com.syncsound.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.syncsound.app.logging.AppLog
import com.syncsound.app.logging.LogArea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates the required synchronization confirmation sound locally, avoiding
 * network payload timing as a variable. Only the shared presentation time is sent.
 */
class SyncTonePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun playAt(localTimeNanos: Long, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            var track: AudioTrack? = null
            try {
                val pcm = createTone()
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size)
                    .build()
                check(track.state == AudioTrack.STATE_INITIALIZED) { "Audio output unavailable" }
                check(track.write(pcm, 0, pcm.size) == pcm.size) { "Could not load sync tone" }
                while (SystemClock.elapsedRealtimeNanos() < localTimeNanos) delay(1)
                track.play()
                AppLog.i(LogArea.SYNC, "sync_tone_started", "targetNanos" to localTimeNanos)
                delay(DURATION_MS + 100L)
                onResult(true)
            } catch (error: Exception) {
                AppLog.e(LogArea.SYNC, "sync_tone_failed", error)
                onResult(false)
            } finally {
                runCatching { track?.stop() }
                track?.release()
            }
        }
    }

    private fun createTone(): ByteArray {
        val sampleCount = SAMPLE_RATE * DURATION_MS / 1_000
        val result = ByteArray(sampleCount * 2)
        repeat(sampleCount) { index ->
            val time = index.toDouble() / SAMPLE_RATE
            val frequency = if (index < sampleCount / 2) 659.25 else 783.99
            val attack = (index / (SAMPLE_RATE * 0.025)).coerceIn(0.0, 1.0)
            val release = ((sampleCount - index) / (SAMPLE_RATE * 0.08)).coerceIn(0.0, 1.0)
            val envelope = attack * release
            val sample = (sin(2.0 * PI * frequency * time) * envelope * 0.22 * Short.MAX_VALUE)
                .toInt()
                .toShort()
            result[index * 2] = (sample.toInt() and 0xff).toByte()
            result[index * 2 + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
        }
        return result
    }

    fun close() = scope.cancel()

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val DURATION_MS = 400
    }
}
