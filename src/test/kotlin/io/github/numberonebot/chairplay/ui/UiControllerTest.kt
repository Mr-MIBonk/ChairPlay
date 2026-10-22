package io.github.numberonebot.chairplay.ui

import io.github.numberonebot.chairplay.config.SettingsFile
import io.github.numberonebot.chairplay.session.BringUpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class UiControllerTest {
    private val dir: Path = Files.createTempDirectory("chairplay-ui")
    private val file: Path = dir.resolve("settings.properties")
    private val connects = AtomicInteger()
    private val reconnects = AtomicInteger()
    private val logged = mutableListOf<String>()

    private fun controller(
        settings: String = "wifi.ssid=Home\nwifi.passphrase=secret123\nwifi.security=WPA_WPA2\nui.autoconnect=true\n" +
            "ui.autoshow=true\naltscreen.enabled=false\nwindow.width=\nwindow.height=\naltscreen.width=1440\naltscreen.height=542\n",
        autoconnect: Boolean = true,
    ): UiController {
        Files.writeString(file, settings)
        val values = SettingsFile(file).read()
        val model = UiModel(!values["wifi.ssid"].isNullOrBlank(), autoconnect, autoshow = true)
        val setup = SetupInfo(dir.resolve("offline-mfi"), FirewallStatus.MISSING, "netsh advfirewall ...", file, 1280, 720)
        return UiController(SettingsFile(file), model, setup, object : UiActions {
            override fun connect() {
                connects.incrementAndGet()
            }

            override fun reconnect() {
                reconnects.incrementAndGet()
            }
        }, logged::add, autoconnect)
    }

    // The page and the log may be seen by others: the stored password is only ever "set" or "not set".
    @Test
    fun `the password never leaves the app`() {
        val ui = controller()

        ui.saveSettings(mapOf("wifi.passphrase" to "brand new pass"))

        val texts = listOf(Json.write(ui.state()), Json.write(ui.settings())) + logged
        for (text in texts) assertFalse(text, "secret123" in text || "brand new pass" in text)
        assertEquals(true, (ui.state()["wifi"] as Map<*, *>)["passphraseSet"])
        assertEquals(true, ui.settings()["passphraseSet"])
        assertFalse((ui.settings()["values"] as Map<*, *>).containsKey("wifi.passphrase"))
    }

    @Test
    fun `a valid change is written into the file`() {
        val ui = controller()

        val result = ui.saveSettings(mapOf("window.width" to "1600", "altscreen.enabled" to "true"))

        assertTrue(result.errors.isEmpty())
        val text = Files.readString(file)
        assertTrue(text, "window.width=1600\n" in text && "altscreen.enabled=true\n" in text)
        assertEquals(mapOf("window.width" to "restart", "altscreen.enabled" to "restart"), result.applies)
    }

    @Test
    fun `an invalid change leaves the file alone and says why`() {
        val ui = controller()
        val before = Files.readString(file)

        val result = ui.saveSettings(mapOf("window.width" to "1600", "altscreen.width" to "1441"))

        assertEquals(setOf("altscreen.width"), result.errors.keys)
        assertEquals(before, Files.readString(file))
    }

    @Test
    fun `autoshow applies at once`() {
        val ui = controller()

        ui.saveSettings(mapOf("ui.autoshow" to "false"))

        assertFalse(ui.model.state.autoshow)
    }

    @Test
    fun `restart-only changes are listed until they are changed back`() {
        val ui = controller()

        ui.saveSettings(mapOf("window.width" to "1600"))
        assertEquals(setOf("window.width"), ui.model.state.restartPending)

        ui.saveSettings(mapOf("window.width" to ""))
        assertEquals(emptySet<String>(), ui.model.state.restartPending)
    }

    @Test
    fun `a Wi-Fi change during a session waits for a reconnect`() {
        val ui = controller()
        ui.model.sessionActive()

        ui.saveSettings(mapOf("wifi.ssid" to "Garage"))

        assertEquals(setOf("wifi.ssid"), ui.model.state.reconnectPending)
        ui.reconnect()
        assertEquals(1, reconnects.get())
    }

    // With ui.autoconnect the app connects as soon as it has a network to connect on.
    @Test
    fun `the first Wi-Fi network starts the connection`() {
        val ui = controller(settings = "wifi.ssid=\nwifi.passphrase=\nwifi.security=WPA_WPA2\n")
        assertEquals(Phase.SETUP, ui.model.state.phase)

        ui.saveSettings(mapOf("wifi.ssid" to "Home", "wifi.passphrase" to "secret123"))

        assertEquals(1, connects.get())
        assertEquals(Phase.STARTING, ui.model.state.phase)
    }

    @Test
    fun `without autoconnect the first Wi-Fi network waits for Connect`() {
        val ui = controller(settings = "wifi.ssid=\n", autoconnect = false)

        ui.saveSettings(mapOf("wifi.ssid" to "Cafe", "wifi.security" to "NONE"))

        assertEquals(0, connects.get())
        assertEquals(Phase.IDLE, ui.model.state.phase)
        assertNull(ui.connect())
        assertEquals(1, connects.get())
        assertEquals(Phase.STARTING, ui.model.state.phase)
    }

    @Test
    fun `Connect needs a Wi-Fi network`() {
        val ui = controller(settings = "wifi.ssid=\n", autoconnect = false)

        assertTrue(ui.connect()!!.contains("Wi-Fi"))
        assertEquals(0, connects.get())
    }

    @Test
    fun `the state tells the page what to show`() {
        val ui = controller()
        ui.model.bringUp(BringUpStatus.Failed("RFCOMM connect failed", 5_000))

        val state = ui.state()

        assertEquals("retrying", state["phase"])
        assertEquals("start", state["screen"])
        assertEquals("RFCOMM connect failed", (state["error"] as Map<*, *>)["message"])
        assertEquals(false, state["canShowCarPlay"])
        assertEquals("Home", (state["wifi"] as Map<*, *>)["ssid"])
        val setup = state["setup"] as Map<*, *>
        assertEquals(false, setup["mfiPresent"])
        assertEquals("missing", setup["firewall"])
    }

    @Test
    fun `the MFi files are found once they are in place`() {
        val ui = controller()
        Files.createDirectories(dir.resolve("offline-mfi"))
        Files.writeString(dir.resolve("offline-mfi/identity.pk8"), "x")
        Files.writeString(dir.resolve("offline-mfi/certificate.p7b"), "x")

        assertEquals(true, (ui.state()["setup"] as Map<*, *>)["mfiPresent"])
    }

    @Test
    fun `screens change as the model allows`() {
        val ui = controller()

        assertFalse(ui.show("carplay"))
        assertTrue(ui.show("settings"))
        assertEquals(Screen.SETTINGS, ui.model.state.screen)
        ui.model.sessionActive()
        assertTrue(ui.show("carplay"))
        assertFalse(ui.show("elsewhere"))
    }

    @Test
    fun `every change moves the version on`() {
        val ui = controller()
        val before = ui.version

        ui.model.sessionActive()

        assertTrue(ui.version > before)
        assertEquals(ui.version, ui.awaitChange(before, 10))
    }
}
