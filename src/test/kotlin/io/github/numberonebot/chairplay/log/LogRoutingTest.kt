package io.github.numberonebot.chairplay.log

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

class LogRoutingTest {
    // FFmpeg's natives load as its log is routed: a missing or broken DLL must reach the log and let the app go on.
    @Test
    fun `an FFmpeg log that cannot be routed is logged and the start goes on`() {
        val dir = Files.createTempDirectory("chairplay-routing")
        val log = AppLog(clock = { LocalDateTime.of(2026, 10, 25, 12, 0, 0) })
        log.open(dir)

        routeFfmpegLog(log) { throw UnsatisfiedLinkError("no jniavutil in java.library.path") }
        log.close()

        val lines = dir.listDirectoryEntries("chairplay-*.log").single().readLines()
        assertTrue("$lines", lines.any { "no jniavutil in java.library.path" in it })
    }
}
