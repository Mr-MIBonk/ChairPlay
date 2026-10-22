package io.github.numberonebot.chairplay.media

import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/** RGBA pixels as glReadPixels returns them, bottom row first, as an upright RGB picture. */
internal fun screenshotImage(width: Int, height: Int, rgba: ByteBuffer): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (row in 0 until height) {
        val y = height - 1 - row
        for (x in 0 until width) {
            val at = (row * width + x) * 4
            val r = rgba.get(at).toInt() and 0xFF
            val g = rgba.get(at + 1).toInt() and 0xFF
            val b = rgba.get(at + 2).toInt() and 0xFF
            image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
    }
    return image
}

/** screenshots/<screen>-<date>-<time>.png in the app folder [dir]; [screen] is carplay or altscreen. */
internal fun screenshotFile(dir: Path, now: LocalDateTime, screen: String = "carplay"): Path =
    dir.resolve("screenshots").resolve("$screen-${now.format(FILE_TIME)}.png")

/** Writes [image] next to the app's other files and logs where, or why not. */
internal fun saveScreenshot(image: BufferedImage, dir: Path, log: (String) -> Unit, screen: String = "carplay") {
    val file = screenshotFile(dir, LocalDateTime.now(), screen)
    try {
        Files.createDirectories(file.parent)
        ImageIO.write(image, "png", file.toFile())
        log("screenshot ${image.width}x${image.height}: $file")
    } catch (error: Exception) {
        log("screenshot not saved: $error")
    }
}

private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
