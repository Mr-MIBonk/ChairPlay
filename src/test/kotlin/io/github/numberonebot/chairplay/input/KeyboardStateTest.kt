package io.github.numberonebot.chairplay.input

import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.glfw.GLFW.*

class KeyboardStateTest {
    @Test fun `shift stays held across real key down and up until Windows releases shift`() {
        val state = KeyboardState()
        assertTrue(state.accepts(GLFW_KEY_LEFT_SHIFT))
        assertEquals(listOf(2), state.event(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS, GLFW_MOD_SHIFT).map { it[0].toInt() })
        val down = state.event(GLFW_KEY_A, GLFW_PRESS, GLFW_MOD_SHIFT).single()
        assertEquals(2, down[0].toInt())
        assertEquals(4, down[2].toInt())
        val up = state.event(GLFW_KEY_A, GLFW_RELEASE, GLFW_MOD_SHIFT).single()
        assertEquals(2, up[0].toInt())
        assertEquals(0, up[2].toInt())
        assertArrayEquals(ByteArray(8), state.event(GLFW_KEY_LEFT_SHIFT, GLFW_RELEASE, GLFW_MOD_SHIFT).single())
    }

    @Test fun `caps lock is not released until the real key release arrives`() {
        val state = KeyboardState()
        val down = state.event(GLFW_KEY_CAPS_LOCK, GLFW_PRESS, 0).single()
        assertEquals(0x39, down[2].toInt())
        assertArrayEquals(ByteArray(8), state.event(GLFW_KEY_CAPS_LOCK, GLFW_RELEASE, 0).single())
    }

    @Test fun `command translation holds command until the letter is released`() {
        val state = KeyboardState()
        state.event(GLFW_KEY_LEFT_CONTROL, GLFW_PRESS, GLFW_MOD_CONTROL)
        val down = state.event(GLFW_KEY_A, GLFW_PRESS, GLFW_MOD_CONTROL)
        assertEquals(listOf(8, 8), down.map { it[0].toInt() })
        assertEquals(listOf(0, 4), down.map { it[2].toInt() })
        val up = state.event(GLFW_KEY_A, GLFW_RELEASE, GLFW_MOD_CONTROL)
        assertEquals(listOf(8, 1), up.map { it[0].toInt() })
        assertTrue(up.all { it.drop(2).all { byte -> byte == 0.toByte() } })
    }

    @Test fun `overlapping keys and host repeat preserve held modifiers`() {
        val state = KeyboardState()
        state.event(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS, GLFW_MOD_SHIFT)
        state.event(GLFW_KEY_A, GLFW_PRESS, GLFW_MOD_SHIFT)
        assertEquals(listOf(4, 5), state.event(GLFW_KEY_B, GLFW_PRESS, GLFW_MOD_SHIFT).last().drop(2).filter { it != 0.toByte() }.map { it.toInt() })
        val repeat = state.event(GLFW_KEY_B, GLFW_REPEAT, GLFW_MOD_SHIFT)
        assertEquals(2, repeat.size)
        assertTrue(repeat.all { it[0] == 2.toByte() })
        assertEquals(listOf(4), repeat[0].drop(2).filter { it != 0.toByte() }.map { it.toInt() })
    }

    @Test fun `focus or mode loss releases all keys before modifiers and clears state`() {
        val state = KeyboardState()
        state.event(GLFW_KEY_A, GLFW_PRESS, GLFW_MOD_SHIFT)
        val reset = state.release()
        assertEquals(listOf(2, 0), reset.map { it[0].toInt() })
        assertTrue(reset.all { it.drop(2).all { byte -> byte == 0.toByte() } })
        assertTrue(state.release().isEmpty())
        assertTrue(state.event(GLFW_KEY_A, GLFW_RELEASE, GLFW_MOD_SHIFT).isEmpty())
        assertTrue(state.event(GLFW_KEY_LEFT_SHIFT, GLFW_RELEASE, GLFW_MOD_SHIFT).isEmpty())
        assertFalse(state.accepts(GLFW_KEY_F10))
    }
}
