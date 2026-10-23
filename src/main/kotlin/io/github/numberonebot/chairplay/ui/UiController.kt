package io.github.numberonebot.chairplay.ui

import io.github.numberonebot.chairplay.config.Applies
import io.github.numberonebot.chairplay.config.EditableSettings
import io.github.numberonebot.chairplay.config.SettingsFile
import io.github.numberonebot.chairplay.media.AudioDevices
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

/** The main window, for the CarPlay page's Actual size. */
interface UiMainWindow {
    /** The client area, in screen pixels. */
    val width: Int
    val height: Int

    /** Safe from any thread: resizes the client area to [width] x [height] screen pixels soon after. */
    fun resizeTo(width: Int, height: Int)
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
enum class FirewallStatus { PRESENT, MISSING, BLOCKED, UNKNOWN }

/** What the start screen's setup steps refer to. */
data class SetupInfo(
    val mfiDirectory: Path,
    val firewall: FirewallStatus,
    /** The netsh commands that add the firewall rules, from an administrator prompt. */
    val firewallCommands: String,
    val settingsFile: Path,
    val displayWidth: Int,
    val displayHeight: Int,
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

    /** Resizes the main window to one screen pixel per picture pixel; false when there is no main window. */
    fun actualSize(): Boolean

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

    init {
        model.addListener { changed() }
    }

    /** Tells the page that a window was shown or hidden, by its trigger or by its own close button, or resized. */
    fun windowsChanged() = changed()

    /** The picture is the display size the app started with, which a change of display.width waits a restart for. */
    override fun actualSize(): Boolean {
        val window = mainWindow ?: return false
        window.resizeTo(setup.displayWidth, setup.displayHeight)
        return true
    }

    override fun window(name: String, visible: Boolean): Boolean {
        val window = windows[name] ?: return false
        window.setShown(visible)
        return true
    }

    /** Tells the page that what the controls window shows changed: the volume, the AltScreen's content, Alt held. */
    fun controlsChanged() = changed()

    override fun control(action: String, down: Boolean): Boolean = controls?.control(action, down) ?: false

    override fun altscreenContent(content: String): Boolean = controls?.altscreenContent(content) ?: false

    /**
     * Opens a Windows page the screens link to, by the name of one the app knows ([PLACES]): Bluetooth or Wi-Fi in
     * Settings, the firewall in Windows Security or its console with the rules, the MFi folder (made first if it is
     * missing). Each goes through explorer.exe, which hands it to the shell: a Settings address opens its page, and the
     * console asks for the administrator's consent it needs. False for a name the app does not know, or when Windows
     * did not start it.
     */
    override fun open(place: String): Boolean {
        if (place !in PLACES) return false
        return runCatching { launch(listOf("explorer.exe", target(place))) }
            .onFailure { log("could not open $place: $it") }
            .isSuccess
    }

    private fun target(place: String): String = when (place) {
        "bluetooth" -> "ms-settings:bluetooth"
        "wifi" -> "ms-settings:network-wifi"
        "firewall" -> "windowsdefender://network/"
        "firewall-rules" -> Path.of(System.getenv("SystemRoot") ?: "C:/Windows", "System32", "wf.msc").toString()
        else -> Files.createDirectories(setup.mfiDirectory).toString()
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
            "mainWindow" to mainWindow?.let {
                mapOf("width" to it.width, "height" to it.height, "pictureWidth" to setup.displayWidth, "pictureHeight" to setup.displayHeight)
            },
            "controls" to controls?.state(),
            "reconnectPending" to state.reconnectPending.sorted(),
            "restartPending" to state.restartPending.sorted(),
            "setup" to linkedMapOf(
                "mfiFolder" to setup.mfiDirectory.toString(),
                "mfiPresent" to MFI_FILES.all { Files.isRegularFile(setup.mfiDirectory.resolve(it)) },
                "firewall" to setup.firewall.name.lowercase(),
                "firewallCommands" to setup.firewallCommands,
                "settingsFile" to setup.settingsFile.toString(),
            ),
        )
    }

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
        if (!now["wifi.ssid"].isNullOrBlank() && model.wifiSaved() && autoconnect) {
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
        val PLACES = listOf("bluetooth", "wifi", "firewall", "firewall-rules", "mfi-folder")
    }
}
