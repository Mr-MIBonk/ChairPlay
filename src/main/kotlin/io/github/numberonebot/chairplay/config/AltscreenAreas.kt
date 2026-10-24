package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea

/**
 * altscreen.experiment.areas: the AltScreen's view areas, rectangles of its stream the iPhone can be told to draw in
 * without reconnecting: the whole frame, its left half and its right half. Each takes the AltScreen's safe area clipped
 * to it, as DiPlay builds them.
 */
fun altscreenViewAreas(width: Int, height: Int): List<AirPlayViewArea> {
    val half = width / 2 / 8 * 8
    return listOf(AirPlayViewArea(width, height), AirPlayViewArea(half, height), AirPlayViewArea(width - half, height, originX = half))
}

/**
 * updateViewArea for the AltScreen: DiPlay's AirPlayInfoPlist.viewAreaCommand, which it sends for the main screen
 * alone, with the AltScreen's display. The iPhone acted on the main screen's only with the animation and the adjacent
 * areas; whether it moves the AltScreen at all is what the experiment is for.
 */
fun altscreenViewAreaCommand(index: Int, count: Int): Map<String, Any?> = linkedMapOf(
    "type" to "updateViewArea",
    "params" to linkedMapOf(
        "uuid" to AirPlayInfoPlist.ALT_UUID,
        "viewAreaIndex" to index,
        "animationDurationMillis" to VIEW_AREA_ANIMATION_MILLIS,
        "adjacentViewAreas" to (0 until count).filter { it != index },
    ),
)

/**
 * Moves CarPlay on the AltScreen to a view area: [send] the session's updateViewArea, then [showView], which asks for
 * the AltScreen's view again with showUI. Seen on an iPhone 15 Pro Max (AirPlay 870.14): after updateViewArea alone the
 * view stays blurred, mid-way through its move, and once asked for again it is drawn in the new area, the rest of the
 * frame near black. [count] is how many areas the connection declared.
 */
class AltscreenAreaMove(
    private val count: () -> Int,
    private val send: (Map<String, Any?>) -> Boolean,
    private val showView: () -> Unit,
) {
    /** False when the iPhone was not sent the move. */
    operator fun invoke(index: Int): Boolean {
        if (!send(altscreenViewAreaCommand(index, count()))) return false
        showView()
        return true
    }
}

private const val VIEW_AREA_ANIMATION_MILLIS = 300
