package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.JunctionType
import io.github.numberonebot.chairplay.data.Lane
import io.github.numberonebot.chairplay.data.LaneGuidance
import io.github.numberonebot.chairplay.data.LaneStatus
import io.github.numberonebot.chairplay.data.ManeuverType
import io.github.numberonebot.chairplay.hud.HudDemo
import io.github.numberonebot.chairplay.hud.hudState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** The maneuver renderer: the shaders, each maneuver's arrow, transitions, lanes, and the demo route through the feed. */
class ManeuverRenderTest : ManeuverRenderFixture() {
    /**
     * The bright, near-white route pixels: how many in the whole frame, and their mean x (0 left, 1 right) in its
     * upper 60 %, above the entry road.
     */
    private fun routeCentre(pixels: ByteBuffer): Pair<Double, Int> {
        var sum = 0.0
        var upper = 0
        var count = 0
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val i = (y * WIDTH + x) * 4
            val r = pixels[i].toInt() and 0xFF
            val g = pixels[i + 1].toInt() and 0xFF
            val b = pixels[i + 2].toInt() and 0xFF
            if (r > 200 && g > 200 && b > 180) {
                count++
                // Rows run bottom up.
                if (y > HEIGHT * 2 / 5) {
                    sum += x.toDouble() / WIDTH
                    upper++
                }
            }
        }
        return (if (upper == 0) 0.5 else sum / upper) to count
    }

    @Test
    fun `the shaders translate to GLSL 3_30 core`() {
        val vertex = ManeuverNative.glslToCore("attribute vec3 a_pos;\nvarying vec2 v_uv;\nvoid main(){}", false)!!
        assertTrue(vertex, vertex.startsWith("#version 330 core\n"))
        assertTrue(vertex, vertex.contains("in vec3 a_pos;\nout vec2 v_uv;"))
        val fragment = ManeuverNative.glslToCore("varying vec2 v_uv;\nuniform sampler2D u_tex;\nvoid main(){gl_FragColor=texture2D(u_tex,v_uv);}", true)!!
        assertTrue(fragment, fragment.contains("out vec4 cr_FragColor;"))
        assertTrue(fragment, fragment.contains("in vec2 v_uv;"))
        assertTrue(fragment, fragment.contains("cr_FragColor=texture(u_tex,v_uv);"))
        // Only whole words change.
        assertEquals("#version 330 core\nfloat varyings; vec4 texture2Dx;", ManeuverNative.glslToCore("float varyings; vec4 texture2Dx;", false))
    }

    @Test
    fun `turns draw to their side, and every maneuver draws something`() {
        val cases = listOf(
            "left" to maneuver(ManeuverType.LEFT_TURN, -90, listOf(-90, 0, 90)),
            "right" to maneuver(ManeuverType.RIGHT_TURN, 90, listOf(-90, 0, 90)),
            "slight-left" to maneuver(ManeuverType.SLIGHT_LEFT_TURN, -35, listOf(-35, 0)),
            "sharp-right" to maneuver(ManeuverType.SHARP_RIGHT_TURN, 140, listOf(140, 0, -90)),
            "straight" to maneuver(ManeuverType.STRAIGHT_AHEAD, 0, listOf(-90, 0, 90)),
            "u-turn" to maneuver(ManeuverType.U_TURN),
            "roundabout-exit-2" to maneuver(ManeuverType.ROUNDABOUT_EXIT_2, 0, listOf(-90, 0, 90, 180), JunctionType.ROUNDABOUT),
            "roundabout-exit-3" to maneuver(ManeuverType.ROUNDABOUT_EXIT_3, 90, listOf(-90, 0, 90, 180), JunctionType.ROUNDABOUT),
            "off-ramp" to maneuver(ManeuverType.OFF_RAMP, 25),
            "lane-change" to maneuver(ManeuverType.CHANGE_HIGHWAY_RIGHT),
            "follow" to maneuver(ManeuverType.FOLLOW_ROAD, roads = listOf(-90, 90)),
            "arrive-right" to maneuver(ManeuverType.ARRIVE_AT_DESTINATION_RIGHT),
        )
        val centres = mutableMapOf<String, Double>()
        cases.forEachIndexed { i, (name, m) ->
            ManeuverNative.command(ManeuverPackets.clear())
            run(0.2)
            show(m.copy(index = i))
            run(1.5)
            val frame = pixels()
            save(name, frame)
            val (centre, count) = routeCentre(frame)
            assertTrue("$name drew $count route pixels", count > 200)
            centres[name] = centre
        }
        assertTrue("left ${centres["left"]}", centres.getValue("left") < 0.47)
        assertTrue("right ${centres["right"]}", centres.getValue("right") > 0.53)
    }

    @Test
    fun `a transition animates and then settles`() {
        ManeuverNative.command(ManeuverPackets.clear())
        run(0.2)
        show(maneuver(ManeuverType.LEFT_TURN, -90, listOf(-90, 0), index = 0))
        run(2.0)
        show(maneuver(ManeuverType.RIGHT_TURN, 90, listOf(0, 90), index = 1))
        val moving = run(0.3)
        assertTrue("animating: $moving", moving and ManeuverNative.ANIMATING != 0)
        save("transition-mid", pixels())
        val settled = run(4.0)
        assertEquals("settled: $settled", 0, settled and (ManeuverNative.ANIMATING or ManeuverNative.LOOPING))
        assertTrue(settled and ManeuverNative.SHOWING != 0)
        save("transition-end", pixels())
        // Nothing changed: no frame is drawn again.
        assertEquals(0, ManeuverNative.frame(clock + 0.1) and ManeuverNative.RENDERED)
    }

    /** The lane panel's blue pixels (a recommended lane, lane_panel.cpp) in the frame's bottom 34 of 181 rows. */
    private fun bluePanelPixels(pixels: ByteBuffer): Int {
        var count = 0
        // Rows run bottom up.
        for (y in 0 until HEIGHT * 34 / 181) for (x in 0 until WIDTH) {
            val i = (y * WIDTH + x) * 4
            val r = pixels[i].toInt() and 0xFF
            val g = pixels[i + 1].toInt() and 0xFF
            val b = pixels[i + 2].toInt() and 0xFF
            if (r < 90 && g > 120 && b > 170) count++
        }
        return count
    }

    private fun lane(position: Int, status: LaneStatus, vararg angles: Int, highlight: Int? = null) =
        Lane(position, status.code, angles.toList(), highlight)

    @Test
    fun `lanes show under the maneuver and go again`() {
        val cases = listOf(
            "lanes-left" to (
                maneuver(ManeuverType.LEFT_TURN, -90, listOf(-90, 0, 90)) to LaneGuidance(
                    0,
                    listOf(
                        lane(0, LaneStatus.PREFERRED, -90, highlight = -90),
                        lane(1, LaneStatus.GOOD, -90, 0, highlight = -90),
                        lane(2, LaneStatus.NOT_GOOD, 0),
                        lane(3, LaneStatus.NOT_GOOD, 0, 90),
                    ),
                )
                ),
            "lanes-off-ramp" to (
                maneuver(ManeuverType.OFF_RAMP, 25) to LaneGuidance(
                    1,
                    listOf(
                        lane(0, LaneStatus.NOT_GOOD, 0),
                        lane(1, LaneStatus.NOT_GOOD, 0),
                        lane(2, LaneStatus.GOOD, 0, 45),
                        lane(3, LaneStatus.PREFERRED, 45, highlight = 45),
                    ),
                )
                ),
            // More lanes than the frame's 5 cells: the end away from the recommended lanes becomes an ellipsis.
            "lanes-overflow" to (
                maneuver(ManeuverType.KEEP_RIGHT, 20, listOf(0, 20)) to LaneGuidance(
                    2, List(7) { lane(it, if (it >= 5) LaneStatus.PREFERRED else LaneStatus.NOT_GOOD, 0, 45, highlight = if (it >= 5) 45 else 0) },
                )
                ),
            "lanes-roundabout" to (
                maneuver(ManeuverType.ROUNDABOUT_EXIT_3, 90, listOf(-90, 0, 90, 180), JunctionType.ROUNDABOUT) to LaneGuidance(
                    3,
                    listOf(lane(0, LaneStatus.NOT_GOOD, -90, 0), lane(1, LaneStatus.PREFERRED, 0, 90, highlight = 90)),
                )
                ),
        )
        cases.forEachIndexed { i, (name, case) ->
            val (m, guidance) = case
            ManeuverNative.command(ManeuverPackets.clear())
            run(0.2)
            ManeuverPackets.lanes(rendererLanes(guidance), token = i + 1).forEach { assertTrue(ManeuverNative.command(it)) }
            show(m.copy(index = i))
            run(1.5)
            val frame = pixels()
            save(name, frame)
            assertTrue("$name drew ${bluePanelPixels(frame)} blue lane pixels", bluePanelPixels(frame) > 100)
            assertTrue("$name drew the maneuver", routeCentre(frame).second > 200)
        }
        // Hidden again: the panel fades out and the maneuver stays.
        ManeuverPackets.lanes(RendererLanes.HIDDEN, token = 99).forEach { ManeuverNative.command(it) }
        run(1.0)
        val hidden = pixels()
        save("lanes-hidden", hidden)
        assertEquals(0, bluePanelPixels(hidden))
        assertTrue(routeCentre(hidden).second > 200)
    }

    // The HUD's whole path, from the bus snapshot through hudState and ManeuverFeed, at 60 frames a second.
    @Test
    fun `the demo route plays through the feed and every maneuver ends up on screen`() {
        ManeuverNative.command(ManeuverPackets.clear())
        run(0.2)
        val demo = HudDemo(withMedia = false)
        val feed = ManeuverFeed()
        var millis = 0L
        while (millis < HudDemo.STEP_MILLIS * demo.steps) {
            for (packet in feed.update(hudState(demo.snapshot(millis)).maneuver, millis)) ManeuverNative.command(packet)
            val status = ManeuverNative.frame(clock + millis / 1000.0)
            // Once the maneuver has settled, and before the arrow blinks blue over the last metres.
            if (millis % HudDemo.STEP_MILLIS in 4_000L until 4_016L) {
                val step = millis / HudDemo.STEP_MILLIS + 1
                val frame = pixels()
                save("demo-$step", frame)
                assertTrue("step $step showing", status and ManeuverNative.SHOWING != 0)
                assertTrue("step $step drew", routeCentre(frame).second > 200)
            }
            millis += 16
        }
    }
}
