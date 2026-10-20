package com.shilapi.xcertplay.desktop.session

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.desktop.config.DesktopConfig
import com.shilapi.xcertplay.desktop.config.writeTestSettings
import com.shilapi.xcertplay.iap2.session.Iap2Session
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WirelessBootstrapTest {
    private val config = AirPlayConfig(
        deviceName = "DiPlay",
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
        val hint = firewallHint(7000, "C:\\x\\diplay.exe")

        assertTrue(hint, "localport=7000" in hint)
        assertTrue(hint, "C:\\x\\diplay.exe" in hint)
        assertTrue(hint, "localport=7000-7010,49152-65535" in hint)
        assertTrue(hint, "protocol=UDP localport=5353" in hint)
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

    /** A bootstrap over fakes on 127.0.0.1; each stack's parts and advertised port are recorded. */
    private inner class Harness : Closeable {
        val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
        val stacks = AtomicInteger()
        val parts = LinkedBlockingDeque<FakeParts>()
        val previousPortFree = AtomicBoolean()
        private val previousPort = AtomicInteger()
        private val advertisedPorts = LinkedBlockingQueue<Int>()
        private val bootstrap = Files.createTempDirectory("diplay-bootstrap").let { dir ->
            writeTestSettings(dir)
            WirelessBootstrap(DesktopConfig(dir), object : MediaSink {}, object : AirPlaySessionListener {}) {
                if (stacks.incrementAndGet() > 1) previousPortFree.set(bindable(loopback, previousPort.get()))
                FakeParts(info(listOf(loopback))) { port ->
                    previousPort.set(port)
                    advertisedPorts.add(port)
                }.also(parts::add)
            }
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
        ): Iap2WirelessControlResult {
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
