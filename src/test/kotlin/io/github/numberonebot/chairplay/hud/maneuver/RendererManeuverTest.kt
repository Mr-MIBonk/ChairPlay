package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.DrivingSide
import io.github.numberonebot.chairplay.data.JunctionType
import io.github.numberonebot.chairplay.data.Maneuver
import io.github.numberonebot.chairplay.data.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Test

class RendererManeuverTest {
    private fun maneuver(
        type: ManeuverType?,
        exit: Int? = null,
        roads: List<Int> = emptyList(),
        junction: JunctionType? = JunctionType.INTERSECTION,
        side: DrivingSide? = DrivingSide.RIGHT,
    ) = Maneuver(index = 0, typeCode = type?.code, exitAngle = exit, junctionAngles = roads, junctionType = junction, drivingSide = side)

    private fun expect(icon: Int, exitAngle: Int, direction: Int = 0, roads: List<Int> = emptyList(), side: Int = 0, snap: Boolean = false) =
        RendererManeuver(icon, direction, exitAngle, side, roads, snap)

    @Test
    fun `a left turn keeps its exact angle and roads in half-degrees`() {
        assertEquals(
            expect(RendererIcon.TURN, -180, roads = listOf(-180, 0, 180)),
            rendererManeuver(maneuver(ManeuverType.LEFT_TURN, -90, listOf(-90, 0, 90))),
        )
        assertEquals(expect(RendererIcon.TURN, -150), rendererManeuver(maneuver(ManeuverType.LEFT_TURN, -75)))
    }

    @Test
    fun `the type decides the side when the angle is missing or points the other way`() {
        assertEquals(expect(RendererIcon.TURN, -180), rendererManeuver(maneuver(ManeuverType.LEFT_TURN, 90)))
        assertEquals(expect(RendererIcon.TURN, -180), rendererManeuver(maneuver(ManeuverType.LEFT_TURN, 0)))
        assertEquals(expect(RendererIcon.TURN, 180), rendererManeuver(maneuver(ManeuverType.RIGHT_TURN)))
        assertEquals(expect(RendererIcon.TURN, 0), rendererManeuver(maneuver(ManeuverType.STRAIGHT_AHEAD, 10)))
    }

    @Test
    fun `slight and sharp turns keep their angle`() {
        assertEquals(expect(RendererIcon.TURN, 60), rendererManeuver(maneuver(ManeuverType.SLIGHT_RIGHT_TURN, 30)))
        assertEquals(expect(RendererIcon.TURN, 90), rendererManeuver(maneuver(ManeuverType.SLIGHT_RIGHT_TURN)))
        assertEquals(expect(RendererIcon.TURN, -320), rendererManeuver(maneuver(ManeuverType.SHARP_LEFT_TURN, -160)))
        assertEquals(expect(RendererIcon.TURN, -270), rendererManeuver(maneuver(ManeuverType.SHARP_LEFT_TURN)))
    }

    @Test
    fun `a u-turn goes the way its angle says, else away from the traffic side`() {
        assertEquals(expect(RendererIcon.UTURN, -360, direction = -1), rendererManeuver(maneuver(ManeuverType.U_TURN)))
        assertEquals(
            expect(RendererIcon.UTURN, 360, direction = 1, side = 1),
            rendererManeuver(maneuver(ManeuverType.U_TURN, side = DrivingSide.LEFT)),
        )
        assertEquals(expect(RendererIcon.UTURN, 360, direction = 1), rendererManeuver(maneuver(ManeuverType.U_TURN_WHEN_POSSIBLE, 170)))
    }

    @Test
    fun `a roundabout exit draws the ring with the raw exit angle and every road, snapping only to a real angle`() {
        val roads = listOf(-135, -67, 0, 90)
        assertEquals(
            expect(RendererIcon.ROUNDABOUT, -134, roads = listOf(-270, -134, 0, 180), snap = true),
            rendererManeuver(maneuver(ManeuverType.ROUNDABOUT_EXIT_2, -67, roads, JunctionType.ROUNDABOUT)),
        )
        assertEquals(
            expect(RendererIcon.ROUNDABOUT, 0, roads = listOf(-270, -134, 0, 180)),
            rendererManeuver(maneuver(ManeuverType.ROUNDABOUT_EXIT_2, null, roads, JunctionType.ROUNDABOUT)),
        )
        assertEquals(
            expect(RendererIcon.ROUNDABOUT, 180, side = 1, snap = true),
            rendererManeuver(maneuver(ManeuverType.ROUNDABOUT_EXIT_1, 90, junction = JunctionType.ROUNDABOUT, side = DrivingSide.LEFT)),
        )
    }

