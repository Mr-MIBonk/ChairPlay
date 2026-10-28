package com.shilapi.xcertplay.airplay

import io.github.numberonebot.chairplay.input.HandwritingHid
import io.github.numberonebot.chairplay.input.KeyboardHid
import java.io.ByteArrayOutputStream
import java.net.Socket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The desktop keyboard redaction must survive upstream's lazy trace conversion. */
class DesktopTraceTest {
    @Test fun `keyboard and character reports are sent without dumping their contents`() {
        for (uuid in listOf(KeyboardHid.UUID, HandwritingHid.UUID)) {
            val logs = mutableListOf<String>()
            val output = ByteArrayOutputStream()
            session(logs, output) { true }.use { session ->
                assertTrue(session.sendCommand(mapOf(
                    "type" to "hidSendReport", "uuid" to uuid, "report" to byteArrayOf(4, 5, 6),
                )))
                assertTrue(output.size() > 0)
                assertFalse(logs.any { it.startsWith("TRACE ") })
            }
        }
    }

    @Test fun `trace can be enabled and disabled during a session`() {
        val logs = mutableListOf<String>()
        var trace = false
        session(logs, ByteArrayOutputStream()) { trace }.use { session ->
            val command = mapOf("type" to "requestUI")
            assertTrue(session.sendCommand(command))
            assertFalse(logs.any { it.startsWith("TRACE ") })
            trace = true
            assertTrue(session.sendCommand(command))
            assertTrue(logs.any { it.startsWith("TRACE ") })
            logs.clear()
            trace = false
            assertTrue(session.sendCommand(command))
            assertFalse(logs.any { it.startsWith("TRACE ") })
        }
    }

    private fun session(logs: MutableList<String>, output: ByteArrayOutputStream, trace: () -> Boolean): AirPlaySession {
        val listener = object : AirPlaySessionListener {
            override val wantsTrace: Boolean get() = trace()
            override fun onDebugLog(message: String) { logs += message }
        }
        return AirPlaySession(
            Socket(),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
                main = AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null, listener, object : AirPlayMediaHandler {},
        ).also { session ->
            val socket = object : Socket() { override fun getOutputStream() = output }
            for ((name, value) in mapOf("eventSocket" to socket,
                "eventCipher" to ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 }))) {
                AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
        }
    }
}
