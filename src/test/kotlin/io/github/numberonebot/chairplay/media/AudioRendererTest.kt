package io.github.numberonebot.chairplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.media.EchoReference
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.concentus.OpusApplication
import org.concentus.OpusEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** Nothing here opens a real SourceDataLine: the renderer is given a line that records its writes. */
class AudioRendererTest {
    private class RecordingLine : PcmLine {
        val written = ByteArrayOutputStream()
        @Volatile var started = false
        @Volatile var closed = false
        override fun start() { started = true }
        @Synchronized override fun write(data: ByteArray, length: Int) { written.write(data, 0, length) }
        @Synchronized fun size() = written.size()
        @Volatile var played: Long? = null
        override fun playedFrames() = played
        override fun close() { closed = true }
    }

    private fun header(cc: Int = 0, sample: Int = 0): ByteArray {
        val rtp = ByteArray(12 + 4 * cc)
        rtp[0] = (0x80 or cc).toByte()
        rtp[4] = (sample ushr 24).toByte()
        rtp[5] = (sample ushr 16).toByte()
        rtp[6] = (sample ushr 8).toByte()
        rtp[7] = sample.toByte()
        return rtp
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    @Test
    fun `strips rtp header with csrc`() {
        val payload = byteArrayOf(9, 8, 7)
        assertArrayEquals(payload, rtpPayload(header(cc = 0) + payload))
        assertArrayEquals(payload, rtpPayload(header(cc = 2) + payload))
        // The payload starts at byte 20 when two CSRC entries follow the fixed header.
        val rtp = ByteArray(24) { it.toByte() }.also { it[0] = 0x82.toByte() }
        assertEquals(20.toByte(), rtpPayload(rtp)[0])
        assertEquals(0, rtpPayload(ByteArray(11)).size)
    }

    @Test
    fun `lpcm swapped to little endian`() {
        assertArrayEquals(byteArrayOf(0x34, 0x12), lpcmBigEndianToLittle(byteArrayOf(0x12, 0x34)))
        assertArrayEquals(byteArrayOf(2, 1, 4, 3), lpcmBigEndianToLittle(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `opus packet decodes to 960 samples at 48k`() {
        val encoder = OpusEncoder(48_000, 2, OpusApplication.OPUS_APPLICATION_AUDIO)
        val pcm = ShortArray(960 * 2) { (sin(2 * PI * 440 * (it / 2) / 48_000) * 8000).toInt().toShort() }
        val packet = ByteArray(1275)
        val length = encoder.encode(pcm, 0, 960, packet, 0, packet.size)
        OpusPcmDecoder(48_000, 2).use { decoder ->
            assertEquals(960 * 2 * 2, decoder.decode(packet.copyOf(length)).size)
        }
    }

    @Test
    fun `aac access unit decodes to 1024 samples`() {
        val units = encodeAac(44_100, 2)
        AacPcmDecoder(44_100, 2).use { decoder ->
            assertEquals(1024 * 2 * 2, decoder.decode(units.first()).size)
        }
    }

    @Test
    fun `late packet older than playhead dropped`() {
        val line = RecordingLine()
        val renderer = AudioRenderer(lineFactory = { _, _, _ -> line })
        val id = AudioStreamId(101, "media")
        renderer.start(id, AudioFormat(AudioCodecKind.LPCM, 44_100, 2, 96, "media"))
        val body = ByteArray(8)
        renderer.push(id, header(sample = 0) + body, 0)
        renderer.push(id, header(sample = 4800) + body, 4800)
        renderer.push(id, header(sample = 960) + body, 960)
        assertEquals(1, renderer.droppedPackets(id))
        renderer.close()
        assertTrue(line.closed)
    }

    // As AndroidMediaSink does, a call stream hands what it writes, and the line's queue, to the echo reference.
    @Test
    fun `a call stream feeds its echo reference with what it writes`() {
        val line = RecordingLine().apply { played = 0L }
        val renderer = AudioRenderer(lineFactory = { _, _, _ -> line })
        val id = AudioStreamId(100, "telephony")
        val reference = EchoReference(48_000)
        renderer.start(id, AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 100, "telephony"), reference)
        // 10 ms of mono big-endian LPCM at 48 kHz, every sample 0x1000; four fill the 40 ms jitter buffer.
        val tenMs = ByteArray(480 * 2) { if (it % 2 == 0) 0x10 else 0 }
        repeat(4) { renderer.push(id, header(sample = it * 480) + tenMs, it * 480) }
        awaitTrue("playback") { line.size() == 4 * tenMs.size }

        val out = ShortArray(480)
        assertTrue("the reference hears the call", reference.read(out, System.nanoTime() + 20_000_000L))
        assertTrue(out.all { it == 0x1000.toShort() })
        renderer.stop(id)
    }

    // A reconnect or a close fades the sound out over a second instead of cutting it.
    @Test
    fun `the fade gain falls from one to zero over its duration`() {
        assertEquals(1f, fadeGain(startNs = 100, nowNs = 100, durationNs = 1_000), 0f)
        assertEquals(0.5f, fadeGain(startNs = 100, nowNs = 600, durationNs = 1_000), 0.001f)
        assertEquals(0f, fadeGain(startNs = 100, nowNs = 5_000, durationNs = 1_000), 0f)
        assertEquals(1f, fadeGain(startNs = NO_FADE, nowNs = 5_000, durationNs = 1_000), 0f)
    }

    @Test
    fun `a gain scales little-endian 16-bit samples`() {
        val pcm = byteArrayOf(0x00, 0x10, 0x00, 0xf0.toByte(), 0x11, 0x22)
        scaleSamples(pcm, 4, 0.5f)
        // 0x1000 halves to 0x0800, -0x1000 to -0x0800; bytes past the length stay.
        assertEquals(listOf<Byte>(0x00, 0x08, 0x00, 0xf8.toByte(), 0x11, 0x22), pcm.toList())
    }

    @Test
    fun `a faded stream writes silence`() {
        val line = RecordingLine()
        val renderer = AudioRenderer(lineFactory = { _, _, _ -> line })
        val id = AudioStreamId(100, "alert")
        renderer.start(id, AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 100, "alert"))
        val tenMs = ByteArray(480 * 2) { if (it % 2 == 0) 0x10 else 0 }
        repeat(4) { renderer.push(id, header(sample = it * 480) + tenMs, it * 480) }
        awaitTrue("playback") { line.size() == 4 * tenMs.size }

        renderer.fadeOut(durationMillis = 1)
        Thread.sleep(20)
        renderer.push(id, header(sample = 4 * 480) + tenMs, 4 * 480)
        awaitTrue("the faded packet") { line.size() == 5 * tenMs.size }

        val bytes = synchronized(line) { line.written.toByteArray() }
        assertTrue(bytes.copyOfRange(4 * tenMs.size, 5 * tenMs.size).all { it == 0.toByte() })
        renderer.stop(id)
    }

    @Test
    fun `playback starts after the jitter buffer fills`() {
        val line = RecordingLine()
        val renderer = AudioRenderer(lineFactory = { _, _, _ -> line })
        val id = AudioStreamId(100, "alert")
        renderer.start(id, AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "alert"))
        // 10 ms of stereo 16-bit at 48 kHz: the 40 ms buffer of a non-media stream needs four of them.
        val tenMs = ByteArray(480 * 4)
        repeat(3) { renderer.push(id, header(sample = it * 480) + tenMs, it * 480) }
        Thread.sleep(150)
        assertEquals(0, line.size())
        renderer.push(id, header(sample = 3 * 480) + tenMs, 3 * 480)
        awaitTrue("playback") { line.size() == 4 * tenMs.size }
        assertTrue(line.started)
        renderer.stop(id)
        assertTrue(line.closed)
    }

    @Test
    fun `undecodable stream is dropped without throwing`() {
        val logs = CopyOnWriteArrayList<String>()
        val renderer = AudioRenderer(log = { logs += it }, lineFactory = { _, _, _ -> RecordingLine() })
        val id = AudioStreamId(101, "media")
        renderer.start(id, AudioFormat(AudioCodecKind.OPUS, 48_000, 6, 96, "media"))
        renderer.push(id, header() + ByteArray(8), 0)
        renderer.stop(id)
        assertEquals(1, logs.size)
        renderer.close()
    }

    @Test
    fun `a corrupt packet between good ones costs only its own audio`() {
        val units = encodeAac(44_100, 2, count = 6)
        val corrupt = ByteArray(64) { 0xff.toByte() }
        // The packet must really make the decoder throw, or this test proves nothing.
        AacPcmDecoder(44_100, 2).use { decoder ->
            assertTrue(runCatching { decoder.decode(corrupt) }.isFailure)
        }
        val line = RecordingLine()
        val logs = CopyOnWriteArrayList<String>()
        val renderer = AudioRenderer(log = { logs += it }, lineFactory = { _, _, _ -> line })
        val id = AudioStreamId(101, "media")
        renderer.start(id, AudioFormat(AudioCodecKind.AAC_LC, 44_100, 2, 96, "media"))
        val packets = units.take(3) + listOf(corrupt) + units.drop(3)
        packets.forEachIndexed { index, payload -> renderer.push(id, header(sample = index * 1024) + payload, index * 1024) }

        // Six good access units of 1024 stereo 16-bit samples each.
        awaitTrue("audio after the corrupt packet") { line.size() == 6 * 1024 * 2 * 2 }
        assertTrue(logs.toString(), logs.any { "decode errors" in it })
        assertTrue(logs.none { "stopped" in it })
        renderer.close()
    }

    private val tenMs = ByteArray(480 * 4)

    private fun alertRenderer(line: RecordingLine, clock: AtomicLong, logs: MutableList<String> = CopyOnWriteArrayList()) =
        AudioRenderer({ logs += it }, { _, _, _ -> line }, { clock.get() }, 5)

    private val alert = AudioStreamId(100, "alert")
    private val alertFormat = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "alert")

