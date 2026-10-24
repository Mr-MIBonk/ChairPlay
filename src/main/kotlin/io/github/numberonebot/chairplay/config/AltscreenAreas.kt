package io.github.numberonebot.chairplay.config

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.AirPlayViewArea

/**
 * altscreen.experiment.areas: the AltScreen's view areas, rectangles of its stream the iPhone can be told to draw in
 * without reconnecting, laid out for an 8:3 cluster: the whole frame; five eighths at the left or the right, beside a
 * dial; three eighths at the left, the middle or the right, between dials or in a side panel; all of the full height.
 * A width keeps to a multiple of 8, as DiPlay's streams do. Each takes the AltScreen's safe area clipped to it, as
 * DiPlay builds them.
 */
fun altscreenViewAreas(width: Int, height: Int): List<AirPlayViewArea> {
    val five = width * 5 / 8 / 8 * 8
    val three = width * 3 / 8 / 8 * 8
    return listOf(
        AirPlayViewArea(width, height),
        AirPlayViewArea(five, height), AirPlayViewArea(five, height, originX = width - five),
        AirPlayViewArea(three, height), AirPlayViewArea(three, height, originX = (width - three) / 4 * 2),
        AirPlayViewArea(three, height, originX = width - three),
    )
}

/** The AltScreen's views the iPhone draws, each of which may have a part of the frame of its own; the HUD is ChairPlay's. */
val AREA_VIEWS = listOf(AltscreenContent.MAP, AltscreenContent.MAP_CARD, AltscreenContent.CARD)

/** altscreen.<view>.area: the view's part of the frame, "left top width height" in pixels. */
fun areaKey(view: AltscreenContent) = "altscreen.${view.key}.area"

/** altscreen.<view>.safe: the margins inside the view's part, "top right bottom left" in pixels. */
fun safeKey(view: AltscreenContent) = "altscreen.${view.key}.safe"

private fun fourNumbers(text: String): List<Int>? {
    val parts = text.trim().split(Regex("\\s+"))
    if (parts.size != 4) return null
    return parts.map { it.toIntOrNull() ?: return null }
}

/** What is wrong with [text] as a view's part of a [width]x[height] frame, as a sentence for the screen; null for nothing. */
fun areaProblem(text: String, width: Int, height: Int): String? {
    val (x, y, w, h) = fourNumbers(text)?.takeIf { (x, y, w, h) -> x >= 0 && y >= 0 && w > 0 && h > 0 }
        ?: return "Enter the left, top, width, and height in pixels, such as 0 0 1200 720"
    return if (x + w > width || y + h > height) "Keep the area inside the ${width}x$height frame" else null
}

/** What is wrong with [text] as the margins inside a part [width] by [height], as a sentence; null for nothing. */
fun safeProblem(text: String, width: Int, height: Int): String? {
    val (top, right, bottom, left) = fourNumbers(text)?.takeIf { parts -> parts.all { it >= 0 } }
        ?: return "Enter the top, right, bottom, and left margins in pixels, such as 0 0 0 0"
    return if (top + bottom >= height || left + right >= width) "Leave some of the area inside the margins" else null
}

/**
 * The AltScreen's view areas for its views' own parts of a [width]x[height] frame (altscreen.<view>.area and .safe,
 * read through [value]): the whole frame first, for the views without, then one for each view with either, its
 * margins counted inside it and given as insets from the whole frame, as /info takes them. [areas] is null when no
 * view has a part of its own.
 */
class AltscreenViewParts(width: Int, height: Int, value: (String) -> String?) {
    private val index = mutableMapOf<AltscreenContent, Int>()
    val areas: List<AirPlayViewArea>?

    init {
        val list = mutableListOf(AirPlayViewArea(width, height))
        for (view in AREA_VIEWS) {
            val area = value(areaKey(view))?.trim()?.ifEmpty { null }
            val safe = value(safeKey(view))?.trim()?.ifEmpty { null }
            if (area == null && safe == null) continue
            area?.let { areaProblem(it, width, height) }?.let { throw IllegalArgumentException("${areaKey(view)}: $it, not $area") }
            val (x, y, w, h) = area?.let(::fourNumbers) ?: listOf(0, 0, width, height)
            safe?.let { safeProblem(it, w, h) }?.let { throw IllegalArgumentException("${safeKey(view)}: $it, not $safe") }
            val insets = safe?.let(::fourNumbers)?.let { (top, right, bottom, left) ->
                AirPlayInsets(top = y + top, bottom = height - y - h + bottom, left = x + left, right = width - x - w + right)
            }
            index[view] = list.size
            list += AirPlayViewArea(w, h, originX = x, originY = y, safeArea = insets)
        }
        areas = list.takeIf { it.size > 1 }
    }

    /** The area [content] shows in: its own, or the whole frame. */
    fun indexOf(content: AltscreenContent): Int = index[content] ?: 0
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
 * the AltScreen's view again with showUI once the move is over ([AREA_MOVE_SETTLE_MILLIS]). Seen on an iPhone 15 Pro Max (AirPlay 870.14): after updateViewArea alone the
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

/**
 * How long after updateViewArea the AltScreen's view is asked for again. Asked for 2 ms after, it stayed blurred: the
 * move's new codec configuration comes about 300 ms after the command, for its 300 ms; asked for a second after, the
 * view was drawn in the new area.
 */
const val AREA_MOVE_SETTLE_MILLIS = 1_000L

private const val VIEW_AREA_ANIMATION_MILLIS = 300
