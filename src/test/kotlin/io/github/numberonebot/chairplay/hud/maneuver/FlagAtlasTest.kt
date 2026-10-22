package io.github.numberonebot.chairplay.hud.maneuver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FlagAtlasTest {
    private fun assertAtlas(path: String) {
        val bytes = ManeuverNative::class.java.getResourceAsStream(path)?.use { it.readBytes() }
        assertNotNull("$path in the app's resources", bytes)
        assertEquals(path, ManeuverNative.FLAG_FRAME * ManeuverNative.FLAG_FRAMES * ManeuverNative.FLAG_FRAME * 4, bytes!!.size)
    }

    @Test
    fun `the app ships the MHI2Q project's destination flag, 14 frames of 128 x 128 RGBA`() {
        assertAtlas(ManeuverNative.FLAG_ATLAS)
    }

    @Test
    fun `and keeps ChairPlay's own chequered flag of the same shape beside it`() {
        assertAtlas(ManeuverNative.FLAG_ATLAS_CHAIRPLAY)
    }
}
