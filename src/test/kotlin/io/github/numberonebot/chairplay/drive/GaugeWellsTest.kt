package io.github.numberonebot.chairplay.drive

import io.github.numberonebot.chairplay.hud.smallWellScale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The wells of the virtual car, where the AltScreen page draws them in a custom area's rectangle. */
class GaugeWellsTest {
    @Test
    fun `large wells stand in the middle of a 1440 by 540 frame`() {
        val (left, right) = gaugeWells(1440, 540, small = false)

        assertEquals(266.4f, left.x, 0.5f)
        assertEquals(270f, left.y, 0.5f)
        assertEquals(244.8f, left.radius, 0.5f)
        assertEquals(1173.6f, right.x, 0.5f)
        assertEquals(270f, right.y, 0.5f)
        assertEquals(244.8f, right.radius, 0.5f)
    }

    @Test
    fun `small wells are the large ones scaled about the corner, a margin above the bottom`() {
        for ((w, h) in listOf(1440 to 540, 800 to 480, 1920 to 720)) {
            val width = w.toFloat()
            val height = h.toFloat()
            val scale = smallWellScale(width, height)
            val (cornerX, cornerY) = GaugeLayout.corner(width, height, scale)
            val (large, _) = gaugeWells(w, h, small = false)
            val (left, right) = gaugeWells(w, h, small = true)

            assertEquals(cornerX + scale * (large.x - cornerX), left.x, 0.01f)
            assertEquals(cornerY + scale * (large.y - cornerY), left.y, 0.01f)
            assertEquals(large.radius * scale, left.radius, 0.01f)
            assertEquals(width - left.x, right.x, 0.01f)
            assertEquals(left.y, right.y, 0f)
            assertEquals(height - GaugeLayout.margin(height), left.y + left.radius, 0.01f)
        }
    }
}
