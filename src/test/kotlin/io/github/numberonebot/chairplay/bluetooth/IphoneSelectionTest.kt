package io.github.numberonebot.chairplay.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IphoneSelectionTest {
    private val phone = PairedDevice("iPhone", "AA:BB:CC:DD:EE:01", 0x0200)
    private val airPods = PairedDevice("AirPods", "AA:BB:CC:DD:EE:02", 0x0400)

    @Test
    fun `configured address wins`() {
        val chosen = selectIphone(listOf(phone, airPods), "aa:bb:cc:dd:ee:02")
        assertEquals(airPods, chosen)
    }

    @Test
    fun `single phone-class device chosen without config`() {
        assertEquals(phone, selectIphone(listOf(airPods, phone), null))
    }

    @Test
    fun `ambiguous phones throw listing candidates`() {
        val other = PairedDevice("Pixel", "AA:BB:CC:DD:EE:03", 0x020C)
        val message = failureOf { selectIphone(listOf(phone, other), null) }
        assertTrue(message, "iPhone" in message && "Pixel" in message)
    }

    @Test
    fun `configured address missing throws`() {
        val message = failureOf { selectIphone(listOf(phone), "11:22:33:44:55:66") }
        assertTrue(message, "11:22:33:44:55:66" in message)
    }

    private fun failureOf(block: () -> Unit): String {
        try {
            block()
        } catch (e: IllegalStateException) {
            return e.message.orEmpty()
        }
        fail("expected IllegalStateException")
        error("unreachable")
    }
}