    @Test
    fun `entering and leaving a roundabout draw the full ring too`() {
        assertEquals(
            expect(RendererIcon.ROUNDABOUT, 90, snap = true),
            rendererManeuver(maneuver(ManeuverType.ENTER_ROUNDABOUT, 45)),
        )
        assertEquals(
            expect(RendererIcon.ROUNDABOUT, 90, snap = true),
            rendererManeuver(maneuver(ManeuverType.EXIT_ROUNDABOUT, 45, junction = JunctionType.ROUNDABOUT)),
        )
    }

    @Test
    fun `a u-turn at a roundabout leaves it backwards and never snaps`() {
        assertEquals(
            expect(RendererIcon.ROUNDABOUT, 360, roads = listOf(0, 180)),
            rendererManeuver(maneuver(ManeuverType.U_TURN_AT_ROUNDABOUT, 170, listOf(0, 90), JunctionType.ROUNDABOUT)),
        )
    }

    @Test
    fun `arriving puts the flag left, right or ahead`() {
        assertEquals(expect(RendererIcon.ARRIVED, 0), rendererManeuver(maneuver(ManeuverType.ARRIVE_AT_DESTINATION)))
        assertEquals(expect(RendererIcon.ARRIVED, 0, direction = -1), rendererManeuver(maneuver(ManeuverType.ARRIVE_AT_DESTINATION_LEFT)))
        assertEquals(expect(RendererIcon.ARRIVED, 0, direction = 1), rendererManeuver(maneuver(ManeuverType.ARRIVE_AT_DESTINATION_RIGHT)))
    }

    @Test
    fun `ramps are one slight bend and an off-ramp keeps the road ahead`() {
        assertEquals(expect(RendererIcon.TURN, 90, roads = listOf(0)), rendererManeuver(maneuver(ManeuverType.OFF_RAMP, 20)))
        assertEquals(expect(RendererIcon.TURN, -90, roads = listOf(0)), rendererManeuver(maneuver(ManeuverType.OFF_RAMP, -20)))
        assertEquals(expect(RendererIcon.TURN, -90, side = 1), rendererManeuver(maneuver(ManeuverType.ON_RAMP, side = DrivingSide.LEFT)))
        assertEquals(
            expect(RendererIcon.TURN, 90, roads = listOf(60, 0)),
            rendererManeuver(maneuver(ManeuverType.HIGHWAY_OFF_RAMP_RIGHT, -30, listOf(30, 0))),
        )
    }

    @Test
    fun `changing highway is a lane change`() {
        assertEquals(expect(RendererIcon.LANE_CHANGE, 0, direction = -1), rendererManeuver(maneuver(ManeuverType.CHANGE_HIGHWAY_LEFT)))
        assertEquals(expect(RendererIcon.LANE_CHANGE, 0, direction = 1), rendererManeuver(maneuver(ManeuverType.CHANGE_HIGHWAY_RIGHT)))
    }

    @Test
    fun `following the road shows the approach with its side streets`() {
        assertEquals(expect(RendererIcon.APPROACH, 0, roads = listOf(-180)), rendererManeuver(maneuver(ManeuverType.FOLLOW_ROAD, roads = listOf(-90))))
        assertEquals(expect(RendererIcon.APPROACH, 0), rendererManeuver(maneuver(ManeuverType.ENTER_FERRY)))
    }

    @Test
    fun `a generic maneuver follows its angle`() {
        assertEquals(expect(RendererIcon.TURN, 60), rendererManeuver(maneuver(ManeuverType.START_ROUTE, 30)))
        assertEquals(expect(RendererIcon.TURN, 0), rendererManeuver(maneuver(ManeuverType.START_ROUTE)))
    }

    @Test
    fun `nothing to draw without a type or for a turn the junction type rules out`() {
        assertEquals(RendererIcon.NONE, rendererManeuver(maneuver(null)).icon)
        assertEquals(RendererIcon.NONE, rendererManeuver(Maneuver(index = 0, typeCode = 99)).icon)
        assertEquals(RendererIcon.NONE, rendererManeuver(maneuver(ManeuverType.LEFT_TURN, junction = JunctionType.ROUNDABOUT)).icon)
    }

    @Test
    fun `a missing junction type counts as an intersection, or a roundabout for roundabout exits`() {
        assertEquals(expect(RendererIcon.TURN, -180), rendererManeuver(maneuver(ManeuverType.LEFT_TURN, junction = null)))
        assertEquals(
            expect(RendererIcon.ROUNDABOUT, 180, snap = true),
            rendererManeuver(maneuver(ManeuverType.ROUNDABOUT_EXIT_3, 90, junction = null)),
        )
    }

    @Test
    fun `roads drop the missing-angle markers and stop at eighteen`() {
        assertEquals(listOf(0, 180), rendererManeuver(maneuver(ManeuverType.LEFT_TURN, -90, listOf(1000, 0, -1000, 90, 200))).junctionAngles)
        assertEquals(18, rendererManeuver(maneuver(ManeuverType.FOLLOW_ROAD, roads = (1..25).toList())).junctionAngles.size)
    }
}
