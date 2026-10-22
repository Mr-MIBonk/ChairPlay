package io.github.numberonebot.chairplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {
    @Test
    fun `writes objects, lists, strings, numbers, booleans and null`() {
        val text = Json.write(linkedMapOf("a" to listOf(1, 2.5, true, null), "b" to "x", "c" to emptyMap<String, Any>()))

        assertEquals("""{"a":[1,2.5,true,null],"b":"x","c":{}}""", text)
    }

    @Test
    fun `escapes what a string cannot hold as it is`() {
        assertEquals("\"q\\\" b\\\\ n\\n t\\t c\\u0001 ls\\u2028\"", Json.write("q\" b\\ n\n t\t c\u0001 ls "))
    }

    @Test
    fun `reads what it writes`() {
        val value = linkedMapOf("wifi.ssid" to "Гараж \"5G\"", "list" to listOf("a", true, null), "n" to 3L)

        assertEquals(value, Json.parse(Json.write(value)))
    }

    @Test
    fun `reads escapes, numbers and whitespace`() {
        val parsed = Json.parse(""" { "s" : "aé\n\/" , "i" : -12 , "d" : 1.5e2 , "f" : false } """)

        assertEquals(mapOf("s" to "aé\n/", "i" to -12L, "d" to 150.0, "f" to false), parsed)
    }

    @Test
    fun `refuses broken input`() {
        for (text in listOf("", "{", """{"a":}""", """{"a":1,}""", "[1 2]", "\"unterminated", "{} extra", "nul")) {
            assertThrows(text, IllegalArgumentException::class.java) { Json.parse(text) }
        }
    }
}
