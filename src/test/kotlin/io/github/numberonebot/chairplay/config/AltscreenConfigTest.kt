package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.AirPlayInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** DesktopConfig: the AltScreen's settings, its view areas and what /info declares of it. */
class AltscreenConfigTest {
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
}
