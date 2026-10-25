package io.github.numberonebot.chairplay

import io.github.numberonebot.chairplay.log.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

class StartFailureTest {
    private val dir: Path = Files.createTempDirectory("chairplay-start")
    private val log = AppLog(clock = { LocalDateTime.of(2026, 10, 25, 12, 0, 0) })
    private val shown = mutableListOf<String>()
    private val exits = mutableListOf<Int>()

    private fun guard(start: (windowsOpen: () -> Unit) -> Unit) = guardStart(log, { shown += it }, { exits += it }, start)

    private fun logText(): String {
        log.close()
        return dir.listDirectoryEntries("chairplay-*.log").single().readText()
    }

    // With no console, a bad setting or a missing OpenGL would end the app without a word.
    @Test
    fun `a failure before the windows open is logged with its stack, shown with the log file, and ends the process`() {
        log.open(dir)
        guard { throw IllegalArgumentException("audio.duck.db must be a whole number from -30 to 0, not loud") }

        val text = logText()
        assertTrue(text, "IllegalArgumentException" in text && "StartFailureTest" in text)
        assertEquals(1, shown.size)
        assertTrue(shown[0], "audio.duck.db must be a whole number from -30 to 0, not loud" in shown[0])
        assertTrue(shown[0], "chairplay-2026-10-25-120000.log" in shown[0])
        assertEquals(listOf(1), exits)
    }

    @Test
    fun `an error without a message of its own is shown by its cause`() {
        log.open(dir)
        guard { throw ExceptionInInitializerError(IllegalStateException("cannot initialise GLFW")) }

        assertTrue(shown.single(), "cannot initialise GLFW" in shown.single())
    }

    @Test
    fun `a failure before the log file opened is still shown`() {
        guard { throw IllegalStateException("app folder not found") }

        assertTrue(shown.single(), "app folder not found" in shown.single())
        assertFalse(shown.single(), "null" in shown.single())
        assertEquals(listOf(1), exits)
    }

    // Once the windows run, an error goes on as it did: the guard neither shows nor ends anything.
    @Test
    fun `a failure once the windows are open is passed on`() {
        log.open(dir)
        val error = IllegalStateException("lost the GL context")

        val thrown = runCatching { guard { windowsOpen -> windowsOpen(); throw error } }.exceptionOrNull()

        assertEquals(error, thrown)
        assertTrue(shown.isEmpty())
        assertTrue(exits.isEmpty())
    }

    @Test
    fun `a start that runs to its end shows nothing`() {
        log.open(dir)
        guard { windowsOpen -> windowsOpen() }

        assertTrue(shown.isEmpty())
        assertTrue(exits.isEmpty())
    }
}
