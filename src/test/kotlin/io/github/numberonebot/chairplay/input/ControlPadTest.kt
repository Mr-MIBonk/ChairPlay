package io.github.numberonebot.chairplay.input

import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlPadTest {
    private val pad = ControlPad()

    // A held button holds the knob as a held key does, so CarPlay can tell a long press; the release lets go.
    @Test
    fun `knob buttons hold until released`() {
        assertEquals(Control.Knob(AirPlayKnobState(select = true), false), pad.press(ControlAction.SELECT))
        assertEquals(Control.Knob(AirPlayKnobState(), false), pad.release(ControlAction.SELECT))
        assertEquals(Control.Knob(AirPlayKnobState(x = -127), false), pad.press(ControlAction.KNOB_LEFT))
        assertEquals(Control.Knob(AirPlayKnobState(home = true), false), pad.press(ControlAction.HOME))
    }

    @Test
    fun `turns, media, calls, Siri and zoom act on the press alone`() {
        assertEquals(Control.Knob(AirPlayKnobState(wheel = 1), true), pad.press(ControlAction.KNOB_NEXT))
        assertEquals(Control.Knob(AirPlayKnobState(wheel = -1), true), pad.press(ControlAction.KNOB_PREVIOUS))
        assertEquals(Control.Media(CarPlayMediaButton.PLAY_PAUSE), pad.press(ControlAction.MEDIA_PLAY_PAUSE))
        assertEquals(Control.Siri, pad.press(ControlAction.SIRI))
        assertEquals(Control.MapZoom(1), pad.press(ControlAction.ALTSCREEN_ZOOM_IN))
        assertEquals(Control.MapZoom(-1), pad.press(ControlAction.ALTSCREEN_ZOOM_OUT))
        assertNull(pad.release(ControlAction.KNOB_NEXT))
        assertNull(pad.release(ControlAction.SIRI))
    }

    // As F11 and Shift+F11.
    @Test
    fun `the AltScreen buttons switch its view and its cards`() {
        assertEquals(Control.NextAltscreenContent, pad.press(ControlAction.ALTSCREEN_CONTENT))
        assertEquals(Control.NextAltscreenCards, pad.press(ControlAction.ALTSCREEN_CARDS))
        assertNull(pad.release(ControlAction.ALTSCREEN_CONTENT))
    }
}
