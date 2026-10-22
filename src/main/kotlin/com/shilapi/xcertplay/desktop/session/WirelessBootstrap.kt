package com.shilapi.xcertplay.desktop.session

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.desktop.bluetooth.WindowsBluetooth
import com.shilapi.xcertplay.desktop.bluetooth.selectIphone
import com.shilapi.xcertplay.desktop.config.DesktopConfig
import com.shilapi.xcertplay.desktop.data.CarPlayDataTap
import com.shilapi.xcertplay.desktop.data.DataLink
import com.shilapi.xcertplay.desktop.data.DataSubscriptions
import com.shilapi.xcertplay.desktop.discovery.DesktopBonjour
import com.shilapi.xcertplay.desktop.network.SameLanNetwork
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.network.HotspotAddressPolicy
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlResult
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessLinkRole
import com.shilapi.xcertplay.transport.forWirelessLink
import java.io.Closeable
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal val IAP2_IPHONE_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")

/** The values DiPlay's head-unit build identifies with, under [name] and keyed to this PC's Bluetooth radio. */
internal fun desktopIdentification(radio: String, name: String = DesktopConfig.DEFAULT_DEVICE_NAME) = Iap2IdentificationConfig(
    name = name,
    modelIdentifier = "DiPlayDesktop",
    manufacturer = "DiPlay",
    serialNumber = "DIPLAY-" + radio.replace(":", ""),
    firmwareVersion = "0.1.0",
    hardwareVersion = "1.0",
    // Only the wired transport reads this; the value matches DiPlay's head-unit build.
    carPlayUsbInterfaceNumber = 3,
)

/** The 0x4301/0x5703 endpoint; the address goes as upstream sends it, compressed and without scope. */
fun buildEndpoint(
    info: WirelessHotspotInfo,
    host: InetAddress,
    port: Int,
    config: AirPlayConfig,
    identity: AirPlayIdentity,
) = Iap2WirelessCarPlayEndpoint(
    ssid = info.ssid,
    passphrase = info.passphrase,
    channel = info.channel,
    security = info.security,
    ipAddresses = listOf(endpointText(host)),
    airPlayPort = port,
    deviceIdentifier = config.deviceId,
    publicKey = identity.publicKeyHex,
    sourceVersion = config.sourceVersion,
    accessPointBssid = info.accessPointBssid,
)

/** Android prints IPv6 in RFC 5952 short form; the JDK prints every group and the scope. */
private fun endpointText(address: InetAddress): String {
    if (address !is Inet6Address) return checkNotNull(address.hostAddress)
    val groups = (0 until 8).map { ((address.address[2 * it].toInt() and 0xff) shl 8) or (address.address[2 * it + 1].toInt() and 0xff) }
    var zerosAt = -1
    var zerosLength = 1
    var run = 0
    for ((index, group) in groups.withIndex()) {
        run = if (group == 0) run + 1 else 0
        if (run > zerosLength) {
            zerosLength = run
            zerosAt = index - run + 1
        }
    }
    val hex = groups.map { it.toString(16) }
    if (zerosAt < 0) return hex.joinToString(":")
    return hex.subList(0, zerosAt).joinToString(":") + "::" + hex.subList(zerosAt + zerosLength, 8).joinToString(":")
}

/** What a bring-up takes from the PC's radios and the iPhone; tests substitute fakes. */
internal interface WirelessParts {
    val mfi: MfiAuthenticator?
    fun network(): WirelessHotspotManager
    fun radioAddress(): String
    fun media(sink: MediaSink, microphoneEnabled: Boolean): AirPlayMediaHandler
    /** Returns the started advertisement. */
    fun advertise(config: AirPlayConfig, identity: AirPlayIdentity, addresses: List<InetAddress>): Closeable
    fun connectIphone(): BlockingDuplexByteStream
    fun control(
        session: Iap2Session,
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
        timeoutMillis: Long,
        onReady: () -> Unit,
        keepAliveAfterDeadline: () -> Boolean,
        /** Each frame the control loop does not handle itself: route guidance, now playing, calls, power. */
        onIncoming: (Iap2Frame) -> Unit,
    ): Iap2WirelessControlResult
}

/**
 * Wireless CarPlay bring-up in the order of upstream CarPlayController.runWireless: network,
 * AirPlay listener, Bonjour, then iAP2 over Bluetooth hands the iPhone the Wi-Fi endpoint. Every
 * bring-up owns one [Stack]. The end of an AirPlay session that went active, or a failure before
 * the iPhone joined over Wi-Fi, closes it and starts the next one exactly once; callbacks from a
 * stack already replaced are ignored. The data the iPhone sends over either iAP2 link (route guidance,
 * now playing, calls, battery, artwork) goes to [data] until its stack is retired.
 */
