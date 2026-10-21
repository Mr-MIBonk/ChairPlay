package com.shilapi.xcertplay.desktop.media

import com.shilapi.xcertplay.desktop.config.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NightModeTest {
    @Test
    fun `the theme decides night mode, windows following its dark mode`() {
        assertEquals(false, nightMode(Theme.LIGHT) { true })
        assertEquals(true, nightMode(Theme.DARK) { false })
        assertEquals(true, nightMode(Theme.WINDOWS) { true })
        assertEquals(false, nightMode(Theme.WINDOWS) { false })
        assertNull(nightMode(Theme.PHONE) { true })
    }

    // Without the registry value (older Windows, other systems) light is the safe answer.
    @Test
    fun `an unreadable windows theme counts as light`() {
        assertEquals(false, nightMode(Theme.WINDOWS) { null })
    }
}
