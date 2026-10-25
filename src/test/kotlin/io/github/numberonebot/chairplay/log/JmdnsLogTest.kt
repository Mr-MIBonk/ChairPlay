package io.github.numberonebot.chairplay.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.LoggerFactory
import org.slf4j.helpers.NOPLoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.util.logging.LogManager

class JmdnsLogTest {
    private val console = ByteArrayOutputStream()
    private val log = AppLog(clock = { LocalDateTime.of(2026, 10, 25, 12, 0) }, console = PrintStream(console, true, Charsets.UTF_8))

    @After
    fun resetJavaLogging() = LogManager.getLogManager().reset()

    private fun lines() = console.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }

    // JmDNS logs through SLF4J, which drops everything without a provider.
    @Test
    fun `slf4j has a provider`() {
        assertFalse(LoggerFactory.getILoggerFactory() is NOPLoggerFactory)
    }

    @Test
    fun `a jmdns warning reaches the log tagged jmdns`() {
        log.install()
        LoggerFactory.getLogger("javax.jmdns.impl.JmDNSImpl").warn("probe")
        val line = lines().single()
        assertTrue(line, line.contains("WARN "))
        assertTrue(line, line.endsWith("[jmdns] probe"))
    }

    @Test
    fun `jmdns debug lines follow the log level`() {
        log.install()
        log.level = LogLevel.INFO
        LoggerFactory.getLogger("javax.jmdns.impl.JmDNSImpl").debug("quiet")
        assertEquals(emptyList<String>(), lines())
        log.level = LogLevel.DEBUG
        LoggerFactory.getLogger("javax.jmdns.impl.JmDNSImpl").debug("loud")
        val line = lines().single()
        assertTrue(line, line.contains("DEBUG"))
        assertTrue(line, line.endsWith("[jmdns] loud"))
    }
}
