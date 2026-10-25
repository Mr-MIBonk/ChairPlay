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
    // Map + card's default margins, blanked where a test counts the areas of other views.
    private val noMapCardMargins = arrayOf("altscreen.map-card.safe.left=", "altscreen.map-card.safe.right=")

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
        val config = configWith("altscreen.enabled=true", "altscreen.width=1920", "altscreen.height=720", "altscreen.card.area=right3",
            *noMapCardMargins)

        assertEquals(AreaPreset.entries.map { it.area(1920, 720) }, config.airPlay.cluster!!.viewAreas)
        assertEquals(9, config.altscreenAreaCount)
        assertEquals(listOf(0, 0, 0, 8), AltscreenContent.entries.map(config::altscreenAreaFor))
        // Another part, set while CarPlay runs, is an area the iPhone already knows.
        assertEquals(2, config.altscreenAreaIndex(AltscreenContent.MAP, AreaPreset.RIGHT5))
        assertEquals(0, config.altscreenAreaIndex(AltscreenContent.HUD, AreaPreset.RIGHT5))
        assertNull(configWith().airPlay.cluster)
    }

    // The views start as the owner set them up (2026-10-24): the turn card in the middle three eighths; the map on the
    // whole frame, all of it safe; Map + card on the whole frame with the card on top and the map kept to the middle
    // three eighths, between the dials.
    @Test
    fun `the views default to the card in the middle and Map + card's map between the dials`() {
        val config = configWith("altscreen.enabled=true")
        assertEquals(AreaPreset.CENTER3, config.altscreenPreset(AltscreenContent.CARD))
        assertEquals(AreaPreset.FULL, config.altscreenPreset(AltscreenContent.MAP))
        assertEquals(AreaPreset.FULL, config.altscreenPreset(AltscreenContent.MAP_CARD))
        assertEquals(AltscreenCards.TOP, config.altscreen!!.cards)
        val text = DesktopConfig.defaultSettingsText()
        assertTrue(text.contains("\naltscreen.cards=top\n"))
        // The map's blank margins leave it to altscreen.safe.*, all zero.
        assertTrue(text.contains(
            "\naltscreen.map.area=full\naltscreen.map.safe.top=\naltscreen.map.safe.bottom=\naltscreen.map.safe.left=\naltscreen.map.safe.right=\n"))
        // The default 1440 px frame's middle three eighths, 536 px as parts keep to multiples of 8, leave 452 at each side.
        assertTrue(text.contains("\naltscreen.map-card.area=full\naltscreen.map-card.safe.top=\naltscreen.map-card.safe.bottom=\n" +
            "altscreen.map-card.safe.left=452\naltscreen.map-card.safe.right=452\n"))
        assertTrue(text.contains("\naltscreen.card.area=center3\n"))
    }

    // A view with margins of its own gets every part again with its margins, counted inside each part; the iPhone hears
    // of each area's own safe area as it connects, so the margins apply from the next connection, its part at once.
    @Test
    fun `a view with margins of its own gets every part with them`() {
        val config = configWith("altscreen.enabled=true", "altscreen.width=1920", "altscreen.height=720",
            "altscreen.card.area=center3", "altscreen.card.safe.top=10", "altscreen.card.safe.bottom=20",
            "altscreen.map-card.safe.left=", "altscreen.map-card.safe.right=300")

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

    // Margins that fit the view's own part but not a narrower one shrink in that one with its width, so the view can
    // still move there: Map + card's map kept to the middle three eighths of the whole frame, in the five eighths.
    @Test
    fun `margins too wide for a narrower part shrink with it`() {
        val config = configWith("altscreen.enabled=true", "altscreen.width=1280", "altscreen.height=480",
            "altscreen.map-card.area=full", "altscreen.map-card.safe.left=400", "altscreen.map-card.safe.right=400")
        val areas = config.airPlay.cluster!!.viewAreas!!
        val whole = areas[config.altscreenAreaIndex(AltscreenContent.MAP_CARD, AreaPreset.FULL)!!]
        assertEquals(400, whole.safeArea!!.left)
        assertEquals(400, whole.safeArea!!.right)
        // The 800 px five eighths at the left: 400 * 800 / 1280 at each side.
        val left5 = areas[config.altscreenAreaIndex(AltscreenContent.MAP_CARD, AreaPreset.LEFT5)!!]
        assertEquals(250, left5.safeArea!!.left)
        assertEquals(1280 - 800 + 250, left5.safeArea!!.right)
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
        // The turn card on top, as on Map + card by default.
        assertEquals("maps:/car/instrumentcluster/instructioncard?showETA=yes&maneuverLayout=topAligned", config.airPlay.cluster!!.initialUrl)
    }

    // DashBoard reads the card layout from the cluster URL for every navigation app (MHI2Q CarPlay project notes).
    @Test
    fun `the altscreen card layout goes into the cluster url`() {
        assertEquals("maps:/car/instrumentcluster/map", configWith("altscreen.enabled=true", "altscreen.cards=auto").airPlay.cluster!!.initialUrl)
        assertEquals(
            "maps:/car/instrumentcluster?showETA=yes&maneuverLayout=rightAligned",
            configWith("altscreen.enabled=true", "altscreen.content=map-card", "altscreen.cards=right").airPlay.cluster!!.initialUrl,
        )
        // On top by default.
        assertEquals(
            "maps:/car/instrumentcluster/map?showETA=yes&maneuverLayout=topAligned",
            configWith("altscreen.enabled=true").airPlay.cluster!!.initialUrl,
        )
        assertEquals(AltscreenCards.TOP, AltscreenCards.RIGHT.next())
    }

    // The HUD shows the turn card cut out of the iPhone's card-only stream, which costs next to no bandwidth.
    @Test
    fun `the HUD asks the iPhone for the turn card`() {
        assertEquals(
            "maps:/car/instrumentcluster/instructioncard",
            configWith("altscreen.enabled=true", "altscreen.content=hud", "altscreen.cards=auto").airPlay.cluster!!.initialUrl,
        )
    }

    // F11 switches between the HUD and the view of the iPhone's picked last, which stands as the AltScreen's other one.
    @Test
    fun `F11 switches between the HUD and the view picked last`() {
        assertEquals(AltscreenContent.MAP_CARD, AltscreenContent.HUD.toggled(AltscreenContent.MAP_CARD))
        assertEquals(AltscreenContent.HUD, AltscreenContent.MAP.toggled(AltscreenContent.MAP_CARD))
        assertEquals(AltscreenContent.HUD, AltscreenContent.CARD.toggled(AltscreenContent.MAP))
    }

    // Under the HUD the iPhone stops drawing, unless altscreen.hud.stream keeps the map view picked last running, so F11
    // comes back to it at once. A demo's HUD over a view keeps that view.
    @Test
    fun `under the HUD the iPhone draws nothing unless the stream is kept`() {
        assertEquals(false, configWith("altscreen.enabled=true").altscreen!!.streamUnderHud)
        assertEquals(true, configWith("altscreen.enabled=true", "altscreen.hud.stream=true").altscreen!!.streamUnderHud)
        val hud = AltscreenContent.HUD
        assertNull(drawnView(hud, AltscreenContent.MAP_CARD, hud = true, underHud = false))
        assertEquals(AltscreenContent.MAP_CARD, drawnView(hud, AltscreenContent.MAP_CARD, hud = true, underHud = true))
        assertEquals(AltscreenContent.CARD, drawnView(AltscreenContent.CARD, AltscreenContent.MAP, hud = true, underHud = true))
        assertEquals(AltscreenContent.MAP, drawnView(AltscreenContent.MAP, AltscreenContent.MAP_CARD, hud = false, underHud = false))
    }

    // The HUD shows a session's route and song only when it is the view picked, or for a demo: on another view, it keeps
    // its waiting line while that view's picture is on its way, rather than flash the song first.
    @Test
    fun `the HUD shows the session only as the view picked or for a demo`() {
        assertEquals(false, hudShowsSession(AltscreenContent.HUD, session = false, demo = false))
        assertEquals(true, hudShowsSession(AltscreenContent.HUD, session = true, demo = false))
        assertEquals(false, hudShowsSession(AltscreenContent.MAP, session = true, demo = false))
        assertEquals(true, hudShowsSession(AltscreenContent.MAP, session = false, demo = true))
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
        AltscreenViewStore(dir).save(AltscreenContent.CARD, AltscreenCards.RIGHT, AltscreenContent.MAP to AltscreenCards.TOP)

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
