package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.ManeuverType

/**
 * How far the arrow has filled toward its maneuver (protocol.h, CMD_PROGRESS): [level] sixteenths still to go,
 * 16 far and 0 at the maneuver, and [state] one of [QUIET], [FILL], [BLINK_LOW] and [BLINK_HIGH].
 */
data class ArrowProgress(val level: Int, val state: Int) {
    companion object {
        /** The quiet white arrow, no progress shown. */
        const val QUIET = 0
        const val FILL = 1
        /** The blink's dark phase. */
        const val BLINK_LOW = 2
        /** The blink's lit phase: the whole arrow blue. */
        const val BLINK_HIGH = 3
        val OFF = ArrowProgress(0, QUIET)

        /** The blink's phase length; the renderer only eases between the phases it is sent. */
        const val BLINK_MILLIS = 600L
    }
}

// The MHI2Q bridge's distance policy (BAPBridge): the HUD prepares a maneuver 1.5 km ahead in town and 3 km on a
// highway; the bar covers the last 15 % of that, or the step to the maneuver when it is shorter, and the arrow blinks
// in its last 20 %. A step over 2 km counts as a highway.
private const val CITY_PREPARE_METERS = 1_500L
private const val HIGHWAY_PREPARE_METERS = 3_000L
private const val HIGHWAY_STEP_METERS = 2_000L
private const val BAR_PERCENT_OF_PREPARE = 15
private const val BLINK_PERCENT = 20

private val HIGHWAY_TYPES = setOf(
    ManeuverType.OFF_RAMP, ManeuverType.ON_RAMP, ManeuverType.HIGHWAY_OFF_RAMP_LEFT, ManeuverType.HIGHWAY_OFF_RAMP_RIGHT,
    ManeuverType.CHANGE_HIGHWAY, ManeuverType.CHANGE_HIGHWAY_LEFT, ManeuverType.CHANGE_HIGHWAY_RIGHT,
).map { it.code }.toSet()

/** The distance the bar runs over, by the step [stepMeters] to the maneuver of type [typeCode]. */
private fun barMeters(stepMeters: Long?, typeCode: Int?): Long {
    val highway = if (stepMeters != null && stepMeters > 0) stepMeters > HIGHWAY_STEP_METERS else typeCode in HIGHWAY_TYPES
    val cap = (if (highway) HIGHWAY_PREPARE_METERS else CITY_PREPARE_METERS) * BAR_PERCENT_OF_PREPARE / 100
    // Google Maps sends no step length: the bar then covers the cap too.
    return if (stepMeters == null || stepMeters <= 0 || stepMeters > cap) cap else stepMeters
}

/** The percentage of the bar still to drive, or null outside the bar. */
private fun percentLeft(distanceMeters: Long?, stepMeters: Long?, typeCode: Int?): Int? {
    val bar = barMeters(stepMeters, typeCode)
    if (distanceMeters == null || distanceMeters <= 0 || distanceMeters > bar) return null
    return (distanceMeters * 100 / bar).toInt().coerceIn(0, 100)
}

/**
 * The arrow's progress [distanceMeters] before a maneuver [stepMeters] after the previous one, at [nowMillis]: the
 * blink's phase follows the clock, lit first, as the MHI2Q bridge's 600 ms blink worker sends it.
 */
fun arrowProgress(distanceMeters: Long?, stepMeters: Long?, typeCode: Int?, nowMillis: Long): ArrowProgress {
    val percent = percentLeft(distanceMeters, stepMeters, typeCode) ?: return ArrowProgress.OFF
    if (percent < BLINK_PERCENT) {
        val lit = Math.floorMod(nowMillis / ArrowProgress.BLINK_MILLIS, 2L) == 0L
        return if (lit) ArrowProgress(16, ArrowProgress.BLINK_HIGH) else ArrowProgress(0, ArrowProgress.BLINK_LOW)
    }
    return ArrowProgress(percent * 16 / 100, ArrowProgress.FILL)
}

/** When [arrowProgress] changes next with the same distance (the blink's next phase), or null when it does not. */
fun nextProgressChange(distanceMeters: Long?, stepMeters: Long?, typeCode: Int?, nowMillis: Long): Long? {
    val percent = percentLeft(distanceMeters, stepMeters, typeCode) ?: return null
    if (percent >= BLINK_PERCENT) return null
    return (Math.floorDiv(nowMillis, ArrowProgress.BLINK_MILLIS) + 1) * ArrowProgress.BLINK_MILLIS
}
