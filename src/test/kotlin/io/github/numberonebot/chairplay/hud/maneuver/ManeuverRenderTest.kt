package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.DrivingSide
import io.github.numberonebot.chairplay.data.JunctionType
import io.github.numberonebot.chairplay.data.Maneuver
import io.github.numberonebot.chairplay.data.ManeuverType
import io.github.numberonebot.chairplay.hud.HudDemo
import io.github.numberonebot.chairplay.hud.hudState
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * The renderer in a hidden window's OpenGL 3.3 core context, as the HUD runs it: maneuvers go in as packets, frames
 * come out of its texture. Skipped where no window or GL 3.3 can be had. Each picture is also written to
 * build/maneuver-renders/ for a person to look at.
 */
class ManeuverRenderTest {
    companion object {
        private const val WIDTH = 656
        private const val HEIGHT = 362
        private var window = 0L
        private var texture = 0
        private val out = File("build/maneuver-renders").apply { mkdirs() }

        @BeforeClass
        @JvmStatic
        fun open() {
            assumeTrue("chairplay_maneuver.dll: ${ManeuverNative.loadError}", ManeuverNative.loadError == null)
            assumeTrue("no GLFW", glfwInit())
            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            window = glfwCreateWindow(64, 64, "maneuver test", 0, 0)
            assumeTrue("no OpenGL 3.3 core window", window != 0L)
            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            texture = ManeuverNative.init(WIDTH, HEIGHT)
            assertNotEquals("init: ${ManeuverNative.lastError()}", 0, texture)
            assertTrue("the destination flag", ManeuverNative.loadFlagAtlas())
        }

        @AfterClass
        @JvmStatic
        fun close() {
            if (window != 0L) {
                ManeuverNative.shutdown()
                glfwDestroyWindow(window)
                window = 0L
            }
            glfwTerminate()
        }
    }

    private var clock = 1000.0

    /** Runs frames at 30 fps for [seconds]; returns the last frame's result bits. */
    private fun run(seconds: Double): Int {
        var status = 0
        repeat((seconds * 30).toInt()) {
            clock += 1.0 / 30
            status = ManeuverNative.frame(clock)
        }
        return status
    }

    private fun show(maneuver: Maneuver, progress: ArrowProgress = ArrowProgress.OFF) {
        assertTrue(ManeuverNative.command(ManeuverPackets.maneuver(rendererManeuver(maneuver), progress, refresh = false)))
    }

    private fun pixels(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.nativeOrder())
        assertTrue(ManeuverNative.read(WIDTH, HEIGHT, buffer))
        return buffer
    }

    /** Saves the frame over the HUD's black, top row first. */
    private fun save(name: String, pixels: ByteBuffer) {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val i = ((HEIGHT - 1 - y) * WIDTH + x) * 4
            // Premultiplied over black is the colour itself.
            val rgb = (pixels[i].toInt() and 0xFF shl 16) or (pixels[i + 1].toInt() and 0xFF shl 8) or (pixels[i + 2].toInt() and 0xFF)
            image.setRGB(x, y, rgb)
        }
        ImageIO.write(image, "png", File(out, "$name.png"))
    }

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

    private fun maneuver(type: ManeuverType, exit: Int? = null, roads: List<Int> = emptyList(), junction: JunctionType = JunctionType.INTERSECTION, index: Int = 0) =
        Maneuver(index = index, typeCode = type.code, exitAngle = exit, junctionAngles = roads, junctionType = junction, drivingSide = DrivingSide.RIGHT)

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

    @Test
    fun `progress fills the arrow and the flag keeps waving`() {
        ManeuverNative.command(ManeuverPackets.clear())
        run(0.2)
        show(maneuver(ManeuverType.LEFT_TURN, -90, listOf(-90, 0), index = 0), ArrowProgress(8, ArrowProgress.FILL))
        run(1.5)
        save("progress-half", pixels())
        ManeuverNative.command(ManeuverPackets.progress(ArrowProgress(16, ArrowProgress.BLINK_HIGH)))
        run(0.5)
        save("progress-blink-high", pixels())
        show(maneuver(ManeuverType.ARRIVE_AT_DESTINATION, index = 1))
        val arriving = run(4.0)
        assertTrue("looping: $arriving", arriving and ManeuverNative.LOOPING != 0)
        save("arrive", pixels())
    }

    // Each frame of this atlas is a gradient, red across and green down: the flag drawn from it must show both to
    // their ends. Drawn as a 3D quad in this context, the flag's texture coordinates reached the fragments divided
    // by w after some frames, and it showed the top left quarter of its picture.
    @Test
    fun `the flag's picture covers its whole sprite`() {
        val frames = ManeuverNative.FLAG_FRAMES
        val size = ManeuverNative.FLAG_FRAME
        val atlas = ByteBuffer.allocateDirect(size * frames * size * 4)
        for (y in 0 until size) for (x in 0 until size * frames) {
            atlas.put(((x % size) * 255 / (size - 1)).toByte()).put((y * 255 / (size - 1)).toByte()).put(0).put(-1)
        }
        atlas.flip()
        assertTrue(ManeuverNative.flagAtlas(atlas, size, size, frames))
        try {
            ManeuverNative.command(ManeuverPackets.clear())
            run(0.2)
            show(maneuver(ManeuverType.ARRIVE_AT_DESTINATION, index = 70))
            run(4.0)
            val frame = pixels()
            save("flag-coordinates", frame)
            var across = 0
            var down = 0
            for (i in 0 until WIDTH * HEIGHT) {
                val r = frame[i * 4].toInt() and 0xFF
                val g = frame[i * 4 + 1].toInt() and 0xFF
                val b = frame[i * 4 + 2].toInt() and 0xFF
                // The gradient has no blue; the road's greys and the progress blue do.
                if (maxOf(r, g) > 60 && b * 3 < maxOf(r, g)) {
                    across = maxOf(across, r)
                    down = maxOf(down, g)
                }
            }
            assertTrue("the flag's picture reaches across to $across of 255", across > 220)
            assertTrue("the flag's picture reaches down to $down of 255", down > 220)
        } finally {
            assertTrue(ManeuverNative.loadFlagAtlas())
        }
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
