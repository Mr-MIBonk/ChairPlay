package io.github.numberonebot.chairplay.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsLayoutTest {
    private val buttons = controlsLayout(720f, 320f)

    @Test
    fun `the window has every car control once`() {
        val expected = setOf(
            ControlAction.SELECT, ControlAction.KNOB_UP, ControlAction.KNOB_DOWN, ControlAction.KNOB_LEFT,
            ControlAction.KNOB_RIGHT, ControlAction.KNOB_PREVIOUS, ControlAction.KNOB_NEXT, ControlAction.BACK,
            ControlAction.HOME, ControlAction.SIRI, ControlAction.MEDIA_PREVIOUS, ControlAction.MEDIA_PLAY_PAUSE,
            ControlAction.MEDIA_NEXT, ControlAction.CALL_ANSWER, ControlAction.CALL_END, ControlAction.ALTSCREEN_CONTENT,
            ControlAction.ALTSCREEN_CARDS, ControlAction.ALTSCREEN_ZOOM_OUT, ControlAction.ALTSCREEN_ZOOM_IN,
        )
        assertEquals(expected, buttons.map { it.action }.toSet())
        assertEquals(expected.size, buttons.size)
    }

    @Test
    fun `buttons stay inside the window and do not overlap`() {
        for (button in buttons) {
            assertTrue(button.toString(), button.left >= 0f && button.top >= 0f && button.right <= 720f && button.bottom <= 320f)
        }
        for (a in buttons) for (b in buttons) {
            if (a !== b) assertFalse("$a overlaps $b", a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom && a.round == b.round && !a.round)
        }
    }

    // A click hits the button under it: the knob's centre selects, its edges tilt.
    @Test
    fun `a point hits the button under it`() {
        for (button in buttons) assertEquals(button.action, controlAt(buttons, button.centreX, button.centreY))
        assertNull(controlAt(buttons, -10f, -10f))
    }

    // The AltScreen's controls share the bottom row, left to right as F11, Shift+F11 and the zoom keys.
    @Test
    fun `the AltScreen row switches its view and cards and zooms it`() {
        val row = buttons.filter { it.action.name.startsWith("ALTSCREEN_") }
        assertEquals(1, row.map { it.top }.toSet().size)
        assertEquals(
            listOf(ControlAction.ALTSCREEN_CONTENT, ControlAction.ALTSCREEN_CARDS, ControlAction.ALTSCREEN_ZOOM_OUT, ControlAction.ALTSCREEN_ZOOM_IN),
            row.sortedBy { it.left }.map { it.action },
        )
        assertTrue(row.all { it.top > buttons.single { b -> b.action == ControlAction.CALL_ANSWER }.top })
    }

    @Test
    fun `a narrow window still fits everything`() {
        val small = controlsLayout(360f, 200f)
        assertEquals(buttons.size, small.size)
        for (button in small) assertTrue(button.right <= 360f && button.bottom <= 200f)
    }
}
