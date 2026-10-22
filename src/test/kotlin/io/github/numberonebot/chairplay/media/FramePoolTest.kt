package io.github.numberonebot.chairplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class FramePoolTest {
    @Test
    fun `released frames of one size are reused`() {
        val pool = FramePool()
        // The decoder, the latest slot and the upload can each hold one picture at a time.
        repeat(1_000) {
            val first = pool.take(1280, 720)
            val second = pool.take(1280, 720)
            val third = pool.take(1280, 720)
            first.release()
            second.release()
            third.release()
        }
        assertEquals(3, pool.allocations)
    }

    @Test
    fun `a size change allocates new frames and drops the old ones`() {
        val pool = FramePool()
        val small = pool.take(320, 240)
        small.release()

        val large = pool.take(1280, 720)
        assertNotSame(small, large)
        assertEquals(1280 * 720, large.y.capacity())
        assertEquals(640 * 360, large.u.capacity())
        assertEquals(2, pool.allocations)

        large.release()
        assertSame(large, pool.take(1280, 720))
        // The 320x240 frame was dropped when 1280x720 was asked for, so going back allocates again.
        pool.take(320, 240)
        assertEquals(3, pool.allocations)
    }

    @Test
    fun `an unreleased frame is never handed out again`() {
        val pool = FramePool()
        val held = pool.take(320, 240)
        assertNotSame(held, pool.take(320, 240))
    }
}
