package io.github.numberonebot.chairplay.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.reader
import kotlin.io.path.writeText

/**
 * Where the window was: the top-left of its client area on the desktop and its size, in GLFW screen coordinates
 * (pixels on Windows). A maximized window keeps the size it returns to when restored.
 */
data class WindowPlacement(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)

/** The main window is never smaller: its screens are laid out to fit this, in the web view's pixels. */
val MAIN_WINDOW_MIN_SIZE = WindowSize(800, 480)

/** The AltScreen window is never narrower; its height follows the picture's shape. */
const val ALTSCREEN_MIN_WIDTH = 800

/** This size in screen pixels at Windows' display [scale] (1.5 at 150 %); a scale unknown (0) leaves it. */
fun WindowSize.scaledBy(scale: Float): WindowSize {
    val factor = scale.takeIf { it > 0f } ?: 1f
    return WindowSize(Math.round(width * factor), Math.round(height * factor))
}

/** This size with the shape of [aspect], keeping its width. */
fun WindowSize.fittedTo(aspect: WindowSize): WindowSize =
    WindowSize(width, Math.round(width.toDouble() * aspect.height / aspect.width).toInt().coerceAtLeast(1))

/** A monitor's work area (without the taskbar), in the same coordinates as [WindowPlacement]. */
data class ScreenArea(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * True when enough of the title bar falls on one of [screens] to grab it, so a window remembered on a monitor
 * that has since been unplugged, or moved out of reach, is not restored there.
 */
fun WindowPlacement.isVisibleOn(screens: List<ScreenArea>): Boolean {
    val top = y - TITLE_BAR_HEIGHT
    return screens.any { screen ->
        val wide = minOf(x + width, screen.x + screen.width) - maxOf(x, screen.x)
        val tall = minOf(y, screen.y + screen.height) - maxOf(top, screen.y)
        wide >= MIN_GRAB_WIDTH && tall >= MIN_GRAB_HEIGHT
    }
}

// The title bar sits above the client area; about 31 pixels at 100 % scaling.
private const val TITLE_BAR_HEIGHT = 32
private const val MIN_GRAB_WIDTH = 100
private const val MIN_GRAB_HEIGHT = 16

/**
 * Remembers the window between starts in window.properties in the app folder [dir]. The app writes that file
 * itself; settings.properties stays the user's. The file also keeps the window size the settings asked for at the
 * time, so a changed window.width/height (or display size) replaces the remembered size. Another window keeps its
 * place in the same file with its keys under [prefix] (the altscreen's under "altscreen."); saving one window
 * rewrites only its own lines.
 */
class WindowPlacementStore(private val dir: Path, private val prefix: String = "") {
    /** The remembered placement, or null before the first close or when the file is broken. */
    fun load(configured: WindowSize): WindowPlacement? {
        val values = readWindowState(dir) ?: return null
        fun text(key: String) = values.getProperty(prefix + key)?.trim()
        fun int(key: String) = text(key)?.toIntOrNull()
        val x = int("x") ?: return null
        val y = int("y") ?: return null
        val width = int("width")?.takeIf { it > 0 } ?: return null
        val height = int("height")?.takeIf { it > 0 } ?: return null
        val maximized = text("maximized") == "true"
        val sameSettings = text(CONFIGURED_KEY) == configured.text()
        return if (sameSettings) {
            WindowPlacement(x, y, width, height, maximized)
        } else {
            WindowPlacement(x, y, configured.width, configured.height, maximized)
        }
    }

    fun save(placement: WindowPlacement, configured: WindowSize) {
        writeWindowState(
            dir,
            listOf(
                "x" to placement.x, "y" to placement.y, "width" to placement.width, "height" to placement.height,
                "maximized" to placement.maximized, CONFIGURED_KEY to configured.text(),
            ).map { (key, value) -> prefix + key to value.toString() },
        )
    }

    private fun WindowSize.text() = "${width}x$height"

    companion object {
        const val FILE = "window.properties"
        private const val CONFIGURED_KEY = "settings.window.size"
    }
}

/**
 * Remembers the AltScreen's content and card layout (F11, Shift+F11) in window.properties, so it opens on the view
 * it was left on. Like the windows' sizes, a view remembered under other altscreen.content or altscreen.cards
 * settings than today's gives way to the settings.
 */
class AltscreenViewStore(private val dir: Path) {
    fun load(content: AltscreenContent, cards: AltscreenCards): Pair<AltscreenContent, AltscreenCards> {
        val values = readWindowState(dir) ?: return content to cards
        if (values.getProperty(SETTINGS)?.trim() != "${content.key},${cards.key}") return content to cards
        val savedContent = AltscreenContent.entries.firstOrNull { it.key == values.getProperty(CONTENT)?.trim() } ?: content
        val savedCards = AltscreenCards.entries.firstOrNull { it.key == values.getProperty(CARDS)?.trim() } ?: cards
        return savedContent to savedCards
    }

    fun save(content: AltscreenContent, cards: AltscreenCards, configured: Pair<AltscreenContent, AltscreenCards>) {
        writeWindowState(
            dir,
            listOf(CONTENT to content.key, CARDS to cards.key, SETTINGS to "${configured.first.key},${configured.second.key}"),
        )
    }

    private companion object {
        const val CONTENT = "altscreen.view.content"
        const val CARDS = "altscreen.view.cards"
        const val SETTINGS = "altscreen.view.settings"
    }
}

/**
 * Remembers whether the controls window was shown or hidden (its close button, or the settings screen's trigger) in
 * window.properties, so it opens that way; before it was ever left, window.controls decides.
 */
class ControlsWindowStore(private val dir: Path) {
    fun load(default: Boolean): Boolean =
        readWindowState(dir)?.getProperty(VISIBLE)?.trim()?.toBooleanStrictOrNull() ?: default

    fun save(visible: Boolean) = writeWindowState(dir, listOf(VISIBLE to visible.toString()))

    private companion object {
        const val VISIBLE = "controls.visible"
    }
}

private val WINDOW_STATE_HEADER = listOf(
    "# Where the ChairPlay windows were when they last closed, and the AltScreen's view, written by the app.",
    "# Delete this file to open them at their default place, size and view again.",
)

private fun readWindowState(dir: Path): Properties? {
    val file = dir.resolve(WindowPlacementStore.FILE)
    if (!file.exists()) return null
    return Properties().apply { file.reader().use(::load) }
}

/** Sets [values] in window.properties, each on its own line, leaving the other lines as they are. */
private fun writeWindowState(dir: Path, values: List<Pair<String, String>>) {
    Files.createDirectories(dir)
    val file = dir.resolve(WindowPlacementStore.FILE)
    val lines = if (file.exists()) Files.readAllLines(file).toMutableList() else WINDOW_STATE_HEADER.toMutableList()
    for ((key, value) in values) {
        val at = lines.indexOfFirst { it.startsWith("$key=") }
        if (at >= 0) lines[at] = "$key=$value" else lines += "$key=$value"
    }
    file.writeText(lines.joinToString("\n", postfix = "\n"))
}
