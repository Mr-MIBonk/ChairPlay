package io.github.numberonebot.chairplay.input

import org.lwjgl.glfw.GLFW.*

/** Physical GLFW keys to USB Keyboard/Keypad usages. The iPhone chooses their text layout. */
internal object KeyboardKeys {
    fun report(key: Int, mods: Int): ByteArray? {
        var usage = when (key) {
            in GLFW_KEY_A..GLFW_KEY_Z -> 4 + key - GLFW_KEY_A
            in GLFW_KEY_1..GLFW_KEY_9 -> 0x1e + key - GLFW_KEY_1
            in GLFW_KEY_KP_1..GLFW_KEY_KP_9 -> 0x59 + key - GLFW_KEY_KP_1
            else -> usages[key] ?: return null
        }
        var modifiers = (if (mods and GLFW_MOD_SHIFT != 0) 2 else 0) or
            (if (mods and GLFW_MOD_CONTROL != 0) 1 else 0) or
            (if (mods and GLFW_MOD_ALT != 0) 4 else 0) or
            (if (mods and GLFW_MOD_SUPER != 0) 8 else 0)
        // Windows editing gestures translated to iPhone's Command/Option equivalents.
        if (mods and GLFW_MOD_CONTROL != 0 && mods and (GLFW_MOD_ALT or GLFW_MOD_SUPER) == 0) {
            if (key in listOf(GLFW_KEY_A, GLFW_KEY_C, GLFW_KEY_X, GLFW_KEY_V, GLFW_KEY_Z, GLFW_KEY_Y)) {
                modifiers = (modifiers and 1.inv()) or 8
                if (key == GLFW_KEY_Y) { usage = 0x1d; modifiers = modifiers or 2 }
            } else if (key in listOf(GLFW_KEY_LEFT, GLFW_KEY_RIGHT, GLFW_KEY_BACKSPACE, GLFW_KEY_DELETE)) {
                modifiers = (modifiers and 1.inv()) or 4
            }
        }
        if (key == GLFW_KEY_HOME || key == GLFW_KEY_END) {
            val wholeField = mods and GLFW_MOD_CONTROL != 0
            usage = if (key == GLFW_KEY_HOME) { if (wholeField) 0x52 else 0x50 }
                else { if (wholeField) 0x51 else 0x4f }
            modifiers = (modifiers and 1.inv()) or 8
        }
        return ByteArray(8).apply { this[0] = modifiers.toByte(); this[2] = usage.toByte() }
    }

    private val usages = mapOf(
        GLFW_KEY_0 to 0x27, GLFW_KEY_ENTER to 0x28, GLFW_KEY_ESCAPE to 0x29,
        GLFW_KEY_BACKSPACE to 0x2a, GLFW_KEY_TAB to 0x2b, GLFW_KEY_SPACE to 0x2c,
        GLFW_KEY_MINUS to 0x2d, GLFW_KEY_EQUAL to 0x2e, GLFW_KEY_LEFT_BRACKET to 0x2f,
        GLFW_KEY_RIGHT_BRACKET to 0x30, GLFW_KEY_BACKSLASH to 0x31, GLFW_KEY_SEMICOLON to 0x33,
        GLFW_KEY_APOSTROPHE to 0x34, GLFW_KEY_GRAVE_ACCENT to 0x35, GLFW_KEY_COMMA to 0x36,
        GLFW_KEY_PERIOD to 0x37, GLFW_KEY_SLASH to 0x38, GLFW_KEY_CAPS_LOCK to 0x39,
        GLFW_KEY_INSERT to 0x49, GLFW_KEY_HOME to 0x4a, GLFW_KEY_PAGE_UP to 0x4b,
        GLFW_KEY_DELETE to 0x4c, GLFW_KEY_END to 0x4d, GLFW_KEY_PAGE_DOWN to 0x4e,
        GLFW_KEY_RIGHT to 0x4f, GLFW_KEY_LEFT to 0x50, GLFW_KEY_DOWN to 0x51, GLFW_KEY_UP to 0x52,
        GLFW_KEY_NUM_LOCK to 0x53, GLFW_KEY_KP_DIVIDE to 0x54, GLFW_KEY_KP_MULTIPLY to 0x55,
        GLFW_KEY_KP_SUBTRACT to 0x56, GLFW_KEY_KP_ADD to 0x57, GLFW_KEY_KP_ENTER to 0x58,
        GLFW_KEY_KP_0 to 0x62, GLFW_KEY_KP_DECIMAL to 0x63, GLFW_KEY_WORLD_1 to 0x64,
    )
}
