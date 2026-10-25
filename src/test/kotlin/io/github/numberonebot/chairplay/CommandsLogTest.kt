package io.github.numberonebot.chairplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

class CommandsLogTest {
    // A command's output has no console to go to: it lands in logs\ of the app folder, which need not exist yet.
    @Test
    fun `a command line that is not a command logs its usage into a fresh app folder`() {
        val app = Files.createTempDirectory("chairplay-app")
        var routed = false

        runCommand(arrayOf("bogus"), app) { routed = true }
        appLog.close()

        assertTrue(routed)
        val files = app.resolve("logs").listDirectoryEntries("chairplay-*.log")
        assertEquals(1, files.size)
        assertTrue(files.single().readLines().any { "usage" in it })
    }
}
