package io.github.numberonebot.chairplay.input

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import io.github.numberonebot.chairplay.config.configWith
import org.junit.Assert.*
import org.junit.Test

class KeyboardHidTest {
    @Test fun `modifiers surround key transitions as independent reports`() {
        val sent = mutableListOf<ByteArray>()
        val report = byteArrayOf(8, 0, 0x50, 0, 0, 0, 0, 0)
        assertTrue(KeyboardHid.sendCharacter(report, write = { sent += it.copyOf(); true }))
        assertEquals(listOf(8, 8, 8, 0), sent.map { it[0].toInt() })
        assertEquals(listOf(0, 0x50, 0, 0), sent.map { it[2].toInt() })
    }

    @Test fun `all printable ASCII characters have distinct key and shift combinations`() {
        val reports = (32..126).map { code -> KeyboardHid.report(code) ?: error("Missing ASCII $code") }
        assertEquals(95, reports.map { it.toList() }.toSet().size)
        reports.forEach {
            assertEquals(8, it.size)
            assertTrue(it[0].toInt() in listOf(0, 2))
            assertEquals(0, it[1].toInt())
            assertTrue(it[2].toInt() in 4..56)
            assertTrue(it.drop(3).all { byte -> byte == 0.toByte() })
        }
        assertArrayEquals(byteArrayOf(0, 0, 4, 0, 0, 0, 0, 0), KeyboardHid.report('a'.code))
        assertArrayEquals(byteArrayOf(2, 0, 4, 0, 0, 0, 0, 0), KeyboardHid.report('A'.code))
        assertEquals(0x1e, KeyboardHid.report('1'.code)!![2].toInt())
        assertEquals(0x27, KeyboardHid.report('0'.code)!![2].toInt())
        assertNull(KeyboardHid.report('я'.code))
        assertNull(KeyboardHid.report(0x1f600))
        assertNull(KeyboardHid.report(-1))
    }

    @Test fun `repeated letters are separate press and release sequences`() {
        val packets = mutableListOf<ByteArray>()
        val write: (ByteArray) -> Boolean = { packets += it.copyOf(); true }
        repeat(2) { assertTrue(KeyboardHid.sendCharacter(KeyboardHid.report('a'.code)!!, write)) }
        assertEquals(4, packets.size)
        assertArrayEquals(packets[0], packets[2])
        assertArrayEquals(ByteArray(8), packets[1])
        assertArrayEquals(ByteArray(8), packets[3])
    }

    @Test fun `failed release does not report successful delivery`() {
        var writes = 0
        assertFalse(KeyboardHid.sendCharacter(ByteArray(8), write = { ++writes != 2 }))
        assertEquals(2, writes)
    }

    @Test fun `keyboard advertisement preserves the existing input devices`() {
        val info = AirPlayInfoPlist.build(configWith("keyboard.text.input=false").airPlay).toMutableMap()
        val devices = info["hidDevices"] as List<*>
        KeyboardHid.advertise(info, experimental = false)
        assertEquals(devices, info["hidDevices"])
        KeyboardHid.advertise(info, experimental = true)
        val after = info["hidDevices"] as List<*>
        assertEquals(devices, after.dropLast(1))
        val added = after.last() as Map<*, *>
        assertEquals(KeyboardHid.UUID, added["uuid"])
        assertEquals(AirPlayInfoPlist.MAIN_UUID, added["displayUUID"])
        assertArrayEquals(KeyboardHid.descriptor(), added["hidDescriptor"] as ByteArray)
    }

    @Test fun `keyboard report size matches the descriptor`() {
        val descriptor = KeyboardHid.descriptor()
        var offset = 0
        var size = 0
        var count = 0
        var bits = 0
        while (offset < descriptor.size) {
            val prefix = descriptor[offset++].toInt() and 255
            var value = 0
            repeat((prefix and 3).let { if (it == 3) 4 else it }) { index ->
                value = value or ((descriptor[offset++].toInt() and 255) shl (index * 8))
            }
            when (prefix and 0xfc) {
                0x74 -> size = value
                0x94 -> count = value
                0x80 -> bits += size * count
            }
        }
        assertEquals(64, bits)
    }
}
