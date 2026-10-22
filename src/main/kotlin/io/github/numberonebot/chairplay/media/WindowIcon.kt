package io.github.numberonebot.chairplay.media

import org.lwjgl.glfw.GLFW.glfwSetWindowIcon
import org.lwjgl.glfw.GLFWImage
import org.lwjgl.system.MemoryUtil
import javax.imageio.ImageIO

/**
 * The ChairPlay icon for the window's title bar and taskbar button, from the PNGs that
 * packaging/icon/make_icon.py writes into the resources. Windows picks the size it needs.
 */
internal object WindowIcon {
    class Image(val size: Int, val rgba: ByteArray)

    private val SIZES = listOf(16, 32, 48, 256)

    /** Non-premultiplied RGBA, rows from the top, as GLFW takes them. */
    fun images(): List<Image> = SIZES.map { size ->
        val png = checkNotNull(WindowIcon::class.java.getResourceAsStream("/icons/chairplay-$size.png")) {
            "icon chairplay-$size.png is missing from the resources"
        }
        val image = png.use(ImageIO::read)
        check(image.width == size && image.height == size) { "chairplay-$size.png is ${image.width}x${image.height}" }
        val rgba = ByteArray(size * size * 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val argb = image.getRGB(x, y)
                val at = (y * size + x) * 4
                rgba[at] = (argb shr 16).toByte()
                rgba[at + 1] = (argb shr 8).toByte()
                rgba[at + 2] = argb.toByte()
                rgba[at + 3] = (argb ushr 24).toByte()
            }
        }
        Image(size, rgba)
    }

    /** Call on the GL thread; GLFW copies the pixels, so they are freed straight after. */
    fun apply(window: Long) {
        val images = images()
        val pixels = images.map { image ->
            MemoryUtil.memAlloc(image.rgba.size).put(image.rgba).flip()
        }
        val buffer = GLFWImage.malloc(images.size)
        try {
            images.forEachIndexed { index, image -> buffer[index].set(image.size, image.size, pixels[index]) }
            glfwSetWindowIcon(window, buffer)
        } finally {
            buffer.free()
            pixels.forEach(MemoryUtil::memFree)
        }
    }
}