class WirelessBootstrap internal constructor(
    private val config: DesktopConfig,
    private val sink: MediaSink,
    private val listener: AirPlaySessionListener,
    private val data: CarPlayDataTap = CarPlayDataTap.NONE,
    private val newParts: () -> WirelessParts,
) : Closeable {
    constructor(config: DesktopConfig, sink: MediaSink, listener: AirPlaySessionListener, data: CarPlayDataTap = CarPlayDataTap.NONE) :
        this(config, sink, listener, data, { DesktopParts(config, listener::onDebugLog) })

    private val lock = Any()
    @Volatile private var current: Stack? = null
    private var closed = false

    fun start() {
        synchronized(lock) {
            if (closed || current != null) return
            val stack = Stack(newParts())
            current = stack
            Thread({ bringUp(stack) }, "diplay-wireless").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun restart() {
        synchronized(lock) {
            current?.let(::retire)
            start()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            current?.let(::retire)
        }
    }

    private fun restart(stack: Stack) {
        synchronized(lock) {
            if (current !== stack) return
            retire(stack)
            start()
        }
    }

    /** Unlike a session end, a failure waits before retrying: most recur at once (Wi-Fi, pairing, MFi). */
    private fun failed(stack: Stack, error: Exception) {
        synchronized(lock) {
            if (current !== stack) return
            log("wireless bring-up failed: ${error.javaClass.simpleName}: ${error.message}")
            retire(stack)
        }
        Thread.sleep(RETRY_DELAY_MILLIS)
        synchronized(lock) { if (current == null) start() }
    }

    private fun retire(stack: Stack) {
        current = null
        stack.close()
        data.onSessionEnded()
    }

    /** A retired stack's links may still deliver a frame or two; they must not refill the next session's data. */
    private fun dataFrom(stack: Stack, link: DataLink): (Iap2Frame) -> Unit = { if (!stack.closed) data.onFrame(link, it) }

    private fun log(message: String) = listener.onDebugLog(message)

    private fun trace(message: String) = log(redactTrace(message))

    private fun bringUp(stack: Stack) {
        val parts = stack.parts
        try {
            val network = stack.own(parts.network())
            val info = network.start(NETWORK_TIMEOUT_MILLIS)
            val host = HotspotAddressPolicy.select(info.hostAddresses) ?: info.hostAddress
                ?: throw IOException("Wi-Fi adapter has no usable address")
            val media = parts.media(sink, microphoneEnabled = config.airPlay.microphone)
            val radio = parts.radioAddress()
            val sessionConfig = config.airPlay.copy(btMac = radio)
            val sessions = sessionListener(stack)
            network.validateReady()
            val airPlay = stack.own(AirPlayListener(
                listOf(host) + info.hostAddresses.filter { it != host },
                sessionConfig.port,
                { socket ->
                    AirPlaySession(socket, sessionConfig.copy(port = socket.localPort), config.identity,
                        config.pairings, parts.mfi, sessions, media)
                },
                { failed(stack, it) },
            ))
            val advertised = sessionConfig.copy(port = airPlay.start())
            log("AirPlay listening on ${host.hostAddress} port ${advertised.port}")
            network.validateReady()
            stack.own(parts.advertise(advertised, config.identity, info.hostAddresses))

            val rfcomm = stack.own(parts.connectIphone())
            val bootstrap = stack.handOver(rfcomm,
                Iap2Session.openWireless(rfcomm, traceContext = "wireless-rfcomm", onTrace = ::trace,
                    onArtwork = { if (!stack.closed) data.onArtwork(DataLink.BLUETOOTH, it) }))
            stack.bootstrap = bootstrap
            val wireless = Iap2WirelessIdentification(radio, info.ssid)
            val identification = desktopIdentification(radio, config.airPlay.deviceName)
            val runtimeIdentification = identification.forWirelessLink(Iap2WirelessLinkRole.RUNTIME_TUNNEL, wireless)
            val endpoint = buildEndpoint(info, host, advertised.port, advertised, config.identity)
            media.setIapTunnelHandler { tunnel -> startTunnelControl(stack, tunnel, runtimeIdentification, endpoint) }
            stack.own(AutoCloseable { media.setIapTunnelHandler(null) })

            network.validateReady()
            val known = config.iphoneKnown()
            val joinTimeout = if (known) config.iphoneJoinSeconds * 1_000L else CONTROL_LOOP_TIMEOUT_MILLIS
            log("waiting up to ${joinTimeout / 1_000} s for the ${if (known) "known" else "new"} iPhone to join the Wi-Fi")
            val result = parts.control(
                bootstrap,
                identification.forWirelessLink(Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP, wireless),
                endpoint,
                joinTimeout,
                onReady = {},
                // keepBluetoothControlAlive: until a handoff, Bluetooth is the only iAP2 channel.
                keepAliveAfterDeadline = { stack.rendered && stack.active.isNotEmpty() },
                onIncoming = dataFrom(stack, DataLink.BLUETOOTH),
            )
            if (stack.closed || stack.handedOff.get()) return
            log("iAP2 Bluetooth control ended ${result.terminal} at ${result.stage}")
            if (!stack.joined) {
                throw IOException("Bluetooth control ended ${result.terminal} before the iPhone joined over Wi-Fi")
            }
        } catch (_: StackClosed) {
        } catch (error: Exception) {
            // The Wi-Fi session outlives its Bluetooth bootstrap (upstream's wirelessActiveReported).
            if (stack.joined || stack.handedOff.get()) {
                if (!stack.closed) log("iAP2 Bluetooth control ended after the iPhone joined over Wi-Fi: ${error.message}")
            } else {
                failed(stack, error)
            }
        }
    }

    /** CarPlayController.startWirelessTunnelControl: runtime iAP2 over the AirPlay type-130 tunnel. */
    private fun startTunnelControl(
        stack: Stack,
        stream: BlockingDuplexByteStream,
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
    ): Boolean {
        val channel = try {
            stack.own(Iap2Session.openTunnel(stream, traceContext = "wireless-tunnel", onTrace = ::trace,
                onArtwork = { if (!stack.closed) data.onArtwork(DataLink.WIFI, it) }))
        } catch (_: StackClosed) {
            return false
        } catch (error: Exception) {
            log("Could not open the tunneled iAP2 link: ${error.message}")
            return false
        }
        Thread({
            try {
                val result = stack.parts.control(channel, identification, endpoint,
                    Iap2WirelessControlClient.NO_TIMEOUT_MILLIS, onReady = { tunnelReady(stack) }, keepAliveAfterDeadline = { false },
                    onIncoming = dataFrom(stack, DataLink.WIFI))
                if (!stack.closed) log("iAP2 tunnel ended ${result.terminal} at ${result.stage}")
            } catch (error: Exception) {
                if (!stack.closed) log("tunneled iAP2 control failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }, "diplay-iap2-tunnel").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** onWirelessTunnelReady: iAP2 control can now run over Wi-Fi. */
    private fun tunnelReady(stack: Stack) {
        if (stack.closed) return
        stack.tunnelReady = true
        stack.joined = true
        log("wireless iAP2 tunnel ready; handoffRequested=${stack.handoffRequested.get()}")
        completeHandoff(stack)
    }

    /**
     * maybeCompleteWirelessHandoff: once the iPhone has asked to stop using Bluetooth and the tunnel
     * carries iAP2, the Bluetooth bootstrap is closed on purpose; the Wi-Fi session stays.
     */
    private fun completeHandoff(stack: Stack) {
        if (!stack.handoffRequested.get() || !stack.tunnelReady) return
        val bootstrap = stack.bootstrap ?: return
        if (!stack.handedOff.compareAndSet(false, true)) return
        log("wireless handoff ready; closing Bluetooth bootstrap transport")
        stack.release(bootstrap)
    }

    private fun sessionListener(stack: Stack) = object : AirPlaySessionListener by listener {
        override fun onSessionActive(session: AirPlaySession) {
            if (stack.closed) return
            stack.active += session
            stack.joined = true
            listener.onSessionActive(session)
        }

        /** Only a session that went active ends CarPlay: /info probes and pairing retries also connect. */
        override fun onSessionEnded(session: AirPlaySession) {
            if (current !== stack) return
            listener.onSessionEnded(session)
            if (stack.active.remove(session)) restart(stack)
        }

        override fun onVideoFrameRendered(session: AirPlaySession) {
            if (session in stack.active) stack.rendered = true
            listener.onVideoFrameRendered(session)
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            if (session in stack.active && isBluetoothHandoffCommand(type) &&
                stack.handoffRequested.compareAndSet(false, true)
            ) {
                log("wireless CarPlay Bluetooth handoff requested; tunnelReady=${stack.tunnelReady}")
                completeHandoff(stack)
            }
            listener.onCommand(session, type, params)
        }
    }

    /** One bring-up's resources, closed in reverse order of acquisition, and its handoff state. */
    private class Stack(val parts: WirelessParts) : Closeable {
        private val owned = ArrayList<AutoCloseable>()
        @Volatile var closed = false
            private set

        val active: MutableSet<AirPlaySession> = ConcurrentHashMap.newKeySet()
        /** The iPhone reached the PC over Wi-Fi: an active AirPlay session or a ready iAP2 tunnel. */
        @Volatile var joined = false
        @Volatile var rendered = false
        @Volatile var bootstrap: Iap2Session? = null
        @Volatile var tunnelReady = false
        val handoffRequested = AtomicBoolean()
        val handedOff = AtomicBoolean()

        /** Takes [resource]; a stack closed meanwhile closes it and ends the bring-up. */
        fun <T : AutoCloseable> own(resource: T): T {
            synchronized(this) {
                if (!closed) {
                    owned += resource
                    return resource
                }
            }
            runCatching { resource.close() }
            throw StackClosed()
        }

        /** [owner] now closes [resource], as an iAP2 session closes its link's stream. */
        fun <T : AutoCloseable> handOver(resource: AutoCloseable, owner: T): T {
            synchronized(this) { owned.remove(resource) }
            return own(owner)
        }

        /** Closes [resource] ahead of the rest of the stack. */
        fun release(resource: AutoCloseable) {
            if (synchronized(this) { owned.remove(resource) }) runCatching { resource.close() }
        }

        override fun close() {
            val resources = synchronized(this) {
                closed = true
                owned.asReversed().toList().also { owned.clear() }
            }
            resources.forEach { runCatching { it.close() } }
        }
    }

    private class StackClosed : Exception()

    private class DesktopParts(private val config: DesktopConfig, private val log: (String) -> Unit) : WirelessParts {
        override val mfi = LocalMfiAuthenticationClient.load(config.mfiDirectory())

        override fun network() = SameLanNetwork(config.wifi)

        override fun radioAddress() = WindowsBluetooth.localRadioAddress()

        override fun media(sink: MediaSink, microphoneEnabled: Boolean) = CarPlayMediaEngine(sink, microphoneEnabled)

        /** DesktopBonjour leaves earlier instances registered when a later address fails. */
        override fun advertise(config: AirPlayConfig, identity: AirPlayIdentity, addresses: List<InetAddress>) =
            DesktopBonjour(config, identity, addresses, log).also {
                try {
                    it.start()
                } catch (error: Exception) {
                    it.close()
                    throw error
                }
            }

        override fun connectIphone(): BlockingDuplexByteStream {
            val iphone = selectIphone(WindowsBluetooth.pairedDevices(), config.iphoneAddress)
            log("RFCOMM connecting to ${iphone.name} ${iphone.address}")
            return WindowsBluetooth.connectRfcomm(iphone.address, IAP2_IPHONE_UUID).toDuplexStream(log)
        }

        override fun control(
            session: Iap2Session,
            identification: Iap2IdentificationConfig,
            endpoint: Iap2WirelessCarPlayEndpoint,
            timeoutMillis: Long,
            onReady: () -> Unit,
            keepAliveAfterDeadline: () -> Boolean,
            onIncoming: (Iap2Frame) -> Unit,
        ) = Iap2WirelessControlClient(session, Iap2MfiAuthenticationClient(mfi)).run(
            identification, endpoint, timeoutMillis,
            // Upstream's subscriptions have just gone out on this link; ours ask for more of the same.
            onReady = {
                onReady()
                DataSubscriptions.send({ session.send(it, SUBSCRIPTION_SEND_TIMEOUT_MILLIS) }, log)
            },
            onIncoming = onIncoming, keepAliveAfterDeadline = keepAliveAfterDeadline, onProgress = log,
        )
    }

    private companion object {
        const val NETWORK_TIMEOUT_MILLIS = 10_000L
        /**
         * Upstream CONTROL_LOOP_TIMEOUT_MILLIS (CarPlayController.kt:3143): identification, the user's
         * first-time consent on the iPhone and its Wi-Fi join must all fit in the Bluetooth bootstrap. A known
         * iPhone gets DesktopConfig.iphoneJoinSeconds instead.
         */
        const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        const val RETRY_DELAY_MILLIS = 5_000L
        /** Iap2Session's own default for a send. */
        const val SUBSCRIPTION_SEND_TIMEOUT_MILLIS = 5_000L

        fun isBluetoothHandoffCommand(type: String) =
            type.equals("disableBluetooth", ignoreCase = true) || type.equals("disable-bluetooth", ignoreCase = true)
    }
}
