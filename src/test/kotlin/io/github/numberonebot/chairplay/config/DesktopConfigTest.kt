package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.AirPlayInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        val dir = Files.createTempDirectory("chairplay-config")
        writeTestSettings(dir)

        val first = DesktopConfig(dir).identity
        val second = DesktopConfig(dir).identity

        assertEquals(first.pairingId, second.pairingId)
        assertEquals(first.publicKeyHex, second.publicKeyHex)
    }

    private fun configWith(vararg lines: String): DesktopConfig {
        val dir = Files.createTempDirectory("chairplay-config")
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
        val dir = Files.createTempDirectory("chairplay-config")
        val old = "wifi.ssid=Home\nwifi.passphrase=secret123\ndisplay.width=1920\n"
        Files.writeString(dir.resolve("settings.properties"), old)

        val config = DesktopConfig(dir)

        val text = Files.readString(dir.resolve("settings.properties"))
        assertTrue(text, text.startsWith(old))
        assertFalse("each block has its own comment; no banner", text.contains("Added by"))
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

    // As before the start screen: the app connects at start and CarPlay covers the screen once the iPhone is there.
    @Test
    fun `the app connects and shows CarPlay on its own unless told not to`() {
        assertTrue(configWith().autoconnect)
        assertTrue(configWith().autoshow)
        val config = configWith("ui.autoconnect=false", "ui.autoshow=false")
        assertFalse(config.autoconnect)
        assertFalse(config.autoshow)
    }

    // The start screen asks for the Wi-Fi, so a missing network no longer stops the start; each bring-up reads it anew.
    @Test
    fun `a missing Wi-Fi network leaves the start to the start screen`() {
        val dir = Files.createTempDirectory("chairplay-config")
        Files.writeString(dir.resolve("settings.properties"), "wifi.ssid=\n")

        val config = DesktopConfig(dir)

        assertNull(config.wifi)
        assertNull(config.currentWifi())
        SettingsFile(dir.resolve("settings.properties")).write(mapOf("wifi.ssid" to "Home", "wifi.passphrase" to "secret123"))
        assertEquals("Home", config.currentWifi()?.ssid)
        assertEquals("secret123", config.currentWifi()?.passphrase)
    }

    // Upstream ships its call echo canceller off, as an experiment; so does the desktop.
    @Test
    fun `call echo cancellation is off unless asked for`() {
        assertFalse(configWith().echoCancellation)
        assertTrue(configWith("audio.echo.cancellation=true").echoCancellation)
    }

    @Test
    fun `the controls window opens only when asked for`() {
        assertFalse(configWith().controlsWindow)
        assertTrue(configWith("window.controls=true").controlsWindow)
    }

    @Test
    fun `windows keep the picture's aspect unless told not to`() {
        assertTrue(configWith().keepAspect)
        assertFalse(configWith("window.keep.aspect=false").keepAspect)
    }

    // Without the setting the iPhone hears of no second screen, as before.
    @Test
    fun `the altscreen is off unless asked for`() {
        assertNull(configWith().airPlay.cluster)
        assertNull(configWith().altscreen)
    }

    // DiPlay's cluster display, at the size asked for, with even margins instead of the BYD cluster's crop.
    @Test
    fun `an enabled altscreen asks the iPhone for its map at the given size`() {
        val config = configWith("altscreen.enabled=true", "altscreen.width=1600", "altscreen.height=600")

        val cluster = config.airPlay.cluster!!
        assertEquals(1600, cluster.widthPixels)
        assertEquals(600, cluster.heightPixels)
        assertEquals(30, cluster.fps)
        assertEquals("maps:/car/instrumentcluster/map", cluster.initialUrl)
        assertEquals(AltscreenContent.MAP, config.altscreen!!.content)
        assertEquals(WindowSize(1600, 600), config.altscreen!!.size)
    }

    @Test
    fun `the altscreen defaults to Audi's cockpit panel and can start on the turn card`() {
        val config = configWith("altscreen.enabled=true", "altscreen.content=card")

        val cluster = config.airPlay.cluster!!
        assertEquals(1440, cluster.widthPixels)
        assertEquals(542, cluster.heightPixels)
        assertEquals(292, cluster.widthPhysicalMm)
        assertEquals(110, cluster.heightPhysicalMm)
        // Nothing covers a window on a PC, so the iPhone keeps its UI (attribution, car marker) to the panel's edges.
        assertNull(cluster.safeArea)
        assertEquals("maps:/car/instrumentcluster/instructioncard", config.airPlay.cluster!!.initialUrl)
    }

    // DashBoard reads the card layout from the cluster URL for every navigation app (MHI2Q CarPlay project notes).
    @Test
    fun `the altscreen card layout goes into the cluster url`() {
        assertEquals("maps:/car/instrumentcluster/map", configWith("altscreen.enabled=true").airPlay.cluster!!.initialUrl)
        assertEquals(
            "maps:/car/instrumentcluster?showETA=yes&maneuverLayout=rightAligned",
            configWith("altscreen.enabled=true", "altscreen.content=map-card", "altscreen.cards=right").airPlay.cluster!!.initialUrl,
        )
        assertEquals(
            "maps:/car/instrumentcluster/map?showETA=no&maneuverLayout=no",
            configWith("altscreen.enabled=true", "altscreen.cards=off").airPlay.cluster!!.initialUrl,
        )
        assertEquals(AltscreenCards.TOP, AltscreenCards.RIGHT.next())
    }

    // Without a route the altscreen looks the same in every mode, so its title says which one it is in.
    @Test
    fun `the window titles name the stream size, and the AltScreen its content and card layout`() {
        assertEquals("ChairPlay · 1280×720", mainWindowTitle(WindowSize(1280, 720)))
        // Closing a session gracefully takes seconds, so the title says what the window waits for.
        assertEquals("ChairPlay · 1280×720 · reconnecting…", mainWindowTitle(WindowSize(1280, 720), "reconnecting…"))
        assertEquals(
            "ChairPlay AltScreen · 1440×542 · map-card · cards right",
            altscreenTitle(WindowSize(1440, 542), AltscreenContent.MAP_CARD, AltscreenCards.RIGHT),
        )
    }

    // The HUD shows the turn card cut out of the iPhone's card-only stream, which costs next to no bandwidth.
    @Test
    fun `the HUD asks the iPhone for the turn card and is the fourth content`() {
        assertEquals(
            "maps:/car/instrumentcluster/instructioncard",
            configWith("altscreen.enabled=true", "altscreen.content=hud").airPlay.cluster!!.initialUrl,
        )
        // F11 goes map, map-card, hud, card.
        assertEquals(AltscreenContent.HUD, AltscreenContent.MAP_CARD.next())
        assertEquals(AltscreenContent.CARD, AltscreenContent.HUD.next())
        assertEquals(AltscreenContent.MAP, AltscreenContent.CARD.next())
    }

    // The iPhone is asked at once for the content the AltScreen was left on.
    @Test
    fun `the AltScreen starts on the view it was left on`() {
        val dir = Files.createTempDirectory("chairplay-config")
        writeTestSettings(dir)
        Files.writeString(dir.resolve("settings.properties"), "altscreen.enabled=true\n", StandardOpenOption.APPEND)
        AltscreenViewStore(dir).save(AltscreenContent.CARD, AltscreenCards.RIGHT, AltscreenContent.MAP to AltscreenCards.AUTO)

        val config = DesktopConfig(dir)

        assertEquals(AltscreenContent.CARD, config.altscreen!!.content)
        assertEquals(AltscreenCards.RIGHT, config.altscreen!!.cards)
        assertEquals("maps:/car/instrumentcluster/instructioncard?showETA=yes&maneuverLayout=rightAligned", config.airPlay.cluster!!.initialUrl)
    }

    // Room for an overlay of our own, such as the maneuver layer on the right.
    @Test
    fun `the altscreen safe area can leave room at its edges`() {
        val config = configWith("altscreen.enabled=true", "altscreen.safe.right=400", "altscreen.safe.top=20")

        assertEquals(AirPlayInsets(top = 20, right = 400), config.airPlay.cluster!!.safeArea)
    }

    @Test
    fun `an unknown altscreen content stops the start with the setting named`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            configWith("altscreen.enabled=true", "altscreen.content=radio")
        }
        assertTrue(error.message, error.message!!.contains("altscreen.content"))
    }

    // The CarPlay data log is for diagnostics and grows during every drive: off unless asked for.
    @Test
    fun `the CarPlay data log is off unless asked for`() {
        assertFalse(configWith().logCarPlayData)
        assertTrue(configWith("log.carplay.data=true").logCarPlayData)
        assertTrue(DesktopConfig.defaultSettingsText().contains("'true' or 'false'.\nlog.carplay.data=false\n"))
        assertTrue(runCatching { configWith("log.carplay.data=yes").logCarPlayData }.exceptionOrNull() is IllegalArgumentException)
    }

    // The name the iPhone shows for the car, in CarPlay settings, /info and the Bonjour service; the window
    // title stays ChairPlay.
    @Test
    fun `the device name comes from the settings`() {
        assertEquals("ChairPlay", configWith().airPlay.deviceName)
        assertEquals("ChairPlay", configWith("device.name=  ").airPlay.deviceName)
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
        val exe = Path.of("C:", "Users", "dark", "Documents", "ChairPlayApp", "ChairPlay.exe")

        assertEquals(exe.parent, DesktopConfig.appDirectory(exe.toString()))
    }

    // Started another way (installDist, an IDE), the app keeps its files in the working directory.
    @Test
    fun `without the packaged exe the app folder is the working directory`() {
        assertEquals(Path.of(System.getProperty("user.dir")).toAbsolutePath(), DesktopConfig.appDirectory(null))
    }

    // Without Opus among the microphone formats the wireless iPhone opened no audio stream at all and
    // played music on its own speaker (iPhone 15, iOS 18.6); upstream: "PCM-only wireless negotiation is
    // not a universal fallback".
    @Test
    fun `offers opus for the microphone`() {
        val dir = Files.createTempDirectory("chairplay-config")
        writeTestSettings(dir)

        val formats = AirPlayInfoPlist.build(DesktopConfig(dir).airPlay)["audioFormats"] as List<*>
        val default = formats.map { it as Map<*, *> }.single { it["type"] == 100 && it["audioType"] == "default" }

        assertTrue((default["audioInputFormats"] as Int) and 0x70000000 != 0)
    }

    // The iPhone shows deviceName as the car and the icon, with oemLabel under it, on the CarPlay home screen.
    @Test
    fun `names the car ChairPlay and gives CarPlay a Windows icon`() {
        val dir = Files.createTempDirectory("chairplay-config")
        writeTestSettings(dir)

        val airPlay = DesktopConfig(dir).airPlay

        assertEquals("ChairPlay", airPlay.deviceName)
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

internal fun writeTestSettings(dir: Path) {
    Files.writeString(dir.resolve("settings.properties"), "wifi.ssid=Home\nwifi.passphrase=secret123\n")
}
