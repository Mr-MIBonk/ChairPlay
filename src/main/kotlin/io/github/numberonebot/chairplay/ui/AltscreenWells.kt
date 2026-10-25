package io.github.numberonebot.chairplay.ui

import io.github.numberonebot.chairplay.drive.gaugeWells

/**
 * The virtual car's wells in the AltScreen's frame, for the page to draw them in a custom area's rectangle: from the
 * settings as saved ([values]), none while the AltScreen is off.
 */
internal fun altscreenWellsState(values: Map<String, String>): List<Map<String, Float>> {
    if (values["altscreen.enabled"]?.trim() != "true") return emptyList()
    val (width, height) = altscreenFrameSize(values)
    val small = values["gps.gauges"]?.trim() == "small"
    return gaugeWells(width, height, small).map { mapOf("x" to it.x, "y" to it.y, "radius" to it.radius) }
}
