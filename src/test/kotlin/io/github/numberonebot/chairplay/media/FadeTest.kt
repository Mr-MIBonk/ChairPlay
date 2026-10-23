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

    // The new content waits on black while it may not rise yet (the iPhone's picture of another view not in), then
    // comes up as usual; content already rising is not held.
    @Test
    fun `new content waits on black until it may rise`() {
        fade.update(true)
        after(150, true)
        assertTrue(fade.shown)

        nanos += TimeUnit.MILLISECONDS.toNanos(500)
        fade.update(true, mayRise = false)
        assertEquals(0f, fade.brightness, 0f)
        assertTrue(fade.moving)

        nanos += TimeUnit.MILLISECONDS.toNanos(75)
        fade.update(true, mayRise = true)
        assertEquals(0.5f, fade.brightness, 0.01f)
        nanos += TimeUnit.MILLISECONDS.toNanos(30)
        fade.update(true, mayRise = false)
        assertEquals(0.7f, fade.brightness, 0.01f)
    }

    // Two views of the iPhone's picture are two contents too, not only the picture and the HUD.
    @Test
    fun `any change of the wanted content goes through black`() {
        val views = Fade(initial = "map", millis = 150) { nanos }
        views.update("card")
        nanos += TimeUnit.MILLISECONDS.toNanos(150)
        views.update("card")

        assertEquals("card", views.shown)
        assertEquals(0f, views.brightness, 0f)
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
