package io.github.numberonebot.chairplay.input

import org.junit.Assert.*
import org.junit.Test

class SeparateCharacterReportsTest {
    @Test fun `clear probe sends candidate then pause then empty candidate for every character`() {
        val packets = mutableListOf<ByteArray>()
        val events = mutableListOf<String>()
        for (letter in "abb") {
            val candidate = HandwritingHid.report(letter.code, separate = true)!!
            assertTrue(HandwritingHid.sendCharacter(candidate, clear = true, pause = { events += "pause" }) {
                packets += it.copyOf()
                events += "write"
                true
            })
        }
        assertEquals(List(3) { listOf("write", "pause", "write") }.flatten(), events)
        assertEquals(6, packets.size)
        packets.chunked(2).forEachIndexed { index, (candidate, cleared) ->
            assertArrayEquals(HandwritingHid.report("abb"[index].code, separate = true), candidate)
            assertEquals(2, cleared[0].toInt())
            assertEquals(15, cleared.size)
            assertTrue(cleared.sliceArray(1..4).all { it == 0.toByte() })
            assertEquals(1, cleared[5].toInt())
            assertEquals(0, cleared[6].toInt())
            assertEquals(0, cleared[7].toInt())
            assertTrue(cleared.sliceArray(8..11).all { it == 0.toByte() })
            assertEquals(1, cleared[12].toInt())
            assertEquals(0, cleared[13].toInt())
            assertEquals(0, cleared[14].toInt())
        }
    }

    @Test fun `clear failure is returned and candidate failure does not send a clear`() {
        val candidate = HandwritingHid.report('a'.code, separate = true)!!
        var writes = 0
        assertFalse(HandwritingHid.sendCharacter(candidate, clear = true, pause = {}) { ++writes != 2 })
        assertEquals(2, writes)
        writes = 0
        assertFalse(HandwritingHid.sendCharacter(candidate, clear = true, pause = { fail("No candidate was delivered") }) {
            ++writes
            false
        })
        assertEquals(1, writes)
    }

    private data class Report(var bits: Int = 0, val usages: MutableSet<Int> = mutableSetOf())

    /** Parse HID short items independently to identify what each input report can generate. */
    private fun inputs(descriptor: ByteArray): Map<Int, Report> {
        val reports = mutableMapOf<Int, Report>()
        val usages = mutableSetOf<Int>()
        var cursor = 0
        var page = 0
        var id = 0
        var size = 0
        var count = 0
        while (cursor < descriptor.size) {
            val prefix = descriptor[cursor++].toInt() and 255
            val bytes = (prefix and 3).let { if (it == 3) 4 else it }
            var value = 0
            repeat(bytes) { value = value or ((descriptor[cursor++].toInt() and 255) shl (8 * it)) }
            when (prefix and 0xfc) {
                0x04 -> page = value
                0x08 -> usages += (page shl 16) or value
                0x74 -> size = value
                0x84 -> id = value
                0x94 -> count = value
                0x80 -> reports.getOrPut(id) { Report() }.apply { bits += size * count; this.usages += usages }
            }
            if (prefix and 0x0c == 0) usages.clear() // Local items expire after every Main item.
        }
        return reports
    }

    @Test fun `text report contains candidates without digitizer contact or coordinates`() {
        val reports = inputs(HandwritingHid.descriptor(separate = true))
        assertEquals(setOf(1, 2), reports.keys)
        assertEquals(40, reports.getValue(1).bits)
        assertTrue(reports.getValue(1).usages.containsAll(setOf(0x0d0033, 0x010030, 0x010031)))
        val text = reports.getValue(2)
        assertEquals(112, text.bits)
        assertEquals(setOf(0x0d0063, 0x0d0065, 0x0d0062, 0x0d0061), text.usages)
    }

    @Test fun `separate character packet matches declared report and preserves UTF8 candidates`() {
        val reports = inputs(HandwritingHid.descriptor(separate = true))
        for (character in listOf("d", "я", "Ω", "😀")) {
            val packet = HandwritingHid.report(character.codePointAt(0), separate = true)!!
            assertEquals(2, packet[0].toInt())
            assertEquals(reports.getValue(2).bits, (packet.size - 1) * 8)
            assertEquals(character, String(packet, 1, packet[6].toInt(), Charsets.UTF_8))
            assertEquals(1, packet[5].toInt())
            assertEquals(100, packet[7].toInt())
            assertEquals(0, packet[13].toInt())
        }
    }
}
