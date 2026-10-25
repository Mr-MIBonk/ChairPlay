package io.github.numberonebot.chairplay.media

import io.github.numberonebot.chairplay.log.AppLog
import io.github.numberonebot.chairplay.log.LogLevel
import org.bytedeco.ffmpeg.global.avutil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.LocalDateTime

class FfmpegLogTest {
    private val savedLevel = avutil.av_log_get_level()

    @After
    fun restoreFfmpegLevel() = avutil.av_log_set_level(savedLevel)

    @Test
    fun `ffmpeg errors and below are warnings, warnings are debug, the rest trace`() {
        assertEquals(LogLevel.WARNING, ffmpegLevel(avutil.AV_LOG_PANIC))
        assertEquals(LogLevel.WARNING, ffmpegLevel(avutil.AV_LOG_ERROR))
        assertEquals(LogLevel.DEBUG, ffmpegLevel(avutil.AV_LOG_WARNING))
        assertEquals(LogLevel.TRACE, ffmpegLevel(avutil.AV_LOG_INFO))
        assertEquals(LogLevel.TRACE, ffmpegLevel(avutil.AV_LOG_VERBOSE))
    }

    @Test
    fun `each app level asks ffmpeg for what it would show`() {
        assertEquals(avutil.AV_LOG_VERBOSE, avLevelFor(LogLevel.TRACE))
        assertEquals(avutil.AV_LOG_WARNING, avLevelFor(LogLevel.DEBUG))
        assertEquals(avutil.AV_LOG_ERROR, avLevelFor(LogLevel.INFO))
        assertEquals(avutil.AV_LOG_ERROR, avLevelFor(LogLevel.WARNING))
        assertEquals(avutil.AV_LOG_ERROR, avLevelFor(LogLevel.ERROR))
    }

    @Test
    fun `an ffmpeg message lands in the log without its newline`() {
        val console = ByteArrayOutputStream()
        val log = AppLog(clock = { LocalDateTime.of(2026, 10, 25, 12, 0) }, console = PrintStream(console, true, Charsets.UTF_8))
        log.level = LogLevel.DEBUG
        FfmpegLog.install(log)
        FfmpegLog.level(LogLevel.DEBUG)

        avutil.av_log(null, avutil.AV_LOG_WARNING, "probe\n")

        val lines = console.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        assertEquals(listOf("12:00:00.000 DEBUG [ffmpeg] probe"), lines)
    }
}
