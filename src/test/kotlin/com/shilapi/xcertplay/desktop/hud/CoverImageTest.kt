package com.shilapi.xcertplay.desktop.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class CoverImageTest {
    private fun encoded(width: Int, height: Int, format: String): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 0xFF0000)
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    @Test
    fun `a JPEG or PNG cover becomes RGBA pixels`() {
        val cover = coverPixels(encoded(4, 3, "png"))!!
        assertEquals(4, cover.width)
        assertEquals(3, cover.height)
        assertEquals(4 * 3 * 4, cover.rgba.remaining())
        // The first pixel is red and opaque.
        assertEquals(listOf(0xFF, 0, 0, 0xFF), (0 until 4).map { cover.rgba.get(it).toInt() and 0xFF })
        assertEquals(10, coverPixels(encoded(10, 10, "jpg"))!!.width)
    }

    // The bytes come from the iPhone; anything that is not a sane JPEG or PNG is no cover, not a crash.
    @Test
    fun `other bytes, huge files and huge pictures are no cover`() {
        assertNull(coverPixels(byteArrayOf(1, 2, 3, 4)))
        assertNull(coverPixels(ByteArray(5 * 1024 * 1024) { 0xFF.toByte() }))
        assertNull(coverPixels(encoded(3000, 10, "png")))
        assertNull(coverPixels(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
    }
}
