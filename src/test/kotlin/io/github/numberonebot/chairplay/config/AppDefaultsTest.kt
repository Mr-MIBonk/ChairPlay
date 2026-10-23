package io.github.numberonebot.chairplay.config

import io.github.numberonebot.chairplay.input.ControlsConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AppDefaultsTest {
    // deploy writes these into the app folder, so the Wi-Fi can be filled in before the first start.
    @Test
    fun `writes the default settings, controls and icon into an empty folder`() {
        val dir = Files.createTempDirectory("chairplay-defaults")

        val written = writeAppDefaults(dir)

        assertEquals(listOf("settings.properties", ControlsConfig.FILE, DesktopConfig.DEFAULT_ICON_FILE), written)
        val settings = Files.readString(dir.resolve("settings.properties"))
        // Both files open the same way: what they are and how to get the defaults back.
        assertTrue(settings, settings.startsWith("# ChairPlay settings, read at start."))
        assertTrue(Files.readString(dir.resolve(ControlsConfig.FILE)).startsWith("# ChairPlay controls, read at start."))
        for (key in DesktopConfig.SETTING_KEYS) assertTrue("$key missing", Regex("(?m)^${Regex.escape(key)}=").containsMatchIn(settings))
        assertTrue(Files.readString(dir.resolve(ControlsConfig.FILE)).contains("\nkey.siri=S\n"))
        // The armchair of the app's own icon, not a picture drawn in code.
        assertArrayEquals(Files.readAllBytes(Path.of("packaging/icon/carplay-icon.png")), Files.readAllBytes(dir.resolve(DesktopConfig.DEFAULT_ICON_FILE)))
    }

    @Test
    fun `leaves files that are already there`() {
        val dir = Files.createTempDirectory("chairplay-defaults")
        Files.writeString(dir.resolve("settings.properties"), "wifi.ssid=Home\n")
        Files.writeString(dir.resolve(ControlsConfig.FILE), "key.siri = F10\n")

        val written = writeAppDefaults(dir)

        assertEquals(listOf(DesktopConfig.DEFAULT_ICON_FILE), written)
        assertEquals("wifi.ssid=Home\n", Files.readString(dir.resolve("settings.properties")))
        assertEquals("key.siri = F10\n", Files.readString(dir.resolve(ControlsConfig.FILE)))
    }
}
