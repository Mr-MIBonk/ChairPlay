package io.github.numberonebot.chairplay.input

import org.junit.Assert.*
import org.junit.Test

class HostTextHidTest {
    @Test fun `keyboard and Unicode reports have independent IDs and correct input sizes`() {
        val bytes = HostTextHid.descriptor(KeyboardHid.descriptor())
        val bits = mutableMapOf<Int, Int>()
        var offset = 0
        var id = 0
        var size = 0
        var count = 0
        while (offset < bytes.size) {
            val prefix = bytes[offset++].toInt() and 255
            var value = 0
            repeat((prefix and 3).let { if (it == 3) 4 else it }) { index ->
                value = value or ((bytes[offset++].toInt() and 255) shl (index * 8))
            }
            when (prefix and 0xfc) {
                0x74 -> size = value
                0x84 -> id = value
                0x94 -> count = value
                0x80 -> bits[id] = (bits[id] ?: 0) + size * count
            }
        }
        assertEquals(mapOf(1 to 64, 2 to 112), bits)
    }

    @Test fun `host Unicode is independent of physical key and iPhone layout`() {
        for (text in listOf("a", "Я", "ї", "Ω", "😀")) {
            val packet = HostTextHid.report(text.codePointAt(0))!!
            assertEquals(2, packet[0].toInt())
            assertEquals(text, String(packet, 1, packet[6].toInt(), Charsets.UTF_8))
            assertEquals(15, packet.size)
        }
        assertNull(HostTextHid.report(8))
        assertNull(HostTextHid.report(0xd800))
    }
}
