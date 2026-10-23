package io.github.numberonebot.chairplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class FadeTest {
    private var nanos = 0L
    private val fade = Fade(initial = false, millis = 150) { nanos }

    private fun after(millis: Long, target: Boolean) {
        nanos += TimeUnit.MILLISECONDS.toNanos(millis)
        fade.update(target)
    }

    @Test
    fun `at rest it shows its content at full brightness`() {
        fade.update(false)

        assertFalse(fade.shown)
        assertEquals(1f, fade.brightness, 0f)
        assertFalse(fade.moving)
    }

    // Through black: the old content dims away, the new one comes up, each over the fade's time.
    @Test
    fun `a change dims the old content to black, swaps, and brings the new one up`() {
        fade.update(true)
        assertTrue(fade.moving)

        after(75, true)
        assertFalse("the old content until black", fade.shown)
        assertEquals(0.5f, fade.brightness, 0.01f)

        after(75, true)
        assertTrue(fade.shown)
        assertEquals(0f, fade.brightness, 0.01f)

        after(75, true)
        assertEquals(0.5f, fade.brightness, 0.01f)
        after(75, true)
        assertEquals(1f, fade.brightness, 0f)
        assertFalse(fade.moving)
    }

    // Changed back half way: the old content comes back up from where it was, never swapping.
    @Test
    fun `a change taken back half way brings the old content back up`() {
        fade.update(true)
        after(75, true)

        after(30, false)

        assertFalse(fade.shown)
        assertEquals(0.7f, fade.brightness, 0.01f)
        after(45, false)
        assertEquals(1f, fade.brightness, 0f)
        assertFalse(fade.moving)
    }

    // A pause in the loop (a dragged window) must not leave the fade stuck half way, nor overshoot.
    @Test
    fun `a long gap between passes ends the step it was in`() {
        fade.update(true)

        after(1000, true)

        assertTrue(fade.shown)
        assertEquals(0f, fade.brightness, 0f)
    }
}
