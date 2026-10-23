package io.github.numberonebot.chairplay.ui

import io.github.numberonebot.chairplay.config.Applies
import io.github.numberonebot.chairplay.config.DesktopConfig
import io.github.numberonebot.chairplay.config.EditableSettings
import io.github.numberonebot.chairplay.config.SettingsFile
import io.github.numberonebot.chairplay.config.SetupStore
import io.github.numberonebot.chairplay.config.WindowSize
import io.github.numberonebot.chairplay.input.ControlAction
import io.github.numberonebot.chairplay.input.ControlsConfig
import io.github.numberonebot.chairplay.input.ControlsFile
import io.github.numberonebot.chairplay.input.ControlsSave
import io.github.numberonebot.chairplay.input.WheelAction
import io.github.numberonebot.chairplay.media.AudioDevices
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter

/** What the screens ask the app to do with the iPhone. */
interface UiActions {
    /** Starts connecting, as the app does on its own with ui.autoconnect. */
    fun connect()

    /** Ends the session or bring-up and starts a new one, as Ctrl+R does. */
    fun reconnect()

    /** Ends the session or bring-up; none starts until Connect. */
    fun disconnect()
}

/** A window of the app the settings screen shows and hides: the AltScreen's, the controls'. */
interface UiWindow {
    val visible: Boolean

    /** Safe from any thread: the window shows or hides on its own thread soon after. */
    fun setShown(shown: Boolean)
}

/** A window that shows a picture, for its page's Actual size: the main window, the AltScreen's. */
interface UiMainWindow {
    /** The client area, in screen pixels. */
    val width: Int
    val height: Int

    /** The smallest client area its edges and a resize can reach, in screen pixels: Windows' display scale grows it. */
    val minWidth: Int get() = 0
    val minHeight: Int get() = 0

    /** The picture it shows now, one screen pixel per picture pixel at Actual size; null for the display size the app started with. */
    val picture: WindowSize? get() = null

    /** Safe from any thread: resizes the client area to [width] x [height] screen pixels soon after. */
    fun resizeTo(width: Int, height: Int)
}

/** The AltScreen's window drawing a safe area for a moment; insets in the picture's pixels. Safe from any thread. */
fun interface UiSafeAreaFrame {
    fun show(top: Int, bottom: Int, left: Int, right: Int)
}

/** The controls window's page: the car's buttons, and what it shows beside them. Safe from any thread. */
interface UiControls {
    /**
     * Presses ([down]) or releases the button [action] names: a key of controls.properties without its "key."
     * ("knob.select", "volume.up"). False when there is no such button.
     */
    fun control(action: String, down: Boolean): Boolean

    /** Shows [content] ("hud", "map", "map-card", "card") on the AltScreen; false when it cannot be shown. */
    fun altscreenContent(content: String): Boolean

    /** What the page shows beside the buttons: their keys, the volume, the AltScreen's content, whether Alt is held. */
    fun state(): Map<String, Any?>
}

/** Whether Windows Firewall lets the iPhone in, as read at start. */
enum class FirewallStatus {
    PRESENT, MISSING, BLOCKED, UNKNOWN,

    /** Off for the networks the PC is on: nothing to add, nothing to warn of. */
    OFF,
}

/** What the start screen's setup steps refer to. */
data class SetupInfo(
    val mfiDirectory: Path,
    val firewall: FirewallStatus,
    /** The netsh commands that add the firewall rules, from an administrator prompt. */
    val firewallCommands: String,
    val settingsFile: Path,
    val displayWidth: Int,
    val displayHeight: Int,
    /** ui.accent, #rrggbb, which the pages colour themselves from. */
    val accent: String = DesktopConfig.DEFAULT_ACCENT,
)

/** The sound devices by the names audio.output and audio.input match against. */
data class AudioDeviceNames(val outputs: List<String>, val inputs: List<String>)

/** A save's outcome: the problems by key, or, when there are none, when each saved setting applies. */
class SaveResult(val errors: Map<String, String>, val applies: Map<String, String>)

/** What the HTTP server asks of the app; the JSON messages are maps and lists for [Json]. */
interface UiApi {
    /** Moves on with every change of what [state] returns. */
    val version: Long
    fun state(): Map<String, Any?>
    fun settings(): Map<String, Any?>
    fun saveSettings(changes: Map<String, String>): SaveResult

    /** Null when connecting started, else why it cannot. */
    fun connect(): String?
    fun reconnect()
    fun disconnect()

    /** Shows the screen named "start", "settings" or "carplay"; false when it cannot be shown. */
    fun show(screen: String): Boolean

