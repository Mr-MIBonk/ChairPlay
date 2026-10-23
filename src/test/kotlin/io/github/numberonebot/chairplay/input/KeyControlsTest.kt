package io.github.numberonebot.chairplay.input

import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.lwjgl.glfw.GLFW.*

class KeyControlsTest {
    private val controls = KeyControls()

    private fun press(key: Int, mods: Int = 0) = controls.key(key, GLFW_PRESS, mods)
    private fun release(key: Int) = controls.key(key, GLFW_RELEASE, 0)

    // The Keyboard page's changes apply at once: the next press reads the bindings the save put in place.
    @Test
    fun `a binding changed while the app runs applies at the next press`() {
        var config = ControlsConfig.DEFAULT
        val live = KeyControls { config }
        assertNull(live.key(GLFW_KEY_F6, GLFW_PRESS, 0))

        config = ControlsConfig.parse("key.home=F6\n")

        assertEquals(Control.Knob(AirPlayKnobState(home = true), false), live.key(GLFW_KEY_F6, GLFW_PRESS, 0))
        assertNull("Home is no longer bound", live.key(GLFW_KEY_HOME, GLFW_PRESS, 0))
    }

    // A key held as its binding goes must still let go of what it pressed, or the button would stay held.
    @Test
    fun `a key held while its binding changes still releases`() {
        var config = ControlsConfig.DEFAULT
        val live = KeyControls { config }
        assertEquals(Control.Knob(AirPlayKnobState(home = true), false), live.key(GLFW_KEY_HOME, GLFW_PRESS, 0))

        config = ControlsConfig.parse("key.home=F6\n")

        assertEquals(Control.Knob(AirPlayKnobState(), false), live.key(GLFW_KEY_HOME, GLFW_RELEASE, 0))
    }

    // The knob's X/Y axes nudge focus in a direction, as a car's knob tilts; release centres them.
    @Test
    fun `arrows nudge the knob and release centres it`() {
        assertEquals(Control.Knob(AirPlayKnobState(x = -127), false), press(GLFW_KEY_LEFT))
        assertEquals(Control.Knob(AirPlayKnobState(x = 127), false), press(GLFW_KEY_RIGHT))
        assertEquals(Control.Knob(AirPlayKnobState(y = -127), false), press(GLFW_KEY_UP))
        assertEquals(Control.Knob(AirPlayKnobState(y = 127), false), press(GLFW_KEY_DOWN))
        assertNull(controls.key(GLFW_KEY_DOWN, GLFW_REPEAT, 0))
        assertEquals(Control.Knob(AirPlayKnobState(), false), release(GLFW_KEY_DOWN))
    }

    // Rotation moves focus item by item, as upstream's CarPlayRemoteKeys turns it for a D-pad.
    @Test
    fun `tab turns the knob forward and shift tab back`() {
        assertEquals(Control.Knob(AirPlayKnobState(wheel = 1), true), press(GLFW_KEY_TAB))
        assertEquals(Control.Knob(AirPlayKnobState(wheel = -1), true), press(GLFW_KEY_TAB, GLFW_MOD_SHIFT))
        assertNull(release(GLFW_KEY_TAB))
    }

    // The app's volume, as a car's volume knob: a held key keeps stepping, as a held turn does.
    @Test
    fun `ctrl up and down step the volume and ctrl m mutes`() {
        assertEquals(Control.VolumeStep(1), press(GLFW_KEY_UP, GLFW_MOD_CONTROL))
        assertEquals(Control.VolumeStep(-1), press(GLFW_KEY_DOWN, GLFW_MOD_CONTROL))
        assertEquals(Control.MuteToggle, press(GLFW_KEY_M, GLFW_MOD_CONTROL))
        assertNull(release(GLFW_KEY_M))
        val held = (1..3).map { controls.key(GLFW_KEY_DOWN, GLFW_REPEAT, GLFW_MOD_CONTROL) }
        assertEquals(listOf(null, null, Control.VolumeStep(-1)), held)
        assertNull(release(GLFW_KEY_DOWN))
    }

    @Test
    fun `a held tab steps on every third repeat`() {
        val sent = listOf(press(GLFW_KEY_TAB)) + (1..6).map { controls.key(GLFW_KEY_TAB, GLFW_REPEAT, 0) }
        assertEquals(listOf(true, false, false, true, false, false, true), sent.map { it != null })
    }

    // Select, back and home keep real down and up, so CarPlay can tell a long press.
    @Test
    fun `select back and home press and release the knob buttons`() {
        for (key in listOf(GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER, GLFW_KEY_SPACE)) {
            assertEquals(Control.Knob(AirPlayKnobState(select = true), false), press(key))
            assertNull(controls.key(key, GLFW_REPEAT, 0))
            assertEquals(Control.Knob(AirPlayKnobState(), false), release(key))
        }
        for (key in listOf(GLFW_KEY_ESCAPE, GLFW_KEY_BACKSPACE)) {
            assertEquals(Control.Knob(AirPlayKnobState(back = true), false), press(key))
        }
        assertEquals(Control.Knob(AirPlayKnobState(home = true), false), press(GLFW_KEY_HOME))
        assertEquals(Control.Knob(AirPlayKnobState(), false), release(GLFW_KEY_HOME))
    }

