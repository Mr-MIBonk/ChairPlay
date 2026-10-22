package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.DrivingSide
import io.github.numberonebot.chairplay.data.JunctionType
import io.github.numberonebot.chairplay.data.Maneuver

/** The maneuver renderer's icon types (native/maneuver/maneuver.h). */
object RendererIcon {
    const val NONE = 0
    const val APPROACH = 1
    const val TURN = 2
    const val UTURN = 3
    const val MERGE = 4
    const val EXIT = 5
    const val ROUNDABOUT = 6
    const val ARRIVED = 7
    const val LANE_CHANGE = 8
    const val ROUNDABOUT_EXIT = 9
}

/**
 * One maneuver as the renderer takes it (protocol.h, CMD_MANEUVER): angles in signed half-degrees, negative to the
 * left, as the MHI2Q project sends them with MAN_FLAG_BAP_GEOMETRY.
 */
data class RendererManeuver(
    /** One of [RendererIcon]. */
    val icon: Int,
    /** -1 left, 0 ahead, 1 right, for u-turns, lane changes and arrivals. */
    val direction: Int,
    val exitAngle: Int,
    /** 0 right-hand traffic, 1 left-hand. */
    val drivingSide: Int,
    /** The junction's roads, the exit among them, at most [MAX_ROADS]. */
    val junctionAngles: List<Int>,
    /** A roundabout's exit may be moved onto the nearest road the iPhone sent. */
    val snapToRoad: Boolean,
) {
    companion object {
        const val MAX_ROADS = 18
    }
}

/**
 * What the renderer draws for [maneuver]: the MHI2Q project's two steps, ManeuverMapper (Apple's type to the
 * Audi HUD's semantics, BAP main element and direction) and RendererMapper (that semantics plus the exact angle
 * and roads the iPhone sent), in java_patch/com/luka/carplay/rgd/. One difference: a maneuver without a junction
 * type counts as an intersection, or a roundabout for the roundabout exits, where the MHI2Q project draws nothing.
 */
fun rendererManeuver(maneuver: Maneuver): RendererManeuver {
    val type = maneuver.typeCode ?: NOT_SET
    val side = if (maneuver.drivingSide == DrivingSide.LEFT) LEFT_TRAFFIC else RIGHT_TRAFFIC
    val junction = when (maneuver.junctionType) {
        JunctionType.INTERSECTION -> INTERSECTION
        JunctionType.ROUNDABOUT -> ROUNDABOUT
        null -> if (type == MT_EXIT_ROUNDABOUT || type in MT_ROUNDABOUT_EXITS) ROUNDABOUT else INTERSECTION
    }
    val angle = maneuver.exitAngle
    val (main, direction) = bapManeuver(type, angle ?: UNKNOWN_ANGLE, junction, side)
    return withGeometry(fromBap(main, direction, side), type, angle, maneuver.junctionAngles)
}

// --- ManeuverMapper: Apple's type to BAP main element and direction ---

private const val NOT_SET = -1
private const val UNKNOWN_ANGLE = 1000
private const val INTERSECTION = 0
private const val ROUNDABOUT = 1
private const val RIGHT_TRAFFIC = 0
private const val LEFT_TRAFFIC = 1

// BAP main elements.
private const val NO_SYMBOL = 0
private const val NO_INFO = 1
private const val ARRIVED = 3
private const val FOLLOW_STREET = 11
private const val CHANGE_LANE = 12
private const val TURN = 13
private const val ROUNDABOUT_TRS_RIGHT = 21
private const val ROUNDABOUT_TRS_LEFT = 22
private const val UTURN = 25
private const val EXIT_ROUNDABOUT_TRS_RIGHT = 26
private const val EXIT_ROUNDABOUT_TRS_LEFT = 27

// BAP directions: 256 steps counter-clockwise from straight ahead.
private const val DIR_STRAIGHT = 0
private const val DIR_SLIGHT_LEFT = 32
private const val DIR_LEFT = 64
private const val DIR_SHARP_LEFT = 96
private const val DIR_UTURN = 128
private const val DIR_SHARP_RIGHT = 160
private const val DIR_RIGHT = 192
private const val DIR_SLIGHT_RIGHT = 224

