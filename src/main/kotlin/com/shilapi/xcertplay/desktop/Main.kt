package com.shilapi.xcertplay.desktop

import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.desktop.bluetooth.WindowsBluetooth
import com.shilapi.xcertplay.desktop.bluetooth.selectIphone
import com.shilapi.xcertplay.desktop.config.DesktopConfig
import com.shilapi.xcertplay.desktop.media.LoggingMediaSink
import com.shilapi.xcertplay.desktop.session.FIREWALL_RULE
import com.shilapi.xcertplay.desktop.session.IAP2_IPHONE_UUID
import com.shilapi.xcertplay.desktop.session.WirelessBootstrap
import com.shilapi.xcertplay.desktop.session.desktopIdentification
import com.shilapi.xcertplay.desktop.session.firewallHint
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessLinkRole
import com.shilapi.xcertplay.transport.forWirelessLink
import java.time.LocalTime
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "devices" -> listDevices()
        "probe-bt" -> exitProcess(if (probeBluetooth(args.getOrNull(1))) 0 else 1)
        "run" -> runCarPlay()
        else -> println("usage: devices | probe-bt [iphone-address] | run")
    }
}

private fun log(message: String) = println("${LocalTime.now()} $message")

private fun listDevices() {
    println("radio ${WindowsBluetooth.localRadioAddress()}")
    WindowsBluetooth.pairedDevices().forEach {
        println("${it.address}  class=0x%06X  ${it.name}".format(it.classOfDevice))
    }
}

/**
 * The go/no-go check for wireless CarPlay on Windows: reach the iPhone's iAP2 service over RFCOMM
 * and complete identification and MFi authentication with DiPlay's own iAP2 code. Stops before
 * handing the iPhone any Wi-Fi endpoint.
 */
private fun probeBluetooth(address: String?): Boolean {
    val radio = WindowsBluetooth.localRadioAddress()
    val iphone = selectIphone(WindowsBluetooth.pairedDevices(), address)
    log("radio $radio, target ${iphone.name} ${iphone.address}")

    val mfiDirectory = DesktopConfig.DEFAULT_DIRECTORY.resolve("offline-mfi").toFile()
    val mfi = LocalMfiAuthenticationClient.load(mfiDirectory) { log("local MFi identity signs ($it-byte signature)") }

    val socket = WindowsBluetooth.connectRfcomm(iphone.address, IAP2_IPHONE_UUID)
    log("RFCOMM connected to the iAP2 service")
    val stream = socket.toDuplexStream(::log)
    val session = Iap2Session.openWireless(stream, traceContext = "probe", onTrace = ::log)
    try {
        val identification = desktopIdentification(radio).forWirelessLink(
            Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP,
            Iap2WirelessIdentification(radio, "DiPlayProbe"),
        )
        // The same two steps, in the same order, that Iap2WirelessControlClient.run begins with.
        Iap2IdentificationClient(session).identify(identification, 30_000L, ::log)
        log("iAP2 identification accepted")
        Iap2MfiAuthenticationClient(mfi).run(session, 30_000L, ::log)
        log("iAP2 authentication accepted")
        log("GO: the iPhone accepted DiPlay over Windows Bluetooth")
        return true
    } catch (error: Exception) {
        log("NO-GO: ${error.javaClass.simpleName}: ${error.message}")
        return false
    } finally {
        session.close()
    }
}

/**
 * Wireless CarPlay with media only counted, not shown. Runs until the process is stopped; every
 * session end or failure restarts the bring-up.
 */
private fun runCarPlay() {
    val config = DesktopConfig(DesktopConfig.DEFAULT_DIRECTORY)
    if (!firewallRuleExists()) {
        val program = ProcessHandle.current().info().command().orElse("java.exe")
        log("no Windows Firewall rule \"$FIREWALL_RULE\"; the iPhone cannot connect until one is added")
        log("from an administrator prompt:\n" + firewallHint(config.airPlay.port, program))
    }
    val sink = LoggingMediaSink(::log)
    val bootstrap = WirelessBootstrap(config, sink, object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) = log("AirPlay session active")
        override fun onSessionEnded(session: AirPlaySession) = log("AirPlay session ended")
        override fun onTransportError(message: String) = log("AirPlay transport error: $message")
        override fun onDebugLog(message: String) = log(message)
    })
    Runtime.getRuntime().addShutdownHook(Thread {
        bootstrap.close()
        sink.close()
    })
    bootstrap.start()
    Thread.currentThread().join()
}

/** Reads the rule only; adding it needs an administrator and is left to the user. */
private fun firewallRuleExists(): Boolean =
    ProcessBuilder("netsh", "advfirewall", "firewall", "show", "rule", "name=$FIREWALL_RULE")
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
        .waitFor() == 0
