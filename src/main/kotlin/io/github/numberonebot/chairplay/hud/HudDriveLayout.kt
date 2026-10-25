package io.github.numberonebot.chairplay.hud

import io.github.numberonebot.chairplay.drive.GaugeLayout

// The HUD in drive mode: the route and the song keep to the room between the GPS car's wells.

/** A rectangle of the HUD. */
data class HudRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
}

/**
 * How small the small wells are, in the large ones' size: as small as leaves the room between them the song's own
 * width, so the song shows between them as it does without them; no smaller than a third.
 */
fun smallWellScale(width: Float, height: Float): Float {
    val song = mediaBlockWidth(mediaBox(width, height), height)
    val edge = (width - song) / 2 - GaugeLayout.margin(height)
    return ((edge - GaugeLayout.margin(height)) / (2 * GaugeLayout.radius(width, height))).coerceIn(1f / 3, 1f)
}

/**
 * The room between the wells: their free span less a gap at each side as wide as the wells keep from the window's
 * edges, within the HUD's margins top and bottom.
 */
fun driveStage(width: Float, height: Float, smallness: Float = 0f): HudRect {
    val (from, to) = GaugeLayout.between(width, height, smallness, smallWellScale(width, height))
    val gap = GaugeLayout.margin(height)
    return HudRect(from + gap, HUD_PAD * height, to - gap, height - HUD_PAD * height)
}

/**
 * The route's slot and the song's in the [driveStage]: the song over the route, two of the wells' margins apart and
 * as tall as each other when both are there, the whole of it alone; [route] and [media] are how far the other has made
 * room for each (HudMotionFrame.mediaPlace and routePlace), so the song moves up as the route comes.
 */
fun driveSlots(width: Float, height: Float, route: Float, media: Float, smallness: Float = 0f): Pair<HudRect, HudRect> {
    val stage = driveStage(width, height, smallness)
    val gap = 2 * GaugeLayout.margin(height)
    // Each gives up half the room and half the gap as the other comes.
    val share = (stage.height + gap) / 2
    return HudRect(stage.left, stage.top + share * media, stage.right, stage.bottom) to
        HudRect(stage.left, stage.top, stage.right, stage.bottom - share * route)
}

/** The scale that fits a block of [width] x [height] into [slot], keeping its shape; never above 1. */
fun fitScale(width: Float, height: Float, slot: HudRect): Float = minOf(slot.width / width, slot.height / height, 1f)