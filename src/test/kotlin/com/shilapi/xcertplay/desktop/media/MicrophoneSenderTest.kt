package com.shilapi.xcertplay.desktop.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.concentus.OpusDecoder
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MicrophoneSenderTest {
    private val config = MicrophoneConfig(
        audioType = "speechRecognition",
        sampleRate = 16_000,
        channels = 1,
        payloadType = 103,
        frameMillis = 20,
        host = InetAddress.getLoopbackAddress(),
        port = 4000,
        key = ByteArray(32) { it.toByte() },
    )
    private val samples = config.sampleRate * config.frameMillis / 1000

    private fun frame(seed: Int) = ByteArray(samples * 2) { (it * 7 + seed).toByte() }

    @Test
    fun `sends sealed pcm frames at frame cadence`() {
        val frames = (1..3).map(::frame)
        val queue = LinkedBlockingQueue(frames)
        val sent = LinkedBlockingQueue<ByteArray>()
        val sender = MicrophoneSender(config, capture = { queue.poll() }, send = sent::put)
        sender.start()
        val packets = (1..3).map { sent.poll(5, TimeUnit.SECONDS)!! }
        sender.close()

        val counters = MicrophoneCounters()
        frames.forEachIndexed { index, frame ->
            val expected = MicrophonePacketizer.sealPacket(
                config.key, config.payloadType, counters, MicrophonePacketizer.toWirePcm(frame), samples,
            )
            assertArrayEquals("packet $index", expected, packets[index])
        }
        assertEquals(3 * samples, counters.timestamp)
    }

    // Siri at audioFormat 0x20000000: 48 kHz capture, Opus bodies, timestamps on the 24 kHz clock.
    @Test
    fun `sends opus bodies stepped on the negotiated clock`() {
        val opus = config.copy(sampleRate = 48_000, codec = AudioCodecKind.OPUS, opusClockRate = 24_000)
        val frames = (1..3).map { ByteArray(opus.frameBytes) { b -> (b + it).toByte() } }
        val queue = LinkedBlockingQueue(frames)
        val sent = LinkedBlockingQueue<ByteArray>()
        val encoded = { pcm: ByteArray -> listOf(byteArrayOf(0x78, pcm[0])) }
        val sender = MicrophoneSender(opus, capture = { queue.poll() }, send = sent::put, encode = encoded)
        sender.start()
        val packets = (1..3).map { sent.poll(5, TimeUnit.SECONDS)!! }
        sender.close()

        val counters = MicrophoneCounters()
        frames.forEachIndexed { index, frame ->
            val expected = MicrophonePacketizer.sealPacket(opus.key, opus.payloadType, counters, encoded(frame).single(), 480)
            assertArrayEquals("packet $index", expected, packets[index])
        }
        assertEquals(3 * 480, counters.timestamp)
    }

    @Test
    fun `opus encoder turns 20 ms of speech into a packet that decodes back`() {
        val pcm = ByteArray(OpusFrameEncoder.FRAME_BYTES)
        for (i in 0 until OpusFrameEncoder.FRAME_BYTES / 2) {
            val sample = (8000 * Math.sin(2 * Math.PI * 440 * i / 48_000.0)).toInt()
            pcm[2 * i] = sample.toByte()
            pcm[2 * i + 1] = (sample shr 8).toByte()
        }
        OpusFrameEncoder(bitrate = 48_000).use { encoder ->
            val packet = encoder.encode(pcm).single()
            val decoded = ShortArray(960)
            val samples = OpusDecoder(48_000, 1).decode(packet, 0, packet.size, decoded, 0, decoded.size, false)
            assertEquals(960, samples)
        }
    }

    @Test
    fun `close unblocks a capture that is waiting and releases once the loop ends`() {
        val inCapture = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val released = AtomicInteger()
        val sender = MicrophoneSender(
            config,
            capture = { inCapture.countDown(); gate.await(); null },
            send = {},
            release = { released.incrementAndGet(); gate.countDown() },
        )
        sender.start()
        assertTrue(inCapture.await(5, TimeUnit.SECONDS))
        sender.close()
        assertTrue("release ran", released.get() >= 1)
    }

    @Test
    fun `a failing send ends the uplink and reports it`() {
        val failure = LinkedBlockingQueue<Throwable>()
        val sender = MicrophoneSender(
            config,
            capture = { frame(1) },
            send = { throw java.io.IOException("socket closed") },
            onFailure = failure::put,
        )
        sender.start()
        assertEquals("socket closed", failure.poll(5, TimeUnit.SECONDS)!!.message)
        sender.close()
    }
}
