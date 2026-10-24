package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.JunctionType
import io.github.numberonebot.chairplay.data.Lane
import io.github.numberonebot.chairplay.data.LaneGuidance
import io.github.numberonebot.chairplay.data.Maneuver
import io.github.numberonebot.chairplay.data.ManeuverType
import io.github.numberonebot.chairplay.hud.HudManeuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManeuverFeedTest {
    private fun hud(next: Maneuver?, distance: Long? = 1_000, lanes: LaneGuidance? = null) =
        HudManeuver(
            typeCode = next?.typeCode, distance = null, road = null, description = null, next = next, distanceMeters = distance,
            lanes = lanes,
        )

    private val twoLanes = LaneGuidance(2, listOf(Lane(0, 2, listOf(-90), -90), Lane(1, 1, listOf(0))))

    private val left = Maneuver(
        index = 3, typeCode = ManeuverType.LEFT_TURN.code, exitAngle = -90, junctionAngles = listOf(-90, 0),
        junctionType = JunctionType.INTERSECTION, distanceBetweenMeters = 150,
    )

    private fun List<ByteArray>.commands() = map { it[0].toInt() }

    @Test
    fun `a new maneuver is pushed once with its progress`() {
        val feed = ManeuverFeed()
        val first = feed.update(hud(left))
        assertEquals(listOf(0x01), first.commands())
        assertEquals(0, first[0][1].toInt() and 0x08)
        assertTrue(feed.showing)
        assertEquals(emptyList<Int>(), feed.update(hud(left)).commands())
    }

    @Test
    fun `a nearer distance sends only progress`() {
        val feed = ManeuverFeed()
        feed.update(hud(left))
        val packets = feed.update(hud(left, distance = 75))
        assertEquals(listOf(0x06), packets.commands())
        assertEquals(ArrowProgress.FILL, packets[0][4].toInt())
        // Half way along the bar: half the arrow.
        assertEquals(8, packets[0][2].toInt())
        assertEquals(emptyList<Int>(), feed.update(hud(left, distance = 75)).commands())
    }

    @Test
    fun `new roads for the same maneuver refresh it without a transition`() {
        val feed = ManeuverFeed()
        feed.update(hud(left))
        val packets = feed.update(hud(left.copy(junctionAngles = listOf(-90, 0, 90))))
        assertEquals(listOf(0x01), packets.commands())
        assertEquals(0x08, packets[0][1].toInt() and 0x08)
    }

    @Test
    fun `the next maneuver is a new push`() {
        val feed = ManeuverFeed()
        feed.update(hud(left))
        val right = left.copy(index = 4, typeCode = ManeuverType.RIGHT_TURN.code, exitAngle = 90)
        val packets = feed.update(hud(right))
        assertEquals(listOf(0x01), packets.commands())
        assertEquals(0, packets[0][1].toInt() and 0x08)
    }

    @Test
    fun `arriving again with the same geometry does not restart the flag`() {
        val feed = ManeuverFeed()
        val arrive = Maneuver(index = 7, typeCode = ManeuverType.ARRIVE_AT_DESTINATION.code, junctionType = JunctionType.INTERSECTION)
        feed.update(hud(arrive))
        assertEquals(emptyList<Int>(), feed.update(hud(arrive.copy(index = 8))).commands())
    }

    @Test
    fun `the end of guidance, or a maneuver it cannot draw, clears it once`() {
        val feed = ManeuverFeed()
        feed.update(hud(left))
        assertEquals(listOf(0x07), feed.update(null).commands())
        assertFalse(feed.showing)
        assertEquals(emptyList<Int>(), feed.update(null).commands())
        feed.update(hud(left))
        assertEquals(listOf(0x07), feed.update(hud(Maneuver(index = 9))).commands())
        assertFalse(feed.showing)
    }

    // BAPBridge.update: the lanes go first, as an event of their own, only when they change.
    @Test
    fun `lanes go before the maneuver and again only when they change`() {
        val feed = ManeuverFeed()
        val first = feed.update(hud(left, lanes = twoLanes))
        assertEquals(listOf(0x0C, 0x0D, 0x0D, 0x0E, 0x01), first.commands())
        assertEquals(emptyList<Int>(), feed.update(hud(left, lanes = twoLanes)).commands())

        // New lanes for the same maneuver leave the arrow alone.
        val changed = feed.update(hud(left, lanes = twoLanes.copy(index = 3)))
        assertEquals(listOf(0x0C, 0x0D, 0x0D, 0x0E), changed.commands())
        // Each batch has a token of its own.
        assertNotEquals(first[0].copyOfRange(2, 6).toList(), changed[0].copyOfRange(2, 6).toList())

        assertEquals(listOf(0x0C, 0x0E), feed.update(hud(left)).commands())
        assertEquals(emptyList<Int>(), feed.update(hud(left)).commands())
    }

    @Test
    fun `the feed knows whether the renderer draws lanes`() {
        val feed = ManeuverFeed()
        assertFalse(feed.showingLanes)
        feed.update(hud(left, lanes = twoLanes))
        assertTrue(feed.showingLanes)
        feed.update(hud(left))
        assertFalse(feed.showingLanes)
        feed.update(hud(left, lanes = twoLanes))
        feed.update(null)
        assertFalse(feed.showingLanes)
    }

    @Test
    fun `without lanes nothing about lanes is sent`() {
        assertEquals(listOf(0x01), ManeuverFeed().update(hud(left)).commands())
    }

    // CLEAR also clears the renderer's lanes: a new route sends them again.
    @Test
    fun `lanes are sent again after the end of guidance`() {
        val feed = ManeuverFeed()
        feed.update(hud(left, lanes = twoLanes))
        assertEquals(listOf(0x07), feed.update(null).commands())
        assertEquals(listOf(0x0C, 0x0D, 0x0D, 0x0E, 0x01), feed.update(hud(left, lanes = twoLanes)).commands())
    }

    private val next = left.copy(index = 4, typeCode = ManeuverType.RIGHT_TURN.code, exitAngle = 90, junctionAngles = listOf(0, 90))

    // The arrow ends all blue at the maneuver; the next one goes out at once, whatever the arrow shows, its own arrow
    // white from its start (unless it is near too), with its lanes before it.
    @Test
    fun `the next maneuver goes out at once, whatever the arrow shows`() {
        val blue = ManeuverFeed()
        blue.update(hud(left, distance = 1))
        assertEquals(listOf(0x01), blue.update(hud(next)).commands())
        val half = ManeuverFeed()
        half.update(hud(left, distance = 75))
        assertEquals(listOf(0x0C, 0x0D, 0x0D, 0x0E, 0x01), half.update(hud(next, lanes = twoLanes)).commands())
    }

    @Test
    fun `the end of guidance clears at once, and a route after it starts at once`() {
        val feed = ManeuverFeed()
        feed.update(hud(left, distance = 75))
        assertEquals(listOf(0x07), feed.update(null).commands())
        assertEquals(listOf(0x01), feed.update(hud(next)).commands())
    }
}
