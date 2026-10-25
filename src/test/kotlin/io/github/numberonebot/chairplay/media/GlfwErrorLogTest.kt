package io.github.numberonebot.chairplay.media

import io.github.numberonebot.chairplay.log.AppLog
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lwjgl.system.MemoryUtil
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

class GlfwErrorLogTest {
    @Test
    fun `a GLFW error becomes a warning line with its description`() {
        val dir = Files.createTempDirectory("chairplay-glfw")
        val log = AppLog(clock = { LocalDateTime.of(2026, 10, 25, 12, 0, 0) })
        log.open(dir)
        val text = MemoryUtil.memUTF8("boom")
        try {
            glfwErrorCallback(log).invoke(0x10008, MemoryUtil.memAddress(text))
        } finally {
            MemoryUtil.memFree(text)
        }
        log.close()

        val line = dir.listDirectoryEntries("chairplay-*.log").single().readLines().single()
        assertTrue(line, "WARN " in line && "[glfw]" in line && "boom" in line)
    }
}
