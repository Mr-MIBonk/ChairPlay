package com.shilapi.xcertplay.desktop.media

import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
