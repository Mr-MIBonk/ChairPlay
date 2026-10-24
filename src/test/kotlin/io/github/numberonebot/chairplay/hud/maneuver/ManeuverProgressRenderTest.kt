package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.ManeuverType
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** The maneuver renderer: the arrow's progress fill, the arrival's disc, and its waving flag. */
class ManeuverProgressRenderTest : ManeuverRenderFixture() {
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

    // At the arrival the arrow's head turns into a disc over the end of its shaft. The fill rising into the disc must
    // colour both alike: the shaft under the disc filled while the disc stayed white, and as the two lie in one plane
    // they took turns row by row, white stripes across the blue.
    @Test
    fun `the fill rises into the arrival's disc in one piece`() {
        // A sixteenth or two still to go: the front is in the disc.
        for (level in listOf(1, 2, 3)) {
            ManeuverNative.command(ManeuverPackets.clear())
            run(0.2)
            show(maneuver(ManeuverType.ARRIVE_AT_DESTINATION, index = 200 + level), ArrowProgress(level, ArrowProgress.FILL))
            run(3.0)
            val frame = pixels()
            save("arrive-fill-$level", frame)
            // Up the middle columns, bottom row first: blue, then white from the front on, and no white amid blue.
            val worst = (WIDTH / 2 - 12..WIDTH / 2 + 12).maxOf { x ->
                var changes = 0
                var last: Boolean? = null
                for (y in 0 until HEIGHT) {
                    val i = (y * WIDTH + x) * 4
                    val r = frame[i].toInt() and 0xFF
                    val g = frame[i + 1].toInt() and 0xFF
                    val b = frame[i + 2].toInt() and 0xFF
                    val blue = b > 200 && r < 150 && b - r > 80
                    val white = r > 200 && g > 200 && b > 200
                    if (!blue && !white) continue
                    if (last != null && last != blue) changes++
                    last = blue
                }
                changes
            }
            assertTrue("level $level: the colour changes $worst times up a column", worst <= 1)
        }
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
}
