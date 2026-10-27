package io.github.numberonebot.chairplay.input

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import io.github.numberonebot.chairplay.config.configWith
import org.junit.Assert.*
import org.junit.Test

class HandwritingHidTest {
    @Test fun `character delivery returns the write result`() {
        assertFalse(HandwritingHid.sendCharacter(HandwritingHid.report('d'.code)!!) { false })
    }

    @Test fun `each character sends only its candidate report`() {
        val sent = mutableListOf<ByteArray>()
        val write: (ByteArray) -> Boolean = { sent += it.copyOf(); true }
        val character = HandwritingHid.report('d'.code)!!
        assertTrue(HandwritingHid.sendCharacter(character, write = write))
        assertTrue(HandwritingHid.sendCharacter(character, write = write))
        assertEquals(2, sent.size)
        sent.forEach { assertArrayEquals(character, it) }
    }

    @Test fun `character input adds a secondary device without replacing existing inputs`() {
        val info = AirPlayInfoPlist.build(configWith("keyboard.text.input=false").airPlay).toMutableMap()
        val before = info.toMap()
        HandwritingHid.advertise(info, experimental = false)
        assertEquals(before, info)
        val original = info["hidDevices"] as List<*>
        HandwritingHid.advertise(info, experimental = true)
        val devices = info["hidDevices"] as List<*>
        assertEquals(original, devices.take(original.size))
        val added = devices.last() as Map<*, *>
        assertEquals(HandwritingHid.UUID, added["uuid"])
        assertEquals(AirPlayInfoPlist.MAIN_UUID, added["displayUUID"])
        assertArrayEquals(HandwritingHid.descriptor(), added["hidDescriptor"] as ByteArray)
        val main = (info["displays"] as List<*>).first() as Map<*, *>
        assertTrue((main["features"] as Number).toLong() and 0x10L != 0L)
        val oldMain = (before["displays"] as List<*>).first() as Map<*, *>
        assertEquals(oldMain["primaryInputDevice"], main["primaryInputDevice"])
    }

    @Test fun `HID descriptor and encoded report agree on input size`() {
        val descriptor = HandwritingHid.descriptor()
        var cursor = 0
        var reportSize = 0
        var count = 0
        var bits = 0
        while (cursor < descriptor.size) {
            val prefix = descriptor[cursor++].toInt() and 255
            val bytes = (prefix and 3).let { if (it == 3) 4 else it }
            var value = 0L
            repeat(bytes) { value = value or ((descriptor[cursor++].toLong() and 255) shl (8 * it)) }
            when (prefix and 0xfc) {
                0x74 -> reportSize = value.toInt()
                0x94 -> count = value.toInt()
                0x80 -> bits += reportSize * count
            }
        }
        assertEquals(HandwritingHid.report('A'.code)!!.size * 8, bits)
    }
}
