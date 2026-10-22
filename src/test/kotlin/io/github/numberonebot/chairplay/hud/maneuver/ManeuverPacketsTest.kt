package io.github.numberonebot.chairplay.hud.maneuver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverPacketsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `a maneuver packet carries the geometry, 3D and its progress, as the MHI2Q bridge sends it`() {
        val left = RendererManeuver(RendererIcon.TURN, 0, -180, 0, listOf(-180, 0, 180), snapToRoad = false)
        val packet = ManeuverPackets.maneuver(left, ArrowProgress(14, ArrowProgress.FILL), refresh = false)
        assertEquals(48, packet.size)
        // Command, then flags: perspective, progress, half-degree geometry and the explicit progress state.
        assertArrayEquals(bytes(0x01, 0x01 or 0x02 or 0x04 or 0x20), packet.copyOfRange(0, 2))
        // Icon, direction, exit angle (big-endian), driving side, road count, roads.
        assertArrayEquals(bytes(2, 0, 0xFF, 0x4C, 0, 3, 0xFF, 0x4C, 0, 0, 0, 0xB4), packet.copyOfRange(2, 14))
        assertArrayEquals(ByteArray(30), packet.copyOfRange(14, 44))
        // Progress state, perspective 3D, remaining level, mode.
        assertArrayEquals(bytes(ArrowProgress.FILL, 1, 14, 1), packet.copyOfRange(44, 48))
    }

    @Test
    fun `a roads-only change and a snapping roundabout set their flags`() {
        val ring = RendererManeuver(RendererIcon.ROUNDABOUT, 0, 90, 1, emptyList(), snapToRoad = true)
        val packet = ManeuverPackets.maneuver(ring, ArrowProgress.OFF, refresh = true)
        assertEquals(0x01 or 0x02 or 0x04 or 0x08 or 0x10 or 0x20, packet[1].toInt())
        assertArrayEquals(bytes(6, 0, 0, 90, 1, 0), packet.copyOfRange(2, 8))
        assertArrayEquals(bytes(ArrowProgress.QUIET, 1, 0, 0), packet.copyOfRange(44, 48))
    }

    @Test
    fun `a negative direction is one signed byte`() {
        val uturn = RendererManeuver(RendererIcon.UTURN, -1, -360, 0, emptyList(), snapToRoad = false)
        assertArrayEquals(bytes(3, 0xFF, 0xFE, 0x98), ManeuverPackets.maneuver(uturn, ArrowProgress.OFF, false).copyOfRange(2, 6))
    }

    @Test
    fun `progress and clear are packets of their own`() {
        val blink = ManeuverPackets.progress(ArrowProgress(0, ArrowProgress.BLINK_LOW))
        assertArrayEquals(bytes(0x06, 0x20, 0, 1, ArrowProgress.BLINK_LOW), blink.copyOfRange(0, 5))
        assertArrayEquals(bytes(0x06, 0x20, 0, 0, ArrowProgress.QUIET), ManeuverPackets.progress(ArrowProgress.OFF).copyOfRange(0, 5))
        val clear = ManeuverPackets.clear()
        assertEquals(48, clear.size)
        assertEquals(0x07, clear[0].toInt())
    }
}
