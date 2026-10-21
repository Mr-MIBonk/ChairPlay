package com.shilapi.xcertplay.desktop

import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.desktop.bluetooth.WindowsBluetooth
import com.shilapi.xcertplay.desktop.bluetooth.selectIphone
import com.shilapi.xcertplay.desktop.config.DesktopConfig
import com.shilapi.xcertplay.desktop.config.Theme
import com.shilapi.xcertplay.desktop.config.WindowPlacementStore
import com.shilapi.xcertplay.desktop.config.WindowSize
import com.shilapi.xcertplay.desktop.input.Control
import com.shilapi.xcertplay.desktop.input.ControlsConfig
import com.shilapi.xcertplay.desktop.input.KeyControls
import com.shilapi.xcertplay.desktop.input.TouchQueue
import com.shilapi.xcertplay.desktop.input.TouchTracker
import com.shilapi.xcertplay.desktop.media.AudioDevices
import com.shilapi.xcertplay.desktop.media.AudioRenderer
import com.shilapi.xcertplay.desktop.media.DesktopMediaSink
import com.shilapi.xcertplay.desktop.media.VideoWindow
import com.shilapi.xcertplay.desktop.media.nightMode
import com.shilapi.xcertplay.desktop.media.runWindows
import com.shilapi.xcertplay.desktop.media.saveScreenshot
import com.shilapi.xcertplay.desktop.media.windowsAppsDark
import com.shilapi.xcertplay.desktop.session.FIREWALL_RULE
import com.shilapi.xcertplay.desktop.session.IAP2_IPHONE_UUID
import com.shilapi.xcertplay.desktop.session.WirelessBootstrap
import com.shilapi.xcertplay.desktop.session.desktopIdentification
import com.shilapi.xcertplay.desktop.session.firewallHint
import com.shilapi.xcertplay.desktop.session.readFirewall
import com.shilapi.xcertplay.desktop.session.redactTrace
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessLinkRole
import com.shilapi.xcertplay.transport.forWirelessLink
import java.nio.file.Path
import java.time.LocalTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
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

/** How often display.theme=windows looks at the Windows theme. */
private const val THEME_POLL_SECONDS = 5L

/** The app folder, where the settings, keys and pairings live. */
private fun dataDirectory(): Path {
    val dir = DesktopConfig.appDirectory()
    log("settings and keys: $dir")
    return dir
}

