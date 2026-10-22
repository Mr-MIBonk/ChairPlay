package io.github.numberonebot.chairplay.hud.maneuver

import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverPlacementTest {
    @Test
    fun `the shown part fills the box and the texture has the frame's shape at the box's scale`() {
        // The shown part: the crop (59, 27, 210 x 153) with room at the sides for the roads to fade out, and the
        // frame's top, where the arrival flag stands.
        assertEquals(ManeuverPlacement(328, 181, 63f, 50f, 328f, 181f), maneuverPlacement(100f, 50f, 254f, 180f))
        val doubled = maneuverPlacement(0f, 0f, 508f, 360f)
        assertEquals(656, doubled.textureWidth)
        assertEquals(362, doubled.textureHeight)
        assertEquals(-74f, doubled.frameX, 0.01f)
        assertEquals(0f, doubled.frameY, 0.01f)
        assertEquals(254f / 180f, ManeuverPlacement.ASPECT, 0.0001f)
    }

    // The HUD fades the frame's bottom into its black where the roads leave it; under the lane row the renderer has
    // already ended them, and the fade would dim the arrows. It goes and comes back with the row's 250 ms fade.
    @Test
    fun `the bottom fade gives way to the lane row`() {
        val full = ManeuverPlacement.BOTTOM_FADE
        assertEquals(full, bottomFade(lanes = false, sinceChangeMillis = 10_000), 0f)
        assertEquals(0f, bottomFade(lanes = true, sinceChangeMillis = 10_000), 0f)
        assertEquals(full, bottomFade(lanes = true, sinceChangeMillis = 0), 0f)
        assertEquals(0f, bottomFade(lanes = false, sinceChangeMillis = 0), 0f)
        assertEquals(full / 2, bottomFade(lanes = true, sinceChangeMillis = 125), 0.001f)
        assertEquals(0f, bottomFade(lanes = true, sinceChangeMillis = 250), 0f)
    }

    @Test
    fun `a tiny box still gets a usable texture`() {
        val tiny = maneuverPlacement(0f, 0f, 2f, 2f)
        assertEquals(64, tiny.textureWidth)
        assertEquals(36, tiny.textureHeight)
    }

    @Test
    fun `a huge box gets a texture of limited size, which NanoVG scales up`() {
        val huge = maneuverPlacement(0f, 0f, 2540f, 1800f)
        assertEquals(ManeuverPlacement.MAX_TEXTURE_WIDTH, huge.textureWidth)
        assertEquals(Math.round(ManeuverPlacement.MAX_TEXTURE_WIDTH * 181f / 328f), huge.textureHeight)
        assertEquals(3280f, huge.frameWidth, 0.5f)
    }

    @Test
    fun `a box that shrinks during an animation keeps the texture of its sharp size`() {
        val sharp = maneuverPlacement(0f, 0f, 254f, 180f)
        val shrinking = maneuverPlacement(10f, 20f, 127f, 90f, sharpHeight = 180f)
        assertEquals(sharp.textureWidth, shrinking.textureWidth)
        assertEquals(sharp.textureHeight, shrinking.textureHeight)
        // The picture itself still fills the smaller box.
        assertEquals(maneuverPlacement(10f, 20f, 127f, 90f).frameWidth, shrinking.frameWidth, 0.01f)
        assertEquals(maneuverPlacement(10f, 20f, 127f, 90f).frameX, shrinking.frameX, 0.01f)
    }
}
