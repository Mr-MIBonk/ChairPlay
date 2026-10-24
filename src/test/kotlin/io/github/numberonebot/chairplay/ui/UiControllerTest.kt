package io.github.numberonebot.chairplay.ui

import io.github.numberonebot.chairplay.config.SettingsFile
import io.github.numberonebot.chairplay.config.SetupStore
import io.github.numberonebot.chairplay.config.WindowSize
import io.github.numberonebot.chairplay.drive.GeoPoint
import io.github.numberonebot.chairplay.drive.WindowsLocation
import io.github.numberonebot.chairplay.input.ControlAction
import io.github.numberonebot.chairplay.input.ControlsConfig
import io.github.numberonebot.chairplay.input.ControlsFile
import io.github.numberonebot.chairplay.input.KeyBinding
import org.lwjgl.glfw.GLFW.GLFW_KEY_F6
import io.github.numberonebot.chairplay.session.BringUpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        mainWindow: UiMainWindow? = null,
        bindings: ControlsFile? = null,
        checks: SetupChecks? = null,
        elevate: (String) -> Boolean = { elevated += it; consent },
        setupStore: SetupStore? = null,
        about: BuildInfo = BuildInfo(Commit("4e6d50df", "2026-10-23"), Commit("143f4674", "2026-10-21")),
        altscreenWindow: UiMainWindow? = null,
        altscreenFrame: UiSafeAreaFrame? = null,
        onSaved: (Map<String, String>) -> Unit = {},
        demo: UiDemo? = null,
        gps: (() -> Map<String, Any?>)? = null,
        locate: () -> WindowsLocation.Result = { WindowsLocation.Unknown },
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
        }, logged::add, autoconnect, windows, controls, launch, audio, mainWindow, bindings, applied::add, checks, elevate, setupStore,
            about, windowsVersion = { "Windows 11 24H2 (build 26300)" }, altscreenWindow = altscreenWindow, altscreenFrame = altscreenFrame,
            onSaved = onSaved, demo = demo, gps = gps, locate = locate)
    }

    // The GPS page's Windows location: the place Windows found, or why there is none.
    @Test
    fun `Windows' location comes as a place or a reason`() {
        assertEquals(
            mapOf("latitude" to 48.85, "longitude" to 2.29),
            controller(locate = { WindowsLocation.Found(GeoPoint(48.85, 2.29)) }).windowsLocation(),
        )
        assertEquals(mapOf("error" to "denied"), controller(locate = { WindowsLocation.Denied }).windowsLocation())
        assertEquals(mapOf("error" to "unknown"), controller(locate = { WindowsLocation.Unknown }).windowsLocation())
    }

    // Copy details and New issue carry the same text: the build, the DiPlay core, and Windows; never a setting.
    @Test
    fun `the details for a report name the build, the core, and Windows`() {
        val details = (controller().state()["about"] as Map<*, *>)["details"] as String

        for (part in listOf("4e6d50df", "143f4674", "Windows 11 24H2 (build 26300)")) assertTrue(details, part in details)
    }

    @Test
    fun `New issue opens GitHub with the details filled in`() {
        val ui = controller()
        assertTrue(ui.open("issues"))

        val (program, url) = launched.single()
        assertEquals("explorer.exe", program)
        assertTrue(url, url.startsWith("https://github.com/NumberOneBot/ChairPlay/issues/new?body="))
        val body = java.net.URLDecoder.decode(url.substringAfter("body="), Charsets.UTF_8)
        assertTrue(body, body.endsWith((ui.state()["about"] as Map<*, *>)["details"] as String))
    }

    @Test
    fun `the About page's links open the project's pages, and the notices in Notepad`() {
        val ui = controller()
        val pages = mapOf(
            "author" to "https://github.com/NumberOneBot",
            "source" to "https://github.com/NumberOneBot/ChairPlay",
            "releases" to "https://github.com/NumberOneBot/ChairPlay/releases",
            "discussions" to "https://github.com/NumberOneBot/ChairPlay/discussions",
            "pull-requests" to "https://github.com/NumberOneBot/ChairPlay/pulls",
            "diplay" to "https://github.com/shihabal3amri/DiPlay",
            "xcertplay" to "https://github.com/shilapi/xcertplay",
            "rgi" to "https://github.com/luka-dev/mib2q-carplay-rgi",
        )
        for ((place, url) in pages) {
            launched.clear()
            assertTrue(place, ui.open(place))
            assertEquals(listOf("explorer.exe", url), launched.single())
        }

        launched.clear()
        assertTrue(ui.open("notices"))
        assertEquals(listOf("notepad.exe", dir.resolve("THIRD_PARTY_NOTICES").toString()), launched.single())
    }

    // Get started shows until the first CarPlay session, which marks the setup done for the starts after.
    @Test
    fun `the first session marks the setup done`() {
        val store = SetupStore(dir)
        val ui = controller(setupStore = store)
        assertEquals(false, ui.state()["setupDone"])

        ui.model.sessionActive()

        assertEquals(true, ui.state()["setupDone"])
        assertTrue(store.done())
    }

    @Test
    fun `Forget this iPhone opens Get started again`() {
        val store = SetupStore(dir)
        store.save(true)
        val ui = controller(setupStore = store)
        val before = ui.version

        assertTrue(ui.forget())

        assertEquals(false, ui.state()["setupDone"])
        assertFalse(store.done())
        assertTrue(ui.version > before)
        assertFalse(controller().forget())
    }

    // The iPhone asks how its screen looks at each connection: a change waits for the next one, which the page marks.
    @Test
    fun `the CarPlay screen's corners and edges wait for the next connection`() {
        val ui = controller()

        ui.saveSettings(mapOf("display.corners" to "rounded", "display.safe.draw.outside" to "false"))

        assertEquals(listOf("display.corners", "display.safe.draw.outside"), ui.state()["reconnectPending"])
        assertEquals(emptyList<String>(), ui.state()["restartPending"])
    }

    private class FakeDemo(override var available: Boolean = true) : UiDemo {
        override var playing: String? = null
        override fun play(kind: String?): Boolean {
            if (kind != null && !available) return false
            playing = kind
            return true
        }
    }

    // The Experiments page plays the HUD's demos on the AltScreen, one at a time, and stops them.
    @Test
    fun `the Experiments page plays a HUD demo and stops it`() {
        val demo = FakeDemo()
        val ui = controller(demo = demo)
        assertEquals(mapOf("playing" to null, "available" to true), ui.state()["demo"])
        val before = ui.version

        assertTrue(ui.demo("lanes"))
        assertEquals("lanes", (ui.state()["demo"] as Map<*, *>)["playing"])
        assertTrue(ui.version > before)
        assertTrue(ui.demo(null))
        assertEquals(null, (ui.state()["demo"] as Map<*, *>)["playing"])

        assertFalse(ui.demo("tetris"))
        demo.available = false
        assertFalse(ui.demo("maneuvers"))
        assertFalse(controller().demo("maneuvers"))
        assertNull(controller().state()["demo"])
    }

    // The GPS page shows the car as it goes: the state carries it, and each change of it tells the page.
    @Test
    fun `the GPS page gets the car's state as it changes`() {
        var speed = 0
        val ui = controller(gps = { mapOf("speed" to speed) })
        assertEquals(mapOf("speed" to 0), ui.state()["gps"])
        val before = ui.version
        speed = 48
        ui.gpsChanged()
        assertTrue(ui.version > before)
        assertEquals(mapOf("speed" to 48), ui.state()["gps"])
        assertNull(controller().state()["gps"])
    }

    // The page shows the black waiting screen instead of the settings while the app connects on its own.
    @Test
    fun `the page hears whether the start waits for the connection`() {
        assertEquals(true, controller().state()["waiting"])
        assertEquals(false, controller(autoconnect = false).state()["waiting"])
    }

    // CarPlay turns dark or light while it runs, as a car's headlights tell it to: a saved theme goes out at once.
    @Test
    fun `a saved theme reaches the app at once and waits for nothing`() {
        val saved = mutableListOf<Map<String, String>>()
        val ui = controller(onSaved = { saved += it })

        ui.saveSettings(mapOf("display.theme" to "dark"))

        assertEquals(listOf(mapOf("display.theme" to "dark")), saved)
        assertEquals(emptyList<String>(), ui.state()["restartPending"])
        assertEquals(emptyList<String>(), ui.state()["reconnectPending"])
    }

    // Show frame previews the safe area saved on the AltScreen page, before a restart puts it in force.
    @Test
    fun `Show frame draws the saved AltScreen safe area on its window`() {
        val shown = mutableListOf<List<Int>>()
        val frame = UiSafeAreaFrame { top, bottom, left, right -> shown += listOf(top, bottom, left, right) }
        val ui = controller(settings = "altscreen.safe.top=40\naltscreen.safe.left=100\naltscreen.safe.right=\n", altscreenFrame = frame)

        assertTrue(ui.showAltscreenFrame())

        assertEquals(listOf(listOf(40, 0, 100, 0)), shown)
        assertFalse(controller().showAltscreenFrame())
    }

    // A view's Show frame draws its own margins inside its part, as insets from the whole frame; a view without either
    // draws the AltScreen's, as it uses them.
    @Test
    fun `Show frame for a view draws its part's margins`() {
        val shown = mutableListOf<List<Int>>()
        val frame = UiSafeAreaFrame { top, bottom, left, right -> shown += listOf(top, bottom, left, right) }
        val ui = controller(settings = "altscreen.width=1920\naltscreen.height=720\naltscreen.safe.top=40\n" +
            "altscreen.card.area=center3\naltscreen.card.safe.top=10\n", altscreenFrame = frame)

        assertTrue(ui.showAltscreenFrame("card"))
        assertTrue(ui.showAltscreenFrame("map"))
        assertFalse(ui.showAltscreenFrame("hud"))
        // A part without margins of its own has the AltScreen's clipped to it, as DiPlay declares it.
        val clipped = controller(settings = "altscreen.width=1920\naltscreen.height=720\naltscreen.safe.top=40\naltscreen.map.area=left5\n",
            altscreenFrame = frame)
        assertTrue(clipped.showAltscreenFrame("map"))

        assertEquals(listOf(listOf(10, 0, 600, 600), listOf(40, 0, 0, 0), listOf(40, 0, 0, 720)), shown)
    }

    // The pages colour themselves from it.
    @Test
    fun `the state carries the accent colour`() {
        assertEquals("#0a84ff", controller().state()["accent"])
    }

    // The About page's Version group.
    @Test
    fun `the state says which build this is`() {
        val about = controller().state()["about"] as Map<*, *>

        assertTrue(Regex("[0-9a-f]{8}").matches((about["app"] as Map<*, *>)["commit"] as String))
        assertTrue(Regex("[0-9a-f]{8}").matches((about["core"] as Map<*, *>)["commit"] as String))
    }

    // Get started owns the first Connect: a network saved before the password must not start a bring-up without it.
    @Test
    fun `the first Wi-Fi network waits for Connect while Get started is open`() {
        val ui = controller(settings = "wifi.ssid=\nwifi.passphrase=\nui.autoconnect=true\n", setupStore = SetupStore(dir))

        ui.saveSettings(mapOf("wifi.ssid" to "Car"))

        assertEquals(0, connects.get())
        assertEquals(Phase.IDLE, ui.model.state.phase)
    }

    // Forget during a session: later changes while the session goes on must not mark the setup done again.
    @Test
    fun `Forget during a session stays forgotten`() {
        val store = SetupStore(dir)
        store.save(true)
        val ui = controller(setupStore = store)
        ui.model.sessionActive()

        ui.forget()
        ui.model.carButton()
        ui.model.show(Screen.CARPLAY)

        assertEquals(false, ui.state()["setupDone"])
        assertFalse(store.done())
    }

    // Actual size cannot go below the window's smallest size, which Windows' display scale makes larger.
    @Test
    fun `the state carries the main window's smallest size`() {
        val window = FakeMainWindow(1024, 576).apply { minWidth = 1000; minHeight = 600 }

        val main = controller(mainWindow = window).state()["mainWindow"] as Map<*, *>

        assertEquals(1000, main["minWidth"])
        assertEquals(600, main["minHeight"])
    }

    @Test
    fun `without a store the setup counts as done`() {
        assertEquals(true, controller().state()["setupDone"])
    }

    /** The commands run with the administrator's consent, and whether it is given. */
    private val elevated = mutableListOf<String>()
    private var consent = true

    // Add the rule: the netsh commands the page shows, run once with the administrator's consent; then read again.
    @Test
    fun `Add the rule runs the firewall commands elevated and reads the firewall again`() {
        var firewall = FirewallStatus.MISSING
        val checks = SetupChecks({ null }, { null }, { firewall }, FirewallStatus.MISSING, { true })
        val ui = controller(checks = checks)
        firewall = FirewallStatus.PRESENT
        val before = ui.version

        assertTrue(ui.addFirewallRule())

        assertEquals(listOf("netsh advfirewall ..."), elevated)
        assertEquals("present", (ui.state()["setup"] as Map<*, *>)["firewall"])
        assertTrue(ui.version > before)
    }

    @Test
    fun `Add the rule says when the consent was not given`() {
        consent = false

        assertFalse(controller().addFirewallRule())
    }

    /** The bindings each Keyboard page save put in force. */
    private val applied = mutableListOf<ControlsConfig>()

    private fun keyboard(): ControlsFile {
        ControlsConfig.load(dir)
        return ControlsFile(dir)
    }

    @Test
    fun `the controls carry each action's keys, defaults, and fixed keys, and the wheel and repeat`() {
        val controls = controller(bindings = keyboard()).controls()!!

        val actions = controls["actions"] as Map<*, *>
        assertEquals(
            mapOf("keys" to listOf("Escape", "Backspace"), "defaults" to listOf("Escape", "Backspace"), "fixed" to listOf("Escape")),
            actions["key.back"],
        )
        assertEquals(mapOf("keys" to listOf("Home"), "defaults" to listOf("Home"), "fixed" to emptyList<String>()), actions["key.home"])
        assertEquals(ControlAction.entries.map { it.key }, actions.keys.toList())
        assertEquals(
            mapOf("wheel.action" to "knob", "wheel.invert" to "false", "wheel.notches.per.step" to "1", "key.repeat.every" to "3",
                "key.zoom.repeat.every" to "10"),
            controls["settings"],
        )
        assertNull(controller().controls())
    }

    // Changes apply at once: the app takes the new bindings, and the controls window's legends follow the version.
    // Get started and the Connection page follow the PC as it changes: a phone paired, the PC on a network, a rule added.
    @Test
    fun `the setup in the state is the live checks'`() {
        val checks = SetupChecks({ "Kate's iPhone" }, { "ChairPlay-5G" }, { FirewallStatus.PRESENT }, FirewallStatus.MISSING, { true })
        val ui = controller(checks = checks)
        val setup = { ui.state()["setup"] as Map<*, *> }
        assertEquals(null, setup()["pairedIphone"])
        assertEquals("missing", setup()["firewall"])

        checks.check()

        assertEquals("Kate's iPhone", setup()["pairedIphone"])
        assertEquals("ChairPlay-5G", setup()["pcWifi"])
    }

    // The controls window's page shows each button's key while Alt is held, or always with ui.controls.keys.
    @Test
    fun `the state says whether the controls window shows its keys`() {
        val ui = controller()
        assertEquals(false, ui.state()["controlsKeys"])

        ui.saveSettings(mapOf("ui.controls.keys" to "true"))

        assertEquals(true, ui.state()["controlsKeys"])
    }

    @Test
    fun `a binding saved is put in force at once`() {
        val ui = controller(bindings = keyboard())
        val before = ui.version

        val result = ui.saveControls(mapOf("key.home" to "F6"))

        assertEquals(emptyMap<String, String>(), result.errors)
        assertEquals(ControlAction.HOME, applied.single().bindings[KeyBinding(GLFW_KEY_F6, 0)])
        assertTrue(ui.version > before)
        assertEquals(listOf("F6"), ((ui.controls()!!["actions"] as Map<*, *>)["key.home"] as Map<*, *>)["keys"])
    }

    @Test
    fun `a binding the start would refuse is refused`() {
        val ui = controller(bindings = keyboard())

        val result = ui.saveControls(mapOf("key.home" to "Escape"))

        assertEquals(setOf("key.home"), result.errors.keys)
        assertEquals(emptyList<ControlsConfig>(), applied)
    }

    /** A main window at [width] x [height] that records the sizes it was asked for. */
    private class FakeMainWindow(override var width: Int, override var height: Int) : UiMainWindow {
        override var available = true
        override var minWidth = 0
        override var minHeight = 0
        override var picture: WindowSize? = null
        val asked = mutableListOf<String>()
        override fun resizeTo(width: Int, height: Int) {
            asked += "${width}x$height"
        }
    }

    // The CarPlay page offers Actual size while the window's client area differs from the picture the app asked for.
    @Test
    fun `the state carries the main window's size and the picture's`() {
        val ui = controller(mainWindow = FakeMainWindow(1024, 576))

        assertEquals(
            mapOf("width" to 1024, "height" to 576, "pictureWidth" to 1280, "pictureHeight" to 720, "minWidth" to 0, "minHeight" to 0),
            ui.state()["mainWindow"],
        )
        assertNull(controller().state()["mainWindow"])
    }

    @Test
    fun `Actual size resizes the main window to one screen pixel per picture pixel`() {
        val window = FakeMainWindow(1024, 576)
        val ui = controller(mainWindow = window)

        assertTrue(ui.actualSize("main"))

        assertEquals(listOf("1280x720"), window.asked)
        assertFalse(controller().actualSize("main"))
    }

    // The AltScreen's window can be dragged to another size as well; its picture is the size the AltScreen page set.
    @Test
    fun `the AltScreen window has an Actual size of its own`() {
        val alt = FakeMainWindow(1200, 450).apply { picture = WindowSize(1440, 540) }
        val ui = controller(altscreenWindow = alt)

        assertEquals(
            mapOf("width" to 1200, "height" to 450, "pictureWidth" to 1440, "pictureHeight" to 540, "minWidth" to 0, "minHeight" to 0),
            ui.state()["altscreenWindow"],
        )
        assertTrue(ui.actualSize("altscreen"))
        assertEquals(listOf("1440x540"), alt.asked)
        assertFalse(ui.actualSize("controls"))
        assertNull(controller().state()["altscreenWindow"])
    }

    // The AltScreen's window waits, hidden, while altscreen.enabled is off: the page sees no AltScreen until a
    // connection turns it on.
    @Test
    fun `an AltScreen that is off is not there for the page`() {
        val trigger = FakeWindow(visible = false, available = false)
        val alt = FakeMainWindow(1440, 540).apply { picture = WindowSize(1440, 540); available = false }
        val ui = controller(windows = mapOf("altscreen" to trigger), altscreenWindow = alt)

        assertEquals(mapOf("available" to false, "visible" to false), (ui.state()["windows"] as Map<*, *>)["altscreen"])
        assertNull(ui.state()["altscreenWindow"])
        assertFalse(ui.actualSize("altscreen"))

        trigger.available = true
        alt.available = true
        assertEquals(mapOf("available" to true, "visible" to false), (ui.state()["windows"] as Map<*, *>)["altscreen"])
        assertEquals(1440, (ui.state()["altscreenWindow"] as Map<*, *>)["pictureWidth"])
    }

    @Test
    fun `a main window resized moves the version on`() {
        val ui = controller(mainWindow = FakeMainWindow(1024, 576))
        val before = ui.version

        ui.windowsChanged()

        assertTrue(ui.version > before)
    }

    /** Controls that record what the page asked of them. */
    private class FakeControls : UiControls {
        val calls = mutableListOf<String>()
        override fun control(action: String, down: Boolean) = (action != "eject").also { calls += "$action $down" }
        override fun altscreenContent(content: String) = (content == "hud").also { calls += "altscreen $content" }
        override fun volume(level: Int) = true.also { calls += "volume $level" }
        override fun state(): Map<String, Any?> = mapOf("volume" to mapOf("level" to 60, "muted" to false))
    }

    // The Audio page's slider sets the app's volume through the controls, as the volume keys turn it.
    @Test
    fun `the Audio page sets the volume`() {
        val controls = FakeControls()

        assertTrue(controller(controls = controls).volume(37))

        assertEquals(listOf("volume 37"), controls.calls)
        assertFalse(controller().volume(37))
    }

    /** A window that only records what it was asked. */
    private class FakeWindow(override var visible: Boolean, override var available: Boolean = true) : UiWindow {
        override fun setShown(shown: Boolean) {
            visible = shown
        }
    }

    // The state, the settings, and the log may be seen by others: there the stored password is only "set" or "not set".
    @Test
    fun `the password stays out of the state, the settings, and the log`() {
        val ui = controller()

        ui.saveSettings(mapOf("wifi.passphrase" to "brand new pass"))

        val texts = listOf(Json.write(ui.state()), Json.write(ui.settings())) + logged
        for (text in texts) assertFalse(text, "secret123" in text || "brand new pass" in text)
        assertEquals(true, (ui.state()["wifi"] as Map<*, *>)["passphraseSet"])
        assertEquals(true, ui.settings()["passphraseSet"])
        assertFalse((ui.settings()["values"] as Map<*, *>).containsKey("wifi.passphrase"))
    }

    // It is in settings.properties as written: the eye beside it asks for it, and gets it alone.
    @Test
    fun `the saved password is given when asked for`() {
        val ui = controller()
        assertEquals("secret123", ui.savedPassphrase())

        ui.saveSettings(mapOf("wifi.passphrase" to "brand new pass"))
        assertEquals("brand new pass", ui.savedPassphrase())
        assertNull(controller(settings = "wifi.ssid=Home\n").savedPassphrase())
        assertFalse(logged.any { "brand new pass" in it })
    }

    @Test
    fun `a valid change is written into the file`() {
        val ui = controller()

        val result = ui.saveSettings(mapOf("window.width" to "1600", "altscreen.enabled" to "true"))

        assertTrue(result.errors.isEmpty())
        val text = Files.readString(file)
        assertTrue(text, "window.width=1600\n" in text && "altscreen.enabled=true\n" in text)
        assertEquals(mapOf("window.width" to "restart", "altscreen.enabled" to "reconnect"), result.applies)
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

        assertNotNull(ui.connect())
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
        assertTrue(ui.open("location"))
        assertFalse(ui.open("calc.exe"))
        assertFalse(ui.open("ms-settings:bluetooth"))

        assertEquals(
            listOf(
                listOf("explorer.exe", "ms-settings:bluetooth"),
                listOf("explorer.exe", "ms-settings:network-wifi"),
                listOf("explorer.exe", "windowsdefender://network/"),
                listOf("explorer.exe", "ms-settings:privacy-location"),
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

    // The Experiments page's Logs: the CarPlay data log's folder and F12's screenshots, in the app folder.
    @Test
    fun `the logs and screenshots folders open in File Explorer, made first if they are missing`() {
        val ui = controller()

        assertTrue(ui.open("logs"))
        assertTrue(ui.open("screenshots"))

        assertTrue(Files.isDirectory(dir.resolve("logs")) && Files.isDirectory(dir.resolve("screenshots")))
        assertEquals(
            listOf(listOf("explorer.exe", dir.resolve("logs").toString()), listOf("explorer.exe", dir.resolve("screenshots").toString())),
            launched,
        )
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
