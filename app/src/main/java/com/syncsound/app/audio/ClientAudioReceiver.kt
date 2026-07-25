package com.syncsound.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import com.syncsound.app.model.PlaybackState
import com.syncsound.app.network.Protocol
import kotlinx.coroutines.CoroutineScope
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
import java.util.TreeMap

/**
 * Receives timestamped PCM, reorders a small window, and feeds AudioTrack.
 * Playback remains paused while the queue fills, then starts on the shared time.
 */
class ClientAudioReceiver {
    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null
    private val queue = TreeMap<Long, PcmPacket>()
    private val lock = Any()
    @Volatile private var scheduledStartNanos: Long? = null
    @Volatile private var allowedHost: InetAddress? = null
    private var startJob: Job? = null
    private var renderedUntilNanos: Long? = null
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state

    fun start(host: InetAddress) {
        allowedHost = host
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { receiverScope ->
            receiverScope.launch {
                val udp = DatagramSocket(Protocol.MEDIA_PORT).apply { soTimeout = 500 }.also { socket = it }
                val bytes = ByteArray(Protocol.MAX_DATAGRAM_BYTES)
                while (isActive) {
                    try {
                        val datagram = DatagramPacket(bytes, bytes.size)
                        udp.receive(datagram)
                        if (datagram.address != allowedHost) continue
                        PcmPacket.decode(datagram.data, datagram.length)?.let { packet ->
                            synchronized(lock) {
                                queue[packet.sequence] = packet
                                while (queue.size > 400) queue.pollFirstEntry()
                            }
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
        startJob?.cancel()
        scheduledStartNanos = null
        renderedUntilNanos = null
        synchronized(lock) { queue.clear() }
        audioTrack?.release()
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum * 4, sampleRate * channels))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    fun playAt(localTimeNanos: Long) {
        val resuming = _state.value == PlaybackState.PAUSED
        scheduledStartNanos = localTimeNanos
        if (!resuming) renderedUntilNanos = localTimeNanos
        _state.value = PlaybackState.BUFFERING
        startJob?.cancel()
        startJob = scope?.launch {
            while (SystemClock.elapsedRealtimeNanos() < localTimeNanos) delay(1)
            audioTrack?.play()
            _state.value = PlaybackState.PLAYING
        }
    }

    fun pauseAt(localTimeNanos: Long) {
        startJob?.cancel()
        startJob = scope?.launch {
            while (SystemClock.elapsedRealtimeNanos() < localTimeNanos) delay(1)
            audioTrack?.pause()
            _state.value = PlaybackState.PAUSED
        }
    }

    fun stopPlayback() {
        scheduledStartNanos = null
        renderedUntilNanos = null
        startJob?.cancel()
        startJob = null
        audioTrack?.run { pause(); flush() }
        synchronized(lock) { queue.clear() }
        _state.value = PlaybackState.STOPPED
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
            val packet = synchronized(lock) {
                queue.pollFirstEntry()?.value
            }
            if (packet == null) {
                delay(2)
            } else {
                val bytesPerFrame = packet.channelCount * 2
                val packetFrames = packet.payload.size / bytesPerFrame
                val packetEnd = packet.presentationTimeNanos +
                    packetFrames * 1_000_000_000L / packet.sampleRate
                val renderedUntil = renderedUntilNanos ?: packet.presentationTimeNanos
                if (packetEnd <= renderedUntil) continue

                val gapNanos = packet.presentationTimeNanos - renderedUntil
                if (gapNanos > 0) {
                    val gapFrames = (gapNanos * packet.sampleRate / 1_000_000_000L)
                        .coerceAtMost(packet.sampleRate.toLong() / 5)
                    if (gapFrames > 0) {
                        val silence = ByteArray((gapFrames * bytesPerFrame).toInt())
                        track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                    }
                }
                track.write(packet.payload, 0, packet.payload.size, AudioTrack.WRITE_BLOCKING)
                renderedUntilNanos = maxOf(renderedUntil, packetEnd)
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
        renderedUntilNanos = null
        audioTrack?.release()
        audioTrack = null
        synchronized(lock) { queue.clear() }
        _state.value = PlaybackState.IDLE
    }
}
