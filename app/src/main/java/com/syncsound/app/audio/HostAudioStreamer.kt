package com.syncsound.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
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
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Decodes an MP3 with Android's platform codec, plays it locally, and sends the
 * same PCM timeline to approved Clients. The object owns one playback lifecycle.
 */
class HostAudioStreamer(private val context: Context) {
    interface Listener {
        fun onFormat(sampleRate: Int, channels: Int)
        fun onStartScheduled(hostTimeNanos: Long)
        fun approvedTargets(): List<InetAddress>
        fun onFailure(message: String)
        fun onCompleted()
    }

    private var scope: CoroutineScope? = null
    private var decodeJob: Job? = null
    private var selectedUri: Uri? = null
    private var listener: Listener? = null
    private var track: AudioTrack? = null
    private val paused = AtomicBoolean(false)
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state

    fun select(uri: Uri) {
        selectedUri = uri
        _state.value = PlaybackState.IDLE
    }

    fun play(listener: Listener) {
        this.listener = listener
        if (paused.compareAndSet(true, false)) {
            val start = SystemClock.elapsedRealtimeNanos() + Protocol.START_LEAD_TIME_MS * 1_000_000
            listener.onStartScheduled(start)
            scope?.launch {
                waitUntil(start)
                track?.play()
            }
            _state.value = PlaybackState.BUFFERING
            return
        }
        if (decodeJob?.isActive == true) return
        val uri = selectedUri ?: run {
            listener.onFailure("Select an MP3 file first")
            return
        }
        scope?.cancel()
        track?.release()
        track = null
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        decodeJob = scope!!.launch { decodeAndStream(uri, listener) }
    }

    fun pauseAt(hostTimeNanos: Long) {
        if (decodeJob?.isActive != true) return
        scope?.launch {
            waitUntil(hostTimeNanos)
            paused.set(true)
            track?.pause()
            _state.value = PlaybackState.PAUSED
        }
    }

    fun stop() {
        paused.set(false)
        decodeJob?.cancel()
        decodeJob = null
        scope?.cancel()
        scope = null
        track?.release()
        track = null
        _state.value = PlaybackState.STOPPED
    }

    private suspend fun decodeAndStream(uri: Uri, listener: Listener) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val udp = DatagramSocket()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("The selected file has no audio track")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio format")
            require(mime == MediaFormat.MIMETYPE_AUDIO_MPEG) { "Version 1 supports MP3 files only" }
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var formatReady = false
            var sampleRate = 0
            var channels = 0
            var bytesPerFrame = 0
            var sequence = 0L
            var framesSent = 0L
            val streamId = System.nanoTime()
            val startTime = SystemClock.elapsedRealtimeNanos() +
                Protocol.START_LEAD_TIME_MS * 1_000_000

            while (!outputEnded) {
                while (paused.get()) delay(5)
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("Decoder input unavailable")
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                        bytesPerFrame = channels * 2
                        track = createAudioTrack(sampleRate, channels)
                        listener.onFormat(sampleRate, channels)
                        listener.onStartScheduled(startTime)
                        scope?.launch {
                            waitUntil(startTime)
                            track?.play()
                            _state.value = PlaybackState.PLAYING
                        }
                        formatReady = true
                        _state.value = PlaybackState.BUFFERING
                    }
                    else -> if (outputIndex >= 0) {
                        check(formatReady)
                        val output = codec.getOutputBuffer(outputIndex) ?: error("Decoder output unavailable")
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size)
                        output.get(pcm)
                        var offset = 0
                        val alignedMaximum = Protocol.MAX_PCM_PAYLOAD -
                            (Protocol.MAX_PCM_PAYLOAD % bytesPerFrame)
                        while (offset < pcm.size) {
                            while (paused.get()) delay(5)
                            val length = min(alignedMaximum, pcm.size - offset)
                                .let { it - (it % bytesPerFrame) }
                            if (length == 0) break
                            val timestamp = startTime +
                                framesSent * 1_000_000_000L / sampleRate
                            paceAhead(timestamp)
                            val payload = pcm.copyOfRange(offset, offset + length)
                            val packet = PcmPacket(
                                streamId, sequence++, timestamp,
                                sampleRate, channels, payload,
                            ).encode()
                            listener.approvedTargets().forEach { target ->
                                runCatching {
                                    udp.send(DatagramPacket(packet, packet.size, target, Protocol.MEDIA_PORT))
                                }
                            }
                            track?.write(payload, 0, payload.size, AudioTrack.WRITE_BLOCKING)
                            framesSent += length / bytesPerFrame
                            offset += length
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            // Packets are intentionally sent ahead of presentation. Wait for the
            // final scheduled audio to drain before ending every device.
            delay(750)
            _state.value = PlaybackState.STOPPED
            listener.onCompleted()
        } catch (exception: Exception) {
            listener.onFailure(exception.message ?: "Audio playback failed")
            _state.value = PlaybackState.STOPPED
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            udp.close()
            track?.release()
            track = null
        }
    }

    private fun createAudioTrack(sampleRate: Int, channels: Int): AudioTrack {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum * 4, sampleRate * channels))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private suspend fun waitUntil(targetNanos: Long) {
        while (SystemClock.elapsedRealtimeNanos() < targetNanos) delay(1)
    }

    private suspend fun paceAhead(presentationNanos: Long) {
        val maximumLead = 700_000_000L
        while (presentationNanos - SystemClock.elapsedRealtimeNanos() > maximumLead) delay(2)
    }
}
