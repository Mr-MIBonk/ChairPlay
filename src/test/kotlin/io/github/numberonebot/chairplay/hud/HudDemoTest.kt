package io.github.numberonebot.chairplay.hud

import io.github.numberonebot.chairplay.data.LaneStatus
import io.github.numberonebot.chairplay.data.ManeuverType
import io.github.numberonebot.chairplay.data.RouteGuidanceState
import io.github.numberonebot.chairplay.hud.maneuver.ArrowProgress
import io.github.numberonebot.chairplay.hud.maneuver.arrowProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HudDemoTest {
    @Test
    fun `the demo drives toward each maneuver in turn and starts over`() {
        val demo = HudDemo(withMedia = false)
        val start = demo.snapshot(0).route!!
        assertEquals(RouteGuidanceState.ROUTE_SET, start.state)
        assertEquals(ManeuverType.LEFT_TURN.code, start.nextManeuver!!.typeCode)
        assertEquals(HudDemo.START_METERS, start.distanceToManeuverMeters)

        val near = demo.snapshot(HudDemo.STEP_MILLIS - 100).route!!
        assertEquals(0, near.nextManeuver!!.index)
        assertTrue("${near.distanceToManeuverMeters}", near.distanceToManeuverMeters!! < 20)

        val second = demo.snapshot(HudDemo.STEP_MILLIS).route!!.nextManeuver!!
        assertEquals(1, second.index)
        assertEquals(ManeuverType.ROUNDABOUT_EXIT_2.code, second.typeCode)

        val again = demo.snapshot(demo.lapMillis).route!!.nextManeuver!!
        assertEquals(ManeuverType.LEFT_TURN.code, again.typeCode)
        // A new lap has new indexes, so the renderer pushes the maneuver again.
        assertEquals(demo.steps, again.index)
    }

    @Test
    fun `the demo slows down toward each maneuver, so the arrow blinks for a while`() {
        val demo = HudDemo(withMedia = false)
        // Fast far away...
        assertTrue(demo.snapshot(1_000).route!!.distanceToManeuverMeters!! > 280)
        // ...and the blink's last 45 metres take the last two seconds and more.
        val near = demo.snapshot(HudDemo.STEP_MILLIS - 2_000).route!!.distanceToManeuverMeters!!
        assertTrue("$near m two seconds before", near in 1..44)
    }

    @Test
    fun `after the arrival the guidance goes, and the route starts over after a pause`() {
        val demo = HudDemo(withMedia = true)
        val arrived = HudDemo.STEP_MILLIS * demo.steps
        // The flag stays a while at the destination...
        val holding = demo.snapshot(arrived + 500)
        assertEquals(ManeuverType.ARRIVE_AT_DESTINATION.code, holding.route!!.nextManeuver!!.typeCode)
        assertEquals(0L, holding.route!!.distanceToManeuverMeters)
        // ...then guidance ends while the song plays on...
        val gap = demo.snapshot(demo.lapMillis - 500)
        assertNull(gap.route)
        assertNotNull(gap.nowPlaying)
        // ...and the next lap starts.
        assertNotNull(demo.snapshot(demo.lapMillis).route)
    }

    @Test
    fun `the HUD shows the demo's route, with a song when asked`() {
        assertNull(HudDemo(withMedia = false).snapshot(0).nowPlaying)
        val withSong = hudState(HudDemo(withMedia = true).snapshot(0))
        assertNotNull(withSong.media)
        assertEquals("400 m", withSong.maneuver!!.distance)
    }

    @Test
    fun `the demo shows lanes where a road would have them`() {
        val demo = HudDemo(withMedia = false)
        val left = demo.snapshot(0)
        assertEquals(true, left.route!!.laneGuidanceShowing)
        assertEquals(left.route!!.laneGuidanceIndex, left.lanes!!.index)
        assertEquals(listOf(-90), left.lanes!!.lanes.filter { it.status == LaneStatus.PREFERRED }.map { it.direction })
        assertEquals(left.lanes, hudState(left).maneuver!!.lanes)

        // None on the roundabout.
        val ring = demo.snapshot(HudDemo.STEP_MILLIS)
        assertNull(ring.lanes)
        assertEquals(false, ring.route!!.laneGuidanceShowing)
        assertNull(hudState(ring).maneuver!!.lanes)

        // Wherever lanes show, a recommended one leads the maneuver's way.
        val withLanes = (0 until demo.steps).map { demo.snapshot(it * HudDemo.STEP_MILLIS) }.filter { it.lanes != null }
        assertTrue("${withLanes.size} steps with lanes", withLanes.size >= 5)
        for (snapshot in withLanes) {
            val exit = snapshot.route!!.nextManeuver!!.exitAngle ?: continue
            val recommended = snapshot.lanes!!.lanes.filter { it.status == LaneStatus.PREFERRED }.mapNotNull { it.direction }
            assertTrue("$exit: $recommended", recommended.isNotEmpty() && recommended.all { abs(it - exit) <= 45 })
        }
    }

    // The Experiments page starts a demo and stops it again while the app runs: a stopped one draws no more.
    @Test(timeout = 10_000)
    fun `a started demo draws until it is stopped`() {
        val repaints = java.util.concurrent.atomic.AtomicInteger()
        val running = HudDemo(withMedia = false).start({ repaints.incrementAndGet() }) {}
        Thread.sleep(HudDemo.FRAME_MILLIS * 4)
        assertTrue(repaints.get() > 0)
        assertNotNull(running.snapshot().route)

        running.stop()
        Thread.sleep(HudDemo.FRAME_MILLIS * 2)
        val stopped = repaints.get()
        Thread.sleep(HudDemo.FRAME_MILLIS * 4)
        assertEquals(stopped, repaints.get())
    }

    @Test
    fun `only the environment variable turns it on`() {
        assertNull(HudDemo.fromEnvironment(emptyMap()))
        assertEquals(false, HudDemo.fromEnvironment(mapOf("CHAIRPLAY_HUD_DEMO" to "1"))!!.withMedia)
        assertEquals(true, HudDemo.fromEnvironment(mapOf("CHAIRPLAY_HUD_DEMO" to "media"))!!.withMedia)
        val cycle = HudDemo.fromEnvironment(mapOf("CHAIRPLAY_HUD_DEMO" to "cycle"))!!
        assertTrue(cycle.cycle)
        assertTrue(cycle.withMedia)
    }

    @Test
    fun `the lanes demo switches lanes under one maneuver`() {
        val demo = HudDemo.fromEnvironment(mapOf("CHAIRPLAY_HUD_DEMO" to "lanes"))!!
        assertTrue(demo.lanes)
        val events = (0 until demo.laneEvents).map { demo.snapshot(it * HudDemo.LANE_STEP_MILLIS + 100) }
        // The maneuver stays the same, so nothing slides: only the lanes change.
        assertEquals(1, events.map { it.route!!.nextManeuver!!.index to it.route!!.nextManeuver!!.typeCode }.toSet().size)
        // Far enough that the arrow stays quiet: no fill or blink to watch past the lanes.
        val m = hudState(events[0]).maneuver!!
        assertEquals(ArrowProgress.OFF, arrowProgress(m.distanceMeters, m.next!!.distanceBetweenMeters, m.typeCode, 0))
        // Every event is a new one for the bus and the renderer...
        val shown = events.filter { it.route!!.laneGuidanceShowing == true }
        assertEquals(shown.size, shown.map { it.route!!.laneGuidanceIndex }.toSet().size)
        assertTrue(shown.all { it.lanes!!.index == it.route!!.laneGuidanceIndex })
        // ...the recommended lane moves, the row grows past the frame's five cells, and it goes and comes back.
        val recommended = shown.map { it.lanes!!.lanes.indexOfFirst { lane -> lane.statusCode == LaneStatus.PREFERRED.code } }
        assertTrue("recommended lanes $recommended", recommended.toSet().size >= 3)
        assertTrue(shown.any { it.lanes!!.lanes.size > 5 })
        assertTrue(events.any { it.route!!.laneGuidanceShowing == false })
        // And over again.
        assertEquals(events[0].lanes!!.lanes, demo.snapshot(demo.laneEvents * HudDemo.LANE_STEP_MILLIS + 100).lanes!!.lanes)
    }

    @Test
    fun `the cycle brings guidance beside the song and takes it away again`() {
        val demo = HudDemo(withMedia = true, cycle = true)
        fun at(millis: Long) = hudState(demo.snapshot(millis))
        assertNotNull(at(1_000).media)
        assertNull(at(1_000).maneuver)
        assertNotNull(at(5_000).media)
        assertNotNull(at(5_000).maneuver)
        assertNotNull(at(11_000).media)
        assertNull(at(11_000).maneuver)
        assertNull(at(15_000).media)
        assertNull(at(15_000).maneuver)
        // And over again.
        assertNotNull(at(HudDemo.CYCLE_MILLIS + 5_000).maneuver)
    }
}
