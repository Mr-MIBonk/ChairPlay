package io.github.numberonebot.chairplay.input

import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.glfw.GLFW.*

class CharacterInputTest {
    private val phone = Any()
    private val params = mapOf("uuid" to "2a2a2a2e", "hidInputMode" to 1L)

    @Test fun `only a character mode for the declared device enables typing`() {
        val input = CharacterInput(true)
        assertFalse(input.typing(phone))
        assertTrue(input.command(phone, "hidSetInputMode", params))
        assertTrue(input.typing(phone))
        assertFalse(input.typing(Any()))
        assertNotNull(input.report(phone, 'F'.code))
        input.command(phone, "hidSetInputMode", params + ("hidInputMode" to 0L))
        assertFalse(input.typing(phone))
        assertNull(input.report(phone, 'F'.code))
    }

    @Test fun `unknown devices and modes cannot capture keys`() {
        for (change in listOf(params + ("uuid" to "2a2a2a2b"), params + ("hidInputMode" to 99L), params + ("hidInputMode" to 1.5), params - "hidInputMode")) {
            val input = CharacterInput(true)
            assertFalse(input.command(phone, "hidSetInputMode", change))
            assertFalse(input.typing(phone))
        }
        val disabled = CharacterInput(false)
        assertFalse(disabled.command(phone, "hidSetInputMode", params))
        assertFalse(disabled.typing(phone))
    }

    @Test fun `session end clears typing without clearing another session`() {
        val input = CharacterInput(true)
        input.command(phone, "hidSetInputMode", params)
        input.ended(Any())
        assertTrue(input.typing(phone))
        input.ended(phone)
        assertFalse(input.typing(phone))
    }

    @Test fun `text mode captures letters but leaves modified shortcuts and navigation available`() {
        val input = CharacterInput(true)
        input.command(phone, "hidSetInputMode", params)
        assertTrue(input.capturesKey(phone, GLFW_KEY_F, 0))
        assertTrue(input.capturesKey(phone, GLFW_KEY_S, GLFW_MOD_SHIFT))
        assertFalse(input.capturesKey(phone, GLFW_KEY_R, GLFW_MOD_CONTROL))
        assertFalse(input.capturesKey(phone, GLFW_KEY_F10, 0))
        assertFalse(input.capturesKey(phone, GLFW_KEY_ENTER, 0))
        assertFalse(input.capturesKey(phone, GLFW_KEY_LEFT, 0))
    }

    @Test fun `UTF8 reports preserve layout characters and reject invalid code points`() {
        val input = CharacterInput(true)
        input.command(phone, "hidSetInputMode", params)
        for (character in listOf("F", "я", "ї", "Ω", "😀")) {
            val report = input.report(phone, character.codePointAt(0))!!
            val length = report[10].toInt()
            assertEquals(character, String(report, 5, length, Charsets.UTF_8))
            assertEquals(19, report.size)
            assertEquals(1, report[9].toInt())
            assertEquals(100, report[11].toInt())
            assertEquals(0, report[17].toInt())
        }
        for (invalid in listOf(-1, 0xd800, 0x110000, 0, 8)) assertNull(input.report(phone, invalid))
        input.command(phone, "hidSetInputMode", params + ("hidInputMode" to 4L))
        assertNotNull(input.report(phone, '+'.code))
        assertNull(input.report(phone, 'F'.code))
    }

    @Test fun `mode query reports the current mode for the matching session and UUID`() {
        val input = CharacterInput(true)
        assertEquals(mapOf("hidInputMode" to 0), input.query(phone, "hidCopyInputMode", params))
        input.command(phone, "hidSetInputMode", params + ("hidInputMode" to 3L))
        assertEquals(mapOf("hidInputMode" to 3), input.query(phone, "hidCopyInputMode", params))
        assertTrue(input.typing(phone))
        assertNull(input.query(phone, "hidCopyInputMode", mapOf("uuid" to "other")))
    }
}
