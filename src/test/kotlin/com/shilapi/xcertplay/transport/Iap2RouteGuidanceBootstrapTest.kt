package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * For the CarPlay Ultra experiments the Bluetooth link may leave route guidance out: its component (30) and its
 * messages. The Wi-Fi tunnel keeps them, as upstream's StartRouteGuidanceUpdates goes there.
 */
class Iap2RouteGuidanceBootstrapTest {
    private val config = Iap2IdentificationConfig(
        name = "ChairPlay",
        modelIdentifier = "ChairPlay",
        manufacturer = "ChairPlay",
        serialNumber = "test",
        firmwareVersion = "1",
        hardwareVersion = "1",
        carPlayUsbInterfaceNumber = 3,
        bootstrapRouteGuidance = false,
    )
    private val wireless = Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "ChairPlay")
    private val routeGuidanceMessages = setOf(0x5200, 0x5201, 0x5202, 0x5203, 0x5204)

    private fun parameters(config: Iap2IdentificationConfig) =
        Iap2ParameterList.parse(Iap2IdentificationClient.identificationInformation(config).payload).asList()

    private fun messages(config: Iap2IdentificationConfig): Set<Int> = parameters(config).filter { it.id == 6 || it.id == 7 }
        .flatMap { p -> List(p.payload.size / 2) { ((p.payload[it * 2].toInt() and 0xff) shl 8) or (p.payload[it * 2 + 1].toInt() and 0xff) } }
        .toSet()

    @Test
    fun `the Bluetooth link can leave route guidance out`() {
        val bluetooth = config.forWirelessLink(Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP, wireless)
        assertTrue(parameters(bluetooth).none { it.id == 30 })
        assertEquals(emptySet<Int>(), messages(bluetooth) intersect routeGuidanceMessages)
    }

    @Test
    fun `the tunnel keeps route guidance`() {
        val tunnel = config.forWirelessLink(Iap2WirelessLinkRole.RUNTIME_TUNNEL, wireless)
        assertTrue(parameters(tunnel).any { it.id == 30 })
        assertEquals(routeGuidanceMessages, messages(tunnel) intersect routeGuidanceMessages)
    }
}
