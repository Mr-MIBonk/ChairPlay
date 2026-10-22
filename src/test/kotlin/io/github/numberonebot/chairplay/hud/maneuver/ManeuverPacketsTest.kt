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

    // RendererServer.lanePacket: BEGIN, one LANE per lane, COMMIT, all with the batch's token.
    @Test
    fun `lanes go as one batch, as the MHI2Q bridge sends them`() {
        val lanes = RendererLanes(
            eventIndex = 258, showing = true, complete = true,
            lanes = listOf(RendererLane(0, 2, -90, listOf(-90, 0)), RendererLane(1, 255, 1000, emptyList())),
        )
        val batch = ManeuverPackets.lanes(lanes, token = 0x01020304)
        assertEquals(4, batch.size)
        batch.forEach { assertEquals(48, it.size) }
        // Begin: token, count, complete, showing, a spare byte, the event index.
        assertArrayEquals(bytes(0x0C, 0, 1, 2, 3, 4, 2, 1, 1, 0, 0, 0, 1, 2), batch[0].copyOfRange(0, 14))
        assertArrayEquals(ByteArray(34), batch[0].copyOfRange(14, 48))
        // A lane: token, record, position, status, angle count, primary angle, the angles.
        assertArrayEquals(bytes(0x0D, 0, 1, 2, 3, 4, 0, 0, 0, 2, 2, 0xFF, 0xA6, 0xFF, 0xA6, 0, 0), batch[1].copyOfRange(0, 17))
        assertArrayEquals(ByteArray(31), batch[1].copyOfRange(17, 48))
        assertArrayEquals(bytes(0x0D, 0, 1, 2, 3, 4, 1, 0, 1, 0xFF, 0, 0x03, 0xE8), batch[2].copyOfRange(0, 13))
        assertArrayEquals(bytes(0x0E, 0, 1, 2, 3, 4), batch[3].copyOfRange(0, 6))
        assertArrayEquals(ByteArray(42), batch[3].copyOfRange(6, 48))
    }

    @Test
    fun `hidden lanes are a batch without lanes`() {
        val batch = ManeuverPackets.lanes(RendererLanes.HIDDEN, token = 5)
        assertEquals(listOf(0x0C, 0x0E), batch.map { it[0].toInt() })
        // No lanes, not complete, not showing, event -1.
        assertArrayEquals(bytes(0, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF), batch[0].copyOfRange(6, 14))
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