    /** Shows or hides the window named "altscreen" or "controls"; false when there is no such window. */
    fun window(name: String, visible: Boolean): Boolean

    /** A button of the controls window pressed or released ([UiControls.control]); false when there is none. */
    fun control(action: String, down: Boolean): Boolean

    /** The controls window's AltScreen modes ([UiControls.altscreenContent]); false when it cannot be shown. */
    fun altscreenContent(content: String): Boolean

    /** Opens the Windows page or folder [place] names ([UiController.open]); false when there is none or it failed. */
    fun open(place: String): Boolean

    /**
     * Resizes the window named "main" or "altscreen" to one screen pixel per picture pixel; false when there is no such
     * window.
     */
    fun actualSize(window: String): Boolean

    /** Shows the AltScreen's saved safe area on its window for a moment; false without an AltScreen. */
    fun showAltscreenFrame(): Boolean

    /** Adds the firewall rules with the administrator's consent; false when it was not given or they failed. */
    fun addFirewallRule(): Boolean

    /** Forget this iPhone: ChairPlay's pairing records go and Get started opens; false when there is nothing to forget. */
    fun forget(): Boolean

    /**
     * The Keyboard page's bindings: each action's keys, defaults and fixed keys, and the wheel and repeat settings as
     * controls.properties names them; null when the app has none to change.
     */
    fun controls(): Map<String, Any?>?

    /** Saves controls.properties [changes] and puts them in force at once, or says why the start would refuse them. */
    fun saveControls(changes: Map<String, String>): SaveResult

    /** Waits up to [timeoutMillis] for [version] to differ from [seen], and returns it. */
    fun awaitChange(seen: Long, timeoutMillis: Long): Long
}

/**
 * The screens' side of the app: the state the page shows, the settings it reads and writes in [settings], and the
 * actions it asks for. The stored Wi-Fi password never leaves: the page and the log learn only whether it is set.
 */
