package io.github.numberonebot.chairplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class RevealGateTest {
    private var nanos = 0L
    private val logs = mutableListOf<String>()
    private val gate = RevealGate(settleMillis = 300, maxMillis = 2000, log = { logs += it }) { nanos }

    private fun after(millis: Long) {
        nanos += TimeUnit.MILLISECONDS.toNanos(millis)
    }

    @Test
    fun `at rest the picture may rise`() {
        assertTrue(gate.open())
    }

    // A view the iPhone already draws (kept running under the HUD) has nothing to wait for: it rises once the ask is out.
    @Test
    fun `a view already drawn rises as soon as its ask is out`() {
        val wait = gate.hold()
        assertFalse(gate.open())
        gate.sent(wait, drawn = true)
        assertTrue(gate.open())
    }

    // The iPhone draws the asked-for view only after the ask, and its first pictures can still be the old view or a
    // blank one: the window stays black until pictures have come for a while after the ask went out.
    @Test
    fun `on black it waits for pictures that came after the ask, then for them to settle`() {
        gate.hold()
        gate.picture(luma = 40)
        after(500)
        assertFalse("a picture before the ask is the old view", gate.open())

        gate.sent()
        after(100)
        assertFalse("no picture since the ask", gate.open())
        gate.picture(luma = 250)
        after(299)
        assertFalse(gate.open())
        after(1)
        assertTrue(gate.open())

        assertEquals(1, logs.count { "revealed" in it })
        assertTrue(logs.toString(), logs.any { "luma 250" in it })
    }

    // A view the iPhone never draws (a lost command) must not leave the window black.
    @Test
    fun `it gives up waiting after the longest hold`() {
        gate.hold()
        gate.sent()
        after(1999)
        assertFalse(gate.open())
        after(1)
        assertTrue(gate.open())
        assertTrue(logs.toString(), logs.any { "no picture" in it })
    }

    // The log tells a white picture from the map's dark one by the luma plane's average, read at a grid of points.
    @Test
    fun `a picture's luma is its luma plane's average`() {
        val white = ByteBuffer.wrap(ByteArray(64 * 36) { 235.toByte() })
        val half = ByteBuffer.wrap(ByteArray(64 * 36) { if (it % 64 < 32) 0 else 200.toByte() })

        assertEquals(235, meanLuma(white, 64, 36))
        assertEquals(100.0, meanLuma(half, 64, 36).toDouble(), 10.0)
        assertEquals(0, white.position())
    }

    // Another switch while waiting starts the wait over: the pictures that came belong to the view left.
    @Test
    fun `a new hold forgets the pictures of the one before`() {
        gate.hold()
        gate.sent()
        gate.picture(luma = 100)
        after(200)
        gate.hold()
        after(200)
        assertFalse(gate.open())
        gate.sent()
        gate.picture(luma = 100)
        after(300)
        assertTrue(gate.open())
    }
}
