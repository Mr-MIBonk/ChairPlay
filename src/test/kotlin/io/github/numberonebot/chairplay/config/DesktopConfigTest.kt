package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.AirPlayInsets
import io.github.numberonebot.chairplay.drive.CarSpec
import io.github.numberonebot.chairplay.drive.GeoPoint
import io.github.numberonebot.chairplay.drive.GpsSettings
import io.github.numberonebot.chairplay.drive.NmeaDialect
import io.github.numberonebot.chairplay.log.LogLevel
import io.github.numberonebot.chairplay.media.DuckSetting
import io.github.numberonebot.chairplay.media.decibelGain
import org.junit.Assert.assertArrayEquals
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

    // One accent colour for the screens, the controls window, and the HUD; everything lighter or fainter is made from it.
    @Test
    fun `the accent colour is the iOS blue unless set`() {
        assertEquals(0x0A84FF, configWith().accent)
        assertEquals(0x30D158, configWith("ui.accent=#30d158").accent)
        assertEquals("#30d158", configWith("ui.accent=#30d158").accentText)
        assertEquals("#0a84ff", configWith().accentText)
        for (bad in listOf("ui.accent=blue", "ui.accent=#0a84f", "ui.accent=0a84ff")) {
            val error = assertThrows(IllegalArgumentException::class.java) { configWith(bad).accent }
            assertTrue(error.message, "ui.accent" in error.message.orEmpty())
        }
    }

    private fun configWith(vararg lines: String): DesktopConfig {
        val dir = Files.createTempDirectory("chairplay-config")
        writeTestSettings(dir)
        Files.writeString(dir.resolve("settings.properties"), lines.joinToString("") { "$it\n" }, StandardOpenOption.APPEND)
        return DesktopConfig(dir)
    }

    // A new install draws CarPlay at 1280x720, a window of a size most screens take. Upstream's default physical width
    // is 200 mm; the height follows the aspect ratio, as /info computes it.
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

        val width = configWith("display.width=1280", "display.height=720", "display.physical.width.mm=300").airPlay.main
        assertEquals(300 to 169, width.widthPhysicalMm to width.heightPhysicalMm)
    }

    @Test
    fun `fps and the window size come from the settings`() {
        val config = configWith("display.width=1280", "display.height=720", "display.fps=30", "window.width=1600", "window.height=900")

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
    fun `the screen has no touch by default, or one that swipes or only taps`() {
        // features: 0x02 a knob, 0x04 a touchscreen that only taps, 0x08 one that swipes; primary input 1 touch, 3 knob.
        // With a mouse the knob is the primary input: CarPlay leaves out its scroll buttons, and clicks still tap.
        val default = configWith().airPlay.main
        assertEquals(0x02 to 3, default.features to default.primaryInputDevice)
        val swipe = configWith("display.touch=swipe").airPlay.main
        assertEquals(0x0A to 1, swipe.features to swipe.primaryInputDevice)
        val tap = configWith("display.touch=tap").airPlay.main
        assertEquals(0x06 to 1, tap.features to tap.primaryInputDevice)
        val none = configWith("display.touch=none").airPlay.main
        assertEquals(0x02 to 3, none.features to none.primaryInputDevice)
    }

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
            "display.width=1280", "display.height=720",
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
            "display.theme=sepia", "display.right.hand.drive=maybe", "display.dock=top", "display.corners=round", "display.touch=pen",
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

    // What the iPhone learns of its screens it asks at each connection, so a changed one applies at the next; the name
    // stays as the app started with it, as the radio keeps it.
    @Test
    fun `the next connection takes the screens from the file as it is now`() {
        val dir = Files.createTempDirectory("chairplay-config")
        Files.writeString(dir.resolve("settings.properties"), "display.width=800\ndisplay.height=480\n")
        val config = DesktopConfig(dir)

        SettingsFile(dir.resolve("settings.properties")).write(
            mapOf(
                "display.width" to "1280", "display.height" to "720", "display.fps" to "30", "display.safe.top" to "10",
                "display.right.hand.drive" to "true", "device.name" to "Other", "altscreen.enabled" to "true",
            ),
        )
        val next = config.airPlayNow()

        assertEquals(1280 to 720, next.main.widthPixels to next.main.heightPixels)
        assertEquals(30, next.main.fps)
        assertEquals(10, next.main.safeArea?.top)
        assertTrue(next.rightHandDrive)
        assertEquals(DesktopConfig.DEFAULT_DEVICE_NAME, next.deviceName)
        assertEquals(1440 to 540, next.cluster?.widthPixels to next.cluster?.heightPixels)
        assertEquals(WindowSize(1440, 540), config.now().altscreen?.size)
        assertEquals(800, config.displayWidth)
        assertNull(config.altscreen)
    }

    // Upstream ships its call echo canceller off, as an experiment; so does the desktop.
    @Test
    fun `call echo cancellation is off unless asked for`() {
        assertFalse(configWith().echoCancellation)
        assertTrue(configWith("audio.echo.cancellation=true").echoCancellation)
    }

    // The music starts silent for a while after it starts, then rises: the iPhone's first audio comes in bursts.
    @Test
    fun `the music starts silent for 2 s and rises over 800 ms unless set`() {
        assertEquals(2000L to 800L, configWith().let { it.musicStartSilenceMillis to it.musicStartFadeMillis })
        val set = configWith("audio.start.silence.ms=0", "audio.start.fade.ms=300")
        assertEquals(0L to 300L, set.musicStartSilenceMillis to set.musicStartFadeMillis)
        assertTrue(runCatching { configWith("audio.start.fade.ms=-1").musicStartFadeMillis }.exceptionOrNull() is IllegalArgumentException)
    }

    // Under a navigation prompt the music plays at the iPhone's level, or at -14 dB, Apple's own, when it sends none.
    @Test
    fun `the music ducks to the iPhone's level, else to -14 dB, unless set`() {
        assertEquals(DuckSetting(decibelGain(-14.0), iPhoneFirst = true), configWith().duckSetting)
        assertEquals(DuckSetting(0.1f, iPhoneFirst = false), configWith("audio.duck.db=-20", "audio.duck.iphone=false").duckSetting)
        assertTrue(DesktopConfig.defaultSettingsText().contains("\naudio.duck.db=-14\n"))
        for (wrong in listOf("-31", "1", "half")) {
            assertTrue(wrong, runCatching { configWith("audio.duck.db=$wrong").duckSetting }.exceptionOrNull() is IllegalArgumentException)
        }
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

    // The spoofed GPS: off until asked for; then the iPhone gets the car's wheel speed too, and satellites when asked.
    @Test
    fun `the spoofed GPS is off unless asked for, with an ordinary car`() {
        val off = configWith().gps
        assertFalse(off.enabled)
        assertTrue(off.wheelSpeed)
        assertFalse(off.satellites)
        assertNull(off.start)
        assertEquals(0.0, off.headingDegrees, 0.0)
        assertTrue(off.resume)
        assertEquals(CarSpec(massKg = 1300.0, powerHp = 150.0), off.car)
        assertTrue(off.gauges)
        // The experiments: the rate the iPhone asks for, and DiPlay's sentences.
        assertEquals(4, off.rate)
        assertEquals(NmeaDialect.CLASSIC, off.nmea)
        assertEquals(10, configWith("gps.rate=10").gps.rate)
        assertEquals(NmeaDialect.MODERN, configWith("gps.nmea=modern").gps.nmea)

        val on = configWith(
            "gps.enabled=true", "gps.satellites=true", "gps.wheel.speed=false", "gps.start=https://maps.apple.com/?ll=50.4501,30.5234",
            "gps.heading=270.5", "gps.resume=false", "gps.car.power.hp=300", "gps.car.mass.kg=1600", "gps.gauges=false",
        ).gps
        assertEquals(
            GpsSettings(true, wheelSpeed = false, satellites = true, start = GeoPoint(50.4501, 30.5234), headingDegrees = 270.5,
                resume = false, car = CarSpec(massKg = 1600.0, powerHp = 300.0), gauges = false),
            on,
        )
    }

    @Test
    fun `rejects GPS values the car cannot have`() {
        for (bad in listOf(
            "gps.start=Kyiv", "gps.heading=north", "gps.heading=360", "gps.car.power.hp=5", "gps.car.mass.kg=50", "gps.rate=3",
            "gps.nmea=ancient",
        )) {
            val key = bad.substringBefore('=')
            val error = assertThrows(IllegalArgumentException::class.java) { configWith(bad).gps }
            assertTrue(error.message, key in error.message.orEmpty())
        }
    }

    // Without the setting the iPhone hears of no second screen, as before.
    @Test
    fun `the altscreen is off unless asked for`() {
        assertNull(configWith().airPlay.cluster)
        assertNull(configWith().altscreen)
    }

    // An enabled AltScreen declares every part of the frame as it connects, the whole frame first, so a view moves to
    // its part as soon as it is set, without reconnecting; a view on the full frame stays in the first.
    @Test
    fun `the altscreen declares every part, the views on the full frame in the first`() {
        val config = configWith("altscreen.enabled=true", "altscreen.width=1920", "altscreen.height=720", "altscreen.card.area=right3")

        assertEquals(AreaPreset.entries.map { it.area(1920, 720) }, config.airPlay.cluster!!.viewAreas)
        assertEquals(9, config.altscreenAreaCount)
        assertEquals(listOf(0, 0, 0, 8), AltscreenContent.entries.map(config::altscreenAreaFor))
        // Another part, set while CarPlay runs, is an area the iPhone already knows.
        assertEquals(2, config.altscreenAreaIndex(AltscreenContent.MAP, AreaPreset.RIGHT5))
        assertEquals(0, config.altscreenAreaIndex(AltscreenContent.HUD, AreaPreset.RIGHT5))
        assertNull(configWith().airPlay.cluster)
        assertTrue(DesktopConfig.defaultSettingsText().contains(
            "\naltscreen.map.area=full\naltscreen.map.safe.top=\naltscreen.map.safe.bottom=\naltscreen.map.safe.left=\naltscreen.map.safe.right=\n"))
        assertTrue(DesktopConfig.defaultSettingsText().contains("\naltscreen.card.area=full\n"))
    }

    // A view with margins of its own gets every part again with its margins, counted inside each part; the iPhone hears
    // of each area's own safe area as it connects, so the margins apply from the next connection, its part at once.
    @Test
    fun `a view with margins of its own gets every part with them`() {
        val config = configWith("altscreen.enabled=true", "altscreen.width=1920", "altscreen.height=720",
            "altscreen.card.area=center3", "altscreen.card.safe.top=10", "altscreen.card.safe.bottom=20",
            "altscreen.map-card.safe.right=300")

        val areas = config.airPlay.cluster!!.viewAreas!!
        assertEquals(27, areas.size)
        assertEquals(AirPlayViewArea(1920, 720), areas[0])
        // Map + card's parts come next, then the turn card's.
        assertEquals(AirPlayViewArea(1920, 720, safeArea = AirPlayInsets(right = 300)), areas[9])
        assertEquals(AirPlayViewArea(720, 720, originX = 600, safeArea = AirPlayInsets(top = 10, bottom = 20, left = 600, right = 600)), areas[25])
        assertEquals(0, config.altscreenAreaFor(AltscreenContent.MAP))
        assertEquals(9, config.altscreenAreaFor(AltscreenContent.MAP_CARD))
        assertEquals(25, config.altscreenAreaFor(AltscreenContent.CARD))
        assertEquals(0, config.altscreenAreaFor(AltscreenContent.HUD))
        assertEquals(19, config.altscreenAreaIndex(AltscreenContent.CARD, AreaPreset.LEFT5))

        // DiPlay's /info carries the area's own safe area, where it clipped the display's to every area.
        val alt = (AirPlayInfoPlist.build(config.airPlay)["displays"] as List<*>)[1] as Map<*, *>
        val card = (alt["viewAreas"] as List<*>)[25] as Map<*, *>
        val safe = card["safeArea"] as Map<*, *>
        assertEquals(listOf(600, 10, 720, 690), listOf(safe["originXPixels"], safe["originYPixels"], safe["widthPixels"], safe["heightPixels"]))
        // The same part of another frame.
        val small = configWith("altscreen.enabled=true", "altscreen.width=1440", "altscreen.height=540")
        assertEquals(AirPlayViewArea(896, 540, originX = 544), small.airPlay.cluster!!.viewAreas!![2])
    }

    @Test
    fun `a view's part is one of the eighths, and leaves room inside its margins`() {
        val base = arrayOf("altscreen.enabled=true", "altscreen.width=1920", "altscreen.height=720")
        for (wrong in listOf("altscreen.card.area=0 0 480 720", "altscreen.card.area=half", "altscreen.map.safe.left=-1",
            "altscreen.map.safe.top=wide", "altscreen.card.area=left3\naltscreen.card.safe.left=360\naltscreen.card.safe.right=360")) {
            assertTrue(wrong, runCatching { configWith(*base, *wrong.split("\n").toTypedArray()) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    // DiPlay's cluster display, at the size asked for, with even margins instead of its car cluster's crop.
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
        assertEquals(540, cluster.heightPixels)
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
            "maps:/car/instrumentcluster/map?showETA=yes&maneuverLayout=topAligned",
            configWith("altscreen.enabled=true", "altscreen.cards=top").airPlay.cluster!!.initialUrl,
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
    fun `the HUD asks the iPhone for the turn card`() {
        assertEquals(
            "maps:/car/instrumentcluster/instructioncard",
            configWith("altscreen.enabled=true", "altscreen.content=hud").airPlay.cluster!!.initialUrl,
        )
    }

    // F11 steps through the views in the order the controls window and the AltScreen page show them.
    @Test
    fun `F11 goes hud, map, map-card, card, as the buttons stand`() {
        assertEquals(AltscreenContent.MAP, AltscreenContent.HUD.next())
        assertEquals(AltscreenContent.MAP_CARD, AltscreenContent.MAP.next())
        assertEquals(AltscreenContent.CARD, AltscreenContent.MAP_CARD.next())
        assertEquals(AltscreenContent.HUD, AltscreenContent.CARD.next())
    }

    // The turn card's place (altscreen.cards, Shift+F11) is Map + card's alone: the map, the turn card alone, and the HUD
    // have none to place.
    @Test
    fun `only Map + card places the turn card`() {
        assertEquals(listOf(false, false, true, false), AltscreenContent.entries.map { it.placesCards })
    }

    // Shift+F11 steps through the places the navigation apps take; neither Google Maps nor Apple Maps hides the card.
    @Test
    fun `Shift+F11 goes auto, left, right, top`() {
        assertEquals(listOf("auto", "left", "right", "top"), AltscreenCards.entries.map { it.key })
        assertEquals(AltscreenCards.AUTO, AltscreenCards.TOP.next())
        assertTrue(runCatching { configWith("altscreen.enabled=true", "altscreen.cards=off") }.exceptionOrNull() is IllegalArgumentException)
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

    // The app's log tells what happens without DiPlay's packet-by-packet lines unless asked for.
    @Test
    fun `the log is at info unless set`() {
        assertEquals(LogLevel.INFO, configWith().logLevel)
        assertEquals(LogLevel.TRACE, configWith("log.level=Trace").logLevel)
        assertTrue(DesktopConfig.defaultSettingsText().contains("\nlog.level=info\n"))
        assertTrue(runCatching { configWith("log.level=loud").logLevel }.exceptionOrNull() is IllegalArgumentException)
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

internal fun writeTestSettings(dir: Path) {
    Files.writeString(dir.resolve("settings.properties"), "wifi.ssid=Home\nwifi.passphrase=secret123\n")
}
