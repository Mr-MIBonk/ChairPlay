package io.github.numberonebot.chairplay.hud

import io.github.numberonebot.chairplay.data.ManeuverType
import io.github.numberonebot.chairplay.data.RouteGuidanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `only the environment variable turns it on`() {
        assertNull(HudDemo.fromEnvironment(emptyMap()))
        assertEquals(false, HudDemo.fromEnvironment(mapOf("CHAIRPLAY_HUD_DEMO" to "1"))!!.withMedia)
        assertEquals(true, HudDemo.fromEnvironment(mapOf("CHAIRPLAY_HUD_DEMO" to "media"))!!.withMedia)
        val cycle = HudDemo.fromEnvironment(mapOf("CHAIRPLAY_HUD_DEMO" to "cycle"))!!
        assertTrue(cycle.cycle)
        assertTrue(cycle.withMedia)
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
