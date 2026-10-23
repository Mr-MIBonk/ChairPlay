package io.github.numberonebot.chairplay.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageKeysTest {
    private val keys = PageKeys { ControlsConfig.DEFAULT }

    // Over the settings the page keeps the keys it moves and types with; the app's own keys still reach the app.
    @Test
    fun `over the settings the app's keys work and the page's stay the page's`() {
        assertEquals(Control.NextAltscreenContent, keys.key("F11", PageKeys.PRESS, everything = false))
        assertEquals(Control.NextAltscreenCards, keys.key("Shift+F11", PageKeys.PRESS, everything = false))
        assertEquals(Control.Screenshot, keys.key("F12", PageKeys.PRESS, everything = false))
        assertEquals(Control.MapZoom(1), keys.key("Equal", PageKeys.PRESS, everything = false))
        assertEquals(Control.MapZoom(-1), keys.key("Minus", PageKeys.PRESS, everything = false))

        for (pageKey in listOf("Up", "Enter", "Tab", "Escape", "Home", "S")) {
            assertNull(pageKey, keys.key(pageKey, PageKeys.PRESS, everything = false))
        }
    }

    // The controls window's page has nothing to type in or move about: every key of the app works over it.
    @Test
    fun `over the controls window every key works`() {
        val up = keys.key("Up", PageKeys.PRESS, everything = true)
        assertTrue("$up", up is Control.Knob)
        assertTrue(keys.key("Up", PageKeys.RELEASE, everything = true) is Control.Knob)
        assertEquals(Control.NextAltscreenContent, keys.key("F11", PageKeys.PRESS, everything = true))
    }

    // A key held repeats as it does on the window: the zoom steps once, then on every tenth repeat.
    @Test
    fun `a held key repeats as on the window`() {
        assertEquals(Control.MapZoom(1), keys.key("Equal", PageKeys.PRESS, everything = false))
        repeat(9) { assertNull(keys.key("Equal", PageKeys.REPEAT, everything = false)) }
        assertEquals(Control.MapZoom(1), keys.key("Equal", PageKeys.REPEAT, everything = false))
        assertNull(keys.key("Equal", PageKeys.RELEASE, everything = false))
    }

    @Test
    fun `a name that is no key does nothing`() {
        assertNull(keys.key("Ctrl+Nothing", PageKeys.PRESS, everything = true))
        assertNull(keys.key("F11", "hold", everything = true))
    }
}
