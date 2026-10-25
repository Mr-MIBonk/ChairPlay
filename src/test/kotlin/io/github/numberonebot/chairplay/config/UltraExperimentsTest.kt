package io.github.numberonebot.chairplay.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UltraExperimentsTest {
    @Test
    fun `the receiver tells DiPlay's version unless the environment sets one`() {
        assertEquals("950.7.1", sourceVersion(emptyMap()))
        assertEquals("320.17", sourceVersion(mapOf(SOURCE_VERSION_VARIABLE to "320.17")))
        assertEquals("320.17", sourceVersion(mapOf(SOURCE_VERSION_VARIABLE to " 320.17 ")))
        assertEquals("950.7.1", sourceVersion(mapOf(SOURCE_VERSION_VARIABLE to " ")))
    }

    // The version goes into the Bonjour TXT record and an HTTP header line.
    @Test
    fun `a version that is not dotted numbers is refused`() {
        for (bad in listOf("320.17\r\nX: 1", "abc", "320.", ".17", "320..17")) {
            assertThrows(IllegalArgumentException::class.java) { sourceVersion(mapOf(SOURCE_VERSION_VARIABLE to bad)) }
        }
    }

    @Test
    fun `the Bluetooth link declares route guidance unless the environment turns it off`() {
        assertEquals(true, bootstrapRouteGuidance(emptyMap()))
        assertEquals(false, bootstrapRouteGuidance(mapOf(BOOTSTRAP_ROUTE_GUIDANCE_VARIABLE to "0")))
        assertEquals(true, bootstrapRouteGuidance(mapOf(BOOTSTRAP_ROUTE_GUIDANCE_VARIABLE to "1")))
        assertThrows(IllegalArgumentException::class.java) { bootstrapRouteGuidance(mapOf(BOOTSTRAP_ROUTE_GUIDANCE_VARIABLE to "off")) }
    }

    @Test
    fun `the identification declares the CarPlay start messages unless the environment turns them off`() {
        assertEquals(true, carPlayAvailability(emptyMap()))
        assertEquals(false, carPlayAvailability(mapOf(CARPLAY_AVAILABILITY_VARIABLE to "0")))
        assertThrows(IllegalArgumentException::class.java) { carPlayAvailability(mapOf(CARPLAY_AVAILABILITY_VARIABLE to "no")) }
    }
}
