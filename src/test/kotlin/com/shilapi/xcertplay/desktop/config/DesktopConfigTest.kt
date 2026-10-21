package com.shilapi.xcertplay.desktop.config

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class DesktopConfigTest {
    @Test
    fun `identity persists across loads`() {
        val dir = Files.createTempDirectory("diplay-config")
        writeTestSettings(dir)

        val first = DesktopConfig(dir).identity
        val second = DesktopConfig(dir).identity

        assertEquals(first.pairingId, second.pairingId)
        assertEquals(first.publicKeyHex, second.publicKeyHex)
    }

    // Without Opus among the microphone formats the wireless iPhone opened no audio stream at all and
    // played music on its own speaker (iPhone 15, iOS 18.6); upstream: "PCM-only wireless negotiation is
    // not a universal fallback".
    @Test
    fun `offers opus for the microphone`() {
        val dir = Files.createTempDirectory("diplay-config")
        writeTestSettings(dir)

        val formats = AirPlayInfoPlist.build(DesktopConfig(dir).airPlay)["audioFormats"] as List<*>
        val default = formats.map { it as Map<*, *> }.single { it["type"] == 100 && it["audioType"] == "default" }

        assertTrue((default["audioInputFormats"] as Int) and 0x70000000 != 0)
    }

    // The iPhone shows deviceName as the car and the icon, with oemLabel under it, on the CarPlay home screen.
    @Test
    fun `names the car DiPlayDesktop and gives CarPlay a Windows icon`() {
        val dir = Files.createTempDirectory("diplay-config")
        writeTestSettings(dir)

        val airPlay = DesktopConfig(dir).airPlay

        assertEquals("DiPlayDesktop", airPlay.deviceName)
        assertEquals("Windows", airPlay.oemLabel)
        val icon = airPlay.icons.single()
        assertEquals(192, icon.widthPixels)
        assertEquals(192, icon.heightPixels)
        val image = ImageIO.read(ByteArrayInputStream(icon.data))
        assertEquals(192, image.width)
        assertEquals(192, image.height)
    }
}

internal fun writeTestSettings(dir: Path) {
    Files.writeString(dir.resolve("settings.properties"), "wifi.ssid=Home\nwifi.passphrase=secret123\n")
}
