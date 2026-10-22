package io.github.numberonebot.chairplay.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.nio.file.Files
import java.util.Properties

class SettingsFileTest {
    private fun load(text: String): Properties = Properties().apply { load(StringReader(text)) }

    @Test
    fun `a value changes on its own line and the comments and other lines stay`() {
        val text = "# Wi-Fi network shared by this PC and the iPhone.\nwifi.ssid=Home\nwifi.passphrase=secret123\n\n" +
            "# 'true' or 'false'\nui.autoshow=true\n"

        val edited = editSettingsText(text, mapOf("wifi.ssid" to "Garage", "ui.autoshow" to "false"))

        assertEquals(
            "# Wi-Fi network shared by this PC and the iPhone.\nwifi.ssid=Garage\nwifi.passphrase=secret123\n\n" +
                "# 'true' or 'false'\nui.autoshow=false\n",
            edited,
        )
    }

    // The owner may have written the file by hand: spaces around the separator, a colon, indentation.
    @Test
    fun `the key and separator stay as written`() {
        val edited = editSettingsText("  window.width = 1280\nwindow.height:720\n", mapOf("window.width" to "1600", "window.height" to "900"))

        assertEquals("  window.width = 1600\nwindow.height:900\n", edited)
    }

    // A commented-out line with the same key is a comment, not a setting.
    @Test
    fun `comments that mention the key are left alone`() {
        val edited = editSettingsText("#wifi.ssid=Old\n! wifi.ssid=Older\nwifi.ssid=Home\n", mapOf("wifi.ssid" to "New"))

        assertEquals("#wifi.ssid=Old\n! wifi.ssid=Older\nwifi.ssid=New\n", edited)
    }

    // Properties reads a backslash as an escape and drops leading spaces: what is written must read back the same.
    @Test
    fun `values read back exactly as given`() {
        val values = listOf("C:\\Users\\dark", "  leading spaces", "pa=ss:wo#rd!", "Гараж", "trailing \\", "tab\there")
        for (value in values) {
            val edited = editSettingsText("wifi.passphrase=old\n", mapOf("wifi.passphrase" to value))
            assertEquals(value, load(edited).getProperty("wifi.passphrase"))
        }
    }

    @Test
    fun `a value spread over continuation lines is replaced whole`() {
        val text = "wifi.ssid=Ho\\\n    me\nwifi.security=WPA_WPA2\n"

        val edited = editSettingsText(text, mapOf("wifi.ssid" to "Garage"))

        assertEquals("wifi.ssid=Garage\nwifi.security=WPA_WPA2\n", edited)
    }

    // Properties keeps the last of several lines with one key, so each gets the new value.
    @Test
    fun `every line of a repeated key gets the value`() {
        val edited = editSettingsText("ui.autoshow=true\nui.autoshow=true\n", mapOf("ui.autoshow" to "false"))

        assertEquals("ui.autoshow=false\nui.autoshow=false\n", edited)
    }

    @Test
    fun `a key the file lacks is added at its end`() {
        assertEquals("wifi.ssid=Home\nui.autoshow=false\n", editSettingsText("wifi.ssid=Home\n", mapOf("ui.autoshow" to "false")))
        assertEquals("wifi.ssid=Home\nui.autoshow=false\n", editSettingsText("wifi.ssid=Home", mapOf("ui.autoshow" to "false")))
    }

    // Notepad writes CRLF; each line keeps the ending it had.
    @Test
    fun `line endings stay as they were`() {
        val edited = editSettingsText("# Wi-Fi\r\nwifi.ssid=Home\r\nwifi.passphrase=x\r\n", mapOf("wifi.ssid" to "Garage"))

        assertEquals("# Wi-Fi\r\nwifi.ssid=Garage\r\nwifi.passphrase=x\r\n", edited)
    }

    @Test
    fun `a line break in a value is refused`() {
        assertThrows(IllegalArgumentException::class.java) { editSettingsText("wifi.ssid=Home\n", mapOf("wifi.ssid" to "a\nb=c")) }
    }

    @Test
    fun `the file is rewritten in place and reads back the new values`() {
        val dir = Files.createTempDirectory("chairplay-settings")
        val file = dir.resolve("settings.properties")
        Files.writeString(file, "# comment\nwifi.ssid=Home\nwifi.passphrase=secret123\n")
        val settings = SettingsFile(file)

        settings.write(mapOf("wifi.passphrase" to "new passphrase"))

        assertEquals("# comment\nwifi.ssid=Home\nwifi.passphrase=new passphrase\n", Files.readString(file))
        assertEquals(mapOf("wifi.ssid" to "Home", "wifi.passphrase" to "new passphrase"), settings.read())
        assertTrue("a temporary file was left behind", Files.list(dir).use { it.count() } == 1L)
    }
}
