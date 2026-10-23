package io.github.numberonebot.chairplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class MediaDuckingTest {
    private val logs = mutableListOf<String>()
    private val ducking = MediaDucking { logs += it }

    private fun ms(millis: Long) = TimeUnit.MILLISECONDS.toNanos(millis)

    @Test
    fun `duckAudio's level and ramp are read from its params`() {
        assertEquals(DuckRequest(0.2f, 500), duckRequest(mapOf("volume" to 0.2, "durationMs" to 500L)))
        // Any number type the plist decoder gives.
        assertEquals(DuckRequest(0.5f, 300), duckRequest(mapOf("volume" to 0.5f, "durationMs" to 300)))
        assertEquals(DuckRequest(0f, 1000), duckRequest(mapOf("volume" to 0, "durationMs" to 1000.0)))
    }

    @Test
    fun `missing params take the defaults`() {
        val defaults = DuckRequest(MediaDucking.DEFAULT_LEVEL, MediaDucking.DEFAULT_RAMP_MILLIS)
        assertEquals(defaults, duckRequest(emptyMap()))
        assertEquals(DuckRequest(MediaDucking.DEFAULT_LEVEL, 400), duckRequest(mapOf("durationMs" to 400L)))
        assertEquals(DuckRequest(0.1f, MediaDucking.DEFAULT_RAMP_MILLIS), duckRequest(mapOf("volume" to 0.1)))
    }

    @Test
    fun `nonsensical params take the defaults or the nearest sane value`() {
        val level = MediaDucking.DEFAULT_LEVEL
        val ramp = MediaDucking.DEFAULT_RAMP_MILLIS
        assertEquals(level, duckRequest(mapOf("volume" to 1.5)).level, 0f)
        assertEquals(level, duckRequest(mapOf("volume" to -0.1)).level, 0f)
        assertEquals(level, duckRequest(mapOf("volume" to Double.NaN)).level, 0f)
        assertEquals(level, duckRequest(mapOf("volume" to "0.2")).level, 0f)
        assertEquals(level, duckRequest(mapOf("volume" to null)).level, 0f)
        assertEquals(ramp, duckRequest(mapOf("durationMs" to -5L)).rampMillis)
        assertEquals(ramp, duckRequest(mapOf("durationMs" to Double.POSITIVE_INFINITY)).rampMillis)
        assertEquals(ramp, duckRequest(mapOf("durationMs" to true)).rampMillis)
        // A jump in one block would click; a ramp of minutes would never be heard as a duck.
        assertEquals(MediaDucking.MIN_RAMP_MILLIS, duckRequest(mapOf("durationMs" to 0L)).rampMillis)
        assertEquals(MediaDucking.MAX_RAMP_MILLIS, duckRequest(mapOf("durationMs" to 60_000L)).rampMillis)
    }

    @Test
    fun `at rest the media plays at full volume`() {
        assertEquals(1f, ducking.gain(0), 0f)
        assertEquals(1f, ducking.gain(ms(60_000)), 0f)
    }

    @Test
    fun `a duck ramps straight down to its level`() {
        ducking.duck(0.3f, ms(200), nowNs = 0)

        assertEquals(1f, ducking.gain(0), 0.001f)
        assertEquals(0.65f, ducking.gain(ms(100)), 0.001f)
        assertEquals(0.3f, ducking.gain(ms(200)), 0.001f)
        assertEquals(0.3f, ducking.gain(ms(5_000)), 0.001f)
    }

    @Test
    fun `an unduck ramps back to full volume`() {
        ducking.duck(0.3f, ms(200), nowNs = 0)

        ducking.unduck(ms(100), nowNs = ms(1_000))

        assertEquals(0.3f, ducking.gain(ms(1_000)), 0.001f)
        assertEquals(0.65f, ducking.gain(ms(1_050)), 0.001f)
        assertEquals(1f, ducking.gain(ms(1_100)), 0f)
        assertEquals(1f, ducking.gain(ms(60_000)), 0f)
    }

    // Back-to-back prompts: unduckAudio, then duckAudio 67 ms later (DiPlay #946). Each ramp starts where the last one is.
    @Test
    fun `a command half way through a ramp starts from where the ramp is`() {
        ducking.duck(0.2f, ms(200), nowNs = 0)
        ducking.unduck(ms(200), nowNs = ms(100))

        assertEquals(0.6f, ducking.gain(ms(100)), 0.001f)
        assertEquals(0.8f, ducking.gain(ms(200)), 0.001f)
        ducking.duck(0.2f, ms(200), nowNs = ms(200))
        assertEquals(0.5f, ducking.gain(ms(300)), 0.001f)
        assertEquals(0.2f, ducking.gain(ms(400)), 0.001f)
    }

    @Test
    fun `a duck without an unduck ends after the timeout`() {
        ducking.duck(0.3f, ms(200), nowNs = 0)

        assertEquals(0.3f, ducking.gain(MediaDucking.TIMEOUT_NS - 1), 0.001f)
        assertTrue(logs.none { "unduckAudio" in it })
        assertEquals(1f, ducking.gain(MediaDucking.TIMEOUT_NS + ms(MediaDucking.DEFAULT_RAMP_MILLIS)), 0f)
        assertEquals(1, logs.count { "no unduckAudio" in it })
        assertFalse(ducking.ducked)
    }

    @Test
    fun `the timeout counts from the last duck`() {
        ducking.duck(0.3f, ms(200), nowNs = 0)
        ducking.duck(0.3f, ms(200), nowNs = ms(20_000))

        assertEquals(0.3f, ducking.gain(MediaDucking.TIMEOUT_NS + ms(5_000)), 0.001f)
        assertEquals(1f, ducking.gain(ms(20_000) + MediaDucking.TIMEOUT_NS + ms(1_000)), 0f)
    }

    @Test
    fun `the timeout ramps up from the duck's level`() {
        ducking.duck(0.3f, ms(200), nowNs = 0)
        val half = MediaDucking.TIMEOUT_NS + ms(MediaDucking.DEFAULT_RAMP_MILLIS) / 2

        assertEquals(0.65f, ducking.gain(half), 0.001f)
        // Read again at an earlier time, as the next block's start can be: still on the same ramp.
        assertEquals(0.3f, ducking.gain(MediaDucking.TIMEOUT_NS), 0.001f)
    }

    @Test
    fun `a reset brings full volume back at once and says whether it was ducked`() {
        assertFalse(ducking.reset())
        ducking.duck(0.3f, ms(200), nowNs = 0)
        assertTrue(ducking.ducked)

        assertTrue(ducking.reset())

        assertEquals(1f, ducking.gain(ms(100)), 0f)
        assertFalse(ducking.ducked)
        assertFalse(ducking.reset())
    }

    @Test
    fun `a reset cuts an unduck ramp short`() {
        ducking.duck(0.3f, ms(200), nowNs = 0)
        ducking.unduck(ms(200), nowNs = ms(1_000))

        assertFalse("the duck is no longer in force", ducking.reset())
        assertEquals(1f, ducking.gain(ms(1_050)), 0f)
    }

    @Test
    fun `a ramp across a block changes the gain frame by frame`() {
        // Four stereo frames of 0x1000, from gain 1 to gain 0: 1, 0.75, 0.5, 0.25 per frame, both channels alike.
        val pcm = ByteArray(16) { if (it % 2 == 1) 0x10 else 0 }

        rampSamples(pcm, pcm.size, channels = 2, startGain = 1f, endGain = 0f)

        val samples = pcm.toList().chunked(2).map { (low, high) -> ((high.toInt() shl 8) or (low.toInt() and 0xff)).toShort().toInt() }
        assertEquals(listOf(0x1000, 0x1000, 0x0c00, 0x0c00, 0x0800, 0x0800, 0x0400, 0x0400), samples)
    }

    @Test
    fun `a ramp with one gain scales as a plain gain does`() {
        val pcm = byteArrayOf(0x00, 0x10, 0x00, 0xf0.toByte(), 0x11, 0x22)

        rampSamples(pcm, 4, channels = 1, startGain = 0.5f, endGain = 0.5f)

        assertEquals(listOf<Byte>(0x00, 0x08, 0x00, 0xf8.toByte(), 0x11, 0x22), pcm.toList())
    }
}
