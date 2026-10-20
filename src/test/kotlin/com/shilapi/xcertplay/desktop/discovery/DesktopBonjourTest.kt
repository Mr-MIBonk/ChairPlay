package com.shilapi.xcertplay.desktop.discovery

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import javax.jmdns.JmDNS

class DesktopBonjourTest {
    private val config = AirPlayConfig(
        deviceName = "xcertplay",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        model = "LIVI",
    )
    private val identity = AirPlayIdentity(
        privateKey = ByteArray(32),
        publicKey = byteArrayOf(0x01, 0x23, 0xab.toByte()),
        pairingId = "pairing-1",
    )

    // Same inputs and expectations as upstream CarPlayBonjourTest, so the copy stays pinned to it.
    @Test
    fun `txt records match upstream`() {
        assertEquals(
            linkedMapOf(
                "deviceid" to "02:00:00:00:00:02",
                "features" to "0x5653aee2,0x61",
                "flags" to "0x4",
                "model" to "LIVI",
                "srcvers" to "366.0",
                "protovers" to "1.1",
                "pi" to "pairing-1",
                "pk" to "0123ab",
            ),
            CarPlayTxt.airPlayTxtRecords(config, identity),
        )
        for (disabled in listOf(false, true)) {
            val receiver = config.copy(disableAudioOutput = disabled)
            val parts = CarPlayTxt.airPlayTxtRecords(receiver, identity).getValue("features")
                .split(',').map { it.removePrefix("0x").toLong(16) }
            assertEquals(AirPlayInfoPlist.build(receiver)["features"], parts[0] or (parts.getOrElse(1) { 0L } shl 32))
        }
        assertEquals("0xffffffff", CarPlayTxt.featuresTxt(0xffffffffL))
    }

    // JmDNS answers a PTR listing on loopback; getServiceInfo alone does not, as it only queries SRV/TXT.
    @Test
    fun `publishes on loopback while port 5353 is shared`() {
        val publishConfig = config.copy(port = 47123)
        val loopback = InetAddress.getLoopbackAddress()
        MulticastSocket(null).use { holder ->
            holder.reuseAddress = true
            holder.bind(InetSocketAddress(5353))
            DesktopBonjour(publishConfig, identity, listOf(loopback)).use { bonjour ->
                bonjour.start()
                val found = JmDNS.create(loopback, "probe").use { probe ->
                    probe.list("${CarPlayTxt.AIRPLAY_SERVICE_TYPE}.local.", 5000)
                        .firstOrNull { it.name == publishConfig.deviceName }
                }
                assertNotNull("service not resolved on $loopback", found)
                assertEquals(publishConfig.port, found!!.port)
                assertEquals("0x4", found.getPropertyString("flags"))
            }
        }
    }
}
