package com.shilapi.xcertplay.desktop.config

import com.shilapi.xcertplay.desktop.input.ControlsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppDefaultsTest {
    // deploy writes these into the app folder, so the Wi-Fi can be filled in before the first start.
    @Test
    fun `writes the default settings, controls and icon into an empty folder`() {
        val dir = Files.createTempDirectory("diplay-defaults")

        val written = writeAppDefaults(dir)

        assertEquals(listOf("settings.properties", ControlsConfig.FILE, DesktopConfig.DEFAULT_ICON_FILE), written)
        val settings = Files.readString(dir.resolve("settings.properties"))
        // Both files open the same way: what they are and how to get the defaults back.
        assertTrue(settings, settings.startsWith("# DiPlayDesktop settings, read at start."))
        assertTrue(Files.readString(dir.resolve(ControlsConfig.FILE)).startsWith("# DiPlayDesktop controls, read at start."))
        for (key in DesktopConfig.SETTING_KEYS) assertTrue("$key missing", Regex("(?m)^${Regex.escape(key)}=").containsMatchIn(settings))
        assertTrue(Files.readString(dir.resolve(ControlsConfig.FILE)).contains("\nkey.siri=S\n"))
        assertTrue(Files.size(dir.resolve(DesktopConfig.DEFAULT_ICON_FILE)) > 0)
    }

    @Test
    fun `leaves files that are already there`() {
        val dir = Files.createTempDirectory("diplay-defaults")
        Files.writeString(dir.resolve("settings.properties"), "wifi.ssid=Home\n")
        Files.writeString(dir.resolve(ControlsConfig.FILE), "key.siri = F10\n")

        val written = writeAppDefaults(dir)

        assertEquals(listOf(DesktopConfig.DEFAULT_ICON_FILE), written)
        assertEquals("wifi.ssid=Home\n", Files.readString(dir.resolve("settings.properties")))
        assertEquals("key.siri = F10\n", Files.readString(dir.resolve(ControlsConfig.FILE)))
    }
}
