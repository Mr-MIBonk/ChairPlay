package com.shilapi.xcertplay.desktop.config

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.desktop.network.SavedWifi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.reader
import kotlin.io.path.writeText

/**
 * The receiver's settings and keys in [dir] (normally %APPDATA%\DiPlayDesktop): `settings.properties`
 * written by the user, `identity.properties` generated on first use, and one file per iPhone
 * pairing in `pairings/`, the desktop counterpart of upstream's AirPlayPersistence.
 */
class DesktopConfig(private val dir: Path) {
    private val settings = loadSettings()
    val identity: AirPlayIdentity = loadIdentity()
    val pairings: PairingStore = loadPairings()
    val iphoneAddress: String? = settings.getProperty("iphone.address")?.trim()?.ifEmpty { null }
    val wifi = SavedWifi(
        ssid = settings.getProperty("wifi.ssid")?.ifBlank { null } ?: error("Set wifi.ssid in ${dir.resolve(SETTINGS)}"),
        passphrase = settings.getProperty("wifi.passphrase").orEmpty(),
        security = Iap2WirelessSecurity.valueOf(settings.getProperty("wifi.security", "WPA_WPA2").trim()),
    )

    /** DiPlay's production values (CarPlayHostActivity.airPlayConfig); btMac is replaced by the radio's. */
    val airPlay = AirPlayConfig(
        deviceName = "DiPlay",
        deviceId = deviceId(identity),
        btMac = deviceId(identity),
        sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(
            widthPixels = evenSetting("display.width", 1280),
            heightPixels = evenSetting("display.height", 720),
            fps = 60,
        ),
        port = 7000,
        hevc = false,
        microphone = true,
        microphoneOpus = false,
        videoInCar = false,
        mainBufferedAudio = false,
    )

    fun mfiDirectory(): File = dir.resolve("offline-mfi").toFile()

    private fun loadSettings(): Properties {
        val file = dir.resolve(SETTINGS)
        if (!file.exists()) {
            Files.createDirectories(dir)
            file.writeText(
                "# Wi-Fi network shared by this PC and the iPhone.\nwifi.ssid=\nwifi.passphrase=\n" +
                    "# NONE, WEP, WPA_WPA2, WPA3_TRANSITION or WPA3_ONLY\nwifi.security=WPA_WPA2\n" +
                    "# Paired iPhone's Bluetooth address, needed only when several phones are paired.\niphone.address=\n" +
                    "display.width=1280\ndisplay.height=720\n",
            )
        }
        return Properties().apply { file.reader().use(::load) }
    }

    private fun evenSetting(key: String, default: Int): Int {
        val value = settings.getProperty(key)?.trim()?.toIntOrNull() ?: default
        require(value > 0 && value % 2 == 0) { "$key must be a positive even number, not $value" }
        return value
    }

    private fun loadIdentity(): AirPlayIdentity {
        val file = dir.resolve("identity.properties")
        if (file.exists()) {
            val saved = Properties().apply { file.reader().use(::load) }
            return AirPlayIdentity(
                HEX.parseHex(saved.getProperty("privateKey")),
                HEX.parseHex(saved.getProperty("publicKey")),
                saved.getProperty("pairingId"),
            )
        }
        return AirPlayIdentity.generate().also {
            file.writeText(
                "privateKey=${HEX.formatHex(it.privateKey)}\npublicKey=${HEX.formatHex(it.publicKey)}\n" +
                    "pairingId=${it.pairingId}\n",
            )
        }
    }

    /** File names are the identifier's hex, as identifiers may hold characters Windows forbids. */
    private fun loadPairings(): PairingStore {
        val pairingDir = Files.createDirectories(dir.resolve("pairings"))
        var loaded = false
        val store = PairingStore { identifier, key ->
            if (loaded) pairingDir.resolve(HEX.formatHex(identifier.toByteArray())).writeText(HEX.formatHex(key))
        }
        for (file in pairingDir.listDirectoryEntries()) {
            store.save(String(HEX.parseHex(file.name)), HEX.parseHex(file.readText().trim()))
        }
        loaded = true
        return store
    }

    companion object {
        private const val SETTINGS = "settings.properties"
        private val HEX = HexFormat.of()

        val DEFAULT_DIRECTORY: Path = Path.of(System.getenv("APPDATA"), "DiPlayDesktop")

        /** DiPlayBootstrap.deviceId: a locally administered MAC derived from the AirPlay public key. */
        fun deviceId(identity: AirPlayIdentity): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).copyOf(6)
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        }
    }
}
