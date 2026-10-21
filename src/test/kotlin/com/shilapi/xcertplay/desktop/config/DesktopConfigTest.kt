package com.shilapi.xcertplay.desktop.config

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.AirPlayInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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

    private fun configWith(vararg lines: String): DesktopConfig {
        val dir = Files.createTempDirectory("diplay-config")
        writeTestSettings(dir)
        Files.writeString(dir.resolve("settings.properties"), lines.joinToString("") { "$it\n" }, StandardOpenOption.APPEND)
        return DesktopConfig(dir)
    }

    // Upstream's default physical width is 200 mm; the height follows the aspect ratio, as /info computes it.
    @Test
    fun `the display defaults to 1280x720 at 60 fps and 200 mm wide`() {
        val config = configWith()
        val main = config.airPlay.main

        assertEquals(1280 to 720, main.widthPixels to main.heightPixels)
        assertEquals(60, main.fps)
        assertEquals(200 to 113, main.widthPhysicalMm to main.heightPhysicalMm)
        assertEquals(WindowSize(1280, 720), config.window)
    }

    // iOS picks its 2x or 3x rendering from pixels against millimetres, so ppi is the convenient knob.
    @Test
    fun `a ppi sets the physical size the iPhone sees`() {
        val main = configWith("display.width=1920", "display.height=1080", "display.ppi=160").airPlay.main

        assertEquals(305 to 171, main.widthPhysicalMm to main.heightPhysicalMm)
    }

    @Test
    fun `millimetres win over ppi and a lone width keeps the aspect ratio`() {
        val both = configWith("display.physical.width.mm=250", "display.physical.height.mm=120", "display.ppi=160").airPlay.main
        assertEquals(250 to 120, both.widthPhysicalMm to both.heightPhysicalMm)

        val width = configWith("display.physical.width.mm=300").airPlay.main
        assertEquals(300 to 169, width.widthPhysicalMm to width.heightPhysicalMm)
    }

    @Test
    fun `fps and the window size come from the settings`() {
        val config = configWith("display.fps=30", "window.width=1600", "window.height=900")

        assertEquals(30, config.airPlay.main.fps)
        assertEquals(WindowSize(1600, 900), config.window)
        assertEquals("display 1280x720 px, 200x113 mm (163 ppi), 30 fps; window 1600x900", config.displaySummary())
    }

    @Test
    fun `rejects display values the iPhone cannot use`() {
        for (bad in listOf("display.fps=90", "display.fps=25", "display.ppi=0", "display.physical.width.mm=-5", "window.width=0")) {
            val error = runCatching { configWith(bad).airPlay }.exceptionOrNull()
            assertTrue("$bad was accepted", error is IllegalArgumentException)
            assertTrue("$bad: ${error?.message}", bad.substringBefore('=') in error!!.message!!)
        }
    }

    // The rest of the CarPlay screen as /info declares it; defaults keep what the iPhone saw before.
    @Test
    fun `screen layout defaults leave the iPhone to decide`() {
        val config = configWith()
        val main = config.airPlay.main

        assertEquals(Theme.WINDOWS, config.theme)
        assertEquals(false, config.airPlay.rightHandDrive)
        assertNull(main.viewAreas)
        assertNull(main.safeArea)
        assertEquals(true, main.safeAreaDrawOutside)
    }

    // A window is rectangular, so the picture fills it with square corners unless rounded is asked for.
    @Test
    fun `corners are square by default and rounded on request`() {
        val square = configWith().airPlay.main
        assertEquals(true, square.mainDisplayCornerMasks)
        assertEquals(true, square.mainDisplayDisableSafeArea)

        val rounded = configWith("display.corners=rounded").airPlay.main
        assertNull(rounded.mainDisplayCornerMasks)
        assertEquals(false, rounded.mainDisplayDisableSafeArea)

        // Square corners leave the safe area out of /info, as upstream does, unless one is set.
        val inset = configWith("display.safe.top=20").airPlay.main
        assertEquals(true, inset.mainDisplayCornerMasks)
        assertEquals(false, inset.mainDisplayDisableSafeArea)
    }

    // A file written by an older version gains the settings it lacks, after its own lines, which stay as they were.
    @Test
    fun `an older settings file gains the missing settings and keeps its own`() {
        val dir = Files.createTempDirectory("diplay-config")
        val old = "wifi.ssid=Home\nwifi.passphrase=secret123\ndisplay.width=1920\n"
        Files.writeString(dir.resolve("settings.properties"), old)

        val config = DesktopConfig(dir)

        val text = Files.readString(dir.resolve("settings.properties"))
        assertTrue(text, text.startsWith(old))
        for (key in DesktopConfig.SETTING_KEYS) assertTrue("$key missing", Regex("(?m)^${Regex.escape(key)}=").containsMatchIn(text))
        assertEquals(1, Regex("(?m)^display\\.width=").findAll(text).count())
        assertEquals(1920, config.airPlay.main.widthPixels)
        DesktopConfig(dir)
        assertEquals(text, Files.readString(dir.resolve("settings.properties")))
    }

    @Test
    fun `theme, driving side, dock, safe area and corners come from the settings`() {
        val config = configWith(
            "display.theme=dark", "display.right.hand.drive=true", "display.dock=bottom",
            "display.safe.top=10", "display.safe.left=40", "display.safe.draw.outside=false", "display.corners=square",
        )
        val main = config.airPlay.main

        assertEquals(Theme.DARK, config.theme)
        assertEquals(true, config.airPlay.rightHandDrive)
        assertEquals(listOf(AirPlayViewArea(1280, 720, dockEdge = AirPlayInfoPlist.DOCK_EDGE_BOTTOM)), main.viewAreas)
        assertEquals(AirPlayInsets(top = 10, left = 40), main.safeArea)
        assertEquals(false, main.safeAreaDrawOutside)
        assertEquals(true, main.mainDisplayCornerMasks)
        assertEquals(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, configWith("display.dock=driver").airPlay.main.viewAreas!!.single().dockEdge)
        for ((value, theme) in listOf("light" to Theme.LIGHT, "phone" to Theme.PHONE, "windows" to Theme.WINDOWS)) {
            assertEquals(theme, configWith("display.theme=$value").theme)
        }
    }

    @Test
    fun `rejects screen layout values the iPhone cannot use`() {
        for (bad in listOf(
            "display.theme=sepia", "display.right.hand.drive=maybe", "display.dock=top", "display.corners=round",
            "display.safe.top=-1", "display.safe.left=700", "display.safe.draw.outside=1",
        )) {
            val extra = if (bad.startsWith("display.safe.left")) arrayOf("display.safe.right=600") else emptyArray()
            val error = runCatching { configWith(bad, *extra).airPlay }.exceptionOrNull()
            assertTrue("$bad was accepted", error is IllegalArgumentException)
            assertTrue("$bad: ${error?.message}", bad.substringBefore('=') in error!!.message!!)
        }
    }

    // The names are matched against the devices at start, where they exist; the config keeps them as written.
    @Test
    fun `sound devices are read as written`() {
        assertEquals("default", configWith().audioOutput)
        val config = configWith("audio.output=ULTRAGEAR", "audio.input=default")
        assertEquals("ULTRAGEAR", config.audioOutput)
        assertEquals("default", config.audioInput)
    }

    // The name the iPhone shows for the car, in CarPlay settings, /info and the Bonjour service; the window
    // title stays DiPlayDesktop.
    @Test
    fun `the device name comes from the settings`() {
        assertEquals("DiPlayDesktop", configWith().airPlay.deviceName)
        assertEquals("DiPlayDesktop", configWith("device.name=  ").airPlay.deviceName)
        assertEquals("Гараж CarPlay", configWith("device.name=Гараж CarPlay").airPlay.deviceName)
    }

    // Bonjour instance names hold at most 63 bytes of UTF-8.
    @Test
    fun `rejects a device name Bonjour cannot carry`() {
        val error = runCatching { configWith("device.name=" + "Я".repeat(32)).airPlay }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && "device.name" in error.message!!)
    }

    // The packaged exe keeps its settings, keys and pairings in its own folder.
    @Test
    fun `the app folder is the packaged exe's folder`() {
        val exe = Path.of("C:", "Users", "dark", "Documents", "DiPlayDesktopApp", "DiPlayDesktop.exe")

        assertEquals(exe.parent, DesktopConfig.appDirectory(exe.toString()))
    }

    // The iPhone knows this PC by the identity and pairings, so the first start in the app folder copies them.
    @Test
    fun `adopts the legacy settings keys and pairings once`() {
        val legacy = Files.createTempDirectory("diplay-legacy")
        writeTestSettings(legacy)
        Files.writeString(legacy.resolve("identity.properties"), "pairingId=abc\n")
        Files.createDirectories(legacy.resolve("pairings")).resolve("6964").let { Files.writeString(it, "00") }
        Files.createDirectories(legacy.resolve("offline-mfi")).resolve("certificate.p7b").let { Files.writeString(it, "cert") }
        Files.writeString(legacy.resolve("unrelated.txt"), "x")
        val app = Files.createTempDirectory("diplay-app")

        assertTrue(DesktopConfig.adoptLegacy(app, legacy))

        assertEquals("pairingId=abc\n", Files.readString(app.resolve("identity.properties")))
        assertEquals("00", Files.readString(app.resolve("pairings/6964")))
        assertEquals("cert", Files.readString(app.resolve("offline-mfi/certificate.p7b")))
        assertTrue(Files.exists(app.resolve("settings.properties")))
        assertFalse(Files.exists(app.resolve("unrelated.txt")))
        assertTrue("the legacy folder stays as a backup", Files.exists(legacy.resolve("identity.properties")))

        Files.writeString(legacy.resolve("identity.properties"), "pairingId=newer\n")
        assertFalse(DesktopConfig.adoptLegacy(app, legacy))
        assertEquals("pairingId=abc\n", Files.readString(app.resolve("identity.properties")))
    }

    @Test
    fun `adopts nothing without a legacy folder`() {
        val app = Files.createTempDirectory("diplay-app")

        assertFalse(DesktopConfig.adoptLegacy(app, null))
        assertFalse(DesktopConfig.adoptLegacy(app, app.resolve("missing")))
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
        assertTrue("the icon is written to the app folder to be replaced", Files.exists(dir.resolve("carplay-icon.png")))
    }

    private fun writePng(path: Path, width: Int, height: Int) {
        ImageIO.write(java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", path.toFile())
    }

    // Upstream sends the icon at 192 pixels at most: a large one in /info stopped the iPhone connecting (#567).
    @Test
    fun `a replaced icon and label are sent, scaled down to 192 pixels`() {
        val dir = Files.createTempDirectory("diplay-config")
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
        val dir = Files.createTempDirectory("diplay-config")
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
            val dir = Files.createTempDirectory("diplay-config")
            writeTestSettings(dir)
            Files.writeString(dir.resolve("settings.properties"), "car.icon=$name\n", StandardOpenOption.APPEND)
            write(dir.resolve(name))
            val error = runCatching { DesktopConfig(dir).airPlay }.exceptionOrNull()
            assertTrue("$name was accepted", error is IllegalArgumentException)
            assertTrue("$name: ${error?.message}", "car.icon" in error!!.message!!)
        }
    }
}

internal fun writeTestSettings(dir: Path) {
    Files.writeString(dir.resolve("settings.properties"), "wifi.ssid=Home\nwifi.passphrase=secret123\n")
}
