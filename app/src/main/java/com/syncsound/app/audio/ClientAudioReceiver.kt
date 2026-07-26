package com.syncsound.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import com.syncsound.app.model.PlaybackState
import com.syncsound.app.logging.AppLog
import com.syncsound.app.logging.LogArea
import com.syncsound.app.network.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Receives timestamped PCM, reorders a small window, and feeds AudioTrack.
 * Playback remains paused while the queue fills, then starts on the shared time.
 */
class ClientAudioReceiver(context: Context) {
    private val focus = AudioFocusController(context, OWNER)
    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null
    private val queue = PacketReorderBuffer()
    @Volatile private var scheduledStartNanos: Long? = null
    @Volatile private var allowedHost: InetAddress? = null
    private var startJob: Job? = null
    private var renderedUntilNanos: Long? = null
    private var invalidPacketCount = 0
    private var framesWritten = 0L
    private var streamClockOffsetNanos: Long? = null
    @Volatile private var targetStreamClockOffsetNanos: Long? = null
    @Volatile private var volume = 1f
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state
    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors

    fun start(host: InetAddress) {
        allowedHost = host
        if (scope != null) return
        val exceptionHandler = CoroutineExceptionHandler { _, error ->
            AppLog.e(LogArea.AUDIO, "client_audio_coroutine_failed", error)
            _errors.value = error.message ?: "Client audio failed"
            _state.value = PlaybackState.STOPPED
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler).also { receiverScope ->
            receiverScope.launch {
                AppLog.i(
                    LogArea.NETWORK,
                    "client_media_socket_opening",
                    "port" to Protocol.MEDIA_PORT,
                    "allowedHost" to host.hostAddress,
                )
                val udp = DatagramSocket(Protocol.MEDIA_PORT).apply { soTimeout = 500 }.also { socket = it }
                AppLog.i(LogArea.NETWORK, "client_media_socket_opened", "port" to udp.localPort)
                val bytes = ByteArray(Protocol.MAX_DATAGRAM_BYTES)
                while (isActive) {
                    try {
                        val datagram = DatagramPacket(bytes, bytes.size)
                        udp.receive(datagram)
                        if (datagram.address != allowedHost) continue
                        val decoded = PcmPacket.decode(datagram.data, datagram.length)
                        if (decoded == null) {
                            invalidPacketCount++
                            if (invalidPacketCount == 1 || invalidPacketCount % 100 == 0) {
                                AppLog.w(
                                    LogArea.NETWORK,
                                    "invalid_media_packet",
                                    null,
                                    "count" to invalidPacketCount,
                                )
                            }
                        }
                        decoded?.let { packet ->
                            queue.offer(packet)
                        }
                    } catch (_: SocketTimeoutException) {
                        // Timeout allows cancellation checks without closing a healthy stream.
                    }
                }
            }
            receiverScope.launch { renderLoop() }
        }
    }

