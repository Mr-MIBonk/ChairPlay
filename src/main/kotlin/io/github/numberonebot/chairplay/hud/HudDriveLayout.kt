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

/** The room between the wells: their free span less a gap at each side, within the HUD's margins top and bottom. */
fun driveStage(width: Float, height: Float): HudRect {
    val (from, to) = GaugeLayout.between(width, height)
    val gap = DRIVE_GAP * width
    return HudRect(from + gap, HUD_PAD * height, to - gap, height - HUD_PAD * height)
}

/**
 * The route's slot and the song's in the [driveStage]: the route over the song, half the height each when both are
 * there, the whole of it alone; [route] and [media] are how present each is, so the song moves down as the route comes.
 */
fun driveSlots(width: Float, height: Float, route: Float, media: Float): Pair<HudRect, HudRect> {
    val stage = driveStage(width, height)
    val half = stage.height / 2
    return HudRect(stage.left, stage.top, stage.right, stage.bottom - half * media) to
        HudRect(stage.left, stage.top + half * route, stage.right, stage.bottom)
}

/**
 * The song's [box] of an [h] high HUD, [driving] of the way to drive mode's: narrowed about its middle until the lines
 * beside the cover take a column [DRIVE_TEXT] covers wide, a squarer block that fits the room between the wells larger.
 */
fun driveMediaBox(box: HudBox, h: Float, driving: Float): HudBox {
    if (driving <= 0f) return box
    val middle = (box.left + box.right) / 2
    val narrow = box.cover * (1 + DRIVE_TEXT) + MEDIA_GAP * h
    val width = lerp(box.right - box.left, minOf(narrow, box.right - box.left), driving)
    return box.copy(left = middle - width / 2, right = middle + width / 2)
}

/** The scale that fits a block of [width] x [height] into [slot], keeping its shape; never above 1. */
fun fitScale(width: Float, height: Float, slot: HudRect): Float = minOf(slot.width / width, slot.height / height, 1f)

/** The gap between a well and the HUD's blocks, in the HUD's width. */
private const val DRIVE_GAP = 0.01f

/** The song's lines in drive mode, in its cover's width. */
private const val DRIVE_TEXT = 1.2f
