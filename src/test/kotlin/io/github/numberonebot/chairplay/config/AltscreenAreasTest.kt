package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import org.junit.Assert.assertEquals
import org.junit.Test

class AltscreenAreasTest {
    // The parts a view can show in, for an 8:3 cluster: the whole frame; five eighths at the left or the right, beside a
    // dial; three eighths at the left, the middle or the right, between dials or in a side panel. All of the full height.
    @Test
    fun `the parts are the whole frame and parts of its eighths`() {
        assertEquals(
            listOf(
                AirPlayViewArea(1920, 720),
                AirPlayViewArea(1200, 720), AirPlayViewArea(1200, 720, originX = 720),
                AirPlayViewArea(720, 720), AirPlayViewArea(720, 720, originX = 600), AirPlayViewArea(720, 720, originX = 1200),
            ),
            AreaPreset.entries.map { it.area(1920, 720) },
        )
        // A width keeps to a multiple of 8, as DiPlay's streams do, and the middle one to an even origin.
        assertEquals(
            listOf(
                AirPlayViewArea(1440, 540),
                AirPlayViewArea(896, 540), AirPlayViewArea(896, 540, originX = 544),
                AirPlayViewArea(536, 540), AirPlayViewArea(536, 540, originX = 452), AirPlayViewArea(536, 540, originX = 904),
            ),
            AreaPreset.entries.map { it.area(1440, 540) },
        )
    }

    // Seen on an iPhone 15 Pro Max (AirPlay 870.14): after updateViewArea the AltScreen's view stays blurred, mid-way,
    // until it is asked for again with showUI; then it is drawn in the new area. So a move sends the area, then the view.
    @Test
    fun `a move sends the area, then asks for the view again`() {
        val sent = mutableListOf<String>()
        val move = AltscreenAreaMove(count = { 3 }, send = { sent += "area ${(it["params"] as Map<*, *>)["viewAreaIndex"]}"; true },
            showView = { sent += "view" })

        assertEquals(true, move(2))
        assertEquals(listOf("area 2", "view"), sent)
    }

    @Test
    fun `a move the iPhone did not get asks for no view`() {
        val sent = mutableListOf<String>()
        val move = AltscreenAreaMove(count = { 3 }, send = { false }, showView = { sent += "view" })

        assertEquals(false, move(1))
        assertEquals(emptyList<String>(), sent)
    }

    // DiPlay's updateViewArea, which it sends for the main screen alone, addressed to the AltScreen.
    @Test
    fun `the AltScreen is moved to an area as DiPlay moves the main screen`() {
        val command = altscreenViewAreaCommand(1, 6)

        assertEquals("updateViewArea", command["type"])
        assertEquals(
            mapOf("uuid" to AirPlayInfoPlist.ALT_UUID, "viewAreaIndex" to 1, "animationDurationMillis" to 300, "adjacentViewAreas" to listOf(0, 2, 3, 4, 5)),
            command["params"],
        )
    }
}
