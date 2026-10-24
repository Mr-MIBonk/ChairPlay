package io.github.numberonebot.chairplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** AudioRenderer: the gains on what a stream writes: the fades out and in, and the app's volume. */
class AudioRendererFadeTest : AudioRendererFixture() {
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

    // A disconnect or the app closing fades the sound out over the time the music fades in over (audio.start.fade.ms).
    @Test
    fun `a fade-out lasts as long as the music's fade-in`() {
        val line = RecordingLine()
        val clock = AtomicLong(1)
        val renderer = AudioRenderer(
            {}, { _, _, _ -> line }, { clock.get() }, 5,
            startFade = { StartFade(silenceMillis = 1000, fadeMillis = 100) },
        )
        val id = AudioStreamId(100, "alert")
        renderer.start(id, AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 96, "alert"))
        repeat(4) { renderer.push(id, header(sample = it * 480) + constantTenMs(1), it * 480) }
        awaitTrue("playback") { line.size() == 4 * 480 * 2 }

        renderer.fadeOut()
        // The line runs dry over the 50 ms, so the jitter buffer fills again before it plays.
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50))
        repeat(4) { renderer.push(id, header(sample = (4 + it) * 480) + constantTenMs(1), (4 + it) * 480) }
        awaitTrue("the packets half way through the fade") { line.size() == 8 * 480 * 2 }

        // Half way through 100 ms, 0x1000 plays at half.
        val faded = samples(synchronized(line) { line.written.toByteArray() }).drop(4 * 480)
        assertTrue(faded.toString(), faded.all { it in 0x07f0..0x0810 })
        renderer.close()
    }

    // The app's volume (Ctrl+Up and Ctrl+Down) scales every stream; Windows' own volume is left alone.
    @Test
    fun `the app's volume scales what a stream writes`() {
        val line = RecordingLine()
        val renderer = AudioRenderer(lineFactory = { _, _, _ -> line }, volume = { 0.5f })
        val id = AudioStreamId(100, "alert")
        renderer.start(id, AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 100, "alert"))
        // 10 ms of mono big-endian LPCM, every sample 0x1000; four fill the 40 ms jitter buffer.
        val tenMs = ByteArray(480 * 2) { if (it % 2 == 0) 0x10 else 0 }
        repeat(4) { renderer.push(id, header(sample = it * 480) + tenMs, it * 480) }
        awaitTrue("playback") { line.size() == 4 * tenMs.size }

        val bytes = synchronized(line) { line.written.toByteArray() }
        // 0x1000 halves to 0x0800, little-endian.
        assertTrue(bytes.toList().chunked(2).all { it == listOf<Byte>(0x00, 0x08) })
        renderer.stop(id)
    }

    // The iPhone's first second or two of music after a connection arrive in bursts, and the line runs dry: the music
    // starts silent and fades in after it, while a prompt plays from its first word.
    @Test
    fun `a media stream starts silent and fades in, a prompt at once`() {
        val lines = ConcurrentHashMap<Int, RecordingLine>()
        val clock = AtomicLong(1)
        val logs = CopyOnWriteArrayList<String>()
        val renderer = AudioRenderer(
            { logs += it }, { _, channels, _ -> RecordingLine().also { lines[channels] = it } }, { clock.get() }, 5,
            startFade = { StartFade(silenceMillis = 100, fadeMillis = 50) },
        )
        val media = AudioStreamId(102, "media")
        val prompt = AudioStreamId(101, "alert")
        renderer.start(media, AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"))
        renderer.start(prompt, AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 96, "alert"))

        repeat(6) { renderer.push(media, header(sample = it * 480) + constantTenMs(2), it * 480) }
        repeat(4) { renderer.push(prompt, header(sample = it * 480) + constantTenMs(1), it * 480) }
        awaitTrue("the first music") { lines[2]?.size() == 6 * 480 * 4 && lines[1]?.size() == 4 * 480 * 2 }
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(200))
        repeat(6) { renderer.push(media, header(sample = (6 + it) * 480) + constantTenMs(2), (6 + it) * 480) }
        awaitTrue("the music after the fade") { lines[2]?.size() == 12 * 480 * 4 }

        val music = samples(synchronized(lines[2]!!) { lines[2]!!.written.toByteArray() })
        assertTrue(music.take(6 * 480 * 2).all { it == 0 })
        assertTrue(music.drop(6 * 480 * 2).all { it == 0x1000 })
        assertTrue(samples(synchronized(lines[1]!!) { lines[1]!!.written.toByteArray() }).all { it == 0x1000 })
        // When the line ran dry, after the stream started: what the start's silence has to cover.
        assertTrue(logs.toString(), logs.any { "underrun 200 ms after the stream started" in it })
        renderer.close()
    }

    @Test
    fun `the start's gain is silent, then rises, then whole`() {
        val fade = StartFade(silenceMillis = 1000, fadeMillis = 500)
        assertEquals(0f, fade.gain(TimeUnit.MILLISECONDS.toNanos(999)), 0f)
        assertEquals(0.5f, fade.gain(TimeUnit.MILLISECONDS.toNanos(1250)), 0.001f)
        assertEquals(1f, fade.gain(TimeUnit.MILLISECONDS.toNanos(1500)), 0f)
        assertEquals(1f, StartFade.NONE.gain(0), 0f)
    }
}
