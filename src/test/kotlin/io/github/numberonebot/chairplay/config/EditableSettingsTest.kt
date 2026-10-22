package io.github.numberonebot.chairplay.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditableSettingsTest {
    private val current = mapOf(
        "wifi.ssid" to "Home", "wifi.passphrase" to "secret123", "wifi.security" to "WPA_WPA2",
        "ui.autoconnect" to "true", "ui.autoshow" to "true", "altscreen.enabled" to "false",
        "window.width" to "", "window.height" to "", "altscreen.width" to "1440", "altscreen.height" to "542",
    )

    private fun errors(vararg changes: Pair<String, String>) = EditableSettings.validate(changes.toMap(), current)

    @Test
    fun `valid changes pass`() {
        val result = errors(
            "wifi.ssid" to "Гараж", "wifi.passphrase" to "correct horse", "wifi.security" to "WPA3_TRANSITION",
            "ui.autoconnect" to "false", "ui.autoshow" to "false", "altscreen.enabled" to "true",
            "window.width" to "1600", "window.height" to "", "altscreen.width" to "1920", "altscreen.height" to "720",
        )

        assertEquals(emptyMap<String, String>(), result)
    }

    @Test
    fun `only the settings the UI offers can change`() {
        val result = errors("display.width" to "1920")

        assertEquals(setOf("display.width"), result.keys)
        assertTrue(result.getValue("display.width"), "cannot be changed here" in result.getValue("display.width"))
    }

    @Test
    fun `the network name must fit a Wi-Fi network`() {
        assertTrue("empty" in errors("wifi.ssid" to " ").getValue("wifi.ssid"))
        assertTrue("32 bytes" in errors("wifi.ssid" to "x".repeat(33)).getValue("wifi.ssid"))
        assertTrue("32 bytes" in errors("wifi.ssid" to "Ж".repeat(17)).getValue("wifi.ssid"))
        assertTrue("control" in errors("wifi.ssid" to "a\tb").getValue("wifi.ssid"))
    }

    // WPA takes 8 to 63 printable ASCII characters or 64 hexadecimal digits.
    @Test
    fun `a WPA password must be one the network can have`() {
        assertTrue("8 to 63" in errors("wifi.passphrase" to "1234567").getValue("wifi.passphrase"))
        assertTrue("8 to 63" in errors("wifi.passphrase" to "x".repeat(64)).getValue("wifi.passphrase"))
        assertTrue("8 to 63" in errors("wifi.passphrase" to "пароль-пароль").getValue("wifi.passphrase"))
        assertEquals(emptyMap<String, String>(), errors("wifi.passphrase" to "0123456789abcdef".repeat(4)))
    }

    // Choosing WPA for a network whose password is not set asks for one; an open network needs none.
    @Test
    fun `the password is checked against the security chosen with it`() {
        val open = current + ("wifi.security" to "NONE") + ("wifi.passphrase" to "")

        assertEquals(emptyMap<String, String>(), EditableSettings.validate(mapOf("wifi.ssid" to "Cafe"), open))
        val wpa = EditableSettings.validate(mapOf("wifi.security" to "WPA_WPA2"), open)
        assertTrue(wpa.toString(), "8 to 63" in wpa.getValue("wifi.passphrase"))
        assertEquals(emptyMap<String, String>(), errors("wifi.security" to "NONE", "wifi.passphrase" to ""))
    }

    @Test
    fun `the security must be one the iPhone knows`() {
        val message = errors("wifi.security" to "wpa2").getValue("wifi.security")

        assertTrue(message, "NONE, WEP, WPA_WPA2, WPA3_TRANSITION, WPA3_ONLY" in message)
    }

    @Test
    fun `switches take true or false`() {
        for (key in listOf("ui.autoconnect", "ui.autoshow", "altscreen.enabled")) {
            assertTrue(key, "true or false" in errors(key to "yes").getValue(key))
        }
    }

    @Test
    fun `sizes must be numbers the windows can have`() {
        assertTrue("positive whole number" in errors("window.width" to "0").getValue("window.width"))
        assertTrue("positive whole number" in errors("window.height" to "big").getValue("window.height"))
        assertTrue("even" in errors("altscreen.width" to "1441").getValue("altscreen.width"))
        assertTrue("even" in errors("altscreen.height" to "").getValue("altscreen.height"))
    }

    // What the screen says after a save.
    @Test
    fun `each setting says when a change takes effect`() {
        assertEquals(Applies.RECONNECT, EditableSettings.applies("wifi.ssid"))
        assertEquals(Applies.RECONNECT, EditableSettings.applies("wifi.passphrase"))
        assertEquals(Applies.NOW, EditableSettings.applies("ui.autoshow"))
        assertEquals(Applies.RESTART, EditableSettings.applies("ui.autoconnect"))
        assertEquals(Applies.RESTART, EditableSettings.applies("altscreen.enabled"))
        assertEquals(Applies.RESTART, EditableSettings.applies("window.width"))
    }
}
