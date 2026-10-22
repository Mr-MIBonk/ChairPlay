package io.github.numberonebot.chairplay

import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import io.github.numberonebot.chairplay.bluetooth.WindowsBluetooth
import io.github.numberonebot.chairplay.bluetooth.selectIphone
import io.github.numberonebot.chairplay.config.DesktopConfig
import io.github.numberonebot.chairplay.config.Theme
import io.github.numberonebot.chairplay.config.AltscreenCards
import io.github.numberonebot.chairplay.config.AltscreenContent
import io.github.numberonebot.chairplay.config.AltscreenViewStore
import io.github.numberonebot.chairplay.config.WindowPlacementStore
import io.github.numberonebot.chairplay.config.altscreenTitle
import io.github.numberonebot.chairplay.config.mainWindowTitle
import io.github.numberonebot.chairplay.config.altscreenUrl
import io.github.numberonebot.chairplay.config.WindowSize
import io.github.numberonebot.chairplay.data.CarPlayData
import io.github.numberonebot.chairplay.data.CarPlayDataLog
import io.github.numberonebot.chairplay.input.Control
import io.github.numberonebot.chairplay.input.ControlPad
import io.github.numberonebot.chairplay.input.ControlsConfig
import io.github.numberonebot.chairplay.input.ControlsPainter
import io.github.numberonebot.chairplay.input.KeyControls
import io.github.numberonebot.chairplay.input.TouchQueue
import io.github.numberonebot.chairplay.input.TouchTracker
import io.github.numberonebot.chairplay.input.controlAt
import io.github.numberonebot.chairplay.media.AudioDevices
import io.github.numberonebot.chairplay.media.AudioRenderer
import io.github.numberonebot.chairplay.media.DesktopMediaSink
import io.github.numberonebot.chairplay.media.VideoWindow
import io.github.numberonebot.chairplay.media.nightMode
import io.github.numberonebot.chairplay.media.runWindows
import io.github.numberonebot.chairplay.hud.HudDemo
import io.github.numberonebot.chairplay.hud.HudPainter
import io.github.numberonebot.chairplay.hud.HudView
import io.github.numberonebot.chairplay.hud.hudState
import io.github.numberonebot.chairplay.media.saveScreenshot
import io.github.numberonebot.chairplay.media.windowsAppsDark
import io.github.numberonebot.chairplay.session.FIREWALL_RULE
import io.github.numberonebot.chairplay.session.IAP2_IPHONE_UUID
import io.github.numberonebot.chairplay.session.WirelessBootstrap
import io.github.numberonebot.chairplay.session.desktopIdentification
import io.github.numberonebot.chairplay.session.firewallHint
import io.github.numberonebot.chairplay.session.readFirewall
import io.github.numberonebot.chairplay.session.redactTrace
import io.github.numberonebot.chairplay.ui.ChairPlayUi
import io.github.numberonebot.chairplay.ui.FirewallStatus
import io.github.numberonebot.chairplay.ui.UiActions
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessLinkRole
import com.shilapi.xcertplay.transport.forWirelessLink
import java.nio.file.Path
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
private const val CONNECTING = "connecting…"
private val CONTROLS_SIZE = WindowSize(720, 320)
private const val SCREENSHOT_PICTURE_WAIT_NANOS = 30_000_000_000L
private const val CLOSING_PREVIOUS = "closing previous run…"
private const val PREVIOUS_RUN_WAIT_MILLIS = 15_000L
private const val FADE_OUT_MILLIS = 1_000L

/** Held for the life of the process; the system lets go of it when the process ends. */
private val instanceLock = AtomicReference<InstanceLock?>()
private const val RECONNECTING = "reconnecting…"
private const val NOT_CONNECTED = "not connected"
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

/**
 * Wireless CarPlay with the screen shown in a window and its sound played through Java Sound. Runs until the window
 * is closed or the process is stopped; every session end or failure restarts the bring-up.
 */
