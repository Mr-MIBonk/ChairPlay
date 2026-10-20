package com.shilapi.xcertplay.desktop.discovery

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist

/**
 * AirPlay TXT records, copied unchanged from `CarPlayBonjourProtocol` in
 * shared/src/main/java/com/shilapi/xcertplay/network/CarPlayBonjour.kt (upstream commit 6d3b8a8).
 * The upstream file imports NsdManager and WifiManager and cannot be compiled on the JVM.
 */
object CarPlayTxt {
    const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp"

    internal fun featuresTxt(features: Long): String {
        val low = "0x${(features and 0xffffffffL).toString(16)}"
        val high = features ushr 32
        return if (high == 0L) low else "$low,0x${high.toString(16)}"
    }

    fun airPlayTxtRecords(
        config: AirPlayConfig,
        identity: AirPlayIdentity,
    ): Map<String, String> = linkedMapOf(
        "deviceid" to config.deviceId,
        "features" to featuresTxt(AirPlayInfoPlist.features(config)),
        "flags" to "0x4",
        "model" to config.model,
        "srcvers" to config.sourceVersion,
        "protovers" to "1.1",
        "pi" to identity.pairingId,
        "pk" to identity.publicKeyHex,
    )
}
