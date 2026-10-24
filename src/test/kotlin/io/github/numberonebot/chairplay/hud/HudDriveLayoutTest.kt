package io.github.numberonebot.chairplay.hud

import io.github.numberonebot.chairplay.drive.GaugeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudDriveLayoutTest {
    private val w = 1280f
    private val h = 480f

    // In drive mode the route and the song keep to the room between the wells, the route over the song, half each.
    @Test
    fun `the route and the song share the room between the wells, the route on top`() {
        val (wellsEnd, wellsStart) = GaugeLayout.between(w, h)
        val (route, media) = driveSlots(w, h, route = 1f, media = 1f)
        for (slot in listOf(route, media)) {
            assertTrue("$slot", slot.left >= wellsEnd && slot.right <= wellsStart)
            assertTrue("$slot", slot.top >= 0f && slot.bottom <= h)
        }
        assertEquals(route.bottom, media.top, 0.01f)
        assertEquals(route.height, media.height, 0.01f)
        assertEquals(route.left, media.left, 0f)
        assertEquals(route.right, media.right, 0f)
    }

    // Alone, either has the whole room; the song moves down as the route comes.
    @Test
    fun `either alone has the whole room, and the song moves down as the route comes`() {
        val (_, song) = driveSlots(w, h, route = 0f, media = 1f)
        val (road, _) = driveSlots(w, h, route = 1f, media = 0f)
        assertEquals(song.top, road.top, 0.01f)
        assertEquals(song.bottom, road.bottom, 0.01f)
        val (_, halfway) = driveSlots(w, h, route = 0.5f, media = 1f)
        val (_, below) = driveSlots(w, h, route = 1f, media = 1f)
        assertTrue(song.top < halfway.top && halfway.top < below.top)
        assertEquals(song.bottom, below.bottom, 0.01f)
    }

    // In drive mode the song's lines take a column 1.2 covers wide, so its block is squarer and fits its slot larger;
    // out of it the song keeps its box.
    @Test
    fun `in drive mode the song's lines are 1_2 covers wide, and the song fits larger`() {
        val alone = mediaBox(w, h, 0f)
        assertEquals(alone, driveMediaBox(alone, h, 0f))
        val narrow = driveMediaBox(alone, h, 1f)
        assertEquals(1.2f * narrow.cover, mediaBlockWidth(narrow, h) - narrow.cover - MEDIA_GAP * h, 0.01f)
        assertEquals((alone.left + alone.right) / 2, (narrow.left + narrow.right) / 2, 0.01f)
        val (_, slot) = driveSlots(w, h, route = 0f, media = 1f)
        assertTrue(fitScale(mediaBlockWidth(narrow, h), narrow.cover, slot) > fitScale(mediaBlockWidth(alone, h), alone.cover, slot))
    }

    // A block shrinks to fit its slot, keeping its shape, and never grows past its own size.
    @Test
    fun `a block shrinks to fit its slot and never grows`() {
        val slot = HudRect(0f, 0f, 300f, 200f)
        assertEquals(0.5f, fitScale(600f, 100f, slot), 1e-6f)
        assertEquals(0.4f, fitScale(100f, 500f, slot), 1e-6f)
        assertEquals(1f, fitScale(100f, 100f, slot), 0f)
    }
}
