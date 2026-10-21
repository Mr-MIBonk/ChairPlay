package com.shilapi.xcertplay.desktop.input

import org.lwjgl.glfw.GLFW.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.reader
import kotlin.io.path.writeText

/** Everything a key can be bound to, with the setting that names its keys and the keys it has by default. */
enum class ControlAction(val key: String, val defaults: String, val description: String) {
    KNOB_LEFT("key.knob.left", "Left", "tilt the knob left: focus moves left"),
    KNOB_RIGHT("key.knob.right", "Right", "tilt the knob right"),
    KNOB_UP("key.knob.up", "Up", "tilt the knob up"),
    KNOB_DOWN("key.knob.down", "Down", "tilt the knob down"),
    KNOB_NEXT("key.knob.next", "Tab", "turn the knob one step forward"),
    KNOB_PREVIOUS("key.knob.previous", "Shift+Tab", "turn the knob one step back"),
    SELECT("key.select", "Enter, KP_Enter, Space", "knob select"),
    BACK("key.back", "Escape, Backspace", "back"),
    HOME("key.home", "Home", "CarPlay home screen"),
    MEDIA_PREVIOUS("key.media.previous", "F7", "previous track"),
    MEDIA_PLAY_PAUSE("key.media.playpause", "F8", "play or pause"),
    MEDIA_NEXT("key.media.next", "F9", "next track"),
    CALL_ANSWER("key.call.answer", "F3", "answer a call"),
    CALL_END("key.call.end", "F4", "end a call"),
    SIRI("key.siri", "S", "Siri"),
    SCREENSHOT("key.screenshot", "F12", "save the CarPlay screen as a PNG in the screenshots folder"),
    ALTSCREEN_CONTENT("key.altscreen.content", "F11", "show the altscreen's next content: map, turn card, map with turn card"),
    ALTSCREEN_ZOOM_IN("key.altscreen.zoom.in", "Equal, Shift+Equal, KP_Add", "zoom the AltScreen map in"),
    ALTSCREEN_ZOOM_OUT("key.altscreen.zoom.out", "Minus, KP_Subtract", "zoom the AltScreen map out"),
    ALTSCREEN_CARDS("key.altscreen.cards", "Shift+F11", "move the altscreen's ETA and turn card: auto, left, right, top, off"),
}

/** A GLFW key code with the Shift, Ctrl and Alt bits that must be held. */
data class KeyBinding(val key: Int, val mods: Int)

enum class WheelAction { KNOB, NONE }

/**
 * Key bindings and mouse-wheel flags from `controls.properties` in the app folder, written with the defaults
 * and their documentation on the first start. A setting left out keeps its default; an empty one turns the
 * action off. Mistakes stop the start with the setting named, so a typo never silently loses a control.
 */