// Apple's maneuver types (ManeuverType codes).
private const val MT_NO_TURN = 0
private const val MT_LEFT_TURN = 1
private const val MT_RIGHT_TURN = 2
private const val MT_STRAIGHT_AHEAD = 3
private const val MT_U_TURN = 4
private const val MT_FOLLOW_ROAD = 5
private const val MT_ENTER_ROUNDABOUT = 6
private const val MT_EXIT_ROUNDABOUT = 7
private const val MT_OFF_RAMP = 8
private const val MT_ON_RAMP = 9
private const val MT_ARRIVE_END_OF_NAVIGATION = 10
private const val MT_START_ROUTE = 11
private const val MT_ARRIVE_AT_DESTINATION = 12
private const val MT_KEEP_LEFT = 13
private const val MT_KEEP_RIGHT = 14
private const val MT_ENTER_FERRY = 15
private const val MT_EXIT_FERRY = 16
private const val MT_CHANGE_FERRY = 17
private const val MT_START_ROUTE_WITH_U_TURN = 18
private const val MT_U_TURN_AT_ROUNDABOUT = 19
private const val MT_LEFT_TURN_AT_END = 20
private const val MT_RIGHT_TURN_AT_END = 21
private const val MT_HIGHWAY_OFF_RAMP_LEFT = 22
private const val MT_HIGHWAY_OFF_RAMP_RIGHT = 23
private const val MT_ARRIVE_DESTINATION_LEFT = 24
private const val MT_ARRIVE_DESTINATION_RIGHT = 25
private const val MT_U_TURN_WHEN_POSSIBLE = 26
private const val MT_ARRIVE_END_OF_DIRECTIONS = 27
private val MT_ROUNDABOUT_EXITS = 28..46
private const val MT_SHARP_LEFT_TURN = 47
private const val MT_SHARP_RIGHT_TURN = 48
private const val MT_SLIGHT_LEFT_TURN = 49
private const val MT_SLIGHT_RIGHT_TURN = 50
private const val MT_CHANGE_HIGHWAY = 51
private const val MT_CHANGE_HIGHWAY_LEFT = 52
private const val MT_CHANGE_HIGHWAY_RIGHT = 53

private val RAMPS = setOf(MT_OFF_RAMP, MT_ON_RAMP, MT_HIGHWAY_OFF_RAMP_LEFT, MT_HIGHWAY_OFF_RAMP_RIGHT)

/** ManeuverMapper.map with the angle present or [UNKNOWN_ANGLE]: the HUD's main element and direction. */
private fun bapManeuver(type: Int, angle: Int, junction: Int, side: Int): Pair<Int, Int> {
    when (type) {
        NOT_SET -> return NO_SYMBOL to DIR_STRAIGHT
        MT_NO_TURN, MT_FOLLOW_ROAD, MT_ENTER_FERRY, MT_CHANGE_FERRY -> return FOLLOW_STREET to DIR_STRAIGHT
        MT_ARRIVE_END_OF_NAVIGATION, MT_ARRIVE_AT_DESTINATION, MT_ARRIVE_END_OF_DIRECTIONS -> return ARRIVED to DIR_STRAIGHT
        MT_CHANGE_HIGHWAY_LEFT -> return CHANGE_LANE to DIR_LEFT
        MT_CHANGE_HIGHWAY_RIGHT -> return CHANGE_LANE to DIR_RIGHT
        MT_START_ROUTE, MT_EXIT_FERRY, MT_CHANGE_HIGHWAY -> return TURN to directionFromTurnAngle(angle)
        MT_U_TURN_AT_ROUNDABOUT -> return (if (side == LEFT_TRAFFIC) ROUNDABOUT_TRS_LEFT else ROUNDABOUT_TRS_RIGHT) to DIR_UTURN
        MT_ENTER_ROUNDABOUT -> return TURN to (if (side == LEFT_TRAFFIC) DIR_LEFT else DIR_RIGHT)
    }
    // The MHI3 gate: at a roundabout only its exits map; any other junction type maps nothing.
    if (junction != INTERSECTION) {
        if (junction != ROUNDABOUT) return NO_INFO to DIR_STRAIGHT
        if (type == MT_EXIT_ROUNDABOUT) {
            return if (side == LEFT_TRAFFIC) EXIT_ROUNDABOUT_TRS_LEFT to DIR_LEFT else EXIT_ROUNDABOUT_TRS_RIGHT to DIR_RIGHT
        }
        if (type in MT_ROUNDABOUT_EXITS) {
            return (if (side == LEFT_TRAFFIC) ROUNDABOUT_TRS_LEFT else ROUNDABOUT_TRS_RIGHT) to directionFromAngle16(angle)
        }
        return NO_INFO to DIR_STRAIGHT
    }
    val (main, direction) = when (type) {
        MT_STRAIGHT_AHEAD -> TURN to DIR_STRAIGHT
        MT_LEFT_TURN -> TURN to DIR_LEFT
        MT_RIGHT_TURN -> TURN to DIR_RIGHT
        MT_SLIGHT_LEFT_TURN, MT_KEEP_LEFT -> TURN to DIR_SLIGHT_LEFT
        MT_SLIGHT_RIGHT_TURN, MT_KEEP_RIGHT -> TURN to DIR_SLIGHT_RIGHT
        MT_SHARP_LEFT_TURN -> TURN to DIR_SHARP_LEFT
        MT_SHARP_RIGHT_TURN -> TURN to DIR_SHARP_RIGHT
        MT_U_TURN, MT_START_ROUTE_WITH_U_TURN, MT_U_TURN_WHEN_POSSIBLE ->
            // The angle's sign when it has one, else away from the traffic side.
            UTURN to when {
                directional(angle) -> if (angle < 0) DIR_LEFT else DIR_RIGHT
                side == LEFT_TRAFFIC -> DIR_RIGHT
                else -> DIR_LEFT
            }
        MT_OFF_RAMP, MT_ON_RAMP -> {
            val left = if (directional(angle)) angle < 0 else side == LEFT_TRAFFIC
            TURN to if (left) DIR_SLIGHT_LEFT else DIR_SLIGHT_RIGHT
        }
        MT_HIGHWAY_OFF_RAMP_LEFT -> TURN to DIR_SLIGHT_LEFT
        MT_HIGHWAY_OFF_RAMP_RIGHT -> TURN to DIR_SLIGHT_RIGHT
        MT_ARRIVE_DESTINATION_LEFT -> ARRIVED to DIR_LEFT
        MT_ARRIVE_DESTINATION_RIGHT -> ARRIVED to DIR_RIGHT
        MT_LEFT_TURN_AT_END -> TURN to when {
            angle < 0 && angle >= -180 && angle >= -67 -> DIR_SLIGHT_LEFT
            angle < 0 && angle >= -180 && angle < -112 -> DIR_SHARP_LEFT
            else -> DIR_LEFT
        }
        MT_RIGHT_TURN_AT_END -> TURN to when {
            angle in 1..67 -> DIR_SLIGHT_RIGHT
            angle in 113..180 -> DIR_SHARP_RIGHT
            else -> DIR_RIGHT
        }
        else -> return NO_INFO to DIR_STRAIGHT
    }
    return main to dsiNavBapOverride(type, direction)
}

