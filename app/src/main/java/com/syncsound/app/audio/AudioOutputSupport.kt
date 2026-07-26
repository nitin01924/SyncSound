package com.syncsound.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import com.syncsound.app.logging.AppLog
import com.syncsound.app.logging.LogArea
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Owns an Android 8+ audio-focus request. SyncSound runs a foreground service,
 * satisfying Android 15's background focus requirement during active sessions.
 */
class AudioFocusController(
    context: Context,
    private val owner: String,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    fun request(transient: Boolean): Boolean {
        abandon()
        val gain = if (transient) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        } else {
            AudioManager.AUDIOFOCUS_GAIN
        }
        val newRequest = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(mediaAttributes())
            .setOnAudioFocusChangeListener { change ->
                AppLog.i(
                    LogArea.AUDIO,
                    "audio_focus_changed",
                    "owner" to owner,
                    "change" to focusChangeName(change),
                    "code" to change,
                )
            }
            .build()
        val result = try {
            audioManager.requestAudioFocus(newRequest)
        } catch (error: Exception) {
            AppLog.e(
                LogArea.AUDIO,
                "audio_focus_request_failed",
                error,
                "owner" to owner,
            )
            return false
        }
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) request = newRequest
        AppLog.i(
            LogArea.AUDIO,
            "audio_focus_requested",
            "owner" to owner,
            "transient" to transient,
            "result" to focusRequestName(result),
            "code" to result,
        )
        return granted
    }

    fun abandon() {
        request?.let {
            runCatching { audioManager.abandonAudioFocusRequest(it) }
                .onSuccess { result ->
                    AppLog.i(
                        LogArea.AUDIO,
                        "audio_focus_abandoned",
                        "owner" to owner,
                        "result" to result,
                    )
                }
                .onFailure { error ->
                    AppLog.w(
                        LogArea.AUDIO,
                        "audio_focus_abandon_failed",
                        error,
                        "owner" to owner,
                    )
                }
        }
        request = null
    }

    fun nativeSampleRate(): String =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "unknown"

    fun nativeFramesPerBuffer(): String =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "unknown"

    companion object {
        fun mediaAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        private fun focusRequestName(result: Int): String = when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "GRANTED"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "DELAYED"
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "FAILED"
            else -> "UNKNOWN"
        }

        private fun focusChangeName(change: Int): String = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_CAN_DUCK"
            else -> "UNKNOWN"
        }
    }
}

/**
 * Handles AudioTrack's documented zero/short-write behavior. Zero means that
 * no bytes were accepted yet; only negative return values are Android errors.
 */
object PcmWritePump {
    suspend fun writeFully(
        track: AudioTrack,
        bytes: ByteArray,
        owner: String,
        onBytesWritten: (Int) -> Unit = {},
    ) {
        var offset = 0
        var zeroWriteStartedAt = 0L
        var zeroWrites = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            check(track.state == AudioTrack.STATE_INITIALIZED) {
                "$owner AudioTrack became ${trackStateName(track.state)} before write"
            }
            val mode = if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                AudioTrack.WRITE_BLOCKING
            } else {
                AudioTrack.WRITE_NON_BLOCKING
            }
            val result = track.write(bytes, offset, bytes.size - offset, mode)
            when {
                result > 0 -> {
                    offset += result
                    zeroWriteStartedAt = 0L
                    onBytesWritten(result)
                }
                result == 0 -> {
                    zeroWrites++
                    if (zeroWriteStartedAt == 0L) {
                        zeroWriteStartedAt = SystemClock.elapsedRealtime()
                        AppLog.d(
                            LogArea.AUDIO,
                            "audio_write_waiting",
                            "owner" to owner,
                            "playState" to playStateName(track.playState),
                            "bytesRemaining" to bytes.size - offset,
                        )
                    }
                    val stalledFor = SystemClock.elapsedRealtime() - zeroWriteStartedAt
                    val legitimatelyPaused = track.playState == AudioTrack.PLAYSTATE_PAUSED
                    if (!legitimatelyPaused && stalledFor > MAX_UNPAUSED_STALL_MS) {
                        error(
                            "$owner AudioTrack accepted 0 bytes for ${stalledFor}ms " +
                                "(state=${trackStateName(track.state)}, " +
                                "playState=${playStateName(track.playState)}, " +
                                "remaining=${bytes.size - offset}, zeroWrites=$zeroWrites)",
                        )
                    }
                    delay(RETRY_DELAY_MS)
                }
                else -> error(
                    "$owner AudioTrack.write failed: ${writeErrorName(result)} " +
                        "($result), state=${trackStateName(track.state)}, " +
                        "playState=${playStateName(track.playState)}, " +
                        "sampleRate=${track.sampleRate}, bufferFrames=${track.bufferSizeInFrames}",
                )
            }
        }
    }

    fun logTrackCreated(
        track: AudioTrack,
        owner: String,
        requestedSampleRate: Int,
        requestedChannels: Int,
        requestedBufferBytes: Int,
        minimumBufferBytes: Int,
        focus: AudioFocusController,
    ) {
        AppLog.i(
            LogArea.AUDIO,
            "audio_track_created",
            "owner" to owner,
            "backend" to "AudioTrack",
            "api" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "state" to trackStateName(track.state),
            "playState" to playStateName(track.playState),
            "requestedSampleRate" to requestedSampleRate,
            "actualSampleRate" to track.sampleRate,
            "channels" to requestedChannels,
            "encoding" to "PCM_16BIT",
            "requestedBufferBytes" to requestedBufferBytes,
            "minimumBufferBytes" to minimumBufferBytes,
            "bufferFrames" to track.bufferSizeInFrames,
            "bufferCapacityFrames" to track.bufferCapacityInFrames,
            "nativeSampleRate" to focus.nativeSampleRate(),
            "nativeFramesPerBuffer" to focus.nativeFramesPerBuffer(),
            "audioSessionId" to track.audioSessionId,
        )
    }

    fun setFullVolume(track: AudioTrack, owner: String) {
        setVolume(track, 1f, owner)
    }

    fun setVolume(track: AudioTrack, volume: Float, owner: String) {
        val safeVolume = volume.coerceIn(0f, 1f)
        val result = track.setVolume(safeVolume)
        check(result == AudioTrack.SUCCESS) {
            "$owner setVolume failed: ${writeErrorName(result)} ($result)"
        }
        AppLog.d(LogArea.AUDIO, "audio_volume_set", "owner" to owner, "volume" to safeVolume)
    }

    fun writeErrorName(code: Int): String = when (code) {
        AudioTrack.ERROR -> "ERROR"
        AudioTrack.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioTrack.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioTrack.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        0 -> "NO_PROGRESS"
        else -> "UNKNOWN_ERROR"
    }

    fun trackStateName(state: Int): String = when (state) {
        AudioTrack.STATE_INITIALIZED -> "INITIALIZED"
        AudioTrack.STATE_NO_STATIC_DATA -> "NO_STATIC_DATA"
        AudioTrack.STATE_UNINITIALIZED -> "UNINITIALIZED"
        else -> "UNKNOWN($state)"
    }

    fun playStateName(state: Int): String = when (state) {
        AudioTrack.PLAYSTATE_PLAYING -> "PLAYING"
        AudioTrack.PLAYSTATE_PAUSED -> "PAUSED"
        AudioTrack.PLAYSTATE_STOPPED -> "STOPPED"
        else -> "UNKNOWN($state)"
    }

    private const val RETRY_DELAY_MS = 2L
    private const val MAX_UNPAUSED_STALL_MS = 10_000L
}
