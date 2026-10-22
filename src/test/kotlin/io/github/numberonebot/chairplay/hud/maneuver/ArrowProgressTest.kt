package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArrowProgressTest {
    private val turn = ManeuverType.LEFT_TURN.code

    @Test
    fun `far from the maneuver the arrow shows no progress`() {
        assertEquals(ArrowProgress.OFF, arrowProgress(1_000, 3_000, turn, 0))
        assertEquals(ArrowProgress.OFF, arrowProgress(null, 300, turn, 0))
        assertEquals(ArrowProgress.OFF, arrowProgress(0, 300, turn, 0))
    }

    @Test
    fun `within the last 15 percent of the prepare distance the arrow fills in sixteenths`() {
        // City: prepare at 1500 m, so the bar runs over the last 225 m, or the step when it is shorter.
        assertEquals(ArrowProgress(14, ArrowProgress.FILL), arrowProgress(200, 300, turn, 0))
        assertEquals(ArrowProgress(3, ArrowProgress.FILL), arrowProgress(30, 150, turn, 0))
        assertEquals(ArrowProgress(16, ArrowProgress.FILL), arrowProgress(150, 150, turn, 0))
    }

    @Test
    fun `a long step is a highway, with twice the distance`() {
        assertEquals(ArrowProgress(7, ArrowProgress.FILL), arrowProgress(200, 5_000, turn, 0))
    }

    @Test
    fun `without a step the type tells a highway`() {
        assertEquals(ArrowProgress(7, ArrowProgress.FILL), arrowProgress(200, null, ManeuverType.OFF_RAMP.code, 0))
        assertEquals(ArrowProgress(14, ArrowProgress.FILL), arrowProgress(200, null, turn, 0))
    }

    @Test
    fun `below 20 percent the arrow blinks every 600 ms, starting full`() {
        assertEquals(ArrowProgress(16, ArrowProgress.BLINK_HIGH), arrowProgress(20, 150, turn, 0))
        assertEquals(ArrowProgress(16, ArrowProgress.BLINK_HIGH), arrowProgress(20, 150, turn, 599))
        assertEquals(ArrowProgress(0, ArrowProgress.BLINK_LOW), arrowProgress(20, 150, turn, 600))
        assertEquals(ArrowProgress(16, ArrowProgress.BLINK_HIGH), arrowProgress(20, 150, turn, 1_200))
    }

    @Test
    fun `only a blinking arrow needs a wake-up of its own`() {
        assertEquals(600L, nextProgressChange(20, 150, turn, 0))
        assertEquals(1_200L, nextProgressChange(20, 150, turn, 601))
        assertNull(nextProgressChange(200, 300, turn, 0))
        assertNull(nextProgressChange(null, 300, turn, 0))
    }
}