private fun listDevices() {
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
private fun probeBluetooth(address: String?): Boolean {
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
 * Wireless CarPlay with the screen shown in a window and its sound played through Java Sound. Runs until the window
 * is closed or the process is stopped; every session end or failure restarts the bring-up.
 */
private fun runCarPlay() {
    val dir = dataDirectory()
    val config = DesktopConfig(dir)
    val controlsConfig = ControlsConfig.load(dir)
    log(controlsConfig.summary())
    checkFirewall(config.airPlay.port)
    log(config.displaySummary())
    val placements = WindowPlacementStore(dir)
    val window = VideoWindow(
        config.window.width, config.window.height, placements.load(config.window),
        aspect = WindowSize(config.displayWidth, config.displayHeight).takeIf { config.keepAspect },
    )
    window.onPlacement = { placement ->
        runCatching { placements.save(placement, config.window) }.onFailure { log("window place not saved: $it") }
    }
    // The altscreen (altscreen.enabled) gets a window of its own beside the main one; it takes no input.
    val altscreen = config.altscreen?.let { alt ->
        log("altscreen ${alt.size.width}x${alt.size.height} px, starting with ${alt.content.key}")
        val altPlacements = WindowPlacementStore(dir, prefix = "altscreen.")
        VideoWindow(
            alt.size.width, alt.size.height, altPlacements.load(alt.size), "DiPlayDesktop altscreen",
            aspect = alt.size.takeIf { config.keepAspect },
        ).also {
            it.onPlacement = { placement ->
                runCatching { altPlacements.save(placement, alt.size) }.onFailure { log("altscreen place not saved: $it") }
            }
        }
    }
    var altscreenContent = config.altscreen?.content
    // A device name matching nothing stops here, before any connection, with the devices listed.
    val output = AudioDevices.output(config.audioOutput)
    val input = AudioDevices.input(config.audioInput)
    log("audio output: ${output?.name ?: "Windows default"}; microphone: ${input?.name ?: "Windows default"}")
    val sink = DesktopMediaSink(window, AudioRenderer(::log, output), ::log, input, config.echoCancellation, altscreen)
    val active = AtomicReference<AirPlaySession?>()
    // sendTouch writes to the iPhone's socket and can block; the GL thread only maps and enqueues.
    // One thread keeps the press, moves and release in order.
    val touchSender = Executors.newSingleThreadExecutor { Thread(it, "touch-sender").apply { isDaemon = true } }
    val tracker = TouchTracker()
    val pending = TouchQueue()
    // Every sample goes through the tracker so it never misses a button change; only the send needs a session.
    window.setInputHandler { event ->
        val contact = tracker.handle(event, window.framebufferSize, window.pictureSize) ?: return@setInputHandler
        if (active.get() == null) return@setInputHandler
        pending.offer(contact)
        touchSender.execute { pending.poll()?.let { active.get()?.sendTouch(listOf(it)) } }
    }
    // Keys and the wheel go through the touch thread too, so a tap and a key press reach the iPhone in order.
    val controls = KeyControls(controlsConfig)
    fun send(control: Control?) {
        control ?: return
        if (control == Control.Screenshot) {
            window.screenshot { image -> saveScreenshot(image, dir, ::log) }
            return
        }
        touchSender.execute {
            val session = active.get() ?: return@execute
            when (control) {
                is Control.Knob -> session.sendKnob(control.state, control.momentary)
                is Control.Media -> session.sendMedia(control.index)
                is Control.Telephony -> session.sendTelephony(control.index)
                Control.Siri -> session.invokeSiri()
                is Control.MapZoom -> repeat(abs(control.steps)) {
                    session.changeMapZoomLevel(zoomIn = control.steps > 0)
                }
                Control.NextAltscreenContent -> {
                    val next = altscreenContent?.next() ?: return@execute
                    altscreenContent = next
                    log("altscreen ${next.key} sent=${session.setClusterUrl(next.cluster.url, send = true)}")
                }
                Control.Screenshot -> Unit
            }
        }
    }
    window.keyHandler = { key, action, mods -> send(controls.key(key, action, mods)) }
    // Diagnostics: DIPLAY_SCREENSHOT_AFTER=<seconds> saves one screenshot that long after the first picture.
    System.getenv("DIPLAY_SCREENSHOT_AFTER")?.toLongOrNull()?.let { seconds ->
        Thread({
            while (window.pictureSize.width == 0) Thread.sleep(200)
            Thread.sleep(seconds * 1000)
            window.screenshot { image -> saveScreenshot(image, dir, ::log) }
        }, "screenshot-timer").apply { isDaemon = true }.start()
    }
    window.scrollHandler = { dy -> send(controls.scroll(dy)) }
    altscreen?.scrollHandler = { dy -> send(controls.altscreenScroll(dy)) }
    fun sessionChanged() {
        pending.clear()
        tracker.reset()
    }
    // The theme goes out once a session is active; with display.theme=windows a change of the Windows theme follows.
    // Set from the session thread and read by the watch thread.
    val lastNight = AtomicReference<Boolean?>()
    fun sendTheme(session: AirPlaySession) {
        val night = nightMode(config.theme, ::windowsAppsDark) ?: return
        lastNight.set(night)
        touchSender.execute { log("CarPlay theme ${if (night) "dark" else "light"} sent=${session.setNightMode(night)}") }
    }
    val themeWatch = Executors.newSingleThreadScheduledExecutor { Thread(it, "theme-watch").apply { isDaemon = true } }
    if (config.theme == Theme.WINDOWS) {
        themeWatch.scheduleWithFixedDelay({
            val session = active.get()
            if (session != null && nightMode(config.theme, ::windowsAppsDark) != lastNight.get()) sendTheme(session)
        }, THEME_POLL_SECONDS, THEME_POLL_SECONDS, TimeUnit.SECONDS)
    }
    val bootstrap = WirelessBootstrap(config, sink, object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            active.set(session)
            sessionChanged()
            log("AirPlay session active")
            sendTheme(session)
        }
        override fun onSessionEnded(session: AirPlaySession) {
            // /info probes and pairing retries end too; resetting for them would cut a drag on the live session
            // short, and its release would never reach the iPhone.
            if (active.compareAndSet(session, null)) sessionChanged()
            log("AirPlay session ended")
        }
        // The driver tapped the Windows icon on the CarPlay home screen: give the screen back to Windows.
        override fun onHostUiRequested(session: AirPlaySession) {
            log("CarPlay asked for the Windows screen; minimizing the window")
            window.minimize()
        }
        override fun onTransportError(message: String) = log("AirPlay transport error: $message")
        override fun onDebugLog(message: String) = log(message)
    })
    Runtime.getRuntime().addShutdownHook(Thread {
        themeWatch.shutdownNow()
        bootstrap.close()
        sink.close()
        touchSender.shutdownNow()
    })
    bootstrap.start()
    // GLFW needs its windows and event loop on this, the main, thread; the bring-up runs on its own.
    if (altscreen == null) window.run() else runWindows(window, altscreen)
    exitProcess(0)
}

/**
 * Says what keeps the iPhone out, if anything. A block rule wins over any allow rule, so one left by Windows'
 * first-run prompt is reported even when ours exists.
 */
private fun checkFirewall(port: Int) {
    val program = ProcessHandle.current().info().command().orElse("java.exe")
    val hint = "from an administrator prompt:\n" + firewallHint(port, program)
    val state = readFirewall(program)
    if (state == null) {
        log("could not read the Windows Firewall rules; if the iPhone cannot connect, add these $hint")
        return
    }
    if (!state.allowRule) {
        log("no Windows Firewall rule \"$FIREWALL_RULE\"; the iPhone cannot connect until one is added")
        log(hint)
    }
    state.blocking.forEach {
        log("Windows Firewall rule $it blocks inbound connections to $program; the iPhone cannot connect while " +
            "it applies. Delete it or set it to Allow in Windows Defender Firewall > Advanced settings > Inbound Rules")
    }
}
