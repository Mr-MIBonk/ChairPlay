package com.shilapi.xcertplay.desktop.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import java.time.LocalDateTime

class ScreenshotTest {
    // glReadPixels returns the bottom row first; the PNG starts at the top.
    @Test
    fun `read pixels become an upright picture without alpha`() {
        val rgba = ByteBuffer.allocateDirect(2 * 2 * 4)
        // Bottom row: red, green. Top row: blue, white.
        for (pixel in listOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF)) {
            rgba.put((pixel shr 16).toByte()).put((pixel shr 8).toByte()).put(pixel.toByte()).put(0x7F)
        }
        rgba.flip()

        val image = screenshotImage(2, 2, rgba)

        assertEquals(0x0000FF, image.getRGB(0, 0) and 0xFFFFFF)
        assertEquals(0xFFFFFF, image.getRGB(1, 0) and 0xFFFFFF)
        assertEquals(0xFF0000, image.getRGB(0, 1) and 0xFFFFFF)
        assertEquals(0x00FF00, image.getRGB(1, 1) and 0xFFFFFF)
    }

    @Test
    fun `screenshots go to their own folder, named by the time they were taken`() {
        val file = screenshotFile(Path.of("app"), LocalDateTime.of(2026, 10, 21, 22, 30, 5))

        assertEquals(Path.of("app", "screenshots", "carplay-2026-10-21-223005.png"), file)
        assertEquals(
            Path.of("app", "screenshots", "altscreen-2026-10-21-223005.png"),
            screenshotFile(Path.of("app"), LocalDateTime.of(2026, 10, 21, 22, 30, 5), "altscreen"),
        )
    }
}
