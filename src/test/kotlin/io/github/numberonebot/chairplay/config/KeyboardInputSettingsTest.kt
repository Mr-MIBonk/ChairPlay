package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class KeyboardInputSettingsTest {
    @Test fun `saved toggle is read for the next connection and leaves the current snapshot unchanged`() {
        val dir = Files.createTempDirectory("chairplay-keyboard-setting")
        writeTestSettings(dir)
        val config = DesktopConfig(dir)
        val settings = SettingsFile(dir.resolve("settings.properties"))
        assertTrue(config.airPlay.keyboardInput)
        settings.write(mapOf("keyboard.text.input" to "false"))
        assertFalse(config.airPlayNow().keyboardInput)
        assertTrue(config.airPlay.keyboardInput)
        settings.write(mapOf("keyboard.text.input" to "true"))
        assertTrue(config.airPlayNow().keyboardInput)
    }

    private fun devices(config: DesktopConfig): Set<String> =
        (AirPlayInfoPlist.build(config.airPlay)["hidDevices"] as List<*>).map { (it as Map<*, *>)["uuid"].toString() }.toSet()

    @Test fun `typing devices are enabled by default and can be disabled without removing existing controls`() {
        val disabled = devices(configWith("keyboard.text.input=false"))
        assertFalse("2a2a2a2e" in disabled)
        assertFalse("2a2a2a2f" in disabled)
        val enabled = devices(configWith())
        assertTrue(enabled.containsAll(disabled))
        assertTrue(enabled.containsAll(setOf("2a2a2a2e", "2a2a2a2f")))
    }

    @Test fun `typing setting is validated and applies on reconnection`() {
        assertTrue(EditableSettings.validate(mapOf("keyboard.text.input" to "true"), emptyMap()).isEmpty())
        assertTrue(EditableSettings.validate(mapOf("keyboard.text.input" to "false"), emptyMap()).isEmpty())
        assertFalse(EditableSettings.validate(mapOf("keyboard.text.input" to "maybe"), emptyMap()).isEmpty())
        assertEquals(Applies.RECONNECT, EditableSettings.applies("keyboard.text.input"))
    }
}