private fun runCarPlay() {
    val dir = dataDirectory()
    val config = DesktopConfig(dir)
    val controlsConfig = ControlsConfig.load(dir)
    log(controlsConfig.summary())
    val firewall = checkFirewall(config.airPlay.port)
    log(config.displaySummary())
    val placements = WindowPlacementStore(dir)
    val window = VideoWindow(
        config.window.width, config.window.height, placements.load(config.window),
        mainWindowTitle(WindowSize(config.displayWidth, config.displayHeight), CONNECTING),
        aspect = WindowSize(config.displayWidth, config.displayHeight).takeIf { config.keepAspect },
    )
    window.onPlacement = { placement ->
        runCatching { placements.save(placement, config.window) }.onFailure { log("window place not saved: $it") }
    }
    // The altscreen (altscreen.enabled) gets a window of its own beside the main one; it takes no input.
    // window.controls: the car's controls as buttons in a window of their own, shaped freely.
    val controlsPainter = if (config.controlsWindow) ControlsPainter(::log) else null
    val controlsWindow = controlsPainter?.let { painter ->
        val placements = WindowPlacementStore(dir, prefix = "controls.")
        VideoWindow(CONTROLS_SIZE.width, CONTROLS_SIZE.height, placements.load(CONTROLS_SIZE), "ChairPlay Controls").also {
            it.overlay = painter
            it.hud = true
            it.onPlacement = { placement ->
                runCatching { placements.save(placement, CONTROLS_SIZE) }.onFailure { error -> log("controls window place not saved: $error") }
            }
        }
    }
    val altscreen = config.altscreen?.let { alt ->
        log("altscreen ${alt.size.width}x${alt.size.height} px, starting with ${alt.content.key}")
        val altPlacements = WindowPlacementStore(dir, prefix = "altscreen.")
        VideoWindow(
            alt.size.width, alt.size.height, altPlacements.load(alt.size), altscreenTitle(alt.size, alt.content, alt.cards),
            aspect = alt.size.takeIf { config.keepAspect },
        ).also {
            it.onPlacement = { placement ->
                runCatching { altPlacements.save(placement, alt.size) }.onFailure { log("altscreen place not saved: $it") }
            }
        }
    }
    var altscreenContent = config.altscreen?.content
    altscreen?.hud = altscreenContent == AltscreenContent.HUD
    var altscreenCards = config.altscreen?.cards ?: AltscreenCards.AUTO
    // F12 saves both screens, each at the size the iPhone draws it.
    fun screenshots() {
        window.screenshot { image -> saveScreenshot(image, dir, ::log) }
        altscreen?.screenshot { image -> saveScreenshot(image, dir, ::log, "altscreen") }
        controlsWindow?.screenshot { image -> saveScreenshot(image, dir, ::log, "controls") }
    }
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
    // The main window's title says when there is no session to show, and why.
    val stream = WindowSize(config.displayWidth, config.displayHeight)
    val reconnecting = AtomicBoolean()
    // Ctrl+R: set once the bootstrap exists; closing a session talks to the network, so not on the GL thread.
    val restartBootstrap = AtomicReference<() -> Unit> {}
    fun reconnect() = Thread({ restartBootstrap.get()() }, "reconnect").apply { isDaemon = true }.start()
    fun send(control: Control?) {
        control ?: return
        if (control == Control.Screenshot) {
            screenshots()
            return
        }
        if (control == Control.Reconnect) {
            log("reconnecting at the user's request")
            reconnecting.set(true)
            window.setTitle(mainWindowTitle(stream, RECONNECTING))
            sink.fadeOut(FADE_OUT_MILLIS)
            reconnect()
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
                Control.NextAltscreenContent, Control.NextAltscreenCards -> {
                    val content = altscreenContent ?: return@execute
                    if (control == Control.NextAltscreenContent) altscreenContent = content.next()
                    else altscreenCards = altscreenCards.next()
                    altscreen?.hud = altscreenContent == AltscreenContent.HUD
                    val url = altscreenUrl(altscreenContent!!, altscreenCards)
                    val sent = session.setClusterUrl(url, send = true)
                    log("altscreen ${altscreenContent!!.key}, cards ${altscreenCards.key}: $url sent=$sent")
                    val size = config.altscreen!!.size
                    altscreen?.setTitle(altscreenTitle(size, altscreenContent!!, altscreenCards) + if (sent) "" else " (not sent)")
                    runCatching { AltscreenViewStore(dir).save(altscreenContent!!, altscreenCards, config.altscreen!!.configured) }
                        .onFailure { log("AltScreen view not saved: $it") }
                }
                Control.Screenshot, Control.Reconnect -> Unit
            }
        }
    }
    // The start and settings screens (ui/): a page over the picture, served by a local HTTP server. Connect waits,
    // as the start does, for a previous run to let go of the iPhone.
    val lockReady = CountDownLatch(1)
    val startBootstrap = AtomicReference<() -> Unit> {}
    val ui = ChairPlayUi(dir, config, firewall, object : UiActions {
        override fun connect() = Thread({
            lockReady.await()
            window.setTitle(mainWindowTitle(stream, CONNECTING))
            startBootstrap.get()()
        }, "connect").apply { isDaemon = true }.start()
        override fun reconnect() = send(Control.Reconnect)
    }, window::repaint, ::log)
    window.child = ui.view
    // While the page covers the window, the keys and the wheel are its own.
    window.keyHandler = { key, action, mods -> if (!ui.coversWindow) send(controls.key(key, action, mods)) }
    // The same keys work while the altscreen window has the focus; both windows run on the one GL thread.
    altscreen?.keyHandler = window.keyHandler
    // A button held with the mouse holds its control until the release, wherever the cursor goes meanwhile.
    controlsWindow?.let { buttons ->
        val painter = controlsPainter!!
        val pad = ControlPad()
        buttons.keyHandler = window.keyHandler
        buttons.setInputHandler { event ->
            val held = painter.pressed
            if (event.down && held == null) {
                val action = controlAt(painter.buttons, event.x.toFloat(), event.y.toFloat()) ?: return@setInputHandler
                painter.pressed = action
                send(pad.press(action))
                buttons.repaint()
            } else if (!event.down && held != null) {
                painter.pressed = null
                send(pad.release(held))
                buttons.repaint()
            }
        }
    }
    // Diagnostics: CHAIRPLAY_SCREENSHOT_AFTER=<seconds> saves one screenshot that long after the first picture, or
    // after half a minute without one, so the windows the app draws itself can be looked at without an iPhone.
    System.getenv("CHAIRPLAY_SCREENSHOT_AFTER")?.toLongOrNull()?.let { seconds ->
        Thread({
            val giveUp = System.nanoTime() + SCREENSHOT_PICTURE_WAIT_NANOS
            while (window.pictureSize.width == 0 && System.nanoTime() < giveUp) Thread.sleep(200)
            Thread.sleep(seconds * 1000)
            screenshots()
        }, "screenshot-timer").apply { isDaemon = true }.start()
    }
    window.scrollHandler = { dy -> if (!ui.coversWindow) send(controls.scroll(dy)) }
    altscreen?.scrollHandler = { dy -> send(controls.altscreenScroll(dy)) }
    fun sessionChanged() {
        pending.clear()
        tracker.reset()
        // The HUD's waiting line and the session's parts rise and fade as the session comes and goes.
        altscreen?.let { if (it.hud) it.repaint() }
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
    // The iPhone's route, song, call and battery messages, for later screens and, with log.carplay.data, a file.
    val carPlayData = CarPlayData(if (config.logCarPlayData) CarPlayDataLog(dir.resolve("logs"), log = ::log) else null)
    // The AltScreen's HUD shows the bus's data, drawn again when it changes.
    altscreen?.let { alt ->
        // CHAIRPLAY_HUD_DEMO plays a made-up route instead of the bus's data, for watching the maneuvers (HudDemo).
        val demo = HudDemo.fromEnvironment()?.start(alt::repaint) { name ->
            alt.screenshot { image -> saveScreenshot(image, dir, ::log, "altscreen-demo-$name") }
        }
        alt.overlay = HudPainter({
            val snapshot = demo?.invoke() ?: carPlayData.bus.snapshot()
            HudView(demo != null || active.get() != null, hudState(snapshot), snapshot.artwork)
        }, ::log, alt::repaint)
        carPlayData.bus.addListener { if (alt.hud) alt.repaint() }
    }
    val bootstrap = WirelessBootstrap(config, sink, object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            active.set(session)
            sessionChanged()
            log("AirPlay session active")
            ui.sessionActive()
            reconnecting.set(false)
            window.setTitle(mainWindowTitle(stream))
            sendTheme(session)
        }
        override fun onSessionEnded(session: AirPlaySession) {
            // /info probes and pairing retries end too; resetting for them would cut a drag on the live session
            // short, and its release would never reach the iPhone.
            if (active.compareAndSet(session, null)) {
                sessionChanged()
                ui.sessionEnded()
                window.setTitle(mainWindowTitle(stream, if (reconnecting.get()) RECONNECTING else CONNECTING))
            }
            log("AirPlay session ended")
        }
        // The driver tapped the car button (the Windows icon) on the CarPlay home screen: the settings screen covers
        // CarPlay. With the page in a browser instead, the window gives the screen back to Windows, as before.
        override fun onHostUiRequested(session: AirPlaySession) {
            if (ui.carButton()) {
                log("CarPlay's car button: showing the settings")
            } else {
                log("CarPlay asked for the Windows screen; minimizing the window")
                window.minimize()
            }
        }
        override fun onTransportError(message: String) = log("AirPlay transport error: $message")
        override fun onDebugLog(message: String) = log(message)
    }, carPlayData)
    restartBootstrap.set(bootstrap::restart)
    startBootstrap.set(bootstrap::start)
    bootstrap.onStatus = ui::bringUp
    Runtime.getRuntime().addShutdownHook(Thread {
        // The window is gone at once; the sound fades over the seconds the session takes to end.
        sink.fadeOut(FADE_OUT_MILLIS)
        ui.close()
        themeWatch.shutdownNow()
        bootstrap.close()
        carPlayData.close()
        sink.close()
        touchSender.shutdownNow()
    })
    // A previous run that is still ending its session holds the iPhone's Bluetooth channel: wait for it first.
    Thread({
        instanceLock.set(InstanceLock.acquire(dir, PREVIOUS_RUN_WAIT_MILLIS) {
            log("the previous run is still closing; waiting for it")
            window.setTitle(mainWindowTitle(stream, CLOSING_PREVIOUS))
        })
        lockReady.countDown()
        if (ui.connectsAtStart) {
            window.setTitle(mainWindowTitle(stream, CONNECTING))
            bootstrap.start()
        } else {
            log(if (config.wifi == null) "no Wi-Fi network set; enter it on the start screen" else "waiting for Connect on the start screen")
            window.setTitle(mainWindowTitle(stream, NOT_CONNECTED))
        }
    }, "instance-lock").apply { isDaemon = true }.start()
    // GLFW needs its windows and event loop on this, the main, thread; the bring-up runs on its own.
    runWindows(window, *listOfNotNull(altscreen, controlsWindow).toTypedArray())
    exitProcess(0)
}

/**
 * Says what keeps the iPhone out, if anything. A block rule wins over any allow rule, so one left by Windows'
 * first-run prompt is reported even when ours exists. The start screen shows the same.
 */
private fun checkFirewall(port: Int): FirewallStatus {
    val program = ProcessHandle.current().info().command().orElse("java.exe")
    val hint = "from an administrator prompt:\n" + firewallHint(port, program)
    val state = readFirewall(program)
    if (state == null) {
        log("could not read the Windows Firewall rules; if the iPhone cannot connect, add these $hint")
        return FirewallStatus.UNKNOWN
    }
    if (!state.allowRule) {
        log("no Windows Firewall rule \"$FIREWALL_RULE\"; the iPhone cannot connect until one is added")
        log(hint)
    }
    state.blocking.forEach {
        log("Windows Firewall rule $it blocks inbound connections to $program; the iPhone cannot connect while " +
            "it applies. Delete it or set it to Allow in Windows Defender Firewall > Advanced settings > Inbound Rules")
    }
    return when {
        state.blocking.isNotEmpty() -> FirewallStatus.BLOCKED
        !state.allowRule -> FirewallStatus.MISSING
        else -> FirewallStatus.PRESENT
    }
}