/** Zero is a real straight angle and the markers of a missing one have no side. */
private fun directional(angle: Int) = angle != 0 && angle != UNKNOWN_ANGLE && angle != -UNKNOWN_ANGLE

/** MHI3's DSINavBap coarsens the plain turns to straight, left, right or u-turn. */
private fun dsiNavBapOverride(type: Int, direction: Int): Int {
    val kept = type in RAMPS || type in setOf(
        MT_U_TURN, MT_START_ROUTE_WITH_U_TURN, MT_U_TURN_WHEN_POSSIBLE, MT_KEEP_LEFT, MT_KEEP_RIGHT,
        MT_SLIGHT_LEFT_TURN, MT_SLIGHT_RIGHT_TURN, MT_SHARP_LEFT_TURN, MT_SHARP_RIGHT_TURN, MT_LEFT_TURN_AT_END, MT_RIGHT_TURN_AT_END,
    )
    if (kept) return direction
    return when (direction) {
        DIR_SLIGHT_LEFT, DIR_LEFT, DIR_SHARP_LEFT -> DIR_LEFT
        DIR_SLIGHT_RIGHT, DIR_RIGHT, DIR_SHARP_RIGHT -> DIR_RIGHT
        DIR_UTURN -> DIR_UTURN
        else -> DIR_STRAIGHT
    }
}

/** A roundabout's exit as the nearest of 17 bins from -180 to 180 degrees, 22.5 apart. */
private fun directionFromAngle16(angle: Int): Int {
    if (angle == UNKNOWN_ANGLE || angle == -UNKNOWN_ANGLE) return DIR_STRAIGHT
    val a = angle.coerceIn(-180, 180)
    val index = kotlin.math.floor((a + 180) / 22.5 + 0.5).toInt().coerceIn(0, 16)
    return DIR16[index]
}

private val DIR16 = intArrayOf(128, 112, 96, 80, 64, 48, 32, 16, 0, 240, 224, 208, 192, 176, 160, 144, 128)

