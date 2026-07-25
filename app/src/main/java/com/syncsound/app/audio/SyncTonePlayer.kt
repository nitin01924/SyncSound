package com.syncsound.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.syncsound.app.logging.AppLog
import com.syncsound.app.logging.LogArea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates and schedules a self-contained 440 Hz confirmation tone. Writer
 * and starter run independently so prefill can never prevent scheduled play().
 */
class SyncTonePlayer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val focus = AudioFocusController(context, OWNER)

    fun playAt(localTimeNanos: Long, onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            var track: AudioTrack? = null
            try {
                AppLog.i(LogArea.SYNC, "sync_tone_engine_creating", "frequencyHz" to FREQUENCY_HZ)
                check(focus.request(transient = true)) { "Audio focus was not granted for sync tone" }
                val pcm = TonePcmGenerator.generate()
                AppLog.i(
                    LogArea.SYNC,
                    "sync_tone_pcm_generated",
                    "sampleRate" to SAMPLE_RATE,
                    "durationMs" to DURATION_MS,
                    "frames" to pcm.size / BYTES_PER_FRAME,
                    "bytes" to pcm.size,
                )
                val channelMask = AudioFormat.CHANNEL_OUT_MONO
                val minimum = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                check(minimum > 0) {
                    "AudioTrack.getMinBufferSize failed: ${PcmWritePump.writeErrorName(minimum)} ($minimum)"
                }
                val requestedBuffer = maxOf(minimum * 2, pcm.size)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioFocusController.mediaAttributes())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(channelMask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(requestedBuffer)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                check(track.state == AudioTrack.STATE_INITIALIZED) {
                    "Sync tone AudioTrack initialization failed: " +
                        PcmWritePump.trackStateName(track.state)
                }
                PcmWritePump.setFullVolume(track, OWNER)
                PcmWritePump.logTrackCreated(
                    track,
                    OWNER,
                    SAMPLE_RATE,
                    1,
                    requestedBuffer,
                    minimum,
                    focus,
                )

                val toneTrack = track
                val starter = async {
                    while (SystemClock.elapsedRealtimeNanos() < localTimeNanos) delay(1)
                    AppLog.i(
                        LogArea.SYNC,
                        "sync_tone_play_starting",
                        "latenessUs" to
                            (SystemClock.elapsedRealtimeNanos() - localTimeNanos) / 1_000,
                    )
                    toneTrack.play()
                    check(toneTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        "Sync tone play() did not enter PLAYING; state=" +
                            PcmWritePump.playStateName(toneTrack.playState)
                    }
                    AppLog.i(LogArea.SYNC, "sync_tone_playback_started")
                }

                var bytesWritten = 0
                PcmWritePump.writeFully(toneTrack, pcm, OWNER) { bytesWritten += it }
                AppLog.i(
                    LogArea.SYNC,
                    "sync_tone_frames_written",
                    "bytes" to bytesWritten,
                    "frames" to bytesWritten / BYTES_PER_FRAME,
                )
                starter.await()
                val expectedFrames = pcm.size / BYTES_PER_FRAME
                withTimeout(DURATION_MS + 2_000L) {
                    while (toneTrack.playbackHeadPosition < expectedFrames) delay(5)
                }
                AppLog.i(
                    LogArea.SYNC,
                    "sync_tone_playback_completed",
                    "framesPlayed" to toneTrack.playbackHeadPosition,
                    "underruns" to toneTrack.underrunCount,
                )
                deliverResult(onResult, true)
            } catch (error: Exception) {
                AppLog.e(
                    LogArea.SYNC,
                    "sync_tone_playback_failed",
                    error,
                    "trackState" to track?.let { PcmWritePump.trackStateName(it.state) },
                    "playState" to track?.let { PcmWritePump.playStateName(it.playState) },
                    "headFrames" to track?.playbackHeadPosition,
                )
                deliverResult(onResult, false)
            } finally {
                runCatching { track?.stop() }
                track?.release()
                focus.abandon()
            }
        }
    }

    fun close() {
        focus.abandon()
        scope.cancel()
    }

    private fun deliverResult(callback: (Boolean) -> Unit, success: Boolean) {
        runCatching { callback(success) }
            .onFailure { AppLog.e(LogArea.SYNC, "sync_tone_result_callback_failed", it) }
    }

    companion object {
        internal const val SAMPLE_RATE = 48_000
        internal const val DURATION_MS = 400
        internal const val FREQUENCY_HZ = 440.0
        private const val BYTES_PER_FRAME = 2
        private const val OWNER = "sync-tone"
    }
}

/**
 * Pure PCM generator kept independent from Android so waveform correctness is
 * covered by local unit tests.
 */
internal object TonePcmGenerator {
    private const val AMPLITUDE = 0.35

    fun generate(): ByteArray {
        val sampleCount = SyncTonePlayer.SAMPLE_RATE * SyncTonePlayer.DURATION_MS / 1_000
        val result = ByteArray(sampleCount * BYTES_PER_FRAME)
        repeat(sampleCount) { index ->
            val time = index.toDouble() / SyncTonePlayer.SAMPLE_RATE
            val attack = (index / (SyncTonePlayer.SAMPLE_RATE * 0.02)).coerceIn(0.0, 1.0)
            val release = ((sampleCount - index) / (SyncTonePlayer.SAMPLE_RATE * 0.06))
                .coerceIn(0.0, 1.0)
            val envelope = attack * release
            val sample = (sin(2.0 * PI * SyncTonePlayer.FREQUENCY_HZ * time) *
                envelope * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
            result[index * 2] = (sample.toInt() and 0xff).toByte()
            result[index * 2 + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
        }
        return result
    }

    private const val BYTES_PER_FRAME = 2
}