class ControlsConfig(
    val bindings: Map<KeyBinding, ControlAction>,
    val repeatEvery: Int = 3,
    /** A held AltScreen zoom key steps on every N-th repeat; 0 steps once a press. */
    val zoomRepeatEvery: Int = 10,
    val wheel: WheelAction = WheelAction.KNOB,
    val wheelInvert: Boolean = false,
    val notchesPerStep: Int = 1,
) {
    fun summary(): String =
        "controls: ${bindings.values.toSet().size} actions, wheel=${if (wheel == WheelAction.NONE) "none" else "knob"}"

    companion object {
        const val FILE = "controls.properties"

        private const val REPEAT = "key.repeat.every"
        private const val ZOOM_REPEAT = "key.zoom.repeat.every"
        private const val WHEEL = "wheel.action"
        private const val INVERT = "wheel.invert"
        private const val NOTCHES = "wheel.notches.per.step"
        private val MODIFIERS = mapOf("shift" to GLFW_MOD_SHIFT, "ctrl" to GLFW_MOD_CONTROL, "alt" to GLFW_MOD_ALT)
        internal const val MOD_MASK = GLFW_MOD_SHIFT or GLFW_MOD_CONTROL or GLFW_MOD_ALT

        // Lazy: parse needs KEY_NAMES, which is initialised below.
        val DEFAULT: ControlsConfig by lazy { parse(Properties()) }

        /** Reads [FILE] in [dir], writing it with the defaults first if it is not there. */
        fun load(dir: Path): ControlsConfig {
            val file = dir.resolve(FILE)
            if (!file.exists()) {
                Files.createDirectories(dir)
                file.writeText(defaultText())
            }
            return parse(Properties().apply { file.reader().use(::load) })
        }

        private fun parse(settings: Properties): ControlsConfig {
            val known = ControlAction.entries.map { it.key }.toSet() + setOf(REPEAT, ZOOM_REPEAT, WHEEL, INVERT, NOTCHES)
            settings.stringPropertyNames().firstOrNull { it !in known }?.let {
                throw IllegalArgumentException("$FILE: unknown setting $it")
            }
            val bindings = LinkedHashMap<KeyBinding, ControlAction>()
            val names = HashMap<KeyBinding, String>()
            for (action in ControlAction.entries) {
                val value = settings.getProperty(action.key) ?: action.defaults
                for (name in value.split(',').map(String::trim).filter(String::isNotEmpty)) {
                    val binding = parseBinding(name) ?: throw IllegalArgumentException(
                        "$FILE: ${action.key} = $value: \"$name\" is not a key; use a name such as F5, Ctrl+S or #290",
                    )
                    bindings[binding]?.let { other ->
                        throw IllegalArgumentException("$FILE: $name is bound to both ${other.key} (as ${names[binding]}) and ${action.key}")
                    }
                    bindings[binding] = action
                    names[binding] = name
                }
            }
            return ControlsConfig(
                bindings,
                repeatEvery = positive(settings, REPEAT, 3),
                zoomRepeatEvery = settings.getProperty(ZOOM_REPEAT)?.trim()?.ifEmpty { null }?.let { raw ->
                    raw.toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw IllegalArgumentException("$FILE: $ZOOM_REPEAT must be a whole number of zero or more, not $raw")
                } ?: 10,
                wheel = when (val wheel = settings.getProperty(WHEEL)?.trim()?.lowercase() ?: "knob") {
                    "knob" -> WheelAction.KNOB
                    "none" -> WheelAction.NONE
                    else -> throw IllegalArgumentException("$FILE: $WHEEL must be knob or none, not $wheel")
                },
                wheelInvert = when (val invert = settings.getProperty(INVERT)?.trim()?.lowercase() ?: "false") {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException("$FILE: $INVERT must be true or false, not $invert")
                },
                notchesPerStep = positive(settings, NOTCHES, 1),
            )
        }

        private fun positive(settings: Properties, key: String, default: Int): Int {
            val raw = settings.getProperty(key)?.trim()?.ifEmpty { null } ?: return default
            val value = raw.toIntOrNull()
            require(value != null && value > 0) { "$FILE: $key must be a positive whole number, not $raw" }
            return value
        }

        /** "Ctrl+Shift+F5", "kp_enter" or "#290" (a raw GLFW key code); null when it names no key. */
        internal fun parseBinding(text: String): KeyBinding? {
            val parts = text.split('+').map(String::trim)
            if (parts.any(String::isEmpty)) return null
            var mods = 0
            for (modifier in parts.dropLast(1)) mods = mods or (MODIFIERS[modifier.lowercase()] ?: return null)
            val keyName = parts.last()
            val key = if (keyName.startsWith("#")) {
                keyName.drop(1).toIntOrNull()?.takeIf { it in 32..GLFW_KEY_LAST }
            } else {
                KEY_NAMES[keyName.lowercase()]
            } ?: return null
            return KeyBinding(key, mods)
        }

        /** Key names as GLFW calls the keys, after their place on a US keyboard, whatever the layout. */
        private val KEY_NAMES: Map<String, Int> = buildMap {
            for (c in 'A'..'Z') put(c.lowercaseChar().toString(), GLFW_KEY_A + (c - 'A'))
            for (d in 0..9) {
                put(d.toString(), GLFW_KEY_0 + d)
                put("kp_$d", GLFW_KEY_KP_0 + d)
            }
            for (n in 1..25) put("f$n", GLFW_KEY_F1 + n - 1)
            putAll(mapOf(
                "space" to GLFW_KEY_SPACE, "apostrophe" to GLFW_KEY_APOSTROPHE, "comma" to GLFW_KEY_COMMA,
                "minus" to GLFW_KEY_MINUS, "period" to GLFW_KEY_PERIOD, "slash" to GLFW_KEY_SLASH,
                "semicolon" to GLFW_KEY_SEMICOLON, "equal" to GLFW_KEY_EQUAL, "leftbracket" to GLFW_KEY_LEFT_BRACKET,
                "backslash" to GLFW_KEY_BACKSLASH, "rightbracket" to GLFW_KEY_RIGHT_BRACKET, "graveaccent" to GLFW_KEY_GRAVE_ACCENT,
                "escape" to GLFW_KEY_ESCAPE, "enter" to GLFW_KEY_ENTER, "tab" to GLFW_KEY_TAB, "backspace" to GLFW_KEY_BACKSPACE,
                "insert" to GLFW_KEY_INSERT, "delete" to GLFW_KEY_DELETE, "right" to GLFW_KEY_RIGHT, "left" to GLFW_KEY_LEFT,
                "down" to GLFW_KEY_DOWN, "up" to GLFW_KEY_UP, "pageup" to GLFW_KEY_PAGE_UP, "pagedown" to GLFW_KEY_PAGE_DOWN,
                "home" to GLFW_KEY_HOME, "end" to GLFW_KEY_END, "capslock" to GLFW_KEY_CAPS_LOCK,
                "scrolllock" to GLFW_KEY_SCROLL_LOCK, "numlock" to GLFW_KEY_NUM_LOCK, "printscreen" to GLFW_KEY_PRINT_SCREEN,
                "pause" to GLFW_KEY_PAUSE, "menu" to GLFW_KEY_MENU,
                "kp_decimal" to GLFW_KEY_KP_DECIMAL, "kp_divide" to GLFW_KEY_KP_DIVIDE, "kp_multiply" to GLFW_KEY_KP_MULTIPLY,
                "kp_subtract" to GLFW_KEY_KP_SUBTRACT, "kp_add" to GLFW_KEY_KP_ADD, "kp_enter" to GLFW_KEY_KP_ENTER,
                "kp_equal" to GLFW_KEY_KP_EQUAL,
            ))
        }

        /** controls.properties with every action at its default keys, as the first start or deploy writes it. */
        fun defaultText(): String = buildString {
            append("# DiPlayDesktop controls, read at start. Delete this file to get the defaults back.\n\n")
            append("# action=keys, separated by commas; leave it empty to turn the action off.\n")
            append("# Keys: 'A'-'Z', '0'-'9', 'F1'-'F25', 'Left', 'Right', 'Up', 'Down', 'Enter', 'Space', 'Tab', 'Escape',\n")
            append("# 'Backspace', 'Insert', 'Delete', 'Home', 'End', 'PageUp', 'PageDown', 'KP_0'-'KP_9', 'KP_Enter',\n")
            append("# 'KP_Add', 'KP_Subtract', 'Minus', 'Equal', 'Comma', 'Period', 'Slash', ... named after their place on\n")
            append("# a US keyboard, so letters work in any layout. A raw GLFW key code is written '#290'.\n")
            append("# Modifiers: 'Shift+', 'Ctrl+', 'Alt+' (for example 'Ctrl+Shift+S').\n")
            append("\n")
            for (action in ControlAction.entries) {
                append("# ${action.description}\n")
                append("${action.key}=${action.defaults}\n")
            }
            append("\n")
            append("# A held key-bound knob turn steps on every N-th key repeat.\n")
            append("$REPEAT=3\n")
            append("# A held AltScreen zoom key steps on every N-th key repeat; '0' steps once a press.\n")
            append("$ZOOM_REPEAT=10\n")
            append("\n")
            append("# Mouse wheel: 'knob' turns the knob (scrolling down is forward), 'none' ignores the wheel.\n")
            append("$WHEEL=knob\n")
            append("# 'true' makes scrolling up forward, 'false' keeps down forward.\n")
            append("$INVERT=false\n")
            append("# Wheel notches per knob step.\n")
            append("$NOTCHES=1\n")
        }
    }
}
