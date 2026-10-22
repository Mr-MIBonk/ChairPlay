package io.github.numberonebot.chairplay.session

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.MediaSink
import io.github.numberonebot.chairplay.config.DesktopConfig
import io.github.numberonebot.chairplay.config.writeTestSettings
import io.github.numberonebot.chairplay.data.CarPlayDataTap
import io.github.numberonebot.chairplay.data.DataLink
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlResult
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WirelessBootstrapTest {
    private val config = AirPlayConfig(
        deviceName = "ChairPlay",
        deviceId = "02:11:22:33:44:55",
        btMac = "02:11:22:33:44:55",
        sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
    )
    private val identity = AirPlayIdentity(
        privateKey = ByteArray(32),
        publicKey = byteArrayOf(0x01, 0x23, 0xab.toByte()),
        pairingId = "pairing-1",
    )

    // The iPhone lists the accessory under the iAP2 identification name.
    @Test
    fun `identifies as ChairPlay`() {
        assertEquals("ChairPlay", desktopIdentification("10:5A:95:75:B2:AA").name)
        assertEquals("Гараж", desktopIdentification("10:5A:95:75:B2:AA", "Гараж").name)
    }

    @Test
    fun `endpoint carries listener port and address`() {
        val host = InetAddress.getByName("192.168.1.20")

        val endpoint = buildEndpoint(info(listOf(host)), host, 7001, config, identity)

        assertEquals(listOf("192.168.1.20"), endpoint.ipAddresses)
        assertEquals(7001, endpoint.airPlayPort)
        assertEquals(identity.publicKeyHex, endpoint.publicKey)
        assertEquals(36, endpoint.channel)
        assertEquals("Home", endpoint.ssid)
    }

    @Test
    fun `link-local IPv6 endpoint drops scope`() {
        val host = InetAddress.getByName("fe80::1%12")

        val endpoint = buildEndpoint(info(listOf(host)), host, 7000, config, identity)

        assertEquals(listOf("fe80::1"), endpoint.ipAddresses)
    }

    @Test
    fun `firewall hint names the port`() {
        val hint = firewallHint(7000, "C:\\x\\chairplay.exe")

        assertTrue(hint, "localport=7000" in hint)
        assertTrue(hint, "C:\\x\\chairplay.exe" in hint)
        assertTrue(hint, "localport=7000-7010,49152-65535" in hint)
        assertTrue(hint, "protocol=UDP localport=5353" in hint)
        // The start-up check looks for this name; Windows names its own prompt rules after the exe.
        assertTrue(hint, "name=\"ChairPlay CarPlay inbound\" dir=in" in hint)
        assertEquals(3, Regex("name=\"$FIREWALL_RULE\"").findAll(hint).count())
    }

    // A new iPhone needs time for the driver to accept CarPlay on it; one that paired before only has to join
    // the Wi-Fi, so a phone that lost the Wi-Fi meanwhile gets the network again after seconds, not minutes.
    @Test(timeout = 30_000)
    fun `the Bluetooth bootstrap waits minutes for a new iPhone and seconds for a known one`() {
        Harness().use { harness ->
            assertEquals(5 * 60_000L, harness.parts.take().bootstrapTimeout.poll(10, TimeUnit.SECONDS))
        }
        Harness(knownIphone = true).use { harness ->
            assertEquals(30_000L, harness.parts.take().bootstrapTimeout.poll(10, TimeUnit.SECONDS))
        }
        Harness(knownIphone = true, settings = "iphone.join.seconds=20").use { harness ->
            assertEquals(20_000L, harness.parts.take().bootstrapTimeout.poll(10, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 30_000)
    fun `ending an inactive session does not restart`() {
        Harness().use { harness ->
            val port = harness.nextPort()
            // A /info probe or a pairing retry: connects, asks, leaves without RECORD.
            RtspClient(harness.loopback, port).use { assertOk(it.request("OPTIONS", "*")) }
            Thread.sleep(500)

            assertEquals(1, harness.stacks.get())
            RtspClient(harness.loopback, port).use { assertOk(it.request("OPTIONS", "*")) }
        }
    }

    @Test(timeout = 30_000)
    fun `ending an active session restarts once`() {
        Harness().use { harness ->
            val port = harness.nextPort()
            // Both sessions go active before either ends, so both ends come from the same stack.
            val clients = List(2) { RtspClient(harness.loopback, port).also { assertOk(it.request("RECORD", "*")) } }

            clients[0].close()
            harness.nextPort()
            clients[1].close()
            Thread.sleep(500)

            assertEquals(2, harness.stacks.get())
            assertTrue("the replaced listener's port $port is still bound", harness.previousPortFree.get())
        }
    }

    @Test(timeout = 30_000)
    fun `Bluetooth loss after joining keeps the Wi-Fi session`() {
        Harness().use { harness ->
            val port = harness.nextPort()
            RtspClient(harness.loopback, port).use { client ->
                assertOk(client.request("RECORD", "*"))
                val parts = harness.parts.last
                awaitTrue { parts.tunnelHandler.get() != null }

                parts.link.endFromPeer()
                Thread.sleep(500)

                assertEquals(1, harness.stacks.get())
                assertOk(client.request("OPTIONS", "*"))
            }
        }
    }

    @Test(timeout = 30_000)
    fun `handoff closes the Bluetooth bootstrap once and keeps the Wi-Fi session`() {
        Harness().use { harness ->
            val port = harness.nextPort()
            RtspClient(harness.loopback, port).use { client ->
                assertOk(client.request("RECORD", "*"))
                val parts = harness.parts.last
                awaitTrue { parts.tunnelHandler.get() != null }
                val tunnel = SilentStream()
                assertTrue(parts.tunnelHandler.get()!!(tunnel))
                awaitTrue { parts.readyRuns.get() == 1 }

                val disableBluetooth = BplistCodec.encode(mapOf("type" to "disableBluetooth", "params" to emptyMap<String, Any?>()))
                assertOk(client.request("POST", "/command", disableBluetooth))
                awaitTrue { parts.link.closes.get() > 0 }
                assertOk(client.request("POST", "/command", disableBluetooth))
                Thread.sleep(300)

                assertEquals(1, parts.link.closes.get())
                assertEquals(0, tunnel.closes.get())
                assertEquals(1, harness.stacks.get())
                assertOk(client.request("OPTIONS", "*"))
            }
        }
    }

    @Test(timeout = 30_000)
    fun `data frames from both iAP2 links reach the data tap`() {
        val tap = RecordingTap()
        Harness(tap).use { harness ->
            val port = harness.nextPort()
            RtspClient(harness.loopback, port).use { client ->
                assertOk(client.request("RECORD", "*"))
                val parts = harness.parts.last
                awaitTrue { parts.tunnelHandler.get() != null }
                assertTrue(parts.tunnelHandler.get()!!(SilentStream()))
                awaitTrue { tap.frames.size >= 2 }

                assertEquals(setOf(DataLink.BLUETOOTH to POWER_UPDATE, DataLink.WIFI to POWER_UPDATE), tap.frames.toSet())
            }
        }
    }

    @Test(timeout = 30_000)
    fun `the end of an active session clears the data`() {
        val tap = RecordingTap()
        Harness(tap).use { harness ->
            val port = harness.nextPort()
            RtspClient(harness.loopback, port).use { assertOk(it.request("RECORD", "*")) }
            harness.nextPort()

            assertEquals(1, tap.ends.get())
        }
    }

    // The start screen tells the driver what the app is waiting for.
    @Test(timeout = 30_000)
    fun `the bring-up reports its progress`() {
        Harness().use { harness ->
            harness.nextPort()

            assertEquals(BringUpStatus.Starting, harness.statuses.poll(10, TimeUnit.SECONDS))
            assertEquals(BringUpStatus.CallingIphone, harness.statuses.poll(10, TimeUnit.SECONDS))
            assertEquals(BringUpStatus.Joining(knownIphone = false), harness.statuses.poll(10, TimeUnit.SECONDS))
        }
    }

    // Missing MFi files stopped the start in the caller's thread, so the reason reached only the console.
    @Test(timeout = 30_000)
    fun `parts that cannot be made fail the bring-up with the reason`() {
        val dir = Files.createTempDirectory("chairplay-bootstrap")
        writeTestSettings(dir)
        val statuses = LinkedBlockingQueue<BringUpStatus>()
        val listener = object : AirPlaySessionListener {}
        WirelessBootstrap(DesktopConfig(dir), object : MediaSink {}, listener, CarPlayDataTap.NONE) {
            throw IllegalArgumentException("Offline MFi directory is missing or invalid")
        }.use { bootstrap ->
            bootstrap.onStatus = statuses::add

            bootstrap.start()

            assertEquals(BringUpStatus.Starting, statuses.poll(10, TimeUnit.SECONDS))
            val failed = statuses.poll(10, TimeUnit.SECONDS)
            assertEquals(BringUpStatus.Failed("Offline MFi directory is missing or invalid", 5_000L), failed)
        }
    }

    private class RecordingTap : CarPlayDataTap {
        val frames = LinkedBlockingQueue<Pair<DataLink, Int>>()
        val ends = AtomicInteger()
        override fun onFrame(link: DataLink, frame: Iap2Frame) {
            frames += link to frame.messageId
        }
        override fun onSessionEnded() {
            ends.incrementAndGet()
        }
    }

    /** A bootstrap over fakes on 127.0.0.1; each stack's parts and advertised port are recorded. */
    private inner class Harness(
        tap: CarPlayDataTap = CarPlayDataTap.NONE,
        knownIphone: Boolean = false,
        settings: String = "",
    ) : Closeable {
        val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
        val stacks = AtomicInteger()
        val parts = LinkedBlockingDeque<FakeParts>()
        val previousPortFree = AtomicBoolean()
        val statuses = LinkedBlockingQueue<BringUpStatus>()
        private val previousPort = AtomicInteger()
        private val advertisedPorts = LinkedBlockingQueue<Int>()
        private val bootstrap = Files.createTempDirectory("chairplay-bootstrap").let { dir ->
            writeTestSettings(dir)
            if (settings.isNotEmpty()) Files.writeString(dir.resolve("settings.properties"), "\n$settings\n", StandardOpenOption.APPEND)
            // An AirPlay pairing record, as the first session with this iPhone leaves.
            if (knownIphone) Files.createDirectories(dir.resolve("pairings")).resolve("6970686f6e65").let { Files.writeString(it, "00") }
            WirelessBootstrap(DesktopConfig(dir), object : MediaSink {}, object : AirPlaySessionListener {}, tap) {
                if (stacks.incrementAndGet() > 1) previousPortFree.set(bindable(loopback, previousPort.get()))
                FakeParts(info(listOf(loopback))) { port ->
                    previousPort.set(port)
                    advertisedPorts.add(port)
                }.also(parts::add)
            }.also { it.onStatus = statuses::add }
        }

        init {
            bootstrap.start()
        }

        fun nextPort(): Int = advertisedPorts.poll(10, TimeUnit.SECONDS)!!

        override fun close() = bootstrap.close()
    }

    private fun info(addresses: List<InetAddress>) = WirelessHotspotInfo(
        ssid = "Home",
        passphrase = "secret123",
        security = Iap2WirelessSecurity.WPA_WPA2,
        channel = 36,
        frequencyMHz = 5180,
        bssid = null,
        interfaceName = "wlan0",
        hostAddress = addresses.first(),
        bandLabel = "5GHz",
        backend = WirelessHotspotBackend.EXISTING_WIFI,
        hostAddresses = addresses,
    )

    private fun bindable(address: InetAddress, port: Int): Boolean = try {
        ServerSocket().use { it.bind(InetSocketAddress(address, port)) }
        true
    } catch (_: IOException) {
        false
    }

    private fun assertOk(status: String) = assertTrue(status, status.startsWith("RTSP/1.0 200"))

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in 10 s" }
            Thread.sleep(10)
        }
    }

    /** Plain RTSP as an iPhone sends it before pairing; reads each response whole. */
    private class RtspClient(address: InetAddress, port: Int) : Closeable {
        private val socket = Socket(address, port)
        private val input = socket.getInputStream()
        private var cseq = 0

        fun request(method: String, path: String, body: ByteArray = ByteArray(0)): String {
            val head = "$method $path RTSP/1.0\r\nCSeq: ${++cseq}\r\nContent-Length: ${body.size}\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray() + body)
                flush()
            }
            val lines = generateSequence { readLine() }.takeWhile { it.isNotEmpty() }.toList()
            val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toInt() ?: 0
            input.readNBytes(length)
            return lines.first()
        }

        private fun readLine(): String {
            val line = StringBuilder()
            while (true) {
                val byte = input.read()
                check(byte >= 0) { "connection closed" }
                if (byte == '\n'.code) return line.toString().removeSuffix("\r")
                line.append(byte.toChar())
            }
        }

        override fun close() = socket.close()
    }

    /** No Wi-Fi, Bluetooth or MFi. Each iAP2 run reports ready, then lasts as long as its link. */
    private class FakeParts(
        private val info: WirelessHotspotInfo,
        private val onAdvertise: (Int) -> Unit,
    ) : WirelessParts {
        val link = SilentStream()
        val tunnelHandler = AtomicReference<((BlockingDuplexByteStream) -> Boolean)?>()
        val readyRuns = AtomicInteger()
        val bootstrapTimeout = LinkedBlockingQueue<Long>()
        override val mfi: MfiAuthenticator? = null

        override fun network() = object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long) = info
            override fun close() {}
        }

        override fun radioAddress() = "02:00:00:00:00:01"

        override fun media(sink: MediaSink, microphoneEnabled: Boolean): AirPlayMediaHandler {
            val engine = CarPlayMediaEngine(sink, microphoneEnabled)
            return object : AirPlayMediaHandler by engine {
                override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
                    tunnelHandler.set(handler)
                    engine.setIapTunnelHandler(handler)
                }
            }
        }

        override fun advertise(config: AirPlayConfig, identity: AirPlayIdentity, addresses: List<InetAddress>): Closeable {
            onAdvertise(config.port)
            return Closeable {}
        }

        override fun connectIphone(): BlockingDuplexByteStream = link

        override fun control(
            session: Iap2Session,
            identification: Iap2IdentificationConfig,
            endpoint: Iap2WirelessCarPlayEndpoint,
            timeoutMillis: Long,
            onReady: () -> Unit,
            keepAliveAfterDeadline: () -> Boolean,
            onIncoming: (Iap2Frame) -> Unit,
        ): Iap2WirelessControlResult {
            if (timeoutMillis != Iap2WirelessControlClient.NO_TIMEOUT_MILLIS) bootstrapTimeout.add(timeoutMillis)
            onIncoming(Iap2Frame(POWER_UPDATE, ByteArray(0)))
            // Only the tunnel runs without a deadline.
            if (timeoutMillis == Iap2WirelessControlClient.NO_TIMEOUT_MILLIS) {
                readyRuns.incrementAndGet()
                onReady()
            }
            // Like the real client, a dead link ends the run with an exception.
            while (!session.isClosed) session.recv(50)
            throw IOException("iAP2 link ended")
        }
    }

    private companion object {
        const val POWER_UPDATE = 0xae01
    }

    /** Never speaks; [endFromPeer] is the iPhone dropping the link, [close] is ours. */
    private class SilentStream : BlockingDuplexByteStream {
        val closes = AtomicInteger()
        private val ended = CountDownLatch(1)
        fun endFromPeer() = ended.countDown()
        override fun send(data: ByteArray) {}
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            if (ended.await(timeoutMillis.coerceAtMost(100), TimeUnit.MILLISECONDS)) ByteArray(0) else null
        override fun close() {
            closes.incrementAndGet()
            ended.countDown()
        }
    }
}
