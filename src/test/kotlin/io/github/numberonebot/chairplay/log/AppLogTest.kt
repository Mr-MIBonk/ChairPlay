package io.github.numberonebot.chairplay.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.logging.Level
import java.util.logging.LogRecord
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.writeText

class AppLogTest {
    private val console = ByteArrayOutputStream()
    private val dir: Path = Files.createTempDirectory("chairplay-logs")
    private val time = LocalDateTime.of(2026, 10, 23, 20, 35, 36, 228_000_000)
    private val log = AppLog(clock = { time }, console = PrintStream(console, true, Charsets.UTF_8))

    private fun fileLines(): List<String> {
        log.close()
        return dir.listDirectoryEntries("chairplay-*.log").single().readLines()
    }

    private fun consoleLines() = console.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }

    // log.level: the file and the console get the lines at the level set and above it.
    @Test
    fun `lines at the level and above go to the file and the console`() {
        log.open(dir)
        log.level = LogLevel.WARNING
        log.log(LogLevel.ERROR, "the iPhone's picture stopped")
        log.log(LogLevel.WARNING, "touch dropped")
        log.log(LogLevel.INFO, "AirPlay session active")
        log.log(LogLevel.DEBUG, "a packet")

        val expected = listOf("20:35:36.228 ERROR the iPhone's picture stopped", "20:35:36.228 WARN  touch dropped")
        assertEquals(expected, fileLines())
        assertEquals(expected, consoleLines())
        assertEquals("chairplay-2026-10-23-203536.log", dir.listDirectoryEntries().single().name)
    }

    @Test
    fun `info is the level unless set`() {
        log.open(dir)
        log.log(LogLevel.INFO, "shown")
        log.log(LogLevel.DEBUG, "hidden")

        assertEquals(listOf("20:35:36.228 INFO  shown"), fileLines())
    }

    // The app folder is known only after a few lines; those lines still reach the file.
    @Test
    fun `lines before the file opens are written to it as it opens`() {
        log.log(LogLevel.INFO, "settings and keys: C:\\ChairPlayApp")
        log.open(dir)
        log.log(LogLevel.INFO, "connecting")

        assertEquals(listOf("20:35:36.228 INFO  settings and keys: C:\\ChairPlayApp", "20:35:36.228 INFO  connecting"), fileLines())
    }

    // DiPlay marks its packet-by-packet lines with TRACE; the rest of what it tells the session listener is info.
    @Test
    fun `DiPlay's TRACE lines are trace, the rest info`() {
        log.open(dir)
        log.level = LogLevel.DEBUG
        log.fromDiPlay("TRACE airplay event tx headers=POST /command RTSP/1.0")
        log.fromDiPlay("airplay session active")
        log.level = LogLevel.TRACE
        log.fromDiPlay("TRACE airplay event rx")

        assertEquals(listOf("20:35:36.228 INFO  airplay session active", "20:35:36.228 TRACE airplay event rx"), fileLines())
    }

    // DiPlay's android.util.Log calls reach java.util.logging through the shim: they come in at their own level, with
    // the error's stack, and a line DiPlay also tells the session listener is written once.
    @Test
    fun `DiPlay's java logging comes in at its level, once`() {
        log.open(dir)
        log.level = LogLevel.DEBUG
        val handler = log.javaLoggingHandler()
        handler.publish(LogRecord(Level.FINE, "a detail"))
        handler.publish(LogRecord(Level.SEVERE, "airplay event read failed").apply { thrown = IllegalStateException("socket closed") })
        handler.publish(LogRecord(Level.INFO, "airplay command type=requestUI keys=[]"))
        log.fromDiPlay("airplay command type=requestUI keys=[]")
        log.fromDiPlay("airplay command type=requestUI keys=[]")
        handler.publish(LogRecord(Level.FINEST, "below debug"))

        val lines = fileLines()
        assertEquals("20:35:36.228 DEBUG a detail", lines[0])
        assertEquals("20:35:36.228 ERROR airplay event read failed", lines[1])
        assertEquals("java.lang.IllegalStateException: socket closed", lines[2])
        assertTrue(lines[3], lines[3].trimStart().startsWith("at "))
        // Once from java.util.logging; the listener's copy right after it is dropped, a second line of the same text is not.
        assertEquals(2, lines.count { it == "20:35:36.228 INFO  airplay command type=requestUI keys=[]" })
        assertTrue(lines.none { "below debug" in it })
    }

    // logs\ keeps the ten newest app logs; the CarPlay data logs beside them are not touched.
    @Test
    fun `only the ten newest logs are kept`() {
        for (day in 1..12) dir.resolve("chairplay-2026-10-%02d-120000.log".format(day)).writeText("old\n")
        dir.resolve("carplay-data-2026-10-01-120000.jsonl").writeText("{}\n")

        log.open(dir)
        log.close()

        val names = dir.listDirectoryEntries().map { it.name }.sorted()
        assertEquals(11, names.size)
        assertTrue("carplay-data-2026-10-01-120000.jsonl" in names)
        assertTrue("chairplay-2026-10-23-203536.log" in names)
        assertTrue("chairplay-2026-10-03-120000.log" !in names)
        assertTrue("chairplay-2026-10-04-120000.log" in names)
    }

    @Test
    fun `log levels are read from the settings' words`() {
        assertEquals(LogLevel.TRACE, LogLevel.of("trace"))
        assertEquals(LogLevel.WARNING, LogLevel.of(" Warning "))
        assertEquals(null, LogLevel.of("verbose"))
    }
}
