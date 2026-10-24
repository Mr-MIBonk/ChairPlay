package io.github.numberonebot.chairplay.config

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO

/** DesktopConfig: the car's name and the CarPlay button's icon. */
class CarIconConfigTest {
    // The iPhone shows deviceName as the car and the icon, with oemLabel under it, on the CarPlay home screen.
    @Test
    fun `names the car and the CarPlay button ChairPlay`() {
        val dir = Files.createTempDirectory("chairplay-config")
        writeTestSettings(dir)

        val airPlay = DesktopConfig(dir).airPlay

        assertEquals("ChairPlay", airPlay.deviceName)
        assertEquals("ChairPlay", airPlay.oemLabel)
        val icon = airPlay.icons.single()
        assertEquals(192, icon.widthPixels)
        assertEquals(192, icon.heightPixels)
        val image = ImageIO.read(ByteArrayInputStream(icon.data))
        assertEquals(192, image.width)
        assertEquals(192, image.height)
        assertTrue("the icon is written to the app folder to be replaced", Files.exists(dir.resolve("carplay-icon.png")))
        // The armchair of the app's own icon, as packaging/icon/make_icon.py draws it for CarPlay.
        assertArrayEquals(Files.readAllBytes(Path.of("packaging/icon/carplay-icon.png")), Files.readAllBytes(dir.resolve("carplay-icon.png")))
    }

    private fun writePng(path: Path, width: Int, height: Int) {
        ImageIO.write(java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", path.toFile())
    }

    // Upstream sends the icon at 192 pixels at most: a large one in /info stopped the iPhone connecting (#567).
    @Test
    fun `a replaced icon and label are sent, scaled down to 192 pixels`() {
        val dir = Files.createTempDirectory("chairplay-config")
        writeTestSettings(dir)
        Files.writeString(dir.resolve("settings.properties"), "car.label=Garage\ncar.icon=mine.png\n", StandardOpenOption.APPEND)
        writePng(dir.resolve("mine.png"), 512, 512)

        val airPlay = DesktopConfig(dir).airPlay

        assertEquals("Garage", airPlay.oemLabel)
        val image = ImageIO.read(ByteArrayInputStream(airPlay.icons.single().data))
        assertEquals(192 to 192, image.width to image.height)
        assertFalse(Files.exists(dir.resolve("carplay-icon.png")))
    }

    @Test
    fun `a small square icon is sent as it is`() {
        val dir = Files.createTempDirectory("chairplay-config")
        writeTestSettings(dir)
        writePng(dir.resolve("carplay-icon.png"), 120, 120)

        val icon = DesktopConfig(dir).airPlay.icons.single()

        assertEquals(120 to 120, icon.widthPixels to icon.heightPixels)
    }

    @Test
    fun `rejects an icon that is missing, not square or not an image`() {
        for ((name, write) in listOf<Pair<String, (Path) -> Unit>>(
            "wide.png" to { writePng(it, 300, 200) },
            "text.png" to { Files.writeString(it, "not an image") },
            "missing.png" to {},
        )) {
            val dir = Files.createTempDirectory("chairplay-config")
            writeTestSettings(dir)
            Files.writeString(dir.resolve("settings.properties"), "car.icon=$name\n", StandardOpenOption.APPEND)
            write(dir.resolve(name))
            val error = runCatching { DesktopConfig(dir).airPlay }.exceptionOrNull()
            assertTrue("$name was accepted", error is IllegalArgumentException)
            assertTrue("$name: ${error?.message}", "car.icon" in error!!.message!!)
        }
    }
}
