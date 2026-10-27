package io.github.numberonebot.chairplay.input

import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.glfw.GLFW.*

class KeyboardKeysTest {
    @Test fun `editing and navigation keys have native HID usages`() {
        val keys = mapOf(
            GLFW_KEY_BACKSPACE to 0x2a, GLFW_KEY_DELETE to 0x4c, GLFW_KEY_ENTER to 0x28,
            GLFW_KEY_TAB to 0x2b, GLFW_KEY_ESCAPE to 0x29, GLFW_KEY_SPACE to 0x2c,
            GLFW_KEY_LEFT to 0x50, GLFW_KEY_RIGHT to 0x4f, GLFW_KEY_UP to 0x52, GLFW_KEY_DOWN to 0x51,
            GLFW_KEY_HOME to 0x50, GLFW_KEY_END to 0x4f, GLFW_KEY_PAGE_UP to 0x4b,
            GLFW_KEY_PAGE_DOWN to 0x4e, GLFW_KEY_INSERT to 0x49, GLFW_KEY_CAPS_LOCK to 0x39,
        )
        keys.forEach { (key, usage) -> assertEquals(usage, KeyboardKeys.report(key, 0)!![2].toInt()) }
    }

    @Test fun `letters digits punctuation and keypad do not depend on Windows character events`() {
        assertEquals(4, KeyboardKeys.report(GLFW_KEY_A, 0)!![2].toInt())
        assertEquals(29, KeyboardKeys.report(GLFW_KEY_Z, 0)!![2].toInt())
        assertEquals(0x1e, KeyboardKeys.report(GLFW_KEY_1, 0)!![2].toInt())
        assertEquals(0x27, KeyboardKeys.report(GLFW_KEY_0, 0)!![2].toInt())
        assertEquals(0x2d, KeyboardKeys.report(GLFW_KEY_MINUS, 0)!![2].toInt())
        assertEquals(0x62, KeyboardKeys.report(GLFW_KEY_KP_0, 0)!![2].toInt())
        assertEquals(0x59, KeyboardKeys.report(GLFW_KEY_KP_1, 0)!![2].toInt())
        assertEquals(0x58, KeyboardKeys.report(GLFW_KEY_KP_ENTER, 0)!![2].toInt())
        assertEquals(9, KeyboardKeys.report(GLFW_KEY_F, 0)!![2].toInt())
        assertNull(KeyboardKeys.report(GLFW_KEY_F10, 0))
    }

    @Test fun `shift selects text and control editing shortcuts use iPhone command equivalents`() {
        assertEquals(2, KeyboardKeys.report(GLFW_KEY_LEFT, GLFW_MOD_SHIFT)!![0].toInt())
        assertEquals(8, KeyboardKeys.report(GLFW_KEY_A, GLFW_MOD_CONTROL)!![0].toInt())
        assertEquals(8, KeyboardKeys.report(GLFW_KEY_C, GLFW_MOD_CONTROL)!![0].toInt())
        assertEquals(8, KeyboardKeys.report(GLFW_KEY_V, GLFW_MOD_CONTROL)!![0].toInt())
        assertEquals(4, KeyboardKeys.report(GLFW_KEY_BACKSPACE, GLFW_MOD_CONTROL)!![0].toInt())
        assertEquals(6, KeyboardKeys.report(GLFW_KEY_RIGHT, GLFW_MOD_CONTROL or GLFW_MOD_SHIFT)!![0].toInt())
        val redo = KeyboardKeys.report(GLFW_KEY_Y, GLFW_MOD_CONTROL)!!
        assertEquals(10, redo[0].toInt())
        assertEquals(0x1d, redo[2].toInt())
        assertEquals(1, KeyboardKeys.report(GLFW_KEY_SPACE, GLFW_MOD_CONTROL)!![0].toInt())
        assertEquals(8, KeyboardKeys.report(GLFW_KEY_HOME, 0)!![0].toInt())
        assertEquals(10, KeyboardKeys.report(GLFW_KEY_END, GLFW_MOD_SHIFT)!![0].toInt())
        assertEquals(0x52, KeyboardKeys.report(GLFW_KEY_HOME, GLFW_MOD_CONTROL)!![2].toInt())
    }
}
