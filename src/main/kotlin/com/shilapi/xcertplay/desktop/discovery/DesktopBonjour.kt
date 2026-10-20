package com.shilapi.xcertplay.desktop.discovery

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.io.Closeable
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Publishes the receiver's `_airplay._tcp` service, one interface-bound JmDNS per address as in
 * upstream's interface-mDNS mode (CarPlayBonjour.start): a JmDNS instance joins only its own
 * address family's multicast group.
 */
class DesktopBonjour(
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val addresses: List<InetAddress>,
) : Closeable {
    private val instances = mutableListOf<JmDNS>()

    @Synchronized
    fun start() {
        for (address in addresses) {
            val dns = JmDNS.create(address, "carplay-${config.deviceId.replace(":", "")}")
            instances.add(dns)
            dns.registerService(ServiceInfo.create(
                "${CarPlayTxt.AIRPLAY_SERVICE_TYPE}.local.", config.deviceName, config.port,
                0, 0, CarPlayTxt.airPlayTxtRecords(config, identity),
            ))
        }
    }

    @Synchronized
    override fun close() {
        for (dns in instances) {
            runCatching { dns.unregisterAllServices() }
            runCatching { dns.close() }
        }
        instances.clear()
    }
}
