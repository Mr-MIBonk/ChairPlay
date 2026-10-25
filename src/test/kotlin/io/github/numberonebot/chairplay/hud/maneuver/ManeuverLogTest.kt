package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.log.AppLog
import io.github.numberonebot.chairplay.log.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.LocalDateTime

class ManeuverLogTest : ManeuverRenderFixture() {
    private val console = ByteArrayOutputStream()
    private val log = AppLog(clock = { LocalDateTime.of(2026, 10, 25, 12, 0) }, console = PrintStream(console, true, Charsets.UTF_8))

    private fun lines() = console.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }

    @Test
    fun `what the renderer writes to stderr is collected in the DLL and handed over once`() {
        ManeuverNative.drainLog()
        ManeuverNative.logSelfTest("first\nsecond\n")
        val drained = ManeuverNative.drainLog()
        assertTrue(drained.toString(), drained!!.contains("first\nsecond\n"))
        assertNull(ManeuverNative.drainLog())
    }

    @Test
    fun `the buffer is bounded and keeps the newest text`() {
        ManeuverNative.drainLog()
        repeat(2000) { ManeuverNative.logSelfTest("line $it of a long noisy renderer\n") }
        val drained = ManeuverNative.drainLog()!!
        assertTrue(drained.length <= 16 * 1024)
        assertTrue(drained.endsWith("line 1999 of a long noisy renderer\n"))
        assertTrue("starts on a line boundary", drained.startsWith("line "))
    }

    @Test
    fun `a multibyte character is never split by the bound or by a long line`() {
        ManeuverNative.drainLog()
        repeat(3000) { ManeuverNative.logSelfTest("é€ line $it\n") }
        ManeuverNative.logSelfTest("€".repeat(1000) + "\n")
        val drained = ManeuverNative.drainLog()!!
        assertTrue(drained.none { it == '�' })
        assertTrue(drained.endsWith("€"))
    }

    @Test
    fun `the renderer's own stderr text, from the copied C files, reaches the log`() {
        ManeuverNative.drainLog()
        ManeuverNative.shutdown()
        try {
            assertNotEquals(0, ManeuverNative.init(WIDTH, HEIGHT))
            val drained = ManeuverNative.drainLog()
            assertNotNull(drained)
            assertTrue(drained.toString(), drained!!.lines().any { it.startsWith("render:") })
        } finally {
            ManeuverNative.loadFlagAtlas()
            ManeuverNative.drainLog()
        }
    }

    @Test
    fun `each non-blank line goes to the app log as a warning`() {
        log.level = LogLevel.WARNING
        ManeuverNative.drainLog()
        ManeuverNative.logSelfTest("one\n\ntwo\n")
        drainManeuverLog(log)
        val written = lines()
        assertEquals(2, written.size)
        assertTrue(written[0].contains("WARN") && written[0].endsWith("[native] one"))
        assertTrue(written[1].endsWith("[native] two"))
        drainManeuverLog(log)
        assertEquals(2, lines().size)
    }
}