    fun configure(sampleRate: Int, channels: Int) {
        AppLog.i(
            LogArea.AUDIO,
            "client_audio_configuring",
            "sampleRate" to sampleRate,
            "channels" to channels,
        )
        startJob?.cancel()
        scheduledStartNanos = null
        renderedUntilNanos = null
        framesWritten = 0L
        streamClockOffsetNanos = null
        targetStreamClockOffsetNanos = null
        queue.clear()
        audioTrack?.release()
        focus.abandon()
        check(focus.request(transient = false)) { "Audio focus was not granted on Client" }
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) {
            "Client getMinBufferSize failed: ${PcmWritePump.writeErrorName(minimum)} ($minimum)"
        }
        val requestedBuffer = maxOf(minimum * 4, sampleRate * channels)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioFocusController.mediaAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(requestedBuffer)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { track ->
                check(track.state == AudioTrack.STATE_INITIALIZED) {
                    "Client AudioTrack initialization failed: " +
                        PcmWritePump.trackStateName(track.state)
                }
                PcmWritePump.setFullVolume(track, OWNER)
                PcmWritePump.setVolume(track, volume, OWNER)
                PcmWritePump.logTrackCreated(
                    track,
                    OWNER,
                    sampleRate,
                    channels,
                    requestedBuffer,
                    minimum,
                    focus,
                )
            }
        AppLog.i(LogArea.AUDIO, "client_audio_configured")
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        audioTrack?.let { PcmWritePump.setVolume(it, volume, OWNER) }
        AppLog.i(LogArea.AUDIO, "client_volume_changed", "volume" to volume)
    }

    /**
     * Receives the continuously refined TCP clock estimate. The media mapping
     * uses the opposite sign and converges gradually inside renderLoop.
     */
    fun updateClockEstimate(hostMinusClientNanos: Long) {
        targetStreamClockOffsetNanos = -hostMinusClientNanos
    }

    fun playAt(localTimeNanos: Long, hostTimeNanos: Long) {
        val resuming = _state.value == PlaybackState.PAUSED
        scheduledStartNanos = localTimeNanos
        if (!resuming) {
            renderedUntilNanos = localTimeNanos
            streamClockOffsetNanos = localTimeNanos - hostTimeNanos
            targetStreamClockOffsetNanos = streamClockOffsetNanos
            AppLog.i(
                LogArea.SYNC,
                "media_timeline_mapped",
                "hostStartNanos" to hostTimeNanos,
                "clientStartNanos" to localTimeNanos,
                "hostToClientOffsetNanos" to streamClockOffsetNanos,
            )
        }
        _state.value = PlaybackState.BUFFERING
        startJob?.cancel()
        startJob = scope?.launch {
            while (SystemClock.elapsedRealtimeNanos() < localTimeNanos) delay(1)
            val track = audioTrack ?: error("Client AudioTrack missing at scheduled start")
            AppLog.i(
                LogArea.AUDIO,
                "client_play_starting",
                "latenessUs" to
                    (SystemClock.elapsedRealtimeNanos() - localTimeNanos) / 1_000,
                "queuedPackets" to queue.size(),
            )
            track.play()
            check(track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                "Client play() did not enter PLAYING: " +
                    PcmWritePump.playStateName(track.playState)
            }
            _state.value = PlaybackState.PLAYING
            AppLog.i(LogArea.AUDIO, "client_playback_started")
        }
    }

    fun pauseAt(localTimeNanos: Long) {
        startJob?.cancel()
        startJob = scope?.launch {
            while (SystemClock.elapsedRealtimeNanos() < localTimeNanos) delay(1)
            audioTrack?.pause()
            _state.value = PlaybackState.PAUSED
            AppLog.i(LogArea.AUDIO, "client_playback_paused")
        }
    }

    fun stopPlayback() {
        scheduledStartNanos = null
        renderedUntilNanos = null
        startJob?.cancel()
        startJob = null
        audioTrack?.run { pause(); flush() }
        focus.abandon()
        queue.clear()
        _state.value = PlaybackState.STOPPED
        AppLog.i(LogArea.AUDIO, "client_playback_stopped")
    }

    private suspend fun renderLoop() {
        while (scope != null) {
            val start = scheduledStartNanos
            val track = audioTrack
            if (start == null || track == null) {
                delay(5)
                continue
            }
            // Write while AudioTrack is paused so its hardware buffer is ready
            // before the separately scheduled start boundary.
            val polled = queue.poll(SystemClock.elapsedRealtimeNanos())
            val packet = polled.packet
            if (packet == null) {
                delay(2)
            } else {
                if (polled.skippedPackets > 0) {
                    AppLog.w(
                        LogArea.NETWORK,
                        "media_packets_lost",
                        null,
                        "count" to polled.skippedPackets,
                        "nextSequence" to packet.sequence,
                    )
                }
                val currentOffset = streamClockOffsetNanos ?: continue
                targetStreamClockOffsetNanos?.let { target ->
                    streamClockOffsetNanos = currentOffset +
                        (target - currentOffset).coerceIn(
                            -MAX_CLOCK_STEP_NANOS,
                            MAX_CLOCK_STEP_NANOS,
                        )
                }
                val bytesPerFrame = packet.channelCount * 2
                val packetFrames = packet.payload.size / bytesPerFrame
                val clockOffset = streamClockOffsetNanos ?: continue
                val localPacketTime = packet.presentationTimeNanos + clockOffset
                val packetEnd = localPacketTime +
                    packetFrames * 1_000_000_000L / packet.sampleRate
                val renderedUntil = renderedUntilNanos ?: localPacketTime
                if (packetEnd <= renderedUntil) continue

                val gapNanos = localPacketTime - renderedUntil
                if (gapNanos > 0) {
                    AppLog.w(
                        LogArea.AUDIO,
                        "packet_gap",
                        null,
                        "gapMs" to gapNanos / 1_000_000,
                        "sequence" to packet.sequence,
                    )
                    val gapFrames = (gapNanos * packet.sampleRate / 1_000_000_000L)
                        .coerceAtMost(packet.sampleRate.toLong() / 5)
                    if (gapFrames > 0) {
                        val silence = ByteArray((gapFrames * bytesPerFrame).toInt())
                        PcmWritePump.writeFully(track, silence, OWNER) {
                            framesWritten += it / bytesPerFrame
                        }
                    }
                }
                PcmWritePump.writeFully(track, packet.payload, OWNER) {
                    framesWritten += it / bytesPerFrame
                }
                renderedUntilNanos = maxOf(renderedUntil, packetEnd)
                if (packet.sequence % 200L == 0L) {
                    val timestamp = AudioTimestamp()
                    val timestampAvailable = track.getTimestamp(timestamp)
                    AppLog.d(
                        LogArea.AUDIO,
                        "client_frames_written",
                        "sequence" to packet.sequence,
                        "writtenFrames" to framesWritten,
                        "headFrames" to track.playbackHeadPosition,
                        "underruns" to track.underrunCount,
                        "queuedPackets" to queue.size(),
                        "clockCorrectionUs" to
                            ((targetStreamClockOffsetNanos ?: clockOffset) - clockOffset) / 1_000,
                        "audioTimestampAvailable" to timestampAvailable,
                        "presentedFrame" to if (timestampAvailable) timestamp.framePosition else null,
                        "presentedAtNanos" to if (timestampAvailable) timestamp.nanoTime else null,
                    )
                }
            }
        }
    }

    fun close() {
        scope?.cancel()
        scope = null
        startJob?.cancel()
        startJob = null
        runCatching { socket?.close() }
        socket = null
        allowedHost = null
        invalidPacketCount = 0
        framesWritten = 0L
        renderedUntilNanos = null
        streamClockOffsetNanos = null
        targetStreamClockOffsetNanos = null
        audioTrack?.release()
        audioTrack = null
        focus.abandon()
        queue.clear()
        _state.value = PlaybackState.IDLE
    }

    companion object {
        private const val OWNER = "client-music"
        private const val MAX_CLOCK_STEP_NANOS = 50_000L
    }
}
