package io.github.numberonebot.chairplay

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessLinkRole
import com.shilapi.xcertplay.transport.forWirelessLink
import io.github.numberonebot.chairplay.bluetooth.WindowsBluetooth
import io.github.numberonebot.chairplay.bluetooth.selectIphone
import io.github.numberonebot.chairplay.media.AudioDevices
import io.github.numberonebot.chairplay.session.IAP2_IPHONE_UUID
import io.github.numberonebot.chairplay.session.desktopIdentification
import io.github.numberonebot.chairplay.session.redactTrace

// The command line's tools besides run: the paired devices and audio devices, and the Bluetooth go/no-go probe.

internal fun listDevices() {
    println("radio ${WindowsBluetooth.localRadioAddress()}")
    WindowsBluetooth.pairedDevices().forEach {
        println("${it.address}  class=0x%06X  ${it.name}".format(it.classOfDevice))
    }
    // The names audio.output and audio.input match against (Java Sound cuts them to 31 characters).
    println("audio outputs:")
    AudioDevices.outputs().forEach { println("  ${it.name}") }
    println("audio inputs:")
    AudioDevices.inputs().forEach { println("  ${it.name}") }
}

/**
 * The go/no-go check for wireless CarPlay on Windows: reach the iPhone's iAP2 service over RFCOMM
 * and complete identification and MFi authentication with DiPlay's own iAP2 code. Stops before
 * handing the iPhone any Wi-Fi endpoint.
 */
internal fun probeBluetooth(address: String?): Boolean {
    val radio = WindowsBluetooth.localRadioAddress()
    val iphone = selectIphone(WindowsBluetooth.pairedDevices(), address)
    log("radio $radio, target ${iphone.name} ${iphone.address}")

    val mfiDirectory = dataDirectory().resolve("offline-mfi").toFile()
    val mfi = LocalMfiAuthenticationClient.load(mfiDirectory) { log("local MFi identity signs ($it-byte signature)") }

    val socket = WindowsBluetooth.connectRfcomm(iphone.address, IAP2_IPHONE_UUID)
    log("RFCOMM connected to the iAP2 service")
    val stream = socket.toDuplexStream(::log)
    val session = Iap2Session.openWireless(stream, traceContext = "probe", onTrace = { log(redactTrace(it)) })
    try {
        val identification = desktopIdentification(radio).forWirelessLink(
            Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP,
            Iap2WirelessIdentification(radio, "ChairPlayProbe"),
        )
        // The same two steps, in the same order, that Iap2WirelessControlClient.run begins with.
        Iap2IdentificationClient(session).identify(identification, 30_000L, ::log)
        log("iAP2 identification accepted")
        Iap2MfiAuthenticationClient(mfi).run(session, 30_000L, ::log)
        log("iAP2 authentication accepted")
        log("GO: the iPhone accepted ChairPlay over Windows Bluetooth")
        return true
    } catch (error: Exception) {
        log("NO-GO: ${error.javaClass.simpleName}: ${error.message}")
        return false
    } finally {
        session.close()
    }
}