/** A generic maneuver's direction: the nearest of the TURN directions, 45 degrees apart. */
private fun directionFromTurnAngle(angle: Int): Int {
    if (angle == UNKNOWN_ANGLE || angle == -UNKNOWN_ANGLE || angle == 0) return DIR_STRAIGHT
    if (angle < -180) return DIR_LEFT
    if (angle > 180) return DIR_RIGHT
    val magnitude = kotlin.math.abs(angle)
    return when {
        magnitude <= 22 -> DIR_STRAIGHT
        magnitude <= 67 -> if (angle < 0) DIR_SLIGHT_LEFT else DIR_SLIGHT_RIGHT
        magnitude <= 112 -> if (angle < 0) DIR_LEFT else DIR_RIGHT
        else -> if (angle < 0) DIR_SHARP_LEFT else DIR_SHARP_RIGHT
    }
}

// --- RendererMapper: the HUD's semantics plus the iPhone's geometry ---

/** A BAP direction as signed half-degrees, negative to the left. */
private fun directionHalfDegrees(direction: Int): Int {
    val d = direction and 255
    if (d == 128) return 360
    return if (d < 128) -(d * 45 / 16) else (256 - d) * 45 / 16
}

private fun fromBap(main: Int, direction: Int, side: Int): RendererManeuver {
    val exit = directionHalfDegrees(direction)
    fun of(icon: Int, dir: Int = 0, angle: Int = exit, drivingSide: Int = side) =
        RendererManeuver(icon, dir, angle, drivingSide, emptyList(), false)
    return when (main) {
        FOLLOW_STREET -> of(RendererIcon.APPROACH)
        TURN -> of(RendererIcon.TURN)
        UTURN -> {
            val dir = if (direction == DIR_LEFT) -1 else 1
            of(RendererIcon.UTURN, dir, dir * 360)
        }
        CHANGE_LANE -> of(RendererIcon.LANE_CHANGE, if (direction == DIR_LEFT) -1 else 1, 0)
        ROUNDABOUT_TRS_LEFT, ROUNDABOUT_TRS_RIGHT ->
            of(RendererIcon.ROUNDABOUT, drivingSide = if (main == ROUNDABOUT_TRS_LEFT) LEFT_TRAFFIC else RIGHT_TRAFFIC)
        EXIT_ROUNDABOUT_TRS_LEFT, EXIT_ROUNDABOUT_TRS_RIGHT -> {
            val left = main == EXIT_ROUNDABOUT_TRS_LEFT
            val dir = if (left) -1 else 1
            of(RendererIcon.ROUNDABOUT_EXIT, dir, dir * 90, if (left) LEFT_TRAFFIC else RIGHT_TRAFFIC)
        }
        ARRIVED -> of(RendererIcon.ARRIVED, if (direction == DIR_LEFT) -1 else if (direction == DIR_RIGHT) 1 else 0, 0)
        else -> of(RendererIcon.NONE, angle = 0)
    }
}

/**
 * The exact angle where it agrees with the HUD's side, full roundabout rings with the raw exit, and the original
 * roads, including the exit (the HUD's own list leaves it out and quantises the rest).
 */
private fun withGeometry(bap: RendererManeuver, type: Int, angle: Int?, roads: List<Int>): RendererManeuver {
    var out = bap
    val valid = angle != null && angle in -180..180
    if (out.icon != RendererIcon.NONE && (type == MT_ENTER_ROUNDABOUT || type == MT_EXIT_ROUNDABOUT || type in MT_ROUNDABOUT_EXITS)) {
        // Only a real angle may snap to a road; a missing one must not be moved onto a neighbouring exit.
        out = out.copy(icon = RendererIcon.ROUNDABOUT, direction = 0, exitAngle = if (valid) angle!! * 2 else 0, snapToRoad = valid)
    } else if (out.icon == RendererIcon.TURN && type !in RAMPS && valid) {
        val generic = type == MT_START_ROUTE || type == MT_EXIT_FERRY || type == MT_CHANGE_HIGHWAY
        if (generic || (out.exitAngle < 0 && angle!! < 0) || (out.exitAngle > 0 && angle!! > 0)) out = out.copy(exitAngle = angle!! * 2)
    }
    if (out.icon == RendererIcon.APPROACH || out.icon == RendererIcon.TURN || out.icon == RendererIcon.ROUNDABOUT) {
        out = out.copy(junctionAngles = roads.filter { it in -180..180 }.take(RendererManeuver.MAX_ROADS).map { it * 2 })
    }
    // A single-bend turn off a highway needs the road it leaves drawn as well.
    if (out.icon == RendererIcon.TURN && (type == MT_OFF_RAMP || type == MT_HIGHWAY_OFF_RAMP_LEFT || type == MT_HIGHWAY_OFF_RAMP_RIGHT) && 0 !in out.junctionAngles) {
        out = out.copy(junctionAngles = out.junctionAngles.take(RendererManeuver.MAX_ROADS - 1) + 0)
    }
    return out
}
