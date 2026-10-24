package io.github.numberonebot.chairplay.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeLayoutTest {
    // Small wells are the large ones scaled about a point near each lower corner: as far from the bottom and the side as
    // the large ones are from the side, the room between them wider; in a window wide or tall alike.
    @Test
    fun `small wells are the large ones scaled down about the lower corners`() {
        for ((w, h) in listOf(1440f to 540f, 1280f to 480f, 1200f to 600f)) {
            val r = GaugeLayout.radius(w, h)
            val margin = GaugeLayout.margin(h)
            val small = 0.69f
            assertEquals(small, GaugeLayout.scale(1f, small), 0f)
            assertEquals(1f, GaugeLayout.scale(0f, small), 0f)
            val (px, py) = GaugeLayout.corner(w, h, small)
            // The left well's centre, scaled about the corner: a margin from the side and from the bottom.
            val cx = px + small * (margin + r - px)
            val cy = py + small * (h / 2 - py)
            assertEquals("$w x $h", margin, cx - small * r, 1e-3f)
            assertEquals("$w x $h", margin, h - (cy + small * r), 1e-3f)
            val (largeFrom, largeTo) = GaugeLayout.between(w, h, 0f, small)
            val (smallFrom, smallTo) = GaugeLayout.between(w, h, 1f, small)
            assertEquals(margin + 2 * r * small, smallFrom, 1e-3f)
            assertTrue(smallFrom < largeFrom && smallTo > largeTo)
        }
    }
}
