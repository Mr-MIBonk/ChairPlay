package io.github.numberonebot.chairplay.network

import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SameLanNetworkTest {
    private val bssid = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)
    private val saved = SavedWifi("Home", "secret", Iap2WirelessSecurity.WPA_WPA2)

    private fun v4(text: String): InetAddress = InetAddress.getByName(text)

    private fun linkLocal(scope: Int): InetAddress =
        Inet6Address.getByAddress(null, ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 }, scope)

    private val wifiNic = NicSnapshot("Intel Wi-Fi 6E AX211", "wlan0", listOf(v4("192.168.1.20"), linkLocal(12)), true)
    private val ethernet = NicSnapshot("Realtek PCIe GbE", "eth0", listOf(v4("10.0.0.5")), true)
    private val hyperV = NicSnapshot("vEthernet (Default Switch)", "eth1", listOf(v4("172.20.0.1")), true)

    private fun connection(ssid: String = "Home", channel: Int = 36) =
        WlanConnection("Intel Wi-Fi 6E AX211", ssid, bssid, channel)

    @Test
    fun `picks only the connected wlan adapter`() {
        val info = hotspotInfoFor(listOf(connection()), listOf(wifiNic, ethernet, hyperV), saved)
        assertEquals(wifiNic.addresses, info.hostAddresses)
        assertEquals(v4("192.168.1.20"), info.hostAddress)
        assertEquals("wlan0", info.interfaceName)
        assertEquals("Home", info.ssid)
        assertEquals("secret", info.passphrase)
        assertEquals(Iap2WirelessSecurity.WPA_WPA2, info.security)
    }

    @Test
    fun `ssid mismatch throws`() {
        try {
            hotspotInfoFor(listOf(connection(ssid = "Home")), listOf(wifiNic), SavedWifi("Office", "x", Iap2WirelessSecurity.WPA_WPA2))
            fail("expected an exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Home") && e.message!!.contains("Office"))
        }
    }

    @Test
    fun `no wlan connection throws`() {
        assertThrows(IllegalStateException::class.java) { hotspotInfoFor(emptyList(), listOf(wifiNic), saved) }
    }

    @Test
    fun `channel and bssid carried`() {
        val info = hotspotInfoFor(listOf(connection(channel = 36)), listOf(wifiNic), saved)
        assertEquals(36, info.channel)
        assertEquals(5180, info.frequencyMHz)
        assertEquals(WirelessHotspotBackend.EXISTING_WIFI, info.backend)
        assertArrayEquals(bssid, info.accessPointBssid)
    }

    @Test
    fun `two point four gigahertz channel resolves and unknown channel does not`() {
        assertEquals(2437, hotspotInfoFor(listOf(connection(channel = 6)), listOf(wifiNic), saved).frequencyMHz)
        assertNull(hotspotInfoFor(listOf(connection(channel = 20)), listOf(wifiNic), saved).frequencyMHz)
    }

    @Test
    fun `loopback and down interfaces ignored`() {
        val loopback = NicSnapshot("Software Loopback Interface 1", "lo", listOf(v4("127.0.0.1")), true)
        val down = NicSnapshot("Intel Wi-Fi 6E AX211", "wlan1", listOf(v4("192.168.9.9")), false)
        val info = hotspotInfoFor(listOf(connection()), listOf(wifiNic, loopback, down), saved)
        assertEquals(wifiNic.addresses, info.hostAddresses)
        assertEquals("wlan0", info.interfaceName)
        val withLoopbackAddress = wifiNic.copy(addresses = wifiNic.addresses + v4("127.0.0.1"))
        assertEquals(wifiNic.addresses, hotspotInfoFor(listOf(connection()), listOf(withLoopbackAddress), saved).hostAddresses)
    }

    @Test
    fun `ambiguous adapter match throws`() {
        val twin = wifiNic.copy(name = "wlan9")
        try {
            hotspotInfoFor(listOf(connection()), listOf(wifiNic, twin), saved)
            fail("expected an exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Intel Wi-Fi 6E AX211"))
        }
    }

    @Test
    fun `adapter without usable address throws`() {
        try {
            hotspotInfoFor(listOf(connection()), listOf(wifiNic.copy(addresses = emptyList())), saved)
            fail("expected an exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Intel Wi-Fi 6E AX211"))
        }
    }
}