class UiController(
    private val settings: SettingsFile,
    val model: UiModel,
    private val setup: SetupInfo,
    private val actions: UiActions,
    private val log: (String) -> Unit,
    /** ui.autoconnect as the app started: the first Wi-Fi network saved then starts the connection. */
    private val autoconnect: Boolean = true,
    /** The windows the settings screen's triggers show and hide, by name; one left out is not there. */
    private val windows: Map<String, UiWindow> = emptyMap(),
    /** The controls window's buttons; null when there is no such window. */
    private val controls: UiControls? = null,
    /** Starts a program with its arguments, for [open]. */
    private val launch: (List<String>) -> Unit = { ProcessBuilder(it).start() },
    /** The sound devices the Audio page offers, as Java Sound names them. */
    private val audioDevices: () -> AudioDeviceNames = {
        AudioDeviceNames(AudioDevices.outputs().map { it.name }, AudioDevices.inputs().map { it.name })
    },
    /** The main window, whose size the CarPlay page compares with the picture's; null in tests without one. */
    private val mainWindow: UiMainWindow? = null,
    /** controls.properties, which the Keyboard page changes; null where there is none to change. */
    private val bindings: ControlsFile? = null,
    /** Puts bindings the Keyboard page saved in force: the next key press reads them. */
    private val onBindings: (ControlsConfig) -> Unit = {},
    /** The setup read again as the PC changes; null keeps the start's reading of the firewall. */
    private val checks: SetupChecks? = null,
    /** Runs commands with the administrator's consent (Windows asks for it); true when they ran and succeeded. */
    private val elevate: (String) -> Boolean = { false },
    /** Whether Get started is done; null counts it as done. */
    private val setupStore: SetupStore? = null,
    /** Which build this is, for the About page. */
    private val about: BuildInfo = BuildInfo.load(),
    /** The Windows version, for the details of a report. */
    private val windowsVersion: () -> String = ::windowsVersion,
    /** The AltScreen's window, for the AltScreen page's Actual size; null without an AltScreen. */
    private val altscreenWindow: UiMainWindow? = null,
    /** The AltScreen's window drawing a safe area, for the AltScreen page's Show frame; null without an AltScreen. */
    private val altscreenFrame: UiSafeAreaFrame? = null,
) : UiApi {
    /** The settings as the app started with them, against which a change waits for the next start. */
    private val startup: Map<String, String> = settings.read()

    /** True while the page is in the main window; false when it opened in a browser instead. */
    @Volatile
    var embedded = false
        set(value) {
            field = value
            changed()
        }

    private val lock = Object()

    @Volatile
    override var version = 0L
        private set

    /** Get started is done: an iPhone has connected since the last Forget. */
    @Volatile
    private var setupDone = setupStore?.done() ?: true

    /** The phase the model was last in, so the setup is marked done on the change into a session alone. */
    private var lastPhase = model.state.phase

    init {
        model.addListener { state ->
            val entered = state.phase == Phase.ACTIVE && lastPhase != Phase.ACTIVE
            lastPhase = state.phase
            // The first CarPlay session ends Get started, for this start and the next; Forget during it stays.
            if (entered && !setupDone) {
                setupDone = true
                runCatching { setupStore?.save(true) }.onFailure { log("setup done not remembered: $it") }
            }
            changed()
        }
    }

    /** Tells the page that a window was shown or hidden, by its trigger or by its own close button, or resized. */
    fun windowsChanged() = changed()

    /** The picture is the one the window shows now: a changed display.width comes with the next connection. */
    override fun actualSize(window: String): Boolean {
        val target = when (window) {
            "main" -> mainWindow
            "altscreen" -> altscreenWindow
            else -> null
        } ?: return false
        val picture = pictureOf(target)
        target.resizeTo(picture.width, picture.height)
        return true
    }

    private fun pictureOf(window: UiMainWindow) = window.picture ?: WindowSize(setup.displayWidth, setup.displayHeight)

    /** A window's client area, its picture, and its smallest size, for its page's Actual size. */
    private fun sizes(window: UiMainWindow): Map<String, Int> {
        val picture = pictureOf(window)
        return mapOf(
            "width" to window.width, "height" to window.height, "pictureWidth" to picture.width,
            "pictureHeight" to picture.height, "minWidth" to window.minWidth, "minHeight" to window.minHeight,
        )
    }

    override fun window(name: String, visible: Boolean): Boolean {
        val window = windows[name] ?: return false
        window.setShown(visible)
        return true
    }

    /** Tells the page that what the controls window shows changed: the volume, the AltScreen's content, Alt held. */
    fun controlsChanged() = changed()

    /** Tells the page that the live setup checks found a change. */
    fun setupChanged() = changed()

    /** The insets as saved, so the frame previews them before a restart puts them in force; blank is none. */
    override fun showAltscreenFrame(): Boolean {
        val frame = altscreenFrame ?: return false
        val values = runCatching { settings.read() }.getOrDefault(emptyMap())
        fun inset(edge: String) = values["altscreen.safe.$edge"]?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        frame.show(inset("top"), inset("bottom"), inset("left"), inset("right"))
        return true
    }

    override fun forget(): Boolean {
        val store = setupStore ?: return false
        runCatching { store.forget() }.onFailure {
            log("could not forget the iPhone: $it")
            return false
        }
        setupDone = false
        log("iPhone forgotten: its pairing records are deleted; Get started opens")
        changed()
        return true
    }

    /** The commands the page offers to copy, run as they are, so the two ways add the same rules. */
    override fun addFirewallRule(): Boolean {
        val added = runCatching { elevate(setup.firewallCommands) }.getOrDefault(false)
        log(if (added) "firewall rules added" else "firewall rules not added: no consent, or netsh failed")
        checks?.firewallNow()
        changed()
        return added
    }

    override fun controls(): Map<String, Any?>? {
        val config = bindings?.read() ?: return null
        return linkedMapOf(
            "actions" to ControlAction.entries.associate { action ->
                action.key to mapOf(
                    "keys" to config.keys(action),
                    "defaults" to action.defaults.split(',').map(String::trim).filter(String::isNotEmpty),
                    "fixed" to config.fixedKeys(action),
                )
            },
            "settings" to linkedMapOf(
                "wheel.action" to if (config.wheel == WheelAction.NONE) "none" else "knob",
                "wheel.invert" to config.wheelInvert.toString(),
                "wheel.notches.per.step" to config.notchesPerStep.toString(),
                "key.repeat.every" to config.repeatEvery.toString(),
                "key.zoom.repeat.every" to config.zoomRepeatEvery.toString(),
            ),
        )
    }

    /** A save also tells the controls window's page, whose buttons show each action's first key. */
    override fun saveControls(changes: Map<String, String>): SaveResult {
        val file = bindings ?: return SaveResult(changes.keys.associateWith { "The app has no key bindings to change" }, emptyMap())
        return when (val result = file.save(changes)) {
            is ControlsSave.Refused -> SaveResult(mapOf(result.key to result.message), emptyMap())
            is ControlsSave.Saved -> {
                onBindings(result.config)
                log("key bindings saved: ${result.changes.keys.joinToString()}")
                changed()
                SaveResult(emptyMap(), result.changes.keys.associateWith { "now" })
            }
        }
    }

    override fun control(action: String, down: Boolean): Boolean = controls?.control(action, down) ?: false

    override fun altscreenContent(content: String): Boolean = controls?.altscreenContent(content) ?: false

    /**
     * Opens a page the screens link to, by the name of one the app knows ([PLACES]): Bluetooth or Wi-Fi in Settings,
     * the firewall in Windows Security or its console with the rules, the MFi folder (made first if it is missing), and
     * the About page's: the project's pages on GitHub (a new issue with [details] filled in), DiPlay's, the HUD
     * renderer's, and the third-party notices beside the app, in Notepad as the file has no extension. Each but the
     * notices goes through explorer.exe, which hands it to the shell: a Settings address opens its page, a web address
     * the browser, and the console asks for the administrator's consent it needs. False for a name the app does not
     * know, or when Windows did not start it.
     */
    override fun open(place: String): Boolean {
        if (place !in PLACES) return false
        return runCatching {
            if (place == "notices") launch(listOf("notepad.exe", setup.settingsFile.resolveSibling("THIRD_PARTY_NOTICES").toString()))
            else launch(listOf("explorer.exe", target(place)))
        }
            .onFailure { log("could not open $place: $it") }
            .isSuccess
    }

    private fun target(place: String): String = when (place) {
        "bluetooth" -> "ms-settings:bluetooth"
        "wifi" -> "ms-settings:network-wifi"
        "firewall" -> "windowsdefender://network/"
        "firewall-rules" -> Path.of(System.getenv("SystemRoot") ?: "C:/Windows", "System32", "wf.msc").toString()
        "issues" -> "$PROJECT/issues/new?body=" +
            URLEncoder.encode("What happened, and what you expected:\n\n\n---\n${details()}", Charsets.UTF_8).replace("+", "%20")
        "discussions" -> "$PROJECT/discussions"
        "pull-requests" -> "$PROJECT/pulls"
        "source" -> PROJECT
        "releases" -> "$PROJECT/releases"
        "diplay" -> "https://github.com/shihabal3amri/DiPlay"
        "rgi" -> "https://github.com/luka-dev/mib2q-carplay-rgi"
        else -> Files.createDirectories(setup.mfiDirectory).toString()
    }

    /** What a report needs to say which ChairPlay it is about: the build, the DiPlay core, and Windows; no setting. */
    private fun details(): String {
        fun commit(name: String, commit: Commit?) = "$name ${commit?.let { "${it.id} (${it.date})" } ?: "not recorded"}"
        val windows = runCatching(windowsVersion).getOrDefault("Windows")
        return "${commit("ChairPlay", about.app)}\n${commit("DiPlay core", about.core)}\n$windows"
    }

    private fun changed() {
        synchronized(lock) {
            version++
            lock.notifyAll()
        }
    }

    override fun awaitChange(seen: Long, timeoutMillis: Long): Long {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        synchronized(lock) {
            while (version == seen) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                lock.wait(left)
            }
            return version
        }
    }

    override fun state(): Map<String, Any?> {
        val state = model.state
        val values = runCatching { settings.read() }.getOrDefault(emptyMap())
        return linkedMapOf(
            "version" to version,
            "phase" to state.phase.name.lowercase(),
            "screen" to state.screen.name.lowercase(),
            "error" to state.error?.let { mapOf("message" to it.message, "at" to it.at.format(TIME)) },
            "knownIphone" to state.knownIphone,
            "canShowCarPlay" to state.canShowCarPlay,
            "autoshow" to state.autoshow,
            "embedded" to embedded,
            "wifi" to mapOf(
                "ssid" to values["wifi.ssid"].orEmpty(),
                "security" to (values["wifi.security"]?.trim()?.ifEmpty { null } ?: "WPA_WPA2"),
                "passphraseSet" to !values["wifi.passphrase"].isNullOrEmpty(),
            ),
            "windows" to WINDOWS.associateWith { name ->
                val window = windows[name]
                mapOf("available" to (window != null), "visible" to (window?.visible ?: false))
            },
            "mainWindow" to mainWindow?.let(::sizes),
            "altscreenWindow" to altscreenWindow?.let(::sizes),
            "controls" to controls?.state(),
            "controlsKeys" to (values["ui.controls.keys"]?.trim() == "true"),
            "setupDone" to setupDone,
            "accent" to setup.accent,
            "about" to mapOf("app" to about.app?.let(::commitMap), "core" to about.core?.let(::commitMap), "details" to details()),
            "reconnectPending" to state.reconnectPending.sorted(),
            "restartPending" to state.restartPending.sorted(),
            "setup" to linkedMapOf(
                "mfiFolder" to setup.mfiDirectory.toString(),
                "mfiPresent" to MFI_FILES.all { Files.isRegularFile(setup.mfiDirectory.resolve(it)) },
                "firewall" to (checks?.snapshot?.firewall ?: setup.firewall).name.lowercase(),
                "pairedIphone" to checks?.snapshot?.pairedIphone,
                "pcWifi" to checks?.snapshot?.pcWifi,
                "firewallCommands" to setup.firewallCommands,
                "settingsFile" to setup.settingsFile.toString(),
            ),
        )
    }

    private fun commitMap(commit: Commit) = mapOf("commit" to commit.id, "date" to commit.date)

    override fun settings(): Map<String, Any?> {
        val values = settings.read()
        return linkedMapOf(
            "values" to EditableSettings.keys.filter { it != PASSPHRASE }.associateWith { values[it].orEmpty() },
            "passphraseSet" to !values[PASSPHRASE].isNullOrEmpty(),
            "applies" to EditableSettings.keys.associateWith { appliesName(EditableSettings.applies(it)) },
            "display" to mapOf("width" to setup.displayWidth, "height" to setup.displayHeight),
            "audio" to runCatching { audioDevices() }.getOrElse { AudioDeviceNames(emptyList(), emptyList()) }
                .let { mapOf("outputs" to it.outputs, "inputs" to it.inputs) },
        )
    }

    override fun saveSettings(changes: Map<String, String>): SaveResult {
        val normalized = changes.mapValues { (key, value) -> normalize(key, value) }
        val (saved, phase) = synchronized(this) {
            val current = settings.read()
            val errors = EditableSettings.validate(normalized, current)
            if (errors.isNotEmpty()) return SaveResult(errors, emptyMap())
            settings.write(normalized)
            normalized.filter { (key, value) -> current[key] != value } to model.state.phase
        }
        val applies = normalized.keys.associateWith { appliesName(EditableSettings.applies(it)) }
        if (saved.isNotEmpty()) log("settings saved: ${saved.keys.joinToString()}")
        saved["ui.autoshow"]?.let { model.setAutoshow(it == "true") }
        val now = settings.read()
        val restart = EditableSettings.keys.filter { EditableSettings.applies(it) == Applies.RESTART && now[it].orEmpty() != startup[it].orEmpty() }
        val running = phase != Phase.SETUP && phase != Phase.IDLE
        val reconnect = saved.keys.filter { EditableSettings.applies(it) == Applies.RECONNECT && running }
        model.pending(reconnect.toSet(), restart.toSet())
        // While Get started is open it owns the first Connect: a network saved before its password must not start
        // a bring-up without one.
        if (!now["wifi.ssid"].isNullOrBlank() && model.wifiSaved() && autoconnect && setupDone) {
            log("Wi-Fi network set; connecting")
            connectNow()
        }
        changed()
        return SaveResult(emptyMap(), applies)
    }

    override fun connect(): String? {
        if (model.state.phase == Phase.SETUP) return "Enter the Wi-Fi network first"
        connectNow()
        return null
    }

    private fun connectNow() {
        model.connecting()
        actions.connect()
    }

    override fun reconnect() = actions.reconnect()

    override fun disconnect() {
        actions.disconnect()
        model.disconnected()
    }

    override fun show(screen: String): Boolean {
        val target = Screen.entries.firstOrNull { it.name.equals(screen, ignoreCase = true) } ?: return false
        return model.show(target)
    }

    private fun normalize(key: String, value: String): String = when (key) {
        // A network name and a password are taken as typed: spaces may belong to them.
        "wifi.ssid", PASSPHRASE -> value
        else -> if (EditableSettings.caseless(key)) value.trim().lowercase() else value.trim()
    }

    private fun appliesName(applies: Applies) = applies.name.lowercase()

    private companion object {
        const val PASSPHRASE = "wifi.passphrase"
        val MFI_FILES = listOf("identity.pk8", "certificate.p7b")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        /** The windows the page has triggers for, each in the state whether it is there or not. */
        val WINDOWS = listOf("altscreen", "controls")
        /** The Windows pages and folder the screens may open, by the name the page gives ([open]). */
        val PLACES = listOf(
            "bluetooth", "wifi", "firewall", "firewall-rules", "mfi-folder",
            "issues", "discussions", "pull-requests", "source", "releases", "notices", "diplay", "rgi",
        )

        /** The project's home on GitHub. */
        const val PROJECT = "https://github.com/NumberOneBot/ChairPlay"
    }
}
