package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrowProgressTest {
    private val turn = ManeuverType.LEFT_TURN.code

    @Test
    fun `far from the maneuver the arrow shows no progress`() {
        assertEquals(ArrowProgress.OFF, arrowProgress(1_000, 3_000, turn))
        assertEquals(ArrowProgress.OFF, arrowProgress(null, 300, turn))
        assertEquals(ArrowProgress.OFF, arrowProgress(0, 300, turn))
    }

    // City: prepare at 1500 m, so the bar runs over the last 225 m, or the step when it is shorter; the arrow fills in
    // sixteenths over the whole bar, and is all blue at the maneuver, with no blink.
    @Test
    fun `the arrow fills over the whole bar and is all blue at the maneuver`() {
        assertEquals(ArrowProgress(14, ArrowProgress.FILL), arrowProgress(200, 300, turn))
        assertEquals(ArrowProgress(16, ArrowProgress.FILL), arrowProgress(150, 150, turn))
        assertEquals(ArrowProgress(8, ArrowProgress.FILL), arrowProgress(75, 150, turn))
        assertEquals(ArrowProgress(0, ArrowProgress.FILL), arrowProgress(1, 150, turn))
    }

    @Test
    fun `a long step is a highway, with twice the distance`() {
        assertEquals(ArrowProgress(7, ArrowProgress.FILL), arrowProgress(200, 5_000, turn))
    }

    @Test
    fun `without a step the type tells a highway`() {
        assertEquals(ArrowProgress(7, ArrowProgress.FILL), arrowProgress(200, null, ManeuverType.OFF_RAMP.code))
        assertEquals(ArrowProgress(14, ArrowProgress.FILL), arrowProgress(200, null, turn))
    }
}
