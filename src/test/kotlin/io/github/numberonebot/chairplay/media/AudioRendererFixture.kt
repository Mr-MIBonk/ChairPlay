package io.github.numberonebot.chairplay.media

import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** What the AudioRenderer tests share: a line that records its writes, so nothing opens a real SourceDataLine. */
abstract class AudioRendererFixture {
    internal class RecordingLine : PcmLine {
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

    protected fun header(cc: Int = 0, sample: Int = 0): ByteArray {
        val rtp = ByteArray(12 + 4 * cc)
        rtp[0] = (0x80 or cc).toByte()
        rtp[4] = (sample ushr 24).toByte()
        rtp[5] = (sample ushr 16).toByte()
        rtp[6] = (sample ushr 8).toByte()
        rtp[7] = sample.toByte()
        return rtp
    }

    protected fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    /** 10 ms of big-endian LPCM at 48 kHz with every sample 0x1000. */
    protected fun constantTenMs(channels: Int) = ByteArray(480 * 2 * channels) { if (it % 2 == 0) 0x10 else 0 }

    /** Little-endian 16-bit samples as ints. */
    protected fun samples(bytes: ByteArray) =
        bytes.toList().chunked(2).map { (low, high) -> ((high.toInt() shl 8) or (low.toInt() and 0xff)).toShort().toInt() }
}
