package io.github.numberonebot.chairplay.hud

// Where the HUD's song and route go. Each keeps one layout, round the HUD's middle, and every animation only moves and
// scales it: beside the other, and between the GPS car's wells in drive mode.

/** Where a block laid out round the HUD's middle goes: its middle at [x], [y], scaled by [scale]. */
data class HudPlace(val x: Float, val y: Float, val scale: Float) {
    /** [t] of the way from this place to [other]: moved and scaled together. */
    fun toward(other: HudPlace, t: Float) = HudPlace(lerp(x, other.x, t), lerp(y, other.y, t), lerp(scale, other.scale, t))
}

/**
 * The song's place in a [width] x [height] HUD: [place] of the way from the middle into its column beside the route
 * (HudMotionFrame.mediaPlace), and [driving] of the way into its slot between the wells, [smallness] small. Between
 * the wells it goes the same [place] of the way from its place alone to its place over the route, moved and scaled
 * at once.
 */
fun songPlacement(width: Float, height: Float, place: Float, driving: Float = 0f, smallness: Float = 0f): HudPlace {
    val box = mediaBox(width, height)
    val blockWidth = mediaBlockWidth(box, height)
    val pad = HUD_SIDE * width
    val left = width / 2 + pad / 2
    val right = width - pad
    val beside = HudPlace((left + right) / 2, height / 2, minOf(MEDIA_BESIDE, (right - left) / blockWidth))
    val normal = HudPlace(width / 2, height / 2, 1f).toward(beside, place)
    if (driving <= 0f) return normal
    val slot = { route: Float -> slotPlace(driveSlots(width, height, route, 0f, smallness).second, blockWidth, box.cover) }
    return normal.toward(slot(0f).toward(slot(1f), place), driving)
}

/**
 * The route's place, for its block of [blockWidth] x [blockHeight] with a picture [viewHeight] tall, laid out as beside
 * the song, in its column ([routeBox]): [place] of the way from the middle, where it grows until its picture is as tall
 * as the HUD's maneuver ([routeAloneScale]), into that column (HudMotionFrame.routePlace), and [driving] of the way into
 * its slot between the wells, as the song does there.
 */
fun routePlacement(
    width: Float, height: Float, place: Float, blockWidth: Float, blockHeight: Float, viewHeight: Float,
    driving: Float = 0f, smallness: Float = 0f,
): HudPlace {
    val column = routeBox(width, height, 1f)
    val beside = HudPlace((column.left + column.right) / 2, height / 2, 1f)
    val alone = HudPlace(width / 2, height / 2, routeAloneScale(width, height, blockWidth, viewHeight))
    val normal = alone.toward(beside, place)
    if (driving <= 0f) return normal
    val slot = { media: Float -> slotPlace(driveSlots(width, height, 0f, media, smallness).first, blockWidth, blockHeight) }
    return normal.toward(slot(0f).toward(slot(1f), place), driving)
}

/** How much the route grows alone: its picture as tall as the HUD's maneuver, within the HUD's margins. */
fun routeAloneScale(width: Float, height: Float, blockWidth: Float, viewHeight: Float): Float =
    minOf(MANEUVER_HEIGHT * height / viewHeight, width * (1 - 2 * HUD_SIDE) / blockWidth)

/** A block of [width] x [height] in the middle of [slot], fitted into it. */
private fun slotPlace(slot: HudRect, width: Float, height: Float) = HudPlace(slot.centerX, slot.centerY, fitScale(width, height, slot))
