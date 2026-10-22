package com.shilapi.xcertplay.desktop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class CardFinderTest {
    /** A luma plane of [background] with [card] filled in [content]. */
    private fun plane(width: Int, height: Int, background: Int, card: PixelRect? = null, content: Int = 235): ByteBuffer {
        val y = ByteBuffer.allocate(width * height)
        for (row in 0 until height) for (x in 0 until width) {
            val inside = card != null && x >= card.left && x < card.right && row >= card.top && row < card.bottom
            y.put(row * width + x, (if (inside) content else background).toByte())
        }
        return y
    }

    // Apple Maps' card-only AltScreen: a flat background, the card's content left-aligned in the middle.
    @Test
    fun `the card is what differs from the background, with a margin`() {
        val y = plane(1440, 542, background = 60, card = PixelRect(22, 240, 520, 300))

        assertEquals(PixelRect(6, 224, 536, 316), cardBounds(y, 1440, 542, margin = 16))
    }

    @Test
    fun `a light background works as well as a dark one`() {
        val y = plane(1440, 542, background = 220, card = PixelRect(740, 240, 1240, 300), content = 30)

        assertEquals(PixelRect(724, 224, 1256, 316), cardBounds(y, 1440, 542, margin = 16))
    }

    @Test
    fun `an empty screen has no card`() {
        assertNull(cardBounds(plane(1440, 542, background = 60), 1440, 542, margin = 16))
    }

    @Test
    fun `the margin stops at the edges`() {
        val y = plane(320, 120, background = 60, card = PixelRect(2, 2, 100, 50))

        assertEquals(PixelRect(0, 0, 116, 66), cardBounds(y, 320, 120, margin = 16))
    }

    // A new maneuver text changes the card's size; growing shows at once, shrinking only once it lasts.
    @Test
    fun `the tracked card grows at once and shrinks only after it stays smaller`() {
        val tracker = CardTracker(shrinkAfterFrames = 3)
        val big = PixelRect(0, 200, 600, 340)
        val small = PixelRect(0, 220, 400, 320)

        assertEquals(big, tracker.update(big))
        assertEquals(big, tracker.update(small))
        assertEquals(big, tracker.update(small))
        assertEquals(small, tracker.update(small))
        assertEquals(big, tracker.update(big))
        // No card in one frame (the screen being redrawn) keeps the last one.
        assertEquals(big, tracker.update(null))
    }
}
