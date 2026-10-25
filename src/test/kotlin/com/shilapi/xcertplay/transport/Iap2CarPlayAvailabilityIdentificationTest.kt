package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * For the CarPlay Ultra experiments the identification may leave out CarPlayAvailability (0x4300) and
 * CarPlayStartSession (0x4301) on every link, as it does for an older iOS that rejects them: CarPlay then starts
 * through Bonjour once the iPhone joins the Wi-Fi.
 */
class Iap2CarPlayAvailabilityIdentificationTest {
    private val config = Iap2IdentificationConfig(
        name = "ChairPlay",
        modelIdentifier = "ChairPlay",
        manufacturer = "ChairPlay",
        serialNumber = "test",
        firmwareVersion = "1",
        hardwareVersion = "1",
        carPlayUsbInterfaceNumber = 3,
    )
    private val wireless = Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "ChairPlay")
    private val carPlayStart = setOf(0x4300, 0x4301)

    private fun messages(config: Iap2IdentificationConfig): Set<Int> =
        Iap2ParameterList.parse(Iap2IdentificationClient.identificationInformation(config).payload).asList()
            .filter { it.id == 6 || it.id == 7 }
            .flatMap { p -> List(p.payload.size / 2) { ((p.payload[it * 2].toInt() and 0xff) shl 8) or (p.payload[it * 2 + 1].toInt() and 0xff) } }
            .toSet()

    @Test
    fun `every link can leave CarPlayAvailability and CarPlayStartSession out`() {
        val without = config.copy(carPlayAvailabilityEnabled = false)
        for (role in Iap2WirelessLinkRole.entries) {
            val messages = messages(without.forWirelessLink(role, wireless))
            assertEquals(emptySet<Int>(), messages intersect carPlayStart)
            assertTrue(0x5703 in messages)
        }
    }

    @Test
    fun `both are declared by default`() {
        for (role in Iap2WirelessLinkRole.entries) {
            assertEquals(carPlayStart, messages(config.forWirelessLink(role, wireless)) intersect carPlayStart)
        }
    }
}
