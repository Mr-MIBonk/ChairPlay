package io.github.numberonebot.chairplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The song and the route each keep one layout, round the HUD's middle; every animation only moves and scales them.
class HudPlacementTest {
    private val w = 1280f
    private val h = 480f
    private val song = mediaBox(w, h)
    private val songWidth = mediaBlockWidth(song, h)
    private val routeWidth = 525f
    private val routeHeight = 186f
    private val viewHeight = 186f

    private fun between(value: Float, a: Float, b: Float) = value > minOf(a, b) + 1e-3f && value < maxOf(a, b) - 1e-3f

    // Alone the song stands in the middle at its own size; beside the route, smaller, in its column.
    @Test
    fun `the song only moves and scales into its column beside the route`() {
        assertEquals(HudPlace(w / 2, h / 2, 1f), songPlacement(w, h, place = 0f))
        val beside = songPlacement(w, h, place = 1f)
        assertEquals(MEDIA_BESIDE, beside.scale, 1e-6f)
        val pad = HUD_SIDE * w
        assertTrue(beside.x - songWidth * beside.scale / 2 >= w / 2 + pad / 2 - 0.01f)
        assertTrue(beside.x + songWidth * beside.scale / 2 <= w - pad + 0.01f)
    }

    // The route lays out as beside the song, in its column; alone it moves to the middle and grows, its picture as tall as
    // the HUD's maneuver.
    @Test
    fun `the route only moves and scales from its column to the middle`() {
        val column = routeBox(w, h, 1f)
        assertEquals(HudPlace((column.left + column.right) / 2, h / 2, 1f), routePlacement(w, h, 1f, routeWidth, routeHeight, viewHeight))
        val alone = routePlacement(w, h, place = 0f, routeWidth, routeHeight, viewHeight)
        assertEquals(w / 2, alone.x, 0.01f)
        assertEquals(MANEUVER_HEIGHT * h, viewHeight * alone.scale, 0.01f)
        assertTrue(routeWidth * alone.scale <= w * (1 - 2 * HUD_SIDE))
    }

    // Between the small wells the song moves up and shrinks as the route comes, both at once, by how far it has made
    // room. (Between the large ones the room's width bounds it, so it only moves.)
    @Test
    fun `between the wells the song moves and scales at once as the route comes`() {
        val alone = songPlacement(w, h, place = 0f, driving = 1f, smallness = 1f)
        val above = songPlacement(w, h, place = 1f, driving = 1f, smallness = 1f)
        assertTrue(above.y < alone.y)
        assertTrue(above.scale < alone.scale)
        for (place in listOf(0.25f, 0.5f, 0.75f)) {
            val now = songPlacement(w, h, place = place, driving = 1f, smallness = 1f)
            assertTrue("$place: $now", between(now.y, alone.y, above.y))
            assertTrue("$place: $now", between(now.scale, alone.scale, above.scale))
        }
    }

    // The wells coming move and scale the song from its place to its slot together.
    @Test
    fun `the wells coming move and scale a block together`() {
        val out = songPlacement(w, h, place = 1f, driving = 0f)
        val inPlace = songPlacement(w, h, place = 1f, driving = 1f)
        val half = songPlacement(w, h, place = 1f, driving = 0.5f)
        assertEquals((out.x + inPlace.x) / 2, half.x, 0.01f)
        assertEquals((out.y + inPlace.y) / 2, half.y, 0.01f)
        assertEquals((out.scale + inPlace.scale) / 2, half.scale, 1e-4f)
    }

    // Between the wells the route sits under the song and fits its slot.
    @Test
    fun `between the wells the route sits under the song and fits its slot`() {
        val road = routePlacement(w, h, place = 1f, routeWidth, routeHeight, viewHeight, driving = 1f)
        val tune = songPlacement(w, h, place = 1f, driving = 1f)
        assertTrue(road.y > tune.y)
        val (slot, _) = driveSlots(w, h, route = 1f, media = 1f)
        assertTrue(routeWidth * road.scale <= slot.width + 0.01f && routeHeight * road.scale <= slot.height + 0.01f)
    }
}
