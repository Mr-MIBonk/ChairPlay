package io.github.numberonebot.chairplay.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class UiServerTest {
    private val api = FakeApi()
    private val assets = mapOf(
        "index.html" to "<!doctype html><title>ChairPlay</title>",
        "assets/app.js" to "console.log(1)",
        "assets/Inter-Regular-abc123.ttf" to "font",
    )
    private val server = UiServer(api, { assets[it]?.toByteArray() }, token = "t0ken").also { it.start() }
    private val client = HttpClient.newHttpClient()
    private val base = "http://127.0.0.1:${server.port}"

    @After
    fun close() = server.close()

    private fun get(path: String, token: String? = "t0ken"): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("$base$path")).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String, type: String = "application/json"): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("$base$path"))
            .header("Authorization", "Bearer t0ken").header("Content-Type", type)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `the page's address carries the token`() {
        assertEquals("http://127.0.0.1:${server.port}/?token=t0ken", server.url)
    }

    @Test
    fun `the API needs the token`() {
        assertEquals(401, get("/api/state", token = null).statusCode())
        assertEquals(401, get("/api/state", token = "wrong").statusCode())
        assertEquals(200, get("/api/state").statusCode())
        assertEquals(200, get("/api/state?token=t0ken", token = null).statusCode())
    }

    // Another site's page reaching 127.0.0.1 through a name of its own (DNS rebinding) sends its own Host.
    @Test
    fun `requests for another host are refused`() {
        Socket("127.0.0.1", server.port).use { socket ->
            socket.getOutputStream().write("GET /api/state?token=t0ken HTTP/1.1\r\nHost: evil.example:${server.port}\r\nConnection: close\r\n\r\n".toByteArray())
            val status = socket.getInputStream().bufferedReader().readLine()
            assertTrue(status, status.startsWith("HTTP/1.1 403"))
        }
    }

    @Test
    fun `the state comes from the app as JSON`() {
        val response = get("/api/state")

        assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").get())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").get())
        assertEquals(mapOf("version" to 0L, "phase" to "idle"), Json.parse(response.body()))
    }

    @Test
    fun `settings are saved or refused with the reasons`() {
        val saved = post("/api/settings", """{"changes":{"window.width":"1600"}}""")
        assertEquals(200, saved.statusCode())
        assertEquals(mapOf("window.width" to "1600"), api.saved)
        assertEquals(mapOf("saved" to listOf("window.width"), "applies" to mapOf("window.width" to "restart")), Json.parse(saved.body()))

        val refused = post("/api/settings", """{"changes":{"window.width":"x"}}""")
        assertEquals(400, refused.statusCode())
        assertEquals(mapOf("errors" to mapOf("window.width" to "bad")), Json.parse(refused.body()))
    }

    @Test
    fun `malformed requests are refused`() {
        assertEquals(400, post("/api/settings", "{").statusCode())
        assertEquals(400, post("/api/settings", """{"changes":{"a":1}}""").statusCode())
        assertEquals(415, post("/api/settings", """{"changes":{}}""", type = "text/plain").statusCode())
        assertEquals(405, get("/api/connect").statusCode())
        assertEquals(404, get("/api/nothing").statusCode())
    }

    @Test
    fun `actions reach the app`() {
        assertEquals(200, post("/api/connect", "{}").statusCode())
        assertEquals(200, post("/api/reconnect", "{}").statusCode())
        assertEquals(200, post("/api/screen", """{"screen":"settings"}""").statusCode())
        assertEquals(409, post("/api/screen", """{"screen":"carplay"}""").statusCode())
        assertEquals(listOf("connect", "reconnect", "show settings", "show carplay"), api.calls)
        assertEquals(200, post("/api/disconnect", "{}").statusCode())
        assertEquals("disconnect", api.calls.removeLast())

        // The settings screen's window triggers.
        assertEquals(200, post("/api/window", """{"name":"controls","visible":false}""").statusCode())
        assertEquals(404, post("/api/window", """{"name":"altscreen","visible":true}""").statusCode())
        assertEquals(400, post("/api/window", """{"name":"controls"}""").statusCode())
        assertEquals(listOf("window controls false", "window altscreen true"), api.calls.drop(4))

        // The controls window's buttons, pressed and released, and its AltScreen modes.
        assertEquals(200, post("/api/control", """{"action":"knob.select","down":true}""").statusCode())
        assertEquals(200, post("/api/control", """{"action":"knob.select","down":false}""").statusCode())
        assertEquals(404, post("/api/control", """{"action":"eject","down":true}""").statusCode())
        assertEquals(400, post("/api/control", """{"action":"knob.select"}""").statusCode())
        assertEquals(200, post("/api/altscreen", """{"content":"hud"}""").statusCode())
        assertEquals(409, post("/api/altscreen", """{"content":"radar"}""").statusCode())
        assertEquals(400, post("/api/altscreen", "{}").statusCode())
        assertEquals(
            listOf("control knob.select true", "control knob.select false", "control eject true", "altscreen hud", "altscreen radar"),
            api.calls.drop(6),
        )

        // A link to a Windows page.
        assertEquals(200, post("/api/open", """{"place":"bluetooth"}""").statusCode())
        assertEquals(404, post("/api/open", """{"place":"cmd.exe"}""").statusCode())
        assertEquals(400, post("/api/open", "{}").statusCode())
        assertEquals(listOf("open bluetooth", "open cmd.exe"), api.calls.drop(11))

        api.connectError = "Enter the Wi-Fi network first"
        val refused = post("/api/connect", "{}")
        assertEquals(409, refused.statusCode())
        assertEquals(mapOf("error" to "Enter the Wi-Fi network first"), Json.parse(refused.body()))
    }

    @Test
    fun `the built page is served without the token`() {
        val index = get("/", token = null)
        assertEquals(200, index.statusCode())
        assertEquals("text/html; charset=utf-8", index.headers().firstValue("Content-Type").get())
        assertTrue(index.headers().firstValue("Content-Security-Policy").isPresent)

        val script = get("/assets/app.js", token = null)
        assertEquals("text/javascript; charset=utf-8", script.headers().firstValue("Content-Type").get())
        assertEquals("console.log(1)", script.body())
        assertEquals("font/ttf", get("/assets/Inter-Regular-abc123.ttf", token = null).headers().firstValue("Content-Type").get())

        // The page's own routes fall back to it; a missing file does not.
        assertEquals(index.body(), get("/settings", token = null).body())
        assertEquals(404, get("/assets/missing.js", token = null).statusCode())
        assertEquals(404, get("/%2e%2e/secret.txt", token = null).statusCode())
    }

    @Test(timeout = 20_000)
    fun `events carry the state and each change`() {
        val request = HttpRequest.newBuilder(URI("$base/api/events?token=t0ken")).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofLines())
        assertEquals("text/event-stream; charset=utf-8", response.headers().firstValue("Content-Type").get())
        val lines = response.body().iterator()

        assertEquals("data: {\"version\":0,\"phase\":\"idle\"}", lines.next())
        api.change("active")
        val next = generateSequence { lines.next() }.first { it.startsWith("data: ") }
        assertEquals("data: {\"version\":1,\"phase\":\"active\"}", next)
    }

    private class FakeApi : UiApi {
        private val lock = Object()
        private var phase = "idle"
        @Volatile override var version = 0L
        var saved: Map<String, String>? = null
        var connectError: String? = null
        val calls = mutableListOf<String>()

        fun change(newPhase: String) = synchronized(lock) {
            phase = newPhase
            version++
            lock.notifyAll()
        }

        override fun state(): Map<String, Any?> = synchronized(lock) { linkedMapOf("version" to version, "phase" to phase) }
        override fun settings(): Map<String, Any?> = mapOf("values" to emptyMap<String, String>())
        override fun saveSettings(changes: Map<String, String>): SaveResult {
            if (changes["window.width"] == "x") return SaveResult(mapOf("window.width" to "bad"), emptyMap())
            saved = changes
            return SaveResult(emptyMap(), mapOf("window.width" to "restart"))
        }
        override fun connect(): String? = connectError ?: null.also { calls += "connect" }
        override fun reconnect() {
            calls += "reconnect"
        }
        override fun disconnect() {
            calls += "disconnect"
        }
        override fun show(screen: String): Boolean {
            calls += "show $screen"
            return screen != "carplay"
        }
        override fun window(name: String, visible: Boolean): Boolean {
            calls += "window $name $visible"
            return name == "controls"
        }
        override fun control(action: String, down: Boolean): Boolean {
            calls += "control $action $down"
            return action != "eject"
        }
        override fun altscreenContent(content: String): Boolean {
            calls += "altscreen $content"
            return content == "hud"
        }
        override fun open(place: String): Boolean {
            calls += "open $place"
            return place == "bluetooth"
        }
        override fun awaitChange(seen: Long, timeoutMillis: Long): Long = synchronized(lock) {
            if (version == seen) lock.wait(timeoutMillis)
            version
        }
    }
}
