package com.shilapi.xcertplay.desktop.media

import com.shilapi.xcertplay.media.SpeexEchoCanceller
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.log10
import kotlin.math.sqrt

/** Upstream's SpeexDSP canceller through the speex_echo DLL this build compiles. */
class SpeexEchoCancellerTest {
    @Test
    fun `the native canceller loads and removes a delayed echo of the reference`() {
        val canceller = SpeexEchoCanceller.create(frameSamples = FRAME, sampleRate = RATE, tailMillis = 250)
        assertNotNull("speex_echo.dll did not load from java.library.path", canceller)
        canceller!!

        // The far end's audio (noise), and a microphone that hears only its echo: 40 ms late, at half level.
        val random = Random(7)
        val total = FRAMES * FRAME
        val reference = ShortArray(total) { (random.nextGaussian() * 4000).toInt().coerceIn(-32768, 32767).toShort() }
        val mic = ShortArray(total) { i -> if (i < DELAY) 0 else (reference[i - DELAY] / 2).toShort() }

        val frame = ByteArray(FRAME * 2)
        val lastSecond = FRAMES - RATE / FRAME
        var inEnergy = 0.0
        var outEnergy = 0.0
        canceller.use {
            for (f in 0 until FRAMES) {
                val start = f * FRAME
                for (i in 0 until FRAME) {
                    val sample = mic[start + i].toInt()
                    frame[2 * i] = sample.toByte()
                    frame[2 * i + 1] = (sample shr 8).toByte()
                }
                assertTrue(it.process(frame, reference.copyOfRange(start, start + FRAME)))
                if (f < lastSecond) continue
                for (i in 0 until FRAME) {
                    val input = mic[start + i].toDouble()
                    val output = ((frame[2 * i + 1].toInt() shl 8) or (frame[2 * i].toInt() and 0xff)).toShort().toDouble()
                    inEnergy += input * input
                    outEnergy += output * output
                }
            }
        }

        val attenuationDb = 10 * log10(inEnergy / outEnergy.coerceAtLeast(1.0))
        println("echo attenuation over the last second: %.1f dB (input RMS %.0f, output RMS %.0f)".format(
            Locale.ROOT, attenuationDb, sqrt(inEnergy / RATE), sqrt(outEnergy / RATE),
        ))
        assertTrue(
            "echo attenuated by only %.1f dB".format(Locale.ROOT, attenuationDb),
            attenuationDb >= MIN_ATTENUATION_DB,
        )
    }

    private companion object {
        const val RATE = 48_000
        const val FRAME = 960 // 20 ms
        const val FRAMES = 250 // 5 s
        const val DELAY = RATE * 40 / 1000
        // About 40 dB is measured (the adaptive filter and the residual echo suppressor together).
        const val MIN_ATTENUATION_DB = 20.0
    }
}
