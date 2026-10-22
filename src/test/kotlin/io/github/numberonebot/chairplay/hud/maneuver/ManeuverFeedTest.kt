package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.JunctionType
import io.github.numberonebot.chairplay.data.Maneuver
import io.github.numberonebot.chairplay.data.ManeuverType
import io.github.numberonebot.chairplay.hud.HudManeuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManeuverFeedTest {
    private fun hud(next: Maneuver?, distance: Long? = 1_000) =
        HudManeuver(typeCode = next?.typeCode, distance = null, road = null, description = null, next = next, distanceMeters = distance)

    private val left = Maneuver(
        index = 3, typeCode = ManeuverType.LEFT_TURN.code, exitAngle = -90, junctionAngles = listOf(-90, 0),
        junctionType = JunctionType.INTERSECTION, distanceBetweenMeters = 150,
    )

    private fun List<ByteArray>.commands() = map { it[0].toInt() }

    @Test
    fun `a new maneuver is pushed once with its progress`() {
        val feed = ManeuverFeed()
        val first = feed.update(hud(left), 0)
        assertEquals(listOf(0x01), first.commands())
        assertEquals(0, first[0][1].toInt() and 0x08)
        assertTrue(feed.showing)
        assertEquals(emptyList<Int>(), feed.update(hud(left), 10).commands())
    }

    @Test
    fun `a nearer distance sends only progress`() {
        val feed = ManeuverFeed()
        feed.update(hud(left), 0)
        val packets = feed.update(hud(left, distance = 75), 10)
        assertEquals(listOf(0x06), packets.commands())
        assertEquals(ArrowProgress.FILL, packets[0][4].toInt())
        assertEquals(8, packets[0][2].toInt())
        assertEquals(emptyList<Int>(), feed.update(hud(left, distance = 75), 20).commands())
    }

    @Test
    fun `new roads for the same maneuver refresh it without a transition`() {
        val feed = ManeuverFeed()
        feed.update(hud(left), 0)
        val packets = feed.update(hud(left.copy(junctionAngles = listOf(-90, 0, 90))), 10)
        assertEquals(listOf(0x01), packets.commands())
        assertEquals(0x08, packets[0][1].toInt() and 0x08)
    }

    @Test
    fun `the next maneuver is a new push`() {
        val feed = ManeuverFeed()
        feed.update(hud(left), 0)
        val right = left.copy(index = 4, typeCode = ManeuverType.RIGHT_TURN.code, exitAngle = 90)
        val packets = feed.update(hud(right), 10)
        assertEquals(listOf(0x01), packets.commands())
        assertEquals(0, packets[0][1].toInt() and 0x08)
    }

    @Test
    fun `arriving again with the same geometry does not restart the flag`() {
        val feed = ManeuverFeed()
        val arrive = Maneuver(index = 7, typeCode = ManeuverType.ARRIVE_AT_DESTINATION.code, junctionType = JunctionType.INTERSECTION)
        feed.update(hud(arrive), 0)
        assertEquals(emptyList<Int>(), feed.update(hud(arrive.copy(index = 8)), 10).commands())
    }

    @Test
    fun `the end of guidance, or a maneuver it cannot draw, clears it once`() {
        val feed = ManeuverFeed()
        feed.update(hud(left), 0)
        assertEquals(listOf(0x07), feed.update(null, 10).commands())
        assertFalse(feed.showing)
        assertEquals(emptyList<Int>(), feed.update(null, 20).commands())
        feed.update(hud(left), 30)
        assertEquals(listOf(0x07), feed.update(hud(Maneuver(index = 9)), 40).commands())
        assertFalse(feed.showing)
    }

    @Test
    fun `a blinking arrow wants a wake-up, a quiet one none`() {
        val feed = ManeuverFeed()
        assertNull(feed.nextChange(hud(left), 0))
        assertEquals(600L, feed.nextChange(hud(left, distance = 20), 0))
        assertNull(feed.nextChange(null, 0))
    }
}