    @Test
    fun `starved line rebuffers before writing again`() {
        val line = RecordingLine()
        val clock = AtomicLong(1)
        val logs = CopyOnWriteArrayList<String>()
        val renderer = alertRenderer(line, clock, logs)
        renderer.start(alert, alertFormat)
        repeat(4) { renderer.push(alert, header(sample = it * 480) + tenMs, it * 480) }
        awaitTrue("first playback") { line.size() == 4 * tenMs.size }
        // The 40 ms written have long been played: the next packet finds an empty line.
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1))
        renderer.push(alert, header(sample = 4 * 480) + tenMs, 4 * 480)
        awaitTrue("underrun noticed") { logs.any { "underrun" in it } }
        Thread.sleep(50)
        assertEquals(4 * tenMs.size, line.size())
        repeat(3) { renderer.push(alert, header(sample = (5 + it) * 480) + tenMs, (5 + it) * 480) }
        awaitTrue("playback after rebuffer") { line.size() == 8 * tenMs.size }
        renderer.close()
    }

    @Test
    fun `short sound below the threshold is played after the tail wait`() {
        val line = RecordingLine()
        val clock = AtomicLong(1)
        val renderer = alertRenderer(line, clock)
        renderer.start(alert, alertFormat)
        renderer.push(alert, header() + tenMs, 0)
        Thread.sleep(50)
        assertEquals(0, line.size())
        clock.addAndGet(AudioRenderer.BUFFER_TAIL_WAIT_NS + 1)
        awaitTrue("tail played") { line.size() == tenMs.size }
        renderer.close()
    }

    @Test
    fun `burst far beyond the target sheds the oldest audio`() {
        val line = RecordingLine()
        val clock = AtomicLong(1)
        val logs = CopyOnWriteArrayList<String>()
        val opened = CountDownLatch(1)
        // The writer waits in open(), so the queue is not drained while the burst arrives.
        val renderer = AudioRenderer({ logs += it }, { _, _, _ -> opened.await(); line }, { clock.get() }, 5)
        renderer.start(alert, alertFormat)
        repeat(200) { renderer.push(alert, header(sample = it * 480) + tenMs, it * 480) }
        val dropped = renderer.droppedPackets(alert)
        // 2 s arrived; the queue was cut to 0.5 s when it passed 1.5 s and has grown by what followed.
        assertTrue("dropped $dropped", dropped in 90..110)
        assertTrue(logs.any { "shed" in it })
        opened.countDown()
        awaitTrue("newest audio played") { line.size() == (200 - dropped) * tenMs.size }
        renderer.close()
    }

    /** Raw AAC access units from FFmpeg's own encoder, one per 1024-sample frame. */
    private fun encodeAac(rate: Int, channels: Int, count: Int = 1): List<ByteArray> {
        av_log_set_level(AV_LOG_ERROR)
        val codec = avcodec_find_encoder(AV_CODEC_ID_AAC)
        val context: AVCodecContext = avcodec_alloc_context3(codec)
        val frame: AVFrame = av_frame_alloc()
        val packet: AVPacket = av_packet_alloc()
        try {
            context.sample_fmt(AV_SAMPLE_FMT_FLTP)
            context.sample_rate(rate)
            context.bit_rate(96_000)
            av_channel_layout_default(context.ch_layout(), channels)
            check(avcodec_open2(context, codec, null as AVDictionary?) >= 0)
            frame.nb_samples(1024)
            frame.format(AV_SAMPLE_FMT_FLTP)
            frame.sample_rate(rate)
            av_channel_layout_default(frame.ch_layout(), channels)
            check(av_frame_get_buffer(frame, 0) >= 0)
            val units = ArrayList<ByteArray>()
            var tick = 0
            while (units.size < count && tick < 16 + count) {
                check(av_frame_make_writable(frame) >= 0)
                for (c in 0 until channels) {
                    val samples = frame.data(c).capacity(1024L * 4).asByteBuffer().asFloatBuffer()
                    for (i in 0 until 1024) samples.put(i, sin(2 * PI * 440 * (tick * 1024 + i) / rate).toFloat() * 0.3f)
                }
                frame.pts(tick * 1024L)
                check(avcodec_send_frame(context, frame) >= 0)
                while (avcodec_receive_packet(context, packet) >= 0) {
                    val bytes = ByteArray(packet.size())
                    packet.data().get(bytes)
                    units += bytes
                    av_packet_unref(packet)
                }
                tick++
            }
            return units
        } finally {
            avcodec_free_context(context)
            av_frame_free(frame)
            av_packet_free(packet)
        }
    }
}
