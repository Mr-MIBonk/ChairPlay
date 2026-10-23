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
        val result = errors("log.carplay.data" to "true")

        assertEquals(setOf("log.carplay.data"), result.keys)
        assertTrue(result.getValue("log.carplay.data"), "cannot be changed here" in result.getValue("log.carplay.data"))
    }

    // Every setting the pages show: the iPhone, the CarPlay screen, the AltScreen, and the sound.
    @Test
    fun `the pages' settings can change`() {
        val result = errors(
            "device.name" to "Гараж", "iphone.address" to "AA:bb:CC:dd:EE:ff", "iphone.join.seconds" to "45",
            "display.width" to "1920", "display.height" to "720", "display.fps" to "30",
            "display.physical.width.mm" to "292", "display.physical.height.mm" to "", "display.ppi" to "",
            "display.theme" to "dark", "display.dock" to "driver", "display.right.hand.drive" to "true",
            "display.safe.draw.outside" to "false", "display.corners" to "rounded",
            "display.safe.top" to "10", "display.safe.bottom" to "0", "display.safe.left" to "40", "display.safe.right" to "0",
            "altscreen.content" to "map-card", "altscreen.cards" to "left",
            "altscreen.safe.top" to "0", "altscreen.safe.bottom" to "0", "altscreen.safe.left" to "12", "altscreen.safe.right" to "0",
            "audio.output" to "Speakers (Realtek(R) Audio)", "audio.input" to "default", "audio.echo.cancellation" to "true",
        )

        assertEquals(emptyMap<String, String>(), result)
    }

    @Test
    fun `choices take only the values the app knows`() {
        assertTrue("windows, light, dark, phone" in errors("display.theme" to "neon").getValue("display.theme"))
        assertTrue("auto, driver, bottom" in errors("display.dock" to "left").getValue("display.dock"))
        assertTrue("rounded, square" in errors("display.corners" to "round").getValue("display.corners"))
        assertTrue("map, map-card, hud, card" in errors("altscreen.content" to "radar").getValue("altscreen.content"))
        assertTrue("auto, left, right, top, off" in errors("altscreen.cards" to "bottom").getValue("altscreen.cards"))
        for (key in listOf("display.right.hand.drive", "display.safe.draw.outside", "audio.echo.cancellation")) {
            assertTrue(key, "true or false" in errors(key to "yes").getValue(key))
        }
    }

    @Test
    fun `the CarPlay screen's numbers are ones the iPhone takes`() {
        assertTrue("even" in errors("display.width" to "801").getValue("display.width"))
        assertTrue("even" in errors("display.height" to "").getValue("display.height"))
        assertTrue("30 to 60" in errors("display.fps" to "25").getValue("display.fps"))
        assertTrue("30 to 60" in errors("display.fps" to "fast").getValue("display.fps"))
        assertTrue("positive" in errors("display.physical.width.mm" to "0").getValue("display.physical.width.mm"))
        assertTrue("positive" in errors("display.ppi" to "abc").getValue("display.ppi"))
        assertTrue(errors("display.physical.width.mm" to "", "display.ppi" to " ").isEmpty())
    }

    // The safe area must leave some of the screen: checked against the size as it will be, changed or not.
    @Test
    fun `a safe area leaves some of the screen`() {
        val wide = errors("display.safe.left" to "400", "display.safe.right" to "500", "display.width" to "800")
        assertTrue(wide.toString(), "left and right" in wide.getValue("display.safe.left"))
        val tall = EditableSettings.validate(mapOf("display.safe.top" to "300"), current + ("display.height" to "480") + ("display.safe.bottom" to "200"))
        assertTrue(tall.toString(), "top and bottom" in tall.getValue("display.safe.top"))
        assertTrue("zero or more" in errors("display.safe.top" to "-1").getValue("display.safe.top"))
        assertTrue("zero or more" in errors("altscreen.safe.left" to "x").getValue("altscreen.safe.left"))
    }

    @Test
    fun `the iPhone's settings are ones it can have`() {
        assertTrue("63 bytes" in errors("device.name" to "Ж".repeat(32)).getValue("device.name"))
        assertTrue("control" in errors("device.name" to "a\nb").getValue("device.name"))
        assertTrue(errors("device.name" to "").isEmpty())
        assertTrue("Bluetooth address" in errors("iphone.address" to "my phone").getValue("iphone.address"))
        assertTrue(errors("iphone.address" to "").isEmpty())
        assertTrue("positive" in errors("iphone.join.seconds" to "0").getValue("iphone.join.seconds"))
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
        // The main window is never smaller than 800x480.
        assertTrue("at least 800" in errors("window.width" to "799").getValue("window.width"))
        assertTrue("at least 480" in errors("window.height" to "479").getValue("window.height"))
        assertTrue(errors("window.width" to "800", "window.height" to "480").isEmpty())
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
        // The app reads these once, as it starts.
        for (key in listOf("device.name", "iphone.address", "iphone.join.seconds", "display.width", "display.theme",
            "display.safe.top", "altscreen.content", "altscreen.safe.left", "audio.output", "audio.echo.cancellation")) {
            assertEquals(key, Applies.RESTART, EditableSettings.applies(key))
        }
    }
}
