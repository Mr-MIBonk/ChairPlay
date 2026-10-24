package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.DrivingSide
import io.github.numberonebot.chairplay.data.JunctionType
import io.github.numberonebot.chairplay.data.Maneuver
import io.github.numberonebot.chairplay.data.ManeuverType
import org.junit.AfterClass
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * What the maneuver renderer tests share: the renderer in a hidden window's OpenGL 3.3 core context, as the HUD runs
 * it. Maneuvers go in as packets, frames come out of its texture. Skipped where no window or GL 3.3 can be had. Each
 * picture is also written to build/maneuver-renders/ for a person to look at.
 */
abstract class ManeuverRenderFixture {
    companion object {
        protected const val WIDTH = 656
        protected const val HEIGHT = 362
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

    protected var clock = 1000.0

    /** Runs frames at 30 fps for [seconds]; returns the last frame's result bits. */
    protected fun run(seconds: Double): Int {
        var status = 0
        repeat((seconds * 30).toInt()) {
            clock += 1.0 / 30
            status = ManeuverNative.frame(clock)
        }
        return status
    }

    protected fun show(maneuver: Maneuver, progress: ArrowProgress = ArrowProgress.OFF) {
        assertTrue(ManeuverNative.command(ManeuverPackets.maneuver(rendererManeuver(maneuver), progress, refresh = false)))
    }

    protected fun pixels(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.nativeOrder())
        assertTrue(ManeuverNative.read(WIDTH, HEIGHT, buffer))
        return buffer
    }

    /** Saves the frame over the HUD's black, top row first. */
    protected fun save(name: String, pixels: ByteBuffer) {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val i = ((HEIGHT - 1 - y) * WIDTH + x) * 4
            // Premultiplied over black is the colour itself.
            val rgb = (pixels[i].toInt() and 0xFF shl 16) or (pixels[i + 1].toInt() and 0xFF shl 8) or (pixels[i + 2].toInt() and 0xFF)
            image.setRGB(x, y, rgb)
        }
        ImageIO.write(image, "png", File(out, "$name.png"))
    }

    protected fun maneuver(type: ManeuverType, exit: Int? = null, roads: List<Int> = emptyList(), junction: JunctionType = JunctionType.INTERSECTION, index: Int = 0) =
        Maneuver(index = index, typeCode = type.code, exitAngle = exit, junctionAngles = roads, junctionType = junction, drivingSide = DrivingSide.RIGHT)
}
