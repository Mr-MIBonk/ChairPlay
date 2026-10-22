package io.github.numberonebot.chairplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.media.EchoReference
import com.shilapi.xcertplay.media.MediaCodecSupport
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.concentus.OpusDecoder
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.AudioFormat as JavaAudioFormat

private const val RTP_HEADER_LEN = 12

/**
 * The RTP payload of [rtp]: past the fixed header and any CSRC entries. [AudioStream][com.shilapi.xcertplay.airplay.AudioStream]
 * hands over the 12-byte header followed by the already decrypted payload (the AEAD tag and nonce are
 * gone), so for CarPlay streams, which carry no CSRC, this is simply byte 12 onwards.
 */
fun rtpPayload(rtp: ByteArray): ByteArray {
    val start = RTP_HEADER_LEN + 4 * (rtp[0].toInt() and 0x0f)
    return if (rtp.size <= start) ByteArray(0) else rtp.copyOfRange(start, rtp.size)
}

/** CarPlay's LPCM is big-endian 16-bit; Java Sound's PCM here is little-endian. A stray odd byte is dropped. */
fun lpcmBigEndianToLittle(payload: ByteArray): ByteArray {
    val out = ByteArray(payload.size and 1.inv())
    for (i in out.indices step 2) {
        out[i] = payload[i + 1]
        out[i + 1] = payload[i]
    }
    return out
}

/** Where decoded 16-bit little-endian PCM ends up; a seam so tests never touch a sound device. */
/** No fade-out in progress. */
internal const val NO_FADE = Long.MIN_VALUE

/** The gain of a fade-out begun at [startNs] and lasting [durationNs]: 1 until it starts, 0 once over, 1 with none. */
internal fun fadeGain(startNs: Long, nowNs: Long, durationNs: Long): Float {
    if (startNs == NO_FADE) return 1f
    val elapsed = nowNs - startNs
    return when {
        elapsed <= 0 -> 1f
        elapsed >= durationNs -> 0f
        else -> 1f - elapsed.toFloat() / durationNs
    }
}

/** Multiplies the first [length] bytes of little-endian 16-bit PCM by [gain], in place. */
internal fun scaleSamples(pcm: ByteArray, length: Int, gain: Float) {
    var at = 0
    while (at + 1 < length) {
        val sample = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xFF)).toShort()
        val scaled = (sample * gain).toInt()
        pcm[at] = scaled.toByte()
        pcm[at + 1] = (scaled shr 8).toByte()
        at += 2
    }
}

internal interface PcmLine : Closeable {
    fun start()
    fun write(data: ByteArray, length: Int)
    /** Frames the device has played so far, as AudioTrack's playback head; null when unknown. */
    fun playedFrames(): Long? = null
}

internal fun interface PcmLineFactory {
    fun open(sampleRate: Int, channels: Int, bufferBytes: Int): PcmLine
}

private class JavaSoundLine(private val line: SourceDataLine) : PcmLine {
    override fun start() = line.start()
    override fun write(data: ByteArray, length: Int) {
        line.write(data, 0, length)
    }

    override fun playedFrames(): Long = line.longFramePosition

    override fun close() {
        line.stop()
        line.flush()
        line.close()
    }
}

/** Lines on [device], or on the Windows default output when it is null. */
private fun javaSoundLines(device: Mixer.Info?) = PcmLineFactory { sampleRate, channels, bufferBytes ->
    val format = JavaAudioFormat(sampleRate.toFloat(), 16, channels, true, false)
    val line = if (device == null) AudioSystem.getSourceDataLine(format) else AudioSystem.getSourceDataLine(format, device)
    line.open(format, bufferBytes)
    JavaSoundLine(line)
}

/** Turns one RTP payload into 16-bit little-endian interleaved PCM; owned by a single writer thread. */
internal interface PcmDecoder : Closeable {
    fun decode(payload: ByteArray): ByteArray
}

internal class LpcmDecoder : PcmDecoder {
    override fun decode(payload: ByteArray) = lpcmBigEndianToLittle(payload)
    override fun close() {}
}

internal class OpusPcmDecoder(sampleRate: Int, private val channels: Int) : PcmDecoder {
    private val decoder = OpusDecoder(sampleRate, channels)
    private val pcm = ShortArray(MAX_FRAME_SAMPLES * channels)