    @Test
    fun `function keys play media and answer calls and S asks Siri`() {
        assertEquals(Control.Media(CarPlayMediaButton.PREVIOUS), press(GLFW_KEY_F7))
        assertEquals(Control.Media(CarPlayMediaButton.PLAY_PAUSE), press(GLFW_KEY_F8))
        assertEquals(Control.Media(CarPlayMediaButton.NEXT), press(GLFW_KEY_F9))
        assertEquals(Control.Telephony(KeyControls.TELEPHONY_ANSWER), press(GLFW_KEY_F3))
        assertEquals(Control.Telephony(KeyControls.TELEPHONY_HANG_UP), press(GLFW_KEY_F4))
        assertEquals(Control.Siri, press(GLFW_KEY_S))
        assertNull(release(GLFW_KEY_F8))
        assertNull(press(GLFW_KEY_Q))
    }

    // GLFW reports a mouse notch as 1.0 and a touchpad in fractions; scrolling down turns the knob forward.
    // changeMapZoomLevel on the main screen was tried for zoom: the iPhone took it and did nothing.
    // The screenshot stays on this PC: one per press, nothing reaches the iPhone on repeat or release.
    @Test
    fun `F12 takes a screenshot once per press`() {
        assertEquals(Control.Screenshot, press(GLFW_KEY_F12))
        assertNull(controls.key(GLFW_KEY_F12, GLFW_REPEAT, 0))
        assertNull(release(GLFW_KEY_F12))
    }

    // The altscreen shows the next of the iPhone's cluster contents; it changes nothing on a release.
    @Test
    fun `F11 moves the altscreen to its next content`() {
        assertEquals(Control.NextAltscreenContent, press(GLFW_KEY_F11))
        assertNull(release(GLFW_KEY_F11))
    }

    // On the altscreen the wheel zooms the dashboard map: up zooms in, a step per notch, touchpad fractions add up.
    @Test
    fun `the wheel over the altscreen zooms its map`() {
        assertEquals(Control.MapZoom(1), controls.altscreenScroll(1.0))
        assertEquals(Control.MapZoom(-2), controls.altscreenScroll(-2.0))
        assertNull(controls.altscreenScroll(0.5))
        assertEquals(Control.MapZoom(1), controls.altscreenScroll(0.5))
    }

    @Test
    fun `shift F11 moves the altscreen to its next card layout`() {
        assertEquals(Control.NextAltscreenCards, press(GLFW_KEY_F11, GLFW_MOD_SHIFT))
    }

    // Plus and minus on either keyboard zoom the AltScreen map; plus on the main keys is Shift+Equal.
    @Test
    fun `plus and minus zoom the altscreen map on both keyboards`() {
        assertEquals(Control.MapZoom(1), press(GLFW_KEY_EQUAL))
        assertEquals(Control.MapZoom(1), press(GLFW_KEY_EQUAL, GLFW_MOD_SHIFT))
        assertEquals(Control.MapZoom(1), press(GLFW_KEY_KP_ADD))
        assertEquals(Control.MapZoom(-1), press(GLFW_KEY_MINUS))
        assertEquals(Control.MapZoom(-1), press(GLFW_KEY_KP_SUBTRACT))
        assertNull(release(GLFW_KEY_KP_SUBTRACT))
    }

    // Windows repeats about 30 times a second; the cluster map has few zoom levels and reached its end within a
    // second at every third repeat, so a held zoom key steps on every tenth.
    @Test
    fun `a held zoom key steps on every tenth repeat`() {
        press(GLFW_KEY_KP_ADD)
        val repeats = (1..20).map { controls.key(GLFW_KEY_KP_ADD, GLFW_REPEAT, 0) }
        assertEquals(listOf(10, 20), repeats.withIndex().filter { it.value != null }.map { it.index + 1 })
    }

    // Starts the connection over without closing the app: a session that stalls, a phone that lost the Wi-Fi.
    @Test
    fun `ctrl R reconnects`() {
        assertEquals(Control.Reconnect, press(GLFW_KEY_R, GLFW_MOD_CONTROL))
        assertNull(press(GLFW_KEY_R))
    }

    @Test
    fun `the mouse wheel turns the knob a step per notch`() {
        assertEquals(Control.Knob(AirPlayKnobState(wheel = 1), true), controls.scroll(-1.0))
        assertEquals(Control.Knob(AirPlayKnobState(wheel = -2), true), controls.scroll(2.0))
        assertNull(controls.scroll(0.5))
        assertEquals(Control.Knob(AirPlayKnobState(wheel = -1), true), controls.scroll(0.5))
    }
}
