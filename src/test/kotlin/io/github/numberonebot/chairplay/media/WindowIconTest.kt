package io.github.numberonebot.chairplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowIconTest {
    // GLFW takes non-premultiplied RGBA rows from the top; Windows picks the title-bar and taskbar sizes.
    @Test
    fun `loads every size as rgba with a transparent corner and an opaque centre`() {
        val images = WindowIcon.images()

        assertEquals(listOf(16, 32, 48, 256), images.map { it.size })
        for (image in images) {
            assertEquals(image.size * image.size * 4, image.rgba.size)
            assertEquals("corner alpha at ${image.size}", 0, image.rgba[3].toInt() and 0xff)
            val centre = ((image.size / 2) * image.size + image.size / 2) * 4
            assertEquals("centre alpha at ${image.size}", 255, image.rgba[centre + 3].toInt() and 0xff)
        }
    }
}
