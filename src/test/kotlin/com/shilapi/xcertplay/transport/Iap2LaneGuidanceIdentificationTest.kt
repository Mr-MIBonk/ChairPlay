package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChairPlay's identification asks the iPhone for lane guidance as LuKa's MHI2Q project does: LaneGuidanceInfoUpdate
 * (0x5204) among the messages it receives, and the RouteGuidanceDisplayComponent's lane limits, parameter 7
 * (MaxLaneGuidanceDescriptionLength) and 8 (MaxLaneGuidanceStorageCapacity). Without them iOS never sends lanes.
 */
class Iap2LaneGuidanceIdentificationTest {
    private val wired = Iap2IdentificationConfig(
        name = "ChairPlay",
        modelIdentifier = "ChairPlay",
        manufacturer = "ChairPlay",
        serialNumber = "test",
        firmwareVersion = "1",
        hardwareVersion = "1",
        carPlayUsbInterfaceNumber = 3,
    )
    private val wireless = wired.copy(wireless = Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "ChairPlay"))

    private fun parameters(bytes: ByteArray) = Iap2ParameterList.parse(bytes).asList()

    private fun u16(bytes: ByteArray, index: Int = 0) =
        ((bytes[index * 2].toInt() and 0xff) shl 8) or (bytes[index * 2 + 1].toInt() and 0xff)

    private fun u16Values(bytes: ByteArray) = List(bytes.size / 2) { u16(bytes, it) }

    @Test
    fun `the iPhone may send lane guidance`() {
        for (config in listOf(wired, wireless)) {
            val identification = parameters(Iap2IdentificationClient.identificationInformation(config).payload)
            assertTrue(0x5204 in u16Values(identification.single { it.id == 7 }.payload))
        }
    }

    @Test
    fun `the route guidance component gives the lane limits LuKa's project gives`() {
        for (config in listOf(wired, wireless)) {
            val identification = parameters(Iap2IdentificationClient.identificationInformation(config).payload)
            val component = parameters(identification.single { it.id == 30 }.payload)
            assertEquals(256, u16(component.single { it.id == 7 }.payload))
            assertEquals(6, u16(component.single { it.id == 8 }.payload))
        }
    }

    // Everything the component says about the display as LuKa's rgd_build_component_tlv does; only its identifier
    // stays DiPlay's 42, which StartRouteGuidanceUpdates names.
    @Test
    fun `the route guidance component is LuKa's in every limit`() {
        val identification = parameters(Iap2IdentificationClient.identificationInformation(wireless).payload)
        val component = parameters(identification.single { it.id == 30 }.payload).associate { it.id to it.payload }
        assertEquals(42, u16(component.getValue(0)))
        assertEquals("RouteGuidanceDisplayComponent", component.getValue(1).decodeToString().trimEnd('\u0000'))
        assertEquals(
            mapOf(2 to 256, 3 to 256, 4 to 256, 5 to 256, 6 to 6, 7 to 256, 8 to 6),
            (2..8).associateWith { u16(component.getValue(it)) },
        )
    }

    // From DiPlay's Iap2RouteGuidanceSubscriptionTest, which the build leaves out: the subscription names the
    // component the identification declares.
    @Test
    fun `StartRouteGuidanceUpdates asks for the declared component`() {
        val frame = Iap2ControlMessages.subscriptions().single { it.messageId == 0x5200 }
        assertArrayEquals(byteArrayOf(0, 6, 0, 0, 0, 42, 0, 4, 0, 1, 0, 4, 0, 2), frame.payload)
    }
}
