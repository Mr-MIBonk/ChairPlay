package io.github.numberonebot.chairplay.hud.maneuver

import io.github.numberonebot.chairplay.data.ManeuverType

/**
 * How far the arrow has filled toward its maneuver (protocol.h, CMD_PROGRESS): [level] sixteenths still to go,
 * 16 far and 0 at the maneuver, and [state] one of [QUIET], [FILL], [BLINK_LOW] and [BLINK_HIGH]; ChairPlay sends
 * only the first two.
 */
data class ArrowProgress(val level: Int, val state: Int) {
    companion object {
        /** The quiet white arrow, no progress shown. */
        const val QUIET = 0
        const val FILL = 1
        /** The renderer's blink phases, dark and lit, which the MHI2Q bridge sends near a maneuver. */
        const val BLINK_LOW = 2
        const val BLINK_HIGH = 3
        val OFF = ArrowProgress(0, QUIET)
    }
}

// The MHI2Q bridge's distance policy (BAPBridge): the HUD prepares a maneuver 1.5 km ahead in town and 3 km on a
// highway, and the bar covers the last 15 % of that, or the step to the maneuver when it is shorter. A step over 2 km
// counts as a highway. Unlike the bridge, nothing blinks: the arrow fills over the whole bar and is all blue at the
// maneuver, and the next one starts from its white arrow.
private const val CITY_PREPARE_METERS = 1_500L
private const val HIGHWAY_PREPARE_METERS = 3_000L
private const val HIGHWAY_STEP_METERS = 2_000L
private const val BAR_PERCENT_OF_PREPARE = 15

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

/** The arrow's progress [distanceMeters] before a maneuver [stepMeters] after the previous one: white until the bar. */
fun arrowProgress(distanceMeters: Long?, stepMeters: Long?, typeCode: Int?): ArrowProgress {
    val bar = barMeters(stepMeters, typeCode)
    if (distanceMeters == null || distanceMeters <= 0 || distanceMeters > bar) return ArrowProgress.OFF
    val percent = (distanceMeters * 100 / bar).toInt().coerceIn(0, 100)
    return ArrowProgress(percent * 16 / 100, ArrowProgress.FILL)
}
