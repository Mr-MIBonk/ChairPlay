package io.github.numberonebot.chairplay.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

class ConsoleCaptureTest {
    private val dir: Path = Files.createTempDirectory("chairplay-capture")
    private val log = AppLog(clock = { LocalDateTime.of(2026, 10, 25, 12, 0, 0) })
    private val savedOut = System.out
    private val savedErr = System.err

    @After
    fun restore() {
        System.setOut(savedOut)
        System.setErr(savedErr)
    }

    private fun fileLines(): List<String> {
        log.close()
        return dir.listDirectoryEntries("chairplay-*.log").single().readLines()
    }

    @Test
    fun `a line stream logs one line per newline, joins partial writes and drops the carriage return`() {
        log.open(dir)
        val stream = LineLogStream("stderr", LogLevel.WARNING, log)
        stream.write("a\r\nb".toByteArray())
        stream.write("c\n".toByteArray())

        val lines = fileLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("12:00:00.000 WARN "))
        assertTrue(lines[0], lines[0].endsWith("[stderr] a"))
        assertTrue(lines[1], lines[1].endsWith("[stderr] bc"))
    }

    @Test
    fun `a partial line waits for flush and utf-8 split across writes is decoded`() {
        log.open(dir)
        val stream = LineLogStream("stdout", LogLevel.WARNING, log)
        val bytes = "é".toByteArray()
        stream.write(bytes, 0, 1)
        stream.write(bytes, 1, 1)
        stream.flush()

        assertTrue(fileLines().single().endsWith("[stdout] é"))
    }

    // Output that never prints a newline must not grow in memory without end.
    @Test
    fun `a long line without a newline is logged in pieces of 8 KiB`() {
        log.open(dir)
        val stream = LineLogStream("stdout", LogLevel.WARNING, log)
        stream.write(ByteArray(8192 * 2 + 10) { 'a'.code.toByte() })
        stream.flush()

        val lines = fileLines().map { it.substringAfter("[stdout] ") }
        assertEquals(listOf(8192, 8192, 10), lines.map { it.length })
    }

    @Test
    fun `a piece never splits a utf-8 character`() {
        log.open(dir)
        val stream = LineLogStream("stdout", LogLevel.WARNING, log)
        stream.write(("a".repeat(8191) + "éb").toByteArray())
        stream.flush()

        val lines = fileLines().map { it.substringAfter("[stdout] ") }
        assertEquals(listOf("a".repeat(8191) + "é", "b"), lines)
    }

    @Test
    fun `captured console output is logged once and does not loop back`() {
        log.open(dir)
        captureConsole(log)
        println("hello out")
        System.err.println("hello err")

        val lines = fileLines()
        assertEquals(1, lines.count { it.endsWith("[stdout] hello out") })
        assertEquals(1, lines.count { it.endsWith("[stderr] hello err") })
        assertEquals(2, lines.size)
    }

    @Test
    fun `prints without a newline join into one logged line`() {
        log.open(dir)
        captureConsole(log)
        print("a")
        print("b\n")

        val lines = fileLines()
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("WARN ") && lines[0].endsWith("[stdout] ab"))
    }

    @Test
    fun `level changes are reported to the hook`() {
        val seen = mutableListOf<LogLevel>()
        log.onLevel = { seen += it }
        log.level = LogLevel.DEBUG

        assertEquals(listOf(LogLevel.DEBUG), seen)
    }
}
