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
    private val disconnects = AtomicInteger()
    private val logged = mutableListOf<String>()
    private val launched = mutableListOf<List<String>>()

    private fun controller(
        settings: String = "wifi.ssid=Home\nwifi.passphrase=secret123\nwifi.security=WPA_WPA2\nui.autoconnect=true\n" +
            "ui.autoshow=true\naltscreen.enabled=false\nwindow.width=\nwindow.height=\naltscreen.width=1440\naltscreen.height=542\n",
        autoconnect: Boolean = true,
        windows: Map<String, UiWindow> = emptyMap(),
        controls: UiControls? = null,
        launch: (List<String>) -> Unit = { launched += it },
        audio: () -> AudioDeviceNames = { AudioDeviceNames(listOf("Speakers (Realtek)"), listOf("Microphone Array")) },
    ): UiController {
        Files.writeString(file, settings)
        val values = SettingsFile(file).read()
        val model = UiModel(!values["wifi.ssid"].isNullOrBlank(), autoconnect, autoshow = true)
        val setup = SetupInfo(dir.resolve("offline-mfi"), FirewallStatus.MISSING, "netsh advfirewall ...", file, 1280, 720)
        return UiController(SettingsFile(file), model, setup, object : UiActions {
            override fun connect() {
                connects.incrementAndGet()
            }

            override fun disconnect() {
                disconnects.incrementAndGet()
            }

            override fun reconnect() {
                reconnects.incrementAndGet()
            }
        }, logged::add, autoconnect, windows, controls, launch, audio)
    }

    /** Controls that record what the page asked of them. */
    private class FakeControls : UiControls {
        val calls = mutableListOf<String>()
        override fun control(action: String, down: Boolean) = (action != "eject").also { calls += "$action $down" }
        override fun altscreenContent(content: String) = (content == "hud").also { calls += "altscreen $content" }
        override fun state(): Map<String, Any?> = mapOf("volume" to mapOf("level" to 60, "muted" to false))
    }

    /** A window that only records what it was asked. */
    private class FakeWindow(override var visible: Boolean) : UiWindow {
        override fun setShown(shown: Boolean) {
            visible = shown
        }
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

    // The settings screen's triggers: the AltScreen's and the controls' windows, shown or hidden, or not there at all.
    @Test
    fun `the state says which windows there are and which show`() {
        val ui = controller(windows = mapOf("controls" to FakeWindow(visible = false)))

        assertEquals(
            mapOf(
                "altscreen" to mapOf("available" to false, "visible" to false),
                "controls" to mapOf("available" to true, "visible" to false),
            ),
            ui.state()["windows"],
        )
    }

    @Test
    fun `a trigger shows or hides its window, if there is one`() {
        val controls = FakeWindow(visible = true)
        val ui = controller(windows = mapOf("controls" to controls))

        assertTrue(ui.window("controls", visible = false))
        assertFalse(controls.visible)
        assertFalse(ui.window("altscreen", visible = true))
        assertFalse(ui.window("elsewhere", visible = true))
    }

    // A window hides itself with its close button: the page hears of it.
    @Test
    fun `a window shown or hidden moves the version on`() {
        val ui = controller(windows = mapOf("controls" to FakeWindow(visible = true)))
        val before = ui.version

        ui.windowsChanged()

        assertTrue(ui.version > before)
    }

    // The controls window's page: its buttons reach the app, and the state carries what they show.
    @Test
    fun `the controls window's buttons reach the app and its state reaches the page`() {
        val controls = FakeControls()
        val ui = controller(controls = controls)

        assertTrue(ui.control("knob.select", down = true))
        assertFalse(ui.control("eject", down = true))
        assertTrue(ui.altscreenContent("hud"))

        assertEquals(listOf("knob.select true", "eject true", "altscreen hud"), controls.calls)
        assertEquals(mapOf("volume" to mapOf("level" to 60, "muted" to false)), ui.state()["controls"])
        val before = ui.version
        ui.controlsChanged()
        assertTrue(ui.version > before)
    }

    @Test
    fun `without controls the buttons reach nothing`() {
        val ui = controller()

        assertFalse(ui.control("knob.select", down = true))
        assertFalse(ui.altscreenContent("hud"))
        assertNull(ui.state()["controls"])
    }

    // The pages show choices as words; the file keeps them in lower case, as the app reads them.
    @Test
    fun `choices are saved in lower case`() {
        val ui = controller()

        val result = ui.saveSettings(mapOf("display.theme" to " Dark ", "display.dock" to "Driver", "altscreen.cards" to "LEFT",
            "display.right.hand.drive" to "True", "device.name" to " My Car "))

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        val text = Files.readString(file)
        for (line in listOf("display.theme=dark", "display.dock=driver", "altscreen.cards=left", "display.right.hand.drive=true", "device.name=My Car")) {
            assertTrue(line, "$line\n" in text)
        }
    }

    // The Audio page offers the devices Windows has, by the names the settings match.
    @Test
    fun `the settings carry the sound devices`() {
        val audio = controller().settings()["audio"] as Map<*, *>

        assertEquals(listOf("Speakers (Realtek)"), audio["outputs"])
        assertEquals(listOf("Microphone Array"), audio["inputs"])
        val none = controller(audio = { throw IllegalStateException("no Java Sound") }).settings()["audio"] as Map<*, *>
        assertEquals(emptyList<String>(), none["outputs"])
    }

    @Test
    fun `Disconnect ends the session and leaves the app idle`() {
        val ui = controller()
        ui.model.sessionActive()

        ui.disconnect()

        assertEquals(1, disconnects.get())
        assertEquals(Phase.IDLE, ui.model.state.phase)
    }

    // The screens' links open Windows' own pages; the page names a place the app knows, never a command of its own.
    @Test
    fun `a link opens the Windows page it names, and nothing else`() {
        val ui = controller()

        assertTrue(ui.open("bluetooth"))
        assertTrue(ui.open("wifi"))
        assertTrue(ui.open("firewall"))
        assertFalse(ui.open("calc.exe"))
        assertFalse(ui.open("ms-settings:bluetooth"))

        assertEquals(
            listOf(
                listOf("explorer.exe", "ms-settings:bluetooth"),
                listOf("explorer.exe", "ms-settings:network-wifi"),
                listOf("explorer.exe", "windowsdefender://network/"),
            ),
            launched,
        )
    }

    // Its rules are in the firewall's own console; the shell asks for the administrator's consent it needs.
    @Test
    fun `the firewall's rules open in its console through the shell`() {
        val ui = controller()

        assertTrue(ui.open("firewall-rules"))

        assertEquals("explorer.exe", launched.single()[0])
        assertTrue(launched.single()[1], launched.single()[1].endsWith("wf.msc"))
    }

    @Test
    fun `the MFi folder opens in File Explorer, made first if it is missing`() {
        val ui = controller()

        assertTrue(ui.open("mfi-folder"))

        assertTrue(Files.isDirectory(dir.resolve("offline-mfi")))
        assertEquals(listOf(listOf("explorer.exe", dir.resolve("offline-mfi").toString())), launched)
    }

    @Test
    fun `a page Windows does not open is logged`() {
        val ui = controller(launch = { throw java.io.IOException("explorer.exe not found") })

        assertFalse(ui.open("bluetooth"))

        assertTrue(logged.joinToString(), logged.any { "bluetooth" in it && "explorer.exe not found" in it })
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
