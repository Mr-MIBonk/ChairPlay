package com.shilapi.xcertplay.desktop.network

import com.shilapi.xcertplay.network.HotspotAddressPolicy
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.wifiBandLabel
import com.shilapi.xcertplay.network.wifiChannelToFrequencyMhz
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.net.InetAddress
import java.net.NetworkInterface

data class SavedWifi(val ssid: String, val passphrase: String, val security: Iap2WirelessSecurity)

data class NicSnapshot(
    val displayName: String,
    val name: String,
    val addresses: List<InetAddress>,
    val up: Boolean,
)

/**
 * Describes the Wi-Fi network the PC is already on. WLAN API gives a channel number without a
 * band, so channels 1-14 are read as 2.4 GHz and 32-177 as 5 GHz; 6 GHz is not detected.
 */
fun hotspotInfoFor(
    connections: List<WlanConnection>,
    interfaces: List<NicSnapshot>,
    saved: SavedWifi,
): WirelessHotspotInfo {
    val connection = connections.firstOrNull() ?: error("Wi-Fi не подключён")
    check(connection.ssid == saved.ssid) {
        "PC подключён к Wi-Fi \"${connection.ssid}\", а сохранён \"${saved.ssid}\""
    }
    val matches = interfaces.filter { it.up && it.displayName == connection.interfaceDescription }
    check(matches.size == 1) {
        "Адаптер \"${connection.interfaceDescription}\" найден ${matches.size} раз(а) среди сетевых интерфейсов"
    }
    val nic = matches.single()
    val addresses = nic.addresses.filterNot { it.isLoopbackAddress }
    val hostAddress = HotspotAddressPolicy.select(addresses)
        ?: error("У адаптера \"${connection.interfaceDescription}\" нет подходящего адреса")
    val band = when (connection.channel) {
        in 1..14 -> 1
        in 32..177 -> 2
        else -> null
    }
    return WirelessHotspotInfo(
        ssid = saved.ssid,
        passphrase = saved.passphrase,
        security = saved.security,
        channel = connection.channel,
        frequencyMHz = wifiChannelToFrequencyMhz(connection.channel, band),
        bssid = null,
        interfaceName = nic.name,
        hostAddress = hostAddress,
        bandLabel = wifiBandLabel(band) ?: "unknown",
        backend = WirelessHotspotBackend.EXISTING_WIFI,
        hostAddresses = addresses,
        accessPointBssid = connection.bssid,
    )
}

/** Attaches to the Wi-Fi network the PC is on; it never changes the network. */
class SameLanNetwork(private val saved: SavedWifi) : WirelessHotspotManager {
    private var published: WirelessHotspotInfo? = null

    override fun start(timeoutMillis: Long): WirelessHotspotInfo =
        describe().also { published = it }

    override fun validateReady() {
        val before = checkNotNull(published) { "SameLanNetwork is not started" }
        val now = describe()
        check(now.hostAddresses == before.hostAddresses) {
            "Адрес Wi-Fi сменился: ${before.hostAddresses} -> ${now.hostAddresses}"
        }
    }

    override fun close() {}

    private fun describe(): WirelessHotspotInfo {
        val nics = NetworkInterface.getNetworkInterfaces().toList().map {
            NicSnapshot(it.displayName, it.name, it.inetAddresses.toList(), it.isUp)
        }
        return hotspotInfoFor(WlanApi.currentConnections(), nics, saved)
    }
}