    override fun decode(payload: ByteArray): ByteArray {
        val samples = decoder.decode(payload, 0, payload.size, pcm, 0, MAX_FRAME_SAMPLES, false)
        val out = ByteArray(samples * channels * 2)
        for (i in 0 until samples * channels) {
            out[2 * i] = pcm[i].toByte()
            out[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        return out
    }

    override fun close() {}

    private companion object {
        // 120 ms at 48 kHz, the longest Opus packet.
        const val MAX_FRAME_SAMPLES = 5760
    }
}

/**
 * AAC-LC over FFmpeg's `aac` decoder. The access unit is wrapped in an ADTS header exactly as the
 * Android sink does, so the decoder needs no extradata; its planar float output is interleaved to
 * 16-bit here.
 */
internal class AacPcmDecoder(private val sampleRate: Int, private val channels: Int) : PcmDecoder {
    private val context: AVCodecContext
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private var closed = false

    init {
        val codec = avcodec_find_decoder(AV_CODEC_ID_AAC) ?: error("FFmpeg has no AAC decoder")
        context = avcodec_alloc_context3(codec)
        check(avcodec_open2(context, codec, null as AVDictionary?) >= 0) { "cannot open the AAC decoder" }
    }

    override fun decode(payload: ByteArray): ByteArray {
        if (closed || payload.isEmpty()) return ByteArray(0)
        val adts = MediaCodecSupport.adtsFrame(payload, sampleRate, channels)
        check(av_new_packet(packet, adts.size) >= 0) { "cannot allocate a packet of ${adts.size} bytes" }
        try {
            packet.data().put(adts, 0, adts.size)
            val sent = avcodec_send_packet(context, packet)
            check(sent >= 0) { "send_packet failed ($sent)" }
        } finally {
            av_packet_unref(packet)
        }
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val received = avcodec_receive_frame(context, frame)
            if (received == AVERROR_EAGAIN() || received == AVERROR_EOF) break
            check(received >= 0) { "receive_frame failed ($received)" }
            try {
                interleave(out)
            } finally {
                av_frame_unref(frame)
            }
        }
        return out.toByteArray()
    }

    private fun interleave(out: java.io.ByteArrayOutputStream) {
        val samples = frame.nb_samples()
        val planar = av_sample_fmt_is_planar(frame.format()) != 0
        val decodedChannels = frame.ch_layout().nb_channels()
        val bytesPerSample = av_get_bytes_per_sample(frame.format())
        check(frame.format() == AV_SAMPLE_FMT_FLTP || frame.format() == AV_SAMPLE_FMT_FLT) {
            "unsupported sample format ${frame.format()}"
        }
        val planes = if (planar) decodedChannels else 1
        val floats = Array(planes) {
            frame.data(it).capacity(samples.toLong() * bytesPerSample * (if (planar) 1 else decodedChannels))
                .asByteBuffer().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        }
        val pcm = ByteArray(samples * decodedChannels * 2)
        for (i in 0 until samples) {
            for (c in 0 until decodedChannels) {
                val value = if (planar) floats[c].get(i) else floats[0].get(i * decodedChannels + c)
                val s = (value * 32768f).toInt().coerceIn(-32768, 32767)
                val at = 2 * (i * decodedChannels + c)
                pcm[at] = s.toByte()
                pcm[at + 1] = (s shr 8).toByte()
            }
        }
        out.write(pcm)
    }

    override fun close() {
        if (closed) return
        closed = true
        avcodec_free_context(context)
        av_packet_free(packet)
        av_frame_free(frame)
    }
}

/**
 * Plays CarPlay audio streams through Java Sound, one line per [AudioStreamId]; Windows mixes the lines.
 *
 * Threading: a stream's RTP thread calls [push], which only judges the packet against the playhead and
 * queues it. A writer thread per stream owns the decoder and the line, decodes, holds back playback until
 * the jitter buffer has filled, and then writes (blocking when the line is full).
 *
 * Buffer maintenance follows AndroidMediaSink.maintainPlaybackBuffer: when the line has run dry the stream
 * goes back to holding audio until the jitter buffer refills, and a short tail that never reaches the
 * threshold is played after [BUFFER_TAIL_WAIT_NS]. Queued audio more than [SHED_ABOVE_NS] behind the newest
 * packet is trimmed to [SHED_TO_NS] from the oldest end, the bounds of MediaPacketLagGuard
 * (CONGESTION_THRESHOLD_NS and RECOVERY_TARGET_NS) but without its 1.5 s grace period.
 * Drops, underruns, undecodable packets and shedding are logged once per class and then at most every [DIAGNOSTIC_INTERVAL_NS].
 */
class AudioRenderer internal constructor(
    private val log: (String) -> Unit = {},
    private val lineFactory: PcmLineFactory = javaSoundLines(null),
    private val clock: () -> Long = System::nanoTime,
    private val pollMillis: Long = POLL_MILLIS,
) : Closeable {
    /** Plays on [output], or on the Windows default output when it is null (audio.output). */
    constructor(log: (String) -> Unit, output: Mixer.Info? = null) : this(log, javaSoundLines(output))

    private val streams = ConcurrentHashMap<AudioStreamId, Stream>()

    /** [echoReference], for a call, receives what this stream plays, for the microphone's echo canceller. */
    internal fun start(id: AudioStreamId, format: AudioFormat, echoReference: EchoReference? = null) {
        streams.remove(id)?.close()
        val supported = format.channels in 1..2 && format.sampleRate > 0
        if (!supported) {
            log("audio ${id.audioType}: ${format.codec} with ${format.channels} channels at ${format.sampleRate} Hz is not supported; dropped")
            return
        }
        streams[id] = Stream(id, format, echoReference).also { it.thread.start() }
    }

    fun push(id: AudioStreamId, rtp: ByteArray, sample: Int) {
        streams[id]?.push(rtp, sample)
    }

    /**
     * Fades every playing stream out over [durationMillis], for a reconnect or a close that ends the session
     * gracefully, which takes seconds. Streams started afterwards, by the next session, play at full volume.
     */
    fun fadeOut(durationMillis: Long) {
        val now = clock()
        streams.values.forEach { it.fadeOut(now, durationMillis * 1_000_000) }
    }

    fun stop(id: AudioStreamId) {
        streams.remove(id)?.close()
    }

    /** Packets [id] refused as older than its playhead or because its queue was full. */
    internal fun droppedPackets(id: AudioStreamId) = streams[id]?.dropped?.get() ?: 0

    override fun close() {
        streams.keys.toList().forEach(::stop)
    }

    private inner class Stream(
        private val id: AudioStreamId,
        private val format: AudioFormat,
        private val echoReference: EchoReference?,
    ) : Closeable {
        val dropped = AtomicInteger()
        private val counts = HashMap<String, Int>()
        private val lastLogged = HashMap<String, Long>()
        val thread = Thread(::run, "audio-${id.audioType}-${id.type}").apply { isDaemon = true }
        private val queue = LinkedBlockingQueue<QueuedPacket>(MAX_QUEUED_PACKETS)
        @Volatile private var running = true
        private var playhead: Int? = null
        private val frameBytes = 2 * format.channels
        @Volatile private var fadeStartNs = NO_FADE
        @Volatile private var fadeNs = 0L

        fun fadeOut(startNs: Long, durationNs: Long) {
            fadeNs = durationNs
            fadeStartNs = startNs
        }
        private val jitterBytes =
            format.sampleRate * frameBytes * (if (id.audioType == "media") MEDIA_JITTER_MS else JITTER_MS) / 1000

        /** Stream thread. Newer than or equal to the playhead is accepted; the iPhone's clock restarting is not "late". */
        fun push(rtp: ByteArray, sample: Int) {
            val head = playhead
            val behind = if (head == null) 0 else head - sample
            if (behind in 1..format.rtpClockRate * RESTART_SECONDS) {
                dropped.incrementAndGet()
                note("late packets dropped")
                return
            }
            playhead = sample
            if (!queue.offer(QueuedPacket(rtp, sample))) {
                dropped.incrementAndGet()
                note("packets dropped, queue full")
            }
            shedExcess(sample)
        }

        private fun spanNanos(oldest: Int, newest: Int) =
            (newest - oldest).toLong() * 1_000_000_000L / format.rtpClockRate

        /** Stream thread. A burst after a stall would otherwise stay queued as latency for good. */
        private fun shedExcess(newest: Int) {
            val oldest = queue.peek() ?: return
            if (spanNanos(oldest.sample, newest) !in SHED_ABOVE_NS..MAX_VALID_SPAN_NS) return
            var shed = 0
            while (queue.peek()?.let { spanNanos(it.sample, newest) > SHED_TO_NS } == true && queue.poll() != null) shed++
            dropped.addAndGet(shed)
            note("queued audio shed to ${SHED_TO_NS / 1_000_000} ms", shed)
        }

        /** Counts an event of one class; the first and then one per interval reach the log. */
        @Synchronized private fun note(what: String, n: Int = 1) {
            val total = (counts[what] ?: 0) + n
            counts[what] = total
            val now = clock()
            val last = lastLogged[what]
            if (last != null && now - last < DIAGNOSTIC_INTERVAL_NS) return
            lastLogged[what] = now
            log("audio ${id.audioType}: $what (total $total)")
        }

        private fun run() {
            var decoder: PcmDecoder? = null
            var line: PcmLine? = null
            try {
                decoder = when (format.codec) {
                    AudioCodecKind.OPUS -> OpusPcmDecoder(format.sampleRate, format.channels)
                    AudioCodecKind.AAC_LC -> AacPcmDecoder(format.sampleRate, format.channels)
                    AudioCodecKind.LPCM -> LpcmDecoder()
                }
                val bufferBytes = jitterBytes + format.sampleRate * frameBytes * LINE_HEADROOM_MS / 1000
                val opened = lineFactory.open(format.sampleRate, format.channels, bufferBytes)
                line = opened
                val held = java.io.ByteArrayOutputStream()
                var playing = false
                // When the audio already written has all been played, by the clock rather than the line.
                var playedUntil = 0L
                var lastPacketAt = clock()
                var writtenFrames = 0L
                fun play(pcm: ByteArray, length: Int) {
                    val gain = fadeGain(fadeStartNs, clock(), fadeNs)
                    if (gain < 1f) scaleSamples(pcm, length, gain)
                    opened.start()
                    opened.write(pcm, length)
                    writtenFrames += length / frameBytes
                    // AndroidMediaSink's call renderer: the block and how much is still queued ahead of the speaker.
                    echoReference?.let { reference ->
                        val pending = opened.playedFrames()?.let { writtenFrames - it }
                        reference.append(pcm, 0, length, format.channels, pending, clock())
                    }
                    playedUntil = maxOf(playedUntil, clock()) + length * 1_000_000_000L / (format.sampleRate * frameBytes)
                    playing = true
                }
                while (running) {
                    val packet = queue.poll(pollMillis, TimeUnit.MILLISECONDS)
                    val now = clock()
                    if (playing && now > playedUntil) {
                        playing = false
                        note("underrun, rebuffering")
                    }
                    if (packet == null) {
                        // A short final burst may never reach the threshold; play it after a bounded wait.
                        if (!playing && held.size() > 0 && now - lastPacketAt >= BUFFER_TAIL_WAIT_NS) {
                            play(held.toByteArray(), held.size())
                            held.reset()
                        }
                        continue
                    }
                    lastPacketAt = now
                    val pcm = try {
                        decoder.decode(payloadOf(packet.rtp))
                    } catch (_: Exception) {
                        // One corrupt packet costs its own audio only; the next one decodes on its own.
                        note("decode errors")
                        continue
                    }
                    if (pcm.isEmpty()) continue
                    if (playing) {
                        play(pcm, pcm.size)
                        continue
                    }
                    held.write(pcm)
                    if (held.size() >= jitterBytes) {
                        play(held.toByteArray(), held.size())
                        held.reset()
                    }
                }
            } catch (_: InterruptedException) {
                // stop() or close().
            } catch (error: Exception) {
                // A decoder that cannot be created or a device failure silences this stream only; the
                // others keep playing.
                if (running) log("audio ${id.audioType}: stopped after ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                runCatching { line?.close() }
                runCatching { decoder?.close() }
            }
        }

        private fun payloadOf(rtp: ByteArray) = rtpPayload(rtp).let {
            // Opus packets shorter than 4 bytes are DTX filler; the Android sink skips them as well.
            if (format.codec == AudioCodecKind.OPUS && it.size < MIN_OPUS_PACKET_BYTES) ByteArray(0) else it
        }

        override fun close() {
            running = false
            thread.interrupt()
            thread.join(JOIN_MILLIS)
        }
    }

    private class QueuedPacket(val rtp: ByteArray, val sample: Int)

    internal companion object {
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        const val SHED_ABOVE_NS = 1_500_000_000L
        // MediaPacketLagGuard.MAX_VALID_SPAN_SECONDS: a bigger jump is the iPhone restarting its clock.
        const val MAX_VALID_SPAN_NS = 16_000_000_000L
        const val SHED_TO_NS = 500_000_000L
        const val DIAGNOSTIC_INTERVAL_NS = 10_000_000_000L
        const val MEDIA_JITTER_MS = 60
        const val JITTER_MS = 40
        const val LINE_HEADROOM_MS = 140
        const val MAX_QUEUED_PACKETS = 256
        const val MIN_OPUS_PACKET_BYTES = 4
        const val POLL_MILLIS = 100L
        const val JOIN_MILLIS = 2000L
        // A timestamp further behind than this is the iPhone restarting its clock at a track boundary.
        const val RESTART_SECONDS = 2
    }
}
