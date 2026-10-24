package io.github.numberonebot.chairplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** AudioRenderer: duckAudio and unduckAudio, which lower and restore the media streams only. */
class AudioRendererDuckTest : AudioRendererFixture() {
    // duckAudio lowers the music under a navigation prompt; the prompt itself, calls and Siri stay as loud as they were.
    @Test
    fun `a duck lowers the media streams only`() {
        val lines = ConcurrentHashMap<Int, RecordingLine>()
        val clock = AtomicLong(1)
        // Media in stereo and the prompt in mono, so each line is known by its channels.
        val renderer = AudioRenderer({}, { _, channels, _ -> RecordingLine().also { lines[channels] = it } }, { clock.get() }, 5)
        val media = AudioStreamId(102, "media")
        val prompt = AudioStreamId(101, "alert")
        renderer.start(media, AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"))
        renderer.start(prompt, AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 96, "alert"))

        renderer.duck(level = 0.5f, rampMillis = 20)
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100))
        repeat(6) { renderer.push(media, header(sample = it * 480) + constantTenMs(2), it * 480) }
        repeat(4) { renderer.push(prompt, header(sample = it * 480) + constantTenMs(1), it * 480) }
        awaitTrue("playback") { lines[2]?.size() == 6 * 480 * 4 && lines[1]?.size() == 4 * 480 * 2 }

        assertTrue(samples(synchronized(lines[2]!!) { lines[2]!!.written.toByteArray() }).all { it == 0x0800 })
        assertTrue(samples(synchronized(lines[1]!!) { lines[1]!!.written.toByteArray() }).all { it == 0x1000 })
        renderer.close()
    }

    @Test
    fun `an unduck brings the media back to full volume`() {
        val line = RecordingLine()
        val clock = AtomicLong(1)
        val renderer = AudioRenderer({}, { _, _, _ -> line }, { clock.get() }, 5)
        val media = AudioStreamId(102, "media")
        renderer.start(media, AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"))
        renderer.duck(level = 0.5f, rampMillis = 20)

        renderer.unduck(rampMillis = 20)
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100))
        repeat(6) { renderer.push(media, header(sample = it * 480) + constantTenMs(2), it * 480) }
        awaitTrue("playback") { line.size() == 6 * 480 * 4 }

        assertTrue(samples(synchronized(line) { line.written.toByteArray() }).all { it == 0x1000 })
        renderer.close()
    }

    // A media stream that starts or stops during a prompt must never leave the next music ducked by mistake.
    @Test
    fun `a media stream started while ducked plays at full volume`() {
        val line = RecordingLine()
        val clock = AtomicLong(1)
        val logs = CopyOnWriteArrayList<String>()
        val renderer = AudioRenderer({ logs += it }, { _, _, _ -> line }, { clock.get() }, 5)
        val media = AudioStreamId(102, "media")
        renderer.duck(level = 0.5f, rampMillis = 20)
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100))

        renderer.start(media, AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"))
        repeat(6) { renderer.push(media, header(sample = it * 480) + constantTenMs(2), it * 480) }
        awaitTrue("playback") { line.size() == 6 * 480 * 4 }

        assertTrue(samples(synchronized(line) { line.written.toByteArray() }).all { it == 0x1000 })
        assertTrue(logs.toString(), logs.any { "full volume" in it })
        renderer.close()
    }

    @Test
    fun `a media stream stopped while ducked ends the duck`() {
        val renderer = AudioRenderer(lineFactory = { _, _, _ -> RecordingLine() })
        val media = AudioStreamId(102, "media")
        val prompt = AudioStreamId(101, "alert")
        renderer.start(media, AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"))
        renderer.start(prompt, AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 96, "alert"))
        renderer.duck(level = 0.5f, rampMillis = 20)

        renderer.stop(prompt)
        assertTrue("the prompt's own stream ending leaves the duck", renderer.ducked)
        renderer.stop(media)

        assertFalse(renderer.ducked)
        renderer.close()
    }
}
