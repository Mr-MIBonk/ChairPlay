package io.github.numberonebot.chairplay.hud

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/** A decoded cover, [width] x [height] RGBA pixels in a direct buffer, ready for nvgCreateImageRGBA. */
class CoverPixels(val width: Int, val height: Int, val rgba: ByteBuffer)

/**
 * Decodes the cover the iPhone sent, in the JVM, rather than handing its bytes to NanoVG's native decoder: only
 * JPEG and PNG, at most [MAX_BYTES] long and [MAX_SIDE] pixels a side, the size read from the header before the
 * picture is. Anything else, or a picture that fails to decode, is null, and the HUD shows no cover.
 */
fun coverPixels(bytes: ByteArray): CoverPixels? {
    if (bytes.size > MAX_BYTES || !(bytes.startsWith(JPEG) || bytes.startsWith(PNG))) return null
    return runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                if (reader.getWidth(0) > MAX_SIDE || reader.getHeight(0) > MAX_SIDE) return null
                val image = reader.read(0)
                val rgba = ByteBuffer.allocateDirect(image.width * image.height * 4).order(ByteOrder.nativeOrder())
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val argb = image.getRGB(x, y)
                    rgba.put((argb shr 16).toByte()).put((argb shr 8).toByte()).put(argb.toByte()).put((argb ushr 24).toByte())
                }
                CoverPixels(image.width, image.height, rgba.flip())
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()
}

private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private const val MAX_BYTES = 4 * 1024 * 1024
private const val MAX_SIDE = 2048
