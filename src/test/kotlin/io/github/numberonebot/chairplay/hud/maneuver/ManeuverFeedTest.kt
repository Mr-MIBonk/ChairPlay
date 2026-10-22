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
        assertEquals(6, packets[0][2].toInt())
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

    // BAPBridge.update: the lanes go first, as an event of their own, only when they change.
    @Test
    fun `lanes go before the maneuver and again only when they change`() {
        val feed = ManeuverFeed()
        val first = feed.update(hud(left, lanes = twoLanes), 0)
        assertEquals(listOf(0x0C, 0x0D, 0x0D, 0x0E, 0x01), first.commands())
        assertEquals(emptyList<Int>(), feed.update(hud(left, lanes = twoLanes), 10).commands())

        // New lanes for the same maneuver leave the arrow alone.
        val changed = feed.update(hud(left, lanes = twoLanes.copy(index = 3)), 20)
        assertEquals(listOf(0x0C, 0x0D, 0x0D, 0x0E), changed.commands())
        // Each batch has a token of its own.
        assertNotEquals(first[0].copyOfRange(2, 6).toList(), changed[0].copyOfRange(2, 6).toList())

        assertEquals(listOf(0x0C, 0x0E), feed.update(hud(left), 30).commands())
        assertEquals(emptyList<Int>(), feed.update(hud(left), 40).commands())
    }

    @Test
    fun `the feed knows whether the renderer draws lanes`() {
        val feed = ManeuverFeed()
        assertFalse(feed.showingLanes)
        feed.update(hud(left, lanes = twoLanes), 0)
        assertTrue(feed.showingLanes)
        feed.update(hud(left), 10)
        assertFalse(feed.showingLanes)
        feed.update(hud(left, lanes = twoLanes), 20)
        feed.update(null, 30)
        assertFalse(feed.showingLanes)
    }

    @Test
    fun `without lanes nothing about lanes is sent`() {
        assertEquals(listOf(0x01), ManeuverFeed().update(hud(left), 0).commands())
    }

    // CLEAR also clears the renderer's lanes: a new route sends them again.
    @Test
    fun `lanes are sent again after the end of guidance`() {
        val feed = ManeuverFeed()
        feed.update(hud(left, lanes = twoLanes), 0)
        assertEquals(listOf(0x07), feed.update(null, 10).commands())
        assertEquals(listOf(0x0C, 0x0D, 0x0D, 0x0E, 0x01), feed.update(hud(left, lanes = twoLanes), 20).commands())
    }

    private val next = left.copy(index = 4, typeCode = ManeuverType.RIGHT_TURN.code, exitAngle = 90, junctionAngles = listOf(0, 90))

    // The next maneuver waits for the arrow's white phase, so the blue never cuts off mid-blink.
    @Test
    fun `a new maneuver waits for the blink's white phase`() {
        val feed = ManeuverFeed()
        // 20 of 150 m left: blinking, lit until 600 ms.
        feed.update(hud(left, distance = 20), 0)
        assertEquals(ArrowProgress(16, ArrowProgress.BLINK_HIGH), arrowProgress(20, 150, ManeuverType.LEFT_TURN.code, 0))
        assertEquals(emptyList<Int>(), feed.update(hud(next), 100).commands())
        assertEquals(600L, feed.nextChange(hud(next), 100))
        // The blink goes white on its own clock...
        val white = feed.update(hud(next), 600)
        assertEquals(listOf(0x06), white.commands())
        assertEquals(ArrowProgress.BLINK_LOW, white[0][4].toInt())
        // ...and once the renderer has eased to it, the next maneuver slides in.
        assertEquals(600L + ManeuverFeed.WHITE_SETTLE_MILLIS, feed.nextChange(hud(next), 600))
        assertEquals(emptyList<Int>(), feed.update(hud(next), 700).commands())
        assertEquals(listOf(0x01), feed.update(hud(next), 600 + ManeuverFeed.WHITE_SETTLE_MILLIS).commands())
    }

    @Test
    fun `a partly filled arrow turns white before the next maneuver`() {
        val feed = ManeuverFeed()
        feed.update(hud(left, distance = 75), 0)
        val white = feed.update(hud(next), 100)
        assertEquals(listOf(0x06), white.commands())
        assertEquals(ArrowProgress.QUIET, white[0][4].toInt())
        assertEquals(emptyList<Int>(), feed.update(hud(next), 200).commands())
        assertEquals(listOf(0x01), feed.update(hud(next), 100 + ManeuverFeed.WHITE_SETTLE_MILLIS).commands())
    }

    @Test
    fun `a white arrow hands over at once`() {
        val feed = ManeuverFeed()
        feed.update(hud(left), 0)
        assertEquals(listOf(0x01), feed.update(hud(next), 100).commands())
        val blinking = ManeuverFeed()
        blinking.update(hud(left, distance = 20), 600)
        assertEquals(listOf(0x01), blinking.update(hud(next), 700).commands())
    }

    @Test
    fun `the next maneuver's lanes wait with it`() {
        val feed = ManeuverFeed()
        feed.update(hud(left, distance = 75), 0)
        assertEquals(listOf(0x06), feed.update(hud(next, lanes = twoLanes), 100).commands())
        assertEquals(listOf(0x0C, 0x0D, 0x0D, 0x0E, 0x01), feed.update(hud(next, lanes = twoLanes), 100 + ManeuverFeed.WHITE_SETTLE_MILLIS).commands())
    }

    @Test
    fun `the end of guidance does not wait`() {
        val feed = ManeuverFeed()
        feed.update(hud(left, distance = 75), 0)
        feed.update(hud(next), 100)
        assertEquals(listOf(0x07), feed.update(null, 120).commands())
        assertNull(feed.nextChange(null, 120))
        // A route after it starts at once.
        assertEquals(listOf(0x01), feed.update(hud(next), 130).commands())
    }

    @Test
    fun `a blinking arrow wants a wake-up, a quiet one none`() {
        val feed = ManeuverFeed()
        assertNull(feed.nextChange(hud(left), 0))
        assertEquals(600L, feed.nextChange(hud(left, distance = 20), 0))
        assertNull(feed.nextChange(null, 0))
    }
}
