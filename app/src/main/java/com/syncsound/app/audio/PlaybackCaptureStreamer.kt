package com.syncsound.app.audio

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.syncsound.app.logging.AppLog
import com.syncsound.app.logging.LogArea
import com.syncsound.app.network.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Official Android 10+ playback-capture pipeline. Android, not SyncSound,
 * decides which source players may be captured according to their capture
 * policy, user profile, and DRM restrictions.
 */
class PlaybackCaptureStreamer(private val context: Context) {
    interface Listener {
        fun onFormat(sampleRate: Int, channels: Int)
        fun onStartScheduled(hostTimeNanos: Long)
        fun approvedTargets(): List<InetAddress>
        fun onStatus(message: String)
        fun onFailure(message: String)
    }

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private val stopping = AtomicBoolean(true)

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun start(resultCode: Int, resultData: Intent, listener: Listener) {
        stop()
        if (!supported) {
            listener.onFailure("System audio capture requires Android 10 or newer.")
            return
        }
        if (resultCode != Activity.RESULT_OK) {
            listener.onFailure("Screen-capture permission was not granted.")
            return
        }
        stopping.set(false)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { captureScope ->
            job = captureScope.launch {
                try {
                    capture(resultCode, resultData, listener)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLog.e(LogArea.AUDIO, "playback_capture_failed", error)
                    listener.onFailure(
                        error.message ?: "Android could not capture playback audio.",
                    )
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun capture(resultCode: Int, data: Intent, listener: Listener) {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "Microphone permission is required by Android for playback capture." }
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val activeProjection = manager.getMediaProjection(resultCode, data)
            ?: error("Android did not return a MediaProjection")
        projection = activeProjection
        activeProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    AppLog.i(LogArea.AUDIO, "playback_capture_projection_stopped")
                    if (stopping.compareAndSet(false, true)) {
                        listener.onFailure("Android ended system audio sharing.")
                        job?.cancel()
                        runCatching { recorder?.stop() }
                    }
                }
            },
            null,
        )
        val configuration = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "AudioRecord buffer query failed ($minimum)" }
        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(configuration)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum * 4, SAMPLE_RATE * CHANNELS))
            .build()
        recorder = record
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "Playback AudioRecord did not initialize (state=${record.state})"
        }
        val udp = DatagramSocket()
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Playback AudioRecord did not start (state=${record.recordingState})"
            }
            val streamId = System.nanoTime()
            val startTime = SystemClock.elapsedRealtimeNanos() +
                CAPTURE_START_LEAD_TIME_MS * 1_000_000
            var frames = 0L
            var sequence = 0L
            var audibleSamples = 0L
            val startedAt = SystemClock.elapsedRealtime()
            listener.onFormat(SAMPLE_RATE, CHANNELS)
            listener.onStartScheduled(startTime)
            listener.onStatus("Capturing supported system playback…")
            AppLog.i(
                LogArea.AUDIO,
                "playback_capture_started",
                "sampleRate" to SAMPLE_RATE,
                "channels" to CHANNELS,
                "bufferBytes" to minimum * 4,
            )
            val buffer = ByteArray(Protocol.MAX_PCM_PAYLOAD - Protocol.MAX_PCM_PAYLOAD % 4)
            while (scope?.isActive == true) {
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count < 0) error("AudioRecord.read failed ($count)")
                if (count == 0) {
                    delay(2)
                    continue
                }
                var index = 0
                while (index + 1 < count) {
                    val sample = ((buffer[index].toInt() and 0xff) or
                        (buffer[index + 1].toInt() shl 8)).toShort()
                    if (abs(sample.toInt()) > 96) audibleSamples++
                    index += 2
                }
                val aligned = count - count % 4
                val payload = buffer.copyOf(aligned)
                val presentation = startTime + frames * 1_000_000_000L / SAMPLE_RATE
                while (presentation - SystemClock.elapsedRealtimeNanos() >
                    CAPTURE_SEND_AHEAD_NANOS
                ) {
                    delay(2)
                }
                val packet = PcmPacket(
                    streamId,
                    sequence++,
                    presentation,
                    SAMPLE_RATE,
                    CHANNELS,
                    payload,
                ).encode()
                listener.approvedTargets().forEach { target ->
                    runCatching {
                        udp.send(DatagramPacket(packet, packet.size, target, Protocol.MEDIA_PORT))
                    }.onFailure {
                        AppLog.w(
                            LogArea.NETWORK,
                            "capture_packet_send_failed",
                            it,
                            "target" to target.hostAddress,
                        )
                    }
                }
                frames += aligned / 4
                if (SystemClock.elapsedRealtime() - startedAt > SILENCE_WARNING_MS &&
                    audibleSamples == 0L
                ) {
                    listener.onStatus(
                        "No capturable audio detected. The source may be silent, DRM-protected, " +
                            "or may block Android playback capture.",
                    )
                    audibleSamples = Long.MIN_VALUE
                }
            }
        } finally {
            stopping.set(true)
            udp.close()
            runCatching { record.stop() }
            record.release()
            recorder = null
            runCatching { activeProjection.stop() }
            projection = null
        }
    }

    fun stop() {
        stopping.set(true)
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        runCatching { recorder?.stop() }
        runCatching { projection?.stop() }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val SILENCE_WARNING_MS = 3_000L
        // System capture cannot be truly real-time over Wi-Fi. This bounded
        // lead trades a small delay for enough time to absorb normal LAN jitter.
        private const val CAPTURE_START_LEAD_TIME_MS = 250L
        private const val CAPTURE_SEND_AHEAD_NANOS = 180_000_000L
    }
}
